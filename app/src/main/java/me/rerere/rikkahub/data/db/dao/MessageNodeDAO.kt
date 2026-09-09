package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    // 使用与 messages 相同的 JSON 编码，保守保留所有分支中出现的 URL。
    @Query("SELECT EXISTS(SELECT 1 FROM message_node WHERE instr(messages, :encodedFileUrl) > 0)")
    suspend fun hasFileReference(encodedFileUrl: String): Boolean

    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: MessageNodeEntity)

    @Update
    suspend fun update(node: MessageNodeEntity)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    // 使用 @RawQuery 绕过 Room 编译期校验，以便使用 json_each() 虚拟表
    @RawQuery
    suspend fun getTokenStatsRaw(query: SupportSQLiteQuery): MessageTokenStats

    @RawQuery
    suspend fun getConversationTokenStatsRaw(query: SupportSQLiteQuery): ConversationTokenStats

    @RawQuery
    suspend fun getMessageCountPerDayRaw(query: SupportSQLiteQuery): List<MessageDayCount>
}

data class MessageTokenStats(
    val totalMessages: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

/**
 * 单个对话的 token 累计（v297）。
 *
 * 口径说明（写在这里是为了让界面与统计页别再各算一套）：
 * - 每条消息优先取 `cumulativeUsage`（本次生成所有请求相加的真实消耗），
 *   没有该字段的老消息退回 `usage`（水位口径，会偏小，但总比没有好）；
 * - reasoningTokens 是 completionTokens 的**子集**（各家服务商都把思考算进输出计费），
 *   只作单列展示，绝不能再加进总数；
 * - 不含起标题 / 生成建议 / 压缩历史 / 翻译这些后台调用的消耗，也不含子代理的消耗
 *   （它们不落在本对话的消息上；界面必须如实标注，不能让用户拿这个数去跟账单对质）。
 */
data class ConversationTokenStats(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val usageMessages: Int = 0,
)

data class MessageDayCount(val day: String, val count: Int)

// SQLite json_each() 展开 messages JSON 数组，json_extract() 提取 Token 字段并聚合
private val TOKEN_STATS_SQL = SimpleSQLiteQuery(
    "SELECT COUNT(*) AS totalMessages, " +
        "COALESCE(SUM(CAST(${usageFieldSql("promptTokens")} AS INTEGER)), 0) AS promptTokens, " +
        "COALESCE(SUM(CAST(${usageFieldSql("completionTokens")} AS INTEGER)), 0) AS completionTokens, " +
        "COALESCE(SUM(CAST(${usageFieldSql("cachedTokens")} AS INTEGER)), 0) AS cachedTokens " +
        "FROM message_node mn, json_each(mn.messages) j"
)

/**
 * 单条消息的取数口径（v297）：有 cumulativeUsage 就用它（本次生成所有请求相加的真实消耗），
 * 老消息没这个字段就退回 usage（水位口径，会偏小，但总比没有好）。
 *
 * 全 App 统计页与本对话统计共用这一个函数，两处口径不会再各算一套。
 */
private fun usageFieldSql(field: String): String =
    "COALESCE(json_extract(j.value, '\$.cumulativeUsage.$field'), json_extract(j.value, '\$.usage.$field'))"

private fun sumOfUsageField(field: String): String =
    "COALESCE(SUM(CAST(${usageFieldSql(field)} AS INTEGER)), 0)"

// v297：本对话累计。conversation_id 走绑定参数，不做字符串拼接
private val CONVERSATION_TOKEN_STATS_SQL: String =
    "SELECT ${sumOfUsageField("promptTokens")} AS promptTokens, " +
        "${sumOfUsageField("completionTokens")} AS completionTokens, " +
        "${sumOfUsageField("cachedTokens")} AS cachedTokens, " +
        "${sumOfUsageField("reasoningTokens")} AS reasoningTokens, " +
        // 三项任一 > 0 就算「这条消息有用量」：只报分项不报 total 的服务商也要被认出来
        "COALESCE(SUM(CASE WHEN ${usageFieldSql("promptTokens")} > 0 OR ${usageFieldSql("completionTokens")} > 0 OR ${usageFieldSql("totalTokens")} > 0 THEN 1 ELSE 0 END), 0) AS usageMessages " +
        "FROM message_node mn, json_each(mn.messages) j " +
        "WHERE mn.conversation_id = ?"

suspend fun MessageNodeDAO.getTokenStats(): MessageTokenStats = getTokenStatsRaw(TOKEN_STATS_SQL)

suspend fun MessageNodeDAO.getConversationTokenStats(conversationId: String): ConversationTokenStats =
    getConversationTokenStatsRaw(
        SimpleSQLiteQuery(CONVERSATION_TOKEN_STATS_SQL, arrayOf(conversationId))
    )

// 按用户消息的 createdAt 字段（LocalDateTime ISO 字符串前10位即日期）统计每日消息数
suspend fun MessageNodeDAO.getMessageCountPerDay(startDate: String): List<MessageDayCount> =
    getMessageCountPerDayRaw(
        SimpleSQLiteQuery(
            "SELECT substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day, " +
                "COUNT(*) AS count " +
                "FROM message_node mn, json_each(mn.messages) j " +
                "WHERE json_extract(j.value, '$.role') = 'user' " +
                "AND json_extract(j.value, '$.createdAt') >= ? " +
                "GROUP BY day",
            arrayOf(startDate)
        )
    )

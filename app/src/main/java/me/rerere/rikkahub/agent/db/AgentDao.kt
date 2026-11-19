package me.rerere.rikkahub.agent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentThreadDao {

    @Insert
    suspend fun insertThread(thread: AgentThreadEntity)

    @Update
    suspend fun updateThread(thread: AgentThreadEntity)

    @Query("SELECT * FROM agent_threads WHERE id = :threadId")
    suspend fun thread(threadId: String): AgentThreadEntity?

    @Query("SELECT * FROM agent_threads WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun threadsFlow(conversationId: String): Flow<List<AgentThreadEntity>>

    // v222：STOPPING 也是活动态，必须计入并发额度，否则「停止中」的线程会被当成已释放，
    // 导致同时运行的子代理超过用户设置的上限。
    // v236：WAITING_AUTO_RETRY（退避等待）同理 —— 它几秒后还会发请求，必须占额度。
    @Query(
        "SELECT COUNT(*) FROM agent_threads " +
            "WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'STOPPING', 'WAITING_AUTO_RETRY')"
    )
    suspend fun activeCount(): Int

    /**
     * v222：改为 REPLACE 冲突策略。
     *
     * 原因（v221 真实缺陷）：流式生成时每来一个数据块就 insert 一条全新消息，
     * 一次子代理运行会往库里塞几百上千条内容层层递增的重复记录，
     * 界面因此完全看不清子代理在干什么。
     * 现在同一条模型消息用稳定 id 覆盖写入，界面看到的是一条实时增长的消息。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: AgentMessageEntity)

    @Query("SELECT * FROM agent_messages WHERE thread_id = :threadId ORDER BY created_at ASC")
    fun messagesFlow(threadId: String): Flow<List<AgentMessageEntity>>

    @Query(
        "UPDATE agent_threads SET status = 'INTERRUPTED', finished_at = :now " +
            "WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'STOPPING', 'WAITING_AUTO_RETRY')"
    )
    suspend fun markInterruptedOnBoot(now: Long)

    @Query("SELECT * FROM agent_messages WHERE thread_id = :threadId ORDER BY created_at ASC")
    suspend fun messages(threadId: String): List<AgentMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: AgentEventEntity)

    /**
     * v251：普通事件流必须排除续跑检查点 —— 检查点是隐藏的内部原文，
     * 不是可见阶段事件，界面不得展示。
     *
     * v1032：TOKEN_USAGE 同样属于内部记账事件，也必须排除。
     * 不排除的话它会混进详情页的「工具调用轨迹（N 项动作）」把计数虚报，
     * 还会被 agentPhaseText 当成最近一次阶段读出来（界面写着写着变成「TOKEN_USAGE」）。
     */
    @Query(
        "SELECT * FROM agent_events WHERE thread_id = :threadId " +
            "AND type NOT IN ('RESUME_CHECKPOINT', 'TOKEN_USAGE') ORDER BY created_at ASC"
    )
    fun eventsFlow(threadId: String): Flow<List<AgentEventEntity>>

    /**
     * v1032：某段对话下所有子代理的 token 用量事件。
     *
     * 为什么按 conversation 而不是按 thread 查：聊天页的活动面板要一次列出多条线程，
     * 每条线程单独建一个 Flow 会变成 N 份订阅；这里一条 Flow 全覆盖，
     * 而且 agent_events 里 TOKEN_USAGE 每种尝试只有一行（稳定 id + REPLACE），量很小。
     * 用 Room 的 Flow 而不是自己攒内存状态，是为了让「内存实时值」和「重开App后的值」
     * 是同一份数据 —— 两处口径不一致是最难查的一类毛病。
     */
    @Query(
        "SELECT * FROM agent_events WHERE type = 'TOKEN_USAGE' " +
            "AND thread_id IN (SELECT id FROM agent_threads WHERE conversation_id = :conversationId) " +
            "ORDER BY created_at ASC"
    )
    fun tokenUsageEventsFlow(conversationId: String): Flow<List<AgentEventEntity>>

    /**
     * v251：读取该线程最近一次续跑检查点（未裁剪的完整原文）。
     * 检查点按稳定 id 覆盖写入 agent_events 表，因此按 created_at 取最新一条即可。
     */
    @Query(
        "SELECT detail FROM agent_events WHERE thread_id = :threadId " +
            "AND type = 'RESUME_CHECKPOINT' ORDER BY created_at DESC LIMIT 1"
    )
    suspend fun resumeCheckpoint(threadId: String): String?
}

package me.rerere.rikkahub.agent.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v218：独立 Agent 数据库实体。
 * 时间统一存 epoch millis（Long），避免额外 TypeConverter。
 */

@Entity(tableName = "agent_threads")
data class AgentThreadEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("parent_message_node_id")
    val parentMessageNodeId: String?,
    @ColumnInfo("task")
    val task: String,
    @ColumnInfo("role")
    val role: String,
    @ColumnInfo("model_id")
    val modelId: String?,
    @ColumnInfo("workspace_id")
    val workspaceId: String?,
    @ColumnInfo("context_summary")
    val contextSummary: String?,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("report_json")
    val reportJson: String?,
    @ColumnInfo("error")
    val error: String?,
    /** v222：Provider 结束原因（用于识别输出被截断） */
    @ColumnInfo("finish_reason")
    val finishReason: String? = null,
    /** v222：是否被截断（0/1） */
    @ColumnInfo("truncated", defaultValue = "0")
    val truncated: Boolean = false,
    /** v222：已续跑次数 */
    @ColumnInfo("resume_count", defaultValue = "0")
    val resumeCount: Int = 0,
    /**
     * v236：可写文件白名单，换行分隔；null/空 = 只读。
     *
     * 用换行分隔而不是 JSON：路径本身不含换行，解析零依赖、迁移默认值也简单（空串）。
     */
    @ColumnInfo("writable_paths", defaultValue = "")
    val writablePaths: String? = null,
    /** v236：本次实际使用的模型 id（备用模型链切换后会变） */
    @ColumnInfo("active_model_id")
    val activeModelId: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("started_at")
    val startedAt: Long?,
    @ColumnInfo("finished_at")
    val finishedAt: Long?,
)

@Entity(tableName = "agent_messages")
data class AgentMessageEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("thread_id")
    val threadId: String,
    @ColumnInfo("role")
    val role: String,
    @ColumnInfo("content")
    val content: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)

@Entity(tableName = "agent_events")
data class AgentEventEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("thread_id")
    val threadId: String,
    @ColumnInfo("type")
    val type: String,
    @ColumnInfo("detail")
    val detail: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)

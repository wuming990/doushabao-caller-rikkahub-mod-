package me.rerere.rikkahub.agent.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.agent.db.AgentDatabase
import me.rerere.rikkahub.agent.db.AgentMessageEntity
import me.rerere.rikkahub.agent.db.AgentEventEntity
import me.rerere.rikkahub.agent.db.AgentThreadEntity
import me.rerere.rikkahub.agent.model.AGENT_EVENT_TYPE_RESUME_CHECKPOINT
import me.rerere.rikkahub.agent.model.AgentMessage
import me.rerere.rikkahub.agent.model.AgentEvent
import me.rerere.rikkahub.agent.model.AgentRole
import me.rerere.rikkahub.agent.model.AgentThread
import me.rerere.rikkahub.agent.model.AgentThreadStatus
import java.time.Instant

/**
 * v218：代理线程仓库接口。
 * 生产实现走独立 AgentDatabase；测试/演示用 InMemory 实现。
 */
interface AgentThreadRepository {
    suspend fun insertThread(thread: AgentThread)
    suspend fun updateThread(thread: AgentThread)
    suspend fun thread(threadId: String): AgentThread?
    fun threadsFlow(conversationId: String): Flow<List<AgentThread>>
    suspend fun activeCount(): Int

    suspend fun insertMessage(message: AgentMessage)
    fun messagesFlow(threadId: String): Flow<List<AgentMessage>>
    suspend fun messages(threadId: String): List<AgentMessage>
    suspend fun markInterruptedOnBoot()

    suspend fun insertEvent(event: AgentEvent)
    fun eventsFlow(threadId: String): Flow<List<AgentEvent>>

    /**
     * v251：读取该线程最近一次续跑检查点（未裁剪的完整原文）。
     * 没有检查点（旧线程 / 从未运行过）返回 null。
     */
    suspend fun resumeCheckpoint(threadId: String): String?
}

/** 生产实现：独立 AgentDatabase（Room） */
class RoomAgentThreadRepository(
    private val database: AgentDatabase,
) : AgentThreadRepository {

    private val dao = database.agentThreadDao()

    override suspend fun insertThread(thread: AgentThread) =
        dao.insertThread(thread.toEntity())

    override suspend fun updateThread(thread: AgentThread) =
        dao.updateThread(thread.toEntity())

    override suspend fun thread(threadId: String): AgentThread? =
        dao.thread(threadId)?.toModel()

    override fun threadsFlow(conversationId: String): Flow<List<AgentThread>> =
        dao.threadsFlow(conversationId).map { list -> list.map { it.toModel() } }

    override suspend fun activeCount(): Int = dao.activeCount()

    override suspend fun insertMessage(message: AgentMessage) =
        dao.insertMessage(
            AgentMessageEntity(
                id = message.id,
                threadId = message.threadId,
                role = message.role,
                content = message.content,
                createdAt = message.createdAt.toEpochMilli(),
            )
        )

    override fun messagesFlow(threadId: String): Flow<List<AgentMessage>> =
        dao.messagesFlow(threadId).map { list ->
            list.map {
                AgentMessage(
                    id = it.id,
                    threadId = it.threadId,
                    role = it.role,
                    content = it.content,
                    createdAt = Instant.ofEpochMilli(it.createdAt),
                )
            }
        }

    override suspend fun messages(threadId: String): List<AgentMessage> =
        dao.messages(threadId).map {
            AgentMessage(
                id = it.id,
                threadId = it.threadId,
                role = it.role,
                content = it.content,
                createdAt = Instant.ofEpochMilli(it.createdAt),
            )
        }

    override suspend fun markInterruptedOnBoot() =
        dao.markInterruptedOnBoot(Instant.now().toEpochMilli())

    override suspend fun insertEvent(event: AgentEvent) =
        dao.insertEvent(
            AgentEventEntity(
                id = event.id,
                threadId = event.threadId,
                type = event.type,
                detail = event.detail,
                createdAt = event.createdAt.toEpochMilli(),
            )
        )

    override fun eventsFlow(threadId: String): Flow<List<AgentEvent>> =
        dao.eventsFlow(threadId).map { list ->
            list.map {
                AgentEvent(
                    id = it.id,
                    threadId = it.threadId,
                    type = it.type,
                    detail = it.detail,
                    createdAt = Instant.ofEpochMilli(it.createdAt),
                )
            }
        }

    override suspend fun resumeCheckpoint(threadId: String): String? =
        dao.resumeCheckpoint(threadId)
}

/** 内存实现：单元测试与无数据库演示用 */
class InMemoryAgentThreadRepository : AgentThreadRepository {

    private val threads = MutableStateFlow<List<AgentThread>>(emptyList())
    private val messages = MutableStateFlow<List<AgentMessage>>(emptyList())
    private val events = MutableStateFlow<List<AgentEvent>>(emptyList())

    override suspend fun insertThread(thread: AgentThread) {
        threads.update { it + thread }
    }

    override suspend fun updateThread(thread: AgentThread) {
        threads.update { list -> list.map { if (it.id == thread.id) thread else it } }
    }

    override suspend fun thread(threadId: String): AgentThread? =
        threads.value.firstOrNull { it.id == threadId }

    override fun threadsFlow(conversationId: String): Flow<List<AgentThread>> =
        threads.map { list -> list.filter { it.conversationId == conversationId } }

    override suspend fun activeCount(): Int =
        threads.value.count { !it.status.isTerminal }

    override suspend fun insertMessage(message: AgentMessage) {
        // v222：与生产实现（REPLACE 冲突策略）保持一致的 upsert 语义。
        // 流式生成会用同一个稳定 id 反复写同一条消息，必须覆盖而不是不断追加。
        messages.update { list ->
            val index = list.indexOfFirst { it.id == message.id }
            if (index >= 0) list.toMutableList().also { it[index] = message } else list + message
        }
    }

    override fun messagesFlow(threadId: String): Flow<List<AgentMessage>> =
        messages.map { list -> list.filter { it.threadId == threadId } }

    override suspend fun messages(threadId: String): List<AgentMessage> =
        messages.value.filter { it.threadId == threadId }

    override suspend fun markInterruptedOnBoot() {
        // 内存实现不跨进程，无需处理
    }

    override suspend fun insertEvent(event: AgentEvent) {
        // v222：同 insertMessage，按 id upsert，与生产实现语义一致
        events.update { list ->
            val index = list.indexOfFirst { it.id == event.id }
            if (index >= 0) list.toMutableList().also { it[index] = event } else list + event
        }
    }

    override fun eventsFlow(threadId: String): Flow<List<AgentEvent>> =
        events.map { list ->
            list.filter { it.threadId == threadId && it.type != AGENT_EVENT_TYPE_RESUME_CHECKPOINT }
        }

    override suspend fun resumeCheckpoint(threadId: String): String? =
        events.value
            .filter { it.threadId == threadId && it.type == AGENT_EVENT_TYPE_RESUME_CHECKPOINT }
            .maxByOrNull { it.createdAt }
            ?.detail
}

private fun AgentThread.toEntity() = AgentThreadEntity(
    id = id,
    conversationId = conversationId,
    parentMessageNodeId = parentMessageNodeId,
    task = task,
    role = role.name,
    modelId = modelId,
    workspaceId = workspaceId,
    contextSummary = contextSummary,
    status = status.name,
    reportJson = reportJson,
    error = error,
    finishReason = finishReason,
    truncated = truncated,
    resumeCount = resumeCount,
    writablePaths = encodeWritablePaths(writablePaths),
    activeModelId = activeModelId,
    createdAt = createdAt.toEpochMilli(),
    startedAt = startedAt?.toEpochMilli(),
    finishedAt = finishedAt?.toEpochMilli(),
)

private fun AgentThreadEntity.toModel() = AgentThread(
    id = id,
    conversationId = conversationId,
    parentMessageNodeId = parentMessageNodeId,
    task = task,
    role = runCatching { AgentRole.valueOf(role) }.getOrDefault(AgentRole.DEFAULT),
    modelId = modelId,
    workspaceId = workspaceId,
    contextSummary = contextSummary,
    status = runCatching { AgentThreadStatus.valueOf(status) }.getOrDefault(AgentThreadStatus.INTERRUPTED),
    reportJson = reportJson,
    error = error,
    finishReason = finishReason,
    truncated = truncated,
    resumeCount = resumeCount,
    writablePaths = decodeWritablePaths(writablePaths),
    activeModelId = activeModelId,
    createdAt = Instant.ofEpochMilli(createdAt),
    startedAt = startedAt?.let { Instant.ofEpochMilli(it) },
    finishedAt = finishedAt?.let { Instant.ofEpochMilli(it) },
)

/**
 * v236：白名单编解码（换行分隔）。
 *
 * 路径里不会有换行，所以这是无歧义的；空列表存空串而不是 null，
 * 与迁移的 DEFAULT '' 保持一致，避免出现「null 和空串两种空」的歧义。
 */
internal fun encodeWritablePaths(paths: List<String>): String =
    paths.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

internal fun decodeWritablePaths(raw: String?): List<String> =
    raw?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * v1024：手动压缩归应用级scope、按发起对话独立管理。
 * 隐藏弹窗/销毁观察页面不会取消任务；同一对话重开时附着原任务，不重复请求。
 * 这里只管理任务生命周期，不认识模型、提示词、摘要算法和对话数据库。
 */
internal class ManualCompressionTaskRegistry(private val scope: CoroutineScope) {
    private val lock = Any()
    private val mutableJobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    val jobs: StateFlow<Map<String, Job>> = mutableJobs.asStateFlow()

    /** v1024：删除原对话时只取消它的压缩，不影响其他对话。 */
    fun cancel(conversationId: String): Job? {
        val job = synchronized(lock) { mutableJobs.value[conversationId] }
        job?.cancel()
        return job
    }

    fun launch(conversationId: String, task: suspend () -> Unit): Job = synchronized(lock) {
        mutableJobs.value[conversationId]?.takeIf { !it.isCompleted }?.let { return@synchronized it }
        // 先登记再启动；Unconfined/快速失败也不会留下“已结束但仍显示运行”的幽灵任务。
        val job = scope.launch(start = CoroutineStart.LAZY) { task() }
        mutableJobs.update { it + (conversationId to job) }
        job.invokeOnCompletion {
            synchronized(lock) {
                // 旧任务的收尾不能误删这次重开后新登记的任务。
                mutableJobs.update { current ->
                    if (current[conversationId] === job) current - conversationId else current
                }
            }
        }
        job.start()
        job
    }
}

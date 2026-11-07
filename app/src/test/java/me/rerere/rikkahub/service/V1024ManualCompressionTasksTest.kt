package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v1024：真实应用级任务管理行为；不调用模型、不写真实对话。UI接线检查另行明确标注。 */
class V1024ManualCompressionTasksTest {
    private fun registry(scope: CoroutineScope): Any = try {
        Class.forName("me.rerere.rikkahub.service.ManualCompressionTaskRegistry")
            .getDeclaredConstructor(CoroutineScope::class.java).newInstance(scope)
    } catch (_: ClassNotFoundException) {
        throw AssertionError("压缩仍只挂当前页面：缺少按原对话管理的应用级压缩任务")
    }
    private fun start(registry: Any, id: String, task: suspend () -> Unit): Job =
        registry.javaClass.getDeclaredMethod("launch", String::class.java, kotlin.jvm.functions.Function1::class.java)
            .invoke(registry, id, task) as Job
    @Suppress("UNCHECKED_CAST")
    private fun jobs(registry: Any): Map<String, Job> =
        (registry.javaClass.getDeclaredMethod("getJobs").invoke(registry) as StateFlow<Map<String, Job>>).value
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test fun `取消当前页面并不取消应用级原对话压缩`() = runBlocking {
        val app = scope(); val page = scope()
        try {
            val registry = registry(app); val gate = CompletableDeferred<Unit>(); var writes = 0
            lateinit var compression: Job
            val pageJob = page.launch {
                compression = start(registry, "A") { gate.await(); writes++ }
                awaitCancellation()
            }
            pageJob.cancelAndJoin()
            assertTrue(compression.isActive)
            assertSame(compression, jobs(registry)["A"])
            gate.complete(Unit); compression.join()
            assertEquals(1, writes)
            assertFalse(jobs(registry).containsKey("A"))
        } finally { page.cancel(); app.cancel() }
    }
    @Test fun `重复打开原对话压缩附着原任务不再请求第二遍`() = runBlocking {
        val app = scope()
        try {
            val registry = registry(app); val gate = CompletableDeferred<Unit>(); var calls = 0
            val first = start(registry, "A") { calls++; gate.await() }
            val second = start(registry, "A") { calls += 100 }
            assertSame(first, second)
            assertEquals(1, calls)
            gate.complete(Unit); first.join()
        } finally { app.cancel() }
    }
    @Test fun `A慢压缩不会阻止B对话单独运行`() = runBlocking {
        val app = scope()
        try {
            val registry = registry(app); val gate = CompletableDeferred<Unit>(); val writes = mutableListOf<String>()
            val a = start(registry, "A") { gate.await(); writes.add("A") }
            val b = start(registry, "B") { writes.add("B") }
            b.join()
            assertTrue(a.isActive)
            assertEquals(listOf("B"), writes)
            gate.complete(Unit); a.join()
            assertEquals(listOf("B", "A"), writes)
        } finally { app.cancel() }
    }
    @Test fun `取消A压缩只停A不会误停B`() = runBlocking {
        val app = scope()
        try {
            val registry = registry(app); val gate = CompletableDeferred<Unit>(); var writes = 0
            val a = start(registry, "A") { gate.await(); writes += 100 }
            val b = start(registry, "B") { gate.await(); writes++ }
            a.cancelAndJoin()
            assertTrue(b.isActive)
            assertFalse(jobs(registry).containsKey("A"))
            gate.complete(Unit); b.join()
            assertEquals(1, writes)
        } finally { app.cancel() }
    }
    @Test fun `已完成任务不妨碍用户下次重新压缩`() = runBlocking {
        val app = scope()
        try {
            val registry = registry(app); var calls = 0
            val first = start(registry, "A") { calls++ }; first.join()
            val second = start(registry, "A") { calls++ }; second.join()
            assertNotSame(first, second)
            assertEquals(2, calls)
            assertTrue(jobs(registry).isEmpty())
        } finally { app.cancel() }
    }
    @Test fun `任务取消时保留取消语义且不提交后半段`() = runBlocking {
        val app = scope()
        try {
            val registry = registry(app); val gate = CompletableDeferred<Unit>(); var written = false
            val job = start(registry, "A") { gate.await(); written = true }
            job.cancelAndJoin(); gate.complete(Unit)
            assertTrue(job.isCancelled)
            assertFalse(written)
            assertTrue(jobs(registry).isEmpty())
        } finally { app.cancel() }
    }

    private fun source(name: String): String {
        var root: File? = File(System.getProperty("user.dir")).absoluteFile
        while (root != null && !File(root, "settings.gradle.kts").isFile) root = root.parentFile
        assertNotNull(root)
        val file = File(root, "app/src/main/java/me/rerere/rikkahub/$name.kt")
        return if (file.exists()) file.readText() else ""
    }
    @Test fun `UI接线守卫 隐藏压缩弹窗不等于取消任务`() {
        val dialog = source("ui/components/ai/CompressContextDialog")
        assertTrue("压缩中必须提供后台继续入口", dialog.contains("chat_page_compress_background"))
        assertTrue("返回键或点外部能收起弹窗", dialog.contains("onDismissRequest = onDismiss"))
        assertTrue("原明确取消仍需取消任务", dialog.contains("currentJob?.cancel()"))
        assertTrue("重开时能接回现有任务", dialog.contains("runningJob: Job?"))
    }
    @Test fun `UI接线守卫 两种手动压缩都改由应用级任务接管而非页面自己launch`() {
        val vm = source("ui/pages/chat/ChatVM")
        val start = vm.indexOf("fun handleCompressContext(")
        val end = vm.indexOf("suspend fun forkMessage", start)
        assertTrue(start >= 0 && end > start)
        val handlers = vm.substring(start, end)
        assertTrue(handlers.contains("startManualCompression("))
        assertTrue(handlers.contains("startManualClassicCompression("))
        assertFalse("不能切走页面就取消压缩", handlers.contains("return viewModelScope.launch"))
        val service = source("service/ChatService")
        assertTrue(service.contains("ManualCompressionTaskRegistry(appScope)"))
        assertTrue(service.contains("getManualCompressionJobFlow("))
        assertTrue("后台失败须归原对话", service.contains("conversationId = conversationId"))
    }
    @Test fun `接线守卫 取消压缩不能留下独立启动的子任务继续扣费`() {
        val service = source("service/ChatService")
        val start = service.indexOf("fun startManualCompression(")
        val end = service.indexOf("// 生成完成流", start)
        assertTrue(start >= 0 && end > start)
        val manual = service.substring(start, end)
        assertTrue(manual.contains("sessionManager.withSession(conversationId)"))
        assertFalse(manual.contains("launchWithConversationReference("))
        assertFalse(manual.contains("appScope.launch"))
        assertTrue(manual.contains("catch (e: CancellationException)"))
    }
    @Test fun `接线守卫 原对话进度可见且智能写回检查源内容未变`() {
        val service = source("service/ChatService")
        assertTrue(service.contains("expectedManualSource = source"))
        assertTrue(service.contains("commitManualSmartCompression("))
        assertTrue(source("service/ManualSmartCompressionCommit").contains("latest.messageNodes == source.messageNodes"))
        val page = source("ui/pages/chat/ChatPage")
        assertTrue(page.contains("ManualCompressionNotice(manualCompressionJob)"))
        assertTrue(page.contains("runningCompressionJob = manualCompressionJob"))
        assertTrue(source("ui/components/ai/FilesPicker").contains("runningJob = runningCompressionJob"))
    }

    @Test fun `删除源对话的取消入口会停原任务且不会停其他对话`() = runBlocking {
        val app = scope()
        try {
            val registry = registry(app); val gate = CompletableDeferred<Unit>()
            val a = start(registry, "A") { gate.await() }
            val b = start(registry, "B") { gate.await() }
            val cancel = try { registry.javaClass.getDeclaredMethod("cancel", String::class.java) }
                catch (_: NoSuchMethodException) { throw AssertionError("缺少删除原对话时取消压缩的入口") }
            cancel.invoke(registry, "A"); a.join()
            assertTrue(a.isCancelled)
            assertTrue(b.isActive)
            assertFalse(jobs(registry).containsKey("A"))
            gate.complete(Unit); b.join()
        } finally { app.cancel() }
    }
    @Test fun `接线守卫 不同任务各自捕获提示词而非重新读共享默认值`() {
        val service = source("service/ChatService")
        assertTrue("手动发起时应传本任务提示词", service.contains("manualPromptOverride = taskPrompt"))
        assertTrue("压缩请求优先本任务提示词，默认设置只给其他旧调用", service.contains("manualPromptOverride ?: settings.compressPrompt"))
        val smart = service.substringAfter("fun startManualCompression(")
            .substringBefore("suspend fun cancelManualCompression(")
        assertTrue("智能任务也要等真实设置加载，不能捕获dummy快照", smart.contains("val taskSettings = settingsStore.awaitLoaded()"))
    }

    @Test fun `删除的聊天历史和批量入口都要先取消原压缩再删除`() {
        val service = source("service/ChatService")
        val single = service.substringAfter("suspend fun deleteConversation(conversationId: Uuid)")
            .substringBefore("suspend fun restoreConversation")
        assertTrue(single.contains("cancelManualCompression(conversationId)"))
        assertTrue(single.indexOf("cancelManualCompression(conversationId)") < single.indexOf("conversationRepo.deleteConversation"))
        val batch = service.substringAfter("suspend fun deleteConversationsOfAssistant(assistantId: Uuid)")
            .substringBefore("suspend fun getConversation")
        assertTrue(batch.contains("cancelManualCompression(it.id)"))
        val history = source("ui/pages/history/HistoryVM")
        val deleting = history.substringAfter("fun deleteConversation(conversation: Conversation)")
            .substringBefore("fun deleteAllConversations")
        assertTrue(deleting.contains("chatService.cancelManualCompression(conversation.id)"))
        assertTrue(deleting.indexOf("cancelManualCompression") < deleting.indexOf("conversationRepo.deleteConversation"))
        val web = source("web/routes/ConversationRoutes")
        val deleteRoute = web.substringAfter("""delete("/{id}")""")
        assertTrue(deleteRoute.contains("chatService.cancelManualCompression(uuid)"))
        assertTrue(deleteRoute.indexOf("cancelManualCompression(uuid)") < deleteRoute.indexOf("deleteConversation(conversation)"))
    }

    @Test fun `接线守卫 经典后台任务也捕获自身设置不被后来切换对话改变`() {
        val service = source("service/ChatService")
        val manual = service.substringAfter("fun startManualClassicCompression(").substringBefore("// 生成完成流")
        assertTrue(manual.contains("val taskSettings = settingsStore.awaitLoaded()"))
        assertTrue(manual.contains("manualSettingsOverride = taskSettings"))
        val classic = service.substringAfter("suspend fun compressConversationClassic(")
            .substringBefore("suspend fun autoCompressForkConversation(")
        assertTrue(classic.contains("manualSettingsOverride ?: settingsStore.awaitLoaded()"))
    }

}

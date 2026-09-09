package me.rerere.rikkahub.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * 真机场景模拟：
 * - 官方工作区里“列表能看到但打不开”的坏条目自动跳过，其余继续复制；
 * - 写二改侧失败=真正失败，但半成品保留（不再自动删除），供续传/丢弃；
 * - 取消=暂停：半成品保留，取消异常照常抛出；
 * - 续传：二改侧已存在且大小相同的文件不重传。
 */
private fun entry(
    id: String,
    name: String,
    dir: Boolean = false,
    size: Long? = null,
) = OfficialSourceEntry(
    documentId = id,
    name = name,
    sizeBytes = size,
    isDirectory = dir,
)

private fun targetWorkspace() = WorkspaceEntity(
    id = "ws-1",
    name = "官方 - workspace",
    root = "ws-1",
    createdAt = 1L,
    updatedAt = 1L,
)

private class FakeSource(
    private val children: Map<String, List<OfficialSourceEntry>>,
    private val fileContents: Map<String, String> = emptyMap(),
    private val openFailures: Map<String, Throwable> = emptyMap(),
    private val nullStreamNames: Set<String> = emptySet(),
    private val listFailures: Map<String, Throwable> = emptyMap(),
) : OfficialWorkspaceSource {
    override fun queryRoot(treeUri: android.net.Uri): OfficialSourceEntry? =
        entry("ws", "workspace", dir = true)

    override fun listChildren(treeUri: android.net.Uri?, parentDocumentId: String): List<OfficialSourceEntry> {
        listFailures[parentDocumentId]?.let { throw it }
        return children[parentDocumentId].orEmpty()
    }

    override fun openInputStream(treeUri: android.net.Uri?, entry: OfficialSourceEntry): InputStream? {
        openFailures[entry.name]?.let { throw it }
        if (entry.name in nullStreamNames) return null
        return fileContents[entry.name]?.let { ByteArrayInputStream(it.toByteArray()) }
    }
}

private class FakeDestination(
    private val importDelayMillis: Long = 0L,
    preloaded: Map<String, String> = emptyMap(),
) : OfficialWorkspaceDestination {
    val imported = mutableMapOf<String, String>()
    val importCalls = mutableListOf<String>()
    val directories = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    var importFailure: Throwable? = null

    init {
        imported.putAll(preloaded)
    }

    override suspend fun create(name: String): WorkspaceEntity = targetWorkspace().copy(name = name)

    override suspend fun get(id: String): WorkspaceEntity? =
        if (id == "ws-1") targetWorkspace() else null

    override suspend fun isNameTaken(name: String, excludeId: String?): Boolean = false

    override suspend fun ensureDirectory(id: String, area: WorkspaceStorageArea, path: String) {
        directories += path
    }

    override suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ) {
        if (importDelayMillis > 0) kotlinx.coroutines.delay(importDelayMillis)
        importFailure?.let { throw it }
        val path = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        importCalls += path
        imported[path] = inputStream.readBytes().decodeToString()
    }

    override suspend fun existingSize(id: String, path: String): Long? =
        imported[path]?.toByteArray()?.size?.toLong()

    override suspend fun delete(id: String) {
        deleted += id
    }
}

private fun testManager(
    source: FakeSource,
    destination: FakeDestination,
) = OfficialWorkspaceMigrationManager(source, destination) { true }

class OfficialWorkspaceMigrationSkipTest {
    @Test
    fun `dangling official file is skipped and the rest is copied`() = runBlocking {
        val source = FakeSource(
            children = mapOf(
                "ws" to listOf(
                    entry("d1", "docs", dir = true),
                    entry("f1", "ok.txt"),
                    entry("f2", "gpt_claude_plan_duo"),
                ),
                "d1" to listOf(entry("f3", "note.md")),
            ),
            fileContents = mapOf("ok.txt" to "hello", "note.md" to "note"),
            openFailures = mapOf(
                "gpt_claude_plan_duo" to
                    FileNotFoundException("open failed: ENOENT (No such file or directory)"),
            ),
        )
        val destination = FakeDestination()

        val result = testManager(source, destination).importIntoExisting(
            treeUri = null,
            sourceEntry = entry("ws", "workspace", dir = true),
            destinationWorkspace = targetWorkspace(),
            resume = false,
        )

        assertEquals(1, result.skipped.size)
        val skipped = result.skipped.single()
        assertEquals("gpt_claude_plan_duo", skipped.path)
        assertEquals("读取官方文件", skipped.operation)
        assertTrue(skipped.reason.contains("ENOENT"))
        assertEquals("hello", destination.imported["ok.txt"])
        assertEquals("note", destination.imported["docs/note.md"])
        assertTrue(destination.deleted.isEmpty())
    }

    @Test
    fun `folder listing failure skips subtree but copy completes`() = runBlocking {
        val source = FakeSource(
            children = mapOf(
                "ws" to listOf(
                    entry("d1", "docs", dir = true),
                    entry("f1", "ok.txt"),
                ),
            ),
            fileContents = mapOf("ok.txt" to "hello"),
            listFailures = mapOf(
                "d1" to FileNotFoundException("open failed: ENOENT (No such file or directory)"),
            ),
        )
        val destination = FakeDestination()

        val result = testManager(source, destination).importIntoExisting(
            treeUri = null,
            sourceEntry = entry("ws", "workspace", dir = true),
            destinationWorkspace = targetWorkspace(),
            resume = false,
        )

        assertEquals(1, result.skipped.size)
        assertEquals("docs", result.skipped.single().path)
        assertEquals("读取文件夹", result.skipped.single().operation)
        assertEquals("hello", destination.imported["ok.txt"])
    }

    @Test
    fun `null stream is skipped with readable reason`() = runBlocking {
        val source = FakeSource(
            children = mapOf("ws" to listOf(entry("f1", "ok.txt"), entry("f2", "ghost"))),
            fileContents = mapOf("ok.txt" to "hello"),
            nullStreamNames = setOf("ghost"),
        )
        val destination = FakeDestination()

        val result = testManager(source, destination).importIntoExisting(
            treeUri = null,
            sourceEntry = entry("ws", "workspace", dir = true),
            destinationWorkspace = targetWorkspace(),
            resume = false,
        )

        assertEquals(1, result.skipped.size)
        assertEquals("ghost", result.skipped.single().path)
        assertEquals("系统没有返回可读取的文件流", result.skipped.single().reason)
    }

    @Test
    fun `write failure is fatal but keeps the partial copy for resume`() = runBlocking {
        val source = FakeSource(
            children = mapOf("ws" to listOf(entry("f1", "ok.txt"))),
            fileContents = mapOf("ok.txt" to "hello"),
        )
        val destination = FakeDestination().apply { importFailure = IOException("disk full") }

        try {
            testManager(source, destination).importIntoExisting(
                treeUri = null,
                sourceEntry = entry("ws", "workspace", dir = true),
                destinationWorkspace = targetWorkspace(),
                resume = false,
            )
            fail("expected OfficialWorkspaceMigrationException")
        } catch (expected: OfficialWorkspaceMigrationException) {
            assertTrue(expected.message.orEmpty().contains("写入二改工作区"))
        }
        // 半成品保留：不自动删除，供“继续/丢弃”
        assertTrue(destination.deleted.isEmpty())
    }

    @Test
    fun `cancellation keeps the partial copy and propagates`() = runBlocking {
        val source = FakeSource(
            children = mapOf("ws" to listOf(entry("f1", "big.bin"))),
            fileContents = mapOf("big.bin" to "x"),
        )
        val destination = FakeDestination(importDelayMillis = 10_000)
        val job = launch {
            try {
                testManager(source, destination).importIntoExisting(
                    treeUri = null,
                    sourceEntry = entry("ws", "workspace", dir = true),
                    destinationWorkspace = targetWorkspace(),
                    resume = false,
                )
            } catch (expected: CancellationException) {
                // 取消必须把取消异常抛出去，让会话层记录“已暂停”
            }
        }
        delay(100)
        job.cancelAndJoin()
        assertTrue(destination.deleted.isEmpty())
    }

    @Test
    fun `resume skips files that already exist with same size`() = runBlocking {
        val source = FakeSource(
            children = mapOf(
                "ws" to listOf(
                    entry("d1", "docs", dir = true),
                    entry("f1", "ok.txt", size = 5L),
                ),
                "d1" to listOf(entry("f3", "note.md", size = 4L)),
            ),
            fileContents = mapOf("ok.txt" to "hello", "note.md" to "note"),
        )
        // 上次已复制 ok.txt（内容相同），note.md 没复制到
        val destination = FakeDestination(preloaded = mapOf("ok.txt" to "hello"))

        testManager(source, destination).importIntoExisting(
            treeUri = null,
            sourceEntry = entry("ws", "workspace", dir = true),
            destinationWorkspace = targetWorkspace(),
            resume = true,
        )

        assertEquals(listOf("docs/note.md"), destination.importCalls)
        assertEquals("note", destination.imported["docs/note.md"])
        assertEquals("hello", destination.imported["ok.txt"])
    }

    @Test
    fun `more than one hundred thousand legitimate folders are not rejected`() = runBlocking {
        val folderCount = 100_005
        val source = FakeSource(
            children = buildMap {
                put(
                    "ws",
                    List(folderCount) { index ->
                        entry("d-$index", "folder-$index", dir = true)
                    },
                )
            },
        )
        val destination = FakeDestination()
        var lastProgress: OfficialWorkspaceMigrationProgress? = null

        testManager(source, destination).importIntoExisting(
            treeUri = null,
            sourceEntry = entry("ws", "workspace", dir = true),
            destinationWorkspace = targetWorkspace(),
            resume = false,
            onProgress = { lastProgress = it },
        )

        assertEquals(folderCount, destination.directories.size)
        assertEquals(folderCount + 1, lastProgress?.processedDirectories)
    }

    @Test
    fun `skipped diagnostics are bounded while total count stays accurate`() = runBlocking {
        val failedFiles = 120
        val files = List(failedFiles) { index -> entry("f-$index", "missing-$index") }
        val source = FakeSource(
            children = mapOf("ws" to files),
            openFailures = files.associate { it.name to FileNotFoundException("missing") },
        )

        val result = testManager(source, FakeDestination()).importIntoExisting(
            treeUri = null,
            sourceEntry = entry("ws", "workspace", dir = true),
            destinationWorkspace = targetWorkspace(),
            resume = false,
        )

        assertEquals(failedFiles, result.skippedCount)
        assertEquals(50, result.skipped.size)
    }

}

package me.rerere.rikkahub.data.files

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 官方工作区复制用的轻量条目。
 * 故意不携带 Uri：复制引擎只依赖名字/目录/大小，
 * 这样单元测试可以安全模拟“列表里能看到但打不开”的坏条目。
 */
data class OfficialSourceEntry(
    val documentId: String,
    val name: String,
    val sizeBytes: Long?,
    val isDirectory: Boolean,
)

/** 读取侧：只读官方 Provider，永远不写官方数据。 */
interface OfficialWorkspaceSource {
    fun queryRoot(treeUri: Uri): OfficialSourceEntry?

    /** treeUri 必须传真实授权树地址（官方版只认树地址）；测试可传 null。 */
    fun listChildren(treeUri: Uri?, parentDocumentId: String): List<OfficialSourceEntry>
    fun openInputStream(treeUri: Uri?, entry: OfficialSourceEntry): InputStream?
}

/** 写入侧：只写本次指定的二改工作区。 */
interface OfficialWorkspaceDestination {
    suspend fun create(name: String): WorkspaceEntity
    suspend fun get(id: String): WorkspaceEntity?
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean
    suspend fun ensureDirectory(id: String, area: WorkspaceStorageArea, path: String)
    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    )
    /** 续传用：目标路径已有文件大小，不存在返回 null。 */
    suspend fun existingSize(id: String, path: String): Long?
    suspend fun delete(id: String)
}

/** 真实读取侧：把 Android SAF 访问适配成轻量条目。 */
class SharedStorageOfficialSource(
    private val manager: SharedStorageManager,
) : OfficialWorkspaceSource {
    override fun queryRoot(treeUri: Uri): OfficialSourceEntry? =
        manager.queryRoot(treeUri)?.toOfficialEntry()

    override fun listChildren(treeUri: Uri?, parentDocumentId: String): List<OfficialSourceEntry> =
        manager
            .listChildren(checkNotNull(treeUri) { "official tree uri missing" }, parentDocumentId)
            .map { it.toOfficialEntry() }

    override fun openInputStream(treeUri: Uri?, entry: OfficialSourceEntry): InputStream? =
        manager.openInputStream(
            manager.documentUri(
                checkNotNull(treeUri) { "official tree uri missing" },
                entry.documentId,
            )
        )

    private fun SharedStorageEntry.toOfficialEntry() = OfficialSourceEntry(
        documentId = documentId,
        name = name,
        sizeBytes = sizeBytes,
        isDirectory = isDirectory,
    )
}

/**
 * 真实写入侧：把二改工作区仓库适配成复制引擎需要的接口。
 *
 * 大迁移的热路径直接复用已经解析出的 WorkspaceManager/root，避免每个文件和目录都重复查询数据库、
 * 重建工作区目录。create/get 仍走 Repository，工作区元数据与原有逻辑保持一致。
 */
class WorkspaceRepositoryDestination(
    private val repository: WorkspaceRepository,
    private val manager: WorkspaceManager,
) : OfficialWorkspaceDestination {
    private val workspaceRoots = ConcurrentHashMap<String, String>()

    override suspend fun create(name: String): WorkspaceEntity =
        repository.create(name).also { workspaceRoots[it.id] = it.root }

    override suspend fun get(id: String): WorkspaceEntity? =
        repository.getById(id)?.also { workspaceRoots[it.id] = it.root }

    override suspend fun isNameTaken(name: String, excludeId: String?): Boolean =
        repository.isNameTaken(name, excludeId)

    override suspend fun ensureDirectory(id: String, area: WorkspaceStorageArea, path: String) {
        withContext(Dispatchers.IO) {
            manager.ensureDirectory(rootFor(id), path, area)
        }
    }

    override suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ) {
        withContext(Dispatchers.IO) {
            manager.importFile(rootFor(id), destinationPath, area, fileName, inputStream)
        }
    }

    override suspend fun existingSize(id: String, path: String): Long? =
        runCatching {
            withContext(Dispatchers.IO) {
                manager.fileSize(rootFor(id), path, WorkspaceStorageArea.FILES)
            }
        }.getOrNull()

    override suspend fun delete(id: String) {
        repository.delete(id)
        workspaceRoots.remove(id)
    }

    private suspend fun rootFor(id: String): String = workspaceRoots[id]
        ?: repository.getById(id)?.root?.also { workspaceRoots[id] = it }
        ?: error("Workspace not found: $id")
}

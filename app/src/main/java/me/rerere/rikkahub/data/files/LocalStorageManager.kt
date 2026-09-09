package me.rerere.rikkahub.data.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * 只读浏览手机共享存储全部目录。
 *
 * 依赖用户一次性开启的 MANAGE_EXTERNAL_STORAGE（“所有文件访问权限”，系统设置总开关）。
 * 本管理器不做任何写入/删除/重命名操作；浏览、打开、选择上传均由上层决定。
 */
class LocalStorageManager(
    private val context: Context,
) {
    /** Android 11+ 需要“所有文件访问权限”；Android 10 及以下无需额外开关。 */
    fun hasAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return Environment.isExternalStorageManager()
    }

    /** 跳转到当前 App 的“所有文件访问权限”设置页。 */
    fun allFilesAccessIntent(): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        }
        return Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    /**
     * 可浏览的普通存储卷根目录：主存储 + 真实可移除存储卡。
     *
     * 厂商可能把隐私空间作为额外的虚拟/用户存储卷报告。此类卷不能作为普通手机存储
     * 直接显示在最外层，因此不在这里暴露。
     */
    fun storageRoots(): List<File> {
        val roots = mutableListOf<File>()
        val primary = Environment.getExternalStorageDirectory()
        if (primary.isDirectory) roots.add(primary)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            runCatching {
                for (volume: StorageVolume in storageManager.storageVolumes) {
                    if (!LocalStorageVisibilityPolicy.isOrdinaryStorageRoot(
                            isPrimary = volume.isPrimary,
                            isRemovable = volume.isRemovable,
                            isEmulated = volume.isEmulated,
                        )
                    ) {
                        continue
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val dir = volume.directory ?: continue
                        if (dir.isDirectory && dir.canonicalPath != primary.canonicalPath) {
                            roots.add(dir)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = volume.directory ?: continue
                        if (dir.isDirectory && dir.canonicalPath != primary.canonicalPath) {
                            roots.add(dir)
                        }
                    }
                }
            }
        }
        return roots.distinctBy { runCatching { it.canonicalPath }.getOrNull() ?: it.absolutePath }
    }

    /**
     * 常用位置：首页直接展示，不让用户理解内部路径。
     *
     * 不包含系统相册目录：厂商相册选择器会连带展示隐私空间相册名称，
     * 因此图片入口保留在聊天页的“图片”按钮，不放在这里。
     */
    fun commonPlaces(): List<LocalStoragePlace> {
        val primary = Environment.getExternalStorageDirectory()
        return listOf(
            LocalStoragePlace(LocalStoragePlaceKind.DOWNLOADS, File(primary, Environment.DIRECTORY_DOWNLOADS)),
            LocalStoragePlace(LocalStoragePlaceKind.DOCUMENTS, File(primary, Environment.DIRECTORY_DOCUMENTS)),
            LocalStoragePlace(LocalStoragePlaceKind.VIDEOS, File(primary, Environment.DIRECTORY_MOVIES)),
            LocalStoragePlace(LocalStoragePlaceKind.MUSIC, File(primary, Environment.DIRECTORY_MUSIC)),
        ).filter { it.directory.isDirectory }
    }

    /**
     * 列出目录内容（只读）。默认隐藏隐藏目录和 Android 系统目录；高级模式才显示。
     * 排序统一：文件夹永远在前，文件按“最后修改时间”新→旧。
     * 不可访问的项跳过。
     */
    fun list(dir: File, advanced: Boolean = false): List<LocalFileEntry> {
        if (!dir.isDirectory) return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        return children
            .asSequence()
            .filter { it.exists() }
            .filter { it.canRead() || it.isDirectory }
            .filter { LocalStorageVisibilityPolicy.isVisibleInBrowser(it.name, advanced) }
            .map { it.toEntry() }
            .sortedWith(
                compareBy<LocalFileEntry> { !it.isDirectory }
                    .thenByDescending { it.lastModified }
            )
            .toList()
    }

    /**
     * 首页搜索：在常用目录（下载/文档/视频/音乐）与手机存储根目录一层内，
     * 按文件名（忽略大小写）匹配。文件夹在前，文件按时间新→旧；按路径去重，限制条数防卡。
     */
    fun search(query: String, limit: Int = 200): List<LocalFileEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val roots = commonPlaces().map { it.directory } + storageRoots()
        val byPath = linkedMapOf<String, LocalFileEntry>()
        outer@ for (root in roots) {
            val children = root.listFiles() ?: continue
            for (child in children) {
                if (byPath.size >= limit) break@outer
                if (!child.exists()) continue
                if (!child.canRead() && !child.isDirectory) continue
                if (!LocalStorageVisibilityPolicy.isVisibleInBrowser(child.name, false)) continue
                if (child.name.contains(q, ignoreCase = true)) {
                    val key = runCatching { child.canonicalPath }.getOrElse { child.absolutePath }
                    if (!byPath.containsKey(key)) {
                        byPath[key] = child.toEntry()
                    }
                }
            }
        }
        return byPath.values
            .sortedWith(
                compareBy<LocalFileEntry> { !it.isDirectory }
                    .thenByDescending { it.lastModified }
            )
            .take(limit)
    }

    /**
     * 首页“文件和文档”入口使用：直接返回最近修改的可发送文件，打开即可见。
     *
     * 主路径查询系统媒体库（MediaStore）索引：系统已按修改时间建好索引，一次查询即可拿到最新文件，
     * 不需要逐层遍历文件夹，避免真机上几万个文件把面板卡死。
     * 媒体库缺失或被厂商裁剪时，退回“限时浅层扫描”兜底，保证仍能出结果。
     *
     * 只返回文件，不返回文件夹；图片继续由聊天页“+”里的图片入口负责，这里排除图片。
     */
    fun recentFiles(
        query: String = "",
        limit: Int = 200,
    ): List<LocalFileEntry> {
        val capped = limit.coerceAtLeast(1)
        val results = linkedMapOf<String, LocalFileEntry>()

        queryMediaStoreRecentFiles(query, capped).forEach { entry ->
            results[entry.path] = entry
        }

        // 媒体库没覆盖到时（未扫描/厂商裁剪），限时浅层扫描补齐，保证入口不为空。
        if (results.size < capped) {
            scanRecentFilesShallow(query, capped).forEach { entry ->
                results.putIfAbsent(entry.path, entry)
            }
        }

        return results.values
            .sortedByDescending { it.lastModified }
            .take(capped)
    }

    /** 查询系统媒体库索引，按修改时间新→旧返回可发送文件。 */
    private fun queryMediaStoreRecentFiles(query: String, limit: Int): List<LocalFileEntry> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
        // 图片在数据库层就排除：相册条目多且时间新，不先过滤会把列表挤满。
        val selectionParts = mutableListOf(
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"
        )
        val selectionArgs = mutableListOf<String>()
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            selectionParts += "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            selectionArgs += "%$trimmed%"
        }
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        val entries = mutableListOf<LocalFileEntry>()
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selectionParts.joinToString(" AND "),
                selectionArgs.toTypedArray().takeIf { it.isNotEmpty() },
                sortOrder,
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val sizeColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val modifiedColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val mimeColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                if (dataColumn < 0) return@use

                while (cursor.moveToNext() && entries.size < limit) {
                    val path = cursor.getString(dataColumn) ?: continue
                    if (!RecentFilesPolicy.isVisiblePath(path)) continue

                    val file = File(path)
                    if (!file.isFile || !file.canRead()) continue

                    val name = nameColumn.takeIf { it >= 0 }
                        ?.let { cursor.getString(it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: file.name
                    val mime = mimeColumn.takeIf { it >= 0 }?.let { cursor.getString(it) }
                    val kind = mime?.takeIf { it.isNotBlank() }
                        ?.let { fileKindOfMime(it) }
                        ?.takeIf { it != FileKind.OTHER }
                        ?: fileKindOf(name)
                    if (!RecentFilesPolicy.isSelectableRecentFile(name, isDirectory = false, kind = kind)) continue

                    val size = sizeColumn.takeIf { it >= 0 }
                        ?.let { cursor.getLong(it) }
                        ?.takeIf { it > 0L }
                        ?: file.length()
                    val modified = RecentFilesPolicy.normalizeModifiedMillis(
                        mediaStoreSeconds = modifiedColumn.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                        fileSystemMillis = file.lastModified(),
                    )

                    entries += LocalFileEntry(
                        path = file.absolutePath,
                        name = name,
                        isDirectory = false,
                        sizeBytes = size,
                        lastModified = modified,
                        kind = kind,
                    )
                }
            }
        }
        return entries
    }

    /**
     * 兜底扫描：只扫常用目录和存储根的浅层，并且带时间预算。
     * 目的是“有结果”，不是“扫全盘”，所以宁可少列也不能卡住面板。
     */
    private fun scanRecentFilesShallow(
        query: String,
        limit: Int,
        maxDepth: Int = 2,
        maxDirectories: Int = 400,
        budgetMillis: Long = 1_500L,
    ): List<LocalFileEntry> {
        val deadline = System.currentTimeMillis() + budgetMillis
        val roots = (commonPlaces().map { it.directory } + storageRoots())
            .distinctBy { root -> root.absolutePath }
        val pending = ArrayDeque<Pair<File, Int>>()
        roots.forEach { pending.addLast(it to 0) }
        val visited = mutableSetOf<String>()
        val results = mutableListOf<LocalFileEntry>()

        while (pending.isNotEmpty() && visited.size < maxDirectories) {
            if (System.currentTimeMillis() > deadline) break
            val (directory, depth) = pending.removeFirst()
            if (!visited.add(directory.absolutePath)) continue

            val children = directory.listFiles() ?: continue
            for (child in children) {
                val name = child.name
                if (!LocalStorageVisibilityPolicy.isVisibleInNormalBrowser(name)) continue
                if (child.isDirectory) {
                    if (depth < maxDepth) pending.addLast(child to depth + 1)
                    continue
                }
                if (!child.canRead()) continue
                val kind = fileKindOf(name)
                if (!RecentFilesPolicy.isSelectableRecentFile(name, isDirectory = false, kind = kind)) continue
                if (!RecentFilesPolicy.matchesQuery(name, query)) continue
                results += child.toEntry()
            }
        }

        return results
            .sortedByDescending { it.lastModified }
            .take(limit)
    }

    /** 生成系统应用打开文件的 Intent（经 FileProvider 临时授权，只读）。 */
    fun openFileIntent(file: File): Intent? {
        if (!file.isFile || !file.canRead()) return null
        return runCatching {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val mime = mimeOf(file)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }.getOrNull()
    }

    private fun File.toEntry(): LocalFileEntry = LocalFileEntry(
        path = absolutePath,
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else null,
        lastModified = lastModified(),
        kind = fileKindOf(name),
    )

    private fun mimeOf(file: File): String {
        val ext = file.extension.lowercase(Locale.getDefault())
        return ext.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }
}

data class LocalStoragePlace(
    val kind: LocalStoragePlaceKind,
    val directory: File,
)

enum class LocalStoragePlaceKind {
    DOWNLOADS,
    PICTURES,
    CAMERA,
    DOCUMENTS,
    VIDEOS,
    MUSIC,
}

data class LocalFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val lastModified: Long,
    val kind: FileKind = FileKind.OTHER,
)

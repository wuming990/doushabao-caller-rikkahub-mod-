package me.rerere.rikkahub.data.files

import java.util.Locale

/**
 * “文件和文档 → 最近文件”的可见性规则。
 *
 * 该入口的目标是打开即可见、点一下即发送，因此：
 * - 只显示文件，不显示文件夹；
 * - 不显示图片（图片走聊天页“+”里的图片入口，避免重复和相册隐私空间干扰）；
 * - 隐藏目录、Android 应用数据目录、厂商隐私空间等一律不出现，规则复用普通浏览器的可见性策略。
 *
 * 这里只做纯判断，不访问文件系统，便于单元测试锁定行为。
 */
internal object RecentFilesPolicy {
    /** 路径前缀里属于存储卷本身的段，不参与敏感目录判断。 */
    private val volumeHeadSegments = setOf(
        "storage",
        "emulated",
        "sdcard",
        "mnt",
        "self",
        "primary",
        "user",
    )

    /**
     * 路径上任意一级目录不可见时，该文件也不可见。
     *
     * 例如 /storage/emulated/0/Android/data/x/cache/a.txt 会因为 Android 段被拒绝，
     * /storage/emulated/0/.thumbnails/a.dat 会因为隐藏段被拒绝。
     */
    fun isVisiblePath(path: String): Boolean {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false

        var index = 0
        while (index < segments.size - 1) {
            val segment = segments[index]
            val isVolumeHead = segment.lowercase(Locale.ROOT) in volumeHeadSegments ||
                segment.all(Char::isDigit)
            if (!isVolumeHead) break
            index++
        }

        for (position in index until segments.size) {
            if (!LocalStorageVisibilityPolicy.isVisibleInNormalBrowser(segments[position])) {
                return false
            }
        }
        return true
    }

    /** 单条候选是否可以作为“最近文件”展示。 */
    fun isSelectableRecentFile(
        name: String,
        isDirectory: Boolean,
        kind: FileKind,
    ): Boolean {
        if (isDirectory) return false
        if (name.isBlank()) return false
        if (kind == FileKind.IMAGE) return false
        return LocalStorageVisibilityPolicy.isVisibleInNormalBrowser(name)
    }

    /** 文件名是否命中搜索词；空搜索词视为全部命中。 */
    fun matchesQuery(name: String, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        return name.contains(trimmed, ignoreCase = true)
    }

    /**
     * 媒体库的修改时间以秒为单位，且部分设备会返回 0。
     * 秒值换算成毫秒；无效值回退到文件系统时间，避免排序全部塌成同一时刻。
     */
    fun normalizeModifiedMillis(mediaStoreSeconds: Long, fileSystemMillis: Long): Long =
        if (mediaStoreSeconds > 0L) mediaStoreSeconds * 1000L else fileSystemMillis
}

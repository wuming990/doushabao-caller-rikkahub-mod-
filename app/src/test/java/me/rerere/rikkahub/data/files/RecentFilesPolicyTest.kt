package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * “文件和文档 → 最近文件”必须打开即可见：只列文件、排除图片，
 * 并且不能把隐藏目录、Android 应用数据目录和厂商隐私空间里的文件带出来。
 */
class RecentFilesPolicyTest {
    @Test
    fun `ordinary user files are visible`() {
        listOf(
            "/storage/emulated/0/Download/report.pdf",
            "/storage/emulated/0/Documents/notes.md",
            "/storage/emulated/0/Movies/clip.mp4",
            "/storage/emulated/0/Music/song.flac",
            "/storage/emulated/0/WeChat/file.docx",
            "/storage/1A2B-3C4D/Docs/table.xlsx",
        ).forEach { path ->
            assertTrue(path, RecentFilesPolicy.isVisiblePath(path))
        }
    }

    @Test
    fun `app data hidden and private space paths are excluded`() {
        listOf(
            "/storage/emulated/0/Android/data/com.foo/cache/a.txt",
            "/storage/emulated/0/Android/media/com.foo/b.txt",
            "/storage/emulated/0/.thumbnails/c.dat",
            "/storage/emulated/0/.hidden/d.pdf",
            "/storage/emulated/0/原子隐私系统/e.pdf",
            "/storage/emulated/0/保密柜/f.docx",
            "/storage/emulated/0/PrivacyBox/g.txt",
            "/storage/emulated/0/LOST.DIR/h.bin",
        ).forEach { path ->
            assertFalse(path, RecentFilesPolicy.isVisiblePath(path))
        }
    }

    @Test
    fun `only non-image files are selectable`() {
        assertTrue(
            RecentFilesPolicy.isSelectableRecentFile("report.pdf", isDirectory = false, kind = FileKind.PDF)
        )
        assertTrue(
            RecentFilesPolicy.isSelectableRecentFile("clip.mp4", isDirectory = false, kind = FileKind.VIDEO)
        )
        assertFalse(
            RecentFilesPolicy.isSelectableRecentFile("Download", isDirectory = true, kind = FileKind.OTHER)
        )
        assertFalse(
            RecentFilesPolicy.isSelectableRecentFile("photo.jpg", isDirectory = false, kind = FileKind.IMAGE)
        )
        assertFalse(
            RecentFilesPolicy.isSelectableRecentFile(".secret.pdf", isDirectory = false, kind = FileKind.PDF)
        )
        assertFalse(
            RecentFilesPolicy.isSelectableRecentFile("", isDirectory = false, kind = FileKind.OTHER)
        )
    }

    @Test
    fun `query matching ignores case and treats blank as match all`() {
        assertTrue(RecentFilesPolicy.matchesQuery("Report.PDF", "report"))
        assertTrue(RecentFilesPolicy.matchesQuery("report.pdf", "  "))
        assertFalse(RecentFilesPolicy.matchesQuery("report.pdf", "invoice"))
    }

    @Test
    fun `media store seconds are converted to millis with file system fallback`() {
        assertEquals(1_700_000_000_000L, RecentFilesPolicy.normalizeModifiedMillis(1_700_000_000L, 5L))
        assertEquals(5L, RecentFilesPolicy.normalizeModifiedMillis(0L, 5L))
    }
}

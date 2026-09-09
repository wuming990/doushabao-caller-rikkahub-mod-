package me.rerere.rikkahub.data.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * “上传文件”首页不再提供系统相册目录入口：厂商相册会带出隐私空间相册名称。
 * 这里锁定该业务约束，避免以后误把图片/相机入口加回普通首页。
 */
class LocalStorageCommonPlacesTest {
    @Test
    fun `pictures and camera are not offered as common upload places`() {
        val kinds = LocalStoragePlaceKind.entries.toSet()
        val offered = setOf(
            LocalStoragePlaceKind.DOWNLOADS,
            LocalStoragePlaceKind.DOCUMENTS,
            LocalStoragePlaceKind.VIDEOS,
            LocalStoragePlaceKind.MUSIC,
        )

        assertTrue(offered.all { it in kinds })
        assertFalse(LocalStoragePlaceKind.PICTURES in offered)
        assertFalse(LocalStoragePlaceKind.CAMERA in offered)
    }
}

package me.rerere.rikkahub.data.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStorageVisibilityPolicyTest {
    @Test
    fun `normal browser hides vivo private-space names`() {
        val names = listOf(
            "原子隐私系统",
            "vivo 原子隐私空间",
            "保密柜",
            "超级保密柜",
            "隐私保险箱",
            "隐私相册",
            "PrivacyBox",
            "Private Space",
            "Privacy Space",
            "Secure Folder",
        )

        names.forEach { name ->
            assertFalse(name, LocalStorageVisibilityPolicy.isVisibleInBrowser(name, advanced = false))
        }
    }

    @Test
    fun `normal browser still hides existing system entries`() {
        listOf(".vivo", ".系统文件", "Android", "LOST.DIR").forEach { name ->
            assertFalse(name, LocalStorageVisibilityPolicy.isVisibleInBrowser(name, advanced = false))
        }
    }

    @Test
    fun `ordinary user folders are not hidden by broad privacy matching`() {
        val names = listOf(
            "Download",
            "Pictures",
            "Documents",
            "Private school notes",
            "Privacy policy",
            "安全资料",
            "个人文件",
        )

        names.forEach { name ->
            assertTrue(name, LocalStorageVisibilityPolicy.isVisibleInBrowser(name, advanced = false))
        }
    }

    @Test
    fun `advanced browser may show sensitive directory entries after explicit confirmation`() {
        listOf(".vivo", "原子隐私系统", "保密柜", "PrivacyBox").forEach { name ->
            assertTrue(name, LocalStorageVisibilityPolicy.isVisibleInBrowser(name, advanced = true))
        }
    }

    @Test
    fun `virtual secondary profile is not promoted as ordinary storage root`() {
        assertTrue(
            LocalStorageVisibilityPolicy.isOrdinaryStorageRoot(
                isPrimary = true,
                isRemovable = false,
                isEmulated = true,
            )
        )
        assertTrue(
            LocalStorageVisibilityPolicy.isOrdinaryStorageRoot(
                isPrimary = false,
                isRemovable = true,
                isEmulated = false,
            )
        )
        assertFalse(
            LocalStorageVisibilityPolicy.isOrdinaryStorageRoot(
                isPrimary = false,
                isRemovable = false,
                isEmulated = true,
            )
        )
        assertFalse(
            LocalStorageVisibilityPolicy.isOrdinaryStorageRoot(
                isPrimary = false,
                isRemovable = true,
                isEmulated = true,
            )
        )
    }
}

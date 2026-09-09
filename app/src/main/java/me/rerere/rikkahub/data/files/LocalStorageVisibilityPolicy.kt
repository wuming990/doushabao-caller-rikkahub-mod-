package me.rerere.rikkahub.data.files

import java.util.Locale

/**
 * Controls which shared-storage locations are safe to show in the friendly, normal browser.
 *
 * This is a presentation boundary only: matching entries are not deleted or modified. A normal
 * directory entry can still be reached from the explicitly confirmed advanced browser, while
 * virtual secondary storage roots are not promoted as ordinary phone storage.
 */
internal object LocalStorageVisibilityPolicy {
    private val exactSensitiveNames = setOf(
        "privacy",
        "private",
        "privacybox",
        "privatespace",
        "privacyspace",
        "securefolder",
        "privatefolder",
        "secretfolder",
        "safefolder",
        "vault",
        "隐私",
        "私密",
        "隐私空间",
        "私密空间",
        "原子隐私系统",
        "隐私保险箱",
        "隐私文件",
        "私密文件",
        "隐私相册",
        "保密柜",
        "超级保密柜",
    )

    private val sensitiveNameFragments = setOf(
        "privacybox",
        "privatespace",
        "privacyspace",
        "privacysystem",
        "privatesystem",
        "privatefolder",
        "secretfolder",
        "safefolder",
        "securefolder",
        "隐私空间",
        "私密空间",
        "隐私系统",
        "私密系统",
        "原子隐私",
        "隐私保险箱",
        "隐私文件夹",
        "私密文件夹",
        "隐私相册",
        "私密相册",
        "保密柜",
    )

    fun isVisibleInBrowser(name: String, advanced: Boolean): Boolean =
        advanced || isVisibleInNormalBrowser(name)

    fun isVisibleInNormalBrowser(name: String): Boolean {
        if (name.startsWith('.')) return false
        val normalized = normalize(name)
        if (normalized == "android" || normalized == "lostdir") return false
        if (normalized in exactSensitiveNames) return false
        if (sensitiveNameFragments.any(normalized::contains)) return false
        return true
    }

    /**
     * Only promote the main shared storage and real removable media as ordinary storage roots.
     * Extra emulated/non-removable roots can represent another Android user/profile (including a
     * vendor private space), so they must not appear as a top-level phone-storage entry.
     */
    fun isOrdinaryStorageRoot(
        isPrimary: Boolean,
        isRemovable: Boolean,
        isEmulated: Boolean,
    ): Boolean = isPrimary || (isRemovable && !isEmulated)

    private fun normalize(name: String): String = name
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}

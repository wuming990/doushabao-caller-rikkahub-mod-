package me.rerere.rikkahub.data.files

import android.net.Uri
import android.provider.DocumentsContract

/** 官方 RikkaHub DocumentsProvider 的稳定身份。 */
object OfficialRikkaHubStorage {
    const val PACKAGE_NAME = "me.rerere.rikkahub"
    const val AUTHORITY = "me.rerere.rikkahub.documents"
    const val ROOT_ID = "rikkahub_workspaces"

    /**
     * 给 ACTION_OPEN_DOCUMENT_TREE 的起始位置必须是“特定 root URI”，
     * 不能使用 content://authority/root（那只是 roots 查询端点，会退回手机根目录）。
     */
    val initialRootUri: Uri
        get() = DocumentsContract.buildRootUri(AUTHORITY, ROOT_ID)

    fun isOfficialTree(uri: Uri): Boolean = uri.authority == AUTHORITY
}

package me.rerere.rikkahub.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * 把文件直接写入手机公共 Download 目录。
 * 不弹系统“另存为”界面，避免系统界面记住上一次位置
 * （例如官方 RikkaHub 工作区）导致用户被“锁”在错误位置。
 */
object DownloadsExporter {
    suspend fun save(
        context: Context,
        displayName: String,
        writer: suspend (OutputStream) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val safeName = displayName.trim().ifBlank { "file" }.replace('/', '_')
        val resolver = context.contentResolver
        val finalName = uniqueName(resolver, safeName)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在手机下载目录创建文件")
            val output = resolver.openOutputStream(uri)
                ?: error("无法打开手机下载目录文件")
            output.use { writer(it) }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dir, finalName).outputStream().use { writer(it) }
        }
        finalName
    }

    private fun uniqueName(resolver: ContentResolver, displayName: String): String {
        val existing = mutableSetOf<String>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            if (index >= 0) {
                while (cursor.moveToNext()) existing += cursor.getString(index)
            }
        }
        if (displayName !in existing) return displayName
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var n = 1
        while ("$base ($n)$ext" in existing) n++
        return "$base ($n)$ext"
    }
}

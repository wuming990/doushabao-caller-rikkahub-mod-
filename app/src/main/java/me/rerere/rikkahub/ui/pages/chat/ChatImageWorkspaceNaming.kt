package me.rerere.rikkahub.ui.pages.chat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天页图片保存到工作区时使用的固定位置与文件名规则。
 *
 * 用户不需要理解工作区内部路径，所以位置固定；上传目录里的文件名是 UUID，
 * 因此这里改成时间戳 + 序号，方便用户和 AI 在工作区里辨认。
 */
internal object ChatImageWorkspaceNaming {
    const val DIRECTORY = "images"

    private const val DEFAULT_EXTENSION = "jpg"

    fun fileName(
        originalName: String,
        index: Int,
        timestampMillis: Long = System.currentTimeMillis(),
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMillis))
        val extension = originalName
            .substringAfterLast('.', "")
            .lowercase(Locale.US)
            .filter { it.isLetterOrDigit() }
            .ifBlank { DEFAULT_EXTENSION }
        return if (index <= 0) {
            "image_$stamp.$extension"
        } else {
            "image_${stamp}_${index + 1}.$extension"
        }
    }
}

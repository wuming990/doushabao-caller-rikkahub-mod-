package me.rerere.rikkahub.data.files

import java.util.Locale

/**
 * 展示用文件类型分类：让文件列表一眼能看出是什么文件。
 * 仅用于 UI 展示，不参与任何读写/权限判断。
 */
enum class FileKind {
    IMAGE,
    VIDEO,
    AUDIO,
    PDF,
    SHEET,
    DOCUMENT,
    ARCHIVE,
    CODE,
    OTHER,
}

fun fileKindOf(name: String): FileKind {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg" -> FileKind.IMAGE
        "mp4", "mkv", "mov", "avi", "wmv", "flv", "webm", "3gp", "m4v", "ts" -> FileKind.VIDEO
        "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "amr", "mid", "midi" -> FileKind.AUDIO
        "pdf" -> FileKind.PDF
        "xls", "xlsx", "xlsm", "csv", "ods" -> FileKind.SHEET
        "doc", "docx", "txt", "md", "rtf", "odt", "pages", "log" -> FileKind.DOCUMENT
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "zst" -> FileKind.ARCHIVE
        "kt", "kts", "java", "py", "js", "ts", "json", "xml", "yml", "yaml", "sh", "sql",
        "html", "css", "c", "cpp", "h", "go", "rs", "toml", "gradle",
        -> FileKind.CODE

        else -> FileKind.OTHER
    }
}

fun fileKindOfMime(mimeType: String): FileKind {
    val mime = mimeType.lowercase(Locale.ROOT)
    return when {
        mime.startsWith("image/") -> FileKind.IMAGE
        mime.startsWith("video/") -> FileKind.VIDEO
        mime.startsWith("audio/") -> FileKind.AUDIO
        mime == "application/pdf" -> FileKind.PDF
        mime.contains("spreadsheet") || mime.contains("excel") || mime.contains("csv") -> FileKind.SHEET
        mime.startsWith("text/") || mime.contains("document") || mime.contains("word") ||
            mime.contains("officedocument") -> FileKind.DOCUMENT
        mime.contains("zip") || mime.contains("compress") || mime.contains("tar") ||
            mime.contains("rar") || mime.contains("7z") -> FileKind.ARCHIVE
        else -> FileKind.OTHER
    }
}

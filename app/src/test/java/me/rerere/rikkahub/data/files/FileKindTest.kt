package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * “上传文件”列表要让用户一眼看出文件是什么类型。
 * 这里锁定扩展名/MIME 到展示分类的映射，避免以后改动把类型识别改坏。
 */
class FileKindTest {
    @Test
    fun `extension maps to display kind`() {
        assertEquals(FileKind.IMAGE, fileKindOf("photo.JPG"))
        assertEquals(FileKind.IMAGE, fileKindOf("icon.webp"))
        assertEquals(FileKind.VIDEO, fileKindOf("clip.mp4"))
        assertEquals(FileKind.AUDIO, fileKindOf("song.flac"))
        assertEquals(FileKind.PDF, fileKindOf("report.pdf"))
        assertEquals(FileKind.SHEET, fileKindOf("data.xlsx"))
        assertEquals(FileKind.SHEET, fileKindOf("table.csv"))
        assertEquals(FileKind.DOCUMENT, fileKindOf("notes.md"))
        assertEquals(FileKind.DOCUMENT, fileKindOf("letter.docx"))
        assertEquals(FileKind.ARCHIVE, fileKindOf("bundle.zip"))
        assertEquals(FileKind.ARCHIVE, fileKindOf("backup.tar.gz"))
        assertEquals(FileKind.CODE, fileKindOf("Main.kt"))
        assertEquals(FileKind.CODE, fileKindOf("config.json"))
    }

    @Test
    fun `unknown or missing extension falls back to other`() {
        assertEquals(FileKind.OTHER, fileKindOf("README"))
        assertEquals(FileKind.OTHER, fileKindOf("weird.qwertyz"))
        assertEquals(FileKind.OTHER, fileKindOf(""))
    }

    @Test
    fun `mime type maps to display kind`() {
        assertEquals(FileKind.IMAGE, fileKindOfMime("image/png"))
        assertEquals(FileKind.VIDEO, fileKindOfMime("video/mp4"))
        assertEquals(FileKind.AUDIO, fileKindOfMime("audio/mpeg"))
        assertEquals(FileKind.PDF, fileKindOfMime("application/pdf"))
        assertEquals(
            FileKind.SHEET,
            fileKindOfMime("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        )
        assertEquals(
            FileKind.DOCUMENT,
            fileKindOfMime("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        )
        assertEquals(FileKind.DOCUMENT, fileKindOfMime("text/plain"))
        assertEquals(FileKind.ARCHIVE, fileKindOfMime("application/zip"))
        assertEquals(FileKind.OTHER, fileKindOfMime("application/octet-stream"))
    }
}

package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ChatImageWorkspaceNamingTest {
    private val fixedMillis: Long = Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply {
        clear()
        set(2026, Calendar.AUGUST, 6, 12, 34, 56)
    }.timeInMillis

    @Test
    fun `images are always saved into one fixed folder`() {
        assertEquals("images", ChatImageWorkspaceNaming.DIRECTORY)
    }

    @Test
    fun `first image keeps original extension without index suffix`() {
        val name = ChatImageWorkspaceNaming.fileName("holiday.PNG", index = 0, timestampMillis = fixedMillis)
        assertEquals("image_20260806_123456.png", name)
    }

    @Test
    fun `additional images get a stable index suffix`() {
        val second = ChatImageWorkspaceNaming.fileName("a.jpg", index = 1, timestampMillis = fixedMillis)
        val third = ChatImageWorkspaceNaming.fileName("a.jpg", index = 2, timestampMillis = fixedMillis)
        assertEquals("image_20260806_123456_2.jpg", second)
        assertEquals("image_20260806_123456_3.jpg", third)
    }

    @Test
    fun `missing or unsafe extension falls back to jpg`() {
        assertEquals(
            "image_20260806_123456.jpg",
            ChatImageWorkspaceNaming.fileName("no-extension", index = 0, timestampMillis = fixedMillis),
        )
        assertEquals(
            "image_20260806_123456.jpg",
            ChatImageWorkspaceNaming.fileName("weird.", index = 0, timestampMillis = fixedMillis),
        )
    }

    @Test
    fun `generated names never contain path separators`() {
        val names = listOf(
            ChatImageWorkspaceNaming.fileName("../../escape.png", index = 0, timestampMillis = fixedMillis),
            ChatImageWorkspaceNaming.fileName("dir/sub/pic.jpeg", index = 3, timestampMillis = fixedMillis),
        )
        names.forEach { name ->
            assertTrue(name, '/' !in name)
            assertTrue(name, '\\' !in name)
            assertTrue(name, !name.contains(".."))
        }
    }
}

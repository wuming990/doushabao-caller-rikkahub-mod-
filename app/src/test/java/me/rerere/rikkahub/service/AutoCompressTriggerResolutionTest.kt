package me.rerere.rikkahub.service

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.AutoCompressModelOverride
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 「特殊模型」的触发值解析：
 * 只有被列进表里的模型走自己的值，其余模型继续用所在助手的自定义触发值。
 */
class AutoCompressTriggerResolutionTest {

    private val assistant = Assistant(enableAutoCompress = true, autoCompressTriggerTokens = 100_000)
    private val bigModel = Uuid.random()
    private val smallModel = Uuid.random()
    private val untouchedModel = Uuid.random()

    private val overrides = listOf(
        AutoCompressModelOverride(modelId = bigModel, triggerTokens = 900_000),
        AutoCompressModelOverride(modelId = smallModel, triggerTokens = 250_000),
    )

    @Test
    fun `selected model uses its own trigger value`() {
        assertEquals(900_000, resolveAutoCompressTriggerTokens(assistant, bigModel, overrides))
        assertEquals(250_000, resolveAutoCompressTriggerTokens(assistant, smallModel, overrides))
    }

    @Test
    fun `unselected model falls back to the assistant value`() {
        assertEquals(100_000, resolveAutoCompressTriggerTokens(assistant, untouchedModel, overrides))
    }

    @Test
    fun `empty override list keeps the old behaviour`() {
        assertEquals(100_000, resolveAutoCompressTriggerTokens(assistant, bigModel, emptyList()))
    }

    @Test
    fun `null model id falls back to the assistant value`() {
        assertEquals(100_000, resolveAutoCompressTriggerTokens(assistant, null, overrides))
    }

    @Test
    fun `non positive override is ignored instead of disabling compression`() {
        val broken = listOf(AutoCompressModelOverride(modelId = bigModel, triggerTokens = 0))

        assertEquals(100_000, resolveAutoCompressTriggerTokens(assistant, bigModel, broken))
    }

    @Test
    fun `resolved value is what actually triggers compression`() {
        val resolved = resolveAutoCompressTriggerTokens(assistant, bigModel, overrides)

        assertFalse(shouldAutoCompressAtTokenCount(assistant, 899_999L, resolved))
        assertTrue(shouldAutoCompressAtTokenCount(assistant, 900_000L, resolved))
        // 同样的对话大小，换成没单独设过的模型时应该早就触发了
        val fallback = resolveAutoCompressTriggerTokens(assistant, untouchedModel, overrides)
        assertTrue(shouldAutoCompressAtTokenCount(assistant, 899_999L, fallback))
    }

    @Test
    fun `overrides survive json round trip`() {
        val json = Json.encodeToString(overrides)
        val restored = Json.decodeFromString<List<AutoCompressModelOverride>>(json)

        assertEquals(overrides, restored)
    }

    @Test
    fun `auto compress disabled still means no compression regardless of override`() {
        val off = Assistant(enableAutoCompress = false, autoCompressTriggerTokens = 100_000)
        val resolved = resolveAutoCompressTriggerTokens(off, bigModel, overrides)

        assertEquals(900_000, resolved)
        assertFalse(shouldAutoCompressAtTokenCount(off, 5_000_000L, resolved))
    }
}

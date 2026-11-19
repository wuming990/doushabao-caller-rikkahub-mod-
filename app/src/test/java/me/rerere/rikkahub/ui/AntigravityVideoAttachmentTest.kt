package me.rerere.rikkahub.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.gemini.withAntigravityRequestShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Google-compatible video capability, its actual + panel button wiring,
 * and preservation of already-built video contents by the Antigravity request shaper.
 * Reflection keeps these tests compilable before the production capability is added.
 */
class AntigravityVideoAttachmentTest {

    private val repoRoot: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir ?: File(System.getProperty("user.dir") ?: ".").absoluteFile
    }

    private fun canAttachVideo(provider: ProviderSetting?): Boolean {
        val facade = Class.forName("me.rerere.rikkahub.ui.components.ai.FilesPickerKt")
        val method = try {
            facade.getDeclaredMethod("canAttachVideo", ProviderSetting::class.java)
        } catch (missing: NoSuchMethodException) {
            throw AssertionError(
                "Missing FilesPicker canAttachVideo(ProviderSetting) UI capability helper",
                missing,
            )
        }
        method.isAccessible = true
        return method.invoke(null, provider) as Boolean
    }

    private fun source(relative: String): String {
        val file = File(repoRoot, relative)
        if (!file.exists()) throw AssertionError("找不到源码 $relative")
        return file.readText()
    }

    /** Reuses V1008's string-aware comment stripper; this remains a source wiring guard. */
    private fun filesPickerCode(): String {
        val path = "app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt"
        val raw = source(path)
        val stripped = V1008NestedScrollGuardTest().stripComments(raw)
        assertTrue(
            "$path 剥注释后只剩 ${stripped.length}/${raw.length} 字符，源码接线守卫可能误吞了代码",
            stripped.length * 2 >= raw.length,
        )
        return stripped
    }

    /**
     * Positively anchors only the real attachment FlowRow, from its camera button to its file button.
     * Missing, duplicate, or out-of-order anchors fail instead of falling back to a whole-file search.
     */
    private fun attachmentButtons(): String {
        val code = filesPickerCode()
        val flowRowAnchor =
            "FlowRow(\n            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)"
        val takePicAnchor = "TakePicButton(onLaunchCamera = onTakePic)"
        val filePickAnchor = "FilePickButton(onClick = onPickFile)"
        val flowRowIndex = code.indexOf(flowRowAnchor)
        val takePicIndex = code.indexOf(takePicAnchor)
        val filePickIndex = code.indexOf(filePickAnchor)
        assertTrue("找不到唯一附件 FlowRow 锚点", flowRowIndex >= 0 && code.lastIndexOf(flowRowAnchor) == flowRowIndex)

        val flowRowOpenParen = code.indexOf('(', flowRowIndex)
        val flowRowCloseParen = matchingDelimiter(code, flowRowOpenParen, '(', ')')
        var flowRowBodyStart = flowRowCloseParen + 1
        while (flowRowBodyStart < code.length && code[flowRowBodyStart].isWhitespace()) flowRowBodyStart++
        assertTrue("附件 FlowRow 缺少尾随内容块", flowRowBodyStart < code.length && code[flowRowBodyStart] == '{')
        val flowRowBodyEnd = matchingDelimiter(code, flowRowBodyStart, '{', '}')
        val followingDivider = code.indexOf("HorizontalDivider(", flowRowBodyEnd)

        assertTrue(
            "拍照按钮锚点缺失、重复，或不在附件 FlowRow 内",
            takePicIndex > flowRowBodyStart && takePicIndex < flowRowBodyEnd &&
                code.lastIndexOf(takePicAnchor) == takePicIndex,
        )
        assertTrue(
            "附件按钮顺序错误，FilePickButton 必须在 TakePicButton 之后且仍位于附件 FlowRow 内",
            filePickIndex > takePicIndex && filePickIndex < flowRowBodyEnd && followingDivider > flowRowBodyEnd,
        )
        assertEquals("附件文件按钮必须唯一", filePickIndex, code.lastIndexOf(filePickAnchor))

        return code.substring(takePicIndex, filePickIndex + filePickAnchor.length)
    }

    private fun matchingDelimiter(text: String, openIndex: Int, open: Char, close: Char): Int {
        var depth = 0
        for (index in openIndex until text.length) {
            when (text[index]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        throw AssertionError("找不到 ${open} 的匹配 ${close}")
    }

    private fun conditionOwningAudioButton(buttons: String): String {
        val audioCall = "AudioPickButton(onClick = onPickAudio)"
        val audioIndex = buttons.indexOf(audioCall)
        assertTrue("附件 FlowRow 中找不到 AudioPickButton", audioIndex >= 0)
        assertEquals("AudioPickButton 必须唯一", audioIndex, buttons.lastIndexOf(audioCall))

        val conditions = Regex("\\bif\\s*\\(").findAll(buttons).mapNotNull { match ->
            val openParen = buttons.indexOf('(', match.range.first)
            val closeParen = matchingDelimiter(buttons, openParen, '(', ')')
            val condition = buttons.substring(openParen + 1, closeParen)
            var bodyStart = closeParen + 1
            while (bodyStart < buttons.length && buttons[bodyStart].isWhitespace()) bodyStart++
            if (bodyStart >= buttons.length || buttons[bodyStart] != '{') return@mapNotNull null

            val bodyEnd = matchingDelimiter(buttons, bodyStart, '{', '}')
            if (audioIndex in (bodyStart + 1) until bodyEnd) condition else null
        }.toList()

        assertEquals("AudioPickButton 必须归属于唯一、明确的 Google 条件块", 1, conditions.size)
        return conditions.single().filterNot(Char::isWhitespace)
    }

    private fun effectiveProvider(
        owner: ProviderSetting,
        modelId: String,
        override: ProviderSetting,
    ): ProviderSetting? {
        val model = Model(modelId = modelId, providerOverwrite = override)
        val ownerWithModel = owner.copyProvider(models = listOf(model))
        return model.findProvider(listOf(ownerWithModel))
    }

    @Test
    fun `Google provider can attach video`() {
        assertTrue(canAttachVideo(ProviderSetting.Google()))
    }

    @Test
    fun `Gemini OAuth provider can attach video`() {
        assertTrue(canAttachVideo(ProviderSetting.GeminiOAuth()))
    }

    @Test
    fun `null provider cannot attach video`() {
        assertFalse(canAttachVideo(null))
    }

    @Test
    fun `OpenAI provider cannot attach video`() {
        assertFalse(canAttachVideo(ProviderSetting.OpenAI()))
    }

    @Test
    fun `Claude provider cannot attach video`() {
        assertFalse(canAttachVideo(ProviderSetting.Claude()))
    }

    @Test
    fun `Gemini Web provider cannot attach video`() {
        assertFalse(canAttachVideo(ProviderSetting.GeminiWeb()))
    }

    @Test
    fun `Codex provider cannot attach video`() {
        assertFalse(canAttachVideo(ProviderSetting.Codex()))
    }

    @Test
    fun `Grok provider cannot attach video`() {
        assertFalse(canAttachVideo(ProviderSetting.Grok()))
    }

    @Test
    fun `ChatGPT provider cannot attach video`() {
        assertFalse(canAttachVideo(ProviderSetting.ChatGPT()))
    }

    @Test
    fun `Google-owned model overwritten to OpenAI follows the actual OpenAI route`() {
        val actualProvider = effectiveProvider(
            owner = ProviderSetting.Google(),
            modelId = "google-owned-model",
            override = ProviderSetting.OpenAI(),
        )
        assertNotNull("模型应解析到实际覆盖供应商", actualProvider)
        assertTrue(actualProvider is ProviderSetting.OpenAI)
        assertFalse(canAttachVideo(actualProvider))
    }

    @Test
    fun `OpenAI-owned model overwritten to Gemini OAuth follows the actual Gemini OAuth route`() {
        val actualProvider = effectiveProvider(
            owner = ProviderSetting.OpenAI(),
            modelId = "openai-owned-model",
            override = ProviderSetting.GeminiOAuth(),
        )
        assertNotNull("模型应解析到实际覆盖供应商", actualProvider)
        assertTrue(actualProvider is ProviderSetting.GeminiOAuth)
        assertTrue(canAttachVideo(actualProvider))
    }

    @Test
    fun `the video button has its own capability block inside the attachment FlowRow`() {
        val buttons = attachmentButtons()
        val videoCapabilityBlock = Regex(
            """if\s*\(\s*canAttachVideo\s*\(\s*provider\s*\)\s*\)\s*\{\s*VideoPickButton\s*\(\s*onClick\s*=\s*onPickVideo\s*\)\s*\}"""
        )
        assertTrue(
            "附件 FlowRow 内的 VideoPickButton(onClick=onPickVideo) 必须单独位于 if(canAttachVideo(provider)) 块中",
            videoCapabilityBlock.containsMatchIn(buttons),
        )
    }

    @Test
    fun `the audio button remains gated only by the original Google provider condition`() {
        val condition = conditionOwningAudioButton(attachmentButtons())
        assertTrue(
            "音频入口必须继续只限 Google，不能复用视频能力或扩到 Gemini OAuth: $condition",
            condition == "provider!=null&&providerisProviderSetting.Google" ||
                condition == "providerisProviderSetting.Google",
        )
    }

    @Test
    fun `Antigravity request shaping preserves a synthetic inline video part`() {
        // Only tests preservation of constructed JSON; this does not encode a local file or claim service acceptance.
        val request = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "parts",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put(
                                                "inlineData",
                                                buildJsonObject {
                                                    put("mimeType", "video/mp4")
                                                    put("data", "synthetic-video-payload")
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        val expectedContents = JsonArray(
            listOf(
                buildJsonObject {
                    put("role", "user")
                    put(
                        "parts",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put(
                                        "inlineData",
                                        buildJsonObject {
                                            put("mimeType", "video/mp4")
                                            put("data", "synthetic-video-payload")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            ),
        )

        val shaped = withAntigravityRequestShape(request, emptyList())

        assertEquals(expectedContents, shaped["contents"])
    }

    @Test
    fun `Antigravity request shaping preserves ordered text multiple videos and function tools`() {
        val request = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "parts",
                                buildJsonArray {
                                    add(buildJsonObject { put("text", "Inspect these clips in order") })
                                    add(
                                        buildJsonObject {
                                            put(
                                                "inlineData",
                                                buildJsonObject {
                                                    put("mimeType", "video/mp4")
                                                    put("data", "first-video-data")
                                                },
                                            )
                                        },
                                    )
                                    add(
                                        buildJsonObject {
                                            put(
                                                "inlineData",
                                                buildJsonObject {
                                                    put("mimeType", "video/mp4")
                                                    put("data", "second-video-data")
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "functionDeclarations",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("name", "save_clip_notes")
                                            put("description", "Save notes about the supplied clips")
                                            put(
                                                "parameters",
                                                buildJsonObject {
                                                    put("type", "OBJECT")
                                                    put(
                                                        "properties",
                                                        buildJsonObject {
                                                            put("summary", buildJsonObject { put("type", "STRING") })
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        val expectedContents = JsonArray(
            listOf(
                buildJsonObject {
                    put("role", "user")
                    put(
                        "parts",
                        buildJsonArray {
                            add(buildJsonObject { put("text", "Inspect these clips in order") })
                            add(
                                buildJsonObject {
                                    put(
                                        "inlineData",
                                        buildJsonObject {
                                            put("mimeType", "video/mp4")
                                            put("data", "first-video-data")
                                        },
                                    )
                                },
                            )
                            add(
                                buildJsonObject {
                                    put(
                                        "inlineData",
                                        buildJsonObject {
                                            put("mimeType", "video/mp4")
                                            put("data", "second-video-data")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            ),
        )
        val expectedTools = JsonArray(
            listOf(
                buildJsonObject {
                    put(
                        "functionDeclarations",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("name", "save_clip_notes")
                                    put("description", "Save notes about the supplied clips")
                                    put(
                                        "parameters",
                                        buildJsonObject {
                                            put("type", "OBJECT")
                                            put(
                                                "properties",
                                                buildJsonObject {
                                                    put("summary", buildJsonObject { put("type", "STRING") })
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            ),
        )

        val shaped = withAntigravityRequestShape(request, emptyList())

        assertEquals(expectedContents, shaped["contents"])
        assertEquals(expectedTools, shaped["tools"])
    }
}

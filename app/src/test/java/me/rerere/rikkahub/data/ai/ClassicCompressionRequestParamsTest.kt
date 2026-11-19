package me.rerere.rikkahub.data.ai

import android.content.ContextWrapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.uuid.Uuid

class ClassicCompressionRequestParamsTest {
    @Test
    fun `assistant custom body excluded from Gemini OAuth is absent from classic compression params`() {
        val fixture = fixture(
            assistantBodies = { _, effectiveProviderId ->
                listOf(body("prompt_cache_key", excluded = setOf(effectiveProviderId)))
            },
        )

        assertTrue(fixture.effectiveProvider is ProviderSetting.GeminiOAuth)
        assertFalse(params(fixture).customBody.any { it.key == "prompt_cache_key" })
    }

    @Test
    fun `model custom body exclusion applies to classic compression params`() {
        val fixture = fixture(
            modelBodies = { _, effectiveProviderId ->
                listOf(body("prompt_cache_key", excluded = setOf(effectiveProviderId)))
            },
        )

        assertFalse(params(fixture).customBody.any { it.key == "prompt_cache_key" })
    }

    @Test
    fun `provider overwrite still excludes owning provider custom bodies`() {
        val fixture = fixture(
            ownerKind = ProviderKind.GEMINI_OAUTH,
            overwriteKind = ProviderKind.OPENAI,
            assistantBodies = { ownerProviderId, _ ->
                listOf(body("prompt_cache_key", excluded = setOf(ownerProviderId)))
            },
        )

        assertTrue(fixture.effectiveProvider is ProviderSetting.OpenAI)
        assertFalse(params(fixture).customBody.any { it.key == "prompt_cache_key" })
    }

    @Test
    fun `provider overwrite exclusion also matches effective provider`() {
        val fixture = fixture(
            ownerKind = ProviderKind.OPENAI,
            overwriteKind = ProviderKind.GEMINI_OAUTH,
            modelBodies = { _, effectiveProviderId ->
                listOf(body("prompt_cache_key", excluded = setOf(effectiveProviderId)))
            },
        )

        assertTrue(fixture.effectiveProvider is ProviderSetting.GeminiOAuth)
        assertFalse(params(fixture).customBody.any { it.key == "prompt_cache_key" })
    }

    @Test
    fun `unexcluded prompt cache key remains in classic compression params`() {
        val promptCacheKey = body("prompt_cache_key")
        val fixture = fixture(assistantBodies = { _, _ -> listOf(promptCacheKey) })

        assertEquals(listOf(promptCacheKey), params(fixture).customBody)
    }

    @Test
    fun `exclusion for unrelated provider does not remove custom body`() {
        val fixture = fixture(
            assistantBodies = { _, _ ->
                listOf(body("gateway_mode", excluded = setOf(Uuid.random())))
            },
        )

        assertEquals(listOf("gateway_mode"), params(fixture).customBody.map { it.key })
    }

    @Test
    fun `legacy custom body whitelist still includes a matching provider`() {
        val fixture = fixture(
            assistantBodies = { _, effectiveProviderId ->
                listOf(body("legacy_option", providerIds = setOf(effectiveProviderId)))
            },
        )

        assertEquals(listOf("legacy_option"), params(fixture).customBody.map { it.key })
    }

    @Test
    fun `legacy custom body whitelist excludes a nonmatching provider`() {
        val fixture = fixture(
            assistantBodies = { _, _ ->
                listOf(body("legacy_option", providerIds = setOf(Uuid.random())))
            },
        )

        assertFalse(params(fixture).customBody.any { it.key == "legacy_option" })
    }

    @Test
    fun `assistant bodies precede model bodies and model value remains effective for duplicate keys`() {
        val assistantBody = body("gateway_mode", value = "assistant")
        val modelBody = body("gateway_mode", value = "model")
        val fixture = fixture(
            assistantBodies = { _, _ -> listOf(assistantBody) },
            modelBodies = { _, _ -> listOf(modelBody) },
        )

        val customBodies = params(fixture).customBody
        assertEquals(listOf(assistantBody, modelBody), customBodies)
        assertEquals(JsonPrimitive("model"), customBodies.last().value)
    }

    @Test
    fun `classic compression params preserve model generation settings and custom headers`() {
        val assistantHeaders = listOf(CustomHeader(name = "X-Assistant", value = "assistant-value"))
        val modelHeaders = listOf(CustomHeader(name = "X-Model", value = "model-value"))
        val fixture = fixture(
            assistantHeaders = assistantHeaders,
            modelHeaders = modelHeaders,
        )

        val params = params(fixture)
        assertEquals(fixture.model, params.model)
        assertEquals(0.37f, params.temperature!!, 0f)
        assertEquals(0.73f, params.topP!!, 0f)
        assertEquals(123, params.maxTokens)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(fixture.turn.conversationId.toString(), params.sessionId)
        assertEquals(assistantHeaders + modelHeaders, params.customHeaders)
    }

    private fun params(fixture: Fixture) = buildStep(newFactory(), fixture.turn).params

    private fun buildStep(factory: ChatRequestFactory, turn: ChatTurnContext): StepRequest = runBlocking {
        suspendCoroutineUninterceptedOrReturn<StepRequest> { continuation ->
            try {
                val result = buildStepMethod.invoke(
                    factory,
                    turn,
                    emptyList<UIMessage>(),
                    MutableStateFlow<String?>(null),
                    continuation,
                )
                if (result === COROUTINE_SUSPENDED) result else result as StepRequest
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

    private fun newFactory(): ChatRequestFactory {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }.get(null) as Unsafe
        val factory = unsafe.allocateInstance(ChatRequestFactory::class.java) as ChatRequestFactory
        ChatRequestFactory::class.java.getDeclaredField("context").apply {
            isAccessible = true
        }.set(factory, ContextWrapper(null))
        ChatRequestFactory::class.java.getDeclaredField("inputTransformers").apply {
            isAccessible = true
        }.set(factory, emptyList<InputMessageTransformer>())
        return factory
    }

    private fun fixture(
        ownerKind: ProviderKind = ProviderKind.GEMINI_OAUTH,
        overwriteKind: ProviderKind? = null,
        assistantBodies: (ownerProviderId: Uuid, effectiveProviderId: Uuid) -> List<CustomBody> = { _, _ ->
            emptyList()
        },
        modelBodies: (ownerProviderId: Uuid, effectiveProviderId: Uuid) -> List<CustomBody> = { _, _ ->
            emptyList()
        },
        assistantHeaders: List<CustomHeader> = emptyList(),
        modelHeaders: List<CustomHeader> = emptyList(),
    ): Fixture {
        val ownerProviderId = Uuid.random()
        val effectiveProviderId = if (overwriteKind == null) ownerProviderId else Uuid.random()
        val model = Model(
            modelId = "compression-params-test-model",
            customHeaders = modelHeaders,
            customBodies = modelBodies(ownerProviderId, effectiveProviderId),
            providerOverwrite = overwriteKind?.let { provider(it, effectiveProviderId) },
        )
        val ownerProvider = provider(ownerKind, ownerProviderId, listOf(model))
        val settings = Settings(providers = listOf(ownerProvider))
        val effectiveProvider = model.findProvider(settings.providers)
            ?: error("Test model must resolve to an effective provider")
        val assistant = Assistant(
            temperature = 0.37f,
            topP = 0.73f,
            maxTokens = 123,
            reasoningLevel = ReasoningLevel.AUTO,
            customHeaders = assistantHeaders,
            customBodies = assistantBodies(ownerProviderId, effectiveProviderId),
        )
        val turn = ChatTurnContext(
            settings = settings,
            assistant = assistant,
            model = model,
            providerSetting = effectiveProvider,
            conversationId = Uuid.random(),
            conversationSystemPrompt = null,
            workspaceCwd = null,
            memories = emptyList(),
            tools = emptyList(),
        )
        return Fixture(model = model, turn = turn, effectiveProvider = effectiveProvider)
    }

    private fun provider(
        kind: ProviderKind,
        id: Uuid,
        models: List<Model> = emptyList(),
    ): ProviderSetting = when (kind) {
        ProviderKind.GEMINI_OAUTH -> ProviderSetting.GeminiOAuth(id = id, models = models)
        ProviderKind.OPENAI -> ProviderSetting.OpenAI(id = id, models = models)
    }

    private fun body(
        key: String,
        value: String = "test-value",
        providerIds: Set<Uuid>? = null,
        excluded: Set<Uuid>? = null,
    ) = CustomBody(
        key = key,
        value = JsonPrimitive(value),
        providerIds = providerIds,
        excludedProviderIds = excluded,
    )

    private data class Fixture(
        val model: Model,
        val turn: ChatTurnContext,
        val effectiveProvider: ProviderSetting,
    )

    private enum class ProviderKind {
        GEMINI_OAUTH,
        OPENAI,
    }

    private companion object {
        val buildStepMethod by lazy {
            ChatRequestFactory::class.java.getDeclaredMethod(
                "buildStep",
                ChatTurnContext::class.java,
                List::class.java,
                MutableStateFlow::class.java,
                Continuation::class.java,
            ).apply { isAccessible = true }
        }
    }
}

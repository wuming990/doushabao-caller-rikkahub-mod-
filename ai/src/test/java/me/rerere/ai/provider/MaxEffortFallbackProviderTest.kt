package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MaxEffortFallbackProviderTest {

    /** 记录每次收到的思考档位，用来断言是否真的降档重发。 */
    private class FakeProvider(
        private val failWhenMax: Throwable?,
        private val chunksBeforeFailure: Int = 0,
    ) : Provider<ProviderSetting.OpenAI> {
        val seenLevels = mutableListOf<ReasoningLevel>()

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
            emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            seenLevels += params.reasoningLevel
            if (params.reasoningLevel == ReasoningLevel.MAX && failWhenMax != null) throw failWhenMax
            return TextGenerationResult(
                id = "id",
                model = params.model.modelId,
                message = UIMessage.assistant("ok"),
            )
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            seenLevels += params.reasoningLevel
            val shouldFail = params.reasoningLevel == ReasoningLevel.MAX && failWhenMax != null
            return flow {
                repeat(if (shouldFail) chunksBeforeFailure else 1) {
                    emit(StreamChunk.TextDelta(id = "t$it", text = "chunk$it"))
                }
                if (shouldFail) throw failWhenMax!!
            }
        }
    }

    private val setting = ProviderSetting.OpenAI()

    private fun maxParams(modelId: String = "some-model") = TextGenerationParams(
        model = Model(modelId = modelId),
        reasoningLevel = ReasoningLevel.MAX,
    )

    private val effortRejection =
        HttpException("Invalid value 'max' for parameter reasoning_effort")

    @Before
    fun setUp() = MaxEffortDenyList.clear()

    @After
    fun tearDown() = MaxEffortDenyList.clear()

    @Test
    fun `non streaming request retries with xhigh when max is rejected`() = runBlocking {
        val fake = FakeProvider(failWhenMax = effortRejection)
        val result = MaxEffortFallbackProvider(fake)
            .generateText(setting, emptyList(), maxParams("relay/model-x"))

        assertEquals("ok", result.message.toText())
        assertEquals(listOf(ReasoningLevel.MAX, ReasoningLevel.XHIGH), fake.seenLevels)
        assertTrue(MaxEffortDenyList.isDenied("relay/model-x"))
    }

    @Test
    fun `a denied model goes out as xhigh right away`() = runBlocking {
        MaxEffortDenyList.deny("relay/model-known-bad")
        val fake = FakeProvider(failWhenMax = effortRejection)
        val result = MaxEffortFallbackProvider(fake)
            .generateText(setting, emptyList(), maxParams("relay/model-known-bad"))

        assertEquals("ok", result.message.toText())
        assertEquals(listOf(ReasoningLevel.XHIGH), fake.seenLevels)
    }

    @Test
    fun `unrelated errors are propagated without a second request`() = runBlocking {
        val fake = FakeProvider(failWhenMax = HttpException("Rate limit reached"))
        var thrown: Throwable? = null
        try {
            MaxEffortFallbackProvider(fake).generateText(setting, emptyList(), maxParams())
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(thrown is HttpException)
        assertEquals(listOf(ReasoningLevel.MAX), fake.seenLevels)
        assertFalse(MaxEffortDenyList.isDenied("some-model"))
    }

    @Test
    fun `requests below MAX are passed straight through`() = runBlocking {
        val fake = FakeProvider(failWhenMax = effortRejection)
        val result = MaxEffortFallbackProvider(fake).generateText(
            setting,
            emptyList(),
            maxParams().copy(reasoningLevel = ReasoningLevel.XHIGH),
        )
        assertEquals("ok", result.message.toText())
        assertEquals(listOf(ReasoningLevel.XHIGH), fake.seenLevels)
    }

    @Test
    fun `streaming retries when max is rejected before any content`() = runBlocking {
        val fake = FakeProvider(failWhenMax = effortRejection, chunksBeforeFailure = 0)
        val chunks = MaxEffortFallbackProvider(fake)
            .streamText(setting, emptyList(), maxParams("relay/model-y"))
            .toList()

        assertEquals(1, chunks.size)
        assertEquals(listOf(ReasoningLevel.MAX, ReasoningLevel.XHIGH), fake.seenLevels)
        assertTrue(MaxEffortDenyList.isDenied("relay/model-y"))
    }

    @Test
    fun `streaming does not retry once content was already shown`() = runBlocking {
        val fake = FakeProvider(failWhenMax = effortRejection, chunksBeforeFailure = 2)
        var thrown: Throwable? = null
        val received = mutableListOf<StreamChunk>()
        try {
            MaxEffortFallbackProvider(fake)
                .streamText(setting, emptyList(), maxParams("relay/model-z"))
                .collect { received += it }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(thrown is HttpException)
        assertEquals(2, received.size)
        assertEquals(listOf(ReasoningLevel.MAX), fake.seenLevels)
    }
}

package me.rerere.ai.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage

/**
 * 思考档位兼容兜底装饰器（v219 泛化版，替代原 MaxEffortFallbackProvider）。
 *
 * 历史版本只对 MAX 档做兜底；这里推广到**所有思考档位**：
 *  1. 请求前查黑名单：已知该模型不支持的档位直接跳过，直达可用最高档（不白花一次注定被拒的请求）；
 *  2. 真被服务端以「思考档位参数非法」的理由拒了（[isReasoningRejection]）→ 记下这个模型 + 档位，
 *     沿降级链找「下一个未被拒绝的档位」（= 模型可用的最大档）重发；
 *  3. 链已到底（连关闭思考都被拒）→ 放弃并抛原始错误，绝不无限重试；
 *  4. 只识别思考档位参数错误，限流/超时/余额等绝不触发降级（宁可漏判不要误判）。
 *
 * 流式场景下只有「还没吐出任何内容」时才允许重发，避免把已显示的半截回答重复一遍。
 * 官方请求构造代码一行都不用改。
 */
internal class ReasoningFallbackProvider<T : ProviderSetting>(
    private val delegate: Provider<T>,
) : Provider<T> {

    override suspend fun listModels(providerSetting: T): List<Model> =
        delegate.listModels(providerSetting)

    override suspend fun getBalance(providerSetting: T): String =
        delegate.getBalance(providerSetting)

    override suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val effective = params.withEffectiveReasoningLevel()
        return try {
            delegate.generateText(providerSetting, messages, effective)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!isReasoningRejection(e)) throw e
            val denied = effective.reasoningLevel
            MaxEffortDenyList.deny(effective.model.modelId, denied)
            val next = effective.nextUsableReasoningLevel(denied)
                ?: throw e // 连关闭思考都被拒：放弃，把原始错误给用户
            delegate.generateText(providerSetting, messages, effective.copy(reasoningLevel = next))
        }
    }

    override suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        val effective = params.withEffectiveReasoningLevel()
        return flow {
            var emitted = 0
            delegate.streamText(providerSetting, messages, effective)
                .onEach { emitted++ }
                .catch { e ->
                    if (e is CancellationException) throw e
                    // 已经吐过内容就不能重发，否则用户会看到重复的半截回答
                    if (emitted > 0 || !isReasoningRejection(e)) throw e
                    val denied = effective.reasoningLevel
                    MaxEffortDenyList.deny(effective.model.modelId, denied)
                    val next = effective.nextUsableReasoningLevel(denied)
                        ?: throw e // 连关闭思考都被拒：放弃，把原始错误给用户
                    emitAll(
                        delegate.streamText(
                            providerSetting,
                            messages,
                            effective.copy(reasoningLevel = next),
                        )
                    )
                }
                .collect { emit(it) }
        }
    }

    override suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult = delegate.generateEmbedding(providerSetting, params)

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = delegate.generateImage(providerSetting, params)

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> = delegate.editImage(providerSetting, params)
}

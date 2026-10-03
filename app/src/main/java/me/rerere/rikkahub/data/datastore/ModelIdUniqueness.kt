package me.rerere.rikkahub.data.datastore

import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.uuid.Uuid
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting

/**
 * v311：消除「同一家应用里出现两个一模一样的模型编号」。
 *
 * ## 修的是什么真机故障（用户原话）
 *
 * > 「子代理现在是同名的话，即使我切换了其他同名子代理（不同上游供应商）还是会用旧的
 * > 继续；而且我子代理是 a（供应商1）替补是 a b c（供应商2），会直接跳过替补 a（供应商2）」
 *
 * ## 根因
 *
 * 应用内部靠 `Model.id`（一个 UUID）认模型：换模型、候选链、`findModelById` 全都用它。
 * 但不同来源会给**不同供应商下的同名模型**发出**同一个 UUID**：
 *
 * - Chatbox 导入用 `stableUuid("chatbox:model:$providerKey:$modelId")`
 *   （见 ChatboxImporter），是**算出来的固定值**，不含本应用那条供应商记录的身份；
 * - 同一份备份导入两次、或导入的条目与手填的条目撞名，都会产出重复编号。
 *
 * 编号一旦重复，用户看到的现象就是必然的：
 *   - `findModelById` 返回**第一个**持有者 → 点第二家的 a 仍然跑第一家的 a；
 *   - 候选链 `distinctBy { it.id }` 把第二家的 a 当成重复项吃掉 → 替补被跳过；
 *   - 模型列表以 `it.id` 作为 Compose key，重复 key 会直接抛异常。
 *
 * ## 为什么在「读设置」时修而不是在各处打补丁
 *
 * 设置里按模型编号存东西的地方有十几处（chatModelId / fastModelId / ocrModelId /
 * compressModelId / agentModelId / agentFallbackModelIds / conversationModelIds /
 * agentRoleModelOverrides / favoriteModels / autoCompressModelOverrides /
 * roundTable 的各阶段模型 …）。逐处改成「供应商+编号」双键定位的改动面太大、
 * 回归风险高；在编号这一层一次性修好，所有下游自动正确。
 *
 * ## 为什么「保留第一个、给后面的重发」是安全的
 *
 * 编号重复时 `findModelById` 本来就**只能**返回第一个持有者，也就是说：所有存量引用
 * （设置里的、历史消息里的 `modelId`）**实际生效的指向一直是第一个**。
 * 我们保留第一个的编号不动、只给后面的重复项换新号，等于把这些引用"钉"在它们
 * 本来就在用的那个模型上，不改变任何既有行为 —— 只是让第二家从此**可以被单独选中**。
 *
 * ## 为什么必须确定性（不能用 Uuid.random()）
 *
 * 这段代码在每次读设置时都会跑。若用随机数，每次加载都会给同一批模型换一个新号，
 * 界面会抖、历史引用全部失效。这里由「本条供应商记录的 id + 原模型编号 + 模型名」
 * 算出固定值，同一份脏数据每次算出的结果完全一致（有单测钉住）。
 */
internal data class ModelIdDedupeResult(
    val providers: List<ProviderSetting>,
    /** 被重发编号的模型条数；0 = 数据本来就没问题（调用方据此决定要不要写日志/落库） */
    val reissued: Int,
)

/**
 * 消除跨供应商（以及同供应商内）的重复 `Model.id`。
 *
 * 顺序敏感：按 providers 的列表顺序、每个 provider 内 models 的列表顺序，
 * **第一个出现的保留原编号**，后续撞号的重发新编号。
 */
internal fun dedupeModelIds(providers: List<ProviderSetting>): ModelIdDedupeResult {
    if (providers.size < 1) return ModelIdDedupeResult(providers, 0)

    val seen = HashSet<Uuid>(128)
    // 快速路径：绝大多数用户的设置本来就没有重复编号，先整体扫一遍再决定是否重建。
    val total = providers.sumOf { it.models.size }
    providers.forEach { provider -> provider.models.forEach { seen.add(it.id) } }
    if (seen.size == total) return ModelIdDedupeResult(providers, 0)

    val taken = HashSet<Uuid>(seen.size + 8)
    var reissued = 0
    val fixed = providers.map { provider ->
        if (provider.models.isEmpty()) {
            provider
        } else {
            val models = provider.models.map { model ->
                if (taken.add(model.id)) {
                    model
                } else {
                    val newId = nextFreeModelId(provider, model, taken)
                    taken.add(newId)
                    reissued++
                    model.copy(id = newId)
                }
            }
            provider.copyProvider(models = models)
        }
    }
    return ModelIdDedupeResult(fixed, reissued)
}

/**
 * 为一条重复编号的模型算出一个没被占用的新编号。
 *
 * 种子刻意只用「本条供应商记录的 id + 它原本的错误编号 + 供应商内的模型名」三样：
 * 三者在设置数据里都是稳定的字符串，因此同一份脏数据任何时刻、任何设备上重跑
 * 都会得到同一个新编号。（不用 provider 名字 —— 名字用户可以随便改，一改编号就漂。）
 */
private fun nextFreeModelId(
    provider: ProviderSetting,
    model: Model,
    taken: Set<Uuid>,
): Uuid {
    var salt = 0
    while (true) {
        val seed = "v311:model-id:${provider.id}:${model.id}:${model.modelId}" +
            if (salt == 0) "" else ":$salt"
        val candidate = Uuid.parse(
            UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()
        )
        if (candidate !in taken) return candidate
        salt++
    }
}

package me.rerere.rikkahub.agent.model

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.displayLabel
import me.rerere.rikkahub.data.datastore.findModelById

/**
 * v1029：主模型点名子代理模型时的一份选项。
 *
 * [ref] 是给主模型看的稳定名字（default / fallback_1 / fallback_2 / fallback_3），
 * [label] 是「模型名 · 供应商名」，[modelId] 是真实 UUID。
 *
 * 为什么不让主模型直接记 UUID：UUID 很长、容易抄错，而且模型改名/换供应商后
 * UUID 不变但名字会变 —— 主模型只需要认得 ref 和 label，解析由工具层做。
 */
data class AgentModelChoice(
    val ref: String,
    val label: String,
    val modelId: String,
)

/**
 * v1029：从设置里组装「默认子代理模型 + 备用模型 1~3」。
 *
 * 只列真实存在的模型；被删除或配置里不存在的条目直接跳过。备用模型的 ref
 * 保留下标（第一条缺失时，第二条仍然是 fallback_2），这样主模型看到的编号与
 * 用户在设置里看到的顺序一致，不会因为中间一条坏了就整体前移。
 */
internal fun buildAgentModelCatalog(settings: Settings): List<AgentModelChoice> = buildList {
    val defaultId = settings.agentModelId ?: settings.chatModelId
    settings.findModelById(defaultId)?.let { model ->
        add(
            AgentModelChoice(
                ref = "default",
                label = model.displayLabel(settings.providers),
                modelId = model.id.toString(),
            )
        )
    }
    settings.agentFallbackModelIds.forEachIndexed { index, id ->
        settings.findModelById(id)?.let { model ->
            add(
                AgentModelChoice(
                    ref = "fallback_${index + 1}",
                    label = model.displayLabel(settings.providers),
                    modelId = model.id.toString(),
                )
            )
        }
    }
}

/**
 * v1029：把主模型写的 model_ref 解析成清单里的那一项。
 *
 * 大小写不敏感、忽略首尾空格；未知引用返回 null，由工具层给出「可用 ref」清单。
 * 这里故意不做「猜一个最像的」——静默选错模型比明确报错危险得多。
 */
internal fun resolveAgentModelRef(
    catalog: List<AgentModelChoice>,
    ref: String?,
): AgentModelChoice? {
    val key = ref?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return catalog.firstOrNull { it.ref == key }
}

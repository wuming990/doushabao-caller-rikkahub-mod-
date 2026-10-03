package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting

/**
 * v311：给模型算「带供应商标识」的展示名。
 *
 * ## 为什么需要
 *
 * 应用内部靠 `Model.id`（一个 UUID）认模型，那个是唯一的；但**名字不是** ——
 * 两家供应商放同一个模型名（尤其是各家中转站都叫 `glm-4.6`、`deepseek-chat` 这类）
 * 是极常见的用法。
 *
 * 用户真机反馈原话：
 * > 「子代理同名时，即使我切换了其他同名子代理（不同上游供应商）还是会用旧的
 * > 子代理继续」
 *
 * 这里有一层确定性的展示缺陷：换人**之前**印「glm-4.6」，换人**之后**还是印
 * 「glm-4.6」，一个字都不差。用户没有任何办法判断到底换没换成，观感就是「没换」。
 * 凡「用户要靠这行字判断现在是谁在跑」的地方都应该用它：子代理换模型事件、
 * 接力顺序预览、备用模型条目、子代理详情页的「本次实际用的模型」。
 *
 * 供应商名为空时退回纯模型名 —— 不显示「 · 」这种空尾巴。
 */
fun Model.displayLabel(providers: List<ProviderSetting>): String =
    modelLabel(displayName, findProvider(providers)?.name)

/** [displayLabel] 的非扩展版：手上只有名字字符串（例如候选链快照）时用这一层 */
fun modelLabel(displayName: String, providerName: String?): String =
    if (providerName.isNullOrBlank()) displayName else "$displayName · $providerName"

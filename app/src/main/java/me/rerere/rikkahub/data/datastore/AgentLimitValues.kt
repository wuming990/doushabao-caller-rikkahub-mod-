package me.rerere.rikkahub.data.datastore

/**
 * v1024：子代理数值由用户手填，不再夹到软件预设的8/96/256等档位。
 * 0只有并发、步数、工具返回代表不限；自动续跑0仍关闭，两个时间项仍用原默认语义。
 * Int.MAX_VALUE仅是既有循环/字符串API的可表示边界，不是额外的业务配额。
 */
internal fun parseAgentInteger(text: String, zeroAllowed: Boolean): Int? {
    val raw = text.trim()
    if (raw.isEmpty() || raw.any { it !in '0'..'9' }) return null
    val number = raw.toIntOrNull() ?: return null
    return number.takeIf { it > 0 || (zeroAllowed && it == 0) }
}

internal fun agentHasCapacity(activeCount: Int, configured: Int): Boolean =
    configured == 0 || activeCount < (configured.takeIf { it > 0 } ?: 4)

internal fun agentStepBudget(readonlySteps: Int, writable: Boolean): Int {
    if (readonlySteps == 0) return Int.MAX_VALUE
    val base = readonlySteps.takeIf { it > 0 } ?: 32
    return (if (writable) base.toLong() * 3 / 2 else base.toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun agentOutputCharLimit(kb: Int): Int =
    if (kb == 0) Int.MAX_VALUE else
        ((kb.takeIf { it > 0 } ?: 64).toLong() * 1024L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** v1024：工具说明也要把0写成unlimited，不能误导主模型认为不允许派发。 */
internal fun agentConcurrencyLabel(configured: Int): String =
    if (configured == 0) "unlimited" else (configured.takeIf { it > 0 } ?: 4).toString()

/** v1024：坏备份里的负数不能被夹成0而意外打开无限。正数原样保留。 */
internal fun normalizeAgentLimit(value: Int, defaultValue: Int, zeroAllowed: Boolean): Int =
    if (value > 0 || (zeroAllowed && value == 0)) value else defaultValue

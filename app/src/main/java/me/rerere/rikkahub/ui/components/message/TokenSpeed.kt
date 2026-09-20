package me.rerere.rikkahub.ui.components.message

/**
 * v303：把「输出 token 数 + 耗时」换算成每秒 token。
 *
 * 抽成纯函数是为了能单测（本模块 testImplementation 只有 junit，造不出 Compose 环境）。
 *
 * 口径与外面工具对齐：分母不含工具执行时间、不含用户审批等待，两派的分母都不含；
 * 两个速度的唯一区别是「要不要把首字等待摊进去」——
 * 不含首字那份用 cumulativeGenerationMs，含首字那份用 cumulativeResponseMs。
 *
 * 耗时缺失或非正、token 非正时返回 null —— 界面据此不显示这一格，
 * 绝不用 0 或 ≈0 冒充（与 token 花费那一处的口径一致）。
 */
internal fun computeTokenSpeed(tokens: Int, durationMs: Long?): Float? {
    val ms = durationMs ?: return null
    if (ms <= 0L || tokens <= 0) return null
    return tokens.toFloat() / ms.toFloat() * 1000f
}

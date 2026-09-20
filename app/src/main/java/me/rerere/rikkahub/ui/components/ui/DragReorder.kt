package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs

/**
 * v304：长按拖动换位（列表 / 横向条带排序用）。
 *
 * ## 为什么自己写一个
 * 对话文件夹与技能列表都需要「用户自己排顺序」。菜单里放「上移 / 下移」虽然简单，
 * 但用户明确选了**长按拖动**（更顺手）。项目里原本没有任何拖动排序的写法，
 * 所以这里做成一个共用的小件，只被这两处调用 —— **主对话与圆桌一个字都没动**。
 *
 * ## 交互口径（刻意定死，别改歪）
 * - **长按不动、直接松手** → 调 [onLongPressWithoutMove]。文件夹条靠它保住原来的
 *   「长按弹出重命名/删除菜单」习惯，所以拖动手势**没有夺走**旧操作。
 * - **长按后拖动** → 每越过「一个身位」（本节点尺寸的 60%，最少 24dp）就与相邻项换一次位，
 *   换位时给一次震动反馈；松手时调 [onDrop]（调用方据此落盘）。
 * - 换位是**逐格**做的：不做跟手浮层（那需要测量每个兄弟节点的位置，且容易与滚动打架）。
 *   好处是逻辑简单、不会出现「拖了一半卡在两项之间」的中间态。
 * - 拖动过程中 `change.consume()`：既抢在外层列表滚动之前，也让外层 `clickable`
 *   不再把「长按松手」当成一次点击（Compose 的拖动检测在收尾时会消费抬手事件）。
 *
 * ## 调用方的义务
 * - 列表必须用稳定的 `key`（否则换位时 Compose 会重建节点，拖动会断）。
 * - [onMove] 要自己判越界：`to` 可能等于 -1 或「最后一项 + 1」，越界请忽略。
 * - 拖动期间建议让列表项带 `Modifier.animateItem()`，换位才会有动画。
 *
 * @param index 本项在当前列表里的下标（换位后由调用方重新传入新下标）
 * @param horizontal true = 横向条带（文件夹条），false = 纵向列表（技能列表）
 * @param onMove 交换回调：把 [from] 位置移到 [to] 位置（相邻一格）
 * @param onDrop 发生过换位、松手时回调一次（落盘用）
 * @param onLongPressWithoutMove 长按后没拖动就松手时回调（可为空 = 什么也不做）
 * @param onDragStateChange 拖动会话状态变化（true = 已进入拖动，false = 拖动结束）。
 *   调用方用它挡住「长按松手时外层 clickable 顺带触发一次点击」—— 这类点击有可能
 *   先于本组件收尾回调到达，所以调用方应把 false 之后留一小段宽限窗口再放行。
 */
@Composable
fun Modifier.dragToReorder(
    index: Int,
    horizontal: Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit = {},
    onLongPressWithoutMove: (() -> Unit)? = null,
    onDragStateChange: (Boolean) -> Unit = {},
): Modifier {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val minThresholdPx = with(density) { DRAG_REORDER_MIN_THRESHOLD_DP.dp.toPx() }

    // 换位后本项的下标会变，用 rememberUpdatedState 保证手势里读到的永远是最新值；
    // pointerInput 的 key 必须是 Unit —— 若把 index 放进 key，换一次位就会重启手势、
    // 拖动会在第一次换位后中断（这是 Compose 拖动排序最常踩的坑）。
    val latestIndex by rememberUpdatedState(index)
    val latestMove by rememberUpdatedState(onMove)
    val latestDrop by rememberUpdatedState(onDrop)
    val latestLongPressTap by rememberUpdatedState(onLongPressWithoutMove)
    val latestDragState by rememberUpdatedState(onDragStateChange)

    var dragging by remember { mutableStateOf(false) }

    val gestureModifier = this
        .zIndex(if (dragging) 1f else 0f)
        .pointerInput(Unit) {
            var accumulated = 0f
            var moved = false
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    dragging = true
                    moved = false
                    accumulated = 0f
                    latestDragState(true)
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                },
                onDragEnd = {
                    dragging = false
                    accumulated = 0f
                    latestDragState(false)
                    if (moved) latestDrop() else latestLongPressTap?.invoke()
                },
                onDragCancel = {
                    dragging = false
                    accumulated = 0f
                    latestDragState(false)
                    if (moved) latestDrop()
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    val delta = if (horizontal) dragAmount.x else dragAmount.y
                    accumulated += delta
                    val unit = (if (horizontal) size.width else size.height).toFloat()
                    val threshold = (unit * DRAG_REORDER_SWAP_RATIO).coerceAtLeast(minThresholdPx)
                    if (abs(accumulated) >= threshold) {
                        val from = latestIndex
                        val target = from + if (accumulated > 0f) 1 else -1
                        accumulated = 0f
                        // 「真的移动过」只认**发生过换位**：手指抖一点点就松手时，
                        // 仍按「长按不动」处理（文件夹条靠它保住原来的重命名/删除菜单）。
                        // 审查位指出：若按位移判定，手抖 10dp 就会出现「菜单不弹、顺序也没变、
                        // 看起来什么都没发生」的中间态。
                        moved = true
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        latestMove(from, target)
                    }
                },
            )
        }

    return if (dragging) gestureModifier.scale(DRAG_REORDER_DRAGGING_SCALE) else gestureModifier
}

/** 越过「一个身位」的多少比例才换位。0.6 = 要拖过本项六成尺寸，避免一碰就跳。 */
private const val DRAG_REORDER_SWAP_RATIO = 0.6f

/** 换位所需位移的下限（dp）。列表项很窄（如文件夹小胶囊）时不至于太灵敏。 */
private const val DRAG_REORDER_MIN_THRESHOLD_DP = 24

/** 拖动中本项的放大倍数（给一点「被抓住」的视觉反馈）。 */
private const val DRAG_REORDER_DRAGGING_SCALE = 1.06f

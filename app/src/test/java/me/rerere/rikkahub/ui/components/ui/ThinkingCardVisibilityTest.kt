package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v307：「思考卡片单步可收起」的可见性规则单测。
 *
 * 真跑函数、直接断言返回值（不读源码字符串），因为规则已抽成纯函数
 * [shouldPreviewThinkingTail] / [visibleThinkingSteps]。
 *
 * 规则一句话：开关打开后，生成中显示尾部步骤、生成结束全部收起、
 * 有步骤在等用户操作时这些步骤必须落在可见范围内。
 */
class ThinkingCardVisibilityTest {

    private val fourSteps = listOf("s1", "s2", "s3", "s4")

    // ---------- 官方行为（开关关闭）必须一个字节都不变 ----------

    @Test
    fun `开关关闭时折叠保留尾部两步`() {
        assertEquals(
            listOf("s3", "s4"),
            visibleThinkingSteps(
                steps = fourSteps,
                expanded = false,
                canCollapse = true,
                fullyCollapsible = false,
                previewTail = true, // 即使上层误传 true，也不许影响官方模式
                minVisibleTailCount = 0,
                collapsedVisibleCount = 2,
            ),
        )
    }

    @Test
    fun `开关关闭时忽略尾部预览与最小可见步数`() {
        assertEquals(
            listOf("s3", "s4"),
            visibleThinkingSteps(
                steps = fourSteps,
                expanded = false,
                canCollapse = true,
                fullyCollapsible = false,
                previewTail = true,
                minVisibleTailCount = 4, // 开关关着时这个参数必须完全没有作用
                collapsedVisibleCount = 2,
            ),
        )
    }

    @Test
    fun `开关关闭时展开显示全部`() {
        assertEquals(
            fourSteps,
            visibleThinkingSteps(
                steps = fourSteps,
                expanded = true,
                canCollapse = true,
                fullyCollapsible = false,
                previewTail = false,
                minVisibleTailCount = 0,
                collapsedVisibleCount = 2,
            ),
        )
    }

    // ---------- 开关打开后的三种情形 ----------

    @Test
    fun `开关打开且生成中显示尾部两步`() {
        assertEquals(
            listOf("s3", "s4"),
            visibleThinkingSteps(
                steps = fourSteps,
                expanded = false,
                canCollapse = true,
                fullyCollapsible = true,
                previewTail = true,
                minVisibleTailCount = 0,
                collapsedVisibleCount = 2,
            ),
        )
    }

    @Test
    fun `开关打开且生成结束全部收起`() {
        assertEquals(
            emptyList<String>(),
            visibleThinkingSteps(
                steps = fourSteps,
                expanded = false,
                canCollapse = true,
                fullyCollapsible = true,
                previewTail = false,
                minVisibleTailCount = 0,
                collapsedVisibleCount = 2,
            ),
        )
    }

    @Test
    fun `开关打开时单步卡片生成中仍可见`() {
        assertEquals(
            listOf("s1"),
            visibleThinkingSteps(
                steps = listOf("s1"),
                expanded = false,
                canCollapse = true,
                fullyCollapsible = true,
                previewTail = true,
                minVisibleTailCount = 0,
                collapsedVisibleCount = 2,
            ),
        )
    }

    @Test
    fun `开关打开时单步卡片生成结束可被完全收起`() {
        assertEquals(
            emptyList<String>(),
            visibleThinkingSteps(
                steps = listOf("s1"),
                expanded = false,
                canCollapse = true,
                fullyCollapsible = true,
                previewTail = false,
                minVisibleTailCount = 0,
                collapsedVisibleCount = 2,
            ),
        )
    }

    @Test
    fun `开关打开时用户手动展开显示全部且不被自动收回`() {
        assertEquals(
            fourSteps,
            visibleThinkingSteps(
                steps = fourSteps,
                expanded = true,
                canCollapse = true,
                fullyCollapsible = true,
                previewTail = false, // 生成已结束，但用户自己点开过 → 尊重用户
                minVisibleTailCount = 0,
                collapsedVisibleCount = 2,
            ),
        )
    }

    @Test
    fun `不可折叠时无论如何都显示全部`() {
        assertEquals(
            fourSteps,
            visibleThinkingSteps(
                steps = fourSteps,
                expanded = false,
                canCollapse = false,
                fullyCollapsible = true,
                previewTail = false,
                minVisibleTailCount = 0,
                collapsedVisibleCount = 2,
            ),
        )
    }

    @Test
    fun `折叠保留数被设成零时尾部预览仍至少显示最后一步`() {
        assertEquals(
            listOf("s4"),
            visibleThinkingSteps(
                steps = fourSteps,
                expanded = false,
                canCollapse = true,
                fullyCollapsible = true,
                previewTail = true,
                minVisibleTailCount = 0,
                collapsedVisibleCount = 0,
            ),
        )
    }

    // ---------- 等用户操作的步骤必须落在可见范围内（防按钮被藏） ----------

    @Test
    fun `等操作的步骤排在倒数第三位时也要显示到它为止`() {
        // 一次发出多个工具调用、需要确认的那个排在前面时的真实形态
        assertEquals(
            listOf("pending", "t1", "t2"),
            visibleThinkingSteps(
                steps = listOf("pending", "t1", "t2"),
                expanded = false,
                canCollapse = true,
                fullyCollapsible = true,
                previewTail = true,
                minVisibleTailCount = 3,
                collapsedVisibleCount = 2,
            ),
        )
    }

    @Test
    fun `等操作的步骤排在倒数第三位且生成已结束时同样显示`() {
        // 生成任务在等审批时已经结束（loading=false），此时更要保证按钮可见
        assertEquals(
            listOf("pending", "t1", "t2"),
            visibleThinkingSteps(
                steps = listOf("pending", "t1", "t2"),
                expanded = false,
                canCollapse = true,
                fullyCollapsible = true,
                previewTail = false,
                minVisibleTailCount = 3,
                collapsedVisibleCount = 2,
            ),
        )
    }

    @Test
    fun `最小可见步数比尾部预览步数小时不缩水`() {
        assertEquals(
            listOf("s3", "s4"),
            visibleThinkingSteps(
                steps = fourSteps,
                expanded = false,
                canCollapse = true,
                fullyCollapsible = true,
                previewTail = true,
                minVisibleTailCount = 1,
                collapsedVisibleCount = 2,
            ),
        )
    }

    @Test
    fun `最小可见步数超过总步数时显示全部`() {
        assertEquals(
            fourSteps,
            visibleThinkingSteps(
                steps = fourSteps,
                expanded = false,
                canCollapse = true,
                fullyCollapsible = true,
                previewTail = true,
                minVisibleTailCount = 99,
                collapsedVisibleCount = 2,
            ),
        )
    }

    // ---------- 「该不该显示尾部」的判定 ----------

    @Test
    fun `生成中且是最后一块才预览尾部`() {
        assertTrue(
            shouldPreviewThinkingTail(
                messageLoading = true,
                isLastBlock = true,
                hasPendingInteraction = false,
            )
        )
        assertFalse(
            shouldPreviewThinkingTail(
                messageLoading = true,
                isLastBlock = false,
                hasPendingInteraction = false,
            )
        )
    }

    @Test
    fun `生成结束且没有待用户操作的步骤时不预览尾部`() {
        assertFalse(
            shouldPreviewThinkingTail(
                messageLoading = false,
                isLastBlock = true,
                hasPendingInteraction = false,
            )
        )
    }

    @Test
    fun `有待用户操作的步骤时即使生成结束也必须预览`() {
        // 这一条是防「按钮被藏」：工具等审批 / 询问用户等回答时生成任务已经结束，
        // 若跟着收起，确认与回答按钮就看不见了（要用户自己想到去点控制条才能找回）。
        assertTrue(
            shouldPreviewThinkingTail(
                messageLoading = false,
                isLastBlock = false,
                hasPendingInteraction = true,
            )
        )
    }

    // ---------- 接线守卫（合并上游被整覆盖时直接变红） ----------

    private val root: java.io.File? by lazy {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !java.io.File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir
    }

    private fun source(vararg path: String): String {
        val dir = checkNotNull(root) { "找不到工程根目录" }
        return java.io.File(dir, path.joinToString("/")).readText()
    }

    @Test
    fun `聊天消息把尾部预览判定接进去了`() {
        val message = source(
            "app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt"
        )
        assertTrue(message.contains("shouldPreviewThinkingTail("))
        assertTrue(message.contains("fullyCollapsible = settings.displaySetting.collapseThinkingCardFully"))
        assertTrue(message.contains("previewTail = previewTail"))
        assertTrue(message.contains("minVisibleTailCount ="))
        // 等待用户确认/回答的步骤必须参与判定（防按钮被藏）
        assertTrue(message.contains("ThinkingStep.ToolStep && step.tool.isPending"))
        assertTrue(message.contains("indexOfFirst"))
    }

    @Test
    fun `思考卡片组件走可见性纯函数`() {
        val card = source(
            "app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt"
        )
        assertTrue(card.contains("visibleThinkingSteps("))
        assertTrue(card.contains("previewTail: Boolean = false"))
        assertTrue(card.contains("minVisibleTailCount: Int = 0"))
    }
}

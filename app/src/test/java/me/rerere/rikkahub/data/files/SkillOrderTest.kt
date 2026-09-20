package me.rerere.rikkahub.data.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v304：技能顺序与拖动换位的**真单测**（直接喂数据断言返回值，不读源码字符串）。
 *
 * 为什么这两件事必须单测：顺序一旦算歪，用户拖完看着正常、重进 App 就乱了，
 * 而且没有任何报错。排序规则与换位规则都是纯函数，所以这里能真跑。
 */
class SkillOrderTest {

    private fun skill(dirName: String) = SkillMetadata(
        name = dirName,
        description = "描述",
        skillDir = File("/tmp/skills/$dirName"),
    )

    @Test
    fun `按保存的顺序排_表里没出现的排在后面并按目录名`() {
        val list = listOf(skill("b"), skill("a"), skill("c"))
        val sorted = sortSkillsByOrder(list, listOf("c", "a"))
        assertEquals(listOf("c", "a", "b"), sorted.map { it.skillDir.name })
    }

    @Test
    fun `顺序表为空时按目录名排_顺序稳定`() {
        val list = listOf(skill("z"), skill("a"), skill("m"))
        assertEquals(
            listOf("a", "m", "z"),
            sortSkillsByOrder(list, emptyList()).map { it.skillDir.name },
        )
    }

    @Test
    fun `表里有但技能已删除的条目直接忽略_不影响其它技能`() {
        val list = listOf(skill("a"), skill("b"))
        val sorted = sortSkillsByOrder(list, listOf("gone", "b"))
        assertEquals(listOf("b", "a"), sorted.map { it.skillDir.name })
    }

    @Test
    fun `技能目录大小为0或1时不会出错`() {
        assertEquals(emptyList<String>(), sortSkillsByOrder(emptyList(), listOf("a")).map { it.skillDir.name })
        assertEquals(
            listOf("a"),
            sortSkillsByOrder(listOf(skill("a")), listOf("a")).map { it.skillDir.name },
        )
    }

    @Test
    fun `拖动换位_相邻与跨位都对`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c", "d"), moveItemTo(list, 0, 1))
        assertEquals(listOf("a", "c", "d", "b"), moveItemTo(list, 1, 3))
        assertEquals(listOf("d", "a", "b", "c"), moveItemTo(list, 3, 0))
    }

    @Test
    fun `拖动换位_越界与原地原样返回_不抛异常`() {
        val list = listOf("a", "b")
        assertEquals(list, moveItemTo(list, 0, 0))
        assertEquals(list, moveItemTo(list, -1, 1))
        assertEquals(list, moveItemTo(list, 0, 5))
        assertEquals(list, moveItemTo(list, 2, 0))
        assertEquals(list, moveItemTo(list, 1, -1))
    }
}

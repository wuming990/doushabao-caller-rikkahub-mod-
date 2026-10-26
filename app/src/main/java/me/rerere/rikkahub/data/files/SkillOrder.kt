package me.rerere.rikkahub.data.files
import me.rerere.rikkahub.data.skills.SkillMetadata

/**
 * v304：技能列表的显示顺序。
 *
 * 顺序由用户拖动决定，存在设置里（`Settings.skillOrder`，存的是**技能目录名**：
 * 目录名是唯一稳定标识，frontmatter 里的 name 可能重复、也可能被用户改）。
 *
 * 排序规则（刻意做成全序，避免「没排过的技能」位置随机）：
 * 1. 出现在顺序表里的技能，按表里的先后；
 * 2. 表里没有的（新导入的），排在后面，按目录名（不区分大小写）排；
 * 3. 表里有、但技能已不存在的条目直接忽略 —— 不清理存储，重新导入还能回到原来的位置。
 *
 * 纯函数，不依赖 Android，可直接单测。
 */
internal fun sortSkillsByOrder(
    skills: List<SkillMetadata>,
    order: List<String>,
): List<SkillMetadata> {
    if (skills.size <= 1) return skills
    if (order.isEmpty()) return skills.sortedBy { it.skillDir.name.lowercase() }
    val rank = order.withIndex().associate { (index, name) -> name to index }
    return skills.sortedWith(
        compareBy(
            { skill -> rank[skill.skillDir.name] ?: Int.MAX_VALUE },
            { skill -> skill.skillDir.name.lowercase() },
        )
    )
}

/**
 * v304：把 [from] 位置上的元素移动到 [to] 位置（拖动换位用）。
 *
 * 越界或原地移动时原样返回 —— 拖动过程中偶发的错位不该抛异常。
 * 文件夹排序与技能排序共用这一个函数。
 */
internal fun <T> moveItemTo(list: List<T>, from: Int, to: Int): List<T> {
    if (from !in list.indices || to !in list.indices || from == to) return list
    val mutable = list.toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return mutable
}

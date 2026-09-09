package me.rerere.rikkahub.service

/**
 * v229：圆桌「本轮任务」守卫。
 *
 * ## 为什么需要它（真机事故）
 *
 * 用户新建对话直接开圆桌，结果第一个模型（任务合同）收到的上文里**只有助手的预设内容，
 * 没有用户这次的要求**，它自己在记录里写道「并未附带或传入你在开启圆桌前输入的原始
 * 具体业务要求」，只能凭空编一个任务出来；而用户输入框里打的字还被清空了。
 *
 * 根因是两个洞叠在一起：
 * 1. 确认框的「开始」按钮放行条件是「任务框有字 **或** 对话里已经有内容」。
 *    助手配了预设内容时，新建对话一打开就算「已经有内容」，于是**空任务也能开跑**；
 * 2. 圆桌保存用户消息那段是 `if (!content.isEmptyInputMessage()) { ... }`，
 *    任务为空就整段跳过，对话里从此没有用户的要求，界面随后又把输入框清空了。
 *
 * 所以这里把判定收紧成一句话：**预设内容不算用户说过的话。**
 * 纯逻辑、不依赖 Android，界面与服务层共用同一套判定，可直接单测。
 */
internal object RoundTableTaskGuard {

    /**
     * 这段历史里有没有「用户真正说过的话」。
     *
     * @param userTexts 会话里所有用户消息的文本（调用方负责取 role == USER）
     * @param presetTexts 助手预设消息的文本
     */
    fun hasRealUserTask(userTexts: List<String>, presetTexts: List<String>): Boolean {
        val presets = presetTexts.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        return userTexts.any { raw ->
            val text = raw.trim()
            text.isNotBlank() && text !in presets
        }
    }

    /**
     * 这一轮圆桌能不能开跑。
     *
     * @param taskText 用户在确认框里写的本轮任务
     * @param hasAttachment 有没有附件（只带附件也算给了材料）
     */
    fun canStart(
        taskText: String,
        hasAttachment: Boolean,
        userTexts: List<String>,
        presetTexts: List<String>,
    ): Boolean = taskText.isNotBlank() ||
        hasAttachment ||
        hasRealUserTask(userTexts, presetTexts)
}

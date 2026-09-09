package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
sealed class Avatar {
    @Serializable
    data object Dummy : Avatar()

    @Serializable
    data class Emoji(val content: String) : Avatar()

    @Serializable
    data class Image(val url: String) : Avatar()

    /**
     * v271：App 内置的默认用户头像（drawable 资源 default_user_avatar）。
     *
     * **只在渲染层使用，绝不落库**：显示设置里存的仍是 [Dummy]，
     * 由 [resolveUserDefaultAvatar] 在取值渲染时把 Dummy 换成本类型。
     * 这样老安装无需任何数据迁移就能直接看到新默认头像，
     * 而助手（Assistant）的默认头像仍走 Dummy 的程序生成头像，不受影响。
     */
    @Serializable
    data object Default : Avatar()
}

/**
 * v271：用户默认头像解析 —— 用户头像为 Dummy（默认值）时渲染内置图片。
 *
 * 助手头像**不要**调用此函数：助手的 Dummy 语义是"程序生成头像"，保持原样。
 * 头像选择器里的"重置"存回 Dummy，渲染时同样落到内置图片，语义自然成立。
 */
fun Avatar.resolveUserDefaultAvatar(): Avatar = if (this is Avatar.Dummy) Avatar.Default else this

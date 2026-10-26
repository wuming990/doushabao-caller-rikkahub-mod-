<div align="center">
  <img src="docs/icon.png" alt="豆沙包调用器" width="100" />
  <h1>豆沙包调用器（基于 RikkaHub 二开）</h1>

一个原生 Android LLM 聊天客户端，支持切换不同的供应商进行聊天 💬

[English](README.md) | [繁體中文](README_ZH_TW.md) | 简体中文 ｜ **上游基线 RikkaHub 3.1.1** ｜ 本版 **3.1.1-mod15**

</div>

> [!NOTE]
> 这里是**二改版「豆沙包调用器」**的仓库：下方介绍页正文来自上游 RikkaHub（AGPL-3.0），我们只删掉了官方 QQ 群 / Discord / 赞助商 / 捐赠等内容，功能介绍仍属上游。
> 二改版安装包在本仓库 **Releases** 页面下载；官方原版请到上游仓库获取。

## 🚀 下载

🔗 [前往官网下载](https://rikka-ai.com/download)（推荐）

🔗 [前往 Google Play 下载](https://play.google.com/store/apps/details?id=me.rerere.rikkahub)

> [!WARNING]
> RikkaHub 存在许多 fork 版本，fork 版本出现问题与 RikkaHub 无关，请谨慎使用 fork 版本，避免隐私泄露或者过度索要权限问题。

## ✨ 功能特色

- 🎨 现代化安卓APP设计（Material 3 Expressive / 预测性返回）和 🌙 暗色模式
- 📦 工作区：基于 proot 的 Linux 智能体环境
- 🎬 媒体生成（图片、视频）
- 🖥️ Web多端访问支持
- 🛠️ MCP 与 Skills 支持
- 🔄 多种类型的供应商支持，自定义 API / URL / 模型（目前支持 OpenAI、Google、Anthropic）
- 🖼️ 多模态输入支持
- 📝 Markdown 渲染（支持代码高亮、数学公式、表格、Mermaid）
- 🔍 搜索功能（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity、..）
- 🧩 Prompt 变量（模型名称、时间等）
- 🤳 二维码导出和导入提供商
- 🤖 智能体自定义
- 🧠 基于文件的记忆
- 📝 AI翻译
- 🌐 自定义HTTP请求头和请求体

## ✨ 开发

> [!IMPORTANT]
> 本项目不接受 Pull Request（PR）。

本项目使用[Android Studio](https://developer.android.com/studio)开发。

技术栈文档:

- [Kotlin](https://kotlinlang.org/) (开发语言)
- [Koin](https://insert-koin.io/) (依赖注入)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI 框架)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore?hl=zh-cn#preferences-datastore) (
  偏好数据存储)
- [Room](https://developer.android.com/training/data-storage/room) (数据库)
- [Coil](https://coil-kt.github.io/coil/) (图片加载)
- [Material 3 Expressive](https://m3.material.io/) (UI 设计)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) (导航)
- [Okhttp](https://square.github.io/okhttp/) (HTTP 客户端)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Json序列化)

> [!TIP]
> 你需要在 `app` 文件夹下添加 `google-services.json` 文件才能构建应用。

## 📄 许可证

本项目基于 [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0) 开源。

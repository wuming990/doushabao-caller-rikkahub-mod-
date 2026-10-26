<div align="center">
  <img src="docs/icon.png" alt="豆沙包调用器" width="100" />
  <h1>豆沙包調用器（基於 RikkaHub 二開）</h1>

一個原生 Android LLM 聊天客戶端，支持切換不同的供應商進行聊天 🤖💬

[English](README.md) | 繁體中文 | [简体中文](README_ZH_CN.md) ｜ **上游基線 RikkaHub 3.1.1** ｜ 本版 **3.1.1-mod15**

</div>

> [!NOTE]
> 这里是**二改版「豆沙包调用器」**的仓库：下方介绍页正文来自上游 RikkaHub（AGPL-3.0），我们只删掉了官方 QQ 群 / Discord / 赞助商 / 捐赠等内容，功能介绍仍属上游。
> 二改版安装包在本仓库 **Releases** 页面下载；官方原版请到上游仓库获取。

## 🚀 下載

🔗 [前往官網下載](https://rikka-ai.com/download)（推薦）

🔗 [前往 Google Play 下載](https://play.google.com/store/apps/details?id=me.rerere.rikkahub)

> [!WARNING]
> RikkaHub 存在許多 fork 版本，fork 版本出現問題與 RikkaHub 無關，請謹慎使用 fork 版本，避免隱私洩露或者過度索要權限問題。

## ✨ 功能特色

- 🎨 現代化安卓APP設計（Material 3 Expressive / 預測性返回）和 🌙 暗色模式
- 📦 工作區：基於 proot 的 Linux 智能體環境
- 🎬 媒體生成（圖片、影片）
- 🖥️ Web多端訪問支持
- 🛠️ MCP 與 Skills 支持
- 🔄 多種類型的供應商支持，自定義 API / URL / 模型（目前支持 OpenAI、Google、Anthropic）
- 🖼️ 多模態輸入支持
- 📝 Markdown 渲染（支持代碼高亮、數學公式、表格、Mermaid）
- 🔍 搜尋功能（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity、..）
- 🧩 Prompt 變量（模型名稱、時間等）
- 🤳 二維碼導出和導入提供商
- 🤖 智能體自定義
- 🧠 基於檔案的記憶
- 📝 AI翻譯
- 🌐 自定義HTTP請求頭和請求體

## ✨ 開發

> [!IMPORTANT]
> 本項目不接受 Pull Request（PR）。

本項目使用[Android Studio](https://developer.android.com/studio)開發。

技術棧文檔:

- [Kotlin](https://kotlinlang.org/) (開發語言)
- [Koin](https://insert-koin.io/) (依賴注入)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI 框架)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore?hl=zh-cn#preferences-datastore) (
  偏好數據存儲)
- [Room](https://developer.android.com/training/data-storage/room) (數據庫)
- [Coil](https://coil-kt.github.io/coil/) (圖片加載)
- [Material 3 Expressive](https://m3.material.io/) (UI 設計)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) (導航)
- [Okhttp](https://square.github.io/okhttp/) (HTTP 客戶端)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Json序列化)

> [!TIP]
> 你需要在 `app` 資料夾下添加 `google-services.json` 檔案才能構建應用。

## 📄 許可證

本項目基於 [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0) 開源。

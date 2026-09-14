# Novex 代码架构

更新：2026-09-14。本文件描述 `src/android/` 的模块划分与依赖方向；改模块边界时必须同步更新。

## 模块地图

Gradle 实际启用的模块（`src/android/settings.gradle.kts`）：

| 模块 | 职责 | 依赖方向 |
|---|---|---|
| `content-core` | 卡片内容纯数据模型：ContentDocument、ModuleTree、BlockEdits | 最底层，无依赖 |
| `content-storage` | 卡片存储与编解码：CardStore/CardEditor、结构编解码、PNG 卡导入、草稿暂存 | ← content-core |
| `conversation-core` | 对话纯逻辑：ConversationControls、ModuleAdoption、RequestCapacity（上下文额度） | ← content-core |
| `conversation-runtime` | 对话运行时：会话状态/时间线、ToolDialogueLoop（工具循环）、卡片编辑工具、checkpoint | ← conversation-core |
| `model-transport` | 模型接口：ChatCompletionClient、ModelCapacityLookup | ← conversation-runtime |
| `novex-core` | 领域核心：学习会话/预算、记忆、Docx 流式解析、工作区工具路由 | 独立 |
| `app` | 安卓壳 + 全部 UI + 遗留业务 | 聚合以上全部 |

依赖关系：`content-core ← content-storage / conversation-core ← conversation-runtime ← model-transport`；`novex-core` 独立；`app` 聚合。

## app 模块内的关键区域

- `ui/chat/`：对话界面与 ChatViewModel（见下方债务）；
- `data/repository/`：ChatRepository、ProviderRepository 等数据仓库；
- `provider/openai/`：OpenAI 兼容协议实现；
- `cards/`：卡片宿主/入口/目录（Integrated* 与 Legacy* 双轨，见债务）；
- `sandbox/`：应用内置终端沙箱（PRoot；`prepare_android_sandbox.sh` 在 CI 中为其准备二进制，`jniLibs` 里的 loader 因 Termux 下架不可重建而显式入库）；
- `novex/`：新旧层之间的适配层。

## 已知债务（改动时不要加重）

1. **ChatViewModel.kt 约 1.3 万行**，是事实上 的上帝类。新功能禁止继续往里加代码；纯逻辑下沉到 conversation-runtime / novex-core，趁功能迭代逐步拆分（历史治理计划见 `docs/tasks/archive/NOVEX_DEVELOPMENT_AND_CLEANUP_PLAN.md`）。
2. **卡片三套实现并存**：`app/cards/Integrated*`、`app/cards/Legacy*`、`content-storage`（novex.storage）。目标是 content-storage 作为唯一存储真相，其余只做 UI 适配；Legacy 标注退出路径。
3. **app 模块约 25 万行**，应下沉的逻辑（卡片、会话）持续迁入库模块。
4. 超大文件清单（新代码不要加入这些文件）：ChatViewModel.kt、ChatScreen.kt、StreamingMarkdownText.kt、OpenAIProvider.kt、SessionListScreen.kt、ProviderRepository.kt。

## 测试布局

单元测试与被测模块同目录（`src/test/`）。新逻辑的测试写在对应库模块；只有 UI 装配逻辑才放 app。卡片格式相关的回归测试必须包含"导出→导入→对账"链路（见 `docs/specs/card-format.md`）。

## 构建与验证

本机可能没有 JDK，编译验证走 GitHub Actions：`android-validate`（PR/手动）、`android-fast`（push next 的选择性验证）、`android-preview`（签名候选）、`android-promote`（稳定晋级）。详见 `docs/RELEASE.md`。

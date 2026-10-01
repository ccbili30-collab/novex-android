# Novex 代码架构

更新：2026-09-30（对齐架构质量审计 `docs/ARCHITECTURE_QUALITY_AUDIT.md` 与包根归位/D1 修复轮）。本文件描述 `src/android/` 的模块划分与依赖方向；改模块边界时必须同步更新。

## 模块地图

Gradle 实际启用的模块（`src/android/settings.gradle.kts`）：

| 模块 | 职责 | 依赖方向 |
|---|---|---|
| `content-core` | 卡片内容纯数据模型：ContentDocument、ModuleTree、BlockEdits | 最底层，无依赖 |
| `content-storage` | 卡片存储与编解码：CardStore/CardEditor、结构编解码、PNG 卡导入、草稿暂存 | ← content-core |
| `conversation-core` | 对话纯逻辑：ConversationControls、ModuleAdoption、RequestCapacity（上下文额度） | ← content-core |
| `model-transport` | 模型接口：ChatCompletionClient、ModelCapacityLookup、四方言 wire 编解码 | ← conversation-core（本模块被 conversation-runtime 依赖，方向勿写反） |
| `conversation-runtime` | 对话运行时：会话状态/时间线、ToolDialogueLoop（工具循环）、卡片编辑工具、checkpoint | ← conversation-core、← model-transport |
| `novex-core` | 领域核心：学习会话/预算、记忆、Docx 流式解析、工作区工具路由 | 独立 |
| `app` | 安卓壳 + 全部 UI + 遗留业务 | 聚合以上全部 |

依赖关系：`content-core ← content-storage`；`content-core ← conversation-core ← model-transport ← conversation-runtime`；`novex-core` 独立；`app` 聚合。（曾把 model-transport 与 conversation-runtime 的方向写反，2026-09-30 依 build.gradle.kts 实测修正——model-transport 只依赖 conversation-core，且保持零 app/android-framework 依赖。）

## app 模块内的关键区域

- `ui/`：全部界面。`ui/chat/` 对话与 ChatViewModel、`ui/noven/` 旧世界屏幕——UI 血统清洗是独立战线（见 `docs/UPSTREAM_EXIT_PLAN.md` 移交清单），非 UI 刀不碰 `com.openminis.app.ui` 文件；
- `data/repository/`：三个编排门面（ProviderRepository/ChatRepository/SkillRepository）钉旧路径公共 API，实现已全部下沉 `novex.android.repo`。**门面只准减不准增**，新能力一律先落 novex 包再转发；
- `cards/`：卡片宿主/入口/目录（Integrated* 与 Legacy* 双轨，见债务）；
- `novex/`：新世界全部自有代码，见下节版图。

## novex.android 包结构（2026-09-30 包根归位后终态）

```
data (31) ◄─ repo (11)          repo/adapter/data 三角边界：data = schema 与 DTO（怎么存）；
data ◄─ adapter (25)            repo = 旧三仓库内脏（存取机制）；adapter = novex.core
ui.cards (19) ──72──► ui (42)   工作区端口（领域↔存储翻译）。改前先对号，别随机落点。
ui.cards、bridge ──► data.ContentPaths（宿主路径解析）
transport (1) ──► data.model（仅 DTO，无 DAO 直穿）──► thinking (3)
models (2) / authkit (12) / sharekit (7) ──► data、logkit (2)
runtime (9) / crashguard (2) / soul / navlink / netwatch / powerguard / vault / localekit：包间零依赖或仅 → logkit
包根：仅 LegacyUiBridge.kt（旧 UI 线桥接门面，拆除排期见 UPSTREAM_EXIT_PLAN P3.6）
```

规矩（违者记债，登记见审计 D 表）：

1. **包根不落新件**——D1 的 20 件根散件已归位（19 件卡片族 → `ui.cards`，ContentPaths → `data`），唯一留根件是 LegacyUiBridge 桥，禁止再往包根加文件。
2. **data 是旧 schema 的包装层**——`NovexMainDatabase` 仍装配旧世界 `com.openminis.app.data.character.*` 的 17 个 Room 实体/DAO/转换器；往 character 表加列时两头都要改，别误以为 data 已是自有 schema。
3. **日志单点**——novex 包只准 `novex.android.logkit.RunLog`；`AppLogger` 是旧路径门面（transport/models 已改接直引 logkit），`android.util.Log` 直打禁止新增（存量 28 件见审计 §5.2，随触碰收敛）。
4. **新包→旧世界的依赖要过冻结面**——可改接的漏网（B 桶 9 项）与合法缝（A 桶）清单见审计 §1.3；每清一批更新 `UPSTREAM_EXIT_PLAN` P3.6。

## 已知债务（改动时不要加重）

1. **ChatViewModel.kt 约 1.4 万行**，是事实上的上帝类。新功能禁止继续往里加代码；纯逻辑下沉到 conversation-runtime / novex-core，趁功能迭代逐步拆分（历史治理计划见 `docs/tasks/archive/NOVEX_DEVELOPMENT_AND_CLEANUP_PLAN.md`）。
2. **卡片三套实现并存**：`app/cards/Integrated*`、`app/cards/Legacy*`、`content-storage`（novex.storage）。目标是 content-storage 作为唯一存储真相，其余只做 UI 适配；Legacy 标注退出路径。
3. **app 模块约 25 万行**，应下沉的逻辑（卡片、会话）持续迁入库模块。
4. 超大文件清单（新代码不要加入这些文件）：ChatViewModel.kt、ChatScreen.kt、StreamingMarkdownText.kt、SessionListScreen.kt、ProviderRepository.kt；novex 侧 `transport/NovexTransportProvider.kt`（1,379 行四线协议，拆分排期见审计 D3）。
5. 结构债总登记：`docs/ARCHITECTURE_QUALITY_AUDIT.md`（D1-D18 债务表）；门面拆除排期：`docs/UPSTREAM_EXIT_PLAN.md` P3.6。

## 测试布局

单元测试与被测模块同目录（`src/test/`）。新逻辑的测试写在对应库模块；只有 UI 装配逻辑才放 app。卡片格式相关的回归测试必须包含"导出→导入→对账"链路（见 `docs/specs/card-format.md`）。

## 构建与验证

本机可能没有 JDK，编译验证走 GitHub Actions：`android-validate`（PR/手动）、`android-fast`（push next 的选择性验证）、`android-preview`（签名候选）、`android-promote`（稳定晋级）。详见 `docs/RELEASE.md`。

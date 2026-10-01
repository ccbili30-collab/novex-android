# UI 血统清洗任务书（交接文档）

> 交接人：底层清洗线（已完成传输/数据/服务/OAuth 全部底层自有化）
> 接手人：UI 重设计线（feat/ui-rikkahub）
> 日期基准：2026-10-01，next 分支最新
> 一句话目标：**把 UI 层剩余的上游衍生代码清洗到「与上游基线文本相似度 <40%」，让整个 App 具备更换开源协议（P5）的资格。**

---

## 一、为什么做这件事（30 秒版）

Novex 起源于开源项目 OpenMinis（GPLv3）的 fork。要让公司能闭源商用，分发物里不能含上游衍生代码。底层（协议/数据库/服务/登录）已经全部重写完毕——**现在 UI 层是最后一块血统**。清洗完成后整个仓库可换协议，这是整个工程的终点线。

「衍生」的判定不靠感觉，靠**机械测量**：每个文件和上游基线（git 根提交 `82c2eb0` 的同路径文件）做文本相似度对比。**<40% 算自有**。你重设计时新写的代码（如 noven/ 组件系）天然是 0%，不用管；要清洗的是那些「在旧文件上改」的屏。

## 二、三条铁律（违反会被审查退回，已发生过两次）

1. **真重写，不是改名直译。** 改类名/变量名/包名但语句结构照搬 = 仍是衍生作品（P3.2 的语音件曾被测出 96-99% 相似度退回重做）。协议事实可以逐字保留（见铁律 3），但**类结构、方法分解、控制流、注释、文案措辞必须是自己的**。
2. **相似度用工具说话。** 仓库自带：`python3 scripts/upstream_audit.py`——输出每个血统文件与基线的相似度（口径：剥注释+空白归一+大小写折叠+行级对比）。每轮交付前自己跑，>40% 的逻辑件回去重做。判定三档：≥80% 仍是上游主体 / 40-80% 半血 / <40% 达标。
3. **冻结面逐字保留。** UI 里也有「数据事实」，清洗时不能变（变了会断功能/断数据）：
   - **R.string 键名**（值可以随便改文案；键是引用锚点）
   - **Room 数据库/prefs/磁盘文件**的任何读写（UI 只许消费，不碰 schema——schema 已在自有层冻结）
   - **深链 URI**（minis://session/ 等格式）
   - **ChatViewModel 等对外公开 API 的可观察行为**（它 59% 半血 1.4 万行，见下方专节）
   - Manifest 组件名、通知渠道 id（底层已冻结，UI 引用即可）
   - 与底层新包的调用契约：一律 import `novex.android.*`（repo/data/runtime/authkit/soul/models/navlink/sharekit 等）；旧路径门面「只进不出」——新代码禁止再引用 `com.openminis.app` 旧路径

## 三、战场清单（95 件 / 6.3 万行，按优先级排）

**第一梯队（大头，5 件 ≈ 2.6 万行）**
| 文件 | 行数 | 当前相似度 | 说明 |
|---|---|---|---|
| ui/chat/ChatViewModel.kt | 14,131 | 59% | **专节见下**——它不是皮，是逻辑层 |
| ui/chat/ChatScreen.kt | 4,752 | 57% | 聊天主屏，你已在大改 |
| ui/chat/StreamingMarkdownText.kt | 3,658 | 98% | 流式 Markdown 渲染（几乎纯上游） |
| ui/sessions/SessionListScreen.kt | 2,670 | 71% | 主屏会话列表 |
| ui/chat/ChatToolDetailUI.kt | 1,581 | 92% | 工具调用详情 |

**第二梯队（千行级 13 件）**：ChatAssistantMessageUI 71%、AppNavigation 61%、SessionListViewModel 88%、FilePreviewScreen 96%、MinisTextKitSelection **100%**、SkillsManagementScreen 95%、ModelGroupsScreen 76%、ChatModelPickerSheet 89%、ChatComposerWidgets 82%、MinisTextKitGesture 98%、ChatMiscViews 89%、ChatFlatItems 84%、MarkdownText 97%

**第三梯队（百行级 ~77 件）**：settings 各屏（ModelGroupDetail 96/Appearance 89/LogManagement 95/StorageManagement 94/BackgroundSettings 94/SoulSettings 96/MemoryManagement 90/UsageStats 97/…）、components（FullscreenImageViewer 96/ImageGalleryViewer 97/ModelEntryPicker 92/…）、markdown（MarkdownParser **100%**/KaTeXView **100%**）、sandbox 残件（FileBrowserViewModel 99）、chat 杂件（KatexWebViewPool **100%**/SystemResourceMonitor **100%**/MarkdownClipboard **100%**/StreamingFade **100%**…）、theme（ChatColors 95；Theme.kt 45% 半血）

**完整实时清单**：跑 `python3 scripts/upstream_audit.py` 看「上游未动/上游改动」两桶里的 ui/ 文件。

## 四、ChatViewModel 专节（最重的一件）

它 1.4 万行、59%——是回合状态机+工具编排+流式装配的**逻辑层**，不是视觉皮。你的重设计改的是 ChatScreen 这层皮；ViewModel 的清洗是**把残余 ~8.3k 上游文本重写掉而公开 API 不动**。两个方案选一：
- **方案 A（推荐）**：按职责拆成自有新文件（如 novex.android.chat.TurnEngine/ToolDispatcher/StreamAssembler…），旧路径留薄门面（先例：repository/service 三轮都这么干的，门面 40-700 行，调用方零改动）；
- **方案 B**：就地重写（文件内逐段重做）。
无论哪个：**先跑通它的测试**（ui/chat 测试很密），既有测试零改动通过是验收面；改之前 `git log --oneline -- <file>` 看你自己的提交别把已有改动冲掉。

## 五、工作流（每轮一刀）

1. **基于最新 next 开分支**（`tasks/ui-clean-N`）——你的 feat/ui-rikkahub 基点太老（落后几十刀：语音/浏览器/沙箱/前尘已删，水管和数据层已换），**先把重设计工作 rebase 到最新 next 再继续**，否则合并时会大面积复活已砍功能。rebase 有冲突叫底层线做合并手术。
2. 一轮做一屏或一族（别一口全吞）——重设计+清洗同刀落地：反正要重画，新代码直接写成自有结构，相似度自然达标。
3. 自查：`python3 scripts/upstream_audit.py` 相似度 <40%（冻结面记档例外）；全量测试绿。
4. PR（base next）→ CI 绿（合并前必须确认 checks 显示 pass——有历史教训）→ 底层线跑「净眼」审查（干净上下文的独立审查员，会机械复测相似度与冻结面）→ 合并。
5. 回填 docs/UPSTREAM_EXIT_PLAN.md 进度日志（相似度前后数字写进去）。

## 六、判例参考（仓库里现成的样板）

- **好样板**：novex/model/GeminiWire.kt（对上游 7.5%——协议事实保留+结构全自有的标准形态）；novex.android.repo / runtime / authkit 三轮（门面策略应对旧引用）
- **反例（被退回的）**：P3.2 语音件首轮改名直译 96%+ 被净眼机械测量抓回，返工两轮才过——别走这条弯路

## 七、边界与支援

- **不归你**：MinisApp.kt（47%）、MainActivity.kt（62%）、provider 接口层（LLMProvider/Factory 绞杀缝）——P4 骨架轮由底层线在**你的清洗落地后**收尾，避免骨架接了旧 UI 又被你改掉。
- 底层新包的 API 有任何不够用的（门面挡住了你需要的成员），提出来底层线开面，别在 ui 里绕 hack。
- 每轮 PR 打上「ui-clean」标签，底层线看到就会排净眼。
- 完成判定：`upstream_audit.py` 的 UI 文件全部 <40%（或记档冻结例外）→ P4 骨架 → P5 换协议（LICENSE + 软著 + 商标）。

# PR 0 任务书 · 地震仪（seismograph）

上级文档：[2026-09-16-conversation-core-rework.md](2026-09-16-conversation-core-rework.md)

目标：每个出站请求留下记录（消灭抓取盲区）；违反不变量的请求在发出前被拦截并留下证据。

## 范围（文件清单）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `app/src/main/java/com/openminis/app/provider/ProviderWireCapture.kt` | 所有请求记摘要行；stats 结构；note 行 |
| 2 | `app/src/main/java/com/openminis/app/ui/chat/PreSendContract.kt` | 新增：三不变量纯函数 |
| 3 | `app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt` | 出口接入断言（attempt lambda，boundedHistory 之后 estimate 之前）；DB 重读仅在 turn==0 |
| 4 | `app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt` | record() 传 stats |
| 5 | `app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt` | record() 传 stats |
| 6 | `app/src/main/java/com/openminis/app/novex/domain/NovexConversationContextLimit.kt` | MINIMUM 64_000 → 300_000 |
| 7 | `app/src/test/java/com/openminis/app/ui/chat/PreSendContractTest.kt` | 新增：不变量单测 |
| 8 | `app/src/test/java/com/openminis/app/provider/ProviderWireCaptureTest.kt` | 新增：摘要行单测 |

不动：cards/、侧边、文游、预设相关一切文件（零 diff 硬验收）。

## 不变量与合法减项（实现依据，净眼必查）

组装链（ChatViewModel attempt lambda）：
`agentHistory` → `scopedHistory`（**逐条映射不删消息**，只做范围脱敏）→
`effectiveAgentHistoryUncounted`（压缩标记在场时 rebuild **会删锚点前消息** → 唯一 I1 豁免）→
空消息过滤 → `prependSideSnapshotHistory`（只增不减）→ `budgetedRequestHistory`
（ConversationToolRetention：**替换内容保留消息条数**）→ `pureChatHistory`（仅当工具禁用，
**删"只有 ToolUse/ToolResult 部件"的消息** → 唯一 I1 合法减项）→ `applyRequestImageBudget`
（**只裁图片字节保消息**，最后一条用户消息的图永不被裁）→ `appendRuntimeInjections`
（**合并进最后一条用户消息，不增删消息**）→ streamMessage。

- **I1 历史守恒**：expected = DB 活跃路径（`loadActiveConversation(activeSessionId).activeMessages`
  → toLLMMessage → 同款实质过滤）条数；actual = requestHistory 条数（pureChat 之后）。
  工具禁用时 expected 先过同款 pureChat 过滤再比较。
  违反条件：expected ≥ 2 且 actual < expected。
  豁免（降级为日志不拦截）：压缩标记在场（`_cachedLatestMarker != null && _compactSummary 非空`）；
  turn > 0（工具循环中轮，DB 落后于内存）；DB 重读失败（IO 异常 → 日志放行）。
- **I2 图片守恒**：沿用既有护栏语义（本轮最后一条用户消息带图 ⇒ boundedHistory 有图片块），
  并入统一出口函数。
- **I3 工具配对**：boundedHistory 全量扫 ToolUse/ToolResult 的 id 集合，双向差集非空即违反。

违反处理：IllegalStateException（用户可读中文文案，说明不变量与自救方式）+ audit 事件
（`presend_contract_violation`，含 expected/actual 差值）+ ProviderWireCapture note 行
（kind=summary, note=violation:xxx，让导出包内联可见）。

## 抓取器（ProviderWireCapture）改动

- `record(provider, body, url, stats, note)`：**先无条件写一行摘要**
  `{at, kind, provider, url, bytes, messages, images, toolUses, note?}`；
  带工具/图片标记的请求在同一行附 `body`（kind=full），沿用现有截断与 4MB 环形策略；
  纯文本请求只有摘要行（kind=summary）——盲区消灭。
- `RequestStats(messageCount, imageCount, toolUseCount)` 由 Provider 在序列化前从消息列表统计；
  stats 缺席时字段记 -1（诚实缺失，不用启发式猜）。
- 既有调用点（AnthropicProvider.buildRequest / OpenAIProvider.buildRequest）补传 stats；
  stats 在 streamMessage 组装处从 messages 计算，buildRequest 增参透传。

## 默认容量

`NovexConversationContextLimit.MINIMUM` 64_000 → 300_000。涟漪确认过（净眼复核后修正口径）：
`minimum(modelWindow)=min(300K, window)`（小窗模型不受影响）；**旧会话保持已保存的 64K
不自动迁移**（读取走 `effective()`，`selection()` 只在用户重存滑杆时介入）——保守安全，
旧行为不破坏；是否要做"加载时迁移"留给用户决策（见台账）。`hasPersistentConfiguration`
的 `!= MINIMUM` 比较语义自动跟随。

## 验收

- [ ] 单测：I1 正常/违反/三豁免；I2 正常/违反；I3 双向；pureChat 减项正确扣除；摘要行格式
- [ ] 本地 CI 同款命令绿：`:app:testStableDebugUnitTest :conversation-runtime:test :app:compilePreviewDebugKotlin`
- [ ] 净眼审查报告结论闭环（台账见下）
- [ ] 守纲六问裁决通过
- [ ] CI（android-validate）绿
- [ ] 用户复现"生成中发新消息入队"场景 → 对话包拿铁证（发布后）

## 净眼审查任务书（干净上下文 Agent 用）

你是局部代码审查者。只审"这段代码自身对不对"，不问架构方向。仓库根：
`/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/tasks/card-core-integration`。
先读 `docs/tasks/2026-09-16-conversation-core-pr0-seismograph.md`（本任务书）与
`docs/tasks/2026-09-16-conversation-core-rework.md`（总纲），再审下列文件的相关改动
（`git diff next...HEAD` 加上下文阅读）：

- `src/android/app/src/main/java/com/openminis/app/provider/ProviderWireCapture.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/PreSendContract.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt`（attempt lambda 区段）
- 两个 Provider 的 buildRequest/streamMessage 区段
- `src/android/app/src/main/java/com/openminis/app/novex/domain/NovexConversationContextLimit.kt`
- 两个新测试文件

必走场景链路（逐步追代码，写出每一步的值）：
1. 队列注入场景：生成中新消息入队 → 消费注入 → runAgentLoopBody attempt → I1 断言用什么值
   判定？会不会误拦？
2. 压缩后会话：标记在场 → I1 豁免路径是否真的不拦？I3 是否仍全量检查？
3. 工具循环第 N 轮（turn>0）：断言跳过 DB 重读后，I3 是否覆盖在飞工具对？
4. pureChat 模型（工具禁用）：expected 过滤与 actual 过滤是否同构？
5. 侧边会话：快照前拼后 actual ≥ expected 是否恒成立？
6. wire-capture：纯文本请求现在留几行？6MB 带图请求截断逻辑是否被摘要行破坏？
7. 违反路径：抛出的异常在哪被捕获？会不会把用户会话卡死在 streaming 状态？

必跑命令：本机无 JDK/Android SDK（2026-09-16 确认），机械检查以 PR 的
android-validate CI 为准（与本地等价：`:app:testStableDebugUnitTest` 等全模块任务）。
净眼做静态走查：逐场景追代码写出行值，核对逻辑与不变量；CI 结果由主笔附给你。

输出格式：逐不变量签"过/不过"；每个问题 = 严重度(P0/P1/P2) + 文件:行 + 证据 + 建议修法；
最后给"是否放行进入守纲裁决"结论。

## 台账

### 净眼审查（2026-09-16，agent_7df48070，结论：放行进入守纲，无 P0）

| 编号 | 结论 | 处置 |
|---|---|---|
| P1-1 runCatching 吞 CancellationException（ChatViewModel DB 重读） | **采纳已修** | onFailure 首行 rethrow CE（照抄文件内 6616/7828 既有范式）。净眼确认无 P0 后果（取消的请求不会发出、streaming 状态会正常清理），但取消会被误记为"重读失败"日志 |
| P2-1 turn>0 跳过 I1 → mid-loop 队列注入请求免检（覆盖缺口非缺陷） | **挂账 PR1** | 该请求仍留 wire-capture 摘要行（messages=N 可见），后验目标达成；PR1 把"注入后的新逻辑轮"重置 I1 基线 |
| P2-2 侧边快照绕过孤儿修复，I3 可能永久拦死一个侧边会话（崩溃窗口定格） | **采纳已修** | prependSideSnapshotHistory 对快照段单独跑 dropOrphanedToolParts（合成错误结果），配对恒成立 |
| P2-3 I1 两侧基线两处理论性不对称（scoped 脱敏空白行 / 孤儿整条删除） | **挂账 PR1** | 净眼标注"未确认（推演上关不上，实践找不到入口）"；PR1 装配线纯函数化时 expected 侧过同款投影，结构性消除 |
| P2-4 任务书"旧 64K 经 selection() 上调"与实现不符 | **采纳已修（改文档）** | 代码注释与任务书均已改为准确口径：旧会话保持已保存值不迁移；是否加载时迁移待用户决策 |
| P2-5 拒发行 provider 命名与正常行不一致（simpleName vs 协议名） | **采纳已修** | 改用 currentProvider.name（与协议命名一致） |
| P2-6 "pureChat 减项正确扣除"验收项无单测 | **采纳已修** | 新增 `I1 baseline is pre-pureChat…` 测试固化设计决策 |

净眼同时确认：违反路径 UX 与旧图片护栏同构（ISE → streamRecovery 直接抛 → setInlineError
清 streaming 状态落 DB → finally 释放），不卡死会话；`fallbackStrategy == always` 时会先走
一遍 fallback 链再抛（与旧护栏完全同构，观察项，不算缺陷）。

### 守纲裁决

（待填）

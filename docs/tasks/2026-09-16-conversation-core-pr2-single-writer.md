# PR 2 任务书 · 单一写者与逻辑轮收敛（状态机第一刀）

上级文档：[2026-09-16-conversation-core-rework.md](2026-09-16-conversation-core-rework.md)
前置：PR 0（地震仪，2831bc9）、PR 1（装配线，cf813b9）已合并。

## 背景与切分决策

原计划"ConversationCore 状态机接管 8 入口"一 PR 完成——实施前重估：8 入口在 PR 1
后已全部汇入 runAgentLoop 单出口，真正的残余分叉是**到达出口前的内存历史准备**。
一次搬走全部入口=13k 行文件的大手术，违背小步纪律。改为按绞杀者切三刀：

- **PR 2a（本 PR）：内存历史的单一写者语义 + 逻辑轮/I1 边角收敛**（挂账清偿）
- **PR 2b（下一 PR）：队列/重试/恢复入口的"DB 先行"改造**——所有入口先落 DB 再
  刷新内存投影，内存写者只剩一个函数（DB 事件 → 内存列表 + 有登记的桥接段）
- **PR 2c：ConversationCore 显式状态机**（Idle/Streaming/ToolRunning/Retrying/Queued
  的枚举与转换合法性检查，UI 事件流定型）

## PR 2a 范围

| # | 文件 | 改动 |
|---|---|---|
| 1 | ChatViewModel.kt | ①压缩 continue 吞 I1 基线标志的修复；②`agentHistory` 写点契约注释（single-writer contract） |
| 2 | docs/tasks/ 本任务书 | 台账 |

**③回合级输入缓存降级到 PR 2b**：实施时发现 fallback 端点切换要求 attempt 内
新鲜读，回合缓存与该语义的合并需要重排 attempt 结构——赶工有 staleness 风险，
按"宁慢勿错"降级（守纲本就标为非阻塞只读 IO）。PR 2b 与"DB 先行"一起做。

### ①压缩 continue 与 I1 基线标志

现状：注入在 turn N 设 `pendingI1BaselineTurn = N+1` 后 continue；若 turn N+1 命中
in-loop COMPACTED continue（先于 attempt），标志不被消费，turn 走到 N+2 后标志过期，
该逻辑轮的 I1 基线静默丢失（净眼 P2-2b）。
修法：COMPACTED continue 分支同步顺延 `if (pendingI1BaselineTurn > turn) pendingI1BaselineTurn = turn + 1`。

### ②agentHistory 写点契约（single-writer contract）

以注释块形式在 `agentHistory` 声明处立契约（本 PR 只立约不搬家，PR 2b 逐步收敛）：
- **允许的写点**：installActiveConversation（DB 全量重建，唯一权威写者）、
  runAgentLoop 循环内的轮次追加（用户行/助手轮/工具对——先 DB 后内存的对偶追加）、
  injectQueuedPromptsAsNewTurn（桥接段=内存专属、有登记；用户行=DB+内存对偶）。
- **禁止**：任何绕过 DB 的整表替换/清空（conv9 病根）。
- 影子装配（shadow_assembly_diff 强信号）是此契约的运行时稽查。

### ③回合级输入缓存（已降级至 PR 2b，见范围表说明）

守纲备忘的双份只读 IO 保留现状：非阻塞、正确性中立；与 PR 2b 的 attempt 结构
重排一起做，避免赶工引入快照过期风险。

## 不变量

- B1 I1 基线标志在任何 continue 路径下不丢失（压缩/注入互斥顺延）；
- B2 写点契约只是文档+稽查，不改变任何现有写点行为（行为变化留给 PR 2b）。

## 净眼审查任务书

场景走查：①注入 turn N → 压缩 continue 在 turn N+1 先命中 → attempt 在 N+2 的
标志值链（顺延是否闭环）；②写点契约与实际写点对账（grep agentHistory 的
add/clear/removeAll/迭代器写，逐点归入契约三类或报告违点）；③压缩顺延修改本身
会不会引入新误判（标志 > turn 的其它来源）。

## 台账

（待净眼/守纲审查后填）

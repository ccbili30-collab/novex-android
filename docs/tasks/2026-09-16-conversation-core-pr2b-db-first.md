# PR 2b 任务书 · DB 先行收敛（单一写者第二刀）

上级：docs/tasks/2026-09-16-conversation-core-rework.md；前置：PR 2a（695ab51）契约已立约。

## 范围（违点分级处置，非一刀切）

| # | 位置 | 违约内容 | 处置 |
|---|---|---|---|
| 1 | clearChat（约5510） | 内存先行 clear + DB 异步后补——崩溃窗口重启复活已清对话 | **修**：DB 先行 |
| 2 | handleUserCancelledCleanup Case 2（约12541） | 内存先 add 且**不带 dbMessageId**（影子指纹天然看不见它）+ DB 异步 append | **修**：DB 先行拿 id 再入内存 |
| 3 | retryLast（约7188） | 内存回滚弹出尾助手 + 孤儿清理，DB 靠分支/重装事后对账 | **立档不动刀**：有 dropOrphanedToolParts + sanitize 双兜底，消息树分支化是产品级重写，单独评估 |
| 4 | 回合级输入缓存 | 卡片场景估算与 attempt 双份 assemblyInputs IO（守纲备忘） | **修**：迭代顶一次采集 |

## 改法

① clearChat DB 先行：删除是单条 SQL（毫秒级）。viewModelScope.launch { dao 删除 → withContext(Main){ agentHistory.clear(); _messages.value = emptyList() } }；其余 UI 状态保持同步清（非真相源，即时反馈）。
② 取消清理 Case 2 DB 先行：launch { IO: appendMessage 拿 entity → Main: agentHistory.add(带 dbMessageId) }；顺带修复"内存消息永无 dbId、影子盲区"。Case 1 保持现状（persistToolResultMessage 本就 DB 先行）。
③ 回合级输入缓存：turn 迭代顶一次 `val turnInputs = assemblyInputs()`；估算与 attempt 复用。端点 fallback 复用同快照——attempt 重试期间无历史写入；DB 侧 I1 基线重读是独立通道不受影响。COMPACTED continue 时快照废弃。

## 不变量

- C1 clearChat 返回后至协程完成前，无发送能读到"已清内存+未清 DB"的分歧组合（发送入口在 cancelStream 后被 isStreaming/队列门挡住——净眼验证此门）；
- C2 取消清理 Case 2 的内存消息必带 dbMessageId（影子可见性恢复）；
- C3 缓存后估算值与正式装配 assembled 同源同值；
- C4 retryLast 行为零变化（仅契约立档措辞更新）。

## 净眼审查任务书

走查：①clearChat 期间并发发送/恢复的所有入口是否真被挡住（列出每个门）；②Case 2 改造后 _canResume 时序变化是否影响恢复路径；③turnInputs 采集点与估算点之间逐行核对无历史写（含 withContext 切换）；④fallback 复用快照的推演；⑤C4：retryLast diff 为零。输出逐不变量签字+问题清单+放行/退回。

## 台账

（待审后填）

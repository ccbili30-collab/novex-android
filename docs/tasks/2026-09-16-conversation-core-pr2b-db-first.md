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

## 措辞更正（净眼一审 P2-6/P2-1）

- ③④"完全一致/attempt 间不变"表述有误：retentionProject 闭包**装配时惰性读**
  effectiveContextWindowTokens()——fallback 端点切换（installStreamFallback）后
  保留集可变、dice 每 attempt 重掷。行为与改造前等价（非回归），PR2a 当年的
  降级理由（要求新鲜读）已被惰性闭包化解，特此记一笔。
- C1 论证更正：靠**排序**（内存清空严格后置于两条 DELETE）而非 isStreaming 门
  ——cancelStream 恰把 isStreaming 置 false，sendMessage 未被门挡；存在反向窗口
  "已清DB+未清内存"（毫秒级，P2 立账，PR2c 收口时加 in-flight 门）。

## 台账

### 净眼一审（agent_ffda1325，commit b7eb1f9，结论：退回 → P1-1 已修复待复核）

| 编号 | 结论 | 处置 |
|---|---|---|
| P1-1 Case 2 延迟 add 无纪元门（与 drain/install 交错，agentHistory 非同步） | **采纳已修（修法改良）** | 纪元门= sid+size 双校验；**未按"丢弃内存应用"实现**——丢弃会让内存恒少一行、I1 把 drain 的发送永久拒掉（守恒断言反噬）；改为纪元变化时走 installActiveConversation 权威对账（7822 RESUME 同款惯用法），行序条数归真。会话切换则不动 |
| P2-1 反向窗口+门论证失实 | **采纳已修（措辞）** | 见上"措辞更正"；in-flight 门挂 PR2c |
| P2-3 协程无异常兜底 | **采纳已修** | runCatching + onFailure 保底回退旧行为（内存加无 id 消息）+ 留痕 |
| P2-4 契约注释过期（clearChat/取消清理仍列违点） | **采纳已修** | 契约改为"PR 2b 已收敛/遗留 PR2c"分段 |
| P2-5 stop→retry 走 fork 分支的行为变化 | **认知项记录** | dbMessageId 非空使 fork 锚点生效——有意变化，方向正确（分支化正是 retryLast 的收敛方向） |
| P2-2 wipe 与 Case2 的 Room 写乱序（存量） | **挂账 PR2c** | 消息表写路径共用 Mutex/串行调度器 |
| P2-6 任务书③④措辞与实现不符 | **采纳已修** | 见"措辞更正" |
| P2-7 decideToolOperation 绕门+阻塞豁口幽灵追加（存量） | **挂账 PR2c** | 随状态机收口 |

净眼确认：③ turnInputs 采集点与 attempt 之间无隐藏历史写点（逐行核过）；
C2/C3/C4 过（C4 代码级零 diff）。

### 净眼复核 / 守纲裁决

（待填）

### 净眼复核一（同 agent，1f9ea6e，结论：再退回窄幅 → 两项已修待窄复核）

- 偏离决策（拒绝丢弃）**采纳**：I1 只罚 memory<DB 方向，丢弃=恒少一行=drain 永久拒发且重试不自愈，与代码实证一致。
- N-P1-a install 与活跃循环并发（全文件首个无门 install；其余 10 处都在 !isStreaming 门内或自有流序言/收尾）→ **采纳已修**：join 循环等当前流（含 drain 起新流）落定后再 install；流中缺行至多 I1 拒发一次，流死+install 落地自愈，死锁面消除。
- N-P1-b runCatching 吞 CE（同型第三次）→ **采纳已修**：onFailure 首行 rethrow，throw 顺带跳过 _canResume 置位。
- N-P2-a 纪元读无 happens-before → **挂账 PR2c**（join 循环已收窄实际窗口；形式闭合需单写者）。
- N-P2-b "7822 惯用法"引证失真 → **采纳已修**：注释更正（本体 7858，且注明并发语境差异）。
- 分支①确认为防御性死枝（占位入列前 ensureSession 已完成，sid 不变），留注不纠。

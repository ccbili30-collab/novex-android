# PR 2c 任务书 · 门收口与状态机种子（单一写者第三刀）

上级：docs/tasks/2026-09-16-conversation-core-rework.md；前置：PR 2b（d1c9a2f）。
守纲条件③：本任务书逐项映射 PR2b 挂账五项，不漂移不丢弃。

## 挂账映射（守纲五项）

| 挂账 | 来源 | 本 PR 处置 |
|---|---|---|
| N-P2-a 纪元读无 happens-before | PR2b 净眼 | **修**：historyWriteLock 同步块覆盖竞态对两侧（见⑤） |
| clearChat 反向窗口（已删DB+未清内存）in-flight 门 | PR2b P2-1 | **修**：isWiping 标志挡窗口内 send/retry/回传（见②） |
| P2-2 wipe 与 Case2 append 的 Room 写乱序 | PR2b | **修**：消息表关键写共用 dbWriteSerial（见③） |
| P2-7 decideToolOperation 绕 canResume 门 | PR2b | **修**：补门（见④） |
| streamJob"已声明未启动"空档 | PR2b 窄复核 | **修**：join 自旋条件（见⑥） |

## 范围

1. **状态机种子（审计先行不执法）**：`RunPhase` 枚举（IDLE/STREAMING/AWAITING_RESUME）
   + `runPhaseTransitionTo()` 审计器——只记日志与非法转换告警，不改任何现有门语义。
   挂点：runAgentLoop 进出、cancelStream、resume、retryLast、clearChat。
   PR3 依据审计数据决定执法化（D1：log-only 是硬约束）。
2. **② isWiping 门**：clearChat 协程期间置位（finally 复位）；sendMessage / retryLast /
   retryFromMessage / resume / runCrossSync 入口检查并快速失败（"正在清空对话，请稍候"）。
3. **③ dbWriteSerial**：suspend Mutex；clearChat 的两条 DELETE 与取消清理 Case 2 的
   appendMessage 共用——消灭"插入落在删除后"的行复活。锁内只有 Room 挂起调用，
   不与 historyWriteLock 交叉持锁（D3）。
4. **④ decideToolOperation 补门**：自置 canResume 前检查会话未被清空/切换。
5. **⑤ historyWriteLock**：plain `Any()` 同步块，覆盖已知竞态对两侧——取消清理的
   纪元读+add、drain/sendMessage 的用户行 add、install 的 clear+addAll、clearChat 的
   内存清。块内纯内存操作（微秒级、D4）；其余写点的非竞态点留 PR3 迁移（台账记录）。
6. **⑥ join 空档自旋**：break 条件改 `job == null || (!job.isActive && !_isStreaming.value)`；
   isStreaming=true 而 job 未启动（声明空档）时 `delay(25)` 重读，上限 ~4s 放行并留痕。

## 不变量

- D1 RunPhase 审计零行为影响（log-only）；
- D2 isWiping 窗口内五入口全部快速失败，不产生半清状态上的发送；
- D3 dbWriteSerial 与 historyWriteLock 无交叉持锁（死锁面归零）；
- D4 historyWriteLock 块内纯内存操作；
- D5 现有全部行为路径（除 D2 快速失败与⑥空档等待）零语义变化。

## 净眼审查任务书

走查：①RunPhase 各挂点转换矩阵——列出真实序列，标出审计器会误报的"合法但看似非法"
序列（误报会淹审计日志）；②isWiping 置位/复位路径全覆盖（含异常路径 finally）；
③两锁持有区间无交叉（逐处画区间）；④⑥自旋的退出条件与最大等待界；⑤D5：逐 hunk
排查语义漂移。

## 实施备注（环境事故记录）

2026-09-17：Bash 工具环境故障（spawn /bin/zsh ENOENT）后，worktree 目录又被外部清空
（仅剩未提交的任务书；git 登记仍在、HEAD=d1c9a2f=已合并 next，零代码损失）。已从 bare
仓库重建 worktree（worktree prune + add），任务书抢救回位，环境恢复后正常提交。

## 台账

### 净眼一审（agent_7f27144a，52eeb37，结论：退回 → P0/P1/P2-2 已修）

| 编号 | 结论 | 处置 |
|---|---|---|
| P0 drain 站点 synchronized 缺闭合 }（文件括号失衡编译必败；CI 实红佐证） | **采纳已修** | `));` 后补 `}` 并归位缩进；净眼警告的"文件尾补括号=monitor 跨 runAgentLoop 死锁结构"已规避 |
| P1 旧流尸体 end-finally 污染审计（IDLE→AWAITING_RESUME 误报+幻影终点） | **采纳已修** | 入口记 jobAtLoopEntry；finally 比 streamJob 身份，过期 job 降级 debug 记录 |
| P2-1 dbWriteSerial 只互斥不保序（依赖未声明调度事实） | **挂账 PR3** | 建议：append 前复检 isWipingSession 或会话世代号 |
| P2-2 clearChat 连点复位窗口 | **采纳已修** | 入口 `if (isWipingSession) return` |
| P2-3 三项 PR2b 既有观察（resume 资格残留/删库异常半态/join 无界） | **挂账 PR3** | 与旧代码同型非回归 |
| P2-4 任务书挂点与实现偏差（cancelStream/resume/retryLast 无直接转换调用） | **记录** | PR3 读审计数据前知悉 |

### 净眼窄复核一（64dca90，结论：再退回 → 新 P0 已修）

新 P0：jobAtLoopEntry 声明在 runAgentLoopBody、使用在兄弟函数 runAgentLoop 的
finally——未解析引用编译必红；且若仅挪入 body 的 withContext 层内会捕获深层子 Job
（恒 stale=审计全盲）。**已修**：捕获移至 runAgentLoop 体首行 try 之前（五个调用点
都在 streamJob 协程内同层直调，该处捕获恰为 streamJob 本身）。
①P0 括号修复复签过（终深 0，块内纯内存）；③P2-2 复签过。
残留记录：clearChat 同 job 迟到 unwind 的 IDLE→AWAITING_RESUME 误报未消
（stale 判定不覆盖"未替换仅取消"类）→ PR3 执法化前补降级条件。

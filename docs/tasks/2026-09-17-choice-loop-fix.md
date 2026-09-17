# 任务书：选项指令寿命修复（choice-instruction-lifecycle）

## 背景（事故）

2026-09-17 用户反馈（会话 297e156a，两份导出包）：模型"一直只输出选项，不输出内容"。
诊断结论（导出包 messages.jsonl + 代码走查）：

- **病因 A（急性）**：v0.2.8（eb0bdc1，2026-09-04）引入的
  `MissingChoiceToolRecoveryPolicy` 恢复分支把
  *"respond only with present_choices… Do not write prose"* 提醒**永久**写进
  agentHistory 的用户消息（ChatViewModel 原 RETRY 分支），投影层对可回放消息
  原样放行 → 之后每一轮请求模型都看得见该禁令。DB 用户行是干净的（毒只在
  进程内存），但同一进程内循环到底。
- **病因 B（结构）**：`present_choices` 是终端 UI 工具，卡按设计从模型历史
  抹掉（`withoutTerminalUiToolUses`；`toLLMMessage` 跳过 uiToolUse 部件）；
  玩家点选又以裸文本进场（选项点击只 Prefill 输入框）→ 模型看到
  `[user: 选项工具][user: 裸选择]`，无任何"卡已出、这是回应"的痕迹。病因 B
  单独即可跨重启维持循环。
- 触发时间线：09-17 05:41 用户发"选项工具"（#384）→ #385 起连续 13 条
  只含 uiToolUse 的助手行；15:38–22:10 用户四次重试仍循环。
- **非重整回归**：该机制比重整早 12 天；新装配线/I1-I3/影子在此场景全部行为正确。

## 范围（两刀）

1. **第一刀：恢复指令 one-shot 化** —— 删除 agentHistory 原地变异；
   `pendingForcedChoiceHint` 每轮迭代头部取走即清零，仅在请求副本
   （`assembly.injected` → boundedHistory）最后一个 user 消息上追加；
   同一逻辑请求内的连接重试/降级共用同一份（同一个 turn 迭代的闭包捕获）。
   提示语自带"仅此一条回复、随即过期"声明。
2. **第二刀：选项回应语境标记** —— `sendMessage` 与 `drainQueuedPrompts`
   在构建用户行时检测：上一条可见消息为含 present_choices 卡的助手回合 →
   用户行 parts 追加 `<system-reminder>用户正在回应上一轮的选项卡片…</system-reminder>`。
   落盘（buildUserPartsJson 新增 extraTextParts）+ 内存 contentParts 同源同序；
   UI 走现成 stripSystemReminders 不渲染；模型后续每轮可见；影子装配两侧
   天然平价（同一 DB 行）。流中注入（injectQueuedPromptsAsNewTurn）不挂：
   上条消息仍在流式中，检测不可靠且语义是打断而非回应。

新纯函数对象 `ChoiceInstructionLifecycle`（FORCED_CHOICE_RECOVERY_HINT /
appendForcedChoiceHint / SELECTION_RESPONSE_REMINDER / endsWithLiveChoicesCard）。

## 不变量

- 恢复指令绝不进 agentHistory / DB；寿命＝被强制的那一个逻辑请求。
- 语境标记只进用户行 parts（content 保持玩家原文），且内存与 DB 同源同序。
- I1/I2/I3、影子装配两级信号不受影响（只增文本部件，不增删消息）。
- `choiceRepairAttempted`/`forcedChoiceToolOnly` 语义不变（每用户消息一次恢复）。

## 测试墙（ChoiceInstructionLifecycleTest）

瞬态性（同实例返回/最后 user 才改/输入零变异=事故根因断言/无 user 或空表 no-op）、
措辞金丝雀（自带过期声明）、循环回归（复刻事故形状：N 轮带提示、N+1 轮起
全文无 "Do not write prose"）、标记单块形状（剥离依赖）、卡检测四象限、
标记只进 parts 不进 content。

## 台账

### 净眼一审（2026-09-18，裁决：退回修复后复审）

五场景：①强制重试链走通（取走清零仅四处出现/COMPACTED 不丢提示/提示只进
请求副本不进影子比较/重试重入共享同一逻辑请求）；②点选链走通（时序/落盘
内存同源同序/模型可见/UI 双路径干净）；③自由输入走通；④非卡链走通；
⑤drain 队列链**走不通**（P1-1）。

三分类处置（commit a3f474c 后追加修复）：

- **采纳 P1-1**：enqueuePrompt 的排队占位气泡（role=user）恒垫 _messages
  尾部，drain 读 lastOrNull() 恒 false、drain 半边死代码 → 改
  `lastOrNull { it.role == "assistant" }`。
- **采纳 P2-2**：toLLMMessage 把标记折进重载 content（live=原文 vs DB=原文+
  标记）→ 影子 fingerprint 弱信号噪音 + latestVisibleUserRequest 不对称 →
  text case 加 `startsWith("<system-reminder>")` 特判，只进 parts 不折
  content（照 <user-attached-files> 先例）。
- **采纳 P2-3**：pruneDeletedMounts/compact 的 system 通知先于检测插入尾部
  → 标记静默丢失 → sendMessage 检测改
  `lastOrNull { role=="assistant" || role=="user" }` 跳过 system 气泡。
- **采纳 P2-4**：loop regression 硬编码 null 测不到接线 → 改状态机式驱动
  （pending 变量取走即清零 + 同迭代重试共享不累积断言）。
- **挂账 P3-5**：choiceRepairAttempted 现仅剩日志用途（非本次引入）。
- **挂账 P3-8**：多段排队合并只挂一次标记——drain 合并整队为单行、
  extraTextParts 传一次天然成立，VM 私有接线层无纯函数可测，记台账。
- **记录 P3-6**：标记文本与恢复三条中文正则无匹配，不误触发（防回归）。
- **记录 P3-7**：本机无 JDK，编译/单测由 CI 补（流水线）。

净眼二审（2026-09-18，窄复核 83646e3 四处修复，裁决：**可合并**）：

- P1-1 成立：卡块经 updateAssistantMessage 写回 canonical _messages 留存；
  drain 与 runAgentLoop 同协程相邻无插入窗口；system 通知被谓词天然跳过；
  "找到老卡回合"要求其后无任何 assistant，语义即"该卡就是最后回合"。
- P2-2 成立：全库 reminder text 部件三源头盘点（标记/resume 提醒 8004/
  停止截断 12703）；停止截断行的同类 content 不对称被顺带修复；resume 行
  content 变空经三家 provider/pureChat/compact/UI/latestVisibleUserRequest
  全消费方核验无回归。
- P2-3 成立：forkBeforeEdit 后尾部=被编辑消息之前，挂/不挂方向均正确；
  占位气泡落入 user 分支不挂＝安全方向（缺标记只退回旧行为，循环根因是
  提示永久存在而非标记缺席）。
- P2-4 成立：hintN1 空值与 107 行清零构成因果依赖（删 107 行即红）；
  count 谓词非恒真非恒假。
- 新问题：无 P0-P2；P3-a（count 谓词对挂错消息不敏感，同文件专用用例
  兜底）/P3-b（VM 私有接线层无纯函数可测＝P3-8 已挂账）/P3-c（resume/
  停止截断行 content 变化，方向良性）/P3-d（正文逐字以 <system-reminder>
  开头的理论边角）。

另：83646e3 CI 红——no-op 用例 assertSame 实参两次构造恒败（我的测试错，
非产品代码），167f46e 修复。

守纲六问（2026-09-18，裁决：**准予合并**）：

1. 功能模块零 diff——通过：diff 恰四处，九个 hunk 逐一核对无夹带（唯一
   "顺带"是删除描述已死旧行为的过时注释，属必要清理）。
2. 无私自开出口——通过：提示经装配线之后的 boundedHistory 追加且
   PreSendContract 三断言直接收 boundedHistory 全过；标记经用户行 parts
   落盘与 toLLMMessage 既有 text case；无 provider 层改动。
3. 无新旧并存——通过：旧变异物理删除 23 行；grep 全源 FORCED 提示生成仅
   一处、标记生成仅两个发送入口；恢复策略本体零 commit。
4. 无过度工程——通过：新文件 66 行=两常量两纯函数，无钩子无配置。
5. 不违插槽——通过：三层各就其位；提示寿命是循环局部态故不做成装配线
   第十段（塞进 assemble() 会耦合 UI 循环态），顺序定义仍只在 assemble()。
6. 上轮兑现——通过：四项闭环对上代码行号；P3-6 独立验证（正则不匹配+
   latestVisibleUserRequest 既有过滤双保险）。

附加判断：A（非文游面）——Bashism/EnvVar 提醒在 toolResult content 非
text 部件、管理层请求直读 partsJson 不经 toLLMMessage，App 自写入的
reminder text 部件只有新标记一种，特判是恢复平价非改行为。B（完整性）——
两刀断两根因；四个第三入口（流中注入/retryLast/侧边/旧行）逐一排查，
未覆盖处均退回旧行为、无新增错误路径。

- CI：一审 ee844fe 红（漏 import）；二审 83646e3 红（assertSame 两次构造）；
  167f46e/d6ed863 待终版

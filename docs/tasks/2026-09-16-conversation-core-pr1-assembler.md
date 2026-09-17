# PR 1 任务书 · 装配线（RequestAssembler）

上级文档：[2026-09-16-conversation-core-rework.md](2026-09-16-conversation-core-rework.md)

目标：组装链收敛为单一编排入口（纯函数、步骤注入、顺序唯一）；
内存侧即刻走装配线（同函数同序，行为按构造不变）；DB 侧影子装配对比指纹，
让双真相源的分歧在切换前可见。同时清 PR 0 挂账 P2-1 / P2-3 / O-1。

## 设计决策（偏离总纲处已注明）

- **放 app 模块而非 conversation-runtime**：LLMMessage/AgentContentPart 是 app 模块类型，
  物理搬模块=大范围类型迁移（违背"小步"）。装配线的价值在"编排唯一+可测"，
  模块边界留给未来。净眼/守纲按此口径审。
- **步骤函数注入而非整体搬家**：compactRebuild/orphanRepair/retentionProject/imageBudget/
  injections 以 `(List) -> List` 参数注入（IO 与 ViewModel 状态留在闭包里），
  装配线拥有顺序与诊断。避免复制逻辑（新旧并存）与 300 行大搬家。
- **内存侧即刻权威**：effectiveAgentHistory 重写为"收集输入 → assemble()"，
  调用的是与原内联路径完全相同的函数与顺序——行为按构造不变，且内联顺序漂移
  从根上不可能（只剩一处顺序定义）。
- **PR 0 的 P2-2 快照孤儿修复上收**：快照段的孤儿修复从 prependSideSnapshotHistory
  移入装配线的快照步骤（结构性，不再散落）。

## 范围（文件清单）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `app/src/main/java/com/openminis/app/ui/chat/RequestAssembler.kt` | 新增：编排+指纹+诊断 |
| 2 | `ChatViewModel.kt` | effectiveAgentHistory 重写为装配线调用；prependSideSnapshotHistory 改为解析器（返回原始主线，修复上收）；I1 基线重置（P2-1）；DB 侧影子装配（P2-3） |
| 3 | `provider/gemini/GeminiProvider.kt` | 两个发送口接 wire-capture（O-1，**URL 剥 ?key=**） |
| 4 | `app/src/test/.../RequestAssemblerTest.kt` | 新增：顺序/快照修复/指纹/诊断 |

不动：cards/、侧边模块文件、文游、预设（零 diff 硬验收）；PreSendContract 语义不变。

## 不变量

- A1 顺序唯一：九段顺序（scope→compact→blank→orphan→snapshot(段内孤儿修复)→
  retention→pureChat→imageBudget→injections）只在 assemble() 定义一次，发送出口
  消费 Result.assembled/request/injected；
- A2 行为等价：内存侧 assemble 输出与旧内联路径同函数同参同序，主路径逐字节等价；
  **一处已接受的边缘改进**：侧边"管线后为空但 agentHistory 非空"时旧代码不发快照、
  新代码以快照主线为请求（侧边仍获主线上下文，方向更合理；净眼 P2-1 定级低危）；
- A3 影子只观察：DB 侧装配 diff 仅日志+audit，不拦截不阻塞；两级信号——structural
  （强：丢/多消息、工具对断裂）与 content（弱：卸载改写等已知投影噪音，只计数）；
  内存专属 Text 部件（工具结果提示语/轮数提示/图路径注释）两侧剔除；仅主线参与；
- A4 I1 基线重置：turn==0 或注入产生的新逻辑轮（pendingI1BaselineTurn）时重读 DB；
- A5 expected 结构化：DB 侧基线过同款 compact/blank/retention 投影（P2-3 关账）；
- A6 密钥不落盘：Gemini 抓取 URL 剥离查询串。

## 台账

### 净眼审查（agent_8c78023b，结论：退回修复 → 已按最低修复集闭环）

| 编号 | 结论 | 处置 |
|---|---|---|
| P0-1 快照测试断言必红（repaired 捕获的是输入而非输出） | **采纳已修** | 测试重写：断言 snapshotOrphanRepair 收到原始快照段、修复产物前拼、主列表只过 orphanRepair 一次 |
| P1-1 影子四大噪音源（阶段差/TOOL_RESULT_HINT 不落盘/卸载改写/图路径注释） | **采纳已修** | ①DB 侧补 retentionProject 阶段对齐；②MEMORY_ONLY_TEXT_PREFIXES 两侧剔除；③两级信号分流：structural=强信号定位，content 差异=弱信号只计数（卸载改写归此类） |
| P1-2 快照孤儿修复被"在飞豁免"挡住（净眼发现 PR0 P2-2 修复对崩溃窗口场景实际无效） | **采纳已修** | Inputs.snapshotOrphanRepair 独立注入，dropOrphanedToolParts 加 exemptTrailing 参数，快照段传 false（冻结态无在飞轮次）；测试复刻真实语义 |
| P1-3 尾三段（pureChat/imageBudget/injections）未进装配线 | **采纳已修** | 出口改为 assemblyInputs().copy(尾三段闭包) → assemble() → 消费 Result；骰子先掷 |
| P2-1 快照早退语义边缘分歧 | **采纳为已记录改进** | A2 口径已更新（见上），不再声称纯"按构造不变" |
| P2-2 基线重置边角（fallback 重读口径/COMPACTED continue 吞标志/末轮过期标志） | **挂账 PR2** | 状态机统一 attempt/轮次语义时一并收敛 |
| P2-3 firstDivergence 长度不等时 -1 误导 | **采纳已修** | tail(len a vs b) 显式标记 |
| P2-4 Gemini 流式口 record 在调用方调度器 | **挂账** | 与既有 provider 流式口模式一致，不单独破例；后续统一挪 IO |
| P2-5 侧边 getSession 瞬时失败误入影子 | **挂账** | 罕见；PR2 收敛时顺手分流 |

### 净眼修复后复查（同 agent，commit 7f3a313，结论：放行进入守纲）

四项退回项全部兑现且证据链闭合（测试取值链/九段接线/删净核对/豁免接线/噪音分级逐项复签）。
CI（android-validate）同轮全绿。新增问题全 P2 级：

| 编号 | 结论 | 处置 |
|---|---|---|
| P2-1' MEMORY_ONLY 前缀是字面量拷贝，测试自引用 | **采纳已修** | 列表改引用 ChatViewModel 的 const（编译期内联）；附件图前缀提取 ATTACHED_IMAGE_NOTE_PREFIX 且三处构造点统一引用；测试锚定真实常量 |
| P2-2' attempt 处注释过期（仍提 effectiveAgentHistory） | **采纳已修** | 注释更新为 assemblyInputs 口径 |
| P2-3' ToolUse.input/thoughtSignature 两级指纹全盲（#179 同类） | **采纳已修** | fingerprint 的 U 项加 input.length 与 thoughtSignature 存在性（弱信号层） |
| P2-4' 工具产图在 DB 不可还原 → 常态强信号 | **认知项记录** | 这是地震仪如实曝光持久化缺口，非误报；守纲与 PR2 排期知悉"强信号 ≠ 内存侧必有 bug" |
| P2-5' 估算路径（retainedContextEstimate 等）仍内联 | **挂账 PR2** | 状态机收敛时统一走装配线 |

### 守纲裁决

（待填）

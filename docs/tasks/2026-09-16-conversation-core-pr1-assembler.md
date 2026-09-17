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

- A1 顺序唯一：scope→compact→blank→orphan→snapshot(含段内孤儿修复)→retention→
  pureChat→imageBudget→injections，只在 assemble() 定义一次；
- A2 行为等价：内存侧 assemble 输出与旧内联路径逐字节同序同值（同函数同参同序）；
- A3 影子只观察：DB 侧装配 diff 仅日志+audit 事件（shadow_assembly_diff），不拦截不阻塞；
  仅主线会话参与（侧边有快照前拼的合法差异）；桥接消息（无 dbMessageId）不参与指纹；
- A4 I1 基线重置：turn==0 或注入产生的新逻辑轮（pendingI1BaselineTurn）时重读 DB；
- A5 expected 结构化：DB 侧基线过同款 compact/blank 投影（P2-3 关账）；
- A6 密钥不落盘：Gemini 抓取 URL 剥离查询串。

## 台账

（待净眼/守纲审查后填）

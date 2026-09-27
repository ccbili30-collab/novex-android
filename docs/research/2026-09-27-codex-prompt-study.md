# Codex 内部提示词研究：他们怎么写，我们怎么借

日期：2026-09-27 · 动因：用户"我们的提示词怎么写的？去找 codex 这种优秀
智能体的内部提示词" · 材料源：openai/codex 仓库（Apache-2.0）
`codex-rs/core/prompt.md`（基础版）、`gpt_5_2_prompt.md`（旗舰版
~21.7KB）、`review_prompt.md`（审查子智能体）

## 一、Codex 提示词的骨架

三份文件三种角色，各自完全不同的写法：

1. **基础版 prompt.md（极短）**：身份一句话（"You are a coding agent
   running in the Codex CLI…precise, safe, and helpful"）→ 能力清单
   → 协作规则（何时询问/如何确认）→ 环境感知（git 状态/平台）→
   安全边界。通用人格底座，不塞任务细节。
2. **旗舰版 gpt_5_2_prompt.md（长文，markdown 层级）**：同一骨架展开
   成"How you work"章节树：计划先行（质量分级样例）→ 逐文件确认
   → 工具协议（apply_patch 专页）→ 测试/调试循环 → 提交纪律。
3. **审查版 review_prompt.md（窄身份+枚举判据+输出 schema）**：
   "You are acting as a reviewer" 一句定身份，八条判据每条一句锋利
   的话，输出 JSON schema "MUST MATCH exactly"。

**关键认知：他们没有"一段万能提示词"，而是按角色分文件、按消费者
组装。我们的四段提示词（主系统/卡片契约/快照/整理）同构，只是写得
不如人家规整。**

## 二、可搬的八个模式（附我们的对照）

| # | Codex 模式 | 原文例证 | 我们现状 |
|---|-----------|---------|---------|
| 1 | **正反指令成对**（Do X. Do NOT Y.） | "NEVER try `applypatch` or `apply-patch`, only `apply_patch`"；"Do not guess or make up an answer" | 多数只写正面；快照提示词本批已补（"用空字符串，不要填'无'"） |
| 2 | **窄身份**（一句话框死职责） | "You are acting as a reviewer for a proposed code change" | 快照"你是状态记录器，只维护一份世界状态快照"（本批收窄） |
| 3 | **条件分支显式化**（不同环境不同行为） | 按 approval mode 分支：never/on-failure 下主动跑测试，untrusted 下先问 | "非游戏对话→全空输出"（本批）；卡片契约按 routing 分支已有雏形 |
| 4 | **输出契约精确到字段+完整样例** | "MUST MATCH *exactly*"; "Do not wrap the JSON in markdown fences"；逐字段+数值映射 | 整理/快照提示词已做到（JSON 字面量+逐字段），保持 |
| 5 | **校准的严格度**（严格度锚定基线，不凭空拔高） | "does not demand rigor absent from the rest of the codebase"；"prefer outputting no findings"（精确优先于召回） | 无此意识——我们的审查/守卫提示词常隐含"越严越好" |
| 6 | **优先级模型显式声明** | "more specific guidelines override these general instructions"；直接指令 > AGENTS.md > 默认 | 卡片契约十几条平铺、无优先级，模型自己猜哪条优先 |
| 7 | **按规模校准输出量** | "Verbosity (enforced)"：≤10 行变更→2-5 句；中等→≤6 bullets；大→每文件 1-2 条 | 无对应物（我们无"AI 该说多长"的显式规则） |
| 8 | **好坏样例并置**（few-shot 正反对照） | 计划章节给了高质量/低质量两版计划全文对照 | 无样例，纯规则描述 |

## 三、我们四段提示词的诊断与处置

- **快照提示词（NovexStateSnapshot）**：本批已按模式 1/2/3/4 重写
  并上契约测试（PR #35）。收口。
- **整理提示词（NovexNotebookStore.consolidationPrompt）**：骨架已对
  （角色+上限+契约+数据）。缺两件：范围排除（"临时闲聊/已被推翻的
  旧条目不记"式反句）与淘汰的显式判据。半小时级小活，下批顺手。
- **卡片契约教学（IntegratedCardPrompt）**：最值得整段重构。现状：
  十几条管理条款平铺无优先级、无正反句式、无按 routing 分支的行为
  指引；用户报过的"对话提示词被压到尾部不代入"就是这种平铺结构的
  症状（位置修了，结构没修）。按模式 6+8 重构：优先级声明+分层+
  关键行为正反样例。需单独成批（长提示词重构要 A/B 验证代入行为）。
- **主系统提示词（NovexSystemPrompt.buildPrepared）**：与卡片契约
  同批重构候选；重构前先做一次"现行全文走查"确认无同类平铺病。

## 四、边界认识

- Codex 的提示词是英文、面向代码智能体；直译句式无意义，搬的是
  **结构模式**（成对指令/窄身份/显式分支/优先级声明），不是词句。
- 旗舰版的长度（~22KB）是给足上下文的代码任务特化；我们的主系统
  提示词在 100 万窗下可以更从容，但手机端旁路调用（快照/整理）必须
  保持现在的短小——他们子智能体（review_prompt）同样是短的。
- 提示词不是万能阀：模型违规填"无"的残留（快照链）提示词只能压制
  不能杜绝，兜底仍是代码守卫（挂账，等动压缩区域的 PR 顺手带上）。

## 五、来源

- https://github.com/openai/codex（Apache-2.0，Rust workspace codex-rs）
- 基础提示词：`codex-rs/core/prompt.md`
- 旗舰提示词：`codex-rs/core/gpt_5_2_prompt.md`
- 审查提示词：`codex-rs/core/review_prompt.md`

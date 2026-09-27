# 快照提示词退化改造（创作会话不产虚构快照）

日期：2026-09-27 · 分支 task/snapshot-prompt-degrade · 状态：终态（PR #35 已合并）

## 背景（用户裁决链）

用户追问"纯创作时压缩起什么作用"→ 代码查证发现压缩成功后的快照
生成/自动档落盘无游玩守卫（stage2 台账已挂账，建议代码守卫
binding.primary != null）。用户裁决：

1. 不做代码守卫（增加成本，不值得单独走批）；
2. "我们要解决第二个问题。能通过提示词解决就直接解决吧"——第二个
   问题=模型把正在创作的卡片虚构内容编造成"世界状态"交作业。

## 方案：纯提示词（现有链路自断，零代码改动）

关键发现：`NovexStateSnapshot.parse` 对 time+main 全空的 JSON 返回
null，`generate` 透传 null，ChatViewModel 压缩尾部
`generate(...) ?: return@launch` 直接跳过整段下游——不存快照、不写
压缩自动档、不设状态锚。因此只需教会模型"非游戏对话输出全空串"，
/saves 垃圾条目、锚行噪音、虚构快照三个问题一并消失。

（残留：模型不守约填"无"时仍会入链——提示词已用"不要填无"正反
句式压制；模型持续违规的兜底仍是挂账的代码守卫，不本批做。）

## 提示词改写（对照 Codex 风格）

研究 openai/codex 仓库内部提示词（codex-rs/core/prompt.md、
gpt_5_2_prompt.md、review_prompt.md）后采用的句式：

- 角色一句话收窄："只维护一份世界状态快照"（Codex reviewer 同款
  窄身份）
- 正反指令对："用空字符串，不要填'无'，也不要把正在创作的内容
  编造成剧情"（Codex "Do X. Do NOT Y." 同款）
- 退化分支给出完整输出样例（全空 JSON 字面量），不只描述规则
- 范围规则显式排除："创作、编辑、讨论写作的内容不属于剧情"

## 变更清单

- NovexStateSnapshot.kt：SNAPSHOT_SYSTEM_PROMPT 收窄角色；prompt()
  加范围排除行+非游戏退化行（含全空 JSON 样例）+字段说明改逐条列出
- NovexStateSnapshotTest.kt：新增 `prompt carries non game degrade
  clause` 提示词契约测试（退化条款四关键词任一丢失即红；全空串
  parse→null 已有旧测覆盖）

## 台账

- stage2 挂账项"快照+压缩自动档缺游玩守卫"以本批提示词方案收口；
  代码守卫维持挂账不排期（用户裁决：边际成本不值单独走批，等下个
  动压缩区域的 PR 顺手带上）
- 净眼六场景全过（游玩不回归/自断链路/违规"无"残留与描述一致/测试
  契约转义字节级核对/旧措辞全仓零依赖/无第二消费者）；两条非阻塞
  备忘留痕：创作会话每轮压缩烧一次被丢弃的旁路调用（方案固有成本）、
  游玩中大段讨论写作可能误走全空分支（后果仅本轮快照缺失，旧快照
  保留）
- 守纲六问过；CI 绿（Unit tests and channel compile）
- 合并：PR #35 → next（fb3dd3d）；发布：v3.0.5-beta.80
- 同日姊妹产出：docs/research/2026-09-27-codex-prompt-study.md
  （Codex 内部提示词研究，用户指令"找 codex 的内部提示词怎么写的"；
  整理/卡片契约/主系统三段提示词的后续重构以该研究为母本挂账）

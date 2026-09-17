# 任务书：建卡工具链修复（card-tooling-fix）

## 背景（事故）

2026-09-17 用户反馈（会话 b314941f 导出包）："建卡太拖沓"。量化：**46 分钟、
15 轮、14221 输出 tokens，建一张 6KB≈3000 字的卡（产出/成品比 7.1×），最终
仍未填完且建出两张重复卡**。工具执行全部 1 秒级——拖沓全在轮数与等待。

三层根因：

- **A【真 bug】schema required 与解析层契约矛盾**：`tool()` helper 把全部
  字段标 required，而 `write_module_text.block_id` 文档明说"新建传空字符
  串"、解析层 defaulted 集合（block_id/before_id/after_id）缺省视为空串。
  app 发送前预检按 required 把模型按文档传的 `""`（#55 原样）与缺省都当
  "参数缺失"拒发——该工具对新建块**结构性不可用**，模型三试三弃换
  write_module_markdown。同族：save_conversation_image(module_id/after_id)、
  insert_owned_image(after_id)、move_module/move_content_block(before_id)。
- **B【格式陷阱+报错不教人】bulk 字符串编码不兼容**：模型/中转把
  `modules`/`module` 整体编码成字符串（#59 形状），optJSONArray/optJSONObject
  判"缺失"，报错无正确形状示例，模型盲猜重试一轮；add_module_bulk 失败后
  模型回退 15×add_module 单发。
- **C【引导矛盾】SKILL.md 教慢路径**：wenyou-maker v1.1.0（SKILL.md 第三步）
  白纸黑字"建卡后逐个 add_module + write_module_text 写入"——与
  create_card_bulk 定义里"建整卡必须用它"直接矛盾，模型照 skill 走骨架弯路。
- 外部因素（不修）：模型组延迟方差 31s↔11min 同形状（会话绑模型组，慢轮
  伴随 57-65K 全量缓存重建），App 侧只能观测/降权，记挂账。

## 范围（本 PR）

1. **P0 根治（schema 侧单一真相源）**：`tool()` helper 增加 `optional`
   参数，required = 全字段 − optional；五个文档承诺空串语义的调用点标记
   （write_module_text.block_id / save_conversation_image.module_id+after_id /
   insert_owned_image.after_id / move_module.before_id / move_content_block.before_id）。
   桥接（IntegratedCards.definitions）自动把 required 传给 app 预检与模型
   契约——一处改动，预检不再拦 ""/缺省，模型可见 schema 同步改诚实。
   **不改 app 白名单**：字段既为 optional，预检只遍历 required，天然放行。
2. **P1a bulk 字符串容忍 + 教学报错**：CardBulk.parseTree 与
   add_module_bulk 解析接受字符串编码的数组/对象；真缺/真坏时报错附最小
   正确形状示例（照抄即对）。
3. **P1b 引导收敛**：SKILL.md 第三步改 bulk-first（禁止先空骨架再逐个填；
   bulk 不带携带规则 → 树成型后 set_module_options 补），版本 1.1.0→1.2.0
   触发存量安装升级；IntegratedCardPrompt 同步（整卡必须 bulk + block_id
   空串或省略均可）。

## 不变量

- 解析层 defaulted 语义不变（缺省/空串/伪编号→默认动作），只放开 schema
  对这几个字段的 required 谎报。
- 语义真必填的不放开：remove_content_block/replace_text_range 的 block_id
  仍 required（测试钉死）。
- CardEditingTools（place_module/add_child_module 等）的严格键集合与幂等
  canonical 记录**不动**——同族隐患挂账（见遗留），其 exact-key 校验与
  canonical 序列化是重试幂等性的承重墙，本轮不碰。
- readTool 族无空串语义字段，不动。

## 测试墙（CardToolProtocolLenientParseTest 追加）

- documentedEmptyStringFieldsAreNotRequiredInSchema：五工具 optional 字段
  不在 required + 两个真必填 block_id 仍在（防顺带放开）。
- writeModuleTextEmptyStringBlockIdIsANewBlock：#55 原样形状 ""→新建。
- createCardBulk/addModuleBulkAcceptsStringEncoded*：#59/#61 形状字符串编码
  兼容。
- bulkMissingArgsErrorTeachesTheCorrectShape：报错含正确形状示例。

## 台账

### 净眼一审（2026-09-18，裁决：可合并）

六场景链全走通（optional 三拼写/真必填防线/桥接一致性含 provider strict
排查/bulk 字符串链含上限仍生效/SKILL 升级链含字典序本次正确/三方文案）。
无 P0/P1。

- P2（挂账）：block_id 键名 typo（block_ld）旧版被 fuzzy 修复或预检拒，
  新版静默降级"新建块"致重复正文——optionality 固有代价，
  created_block_ids+read_card 可自愈；跟进项：ToolJsonRepair fuzzy 匹配
  扩展到 properties 全集。
- P3（挂账）：显式 JSON null 的 before_id/module_id 经 optString 得
  "null" 字符串（write_module_text 因 NEW_BLOCK_HINTS 恰好安全）——
  跟进项：optional 定位字段统一过滤 "null"。
- P3（存量，记录）：depthOf 死代码/版本字典序比较/copyAssetTree 不清旧
  文件。
- P3（已修）：SKILL.md 微调句 bulk 语义错位（本 commit 修正）；
  任务书版本笔误 v1.1.1→1.1.0。

### 守纲终裁（2026-09-18，裁决：准予合并）

六问全过（零 diff/单一真相源无旁路/无新旧并存/无过度工程/层次正确/
挂账边界守得住）。Q2 反例当场关账：save_conversation_image.module_id
schema optional 但解析层 defaulted 不含 → 省略拼写抛"工具字段缺失"浪费
一轮——已修（defaulted 按工具扩展 + 回归测试）。

新增挂账（守纲 B）：
- card-organizer/SKILL.md:26 仍教 create_card→add_module→write_module_text
  骨架路径，与 bulk-first 工具描述矛盾——下个 skill 批次一并改。
- optional 定位字段统一过滤显式 JSON null（"null" 字符串）与
  ToolJsonRepair fuzzy 扩展（与净眼 P2/P3 合并为一条跟进项）。

- CI：2051d4c 红（泛型默认值 >= 词法坑）；语法修正+守纲关账推送后待终版

## 遗留挂账

- CardEditingTools 族（place_module.parent_id/before_id、
  add_child_module.parent_id、place_internal_character.module_id）同款
  "文档空串语义 vs required+exact-key"隐患——需连 parse 的 exact-key 与
  canonical 幂等记录一起设计，独立 PR。
- 模型组延迟方差观测/慢成员降权（外部渠道问题，App 侧缓解方案另议）。

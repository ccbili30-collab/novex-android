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
- **C【引导矛盾】SKILL.md 教慢路径**：wenyou-maker v1.1.1（SKILL.md 第三步）
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

- 净眼审查：待
- 守纲六问：待
- CI：待

## 遗留挂账

- CardEditingTools 族（place_module.parent_id/before_id、
  add_child_module.parent_id、place_internal_character.module_id）同款
  "文档空串语义 vs required+exact-key"隐患——需连 parse 的 exact-key 与
  canonical 幂等记录一起设计，独立 PR。
- 模型组延迟方差观测/慢成员降权（外部渠道问题，App 侧缓解方案另议）。

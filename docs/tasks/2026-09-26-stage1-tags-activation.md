# 阶段 1 实施任务书：模块标签系统 + 开局激活协议（stage1-tags-activation）

总纲：2026-09-26-memory-system-rework.md §3.1/3.2/3.3/§4 ｜ 日期：2026-09-26
侦察依据：2026-09-26 代码走查（行号见下文各节）

## 切分：本阶段两个 PR

- **PR-A 数据层**：标签 schema + 工具/编辑器/SKILL 支持 + 存量兼容
- **PR-B 对话层**：开局激活协议（系统消息/启动词/槽位拼接/自动首回合）
- B 依赖 A；各自独立走净眼/守纲/CI。

## PR-A：标签 schema

### 设计决策（依据侦察）

1. **标签为 ContentModule 平行字段**，不动 ModuleUse 三态（Always/Manual/
   Keywords 保留为"待命"体系的触发规则）：
   - `routing: String? = null` —— 取值 `default` / `perTurn` / `style` /
     `standby`；**null 即"默认"**（存量卡零迁移：旧 JSON 无此字段 →
     语义=进开局资料包，恰是总纲"存量全标默认"）
   - `temporality: String? = null` —— 取值 `constant` / `snapshot`；
     **null 即 constant**（安全侧：常量可重注入；快照型内容误标常量的
     干扰风险由阶段 3 重注入元说明兜底）。阶段 1 仅存储+编辑，消费在阶段 3。
   - ⚠️ 命名坑（侦察确认）：ModuleOptionsProtocol.kt:30 的 `constant`
     别名现映射 `use=always`——时间性标签用独立字段名 `temporality`，
     与 use 的 kind 值域不相交，无冲突；但 set_module_options 的 rule
     参数描述文本需写清两者区别。
2. **schema/codec**：CardStructureCodec 模块级可选键白名单（L73）加
   `routing`/`temporality`；encode/decode 各两分支；docs/specs/card-format.md
   同步（规范红线：改格式必须同步 CardRoundTripReconciliationTest）。
3. **编辑器**：ModuleOptionsDialog 加两组单选（路由 4 选 1 含"未设=默认"；
   时间性 2 选 1 含"未设=常量"）；EditorCommand.ModuleOptions 扩参数。
4. **建卡工具**：BulkModuleNode 加 `routing?/temporality?` 字段 +
   moduleSchema() properties + parseArray + buildModules 透传；
   CardToolProtocol 的 create_card_bulk/add_module_bulk schema 描述与
   defaulted 集合同步（PR#27 的契约先例：optional 字段解析层 defaulted）。
   set_module_options 扩同名可选参数。
5. **SKILL**：wenyou-maker 出卡指导——引擎/世界观/规则类模块标默认+常量；
   开局状态类（起始时间线/初始关系）标默认+快照；事件库/人物库等检索型
   标待命（触发规则用 use.keywords）；每轮注入/文风作为特殊模块示范。
   版本 1.2.0→1.3.0，SkillRepositoryBundledAssetSkillTest 断言同步。
6. **消费侧（本 PR 仅最小接入）**：IntegratedCards.candidates() 的
   ModuleAdoption 判定改为：routing=default → 视同 Always（开局/全程进）；
   routing=standby → 沿用现有 use 三态判定；perTurn/style 模块不进材料
   流（PR-B 路由到槽位）。AI 选择器（UNCONFIGURED 分支）此 PR 不动。

### 测试墙（PR-A）

- codec 往返：新字段有/无各往返；旧卡（无字段）读出=default/constant。
- CardRoundTripReconciliationTest 同步新格式样例。
- ModuleOptionsProtocol：新参数 lenient 解析 + constant 别名不撞车。
- CardBulk：带标签建卡全链路。
- ModuleAdoption：routing 判定矩阵（default/standby/perTurn/style × use 三态）。

## PR-B：开局激活协议

### 设计决策（依据侦察）

1. **系统消息落库**：复用 role="system" + parts 现有结构（渲染管线
   FallbackInfoBlock 居中可展开已存在，ChatFlatItems.kt:653 分支已有）。
   - 写入：开局资料包 = 一条持久化 system 消息（ChatRepository.appendMessage
     对任意 role 透明，侦察 §2），payload 含模块清单（id/name/token 估算）
     与全文，iconKind="card-activation"。
   - **请求侧过滤（侦察坑）**：toLLMMessage 非 user 一律映射 ASSISTANT
     （ChatViewModel.kt:13839）——资料包对模型的投影不走"把这条 system
     消息当 assistant 发"：投影为**对话流位置的 user 上下文块**（拼接进
     首轮请求的装配，见 3），DB 里的 system 行在投影时跳过（如同现有
     内存 system 行不进请求的语义）。
2. **挂卡即激活的触发点**：
   - draft 路径（卡库→新会话）：ensureSession 完成后（binding 已入行）。
   - 已有会话路径（IntegratedCardSettings 保存）：saveIntegratedCardBinding
     成功后（非 streaming 约束天然满足）。
   - 幂等：binding 里记 `activatedAt`/`activationEntryId`，重复挂/换卡
     重新激活（换卡=新资料包；同卡不重复）。
3. **启动词与首回合**：
   - 资料包投影文本 = 模块全文（按卡内顺序）+ 启动词尾注
     （"以上为本局全部开局资料，已就位。请按此卡运行并开始第一幕。"）
   - 自动首回合：激活后经 ChatViewModel 内部发送链发起（复用 sendMessage
     的上下文准备与 agent loop；不走 HeadlessChatRunner——那是 debug 包，
     且走 VM 内链路才能正确处理 gameEntryState 门禁与 UI 状态）。内容：
     文游卡 → 启动确认轮（"启动完成，开场引导"由模型生成）；
     角色卡 → 直接入戏开场。触发文案区分卡类型（卡的 kind 字段）。
   - 门禁：sendMessage 6806 的 gameEntryState 拒发对内部激活轮放行
     （NovexGameEntryController 状态协调）。
4. **槽位拼接**（侦察 §3：appendRuntimeInjections 是唯一拼接点，
   ConversationSettings.kt:147-180）：
   - 激活时：perTurn 模块文本 → perTurnPrompt（空则填，有则 "\n\n" 追加）；
     style 模块 → textStylePrompt 同语义。
   - 来源标注：激活系统消息 payload 里记"已向槽位注入 X 字"，用户在
     设置页可见可改（现有 UI 零改，文本已在那里）。
5. **放不下绝不静默漏**：开局资料包投影后若 estimate 超限 → 拒发首轮 +
   错误明示"开局资料 X token 超出本会话容量 Y，请在卡设置中减少默认模块
   或调大容量"。

### 测试墙（PR-B）

- 激活幂等（同卡不重复/换卡重激活）。
- 资料包投影：DB system 行不进请求、投影块进首轮请求、启动词在场。
- 槽位拼接：空填/追加两态。
- 放不下报错路径。
- gameEntryState 放行协调。
- 导出包含资料包行（原样）。

## 不变量

- 旧卡零迁移（null=default/constant）；旧版本 App 读新卡报错是格式既定
  行为（codec 严格校验，规范文档明示）——不做向后兼容妥协。
- ModuleUse 三态语义不变（standby 触发体系原样）。
- appendSystemInfo 内存行与持久化 system 行的渲染一致性（同管线）。
- 每 PR 净眼+守纲（对照总纲 §3.1-3.3，不越界动阶段 2/3 内容）。

## 台账

- PR-A 净眼/守纲/CI：待
- PR-B 净眼/守纲/CI：待

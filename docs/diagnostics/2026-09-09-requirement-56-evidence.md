# 原 56 项问题与当前验收证据对照

此表继承原编号与问题，不新增编号改变分母。原始映射来自 2026-09-07 的问题细化记录；当前状态以 3.0 工作树和实际结果为准。**全部条目仍需按完整问题验收，表中的软件测试只证明其具体断言，不证明模型每次理解、审美或长期事实质量。**

本次索引核对的应用结果：`local/qa/v3-execution-20260909/receipt-chronology-green-results.tar.gz`，345 项中 344 通过、1 跳过，无失败。下列测试类均在该归档中；名称单参数存档改动在其之后，另等下一批结果。真实模型依据为 `creation-model-promoted-card`（13 次调用，持久化链通过但语义未过），长资料依据为 `learning-small-convergence`（原题未过）。原来只用模拟工具的候选成绩不并入新成绩。

## 软件证据索引

- **对象与采用**：[NovexConversationConfigurationTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexConversationConfigurationTest.kt)、[NovexContextComposerTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexContextComposerTest.kt)。
- **管理与权限**：[NovexExecutionModeTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexExecutionModeTest.kt)、[NovexManagementServiceTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexManagementServiceTest.kt)。
- **卡片创建修改**：[NovexCardFileServiceTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexCardFileServiceTest.kt)、[NovexCardEditingContinuityTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexCardEditingContinuityTest.kt)。
- **引用与版本**：[NovexCardReferencePersistenceTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexCardReferencePersistenceTest.kt)、[NovexCharacterVersionRelationPersistenceTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexCharacterVersionRelationPersistenceTest.kt)。
- **读取与隔离**：[NovexRoleScopePersistenceTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexRoleScopePersistenceTest.kt)、[NovexScopedConversationHistoryTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexScopedConversationHistoryTest.kt)、[NovexCheckpointPrivacyTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexCheckpointPrivacyTest.kt)。
- **目录与创作库**：[NovexLibraryOrganizationTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexLibraryOrganizationTest.kt)、[NovexWorkGroupPersistenceTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexWorkGroupPersistenceTest.kt)。
- **原件与交换**：[NovexExternalCardImportTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexExternalCardImportTest.kt)、[NovexWorldGameExportCompletenessTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexWorldGameExportCompletenessTest.kt)。
- **复制与分身**：[NovexCardCopyPersistenceTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexCardCopyPersistenceTest.kt)、[NovexWorldParallelPersistenceTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexWorldParallelPersistenceTest.kt)。
- **回合与存档**：[InteractiveFictionRuntimeTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/InteractiveFictionRuntimeTest.kt)、[NovexPlaythroughCheckpointTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexPlaythroughCheckpointTest.kt)。
- **教学接入**：[NovexTeachingAssemblyTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexTeachingAssemblyTest.kt)、[NovexRoleCapabilitiesTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexRoleCapabilitiesTest.kt)。
- **状态与身份恢复**：[NovexMixedConversationPersistenceTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexMixedConversationPersistenceTest.kt)、[NovexConversationRuntimeClosureTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexConversationRuntimeClosureTest.kt)。
- **阅读覆盖**：[NovexSourceReadCoveragePersistenceTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexSourceReadCoveragePersistenceTest.kt)、[NovexDocumentReadCoveragePersistenceTest（测试源码）](../../src/android/app/src/test/java/com/openminis/app/novex/domain/NovexDocumentReadCoveragePersistenceTest.kt)。

## 逐项状态

| 原编号 | 原问题 | 原候选场景 | 当前软件证据范围 | 模型与完整验收状态 |
|---|---|---|---|---|
| 01 | 理解软件对象：如何分清作品集合、世界卡、角色版本、文游卡、对话、文件与本局状态？ | 未覆盖 | 对象与采用 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 02 | 识别用户意图：如何区分剧情行动、场外讨论、设定纠正与真实编辑，并处理混合输入？ | N01、R07 | 教学接入 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 03 | 区分使用与管理：同卡兼作背景与编辑目标时，什么影响回答，什么修改原件？ | N05、R08 | 管理与权限 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 04 | 协调多处要求：软件规则、身份、用户风格、文游规则、世界事实与角色指令如何按作用范围协调？ | N01、R04 | 对象与采用 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 05 | 理解授权：如何识别允许的对象、操作、本局已有授权和需要新增申请的范围？ | N06、N07、R08 | 管理与权限 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 06 | 完成原生创作：明确建卡请求如何利用合适目标完成真实内容，而非只给文字或空容器？ | N02、N09 | 卡片创建修改 | 创建角色与文游、单卡三模块有真实安卓证据；批量合理拆卡与完整质量未通过。 |
| 07 | 理解多卡来源：如何辨认多个世界、角色和文游依赖的来源、用途、版本及重复引用？ | N08 | 引用与版本 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 08 | 尊重信息范围：如何避免背景人物专属指令、配套玩家身份与秘密通过读取、搜索或摘要越界？ | R04、R08 | 读取与隔离 | 普通历史、摘要、存档旁路已有软件隔离测试及真实请求检查；跨身份长期模型演绎仍待验。 |
| 09 | 定位资料：如何通过目录、模块和索引读取原文，而非全量注入或凭印象回答？ | N03、N08 | 阅读覆盖 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 10 | 处理超长资料：如何连续阅读、记录覆盖、恢复中断，并区分已读、摘要与未读？ | N03、N10 | 阅读覆盖 | 原小规模可读块 279/279，扫描附录未解析；事实质量失败，不能扩大规模。 |
| 11 | 压缩后继续工作：哪些事实、承诺、未完成事项和原文位置应保留，如何重新定位？ | N10 | 读取与隔离 | 原件与来源修订可回查；摘要会污染事实，压缩后整体准确性未通过。 |
| 12 | 完成工具流程：如何检查、读取、执行与核对，并区分待批准、部分成功、成功和失败？ | N02、N06、N07、G02、G03、G05 | 卡片创建修改 | 真实创建、切身份、启动、存档已走通；曾错误复述旧失败，修正文案后的完整旅程待验。 |
| 13 | 防止版本误写：如何区分原卡、本局、复制、平行版本，并处理其他对话造成的修改冲突？ | R07、R09 | 引用与版本 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 14 | 正确理解关联：增删、复制和导出如何遵守引用用途、依赖与循环边界，不误删或误接同名卡？ | N04 | 复制与分身 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 15 | 区分展示与保存：面板、按钮、文字、图片中哪些只是显示，哪些已成为可恢复成果？ | N09、G02、G04 | 回合与存档 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 16 | 准确反馈与恢复：失败、能力缺失或中断时，如何说明实际状态、保留成果并继续工作？ | N03、N04、N09、G04、G06 | 状态与身份恢复 | 成果地址丢失已修并回归；最新模型仍误报旧失败，修正后的自然续作待验。 |
| 17 | 自然交流：无挂载时如何正常聊天、解释与帮助，不把所有话题导向建卡或管理？ | N01 | 教学接入 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 18 | 引导使用：如何围绕用户目标介绍软件能力，避免背功能清单和要求用户理解技术字段？ | N05 | 教学接入 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 19 | 选择交付形式：如何判断讨论、草稿、文件或原生卡片，并在明确后直接执行？ | N01、N02 | 卡片创建修改 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 20 | 处理杂乱输入：如何为混合文本、笔记、重复版本与图片建立可靠目录并保留来源？ | 未覆盖 | 原件与交换 | 本机原文导入不需模型，重新整理沿用原卡有检查；混合资料的模型分类待验。 |
| 21 | 合理拆分三卡：如何识别世界事实、人物设定、玩法规则与普通模块？ | N02 | 卡片创建修改 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 22 | 整理多个作品：如何分类与支持多重归属，避免因共同题材、同名或相似设定误合并？ | 未覆盖 | 目录与创作库 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 23 | 处理矛盾资料：如何区分作者修改、人生阶段、平行路线与真正冲突？ | N08 | 引用与版本 | 原 11 题仍把有限定的事实写成绝对结论；未通过。 |
| 24 | 进行共创：如何补足内容、保留核心设想，并区分原设定、建议与新写入内容？ | 未覆盖 | 卡片创建修改 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 25 | 批量修改：如何定位受影响对象、落实有限范围的修改并检查遗漏？ | N06、N07 | 卡片创建修改 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 26 | 处理复制与分身：如何复制、批量选择配套人物分身，并说明复用和独立的部分？ | N04 | 复制与分身 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 27 | 衔接文件与卡片：整理成果如何入卡，卡片如何形成文件，不同步时如何处理？ | 未覆盖 | 原件与交换 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 28 | 管理长期任务：如何保留进度、待处理问题和交付记录，不依赖用户不断催问纠正？ | N10 | 阅读覆盖 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 29 | 正确启动：固定身份、自建玩家、先共创世界和自由沙盒如何分别开局？ | N05 | 状态与身份恢复 | 当前中文旅程正式角色／主持身份与玩家原句可保存；开场语义仍未通过。 |
| 30 | 理解本局采用内容：如何分清启用的世界、规则、人物和玩家身份，以及仅供管理的文游？ | N05、G09 | 对象与采用 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 31 | 维护场景连续性：如何保持时间、地点、在场人物、行动先后和物品流转一致？ | G01、R06 | 回合与存档 | 真实存档出现暮色变清晨、人物与物品增写；未通过。 |
| 32 | 尊重玩家自主性：如何描写环境与后果，不替玩家决定动机、台词、感情和关键行动？ | G01、G07 | 无相应可替代语义验收的软件断言 | 多轮候选仍替玩家触摸或移动；未通过，不将模型说“尊重自主”算验收。 |
| 33 | 裁定自由行动：按钮之外的行为如何依据世界与玩法处理，而非强行回到预设选项？ | 未覆盖 | 无相应可替代语义验收的软件断言 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 34 | 一致地使用规则：何时检定、付出代价或改变数值，如何避免临时捏造规则与难度？ | G08、G10 | 回合与存档 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 35 | 演绎多个角色：如何保持不同人物的知识、目标、表达和关系？ | 未覆盖 | 读取与隔离 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 36 | 让世界合理变化：如何区分有依据的事件、可能性和为了推进而凭空制造的突发事件？ | G01、G08 | 无相应可替代语义验收的软件断言 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 37 | 掌握节奏：如何支持探索、闲聊、日常与主线，不让每轮都成为冲突或重大转折？ | G01 | 无相应可替代语义验收的软件断言 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 38 | 使用交互工具：何时提供按钮和面板，如何保证查看不推进剧情、行动才产生回合？ | G02、G03、G04、G07 | 回合与存档 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 39 | 更新与保存状态：哪些变化需记录、何时保存、失败如何补救、叙述如何与存档一致？ | G05、G06、G07、G08、G10、R03 | 回合与存档 | 软件保存与列表回读通过；模型叙事增写未过。名称单参数保存正在编译，不能提前计入通过。 |
| 40 | 处理场外修改与恢复：纠正、改规则、回退、恢复和结束时，如何处理影响与版本？ | G09 | 状态与身份恢复 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 41 | 建立人物理解：如何由经历、欲望、恐惧、价值判断和行为例子理解人物，而非套用标签与口癖？ | R01、R10 | 无相应可替代语义验收的软件断言 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 42 | 确定当前版本：如何认清人生阶段与世界分身，避免混用其他版本的经历和关系？ | R02、R09 | 引用与版本 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 43 | 适应所在世界：如何结合制度、常识和处境推演，同时保留人物特点？ | R09 | 无相应可替代语义验收的软件断言 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 44 | 隔离角色知识：读到的资料、秘密与场外说明中，哪些能成为角色实际知识？ | R02、R04、R08 | 读取与隔离 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 45 | 形成可信反应：如何结合性格、情绪、关系和风险，不机械执行单一标签？ | R01、R03、R06、R10 | 无相应可替代语义验收的软件断言 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 46 | 掌握表达方式：台词、动作、心理与旁白如何配合用户的篇幅、视角和风格偏好？ | R01、R07 | 教学接入 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 47 | 发展人物关系：如何让信任、亲密、戒备与敌意有经历支撑，不突然变化或重置？ | R01、R05 | 无相应可替代语义验收的软件断言 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 48 | 允许合理成长：如何区分有依据的人物变化与无缘由漂移，同时不把人物冻结？ | 未覆盖 | 无相应可替代语义验收的软件断言 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 49 | 处理设定空白：哪些细节可以自然补充，哪些身世、立场与关系不能凭空确定？ | G10、R06、R10 | 无相应可替代语义验收的软件断言 | 原小规模问答保留部分未知项，但有无依据的绝对断言；未通过。 |
| 50 | 吸收用户纠正：如何修正表现并保存适用范围，不要求用户持续场外教学？ | R07 | 卡片创建修改 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 51 | 表达人物自主性：如何保留自己的判断与拒绝，又不训斥用户、操纵玩家或强推剧情？ | R05、R10 | 无相应可替代语义验收的软件断言 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 52 | 兼顾软件操作：如何读取、存档、生成图片与修改卡片，又不让人物突然谈论软件内部概念？ | R03 | 回合与存档 | 旧失败说明被模型照抄到正式答复；内部术语与自然角色工作衔接仍待复验。 |
| 53 | 定义新身份：除名字与人格文字，还需要哪些职责、表达、场景与成功标准？ | 未覆盖 | 对象与采用 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 54 | 复用已有教学：新身份如何沿用共用能力并增加专门方法，避免复制出互相矛盾的说明？ | 未覆盖 | 教学接入 | 当前未取得覆盖本题完整语义的正式模型验收记录；保留待验。 |
| 55 | 处理身份切换：哪些事实、任务与授权保留，哪些表达、扮演知识和玩家配套关系需重新选择？ | 未覆盖 | 读取与隔离 | 私有内容隔离及公开成果地址保存已有测试；切身份后的完整任务续接仍在复验。 |
| 56 | 随软件更新教学：如何同步工具、示例、身份教学与验证，避免能力与说明脱节？ | 未覆盖 | 教学接入 | 第六版未启用；局部修正逐项留证。30×3 核心和三条 20 轮完整旅程尚未完成。 |

## 使用规则

先针对当前失败样例修复，再用原场景和新保留场景验证。软件测试通过不能消除本表的模型待验；未覆盖项不能用同类演示替代。后续每组结果应更新具体行并保留失败原件。图文模板、挂载关系图等已经明确延期的功能仍按主计划处理，本表不把它们偷偷加回当前实现范围。

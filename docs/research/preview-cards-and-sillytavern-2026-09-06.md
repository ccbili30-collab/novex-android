# 三类卡片与酒馆：预览版研究

研究日期：2026-09-06。结论基于项目源码、发布接口、酒馆官方文档及扩展作者资料；没有安装或点击手机、模拟器，没有调用真实模型验证游玩效果。本文是研究结论与建议，不修改已确认的产品方案。

**主要判断**

诺文已经建立了“世界提供设定、角色承载人物版本、文游组织玩法、统一对话承载使用过程”的结构。酒馆最值得学习的是让内容真正参与生成和游玩的机制：角色表达示范、设定触发、提示词编排、变量与操作、作品交换。继续发展三类卡片的组合关系有价值；目前应优先核对这些关系是否在启动、每轮生成、保存和再导入时保持一致。

**研究基线**

公开发布接口核实最新预览安装版为 `0.2.16-beta.33`（第 33 个预览版），2026-09-06 19:02:19 北京时间发布，来源提交 `49b4f07dffdf42ae941aeacfeeb9ab36d33fd256`。本次开始检查的预览源码为 `3c8b8c5`，比安装版多出独立人格快照等改动。[预览发布](https://github.com/ccbili30-collab/novex-android/releases/tag/v0.2.16-beta.33)

酒馆公开发布接口返回最新正式版为 `1.18.0`，发布日期 2026-05-03，对应提交 `51ad27fb86d39a3daca3adaa970375c9670c12df`。本次以当前官方文档研究产品机制，并用该版本世界信息源码核对预算、触发时效和卡片字段转换；不把在线文档中较新的每个选项都视为此安装版已有。[酒馆发布](https://github.com/SillyTavern/SillyTavern/releases/tag/1.18.0)

项目旧玩家模式文档、9 月 2 日三空间设计与最新实现存在时间差。本次以发布来源、当前代码及最新明确计划区分事实；例如当前根入口已有“会话、世界、角色、文游”四项，不能继续用旧三空间说明代替现状。[根界面](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/ui/sessions/NovexRootScreen.kt:135)

**项目现在具备什么**

| 对象 | 已有结构和流程 | 需要保留的区别 |
| --- | --- | --- |
| 世界卡 | 概述、媒体，按需添加时间线、事件、地图、地区、势力、种族和自定义模块；关联具体角色版本 | 世界是共享内容对象；单次剧情事实属于具体对话 |
| 角色卡 | 根角色下有本体和分身，各版本可独立编辑、关联多个世界；包括语录、经历、属性、装备、技能等模块 | 分身是可复用人物版本；不能直接等同修订历史或自动互通的记忆 |
| 文游卡 | 四种启动方式：固定玩家身份、自建身份、先共创世界、自由沙盒；包括开局、叙事规则、力量体系、任务、检定、结局、快捷操作等 | 文游项目与某一次游玩的状态分别保存 |

以上由[模块目录](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/data/character/ContentModuleCatalog.kt:20)、[文游数据](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/data/interactivefiction/InteractiveFictionProject.kt:12)及[项目实现证据笔记](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/docs/research/preview-card-implementation-2026-09-06.md)支撑。

三类内容已有展示、编辑、草稿预览和原生交换链路。展示和草稿预览复用模块呈现，已经存在地图、时间线、集合等显示分支；最新计划仍把特殊模块的进一步专门渲染列为后续工作。因此“存在渲染分支”与“特殊模块体验全部完成”要分开。[模块呈现](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/ui/novex/NovexContentModuleRenderer.kt:44)、[后续边界](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/docs/plans/2026-09-06-identity-card-workspace-update.md:26)

文游启动形成内容快照；本局状态和动态操作按消息分支恢复。查看型操作读取状态，行动型操作生成待行动内容。它已经有运行机制，不能把文游卡概括成一篇玩法说明；这些代码也不能证明真实模型始终正确执行规则。四种启动方式目前主要是开局引导配置，结构化状态与工具更新也不等于已经具有确定性的战斗、检定和任务结算引擎。[文游运行机制](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/novex/domain/InteractiveFictionRuntime.kt:11)

项目也已经实现上下文选择：核心常驻、名称或正文匹配、一跳关联、共享预算及超长内容摘取，并接入请求路径；消息界面显示已引用来源、词元量和部分未纳入原因。后续应完善粒度和可解释性，而不是重新规划一套已有机制。[上下文编排](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/novex/domain/NovexContextComposer.kt:92)、[实际请求路径](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:3282)、[引用记录界面](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatUserMessageUI.kt:535)

仍需明确：当前角色候选从共享库读取，文游从本局快照读取，两者更新语义并不完全一致。独立人格的最新提交主要补保存和恢复，完整选择与请求注入仍属后续；新对话三份私有空草稿、带用途跨卡引用、阶段关系管理也不能仅凭领域文档算成已发布能力。[加载器](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/novex/adapter/WorkspaceNovexContextLoader.kt:26)、[已确认后续计划](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/docs/plans/2026-09-06-identity-card-workspace-update.md:8)

**酒馆的机制如何对应我们**

| 学习对象 | 一手材料核实的能力 | 对诺文的意义（研究建议） |
| --- | --- | --- |
| Character Card（角色卡） | 描述、性格、场景、首条消息、示例对话、替代开场、专属指令；作者说明与模型输入有区别 | 角色编辑应同时帮助作者组织设定和演示角色怎样说话；单有年龄职业表不够 |
| World Info / Lorebook（世界信息／世界书） | 条目可按条件进入提示词，支持常驻、关键词、预算、关联触发和时效；可绑定不同作用域 | 世界卡保留易读的组织结构，运行时进一步精确到设定条目，避免一个命中带入整个大模块 |
| Persona（玩家身份） | 用户可以保存参与对话的身份，并绑定对话或角色 | 对应我们的玩家身份，不能混同人工智能的独立人格预设 |
| Prompt Manager（提示词管理器） | 编排提示词的顺序、角色、启用状态与插入位置 | 保持“谁回答、参考什么、遵循什么规则”的清晰来源；为高级作者提供可检查的生成配置 |
| Quick Reply（快捷回复）与 STscript（酒馆脚本） | 按钮可执行命令；脚本支持本局变量、条件、运算和交互 | 让背包、任务、检定等操作形成可复用玩法，并分别表达查看与推进 |
| 检查点与分支 | 从指定消息复制出分支或命名检查点 | 保留尝试不同剧情的能力；诺文还需保障状态与消息分支一致 |

来源：[角色设计](https://docs.sillytavern.app/usage/core-concepts/characterdesign/)、[世界信息](https://docs.sillytavern.app/usage/core-concepts/worldinfo/)、[玩家身份](https://docs.sillytavern.app/usage/core-concepts/personas/)、[提示词管理器](https://docs.sillytavern.app/usage/prompts/prompt-manager/)、[内置扩展](https://docs.sillytavern.app/extensions/)、[酒馆脚本](https://docs.sillytavern.app/usage/st-script/)、[聊天文件与检查点](https://docs.sillytavern.app/usage/core-concepts/chatfilemanagement/)。

角色卡的“开场”和“示例”值得单独重视：它们把期望的文风、长度、互动方式变成可观察的样本。酒馆区分常驻角色定义与可随预算退出的示例，这提示我们在增加创作字段时同时考虑上下文消耗。对诺文可以形成“填写设定 → 写一段示范 → 独立试聊 → 回来修改”的作者流程建议，不将试聊事实写回共享原件。[角色设计](https://docs.sillytavern.app/usage/core-concepts/characterdesign/)

对大型世界，值得借鉴的顺序是：先判断条目是否适用，再决定是否触发，最后在预算内编排。酒馆的粘附、冷却和延迟以消息数计，并且有作用域；中文不宜直接套用整词匹配默认值。诺文已有一跳关联，但更细的触发条件、持续时间和每条命中理由尚需独立核对与设计。[世界信息](https://docs.sillytavern.app/usage/core-concepts/worldinfo/)

源码核对补充：酒馆 1.18.0 的世界信息扫描读取上下文预算并应用上限，随后检查时效；角色世界书转换保留关键词、次级条件、概率、位置、递归及粘附等字段。这证明它的世界书是一套执行规则。[预算与扫描源码](https://github.com/SillyTavern/SillyTavern/blob/51ad27fb86d39a3daca3adaa970375c9670c12df/public/scripts/world-info.js#L4597)、[字段转换源码](https://github.com/SillyTavern/SillyTavern/blob/51ad27fb86d39a3daca3adaa970375c9670c12df/public/scripts/world-info.js#L5500)

**文游要同时研究酒馆本体与扩展作者**

酒馆官方文档允许把模型设为叙述者或文字冒险主持人；所以不能把“角色卡”理解成只能装一个人物。根据这种机制，可以推断它也能承载场景和主持规则，但这并不等于拥有与诺文相同的独立文游对象。[提示词官方说明](https://docs.sillytavern.app/usage/prompts/)

| 层次 | 核实范围 | 值得学习的具体点 |
| --- | --- | --- |
| 酒馆内置正则替换 | 区分作用于显示、发送给模型的内容或存储文本；有输入输出测试视图 | 显示给玩家的界面与模型需要读取的数据可以分别组织 |
| Tavern Helper（酒馆助手，第三方扩展） | 可在消息中呈现完整网页界面，操作变量、世界书、事件和生成流程 | 状态栏、可点击界面和内容交互背后需要稳定接口 |
| MVU（变量更新框架，基于酒馆助手的独立脚本） | 维护变量状态，解析模型输出中的更新并将结果用于状态呈现 | 把状态维护从反复生成整张状态栏中分离出来 |
| ST-Prompt-Template（酒馆提示词模板扩展） | 使用条件和变量在发送前动态生成提示词，并提供不同作用域的变量 | 根据当前剧情状态选用规则，比静态堆叠全部规则更有表达力 |

来源：[正则替换](https://docs.sillytavern.app/extensions/regex/)、[酒馆助手作者文档](https://n0vi028.github.io/JS-Slash-Runner-Doc/)、[变量框架作者仓库](https://github.com/MagicalAstrogy/MagVarUpdate)、[提示词模板作者仓库](https://github.com/zonde306/ST-Prompt-Template)。

这里的建议是把状态、操作和呈现做成作者可复用的能力。诺文可以优先发展已有原生状态与操作协议；是否运行第三方脚本，是单独的产品和实现选择。研究这些扩展不代表本次已安装、运行或核验其完整卡片效果。

另外，酒馆群聊具有发言顺序策略以及切换／合并角色定义的方式，值得作为以后多人互动的资料；它共享聊天历史，不能直接当作人物知识隔离方案。本项目已明确暂缓多角色独立发言，本次不改变该边界。[群聊官方说明](https://docs.sillytavern.app/usage/core-concepts/groupchats/)

**互通是当前最需要说准确的地方**

当前三类库的导入按钮走原生卡包导入器，分别读取世界、角色、文游包，先预览再写入。酒馆图片卡与结构化文本卡解析器存在于旧角色卡代码中；不能据此对新版三库宣称已经提供完整酒馆导入。[新版导入流程](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/ui/settings/NovexNativeCardImportFlow.kt:33)

旧酒馆解析器把启用的世界书条目拼成知识文本；旧导出器又把知识合成一个常驻世界书条目。这条转换路径不会保留原来的完整触发行为。它与新版原生卡包对未知模块、原始文档和引用的保留是两回事。[旧解析器](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/data/character/SillyTavernCardParser.kt:118)、[旧导出器](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/data/character/SillyTavernCardExporter.kt:31)

建议以后明确三种能力：“能读取内容”“能保留可交换字段”“能复现运行行为”。角色卡第二版规范要求未知扩展数据得到保留；第三版还有更多资源及元数据要求，识别版本标记不足以证明完整实现。[第二版规范](https://github.com/malfoyslastname/character-card-spec-v2/blob/main/spec_v2.md)、[第三版规范仓库](https://github.com/kwaroran/character-card-spec-v3)

原生卡包也不宜笼统称为全格式无损：主文档有保留机制，但清单扩展没有完整传入领域导入对象。本次没有执行全格式往返验证，具体边界见实现笔记。

建议导入预览展示：已识别内容、已启用能力、仅保留的扩展、不支持的运行依赖。这样作者能知道外观、触发、变量更新分别能否工作，而不会把“导入成功”理解成“原作完整可玩”。

**对本轮更新的优先级判断（按用户完整交接重新校准）**

本轮主线是对话、回答身份、卡片、工作空间与工具之间的关系打通，私有空卡和带用途互引是核心。以下替代此前围绕单次游玩组织的优先级；具体建议见[本轮酒馆双向通道建议](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/docs/research/2026-09-06-tavern-bridge-update-proposal.md)。

1. **让实际请求采用明确的对话关系。** 接通回答身份、玩家身份、背景、管理范围、活动文游和工作空间索引；身份与工具授权独立。
2. **落实私有空卡与成果生命周期。** 创作请求有明确目标，成功写入立即保存；返回列表时按内容与保护性引用整理，保留作品及来源。
3. **完成带用途互引和一致的访问范围。** 稳定编号、模块定位、反向索引、版本快照、缺失依赖及配套玩家身份隔离应贯穿读取、搜索、摘要、工具和交换。
4. **统一产品教学、执行默认值与标准工具。** 明确创建卡片时进入原生流程，使用结构处理长文，真实报告落盘结果；状态与存档继续跟随消息分支。
5. **在上述基础中预留并逐步接通酒馆双向交换。** 现在保留原始字段、触发条件、扩展与来源映射，按可验证能力逐项转换；完整行为兼容和第三方脚本不阻塞本轮主线。作者试聊与特殊视觉呈现不能挤占空卡和引用关系工作。

作为下一轮产品讨论样例，可以组合一个“雾港”世界、一名“守门人·青年版”和一个“夜间调查”文游：地点事实属于世界，人物设定属于角色，胜负与开局属于文游，当前时间、物品与任务进展属于本局。这是本文构造的说明案例，没有写入项目卡库。

未来验证可以围绕同一个小作品：询问港口时是否读到正确条目；修改共享角色后旧局是否按预期变化；查看背包是否不推进剧情；切换分支是否恢复各自物品；再次导入是否保留触发条件与依赖。以上是建议验证项，本次没有宣称这些场景全部通过。

**证据与未覆盖范围**

项目代码链路研究见[完整实现笔记](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/docs/research/preview-card-implementation-2026-09-06.md)。本次没有重新运行项目测试，既有发布测试只作为仓库记录引用；没有将静态阅读等同手机体验验收或模型输出质量验证。酒馆研究覆盖核心文档、世界信息源码与三项相关扩展作者材料，没有逐一游玩社区成品卡，也没有比较不同模型的文风表现。

# 最新预览版三类卡片实现研究

研究日期：2026-09-06。只读检查产品源码；仅新增此研究记录。没有操作手机、运行构建或测试，也没有调用付费模型。下文“已实现”指可追踪到源码路径，不代表本次完成实机或真实模型验证。

## 版本边界

当前工作树最新提交为 `3c8b8c5`。公开最新安装包为 `v0.2.16-beta.33`（预览版 33），来源提交 `49b4f07`，由主研究任务核对发布接口。执行 `git diff --stat 49b4f07..HEAD`（比较发布来源与工作树最新提交）确认：发布后产品代码仅改回答身份的数据、编解码及设置页一个分支；三类卡片创建、原生包交换、文游运行及上下文召回文件均无变化。不能将新提交的“独立人格快照保存”说成预览版 33 已发布能力，更不能说完整人格运行时已接通。

后续身份、私有空草稿、带用途引用、玩家身份隔离、角色人生阶段等列在 `docs/plans/2026-09-06-identity-card-workspace-update.md`，属于计划或最初检查点。旧界面设计文件 `docs/design/preview-home-world-character.md` 中“帮我创作仅占位”也已落后：当前导航已接创作工作空间。

以下源码路径均相对于 `src/android/app/src/main/java/com/openminis/app/`；行号为当前工作树，三卡相关文件与发布来源相同。

## 实际产品对象与编辑闭环

| 对象 | 已存在的核心结构 | 可确认的界面与保存路径 |
| --- | --- | --- |
| 世界卡 | 名称、世界观概述、标签、媒体、可选模块；世界关联具体角色版本 | `data/character/CharacterCatalogEntities.kt:77`；`ui/navigation/AppNavigation.kt:732` 进入世界详情，`:790` 进入编辑；`novex/domain/NovexWorkspace.kt:599` 整页保存 |
| 角色卡 | 一个角色根对象，恰好一个本体，可有多个分身；每个版本拥有独立档案和模块；世界与版本是多对多关系 | `data/character/CharacterCatalogEntities.kt:21`、`:64`、`:114`；`ui/settings/CharacterCatalogScreens.kt:127` 默认本体，`:225` 版本选择；`:176` 编辑关联世界的共享版本前确认影响；`ui/settings/NovexCharacterEditorScreen.kt:124` 载入草稿；`novex/domain/NovexWorkspace.kt:808` 保存角色整页 |
| 文游卡 | 独立文游项目，名称、简介、启动方式、玩家身份、媒体、模块 | `data/interactivefiction/InteractiveFictionProject.kt:11` 四种启动方式：固定身份、自建身份、先共创世界、自由沙盒；`ui/settings/InteractiveFictionCatalogScreens.kt:285` 保存、`:295` 未保存草稿预览、`:333` 创建/编辑界面 |

角色“分身”是新的版本对象，不等于对话分支，也不是编辑历史。新分身由来源版本生成独立草稿并保存新编号；世界引用的是具体版本，编辑共享版本会影响它的关联世界。不要把规划中的人生阶段、平行分身、编辑修订三分法说成当前已实现。

三类对象共用模块目录：`data/character/ContentModuleCatalog.kt:21` 世界提供时间线、时代事件、地图、地区、势力、种族、自定义；`:31` 角色提供语录、世界经历、属性、装备、技能、外貌性格、兴趣、自定义；`:42` 文游提供玩家身份、开局、叙事规则、力量体系、属性、技能、装备、物品、任务、检定、结局、角色档案、快捷操作、自定义。`:78` 禁止重复添加同一种内置模块，自定义可重复。

这证明已有实际模块体系；不证明每类模块都有专用交互。新计划明确延期时间线、地图等特殊模块专门渲染，继续复用现有 JSON（结构化数据）及 Markdown（标记文本）能力。文游编辑页也直接说明“启动方式只决定开始时如何引导对话”（`InteractiveFictionCatalogScreens.kt:347`），不能将四种枚举解释为四套完成的游戏流程。

## 原生导入导出与酒馆兼容必须分开判断

当前世界、角色、文游库的导入入口分别在 `ui/sessions/NovexLibraryRoots.kt:126`、`:233`、`:338`，全部调用原生导入器。角色菜单实际只有“新建角色”“导入角色卡”（`:270`）。`ui/settings/NovexNativeCardImportFlow.kt:70` 读取压缩卡包、校验类型、生成预览；`:98` 用户确认后才发送导入命令。

三种原生扩展名为 `.novexworld`（世界卡包）、`.novexcharacter`（角色卡包）、`.novexgame`（文游卡包），定义在 `data/character/NovexCardPackage.kt:17`。卡包含清单、主文档及声明媒体，校验路径、媒体大小、摘要、图片头和未声明文件（`:50`）。角色详情导出的是整张角色根卡，包含本体及分身（`ui/settings/CharacterCatalogScreens.kt:201`），文游还支持全文导出和发送到普通对话（`ui/settings/InteractiveFictionCatalogScreens.kt:113`、`ui/navigation/AppNavigation.kt:920`）。

原生格式有明确保留机制：世界保存原始主文档（`novex/domain/NovexWorkspace.kt:1169`）；角色本体档案保存原始整卡（`data/character/NovexCardTransferDocument.kt:209`）；文游保存原始文档（`novex/domain/NovexWorkspace.kt:1248`），导出时以原始文档为底覆盖当前已知字段（`:1542`、`:1568`）。未知模块类型转自定义，未知呈现转不支持文档（`data/character/NovexCardTransferDocument.kt:233`、`:305`）。此机制不等于已证明所有扩展都无损；例如清单扩展虽在容器预览中保留，领域导入对象 `NovexValidatedCardImport`（已校验卡包）并不携带清单，不能把容器编解码保留注释直接当完整数据库往返保证。

旧酒馆解析器 `data/character/SillyTavernCardParser.kt:26` 支持 PNG（便携式网络图形）和 JSON（结构化数据）角色卡，字段归一化见 `:77`。它的 `:107` 确实将启用知识条目、标题和关键词拼成文本，禁用条目跳过；没有保留酒馆原始整卡或任意扩展字段。`data/character/SillyTavernCardExporter.kt:30` 将知识导出为一个常驻条目，因此该旧兼容路径不是酒馆世界书行为的无损往返。

但是该解析器仅见旧界面 `ui/settings/CharacterCardScreens.kt:102` 和 `ui/settings/Card3Screens.kt:168` 调用；现有预览库的原生导入入口不调用它。当前主导航使用新目录界面，未发现旧酒馆导入页接在主导航上。本次未遍历实机所有隐藏入口，故结论是“当前三库导入入口没有直接接酒馆解析器”，而非断言安装包任何地方都绝无酒馆入口。

## 引用、对话与本局状态

世界和角色版本的关联有独立表。模块还有显式引用；原生导入恢复卡包内部模块和角色版本编号，外部或未知引用保留为待解析数据，不按名称猜绑定（`novex/domain/NovexWorkspace.kt:1295`）。角色包导入将同包版本引用重映射到新副本（`:1229`），避免导入副本仍指向旧对象。

但当前文游项目实体、原生文游文档和运行快照没有完整“多个世界/角色加用途”的项目级引用结构：见 `data/interactivefiction/InteractiveFictionProject.kt:19`、`data/character/NovexCardTransferDocument.kt:34`、`novex/domain/InteractiveFictionRuntime.kt:13`。已有模块引用不等于计划中的背景、回答身份、玩家身份、规则、管理用途区分。

世界开对话把世界编号、角色版本编号和可选玩家身份传入草稿（`ui/navigation/AppNavigation.kt:766`）；初始化创建旧格式兼容快照（`ui/chat/ChatViewModel.kt:3907`、`data/character/CharacterConversationSnapshotFactory.kt:17`）。这条兼容快照路径要求角色版本属于所选世界。

文游详情“开始对话”有真实路由（`ui/navigation/AppNavigation.kt:913`）；初始化调用活动文游命令（`ui/chat/ChatViewModel.kt:3945`）；运行快照冻结项目内容、模块、顺序并计算内容摘要，快捷操作从模块解析（`novex/domain/InteractiveFictionRuntime.kt:13`）。共享文游后来修改不会修改该冻结快照。

本局状态是按消息分支存储的文本、数值、布尔值，不是写回共享卡。`novex/domain/InteractiveFictionRuntime.kt:59` 取活动路径最近状态；`:70` 只显示当前分支可见快捷操作；`:88` 将查看与行动分开；`:171` 解析状态更新。`novex/domain/NovexConversationConfiguration.kt:210` 要求活动文游才能写状态，`:226` 支持分支复制。工具已接至对话模型：`ui/chat/ChatViewModel.kt:8897` 分发状态更新，`:9400` 应用并持久化。不能据此宣称检定、战斗、任务结算已有确定性规则引擎；当前可确认的是结构化状态及模型工具更新基础。

## 模型实际读取哪些卡片内容

项目已经有按需上下文系统，不能简单概括为“整张卡塞进提示词”。

`novex/adapter/WorkspaceNovexContextLoader.kt:26` 只加载显式背景、回答角色和活动文游，管理对象不会自动成为背景。世界概述与角色基础资料常驻；模块转正文、别名及关联来源。文游核心、叙事规则、玩家身份常驻，其余模块参加召回（`:119`）。

`novex/domain/NovexContextComposer.kt:92` 按查询中的别名和正文词项匹配，`:108` 补一跳关联，`:112` 排序常驻/直接命中/关联命中，`:139` 在共享词元预算内选全文或完整段落，并记载超预算省略。它已有召回与解释基础，但不是酒馆世界书的完整触发器体系。

`ui/chat/ChatViewModel.kt:3282` 实际请求加载候选，`:3304` 加入当前分支本局状态；本轮工具循环复用同一请求上下文。限制也明确：世界及角色候选仍从共享工作空间实时读取（`WorkspaceNovexContextLoader.kt:37`、`:55`），活动文游读的是冻结快照（`:86`）。旧兼容角色快照存在，不等于所有新增上下文路径都已实现会话级冻结。

工作树新增独立人格的数据可保存，但当前加载器只识别回答身份中的角色版本，实际请求仅为默认 Nova（诺娃助手）追加人格指令（`ChatViewModel.kt:3293`）；尚不能把独立人格数据持久化说成角色切换与模型人格运行链已经完成。

## 可供主研究采用的结论

项目已经形成“世界资料库—角色版本库—文游规则卡—对话本局”的分层，有实际编辑、原生交换、活动快照、分支状态和按需上下文。最值得向酒馆学习的不是照搬卡片表单，而是卡片数据如何精确进入模型、身份与背景的绑定规则、成熟交换格式的语义兼容，以及玩家可理解的触发调试反馈。

比较时必须保留三条边界：最新已发布版与下一版计划分开；新版原生三卡入口与旧酒馆兼容代码分开；源码链路可确认与实机/真实模型效果未验证分开。

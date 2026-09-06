# Novex 修复前问题盘点 · 2026-09-06

本轮产物是问题清单与证据，不是修复完成报告。按用户要求暂停实现与发布，仅检查源码、既有记录与日志，并运行已有只读静态探针；没有调用真实模型、没有处理用户原始长文、没有操作移动设备。

## 基线与统计口径

- 当前预览工作树：`/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview`；分支 `next`（下一预览版）；当前提交 `129580c`。
- 用户崩溃日志明确标识版本 `0.2.12-beta.32 / 2120032`，设备为 vivo V2463A，Android 16（安卓 16）。当前本地已合入正式版 0.2.15 的改动，不能把本地代码状态当作用户已安装结果。
- 已完整读取项目 CONTEXT.md（领域上下文）；该工作树没有 AGENTS.md（项目代理说明），遵循用户提供的全局说明。
- 本清单登记 **18 个开放问题项**，包含缺陷、执行协议缺口和待复现反馈；不是“18 个都已在真机复现”。另列 **4 个已有代码修复、待用户复验的历史项**，不重复计入开放项。
- 两份崩溃日志是同一类前台服务超时，计一项。
- “源码确认”证明当前实现/契约存在该行为或限制；“用户反馈”证明用户遭遇了现象，但未必证明具体根因。本轮没有把模型自述当作真实工具执行轨迹。
- 用户本轮要求先统计；后续修复范围需据此确认，不能自动沿用上一答复并直接发布。

| 范围 | 开放项数 |
| --- | ---: |
| 产品理解与创作流程 | 4 |
| 工具契约与内容完整性 | 4 |
| 文档通读、定位与预算 | 7 |
| 外观与渲染 | 2 |
| 崩溃 | 1 |
| 合计 | 18 |

优先级：阻塞＝应用不能稳定使用；高＝核心任务不能正确完成、内容不可见或反复中断；中＝恢复、效率或一致性明显受损。

## 开放问题清单

| 编号 | 优先级 | 用户可见问题与当前证据 | 修复后的验收要求 |
| --- | --- | --- | --- |
| A01 | 高 | 明确说“创建世界卡／角色卡／文游卡”，仍被问要文字还是应用内对象。用户已反馈；系统说明有修改工具顺序，但缺少明确的产品意图映射、结果位置与完成标准。本轮未重放该模型请求。 | 明确卡片创建请求默认产生相应应用内内容；只有用户说“只写草稿／不要保存”才只输出文本。不能再要求用户解释这些卡是什么。 |
| A02 | 高 | 来源已有模块结构，仍被问是否模块化，或倾向把全文放进单模块。用户已反馈；当前通用协议没有章节/条目到共享内容模块的组织规则。 | 读取来源结构后主动保留和组织模块；超长模块按语义细分，未覆盖类型可用自定义模块；只就真正的内容歧义提问。不能把每行文字机械做成一个模块。 |
| A03 | 高 | 某些角色身份对话会失去管理、工作区、学习及文游工具。源码确认：旧角色白名单过滤工具；角色提示词分支提前返回，绕开通用工具流程说明。 | 回答人格与工具权限分开；具备相同挂载授权的 Nova 与角色身份都能完成相同管理任务，权限仍由明确授权控制。旧会话迁移也须覆盖。 |
| A04 | 高 | 创建世界/文游外壳和填充模块是分开的计划，容易停在空壳。源码确认：创建参数不含初始模块，没有计划内新对象引用；添加模块要已存在的对象编号；一次变更上限 20 项。 | 一次创作任务能覆盖建对象、模块、排序、引用与回读；可分批执行，但必须持续报告整体完成度，失败不能声称整卡已完成。具体合并提案方式待修复设计。 |
| T01 | 高 | 工具没有公布完整模块正文格式；合法 JSON（结构化数据）不一定能被页面解读。源码确认：管理层仅检查正文是 JSON 对象；渲染器要求特定结构。另有集合条目长描述未进入纯文本投影的缺口。 | 工具、保存、展示、编辑、上下文和导出复用同一内容契约；模型可查询各类型字段与最小示例；写入前验证可解释性，回读不能悄悄丢掉正文。未知内容保留并明确提示。 |
| T02 | 高 | 按说明只改模块名称或正文会失败。源码确认：说明把名称/正文标为可选，解码器却对两项都使用必填读取。 | 文档与实际参数一致；支持的局部修改保留未修改字段，不能要求模型重新拼完整内容。 |
| T03 | 中 | 文游启动方式仍要猜内部枚举。源码确认：创建工具暴露 launch_mode（启动方式）但不列合法值；解析直接使用内部枚举转换。 | 提供稳定合法值、中文含义和示例；非法值返回可执行纠正信息，不泄漏混淆后的内部类名。 |
| T04 | 高 | 多轮沟通中已有明确创建意图，后续“按刚才方案做”可能被判无授权。源码确认：创建意图只检查最新真实用户消息中的少量固定动词。 | 在任务范围内保留明确创建意图；执行确认继续来自真实用户，不能放宽成由模型自行宣称授权。区分意图继承与最终写入确认。 |
| D01 | 高 | 文档小块过多，默认每次只读 20 块；学习执行器也默认每批 20 块。代码确认；用户报告 1,154 块。 | 原始锚点可保持细粒度，但模型阅读页按字符/词元与语义连续性合并；小段落不能导致大量模型往返。 |
| D02 | 高 | 用户可见清楚章节，工具目录却为空。反馈成立；源码只识别受支持的标题样式/结构，兼容文本仅识别井号标题，未提供一般章节索引回退。原始文件未在本轮提供，具体解析路径待核实。 | 有正式标题则保留；视觉标题未编码成样式时提供带来源与可信度的推断索引。无目录也可完整阅读。 |
| D03 | 中 | AI（人工智能）手工拼游标、同时传游标与搜索词后陷入错误恢复。互斥校验本身正确；公开说明缺少明确互斥/原样续读要求，错误中的合法值却返回工具名称。 | 续读不要求模型猜内部游标；参数互斥可理解；错误指向具体冲突参数并给出可恢复下一步。不得为容错而静默丢弃查询条件。 |
| D04 | 中 | 已知大致阅读位置，却没有“从第 N 块开始”的入口。源码确认有稳定块编号、标题、关键词、部分格式页码及游标定位，但没有顺序编号范围。 | 返回可理解的位置/进度，并支持有效的中段定位与恢复；不依赖用户复制内部编码。 |
| D05 | 高 | 预检说一轮、1—4 分钟，执行却可能需要数十轮。源码确认：预检按总估算词元除以 32,000；真实执行又受每批 20 块限制，且最后还有综合整理。 | 预检与执行共用实际分批计划，明确阅读批次、综合整理和预估区间；不能用一套估算承诺另一套执行。 |
| D06 | 高 | “7,118 词元”看似精确但只是字符粗估，且预检和执行预算口径严重不同。源码确认：预检约字符数/3；执行保守预留约字符数×4＋每批 2,048，并另留输出。 | 标注估算方法/不确定性，兼顾中文和当前模型；预算预检与实际保留空间一致，不能因估算落差在开工后反复暂停。未拿到原文和真实模型计数，不能判断该文档实际只有七千词元。 |
| D07 | 高 | 真正超长的单个内容块仍没有可靠二次拆分；所有整理笔记又一次性交给最终综合步骤。源码确认：分批函数只在已有批次非空时换批，并不拆超长单块。 | 长条目能按语义继续分块，笔记综合也有分层与预算边界，保留全部来源覆盖，不因超长条目卡死或把检索当通读。 |
| U01 | 高 | 切到 Cloude（用户命名色系）后原主题恢复不了，大部分外观项似乎不生效。用户反馈；读取已知预设编号会取内置值，但正常编辑会改为自定义编号，因此这不足以解释锁死。 | 全部预设可来回切换；重启保持选择；自定义不被强制覆盖；取消不保存；恢复默认同时恢复色彩与字体。先复现完整链路再确认根因。 |
| U02 | 中 | 共享模块渲染仍绕过应用字号度量。源码确认：模块渲染器有 14 处直接固定 sp（缩放像素）字号，没有使用应用字号缩放入口；系统字号缩放与应用自设缩放是两回事。 | 世界、角色、文游模块随应用字号设置一致变化，长文与窄屏保持可读；新组件名称本身不能算统一完成。 |
| S01 | 阻塞 | 真实设备重复闪退。两份日志均为前台服务未及时进入前台的系统异常。源码中服务建通知前仍执行初始化，但没有时间追踪能证明具体耗时根因。 | 覆盖正常启动、进程恢复、安全模式、停止竞态及初始化失败；及时满足前台契约，保留真实设备复验。不能称为“主题导致闪退”。 |

## 关键证据与判断边界

### 1. AI（人工智能）对产品的理解不是只有工具名称

[通用系统说明](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/agent/NovexSystemPrompt.kt:111) 告知检查、提案、确认与应用的顺序，但没有明确“创建卡片”到应用内对象的默认映射，也没有来源模块化组织规则。

[角色工具过滤入口](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1005) 与 [角色白名单及独立提示词](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/data/character/CharacterPromptComposer.kt:62) 仍保留角色对话与通用工具能力相互绑定的旧逻辑。[角色提示词提前返回](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10380) 是可见的协议分叉。

因此修复需要同时检查：产品规则、当轮实际提供的工具、人格分支、工作流程和输出验证。单独补一句“你要主动”不能证明这些问题已解决。没有抓到这次实际模型请求之前，不能断言某一句提示词就是此次反问的唯一根因。

### 2. 工具成功与内容正确展示不是一回事

[管理工具说明](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/tools/NovexManagementTools.kt:43) 把正文与资料声明为 JSON（结构化数据），但未公布内部内容文档格式。

[内容解码器](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/data/character/ContentModuleDocument.kt:108) 识别 article（文章）、single_image（单图）、timeline（时间线）、collection（集合）等结构；未知普通对象仅尝试取 text（文本）字段。例如已有服务测试使用的 `{"caption":"北境"}` 可以通过管理层 JSON 检查，但地图正文回退只取 text，无法得到该说明。

[现有管理服务测试](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/test/java/com/openminis/app/novex/domain/NovexManagementServiceTest.kt:130) 断言事务次数与命令数量，并没有让这份内容进入实际展示转换。[集合纯文本投影](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/data/character/ContentModuleDocument.kt:225) 对集合只取名称/摘要，没有纳入条目 description（详细描述）；[上下文读取适配器](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/novex/adapter/WorkspaceNovexContextLoader.kt:102) 使用该投影，详细资料可能在进入上下文前就缺失。原始 JSON 仍保存，不等于原文件已删除，但可见与可用内容不完整。

[变更解码器](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/novex/domain/NovexManagement.kt:607) 对 update_module（修改模块）必读名称和正文；同文件创建文游对启动模式使用内部枚举，证实说明/实现不一致。

[创建后挂载](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8997) 已实现，不能再登记成“创建后完全没有挂载”。缺口在完整创作任务的组合与验收，不在该挂载动作不存在。

### 3. 长文问题需要纠正上一轮答复

[文档读取参数](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/novex-core/src/main/kotlin/com/openminis/app/novex/domain/NovexDocumentTools.kt:176)：默认 20 块、12,000 字符；可配置到 100 块、48,000 字符。若有 1,154 个可读块，默认块数限制至少需要 58 次；把上限改成 100 时块数理论下限为 12 次，仍需考虑字符截断。这不是一次最多只能读 20 块的硬限制。

[学习执行器](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/novex-core/src/main/kotlin/com/openminis/app/novex/domain/NovexLearningReviewRunner.kt:45) 同样默认每批 20 块，[应用调用处](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:12556) 没有覆盖该参数。**上一轮“直接走完整批量学习就能解决”的建议没有充分核对执行器，不能作为已可用方案。**

[学习预检](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/novex-core/src/main/kotlin/com/openminis/app/novex/domain/NovexLearningPreflight.kt:143) 与 [应用估算口径](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:12330) 证明预估与执行不是同一套分批/预算规则。原文尚未取得，不把 7,118 当作真实模型词元用量。

[兼容文本标题解析](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/novex-core/src/main/kotlin/com/openminis/app/novex/domain/NovexDocumentSnapshotPipeline.kt:138) 与 [新版 Word 段落解析](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/novex-core/src/main/kotlin/com/openminis/app/novex/domain/NovexDocxStreamingParser.kt:297) 表明“视觉上是标题”并不一定被结构解析器识别。

### 4. 主题与崩溃应分别定位

[主题偏好解码](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/ui/theme/AppThemeColors.kt:227) 会把已知预设编号规范化为当前内置配色；[主题编辑页](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/ui/settings/ThemeColorScreen.kt:69) 使用独立草稿，保存时才写入。当前证据不足以认定“预设规范化”就是无法切回的原因；上一轮将它视为主要根因的措辞过早。

[共享渲染器](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/ui/novex/NovexContentModuleRenderer.kt:135) 的固定字号则是可静态确认的统一设置缺口。

[崩溃日志](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/tmp/theme-bug-logs-20260906/crash-2026-09-06_08-40-00.log:8) 的异常为 `ForegroundServiceDidNotStartInTimeException`（前台服务未及时启动异常）；[服务启动顺序](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/service/AgentForegroundService.kt:124) 提供进一步定位入口。本轮仅确认异常类型，未证实服务启动为何超时。

## 已有改动、尚待用户复验的 4 项

| 编号 | 历史问题 | 当前检查结果 |
| --- | --- | --- |
| H01 | 对话左上角返回无效 | 只读导航契约探针通过；不能代替用户实际点击。 |
| H02 | 旧对话右上角菜单跳转无效 | 菜单导航契约探针通过；新旧会话仍应实测。 |
| H03 | 公告“检查更新／关闭”动作布局错误 | 公告动作契约探针通过；宽度、长文字与重叠仍需用户实测。 |
| H04 | 模块类型错误不告知合法值 | 当前检查工具返回类型目录，解码错误列出稳定合法值，已有回归测试。完整正文格式仍缺失，归 T01；文游启动枚举问题归 T03。 |

本轮实际执行的命令：

```sh
bash tmp/novex-acceptance/cp9-diagnostic/check_chat_navigation_contract.sh
bash tmp/novex-acceptance/cp9-diagnostic/check_announcement_actions_contract.sh
```

输出为对话返回通过、菜单导航通过、公告双动作契约通过。探针只是源码结构检查，不证明运行时布局或生命周期问题完全消失。

更早的首页搜索卡顿、首帧黑点、深层链接返回与角色视觉差异保留在历史验收报告里；未经当前版本重测，不据旧候选直接宣称仍然存在或已经解决。

## 不混作已实现功能的能力缺口

- 旧 `.doc`（旧版 Word）文件在当前标准解析入口明确不支持，要求转换 `.docx`（新版 Word）。这不是“所有 Word 文档都已支持”。证据：[解析入口](/Users/noven/Documents/Codex/CodexProduct/1/Novex-Development/worktrees/preview/src/android/app/src/main/java/com/openminis/app/data/attachments/NovexDocumentSnapshotExtractor.kt:72)。
- 原始长文、图片、空模块、复杂表格、未知模块在真实设备上的压力测试仍不完整。
- 曾有只验证工具名称/参数字符串的测试，但没有“用户自然语言请求 → 读取覆盖 → 自动组织模块 → 写入 → 页面可见 → 再导出”的整链验收；模型行为还需要实际工具轨迹与结果检查。后续不能用关键词出现次数当成模型理解正确。
- 本轮没有确认丢失用户原始文件，也没有实际模型费用证据；不作相应断言。

## 本轮需要固定的产品行为

用户明确说“把这些材料做成世界卡、角色卡或文游卡”时，正常执行应是：

1. 识别应用内目标和已有授权；材料仅要求整理时，保持为资料整理任务。
2. 检查附件结构、读取覆盖与预算，少量直接读，大量走已经说明范围/代价并确认的整理流程。
3. 沿用有意义的章节和条目，形成可编辑模块、顺序和引用；只就目标含糊或会改变内容含义的问题询问。
4. 生成可检查的内容变更计划，按既有真实用户确认规则保存；技术分页、合法枚举与正文格式由工具契约处理。
5. 回读验证标题、模块、正文、图片引用与顺序，并提供打开成品的入口。局部失败明确列出，不把外壳当成成品。

只有“需要采用哪段互相冲突的设定”“要改动哪个同名既有对象”等问题值得用户决定；“要不要用本软件内部环境”“原文已经模块化还要不要分模块”不应成为例行反问。

后续范围建议：先保障应用不崩溃与外观可恢复，同时按 A01—D07 修通内容创作的完整路径；结构化渲染与工具正文必须一起验收。此处是范围建议，本轮没有实施修复、构建、推送或发布。


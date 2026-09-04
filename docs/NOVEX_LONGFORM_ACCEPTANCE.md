# Novex 长篇创作压力验收

## 结论边界

本文件把“实现已具备”与“真实内测已经证明”分开记录。

- 自动测试可以证明数据关系、预算、检索、分支恢复、归档往返和引用保护；
- 自动测试不能证明某个真实模型在长篇小说中的文风、事实保持、实际账单或网络延迟；
- 模拟器也不能代替 360 / 412 密度无关像素真机上的软键盘、长按、图片选择和长文本滚动；
- 因此候选只有在下方外部证据完成后，才能称为“五人长文压力验收通过”。

## 五种深度使用画像

1. 世界构筑者：三个以上背景世界、百余模块、跨模块引用、一个超长历史模块；
2. 长篇作者：连续创作达到至少 200K 词元，经历多轮蒸馏，核对因果、时间线、伏笔和文风；
3. 角色扮演者：角色版本替换 Nova 回答身份，同时把其他角色仅作为背景资料；
4. 文游玩家：一个活动文游、玩家身份、状态字段、人工智能与用户快捷操作、回复分支；
5. 设定档案管理员：世界卡、角色卡、文游卡与创作成果反复导入、编辑、导出和删除引用。

这五项是可重复的测试画像，不等于五名真人。正式结论仍需五名用户各自留下真实会话证据。

## 自动化证据矩阵

| 能力 | 自动化证据 | 当前状态 |
| --- | --- | --- |
| 200K 级对话容量 | `NovexLongformPressureTest.twoHundredFiveThousandTokenConversationStillHasBoundedRoomForRelevantModulesOnAOneMillionModel` | 已覆盖 |
| 128K / 200K / 1M 模型分级 | `NovexLongformModelPolicyTest` | 已覆盖 |
| 百余结构化模块精确召回 | `NovexLongformPressureTest` 与 `NovexWorkspaceInstrumentedTest.longformWorkspaceLoadsMoreThanOneHundredModulesAcrossWorldCharacterAndGameWithoutLoadingManagedOnlyContent` | 领域测试已运行；数据库测试已编译 |
| 超长单模块语义截取 | `NovexContextComposerTest.anOversizedModuleSelectsRelevantSemanticParagraphsInsteadOfHardCuttingTheDocument` | 已覆盖 |
| 多世界背景与管理对象分离 | `NovexLongformPressureTest.novelistCanUseSeveralWorldsAsBackgroundWhileManagingTheSameSubjects` | 已覆盖 |
| 回答角色与参考角色分离 | `NovexLongformPressureTest.rolePlayerKeepsAnswerIdentitySeparateFromReferenceCharacters` | 已覆盖 |
| 文游状态跨重启、跨分支 | `NovexLongformPressureTest.gamePlayerStateAndControlsSurvivePersistenceAndForkWithoutCrossingBranches` | 已覆盖 |
| 世界卡、角色卡、文游卡往返 | `NovexWorkspaceInstrumentedTest.nativeSampleCardsPreviewImportDisplayAndReExportAsOneClosedLoop` 与 `interactiveFictionSavesImportsExportsAndCopiesInVisibleModuleOrder` | 已有数据库测试 |
| 图片共享引用保护 | `NovexWorkspaceInstrumentedTest.worldLinksAndSharedMediaRemainValidWhenOneOwnerIsDeleted` | 已有数据库测试 |
| 创作成果版本与引用保护 | `CreativeArtifactRepositoryInstrumentedTest` | 已有数据库测试 |
| 蒸馏保留合同 | `NovexCreativeDistillationPolicyTest` | 提示合同已覆盖；模型质量待真人验证 |

## 真实模型验收记录

每名内测者至少提交一条长篇会话记录，并填写以下内容；空值不能用推测补齐。

| 字段 | 要求 |
| --- | --- |
| 用户编号 | 匿名编号即可，例如 U1 |
| 使用画像 | 上述五种之一，可重复但整体必须覆盖五种 |
| 模型与提供方 | 保存实际模型编号与服务来源 |
| 有效上下文窗口 | 记录应用显示值与提供方声明值；二者不一致时标红 |
| 输入、输出和缓存词元 | 取自每轮接口返回，不用字符数冒充 |
| 费用 | 以提供方账单为最终证据；目录价计算只能标为估算 |
| 延迟 | 首字延迟、整轮耗时和工具回合耗时分别记录 |
| 蒸馏点 | 每次蒸馏前后词元数、摘要编号和活动消息分支 |
| 连续性核对 | 世界规则、人物关系、时间线、伏笔、当前场景、文游状态逐项核对 |
| 降级行为 | 低于 200K 时是否明确提示并缩小召回；不得静默假装达到目标 |

模型能力判定规则：

- 未取得上下文上限：显示“能力未确认”，不得宣传已支持长篇目标；
- 小于 200K：进入降级模式，缩小结构化资料召回并更早蒸馏；
- 200K 至 1M 以下：达到本轮最低目标；
- 1M 及以上：标为扩展长篇，但仍按模块检索，不能全量发送无关资料。

## 真机交互验收

由用户或独立验收线执行，主线不重复手动点击：

- 360 和 412 密度无关像素宽度分别检查世界、角色、文游、对话编辑和创作成果；
- 软键盘出现时，输入框、保存、取消和系统返回顺序正确；
- 百余模块长列表可滚动、定位、展开、排序，不横向裁切；
- 超长文本编辑不漏字，不因每个字符触发整页重组；
- 世界封面、地图单图、角色头像、成果图片可打开、替换与移除；
- 切换消息分支不重复执行工具，不把另一分支文游状态带入当前路径；
- 应用强制停止后恢复活动分支、文游状态、快捷操作和内容挂载关系。

## 通过标准

自动测试全部通过只是进入五人内测的门槛。最终通过还要求：

1. 五名真实用户的记录齐全；
2. 至少一条真实会话达到 200K 词元级并经历两次以上蒸馏；
3. 没有确认事实、人物关系、时间线、伏笔或文游状态跨分支串线；
4. 费用与延迟有真实数据，不以模型目录宣传值代替；
5. 360 / 412 宽度和软键盘真机检查无阻塞问题。

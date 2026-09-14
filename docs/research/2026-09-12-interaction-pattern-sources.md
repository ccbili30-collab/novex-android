# 基础交互范例：来源核对

日期：2026-09-12。范围：研究现成设计规范、效果图与开源实现；不修改产品代码，不代表已选定或完成迁移。

## 结论

基础交互应以明确的状态图、视觉规格和组件行为为依据。文字原则只能辅助检查，不能替代可对照的范例。建议以安卓原生设计和实现为主基准，AI（人工智能）对话的特殊状态另选专门范例；不是把多个设计系统的外观拼起来。

## 已核对一手来源

| 对象 | 可直接查看的材料 | 适用范围与限制 |
| --- | --- | --- |
| 三点菜单 | [安卓菜单效果图、源码及开关行为](https://developer.android.com/develop/ui/compose/components/menu)；[对应固定提交源码](https://github.com/android/snippets/blob/70cc63249a4bb73f782bed92f3340af744c9c5d4/compose/snippets/src/main/java/com/example/compose/snippets/components/Menus.kt) | 官方直接给出两项菜单、长菜单、分组菜单；业务操作内容由产品确定。 |
| 确认弹窗 | [安卓对话框示例](https://developer.android.com/develop/ui/compose/components/dialog) | 可复用对话框结构、确认与取消回调；不能自动确定删除影响范围或草稿保存事务。 |
| 页面返回 | [安卓预测性返回设计](https://developer.android.com/design/ui/mobile/guides/patterns/predictive-back) | 有视频、取消行为、缩放和动画参数；按对应导航实现选择适用示例，不盲目复制自定义动画参数。 |
| 手机对话 | [Jetchat（官方聊天示例）](https://github.com/android/compose-samples/tree/main/Jetchat) | 有截图目录、返回拦截、表情面板与键盘切换、状态恢复与测试；仓库声明仍有未实现功能与已知问题，不是完整AI运行系统。 |
| 原生组件演示 | [Fluent UI Android（微软安卓界面组件库）](https://github.com/microsoft/fluentui-android) | 提供菜单、列表、对话框等组件和演示应用；可以比较范例，不据此要求更换Novex视觉体系。 |
| 旧材料组件库 | [Material Components Android（安卓材料组件）](https://github.com/material-components/material-components-android) | 当前仓库明确进入维护模式；新方案优先查Compose（声明式界面）实现，不因仓库知名就照搬旧技术。 |

## AI（人工智能）对话具体范例

已核对 [assistant-ui（智能助手界面库）源码仓库](https://github.com/assistant-ui/assistant-ui)与其官方组件页：

- [工具分组](https://www.assistant-ui.com/elements/tool-group)：具体演示与组合代码，连续工具调用分组，消息正文单独呈现。
- [默认工具展示](https://www.assistant-ui.com/elements/tool-fallback)：可展开摘要，以及运行、结果、错误、审批状态。
- [错误状态](https://www.assistant-ui.com/elements/error-state)：消息内失败与重试范例；实际运行状态接入仍需实现。

这部分是网页React（界面框架）范例，不是安卓原生组件，也不证明手机返回和键盘行为。适合参考状态与信息呈现，不应整体替换现有应用。

## 本地规范核对

读取了 `docs/NOVEX_MOBILE_DESIGN_SYSTEM.md`：有基础尺寸、返回、草稿及菜单规则，但仍写有独立文游入口、旧分身与关联删除规则，和后续用户决定冲突。因此“已有文字规范”不等于“存在有效且贯彻的设计基准”。该文件此次未修改，过时条款不得恢复为当前产品要求。

## 建议的后续产物（尚未执行）

每个基础场景应有：来源范例、采用的状态序列、具体视觉图、交互结果及错误/返回分支、对应共用组件。基础场景包括列表到详情、编辑与未保存退出、三点菜单、删除与恢复、模块展开和拖动、对话发送/停止/工具结果/失败。

已确定的Novex业务边界继续有效：预览不保存、使用不授予管理权限、人工与AI共享真实内容、世界和角色共用模块底座。通用组件不替产品决定这些语义。

## 证据边界

本轮阅读一手文档、仓库说明和示例源码页面；没有编译或运行外部示例，没有完成手机实测，也没有声称现有Novex实现已符合这些规则。

## 后续追问：减少口头描述和整包验收依赖

用户追问是否必须原生安卓，要求更轻便的界面设计办法。以下为方案研究，不是迁移决定：

- [Penpot（开源界面设计工具）](https://penpot.app/features)：在共享画布中组织组件、变体及页面，连线建立跳转、覆盖层和返回，可直接播放原型。它的设计检查及样式代码导出，不等于完整安卓业务实现。
- [Compose（安卓声明式界面）交互预览](https://developer.android.com/develop/ui/compose/tooling/previews)：支持组件预览、交互模式和多种屏幕/主题状态；预览不能替代手机键盘与完整业务验证。
- [Capacitor（网页应用原生容器）](https://capacitorjs.com/docs)：可用网页技术实现界面，再接安卓原生能力；原有存储与运行核心需要明确适配，不能把网页在浏览器能运行当作迁移完成。

本地只读核对：`src/android/app/build.gradle.kts` 已开启Compose（声明式界面），依赖材料设计第三版与预览工具；在 `src/android/app/src/main/java/com/openminis/app/ui` 范围搜索未找到 `@Preview`（预览定义）。这证明已查范围内缺少这类预览样本，不等于整个项目没有任何界面测试。

建议首先建立可视化设计与实际界面预览之间的对照流程；是否更换界面技术单独评估，不自动重写软件。

## 字节及其他大型团队的公开工程实例

- [Semi Design（字节设计系统）介绍](https://semi.design/zh-CN/start/introduction)明确由抖音前端和产品设计团队共同维护，设计变量可以在设计工具与工程中共同使用；[源码仓库](https://github.com/DouyinFE/semi-design)与设计资源相互配套。该系统主要针对网页中后台，不能据此描述字节所有团队或安卓客户端的内部流程。
- [Semi Design官网](https://semi.design/zh-CN)提供设计稿转代码、主题定制与质量保障相关入口；自动生成界面不等于完成业务数据接入。
- [Now in Android（谷歌安卓完整示例）](https://github.com/android/nowinandroid)在同一仓库中提供设计案例、设计文件、组件目录应用、架构说明、截图比较及其他测试。可以直接核对[设计文件](https://github.com/android/nowinandroid/blob/main/docs/Now-In-Android-Design-File.pdf)与实现之间的对应。这是公开参考项目，不是谷歌全部产品研发的内部流程记录。

基于上述材料的工程建议：用具体用户任务及结果约定连接设计、实现和验收；共享组件在设计与代码中对应；视觉差异和业务结果分别检查。产品/设计/开发/测试可由少数人承担，但职责不能因人少而省略。此为方法归纳，不冒充某公司统一规定。

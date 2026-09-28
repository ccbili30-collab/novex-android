# 任务书：阅读视图全局化——纯渲染模式，默认翻页

日期：2026-09-28　分支：reading-view-global　目标：next

## 背景

用户裁决（2026-09-28，验收滚动/翻页模式时）：「他应该就是一种前端渲染模式，
反正数据不会变，想要翻页还是一整页不是用户自己说了算吗？切换视图而已，
默认翻页」。

此前布局存在卡文档 appearance.readingLayout 里：切换要落盘产生修订
（PR#47 即时 commit），且编辑器的「主要容器/横向分组」体系
（PromoteModule/AddPresentedModule 的结构转换）挂在它上面。

## 决议

**阅读视图 = 全局用户偏好，纯渲染判定，不写卡数据**：

1. 新增 `ReadingViewPrefs`（novex.android 包，SharedPreferences+进程缓存，
   默认 `PAGED` 翻页）；hydrate 挂 MinisApp 冷启动。
2. `CardPresentation.rootModulesHorizontal()` 改读 ReadingViewPrefs——
   阅读页滚动/翻页、手势翻页、「主要」槽判定全部跟随全局视图；
   `usesMainSlot()` 随之（编辑器 ModuleTreeEditor 的入口显示同源）。
3. `CardSessionModel.setReadingLayout` 简化为写偏好（同步、零 IO 链、
   不产生修订）；PR#47 的 commit+begin 逻辑随本决议退役。
4. **编辑器数据组织规则保持现状**：PromoteModule/AddPresentedModule 的
   「主要容器/横向分组」转换继续按卡内 appearance.readingLayout 存量值
   工作（存量卡兼容，测试不动）——阅读渲染不再读该字段，但它仍是编辑器
   归置新模块的既有信号。
5. 存量卡字段保留：appearance.readingLayout 解析/导出不破坏，只是阅读
   渲染忽略它。
6. 编辑菜单「滚动模式/翻页模式」两项成为全局视图切换入口；阅读页内
   即时切换按钮挂账（需 UI 设计）。

## 已知取舍

- 滚动视图查看存量 PAGED 卡：「主要」容器显示为一个可折叠顶层模块
  （数据本来如此）；
- 编辑器新模块归置仍按卡内字段——与阅读视图解耦，属过渡态。

## 验收

- 编辑菜单切换滚动/翻页 → 预览即时生效，卡数据零修订；
- 全新卡默认翻页视图；设置全局生效（跨卡一致）；
- CI 绿；存量卡导入导出不受影响。

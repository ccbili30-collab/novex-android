# 任务书：阅读布局设置即时生效

日期：2026-09-28　分支：reading-layout-live　目标：next

## 背景

用户验收「滚动模式/翻页模式」（PR#46 改名后）时报告：「预览展示不显示，
只有编辑界面显示，应该都显示才对」。

根因：`CardSessionModel.setReadingLayout` 只把布局写进编辑草稿
（`applyComposition(EditorCommand.Layout(...))`），要等用户手动「保存」才落
盘；而阅读页（`CardReading`，readingScope="saved"）读的是已保存卡——点完
菜单直接去预览，设置不生效，只有编辑界面（草稿态）变了。

## 决议

布局是展示偏好，设置即落盘：`setReadingLayout` 在 apply 到草稿后立即
`CardDrafts.commit`，`saved` 同步更新（阅读页即时生效），**保留 draft 与当前
页面**——不能复用 `save()`（那会清草稿并跳回详情页，打断编辑）。

## 验收

- 编辑菜单点「滚动模式/翻页模式」→ 不退出编辑器 → 切到预览/阅读页立即按
  新布局渲染；
- 后续继续编辑与手动保存不受影响；
- CI 绿（novex.android 包无 VM 测试目录，真机验收归用户）。

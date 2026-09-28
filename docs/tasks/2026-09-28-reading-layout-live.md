# 任务书：阅读布局设置即时生效

日期：2026-09-28　分支：reading-layout-live　目标：next

## 背景

用户验收「滚动模式/翻页模式」（PR#46 改名后）时报告：「预览展示不显示，
只有编辑界面显示，应该都显示才对」。

根因：`CardSessionModel.setReadingLayout` 只把布局写进编辑草稿
（`applyComposition(EditorCommand.Layout(...))`），要等用户手动「保存」才落
盘；而阅读页（`CardReading`，readingScope="saved"）读的是已保存卡——点完
菜单直接去预览，设置不生效，只有编辑界面（草稿态）变了。

## 决议（净眼退回后修订）

布局是展示偏好，设置即落盘：`setReadingLayout` 在 apply 到草稿后立即
`CardDrafts.commit`，`saved` 同步更新（阅读页即时生效），编辑器留在原地——
不能复用 `save()`（那会清草稿并跳回详情页，打断编辑）。

净眼 S2 退回件：commit 会删除磁盘草稿文件（CardDraftsTest 钉死），保留内存
draft 会让后续 flush/保存全部报「草稿不存在」——commit 后必须
`CardDrafts.begin(cardId)` 重建草稿再入 state。连带语义（记录性）：切换布局
会发布当时草稿的全部未保存修改，且每次切换产生一个修订。

## 验收

- 编辑菜单点「滚动模式/翻页模式」→ 不退出编辑器 → 切到预览/阅读页立即按
  新布局渲染；
- 设置后继续输入、切换模块、手动保存均正常（不出现「草稿不存在」）；
- CI 绿（novex.android 包无 VM 测试目录，真机验收归用户）。

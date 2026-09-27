# 卡片采用矩阵页退役：三专属页（身份/背景/管理）

日期：2026-09-28 · 分支 task/card-usage-pages · 状态：进行中

## 用户决议（讨论两轮定稿）

1. 原"卡片采用与管理"三列矩阵页**直接砍掉**（混杂 IA 之源）
2. 回答身份**单选**；背景/管理**多选**
3. 身份页世界卡与角色卡**分组并列**，不混列
4. 背景/管理各回专属区（此前的"串"=挂卡对话五行全跳矩阵页，
   ConversationSettingsScreen.kt:286）

## 前置确认（另一会话合并走查）

另一边合入聊天转写 UI 整备（13 文件 +961/-184：助手操作行/吸底流式/
执行过程步骤化/每轮注入显示）；与本批三个文件（IntegratedCardSettings/
ConversationSettingsScreen/AppNavigation）零交集，ChatViewModel +11 行
不涉绑定。无冲突。

## 变更

- IntegratedCardSettings.kt 重写为三页（顶部页签 identity/background/
  manage，路由 `?mode=` 入口直达）：
  - 回答身份页：Nova（默认，清除主卡）/「角色 · 扮演」组（角色根+
    世界内角色）/「世界 · GM 叙述」组（世界根）；主卡缺失提示
  - 背景资料页：角色/世界/失效关联三组多选+模块携带（必带/不带/默认，
    仅主卡与背景卡展开）
  - 管理页：三组多选（纯权限）
- ConversationSettingsScreen：onCardSettings 改 (String)->Unit；挂卡
  对话 answer/background/manage 行分别直达 identity/background/manage
  页；game/images 恢复各自系统页（不再跳卡设置）；未挂卡"采用新版
  卡片"行→identity 页
- AppNavigation：路由加 ?mode={mode}（默认 identity）+ Uri 编码传参

## 不变量

- 绑定语义零改动：同一 CardBinding，显式保存+放弃确认沿用；主卡变更
  经 saveIntegratedCardBinding 触发既有激活流程（资料包/自动开场）
- UsageChoice 数据（target/name/kind/isWorldRoot/missing）替换原
  Triple；modules/overrides 逻辑原样移植

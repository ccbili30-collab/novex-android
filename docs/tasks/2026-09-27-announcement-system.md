# 公告体系：Gitee 道公告随源（hub announcements 索引）

日期：2026-09-27 · 分支 task/announcement-system · 状态：终态（已合并 → beta.84）

## 用户指令

「公告能不能我来调整」→ 能：hub 仓库写 md+索引一行+push 即生效，
无需发版。「公告也从这里获取，更新来源同更新源」（挂账项提前落地）。

## 设计

- hub 侧：仓库根 `announcements.json` 索引（新到旧，条目=file/title/
  date，file 限定 announcements/*.md）+ `announcements/` markdown 正文
  （沿用既有目录约定）
- 应用侧：`GiteeAnnouncementSource`——Gitee 道拉索引→逐条拉原文（≤5
  条，索引顺序即优先级）→ NovexBulletin(announcements, releaseNotes=[])；
  任一环节失败回落内置归档 NovexBulletinDefaults（公告是潜在收益，
  绝不阻塞）。GitHub 道公告继续走 release 正文（NovexBulletinPolicy）
  不动
- 作者工作流（AGENTS.md 同步）：新建 announcements/YYYY-MM-DD-标题.md
  → announcements.json 顶部加一行 → push（README 列表保留为人类入口）

## 变更

- 新增 `data/GiteeAnnouncementSource.kt`（纯解析 parseAnnouncementsIndex
  + rawUrl + fetchBulletin）
- `UpdateChecker.fetchBulletin(GITEE)` 由"回落内置"改为真源拉取
- 测试：GiteeAnnouncementSourceTest（顺序/路径校验防越权与穿越/标题
  必填/上限 5 截尾不掐头/非法整体 null/raw URL 拼接）
- 总任务书重建版（docs/tasks/2026-09-26-memory-system-rework.md，
  五阶段 ✅ 总账）随车归档——兑现"确认那个计划文件都✅了"
- hub 侧（本 PR 外，直推 hub 仓库）：announcements.json 建立+README
  版本表刷新（v0.2.2 旧账→v3.0.4/beta.83）+AGENTS.md 公告工作流补
  索引步骤

## 台账（终态）

- 净眼一审退回两处（索引未拒 .. 穿越段/空数组应整体 null——CI 红即其预判断言）+测试跨串逗号语法错误（两轮 CI 编译期红根因，本机无 JDK 未拦住，教训：raw 字符串拼接避免跨串标点戏法）修复后绿；终验过；守纲六问过；合并 PR #40 → beta.84；hub 侧 announcements.json/README 刷新/AGENTS.md 工作流已直推（双 forge）

# 任务书：公告面板 hero 改版（版本中心样式）

日期：2026-09-28　分支：announcement-hero　目标：next（beta.89）

## 背景

用户发参考设计稿问「公告能不能做成这样」——「版本中心」风格弹窗：hero 横幅
（大版本号+徽标+日期·渠道+标语+天空之城插画）、带符号分节标题、结构化正文、
底部「关闭+检查更新」。三条裁决：

1. 分节符号只给「更新亮点」和「优化与调整」两处，别的节标题不加；
2. hero 的版本号/时间/标语要与发布体系勾兑（不手写两份），标语核心=
   「让创作更简单」；
3. 封面图用生图重绘天空之城（干净、符合横幅尺寸）。

## 决议

- **索引扩展**：announcements.json 条目新增可选字段 `version`（有值→hero
  模式）、`badge`（徽标胶囊）、`tagline`（标语）、`cover`（横幅图，相对 hub
  根路径）。全部可选=向后兼容：旧条目旧渲染，旧 App 忽略未知字段。
- **cover 护栏**：与 file 同源——限定 `announcements/` 前缀、拒 `..` 段、
  白名单扩展名（.webp/.png/.jpg/.jpeg）。App 端在 source 层按当前道
  （Gitee/GitHub 镜像）拼完整 raw URL 存入 NovexAnnouncement。
- **勾兑**：announce.sh 发版公告时 version/date 自动从 update.json 对应
  通道带出（不手填），tagline 缺省句式以「让创作更简单」收尾；hub
  AGENTS.md 记 schema。
- **渲染**（App）：公告正文走自家 MarkdownParser 块级定制渲染，不再走
  通用 MarkdownText：
  - hero 条目：横幅（AsyncImage cover+左侧 NOVEX 小字/大版本号/徽标胶囊/
    日期·渠道/标语）→ 正文（跳过开头「**日期**/**通道**」元信息行，信息
    已上横幅）；
  - `##` 节标题：标题含「新增」→ sparkle 符号、含「改进」或「优化」→
    gear 符号（对应设计稿两处），其余节（变更与修复/升级方式）无符号；
  - Blockquote→左边框导语；BulletList→轻量卡片行（粗体标题+描述，无图标
    方块——用户裁决符号只给两处节标题）；`###` 子节纯文字；
  - 非 hero 通用公告：同块渲染、无横幅；跳脸模式同享 hero；
  - 「检查更新」按钮加刷新图标；tab 改下划线式（贴设计稿）。
- **缓存**：BulletinCache save/load 补四个新字段（旧缓存无字段→null，
  兼容）。

## 资产

- `hub/announcements/assets/3.0.5-cover.webp`（1560×619，42.7KB，flare
  经 sail 容灾生成：云上悬浮城堡居右、左侧留浅色天空给文字排版）。

## 验收

- hero 横幅在 公告面板/跳脸弹窗 正确呈现；通用公告（发布站上线）不受影响；
- 3.0.5 stable 正式版 App（旧解析）拉新索引不崩（未知字段忽略）；
- 新解析测试：四字段解析+cover 路径护栏（拒 ..、拒白名单外扩展名）；
- BulletinCache 往返保字段；CI 绿；净眼过；六问过。

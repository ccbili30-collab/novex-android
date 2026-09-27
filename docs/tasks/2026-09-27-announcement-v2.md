# 公告体系 v2：双版本互通+跳脸+通道定向+公告/更新切换+双源同源

日期：2026-09-27 · 分支 task/announcement-v2 · 状态：进行中

## 用户决议（2026-09-27 聊天定稿）

1. 「普通公告互通」——通用公告双版本（stable/preview）都可见
2. 「有新公告就跳脸让他关闭」——新公告冷启动弹窗，用户关闭即视为已读
3. 「版本公告两个独立」——通道公告（stable-only/preview-only）只给
   对应通道
4. 「平时主要管通用公告」——索引 channel 字段可选，缺省=通用（作者
   主路径零负担）
5. 「公告本身也要能切换公告/更新才行」——公告面板内公告/更新两页切换

隐含前提：**双源同源收敛**——GitHub 道公告从 release 正文切换到镜像
仓库（ccbili30-collab/novex）的 announcements.json，否则互通对 GitHub
源用户不成立。一处写作（hub），两道同源（host 不同）。

## 设计

### 索引 schema v2（向后兼容）

条目加可选 `channel`（"stable"|"preview"，缺省=通用；非法值当通用）。
通用=互通；通道条目只下发对应通道。

### 应用侧

- `GiteeAnnouncementSource`：
  - AnnouncementEntry +channel 字段；fetchBulletin 参数化 raw base
    （Gitee=hub / GitHub=镜像），同解析器两 host
  - 通道过滤 filterForChannel（null 或匹配）
  - Gitee 道 releaseNotes：顺拉 update.json 当前通道条目→单条更新说明
    （更新页内容）；GitHub 道 releaseNotes 保留 releases API 完整历史
- `NovexAnnouncement` 加 `id` 字段（默认=versionName；Gitee 道用文件名
  ——已读键）
- 新 `NovexAnnouncementReadStore`：prefs 记已读 id；未读判定纯函数
  unreadAnnouncements(announcements, readIds) 便于 JVM 测试
- `NovexUpdateMonitor`：冷启动顺拉 bulletin，StateFlow 暴露未读子集
- 跳脸：home 工具栏 NovexUpdateAction collect 未读流→非空弹
  AnnouncementDialog（未读列表）；关闭即 markRead 全部已展示项
- AnnouncementDialog：顶部公告/更新 segmented 切换（rememberSaveable）；
  公告页=最新+往期；更新页=releaseNotes（空态文案）；按钮不动

### hub 侧（直推）

- AGENTS.md 公告工作流补 channel 字段说明
- announce.sh 顺手脚本：参数 标题 [stable|preview]，生成日期 md 模板+
  索引行插入+README 行

## 不变量

- 跳脸只在成功拉到未读时发生；网络失败静默下次再试（不阻塞启动）
- 已读只在成功展示后写入；公告内容更新（同文件）不重复跳脸
- GitHub 道更新页（releases 历史）与公告页（镜像 json）数据源分离
  但同弹窗

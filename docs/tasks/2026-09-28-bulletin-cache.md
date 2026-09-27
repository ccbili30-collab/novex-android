# 公告缓存+内置归档退役（硬编码遗留清除）

日期：2026-09-28 · 分支 task/bulletin-cache · 状态：进行中

## 用户报告（2026-09-27 截图）

beta.85 公告面板显示"公告 · 0.2.14 恭喜全人类迎来 AGI 时代""特别致哀
· 0.2.9"——NovexBulletinDefaults（MinisApp 时代硬编码内置归档）作为
加载占位符顶出。用户定性：硬编码遗留；指令：**点开公告按钮第一时间
就应该看到关闭前的公告缓存，有新的再刷新（同时跳脸）**。

## 方案

1. 内置归档退役：NovexBulletinDefaults 内容清空为空哨兵（占位职责
   移交缓存；NovexAnnouncementTest 守护内容不得回流）
2. 新增 BulletinCache：filesDir/novex/bulletin-cache.json，live 拉取
   成功后落盘（手动打开与冷启动两路都写）；load 损坏/缺失/空内容
   静默 null
3. 面板打开流程：先显缓存（无缓存才显示加载条）→ 后台刷新 →
   成功覆盖+落盘；失败（空回落=!live 且两列表全空）保留缓存不覆盖；
   失败且无缓存 → 空态"暂无公告"
4. 冷启动：monitor attachContext 注入（MinisApp），成功拉取即写缓存
   ——面板打开瞬间即有新鲜内容

## 不变量

- 跳脸链路不变（活源未读→弹窗→关闭即已读）
- 失败永不覆盖缓存；空哨兵不落盘不显示

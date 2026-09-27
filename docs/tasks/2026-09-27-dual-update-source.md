# 双源更新：Gitee（默认）/GitHub 切换 + 同步推送

日期：2026-09-27 · 分支 task/dual-update-source · 状态：终态（PR #38 已合并）

## 用户指令原文

「下次我们同步推送 gitee 及 github。软件同步从他们获取，默认 gitee。
预览版正式版独立获取更新。增加选项：切换更新源。（logo）github（海外）
（logo）gitee（国内）公告也从这里获取，更新来源同更新源。公告体系后面
搞，不急」

## 走查结论（本批前提事实）

- GitHub 仓库 ccbili30-collab/novex-android **公开**（匿名 200），且
  v3.0.4 挂 `novex.apk`（stable 渠道资产名）、v3.0.5-beta.8x 挂
  `novex-preview.novex`（preview 资产名）——**GitHub 已是活的应用内
  更新源，版本线=3.0.x**。
- hub（gitee.com/ccbili/novex）update.json stable=0.2.2（09-01）为版本
  线切换前旧账；AGENTS.md 版本表（v0.2.x/v0.3.0-beta.n）同源过时——
  首次同步推送时一并校正为 3.0.x 口径。

## 应用侧变更

- 新增 `data/UpdateSourceStore.kt`：UpdateSource 枚举（GITEE 默认/
  GITHUB）+ SharedPreferences 持久化 + 进程级缓存注水（MinisApp 冷启动
  先于检查调用）
- `UpdateChecker.check(source)` 路由：Gitee 道=GET update.json raw
  （302 跟随、不缓存跳转），只读本构建通道键（stable/preview 独立），
  版本比较复用 UpdateReleasePolicy（语义化版本含 prerelease）；
  GitHub 道原样保留（含 Atom 兜底）。降级路径对齐：404→未发布、
  缺 download→NoApkAsset、坏 JSON→Error、网络异常→NetworkUnreachable
- `parseGiteeUpdate` 纯函数（JVM 可测）
- `fetchBulletin(source)`：公告随更新源；Gitee 道暂回落内置归档
  （公告体系后置，用户口径）
- UI：CheckUpdateSection 顶部更新源切换行——两枚 logo 胶囊
  （ic_gitee 红底白 G 描边图标 / ic_github 品牌黑 octocat），选中描边
  高亮，切换即清本轮检查状态；新字符串进 8 个语言包
  （zh-rTW 无锚点串，按包尾追加）

## 发布侧（合并后执行，不在本 PR）

1. 校正 hub AGENTS.md 版本表→3.0.x 口径
2. 首次同步推送：stable 3.0.4 + preview 3.0.5-beta.8x 上 Gitee
   （publish.sh；APK 取 GitHub release 资产下载后上传）
3. 之后每轮 GitHub 发版后本地跑 publish.sh 双推；双推流程写入
   RELEASE 文档（CI 内置 Gitee 推送需在 Actions 配 GITEE_TOKEN secret，
   后置）

## 不变量

- 预览/正式独立：build 烤入 UPDATE_CHANNEL，两源都只读本通道
  （Gitee=update.json 通道键；GitHub=prerelease 过滤+资产名）
- 下载渠道校验不动（matchesInstalledTrack 查 APK 元数据）
- PendingUpdateStore/安装链路零改动（源切换只影响检查，不影响在途安装）

## 台账（终态）

- 净眼七场景过（通道独立/GitHub 道逐行搬迁机械 diff 为空/注水时序/
  切换状态清理/图标资源/字符串全 locale/编译面）；非阻塞观察留痕：
  update_source_row_title 预留未引用；源切换不清 NovexUpdateMonitor
  available 角标（留存至下次刷新，无害）
- 守纲六问过（含用户指令五要素逐条：默认 gitee/切换选项 logo 胶囊/
  预览正式独立/公告随源/公告体系后置）；CI 绿；合并 PR #38 → next
  （208ac7a）；发布 v3.0.5-beta.83
- 首次同步推送完成：stable 3.0.4 + preview 3.0.5-beta.83 上 Gitee
  （publish.sh），三条验证清单全过（raw 含新版本/APK 直链 200 且
  Content-Length 与本地一致/仓库公开）；hub 仓库本体双推 gitee+github
  镜像（ccbili30-collab/novex，api 验证镜像 update.json 两通道同步）；
  AGENTS.md 版本表校正 3.0.x；publish.sh 修远端名回退（无 origin 取
  gitee——hub 仓库远端名为 gitee/github）
- 发布侧流程定型：每轮 GitHub 发版后本地 publish.sh 双推 + github
  镜像 git push；CI 内置 Gitee 推送需 Actions 配 GITEE_TOKEN secret，
  挂账后置

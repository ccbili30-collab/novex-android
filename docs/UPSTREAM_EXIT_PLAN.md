# 去上游化路线图（Upstream Exit Plan）

> 目的：把「替换上游 OpenMinis 血统代码」拆成可随时挂载在正常发版节奏里的
> 小刀，每刀独立验证、独立合并。商业采用要求最终分发物不含上游衍生代码
> （proot 是否作为独立 GPL 组件保留见「决策挂账」D1）。
>
> 本文件是活文档：每完成一勾，在「进度日志」追加日期 + PR 号 + 度量快照。

## 0. 度量工具（随时重跑）

```bash
python3 scripts/upstream_audit.py --json /tmp/audit.json
```

- 基线 = 仓库根提交（上游 OpenMinis 整包导入 `82c2eb0`，2026-08-29）
- 血统三分类 + Manifest 根出发的可达性闭包，覆盖 import / 同包符号 /
  全限定名内联调用三种引用方式
- **已知盲区（fail-open 方向保守）**：反射、按名字的 DI、纯字符串类名发现
  不出来，一律按「活」处理。删除执行前仍须编译 + 全量测试 + 冒烟兜底。
- **历史教训一**：初版工具漏了 FQN 内联调用，把 102 个活文件（含
  FullscreenImageViewer/ImageGalleryViewer 图片查看器两件套）误判为死代码。
- **历史教训二**：初版工具只扫 Kotlin 源码，漏了 META-INF/services（SPI
  服务注册）——P0 误删 ACRA 本地崩溃发送器（CrashFileSenderFactory，经
  ServiceLoader 反射实例化），单测 CI 探不到，R8 missing-class 拦截。
  工具已把 SPI 注册纳入活代码根。任何「删了没影响」的结论必须以最新工具
  + 编译 + 冒烟三重验证为准。

## 1. 基线数据（2026-09-28 @ next `dfaf1e4`）

血统三分类（Kotlin，`src/android/`）：

| 分类 | 文件 | 行数 | 处置 |
|---|---|---|---|
| 上游原样未动 | 261 | 63,725 | P3/P4 人工重写 |
| 上游改动过（混合） | 159 | 101,752 | 随 P3 逐文件甄别，重写上游部分 |
| Novex 新增（零上游血统） | 379 | 50,247 | P1 机械搬家即可 |
| **死代码候选** | **28** | **4,831** | **P0 直接删除** |

活代码重灾区（重写主战场）：ui/chat 47.4k、ui/settings 19.9k、
sandbox.offload 11.7k、novex.domain 11.0k（自有）、data.repository 7.5k、
ui.sessions 7.3k、ui.novex 6.3k（自有）、debug 5.7k。

GPL 实体（Android 分发物内）：

| 实体 | 许可 | 处置 |
|---|---|---|
| proot 全家（proot-aarch64、libproot.so、libproot-loader.so/-loader32.so） | GPL-2.0 (proot) | 独立进程 exec，**不参与代码重写**；是否保留见 D1 |
| assets/alpine-minirootfs.tar.gz 内 BusyBox | GPL-2.0 | P2 换 toybox(BSD) 自建 rootfs |
| ~~iSH（GPL-3.0）~~ | — | 已随 iOS 层整体删除 |

Android Gradle 依赖全部宽松（Apache-2.0/MIT，见 THIRD_PARTY_LICENSES.md），
含 Shizuku（dev.rikka.shizuku，MIT——注意与 RikkaHub 无关）。

## 2. 阶段计划

原则：**绿档随时可发**（行为零变化），**黄档逐个子系统**（parity 验证后
合并，合并窗口以天计），**红档最后动**（崩溃线）。

### P0 · 死代码删除 — 绿档 — 28 文件 4,831 行（实测删除 4,803）— [x]

- [x] ui.settings：CatalogPagePresentation, CharacterCatalogScreens,
      CharacterEditorDraftState, InteractiveFictionCatalogScreens,
      InteractiveFictionEditorDraftState, KimiDeviceLoginDialog,
      NovexCharacterEditorScreen, ThinkingRuleEditor, ThinkingRulesSection
      （均为被新编辑器/新设置体系替代的旧代界面）
- [x] ui.onboarding：OnboardingScreen（启动流已被 NovexLaunchActivity 替代）
- [x] data：DeviceIdentity, SessionForkManager（被 conversation 模块替代）
- [x] providers：MinisDocumentsProvider（未在 Manifest 注册）
- [x] offload：CalendarManager, HealthManager（上游助理遗留）
- [x] auth/provider.antigravity：AntigravityOAuthManager, AntigravityModelsApi
- [x] tools：BrowserUseTool（ChatViewModel 另有自带实现，删前确认）
- [x] ui.terminal：AnsiParser
- [x] data.attachments：DocumentExtractionDiagnostics, NovexMediaWikiHttpTransport
- [x] sandbox：ShellTimeoutPolicy
- [x] speech：ToolSpeech
- [x] ui.markdown：SyntaxHighlighter
- [x] ~~crash：CrashFileReporter~~（误删后恢复：经 META-INF/services SPI 注册存活，见教训二）
- [x] service：BackgroundInterruptionTracker
- [x] ui.chat：ConversationTimelineMutation
- [x] novex.android：ModuleList
- [x] 捎带：ui/chat 注释里的「RikkaHub/ZCode-style」措辞改为
      「主流聊天客户端惯例」（尽观感友好，非法律义务）

### P1 · Novex 自有代码机械搬家 — 绿档 — 自包含包 main 新路径共 192 文件（app 151 + novex-core 41；含测试共 340 改名/571 文件改写）— [x]

执行口径（2026-09-29，PR 见进度日志）：整包迁移自包含的 Novex 包——
`com.openminis.app.novex.domain` → `novex.core`（app 84 + novex-core 41，split
package 两半同步迁移）、`com.openminis.app.novex.adapter` → `novex.android.adapter`、
`com.openminis.app.ui.novex` → `novex.android.ui`。搬家前置排雷：Room 注解零、
kotlinx.serialization 零（无多态判别式落盘风险）、非 Kotlin 文件引用零。
散落在上游包内的 Novex 新文件（data/character、ui/settings、ui/chat 等，
约 169f）**随 P3 所属子系统一并迁移**——它们与上游代码存在同包裸引用，
单独搬迁非纯机械，强行移动违反本阶段绿档约束。审计工具包前缀已升级为
`novex.*` 通配。

### P2 · rootfs 去 GPL — 黄档 — [x]

执行口径（2026-09-29，见进度日志）：toybox 预编译通道不可用（landley.net
不可达、Alpine 无包），改用更务实变体——rootfs 内 **BusyBox 整体出包**
（GPL 维权最高发组件），`/bin/sh` 由 **dash（BSD-2-Clause，Alpine 官方
aarch64 包，SHA256 钉死）** 承担（相对符号链接双兜底 /bin + /usr/bin）；
**apk 保留**（运行时 `apk add` 是活依赖，GPL-2.0+ 独立组件，符合 D1）。
`prepare_android_sandbox.sh` 构建期转换（alpine 基线→剥离 busybox 实体/
applet 符号链接/配置目录→apk world 去 busybox 登记→注入 dash→novex-tools
一次性预装脚本→重打包），tar 为 gitignored 生成物。App 代码变化：
① OnDemandBash 安装清单扩为 `bash coreutils coreutils-env sed grep
findutils`（运行时经 apk 拉取，非本 App 分发；env 在 Alpine 拆在
coreutils-env 子包）；② PRootKernel 两处包装脚本去 busybox 硬编码
（写保护九命令改显式路径解析、top 的 ps 解析+awk 降级）。既有容器安装
不回溯变更，「重置容器」后生效。风险注记（真机回归清单）：dash vs
busybox ash 的 shell 语义差异（PS1 反斜杠转义、applet 覆盖）、**只读
挂载写保护包装**（touch/cp/mv/mkdir/rm/rmdir/ln/dd/tee）、top 渲染、
minis-mcp-cli（#!/usr/bin/env 依赖 coreutils-env）、命令真空期预装。

### P2.5 · 沙箱退役（R0-R4）— 用户裁决 2026-09-29：「拆，必须拆」 — [ ]

前置事实（代码核实）：制卡全链路（NovexContentToolExecutor 纯 Kotlin）、
生图（OkHttp 直连）、文档解析（Kotlin）、技能/记忆读取（/var/minis 宿主侧
解析，不走 PRoot）均零沙箱依赖。沙箱服务的 shell_execute/终端/MCP/
Shizuku 系统操控全系上游「AI 助理」遗产。**退役后分发物 GPL 归零。**

- [ ] **R0 下架灰度**（1 个 beta 周期）：查证后两项已天然成立——
      shell_execute 早已不在工具目录（有测试钉），Novex 设置页从未渲染
      终端/容器/挂载入口（死参数家族，仅深链+存储页 Shell 行可达，后者
      用户主动、R3 拆）。实际落地：撤 LLMModel/MCP 两处幽灵命令广告、
      分发器拒绝幻觉调用（3 次同形失败自动停，无风暴）
- [x] **R1 保命件搬家**：/var/minis 宿主侧路径解析器抽为
      novex.android.ContentPaths（挂载表所有权移交；PRootKernel 留薄委托
      供将亡文件过渡；22 个消费文件直连新解析器；沙箱根回退改为显式登记
      rootfsFallbackDir，随 R2 消失）；JVM 单测钉最长前缀/精确/回退/移除行为
- [x] **R2 拆执行层**：删 sandbox/（12f）+ sandbox/offload（23f）+
      OnDemandBash + ChatViewModel shell_execute/browser_use 路径；删
      jniLibs 全部五枚（libproot/-loader/-loader32/libtalloc/
      libandroid-shmem）+ cpp/pty_bridge.c + 资产（rootfs、proot-aarch64、
      default_mount）+ prepare_android_sandbox.sh + CI workflow 的 prepare
      步骤；Manifest 摘 4 组件（AlarmReceiver/ScheduledNotificationReceiver/
      NotificationListener/无障碍）+ 6 权限（日历×2、定位×2、通讯录×2）。
      裁决偏离：BrowserUseManager 保留——它是 BrowserTabPool 的 tab 包装器
      （手动内置浏览器在用），删它断编译；SYSTEM_ALERT_WINDOW 保留——
      AgentForegroundService 悬浮胶囊在用；offload/ 三件保留
      （OffloadPermissionManager/ShizukuManager/ShizukuBackend，权限 UI 在用）
- [x] **R3 拆界面**：ui/terminal 全家（终端+模拟器+canvas）、
      Mirror/Rootfs 两屏、挂载两屏（MountDetail/MountedFolders）、
      环境变量屏、设置行、存储管理「沙箱容器」桶、导航路由/深链。裁决
      偏离：SharedFolders 保留（/var/minis/shared 是全局桶、ContentPaths
      直连，不依赖沙箱）；ui/sandbox 的 FileBrowser/FileBrowserViewModel/
      FilePreview 三件保留（聊天附件预览/创作库/会话存储/共享文件夹四个
      幸存界面共管，已改走 ContentPaths）
- [ ] **R4 收尾**：THIRD_PARTY_LICENSES 出清 proot/talloc/alpine 条目
      （**GPL=0**）；本计划表重写（P3 沙箱重写项删除、P4 瘦身、P5 解锁）；
      审计复跑 + 八流程冒烟（重点证明制卡/技能/生图/附件零沙箱依赖）
- 卫星裁决（保留）：AgentForegroundService（对话后台）、定时任务
  （纯闹钟+对话）、用户手动内置浏览器（与 AI 自动浏览分离评估）

预计拆除：源码约 21,640 行 + 二进制/资产约 4MB + 权限清单大瘦身。

### P3 · 子系统绞杀 — 黄档 — 顺序即依赖序 — [ ]

**P3.0 执行纲领（摸底结论，2026-09-29）**：provider.* 与全部待绞杀子系统
都是**上游血统**代码——正确路线是「以自有模块重写等价功能 → parity 验收 →
删除上游原件」，**绝不能把上游文件搬进干净模块**（搬家即污染 novex-core/
model-transport 的零血统状态，前面 P1 的成果就白费了）。每个子系统的
workorder：①可达面甄别（audit 工具 + 29 个消费文件逐一核对真实用到的
API 面）→ ②自有实现（放 novex.model / novex.runtime / novex.android.ui）
→ ③parity 验收（单测钉行为；网络层用 mock/response 钉格式）→ ④删上游
原件 → ⑤CI + 净眼 + 冒烟。顺序即依赖序：

1. provider 接入（openai/anthropic/gemini/thinking/voice/image ≈10k，
   29 个消费文件）→ 以自有 model-transport（novex.model，现有
   ChatCompletionClient）为底座重写后删除上游原件
2. data.db（Room minis.db）+ data.repository → 自有存储模块
3. ~~sandbox 应用侧 Kotlin 重写~~ → 已被 P2.5 沙箱退役整体取代（用户裁决）
4. browser + ui.browser、speech（活 12f）、debug 面板、config、tools、
   offload、share、ui.settings 剩余屏、ui.chat 逐块 → conversation-runtime
   + ui.novex 持续绞杀
5. 159 个混合文件随所在子系统一并甄别：Novex 部分保留搬家，上游部分重写

### P4 · 启动骨架五件套 — 红档 — 最后 — [ ]

MinisApp 初始化图（DB/Coil/ACRA/hydrate）、入口 Activity
（MainActivity/NovexLaunchActivity/NovexHomeActivity）、导航图、Room 装配、
provider 配置流。**这是崩溃线**：动之前 P0–P3 必须全部完成。

### P5 · 协议切换与法律动作 — [ ]

- [ ] 分发物内上游代码清零的审计复核（重跑本工具 + 抽查）
- [ ] LICENSE 更换（Apache-2.0 或专有，按公司法务拍板）、THIRD_PARTY 更新
- [ ] 软著登记、Novex 商标注册（GPL 不带走商标权，商标才是自己的）
- [ ] proot GPL 组件的源码随附/指引页（若 D1 决定保留）

## 3. 每刀执行协议

1. 从本计划表取 scope → 任务书
2. 分支 `tasks/exit-N`（或并入功能 PR 捎带，仅限绿档）
3. 实现 → CI（Android quick validation）绿
4. 全量测试绿
5. 冒烟清单（下节）逐项过
6. 净眼局部审查 → 合并 next → 随正常 beta 发布
7. 回填本文件勾选框 + 进度日志（日期/PR/度量快照）

冒烟清单（八流程）：启动进首页 / 会话列表与切换 / 对话收发（含工具执行）/
沙箱终端开合 / 设置各页 / 存储管理 / 版本中心检查更新 / 导入导出卡片。

## 4. 决策挂账

- **D1（公司法务）**：proot（GPL-2.0，独立进程 + 源码公开）可否作为最终
  分发物内的隔离组件保留？可 → P3 第 3 项照做、P5 挂源码指引；
  否 → 仅剩自研 ptrace 加载器（人年级）或砍沙箱功能两条路。
- **D2（发版节奏）**：P0/P1 何时开刀（绿档，可与任意待发功能同车）。
- 上游（OpenMinis）后续同步策略：P0 合并后冻结非安全类同步，避免血统
  复活。

## 5. 进度日志

| 日期 | PR | 动作 | 度量快照 |
|---|---|---|---|
| 2026-09-28 | — | 建立本计划 + 审计工具 scripts/upstream_audit.py | 死代码 28f/4.8k；活代码血统 63.7k/101.8k/50.2k |
| 2026-09-28 | #52 | P0 完成：删 28 死文件（实测 4,803 行）+ 5 死测试整删 + 6 测试修剪 + 16 处注释中性化 | 上游命名空间血统存量 -4.8k；测试源集死引用清零 |
| 2026-09-29 | 见进度 | P0 修正：CrashFileReporter 经 SPI 注册存活被误删，恢复并升级审计工具（SPI 根）；beta.94 构建被 R8 拦截后修复 | 血统分类 261/160/379（+1 上游改动=恢复件） |
| 2026-09-29 | #53 合并 + beta.95 发布 | P1 完成：三个自有包迁入 novex.*（含遮蔽修复）；公告含请勿更新警告 | 上游命名空间自有代码清零（自包含部分） |
| 2026-09-29 | 见进度 | P2 完成：BusyBox 出包、dash 承担 /bin/sh、apk 保留（D1）；净眼两轮退回修复（包装去 busybox 硬编码、coreutils-env、apk DB 剔除 busybox 条目、预装脚本、命令真空期兜底） | 分发物 GPL 仅剩 proot 全家 + apk 两个隔离组件；DB 无 busybox 登记，upgrade 不回装 |
| 2026-09-29 | 见进度 | R2+R3 完成：执行层（sandbox/ 35f、agent/shell 3f、offload 死件 8f）与界面（ui/terminal 全家、Mirror/Rootfs/挂载/环境变量屏）拆除；五枚 so + default_mount + prepare 脚本 + CI prepare 步骤清零；Manifest 摘 4 组件 6 权限 | 源码 -约 3.1 万行（git diff --stat）；BrowserUseManager/BrowserTabPool 因手动浏览器共管保留 |

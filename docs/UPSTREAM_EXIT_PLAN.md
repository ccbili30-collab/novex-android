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
- **第四维（P3.4）**：改动桶每件与基线同路径文件的文本相似度（剥注释 +
  空白归一 + 大小写折叠的行级比对），三档量化真实剩余重写量——见
  「P3.4 混合件余量量化」小节
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
- [x] **R2 拆执行层**：删 sandbox/（主 11f）+ sandbox/offload（26f）+
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
- [x] **R4 收尾**：THIRD_PARTY_LICENSES 出清 proot/talloc/alpine 条目
      （**GPL=0** 宣告，2026-09-29）；净眼 PR#60 六条建议清剿（terminal
      死码两件、a11y 死路 UI 与 accessibility/ 整包、过时 KDoc 归因、
      toolPattern 去 browser_use、钉死契约的 ImageEditRoutingMatrixTest）；
      审计复跑（死代码归零）。计划表 P5 proot 挂账随 GPL=0 了结；
      D1 无需再决。八流程冒烟随 R4 后的 beta 发版执行
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

1. ~~provider 接入（openai/anthropic/gemini/thinking/voice/image ≈10k，
   29 个消费文件）~~ —— P3.1a–P3.1e + P3.2 完成：聊天/生图/模型目录/
   语音/思考全部走自有实现（novex.model 传输与目录 + novex.android.
   transport 适配器 + novex.android.voice/thinking/models），provider/ 下
   openai、anthropic、gemini、voice、image、thinking 六包上游原件清零
   （provider/ 根与 openrouter/xai 的 ModelsApi 随后续刀处置）
2. ~~data.db（Room minis.db）+ data.repository → 自有存储模块~~ —— P3.2b
   完成前半：data/db（27f）+ data/model（18f）整包删除，Room 层自有化
   （novex.android.data；schema 冻结证据见进度日志 P3.2b 行——DDL 逐句
   一致 + identityHash 字节相同，老用户库无损）；data/repository 本轮仅
   改接（import/符号名），其真重写立为 **P3.2c 独立任务**（下一刀）
3. ~~sandbox 应用侧 Kotlin 重写~~ → 已被 P2.5 沙箱退役整体取代（用户裁决）
4. browser + ui.browser、speech（活 12f）、debug 面板、config、tools、
   offload、share、ui.settings 剩余屏、ui.chat 逐块 → conversation-runtime
   + ui.novex 持续绞杀
5. 159 个混合文件随所在子系统一并甄别：Novex 部分保留搬家，上游部分重写

**进度**：P3.1a 流式能力落地（PR #62）——model-transport（novex.model）补齐
SSE 流式：stream=true 编码（stream_options 兼容开关）、逐行容错解析（CRLF/
注释/半行/非 JSON）、TextDelta/ThinkingDelta/ToolCallDelta/Usage/Done/Failure
块、聚合器、取消即断；模块零上游依赖不变，app 侧接线是下一步 P3.1b。
P3.1b 适配器换管（PR #63，2026-09-29）——OpenAI 兼容线路（自定 base URL
的纯 chat completions）已切自有传输：`novex.android.transport
.NovexTransportProvider` 实现上游 LLMProvider、内部全走 novex.model；官方
直连/Azure/Responses/前尘回退/局域网明文与 anthropic/gemini/openRouter/
xAI/kimi/语音/生图暂留上游（生图经 imageDelegate 回上游实现）。模块面小进化
（ModelEndpoint 附加头、WireMessage reasoning_content 回放与音频块、
TextRequest 附加顶层参数）；流时序/错误分类/取消语义贴上游。净眼退回两修后：
流桥改 trySendBlocking（维持 stream 的背压契约——慢消费者放慢读取而非丢块）、
token 上限键按主机对齐（OpenRouter 收 max_tokens，其余一切端点收
max_completion_tokens，两键在模块层互斥）、流中 error 对象按数字 code 走上游
分类矩阵（含 503 永久失败标记与 OpenCode 日落文案）。已知不对齐（记录在案）：
<think> 前缀拆分不做、HTTP 错误不带 error body 文本、usage 无 cache 字段、
temperature 丢弃（调用点皆 null）、模块容量闸门在适配层直通。
P3.1c 原生协议换管（PR #64，2026-09-28）——anthropic（Messages）与 gemini
（generateContent）两家原生线协议在 novex.model 自有实现（AnthropicWire /
GeminiWire：请求编码 + SSE 方言解码 + 思考形态判定），适配器按 provider 类型
分线（WireProtocol 三方言共享 ChatCompletionCall 的连接/取消/超时/容量骨架，
SseDecoder 抽出行状态机骨架）；ProviderFactory 的 anthropic/gemini 分支改为
构造适配器——官方直连、自定中继、OAuth（Claude Code）一并换管，上游
AnthropicProvider/GeminiProvider 不再被工厂引用、主代码零活引用（文件留存，
P3.1d 统一拆除）。三线（OpenAI 兼容/anthropic/gemini）聊天流量全走自有传输。
随线能力：cache_control 断点（system/末工具/最近两条 user，enhancedCache 的
1h TTL + beta 旗标）、思考形态（adaptive effort / legacy budget+temperature=1 /
disabled）、OAuth 系统前缀块拆分与 CLI 指纹头、交错无签名思考回放（Anthropic
兼容中继）、gemini thoughtSignature 回放与缺签名降级、inlineData 媒体输出、
静默空完成按瞬态失败（failOnSilentEmptyCompletion，贴被替换实现）。净眼退回
修复（同 PR）：HTTP 非 200 读错误体进失败块 message（三方言各按自家 JSON 形态
解析——OpenAI error.message+request_id / anthropic [type] message+type 进 code /
gemini message+status 进 code；P3.1b 的「HTTP 错误不带 error body 文本」缺口
就此关闭）、gemini 干净断流未见 finishReason 缺省 Done("end_turn")（对齐被替换
实现；空响应经此缺省不再触发空完成瞬态重试——与被替换实现一致，截断/IO 异常
路径的重试不受影响）、LAN 明文中继请求前置预检（适配器 rawStream 开头过
endpointAcceptable，不通过以确定性中文 ProviderError 收流、绝不映射 NetworkError
进瞬态重试链——与 P3.1b 的 OpenAI 兼容线「暂留上游」不同，因工厂不得再引用
上游实现）。已知 judgment calls（记录在案）：usage 无 cache 计量字段（沿
P3.1b）、gemini safety 续读不做（SAFETY 收尾原样透传）、temperature 调用方值
丢弃（协议性的 legacy temperature=1 仍生效）、gemini 图片现过 ImageBudget 预算
压缩（被替换实现原图直发）、空 systemInstruction 不发（被替换实现只判 null、
空串也发）。不做（P3.1d 处置）：工具结果内嵌图片（anthropic 线，沿 P3.1b
口径）、孤儿 tool_result 语义对齐（新线在适配层 idRegistry 丢弃，被替换实现在
provider 层 strip）、usage cache 字段、TransportCall 的 protocol 防呆断言、
OAuth 前缀测试的 assumeTrue 覆盖（未配置定制属性时跳过）。
P3.1d 绞杀收尾（PR 见进度日志，2026-09-29）——**删除上游 provider/anthropic/
（3f/1,430 行）与 provider/gemini/（2f/683 行）两包及其测试**（632+403 行），
残余接口面先立后破，净眼挂账五条全清：
- **ImagesClient**（novex.model 自有，/images/generations JSON + /images/edits
  multipart）：请求体键、b64_json 自动探测重试、url 条目无鉴权下载、mime
  提示/响应头/魔数兜底、代理误路由 404 语义对齐上游生图路径；适配器
  imageDelegate 改走它，消费方（GenerateImageTool/QuickTestSheet）经新接口
  ImagesCapableProvider 取生图能力，不再下钻具体 provider 类型。
- **ModelsCatalog**（novex.model 纯函数面）+ app 侧 **ModelsCatalogApi** 包装器：
  三方言模型目录自有化（anthropic 基址爬升 + 思考盖章、gemini chatCapable
  过滤、openai 前缀过滤 + raw 模态透出）；ProviderRepository/设置屏/容量测试
  改调，OpenAIModelsApi（212 行）随之删除。两处等价改写记录在案：gemini key
  改 x-goog-api-key 头（服务端等价 ?key=，令牌不进 URL）；anthropic 缓存并入
  ProviderModelsCache("anthropic") 命名空间（旧无命名空间条目自然过期）。
- 净眼挂账：a) anthropic 工具结果内嵌图片补发（含 ImageBudget 兜底；exotic
  mime 强转 jpeg 为新线口径——WireImage 只收四种位图 mime）；b) 孤儿
  tool_result「过一条 user 即失效」（idRegistry.expireBatch，迟到旧批结果按
  孤儿丢弃）；c) usage 补 cache_creation/cache_read 计量（anthropic 方言，适配器
  LLMUsage 映射）；d) ChatCompletionCall 协议×请求体防呆（错配开连接前早失败，
  execute 限 OpenAI 兼容线）；e) OAuth 前缀可注入（oauthSystemPrefixOverride，
  公共 CI 跑满断言块，assumeTrue 撤除）。
- **范围纠偏（重要）**：provider/openai/ 整目录删除**未执行**——侦察发现
  OpenAIProvider（3,718 行）仍是 Codex-OAuth（Responses API）/官方直连/
  useResponsesAPI/Azure/前尘 responses 回退/局域网明文/OpenRouter/xAI（OAuth+
  key）/Kimi（OAuth+key）九类实例的**活聊天实现**（P3.1b 有意暂留上游，见
  P3.1b 行），并非死码。删除须先在 novex.model 立 Responses API wire + Azure
  deployments 路径 + 动态 OAuth bearer + OpenRouter 附加头与 anthropic/ 前缀
  cache_control，并逐线 parity 验收——体量同级于 P3.1c，立为 **P3.1e 独立
  任务**（下一刀），不塞进本轮。openai 包本轮仍瘦身：OpenAIModelsApi 移植后
  删除，包内剩 OpenAIProvider + ThinkPrefixStreamParser（3,920 行血统）。
  适配器对 imageDegradedModels/looksLikeImageRejection 的共用（跨实现学习集）
  随 P3.1e 一并迁移。
P3.1e OpenAIProvider 终局退役（PR 见进度日志，2026-09-28）——**删除上游
provider/openai/ 整包（OpenAIProvider + ThinkPrefixStreamParser，审计口径 3,920 行）及其测试**，九类实例全部换管自有传输：
- **ResponsesWire**（novex.model 自有，零上游依赖）：Responses 请求编码
  （input items/instructions/扁平工具/store:false/prompt_cache_key 会话稳定
  缓存键/reasoning{effort,summary}/include 加密思考回放（仅 codex）/
  max_output_tokens（codex 指纹体不写）/input_image 裸字符串形态/function_call
  双 id（id=fc_… + call_id）逐字回放与 fc_syn 合成）+ SSE 事件族解码
  （output_text/reasoning_text/reasoning_summary_text 增量、output_item
  added|done、function_call_arguments 增量、completed|failed|incomplete 终态、
  无 [DONE] 哨兵时按 finishReason 补收尾、从未 added 过的 function_call 整块
  防御补块在 output_item.done、completed 的 output 重扫仅 codexImageRun 生图
  流提图块）+ **gpt-image-2 codex 生图流**
  （固定指纹体 gpt-5.5+image_generation 工具；文本增量转拒答文案、图块转
  媒体附件、无图以 Failure 收流——Done 只随图产出，不被 finishReason 盖掉）。
- **Azure 形态**：ModelEndpoint 增 tokenHeader（api-key 头替代 Bearer）+
  permitQueryParams；适配器 azureUrl 剥离游离 /v1 与 /openai 段拼
  deployments 路径并保留 ?api-version 查询；聊天/Responses/生图（ImagesClient
  显式端点覆盖）三路同规则。
- **动态 OAuth bearer**：适配器 oauthTokenProvider 每请求挂起解析（刷新感知；
  解析失败按 InvalidApiKey 收流不进重试链），Codex/xAI/Kimi 三家共用；codex
  客户端指纹头（Version/Openai-Beta/Originator/codex_cli_rs UA/账号 id 可空）。
- **OpenRouter 附加头**（HTTP-Referer/X-Title 经 extraHeaders 后合并替换）+
  anthropic/ 前缀模型顶层 cache_control 断点（[OpenMinis#191] 3-6 倍成本修复）。
- **前尘 responses 回退语义保真**：chat 首块前失败自动改走 /v1/responses 重试
  一次；进程级粘性按实例 id（工厂重建的 provider 对象同样命中，省一次必败
  chat 往返）；粘性等重试真的产出首块再落（responses 也失败则下回合仍从 chat
  试起）；已发块后的失败不触发回退。
- **imageDegradedModels 学习集迁移**：app 侧自有 ImageDegradationLearning
  （imageDegradedModels/looksLikeImageRejection/中性占位文案）；chat+responses
  两方言参与降级学习，anthropic/gemini 恒真实发送。openCodeSunsetFriendlyError
  随 401/403 分类矩阵迁至适配器（测试随迁）。
- **LAN 明文政策（与 P3.1c 一致）**：自有传输只收 https/本机回环；局域网明文
  http 中继以确定性中文 ProviderError 收流（请求前置预检，含动态 OAuth 令牌
  装配后的端点形校验），工厂不再有任何回退实现可躲。
- Responses 方言发裸 `application/json`（无 charset 后缀——部分第三方 Responses
  中转严格拒收）；chat 线（与 anthropic/gemini 线）沿用
  `application/json; charset=utf-8` 不变——两线 Content-Type 差异由此记档。
  诊断面差异（净眼 PR#66 ⑤，2026-09-28 记档）：被删上游 provider 层写入的
  LLMRequestLog 环形缓冲（debug.llmRequests 面）与 [T321] 无哨兵断流尾日志随
  包删除后**无写入方**——LLMRequestLog 暂成只读死面（DebugRPCHandler 读取端
  保留，返回恒空）；存活的逐请求诊断面是 ProviderWireCapture（ChatViewModel
  层：每出站请求无条件一行摘要 + 带 tools/图片标记的请求附原文）与适配器
  Thinking/NovexTransport 日志线。response.failed 的 server_error/rate_limit_exceeded 按瞬态
  分类（仅 responses 线；chat 线同名字符串 code 仍走上游 optInt 默认 0 的供应
  商错误口径）。
- P3.1d 净眼三建议采纳：① gemini 目录拉取网络异常 IOException 上抛（对齐被删
  语义，不再静默回内置表）+ OAuth 目录补品牌 UA；② ImagesClient b64 重试匹配
  lowercase（大写 Response_Format 拼写同样触发去键重试）；③ anthropic 线工具
  结果图 mime 缺失按魔数探测（不再假定 png）。
- 存量行为测试换管续跑：ThinkingWireGoldenSnapshot（119 行金表经适配器原样
  通过）、MistralReasoningField/OpenRouterCacheControl/ThinkingRulesRegression/
  ResponsesTopLevelImage/ResponsesApiFinished/StreamDropNoFinish/OpenAIEditImage/
  ModelRequestAudit/DocxAttachmentRequestChain/DocumentContinuationBudget 全部改
  经 NovexTransportProvider 钉同一批行为；OpenAIProviderTest/ThinkPrefix
  StreamParserTest/OpenAIHttpErrorDetailTest 删除（思考金表由快照与回归测试
  承担、<think> 前缀拆分自 P3.1b 起记录在案不迁移、HTTP 错误体细节由模块
  ChatCompletionStreamTest 钉）。
- 已知 judgment calls（记录在案）：图片降级占位折进 user 轮文本（chat/responses
  两线同口径；被替换 responses 实现为独立 input_text 块）；responses 线 usage 不做 fresh-only 减法
  （沿 chat 线口径，cacheRead 原样透传）；output_item.done 的 function_call
  权威 arguments 以增量流为准（StreamChunk 契约，服务端修正载荷罕见）；codex
  生图 url 条目下载不含（后端只走 base64 result）；temperature 丢弃沿旧规；
  TTFB 分相看门狗/OkHttp 线路追踪不上移（自有传输统一 300s 读超时+停滞看门
  狗，P3.1b 已记录）。
九类路由对照：①Codex-OAuth→RESPONSES@chatgpt.com 后端+指纹头+codex 生图体
②官方直连→CHAT@api.openai.com/v1 ③useResponsesAPI→RESPONSES@自定基址
④Azure→deployments 路径+api-key 头（chat/responses/生图同规则）⑤前尘回退→
CHAT 起步失败自动切 RESPONSES（进程粘性）⑥LAN 明文→适配器预检确定性报错
⑦OpenRouter→CHAT@openrouter.ai+附加头+anthropic cache_control ⑧xAI→CHAT
@api.x.ai/v1（OAuth 动态 bearer）⑨Kimi→CHAT@api.kimi.com/coding/v1（OAuth
动态 bearer）。`grep OpenAIProvider src/android/app/src` 归零（注释墓碑中性化）；
`grep 'import com.openminis' src/android/model-transport/` 保持归零。
P3.2 provider 剩余小件绞杀（PR 见进度日志，2026-09-28）——**voice/image/thinking
三包删除，provider.* 下再无上游血统子系统**：
- **voice**（provider/voice 4f + data/model 模板件）：自有 novex.android.voice
  五件（VoiceWire 值类型与错误分类 / VoiceClient OpenAI 兼容基座 / VoiceClients
  十二厂商客户端 / VoiceClientFactory 判定矩阵 / VoiceVendorTemplates 厂商
  种子模板）。逐厂商 parity：请求形状（Doubao v3 单向流式 TTS 帧解析 + bigmodel
  flash ASR、MiniMax t2a_v2 双响应外壳（data.audio hex→audio.audio base64）+
  base 剥 /v1|/anthropic、讯飞 HMAC-SHA256 签名 URL + WebSocket TTS PCM 收流
  包 WAV、Gemini generateContent+AUDIO 模态、ElevenLabs/Deepgram/Azure/MiMo/
  Groq/阿里/xAI、OpenRouter chat-audio 形态与 ASR 双端点路由谓词）、鉴权
  （Bearer/X-Api-Key/xi-api-key/Token/Ocp-Apim/签名 URL/api-key）、音频格式
  （WAV 包头、mime 魔数、24k/16k 采样率）、错误分类（401/403→Auth、
  MiniMax base_resp 码、Doubao 无帧附原文预览、OpenRouter 无音频带 transcript）。
  假服务器测试钉 Doubao TTS/ASR 与 OpenAI TTS 各一组（请求断言+响应解析+错误）
  外加 MiniMax/基座 multipart/OpenRouter 路由迁移；工厂判定矩阵（含讯飞复合
  凭据）钉用例。消费方四处换管（语音输入引擎/朗读播放器/快速测试/影子语音门）。
- **image 目录**（provider/image 1f，审计分类本为零血统 Novex 新增）：P1 式
  机械搬家至 novex.android.models.ImageGenerationModels（端点/鉴权/过滤语义
  原样），测试随迁。
- **thinking**（provider/thinking 4f）：自有 novex.android.thinking 四件
  （ThinkingWire 形态词表 / ThinkingContract 规则值类型 / ThinkingContract
  Resolver 解析器 / ThinkingContractCoding 持久化编码）。金表快照（119 行）与
  回归/合并/xAI 空档位四件测试原样通过（ThinkingWireGeminiAnthropicSnapshot
  仅换 import；两件 provider/thinking 测试等价迁入自有包）。DB 实体与 DAO 方法
  随消费者更名（ProviderThinkingContractEntity，Room 表名 provider_thinking_
  rules 与列名不变、存量数据无损）；自定义规则 blob 的 JSON tag 词汇逐字兼容。
- 净眼 PR#66 七条全清：①「已发块后失败不触发回退」测试补断言请求数=1；
  ②负向粘性测试（responses 重试也失败→粘性不落，新回合仍从 chat 起步）；
  ③codexImageRun 不认 [DONE] 哨兵——图块前到哨兵不再发 Done(null)，与被删
  一致按流尾 Failure 收（No image data）；④codex 生图流 reasoning 增量不折
  进 refusalText（被删上游只认文本/消息条目为拒答源），output_text.done 整段
  替换增量拒答（免重复）；⑤docs 补记 chat 线 Content-Type 带 charset 与诊断
  面差异（LLMRequestLog 随上游 provider 删除后无写入方、T321 尾日志同亡，
  ProviderWireCapture 为存活面——见 P3.1e 行内记档）；⑥docs 措辞修正
  （function_call 整块补块在 output_item.done、completed 重扫仅 codexImageRun）；
  ⑦三测试盲区补钉（Mistral×responses 双头抑制、ImagesClient 大写
  Response_Format 拼写触发去键重试、①Codex 工厂构造用例——Robolectric
  Context 走真实 OAuth 存储回落路径）。
- 死码扫尾：diagnostics/LargeAllocProbe（105 行）+ provider/JsonExt（18 行）
  删除（三通道核查：SPI 仅 ACRA 注册件、Manifest 零引用、FQN 零引用）。
- 验收：`grep 'VoiceProvider\|ImageModelCatalog\|ThinkingRule' src/android/
  app/src --include='*.kt'` 归零；model-transport 零 com.openminis 不变；
  审计死代码归零（血统数字见进度日志行）。
P3.2b Room 数据层绞杀（PR 见进度日志，2026-09-28）——**删 data/db（27f）
+ data/model（18f）整包，Room 存储与模型值类型全部自有化
（novex.android.data，app 模块内）**：
- **冻结面（逐字保留，schema 无损的硬证据）**：主库 minis.db 版本 39、
  副库 provider.db 版本 5 不变；表/列/索引/外键经 @Entity/@ColumnInfo 显式
  钉住；38 步迁移 DDL 原文保留（仅外围重构）；**Room KSP 生成物比对：
  主库 createAllTables 89/89 条 DDL 逐句一致、副库 9/9 一致，identityHash
  （主 6397ab3a…/8567eaad…、副 394a39eb…/4d415b94…）重写前后字节相同**——
  老用户库零损升级，Room 校验不触发 fallback。
- **重做面**：AppDatabase → NovexMainDatabase + MainDatabaseMigrations（38
  步重排为 step() 注册表 + addColumnUnlessPresent 幂等助手，迁移链
  MAIN_SCHEMA_STEPS 显式成表）；ChatDao 单体 800 行拆为读写面（SessionReads/
  SessionWrites/FolderReads/FolderWrites/MessageDao/MarkerDao/ContextUsageDao）
  + ChatDao 门面（@Transaction 复合操作重排控制流）；provider 库 DAO 改
  快照式（readSnapshot/overwriteConfigTables 截断走共享 RawQuery 执行器）；
  卡片库实体集中进 CardTables 冻结文件、DAO 分件；查询语句全部等价改形
  （表别名/谓词重排/截断合并），结果集不变。
- **模型值类型（data/model 18f → 11f）**：LLMMessage/LLMStreamChunk/
  ContentPart 等 wire/parts_json 事实面保持逐字（@SerialName 标签即存储
  格式）；错误文案重写（英文转中文用户向措辞，无消费方按前缀匹配，已核）；
  ProviderFailure 取证重构（状态形态表驱动 + firstNotNullOfOrNull）。
- **测试**：新增 Robolectric 两件十用例（inMemoryDatabaseBuilder 钉
  sessions/messages/compact_markers/provider_instances/provider_thinking_
  rules 列名与列序 + 版本号/文件名 + 插入/分支切换/compact marker/provider
  配置/思考规则读写回路）；既有 androidTest Room 用例改 import 保留
  （androidTest 编译错误为 next 上预存 UI 签名漂移，与本刀无关，快线 CI
  不编译 androidTest）；全量单测 1,585 条通过。
- **死码**：AgentTypes 族（AgentStreamEvent/AgentBlockStart/AgentStopReason/
  ToolCallMetadata/AgentMessage/sanitizeToolId）在 HEAD 上已零活引用（仅
  注释提及），随绞杀删除。
- **相似度验收**：剥注释+归一化标识符+语句级对比（与 P3.2 同口径）——
  逻辑件 21 件全部 <40%（最大 26.4%、均值 7.4%）；>40% 者皆为冻结面
  （MainDatabaseMigrations DDL 0%、Room 列声明、parts_json 编解码 95.8%、
  LLM 线协议 DTO 92-100%、provider JSON 镜像字段名 68.1%——字段名/标签
  即持久化格式，逐字保留是验收要求而非缺陷）。
- **消费方**：全仓 ~290 件改接（import + 符号名），data/repository 按纪律
  仅改接不重写；data/character、data/creative、data/attachments 未动（随
  各自消费方战役处置）。

P3.3 产品范围裁军轮（用户裁决 A+B+C 全砍单，2026-09-30）——**九大产品面
按用户裁决整体退役，砍单逐项删净不留尸体，130 文件约 -4.0 万行**：

- **用户裁决砍单清单**：①语音全家（ASR+TTS 两套）②内置浏览器全家
  ③WebApp/PWA 捷径 ④Shizuku/特权后端 ⑤定时任务 ⑥调试面板 ⑦MCP 残件
  ⑧聊天内嵌媒体播放器 ⑨minis-config 体系（自定义思考规则机器随葬）。
- **砍单明细（文件数/行数）**：speech/ 26f 7,733 行（含 correction/ 14f）
  + novex.android.voice/ 5f 1,509 行（12 厂商客户端）+ ui/chat/voice/ 3f
  + ShadowVoiceDetailScreen/语音模板 UI/voice 设置项；browser/ 8f 3,901 行
  + ui/browser/ 6f 2,012 行 + ui/preview/ 4f 1,367 行 + UrlPreviewSheet；
  webapp/ 5f 1,293 行；offload/ 3f 910 行 + Offload/Shizuku 权限屏；
  scheduled/ 5f 960 行 + ui/scheduled/ 4f 1,539 行；debug/ 12f 5,498 行；
  mcp/ 4f 469 行 + MCPRepository/SessionMcpsSheet/MCPIntegrationsScreen；
  ui/media/InlineMediaPlayer 1f 653 行；config/ 20f 4,785 行
  + ConfigAudit/ConfigConfirm 两屏与 ConfigConfirmNotifier；
  OffloadPermissionDialog/AudioWaveformView/LazyReadAloudPlayer/
  SpeechLanguagePickerSheet/ComposerInputModePrefs/UnifiedModelPickerSheet/
  ThinkingContractCoding 等伴生件合计再 -6,385 行；裁军孤儿（分词四件
  SentenceSplitter/TextSegmenter/JiebaEngine/SystemSegmentEngine +
  BringIntoViewOnFocus）随葬。
- **保留红线（未动）**：内置思考金表（ThinkingWire/座次表/解析器——
  CUSTOM 自定义席位删除后 resolver 只跑内置）、ACRA（crash/）、AppLogger
  （logging/）、系统分享接收、附件/文档解析、生图、卡片全家桶、对话主界面、
  设置页其余、后台保活、provider OAuth（Custom Tabs 保留）、markdown 渲染。
- **链接外跳改写点**：消息链接点击（ChatScreen.urlClickHandler）与 markdown
  链接（MarkdownText）一律 ACTION_VIEW 外跳系统浏览器/处理器（原
  UrlPreviewSheet 内预览与 BrowserExternalSchemeHandler 收口进
  openExternalUrl）；会话内 HTML 文件改走 FilePreviewScreen 自带 WebView；
  视频/音频改经 FileProvider 外跳系统播放器（openMediaFileExternally，
  替代被裁的 InlineMediaPlayer）。
- **Manifest/依赖**：摘 RECORD_AUDIO、SCHEDULE_EXACT_ALARM、
  RECEIVE_BOOT_COMPLETED、com.android.alarm.permission.SET_ALARM、
  moe.shizuku.manager.permission.API_V23；queries 摘 RecognitionService/
  SET_ALARM・SHOW_ALARMS・SET_TIMER/shizuku・axerman 包可见项；摘
  WebAppActivity、ScheduledTaskAlarmReceiver、ShizukuProvider；build.gradle
  出清 dev.rikka.shizuku（THIRD_PARTY 同步宣告）与 androidx.webkit。
- **思考金表与库面**：前尘预设原写入的 CUSTOM 规则（gemini-* 省略思考
  参数）收编为内置席 qianchen-relay-gemini（按 base URL 嗅探，行为逐字节
  一致）；Room 表 provider_thinking_rules 保留（schema 冻结）但读写 DAO
  与 UI 全摘；ProviderConfig 的 voiceInput/OutputGroupId 字段原样保留
  （持久化格式）。deep-link：views/alarm、action/voice_chat、
  session/<id>/<path>、settings/permissions 解析删除。
- **验收**：砍单符号全仓 grep 仅墓碑注释命中；RECORD_AUDIO/SET_ALARM
  零残留；孤儿串清理 695 键（values 全量 + 六个语言目录共 -3,903 条）；
  审计死代码 0f（血统数字见进度日志行）。


### P3.4 · 混合件余量量化（审计工具第四维） — 绿档（工具+度量，零行为变化） — [x]

净眼三轮揪出的盲区：路径三分类数不出「改名换路径的直译件」（落 Novex 新增
桶），也数不出「改动桶里其实已经基本自有」的文件。本轮给审计工具加第四维
——每个血统文件与上游基线（根提交 `82c2eb0`）同路径文件的文本相似度，把
「上游改动」桶的 118 个混合件精确量化。

口径（保守估计，净眼同源）：剥注释（// 与可嵌套的 /* */，字符串字面量内的
注释符不剥）+ 空白归一 + 大小写折叠（标识符折叠刻意不做——太激进），行级
difflib SequenceMatcher ratio（autojunk 关）。基线 blob 经单个
`git cat-file --batch`（`git show 82c2eb0:<path>` 的批量等价）流式获取，
逐块读完即弃。上游未动桶与基线逐字节一致（相似度恒 100%）不算；Novex 新增
桶无同路径基线可比不算。自测：抽 3 件人工核对——XAIOAuthManager 报 100%
（git diff 仅 1 行 KDoc 改动，剥注释后逐字一致）、ProviderDetailScreen 报
3.1%（numstat +11/−1167，整体重写）、Theme.kt 报 46.0%（+274/−22 增量
改造），数字全部合理。

三档分布（118 件 74,890 行，2026-09-30 @ 本刀分支树 = next `efef545` + P3.4
改动；重跑 `python3 scripts/upstream_audit.py` 可复现）：

| 档位 | 判定 | 文件 | 行数 | 占改动桶 |
|---|---|---|---|---|
| ≥80% | 仍是上游主体（重写优先级高） | 86 | 41,497 | 55.4% |
| 40-80% | 半血（逐件甄别） | 27 | 32,905 | 43.9% |
| <40% | 基本自有（低优先，或只清洗残余片段） | 5 | 488 | 0.7% |

**真实剩余重写量结论**：改动桶名义 74.9k 行里，真正还是上游主体的只有
41.5k 行（55.4%）；加上上游未动桶 60 件 11,827 行（天然 100%），**全仓上游
血统存量 = 53,324 行（146 件）**，比「未动+改动」名义合计 86,717 行少
38.5%。半血 32.9k 行逐件甄别后还会有相当部分沉入自有档（P3.2c 已示范：
repository 层改接后大半文件滑到半血以下）。基本自有 5 件（SettingsScreen
37.1%、ProviderDetailScreen 3.1%、AgentTools 18%、SectionDropdown 19.4%、
SectionTextField 36.5%）——设置页主体与工具注册表实际已基本自有。

**战线重排建议**（按「≥80% 档行数」= 纯重写工作量排序）：

| 子系统（改动桶内） | 文件/行数 | 其中≥80%档 | 建议 |
|---|---|---|---|
| ui.chat | 29f / 35,750 | 14,167 | 半血占多数（21.6k 已 <80%），甄别后逐屏绞杀；StreamingMarkdownText.kt（3,659 行/98.9%）是单件最大纯上游件 |
| data（含 repository） | 10f / 7,312 | 2,892 | 大半已半血化（P3.2c 改接成效），真重写量比名义小得多 |
| ui.settings | 23f / 9,558 | 6,956 | 主体屏已自有，剩余集中在 CheckUpdateSection/UpdateChecker 两件（1.8k）与杂项屏 |
| ui.sessions | 4f / 4,870 | 4,870（全部） | **整包纯上游**，可整刀绞杀 |
| ui.components | 12f / 2,615 | 1,800 | 甄别+重写混合 |
| service | 4f / 2,576 | 2,482 | 几乎纯上游（ToolOverlayController 868 行 99.7%） |
| ui.sandbox（三件幸存） | 3f / 2,201 | 2,201（全部） | **整包纯上游**，可整刀绞杀 |
| provider 残根 | 9f / 1,767 | 1,554 | 小件集中清 |
| auth | 3f / 1,162 | 1,162（全部） | **整包纯上游**（三件 OAuth/直连管理器），可整刀绞杀 |
| tools | 8f / 1,207 | 872 | AgentTools 已自有，其余小件 |
| ui.markdown | 1f / 939 | 939 | MarkdownText 单件纯上游 |
| agent（ToolLoopDetector 同族） | 1f / 586 | 586 | 单刀 |

实际基本自有、可移出绞杀名单的子系统：deeplink（两件 76.8%/66.7% 半血但
量小）、ui.navigation（AppNavigation 61.5%）、app 根（MinisApp 47.5%、
MainActivity 63.8%）、ui.theme（46.0%）——这四块全部落在半血档，无一件
≥80%，维持现状随邻近战役顺带即可。ui.sessions/ui.sandbox/auth 三块是
「名义混合、实则纯上游」的整刀候选，优先级应按此上调。

### P4 · 启动骨架五件套 — 红档 — 最后 — [ ]

MinisApp 初始化图（DB/Coil/ACRA/hydrate）、入口 Activity
（MainActivity/NovexLaunchActivity/NovexHomeActivity）、导航图、Room 装配、
provider 配置流。**这是崩溃线**：动之前 P0–P3 必须全部完成。

### P5 · 协议切换与法律动作 — [ ]

- [ ] 分发物内上游代码清零的审计复核（重跑本工具 + 抽查）
- [ ] LICENSE 更换（Apache-2.0 或专有，按公司法务拍板）、THIRD_PARTY 更新
- [ ] 软著登记、Novex 商标注册（GPL 不带走商标权，商标才是自己的）
- [ ] ~~proot GPL 组件的源码随附/指引页（若 D1 决定保留）~~ 已无对象：
      proot/talloc/rootfs 随沙箱退役整体移除，Android 分发物 GPL=0
      （见 THIRD_PARTY_LICENSES.md 宣告）

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

- **D1（公司法务）**：~~proot（GPL-2.0，独立进程 + 源码公开）可否作为最终
  分发物内的隔离组件保留？~~ **已随沙箱退役了结**——proot 全家（含
  talloc）与 Alpine rootfs 已于 P2.5/R2 移除，Android 分发物 GPL=0，
  无需再决。历史选项存档：可 → 源码指引；否 → 自研 ptrace 加载器
  （人年级）或砍沙箱功能。
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
| 2026-09-29 | 见进度 | R2+R3 完成：执行层（sandbox/ 主 11f + offload 26f、agent/shell 3f、offload 死件 8f）与界面（ui/terminal 全家、Mirror/Rootfs/挂载/环境变量屏）拆除；五枚 so + default_mount + prepare 脚本 + CI prepare 步骤清零；Manifest 摘 4 组件 6 权限 | 源码 -约 3.1 万行（git diff --stat）；BrowserUseManager/BrowserTabPool 因手动浏览器共管保留 |
| 2026-09-29 | #60（merge `fc50cf1`） | R2+R3 合并入 next | 155 文件 −29,968 行（+87） |
| 2026-09-29 | 本 PR（R4） | R4 收尾清剿：terminal 死码两件（MinisOpenUrlBroker/MinisUrlMarker + ChatScreen 死流）、a11y 死路 UI（SystemPermissions/OffloadPermission 卡与恢复对话框）+ accessibility/ 整包删除、ImageEditRoutingMatrixTest、OpenAIProvider 过时归因、toolPattern 去 browser_use、THIRD_PARTY GPL=0 出清、审计复跑 | 血统 191f/39,865 行（上游未动）、161f/100,520 行（上游改动）、379f/50,349 行（Novex 新增）；死代码 0f |
| 2026-09-29 | #63 | P3.1b 适配器换管：OpenAI 兼容中转（自定 base 纯 chat）聊天流量切自有 novex.model 传输；model-transport 小进化（附加头/reasoning 回放/音频块/附加参数，ChatCompletionClient +54/-12）；净眼退回两修（流桥 trySendBlocking 背压、token 上限键按主机选择）+ 流中 error 数字 code 矩阵；44 条新增单测（模块 7 + 适配器 28 + 工厂 9） | 上游 OpenAIProvider 及其测试零改动（P3.1d 处置）；model-transport 零上游依赖不变 |
| 2026-09-28 | #64 | P3.1c 原生协议换管：anthropic/gemini 聊天流量切自有 novex.model 传输（模块新增 AnthropicWire 322 行 + GeminiWire 208 行；适配器分线路由 +374/−68；ChatCompletionClient +30/−11、Stream +65/−15；工厂两分支改构造适配器；ThinkingRuleResolver.anthropicThinkingShape 委托 novex.model；ChatViewModel 的 isOAuth/enhancedCache 盖章改指适配器）；净眼退回一修三采纳（LAN 明文预检确定性 ProviderError、HTTP 错误体三方言解析、gemini 缺省 end_turn、台账口径）；三线全走自有传输；49 条新增单测（模块 anthropic 17 + gemini 14 + 适配器原生线 18，含 MockWebServer 端到端 2） | 上游 anthropic/ 两包（3f ~1.4k）与 gemini/（2f ~0.7k）聊天流量清零、工厂零引用（文件留存，P3.1d 删）；model-transport 零上游依赖不变 |
| 2026-09-29 | 本 PR（P3.1d） | 绞杀收尾：删上游 anthropic/（3f 1,430 行）+ gemini/（2f 683 行）+ OpenAIModelsApi（212 行）+ 两测试（1,035 行）；移植 ImagesClient（novex.model 生图）与 ModelsCatalog/ModelsCatalogApi（三方言模型目录）；适配器 imageDelegate 切自有传输、消费方改走 ImagesCapableProvider 接口；净眼挂账五条全清（a 工具结果图片补发/b 孤儿过一条 user 即失效/c usage 缓存计量/d 协议防呆/e OAuth 前缀注入）；Anthropic*/Gemini* 主代码引用清零（仅注释墓碑）| 血统：上游未动 191f/39,865 → 186f/37,943（−1,922 行），上游改动 161f/100,520 → 160f/100,153；Novex 新增 379f/50,349 → 382f/51,691（+ImagesClient/ModelsCatalog/ImagesCapableProvider/ModelsCatalogApi）；provider.openai 留存 2f/3,920 行（活码，P3.1e）；model-transport 零上游依赖不变 |
| 2026-09-28 | 本 PR（P3.1e） | OpenAIProvider 终局退役：删上游 provider/openai/ 整包（2f 3,920 行）+ 旧测试 3 件；novex.model 新增 ResponsesWire（请求编码+SSE 事件族+codex 生图流）与 ModelEndpoint tokenHeader/permitQueryParams、ImagesClient 端点覆盖；适配器九类路由（Codex-OAuth/官方直连/useResponsesAPI/Azure/前尘回退/LAN 明文/OpenRouter/xAI/Kimi）+ 动态 OAuth bearer + 前尘回退进程粘性 + OpenRouter 附加头与 anthropic cache_control；imageDegradedModels 迁 app 侧 ImageDegradationLearning；P3.1d 净眼三建议采纳；存量行为测试 10 件换管续跑、模块+适配器+工厂新增 40+ 用例 | 血统：上游未动 186f/37,943 → 179f/36,228（openai 整包归零 + 墓碑注释改动使 4 个文件移入「上游改动」桶），上游改动 160f/100,153 → 163f/97,807；Novex 新增 382f/51,691 → 383f/52,065；model-transport 新增 ResponsesWire（+~470 行）零上游依赖不变 |
| 2026-09-28 | 本 PR（P3.2） | provider 剩余小件绞杀：删上游 provider/voice（4f）+ provider/thinking（4f）+ provider/image/ImageModelCatalog（零血统件机械搬家为 novex.android.models.ImageGenerationModels）+ data/model/VoiceProviderTemplate（自有 VoiceVendorTemplates 重写，模板数据逐字节一致）+ LargeAllocProbe/JsonExt 死码（123 行）；自有 novex.android.voice 五件（VoiceWire 值类型/VoiceClient 引擎+OpenAI 方言/VoiceClients 十二厂商/VoiceClientFactory 标记路由表/VoiceVendorTemplates）与 novex.android.thinking 四件（ThinkingWire 词表+内聚编解码/ThinkingContract/ThinkingContractResolver 座次表+决策落笔分离/ThinkingContractCoding）；DB 实体/DAO/仓库/配置集合件随消费者更名（Room 表列名不变）；净眼 PR#66 七条全清（③④代码修复 + ①②⑦测试补钉 + ⑤⑥docs 记档）；净眼退回后九件真重写（结构/分解/控制流/注释/文案全部重做，协议事实逐字节保留；剥注释+归一化标识符的语句相似度从 96-99% 降至 10.8-35.8%、均值 23.3%，对照 P3.1c GeminiWire 同口径 ~7.5%），退回附带补钉：讯飞签名 URL/WS 收流确定性测试（注入假 socket 零网络）、MiniMax legacy b64 外壳兜底用例、①Codex 工厂手工 bearer 断言；假服务器 parity 测试钉 Doubao TTS/ASR 与 OpenAI TTS 等厂商；思考金表/回归/合并/xAI 四件测试原样通过| 血统：上游未动 179f/36,228 → 163f/30,167，上游改动 163f/97,807 → 168f/100,749（含 改名换路径的血统件落 Novex 新增桶（其中 ThinkingContractsCollection 经净眼三轮揪出为改名直译，已真重写）），Novex 新增 383f/52,065 → 394f/54,690；死代码 2f/123 行 → 0f；grep 'VoiceProvider|ImageModelCatalog|ThinkingRule' src/android/app/src 归零；model-transport 零 com.openminis 不变 |
| 2026-09-28 | 本 PR（P3.2b） | Room 数据层绞杀：删 data/db（27f）+ data/model（18f）整包，自有 novex.android.data 三十件 = 库件 4（NovexMainDatabase+MainDatabaseMigrations 38 步迁移重排为 step 注册表/NovexProviderDatabase/WebShortcutStore）+ chat 6（DAO 按读写面拆并：SessionReads/SessionWrites/FolderReads/FolderWrites/MessageDao/MarkerDao+ChatDao 门面）+ cards 6（含冻结面集中文件 CardTables）+ provider 3（快照式读写 ProviderStoreDao+行声明+编解码）+ model 11；**schema 冻结证据：Room KSP 生成物 createAllTables DDL 逐句一致（createAllTables 59 条；89 含 DROP/INSERT 口径）、provider 库 9/9 一致，identityHash 主库 6397ab3a…/8567eaad… 副库 394a39eb…/4d415b94… 重写前后字节相同，老用户库无损**；DAO 查询全部等价改形（表别名/谓词重排/截断走共享 RawQuery 执行器），Robolectric 钉 sessions/messages/compact_markers/provider_instances/provider_thinking_rules 表列名与插入/分支切换/compact marker/provider 配置/思考规则读写十用例；全量单测 1,585 条通过；死码 AgentTypes 族（AgentStreamEvent/AgentMessage/sanitizeToolId，HEAD 上已零引用）删除；相似度：逻辑件 21 件全部 <40%（最大 26.4%、均值 7.4%；冻结面除外——迁移 DDL、Room 列声明、parts_json 编解码、LLM 线协议 DTO、provider JSON 镜像字段名） | 血统：上游未动 163f/30,167 → 128f/24,366，上游改动 168f/100,749 → 173f/102,448，Novex 新增 394f/54,690 → 409f/58,456；死代码 0f；grep 'com.openminis.app.data.db\|com.openminis.app.data.model' src 归零；data/repository 仅改接（import/符号），真重写留 P3.2c |
| 2026-09-30 | 本 PR（P3.3 裁军） | 产品范围裁军（用户裁决 A+B+C 全砍）：语音全家（speech/ 26f 7,733 行 + novex.android.voice/ 5f 1,509 行 + ui/chat/voice + 影子语音屏与 voice 设置项）、内置浏览器全家（browser/ 8f 3,901 行 + ui/browser/ 6f 2,012 行 + ui/preview/ 4f 1,367 行 + UrlPreviewSheet，链接点击改 ACTION_VIEW 外跳、会话内 HTML 走 FilePreviewScreen WebView、音视频走 FileProvider 外跳）、WebApp（webapp/ 5f 1,293 行 + Manifest 摘 WebAppActivity/OPEN_WEBAPP）、Shizuku/特权后端（offload/ 3f 910 行 + Offload/Shizuku/SystemPermissions 权限屏 + dev.rikka.shizuku 依赖出清 + Manifest 摘 ShizukuProvider 与 API_V23）、定时任务（scheduled/ 5f + ui/scheduled/ 4f 共 2,499 行 + Manifest 摘 AlarmReceiver + SET_ALARM/SCHEDULE_EXACT_ALARM/RECEIVE_BOOT_COMPLETED）、调试面板（debug/ 12f 5,498 行；ACRA 与 AppLogger 保留）、MCP 残件（mcp/ 4f + MCPRepository + 三处 UI 面 + ContentPaths mcp-servers 桶）、内嵌媒体播放器（ui/media 653 行）、minis-config 体系（config/ 20f 4,785 行 + ConfigAudit/ConfigConfirm 屏；自定义思考规则机器随葬：CUSTOM 席位/读写 DAO/ThinkingContractsCollection 删除，前尘预设规则收编内置席 qianchen-relay-gemini，Room 表 provider_thinking_rules 保留 schema 冻结）；死参数/死路由/deep-link 动作清扫（SHADOW_VOICE/PERMISSIONS/SHIZUKU/SYSTEM_PERMISSIONS/SCHEDULED_TASKS/MCP 路由、OpenHtmlPreview/NewVoiceChat/OpenAlarmList/OpenPermissionSettings 动作、settings/sessions 死参数、voice_chat 桌面捷径）；孤儿串 695 键出清（七语言文件共 -3,903 条）；裁军孤儿（shared 分词四件 + BringIntoViewOnFocus）随葬 | 血统：上游未动 128f/24,366 → 60f/11,827，上游改动 173f/102,448 → 118f/74,802，Novex 新增 409f/58,456 → 402f/56,627（编辑过的原上游未动件移入改动桶）；随葬测试（config/debug/mcp/offload/speech/自定义思考规则回路 + shared 分词件）删除；死代码 0f；砍单符号 grep 仅墓碑注释命中；RECORD_AUDIO/SET_ALARM 零残留 |
| 2026-09-30 | 本 PR（P3.4） | 审计工具第四维：混合件与上游基线同路径文件的文本相似度（剥注释+空白归一+大小写折叠、行级 SequenceMatcher autojunk 关；基线 blob 单进程 cat-file --batch 流式取件逐块即弃；--json 新增 mixed_similarity 键，既有键不动）；3 件人工核对（100%/3.1%/46% 全部与 git diff 吻合）；净眼 PR#70 六建议顺手清（qianchen-relay 内置席 resolver 级测试、FilePreviewScreen 外跳按钮文案走 R.string 八语言包、browser_use 外跳措辞与日志改口、THIRD_PARTY AndroidX 汇总行去 webkit、ChatLinkDiag 逐链接 Log.w 清除、MinisApp 一次性清扫已删 receiver 的旧定时 alarm）；docs 补「P3.4 混合件余量量化」小节与战线重排 | 改动桶 118f/74,890 相似度三档：≥80% 86f/41,497（55.4%）、40-80% 27f/32,905（43.9%）、<40% 5f/488（0.7%）；全仓上游血统存量 = 41,497 + 未动桶 11,827 = **53,324 行（146 件）**，比名义合计少 38.5%；ui.sessions/ui.sandbox/auth 三块「名义混合实则纯上游」升为整刀候选，deeplink/navigation/app根/theme 全落半血档移出绞杀名单 |

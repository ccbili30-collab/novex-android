# 架构质量审计（Architecture Quality Audit）

> 审计基线：worktree `diag-next`，分支 `docs/ui-handoff`（HEAD 88b0c1a，基点 next@52deca2）。
> 审计日期：2026-09-30。审计员立场：全新眼睛、只挑刺。血统审计（GPL 遗留判定）不在本文件范围，见 `docs/UPSTREAM_EXIT_PLAN.md`。
> 处置状态（2026-09-30 修复轮注记）：D1/D2 已落地，D12 排期表与 D14 修订见 `docs/UPSTREAM_EXIT_PLAN.md`「P3.6 门面拆除排期」及进度日志；本文件其余为审计时点快照，数字不随修复回改。
> 方法：只读静态审计——全量 import 图扫描（包级边 + 逐文件证据）、门面清点（typealias / 转发对象 / 正典留位 / Manifest 壳 / 编排门面五类逐件过）、测试目录全列、卫生模式 grep（TODO/日志/协程/JSON 栈/异常吞咽）。未运行编译与测试。
> 体量背景：旧世界 `com.openminis` 389 件 / 105,219 行；新世界 `novex.*`（app 内 + 各库模块）258 件 / 38,835 行；`novex.android.*` 19 个子包 + 根散件共 153 件约 27,900 行。

## 总评

**不会立刻崩，但有三处正在固化的结构债。** 分层方向本身干净（无环、transport 未穿 DAO、novex.model 零 app 依赖），门面纪律罕见地好（全部纯转发、零积肉、零 TODO）。真正的问题：

1. **`novex.android.data` 不是自足的数据层**——`NovexMainDatabase` 仍从旧世界 `com.openminis.app.data.character.*` 进口 17 个 Room 实体/DAO/转换器。新数据层是旧 schema 的再包装，这是全仓最大的一根绞杀缝，也是最容易被误读为"已完成迁移"的地方。
2. **runtime / ui / adapter 三包对旧世界的依赖不是缝，是日常通勤**——新代码把旧仓库门面、旧主题、旧路由当一等公民调用，没有冻结面保护。绞杀完成判定会因此持续后延。
3. **根散件与微包**：`novex.android` 包根趴着 20 个文件（2775 行 UI 页与 ViewModel），5 个单文件包，`models` 与 `data.model` 撞名。现在挪是目录手术，半年后挪是考古。

严重度分级：P0 = 崩塌级（不处理必然变屎山）；P1 = 恶化级（每次改动都在付利息）；P2 = 卫生级（记账即可）。

---

## 1. 分层依赖规则

### 1.1 novex.model（model-transport 模块）零依赖 app —— 验证通过

证据（`src/android/model-transport/build.gradle.kts` + 全部主源 import 扫描）：

- 依赖声明只有 `project(":conversation-core")`、`org.json:json:20231013`、junit。主源 8 件 import 全集：`java.io/net/util.*`、`novex.conversation.*`、`org.json.*`。**零 `com.openminis`、零 `novex.android`、零 android-framework 引用**（kotlin("jvm") 模块，想依赖也依赖不上——模块边界机器强制，好）。
- 测试 10 件含 wire 协议逐方言测试与 `AnonymousProbe`（真实网络探针，`gradle anonymousProbe` 任务独立挂载）。
- **P2｜文档失真**：`docs/ARCHITECTURE.md` 模块表写 model-transport "← conversation-runtime"，实际方向相反（`conversation-runtime/build.gradle.kts:7` 依赖 model-transport；model-transport 只依赖 conversation-core）。真实隔离比文档写的更好，但按文档做规划的人会得出错误结论。改模块边界必须同步更新——这是该文档自己立的规矩。

### 1.2 novex.android.* 包间依赖图（实测，import 计数）

```
                    ┌─ novex.model（独立模块，不在此图）
                    │
 ui (42件) ──1──► adapter (25件) ──19──► data (30件)
 root散件(20件) ──72──► ui
 repo (11件) ──26──► data
 transport (1件) ──11──► data.model   [仅 DTO，无 DAO]
              ──2──► thinking (3件) ──2──► data
 models (2件) ──3──► data
 authkit (12件) ──3──► data ──1──► logkit
 sharekit (7件) ──2──► data ──5──► logkit
 soul ──1──► logkit      powerguard ──1──► logkit
 [runtime, crashguard, navlink, netwatch, vault, localekit：零 novex.android 包间依赖]
```

判定：

- **无环**（包级）。方向大体符合 data ← repo/adapter ← 上层。
- **transport 没有摸 DAO**：`NovexTransportProvider.kt` 对 data 的 11 个 import 全部是 `data.model.*` 纯 DTO（LLMMessage/LLMStreamChunk/…）。无跨层直穿。**通过**。
- **P1｜repo 与 adapter 职责重叠**：两者都直接吃 data（26 vs 19 个 import），repo 装的是 ProviderConfigStore/SkillStorage 等旧仓库的内脏，adapter 装的是 novex.core 工作区到 Room 的端口适配（RoomCardRevisionAdapter 等 10 件 Room*Adapter 是六边形端口实现，吃 DAO 名正言顺）。但对外看，"改 provider 持久化"和"改卡片修订存取"要进两个名字毫无提示性的包，新人无法从包名推断边界（详见 §3）。
- **P1｜runtime 完全反向依赖旧世界**：9 件里零个 novex.android 包间 import，却 import 了 `AgentForegroundService`、`ChatRepository`、`BackgroundSettingsRepository`、`SessionBadgeStore`（正典）、`CrashFrequencyDetector`、`R`、`MinisApp`。runtime 是"新家"，但每天回旧家吃饭。其中 `ChatRepository`/`BackgroundSettingsRepository` 是编排门面（本身又调 novex.data/repo），形成 **runtime → 旧仓库门面 → novex.data/repo 的三跳链**——这是新包之间的事实耦合，只是借道旧世界，import 图看不见。同理 ui → `NovenColors`/`novexEditorBackAction`/`ContentModuleDocument` 等 30+ 旧符号。

### 1.3 新包对旧世界（com.openminis.app）的依赖面清单

按"必要绞杀缝 / 可改接的漏网"分桶（逐 import 实测）：

**A. 必要缝（有冻结面文档背书，绞杀期合法）：**

| 缝 | 位置 | 冻结原因 |
|---|---|---|
| `LLMProvider` 接口 + `ProviderFactory` 构造点 | transport、旧 ProviderFactory.kt:46/61/91 | 调用面零改动的绞杀适配器，文件头明示"本文件是绞杀缝" |
| 正典嵌套类型（Vendor、SessionBadgeState、ChatAction/PendingChatInput、DeepLinkAction、SoulBodyLimitCheck、PendingShare.Item.Kind、ShareHandoffPolicy.Outcome） | powerguard/runtime/navlink/soul/sharekit 反向引用 | typealias 无法转发嵌套类型，UI 以全限定名钉死 |
| Room character schema（17 个 Entity/Dao/Converter） | data/NovexMainDatabase.kt:8-24 | 主库 schema 是用户数据事实面，迁移即换库文件 |
| `R` 资源、`MinisApp`、`BuildConfig` | ui/runtime/sharekit/crashguard/authkit | 应用身份与资源表在 app 模块，属结构性 |
| `AgentForegroundService`（Manifest 壳） | runtime/AgentKeepAlive 等 | 组件名 + mediaPlayback 前台类型是系统已认得的冻结面 |
| 旧编排门面（ProviderRepository 756 行 / ChatRepository 600 / SkillRepository 708） | repo 内脏被它们消费 | 对外 API 形状钉死，调用方遍布全仓 |

**B. 可改接的漏网（无冻结面背书，属新代码顺手抓了旧符号）：**

| 漏网 | 位置 | 改接方向 |
|---|---|---|
| `AppLogger` | transport(7处)/models(4处) | AppLogger 自己就是 logkit.RunLog 的门面；新包应直接 import `novex.android.logkit`，绕掉旧路径 |
| `MinisUserAgent`、`ImageBudget`、`ImageDegradationLearning`、`failOnSilentEmptyCompletion` | transport | 这四件是纯逻辑/纯配置，无冻结面，应搬进 novex.android（transport 或 data.model） |
| `ClaudeOAuthManager` | transport:3 | typealias 门面可穿透引用（`ClaudeLoginFlow` 本体在 authkit），transport 应 import authkit |
| `KimiDeviceFlow` | authkit/KimiLoginFlow:6 | P3.5c 新写逻辑却停在旧路径（仅因测试钉名）；应搬入 authkit 并改测试包 |
| `KEY_LANGUAGE`/`PREF_APPEARANCE` | localekit/LocaleOverride:7-8 | 新 locale 包 import 旧设置页的 prefs 常量；常量应下沉 localekit 自己持有 |
| `ChatRepository`、`BackgroundSettingsRepository` | runtime/LiveSessionHub 等 | runtime 想要的是会话活跃度数据源，不是旧仓库门面；应从 repo 层开口 |
| `NovenColors`、`LocalChatPalette`、`novexEditorBackAction`、`Routes` | root 散件、ui | 主题/导航符号散在旧世界，Novex UI 套件应自持 |
| `NovexWorldbookTools`、`ContentModuleRepository`、`CharacterCatalogRepository` 等 | adapter（13+ 处） | 大头：adapter 对旧 repository 层的直连，绞杀期可接受但需登记 |
| `stripAgentAttachmentMetadata` 等顶层函数 | adapter、sharekit | 附件元数据工具被两个新包共用，应搬 data 或共享包 |

### 1.4 依赖图结论

新包间无环、无 DAO 直穿、模块边界机器强制——**这是这次重写最扎实的部分**。风险全在"新→旧"的日常通勤面（B 桶 9 项）与 data 的 schema 进口。若 B 桶不定期清，绞杀完成判定（旧路径可删）将永远差一口气。

---

## 2. 门面层健康度

五风格并存（一致性代价见 2.6），逐件清点如下。**总体判定：纪律极好，零积肉**——所有 typealias 文件 ≤37 行且纯转发；正典留位件只含冻结形状 + 转发；两处 Manifest 壳行为逻辑全在实现侧。

### 2.1 typealias 门面（16 件）

| 旧路径符号 | 新本体 | 行数 | 剩余消费者 | 可拆条件 |
|---|---|---|---|---|
| OAuthManager | authkit.VendorLoginFlow | 14 | 7 件 | 调用点改 import |
| ClaudeOAuthManager | authkit.ClaudeLoginFlow | 12 | 4 | 同上（transport 也在用） |
| OpenAIOAuthManager(+异常别名) | authkit.CodexLoginFlow | 15 | — | 同上 |
| GeminiOAuthManager / KimiOAuthManager / XAIOAuthManager | authkit.*LoginFlow | 12-14 | — | 同上 |
| OAuthCallbackServer | authkit.LoopbackReceiver | 11 | — | 同上 |
| NetworkMonitor | netwatch.LinkMonitor | 13 | 2 | 同上 |
| ModelsDevApi | models.ModelsDevCatalog | 15 | 8 | 同上 |
| SoulStore/SoulMetadata/SoulFile/SoulMDParser（4 别名同文件） | soul 三件 | 37 | 11 | 同上（消费面最大） |
| LocaleWrap | localekit.LocaleOverride | 12 | 4 | 同上 |
| SharedShareStore / ChatExporter / ShareCoordinator | sharekit 三件 | 各12 | 3/2/4 | 同上 |
| DeepLinkHandler | navlink.LinkParser | 30 | 3 | 同上（同文件还有正典 DeepLinkAction，见 2.2） |
| EncryptedPrefsFactory | vault.SelfHealingPrefs | 13 | 4 | 同上 |

### 2.2 正典留位件（嵌套类型钉死，实现侧反向引用）

| 旧路径件 | 钉住的形状 | 剩余消费者 | 可拆条件 |
|---|---|---|---|
| power/PowerOptimizationManager | `Vendor` 枚举（OEM 名单冻结） | 2 | 设置页改用 `OemPowerGates.Vendor`（typealias 已备好）后删 |
| service/SessionBadgeStore | `SessionBadgeState` 枚举 + prefs 键契约 | 4 | UI 改引 novex.android.runtime.SessionBadges 后删 |
| deeplink/DeepLinkCoordinator | `ChatAction`/`PendingChatInput` | 7 | MainActivity/AppNavigation 改引 PendingLinkFx 同名类型 |
| deeplink/DeepLinkHandler.kt（同文件） | `DeepLinkAction` 密封族 | 3 | 同上 |
| agent/SoulStore.kt（同文件） | `SoulBodyLimitCheck` 密封族 + `migrateLegacyAssistantName` | 11 | 设置页 when 分支改引 soul 包 |
| share/PendingShare、share/ShareHandoffPolicy | `PendingShare.Item.Kind`、`Outcome` | 7/1 | sharekit 内部类型升顶层后改引 |

### 2.3 纯转发对象门面（无正典形状，只保 API 名）

`SessionActivityTracker`（14+ 成员逐个转发 LiveSessionHub）、`AppLogger`（52 行，转 logkit.RunLog）、`BackgroundTaskNotifier`（35）、`SessionConcurrencyManager`（24，转 StreamSlotLimiter）、`DynamicIslandSupport`（20，转 LiveUpdatesProbe）、`CrashFrequencyDetector`（40，转 crashguard 两件）。全部纯转发、带冻结面注释。可拆条件均为"消费点改 import"，无形状障碍——**这批是门面里最廉价可拆的，建议排在正典件之前拆**。

### 2.4 Manifest/组件壳

`AgentForegroundService`（70 行，生命周期回调递给 AgentKeepAlive）、`OAuthRedirectActivity`（19 行，转 LoopbackRedirectRelay）、`NovexLaunchActivity`/`NovexHomeActivity`（启动链）。组件名即冻结面，只能随应用身份一起处理，不属于代码债。

### 2.5 编排门面（有肉，且是有意留的肉）

`ProviderRepository`(756)/`ChatRepository`(600)/`SkillRepository`(708)——不是转发件：各自持有对外 API 形状 + 编排纪律（锁+装载闸+工作副本；分支图一致性；状态流发布），机制全数下沉 novex.android.repo / data。**这是全部门面里唯一会继续长肉的地方**：新需求天然往这三件里加方法（它们是"稳定的公共 API"）。需要一条规矩：编排门面只准减不准增，新能力一律先落 novex 包再转发。目前没见违反，但也没有成文的防增机制。

### 2.6 一致性代价与判定

- **P2｜五风格并存的学习成本**：同一个"旧路径保持可编译"目标，typealias（最便宜）、纯转发对象（次之）、正典留位（嵌套类型被迫）、Manifest 壳（组件名被迫）、编排门面（API 面被迫）。选型逻辑其实清晰（能不能 typealias → 是不是组件 → 是不是 API 聚合点），但没有一份文档把"新门面选型决策树"写下来；下一个人做 P3.6 时会重新发明一遍，且可能选错。
- **P1｜可拆条件全部未排期**：16+ 件门面每件都标了"等谁改接"，但没有一份按消费者数排序的拆除顺序表。消费者数已实测（SoulStore 11、ModelsDevApi 8、OAuthManager/DeepLinkCoordinator/PendingShare 各 7…）。门面本身不烂，烂的是"永远差最后一步"：只要消费者不改，这些旧路径文件就以"活代码"身份永续参与血统审计、R8 keep 规则、全局重命名成本。
- **积肉检查**：通过。所有 typealias 文件纯转发；正典件只含冻结形状；未发现应纯转发却长出业务逻辑的文件。`NovexEdgeRibbon.kt`(225 行，旧路径) 引用 NovexColors 属旧 UI 存量，非门面。

---

## 3. 包结构合理性

### 3.1 现状盘点（文件数/行数）

| 包 | 件 | 行 | 内容 | 判定 |
|---|---|---|---|---|
| root 散件 | 20 | 2,775 | 卡片 UI 页 + ViewModel + 挂载表 + Markdown 渲染 | **错位**：15 件是 UI，趴在包根 |
| ui | 42 | 6,422 | Novex 设计系统 + 卡片编辑区段 | 健康 |
| adapter | 25 | 2,067 | novex.core 工作区 ↔ Room 端口适配 | 健康，名字见 3.3 |
| data | 30 | 4,229 | 两库 + DAO + DTO + 46 个迁移 | 健康，但 schema 进口（§1.3A） |
| repo | 11 | 2,514 | 旧三仓库的内脏（store/transfer/refresh/seed） | 健康，名字见 3.3 |
| authkit | 12 | 2,125 | 五家 OAuth + PKCE + 凭据保险箱 + 刷新闸 | 健康 |
| runtime | 9 | 2,236 | 前台服务逻辑 + 悬浮胶囊 + 会话中枢 + 并发闸 + 通知 + 探针 | **杂**：三种不相干职责 |
| sharekit | 7 | 823 | 分享入站/出站/导出/闸 | 健康 |
| transport | 1 | 1,379 | 四线协议适配器 | **单文件包**，见 §5.1 |
| thinking | 3 | 759 | 思考契约解析 | 健康 |
| crashguard | 2 | 693 | 崩溃风暴 + 崩溃外发 | 健康 |
| models | 2 | 589 | models.dev 目录 + 生图模型表 | **撞名** data.model |
| soul / navlink / logkit | 2/2/2 | 321/151/388 | 人格/深链/日志 | 健康 |
| netwatch / powerguard / vault / localekit | 1/1/1/1 | 132/137/77/60 | 单件包 | **过碎** |

### 3.2 判定与重组建议（现在动 vs 半年后动）

- **P1｜根散件必须先动**：`novex.android` 包根的 20 件是"无处安放"的化石层——CardPages/CardOpeningPage 等 15 件 UI 页应进 `ui`（或新建 `ui.cards`），CardSessionModel/LibraryModel/FileTransferModel 三个 ViewModel 应有 `model` 或 `vm` 归处，ContentPaths/ReadingViewPrefs/LiveMarkdown 是工具。它们已被 72 处 import 依赖，每拖一个月，搬移的爆炸半径就大一圈。**这是本审计里唯一"现在动便宜、半年后动就是屎山手术"的确诊项。**
- **P1｜runtime 三合一拆解**：`AgentKeepAlive`+`KeepAliveStatusLine`+`TaskDoneNotifier`（前台服务族）、`OverlayCapsule`+`OverlayPaintViews`+`LiveUpdatesProbe`（悬浮胶囊族）、`LiveSessionHub`+`SessionBadges`+`StreamSlotLimiter`（会话状态族）——三族零内部耦合（import 实测互不引用），放一个包纯粹是"都跟运行期有关"。建议拆 `keepalive` / `overlay` / `session` 三包，或至少在包 README 里写明三族分区。不拆的代价：SessionBadges 这种纯状态件永远被人当成"服务相关"不敢动。
- **P2｜五个单文件包**：netwatch/powerguard/vault/localekit 各 60-137 行。单文件包不是罪（有独立冻结面注释、有明确单一职责），但 5 个加起来 406 行、4 个目录、4 套包文档。可合并为一个 `platform`（或 `devicekit`）包分四文件；也可保留——**判定：可保可并，但必须二选一写进结构规矩，防止第 6、7 个单件包继续冒出来**。
- **P2｜models 撞名 data.model**：`novex.android.models`（models.dev 目录缓存）与 `novex.android.data.model`（LLM DTO）语义完全不同。建议前者改名 `modelcatalog`，与 `ModelsDevCatalog` 本名对齐。
- **P2｜repo vs adapter vs data 三角**：对外语义建议写进 ARCHITECTURE.md 一句话规矩——data = schema 与 DTO（怎么存），repo = 旧仓库内脏（存取机制），adapter = novex.core 工作区端口（领域↔存储翻译）。名字本身不必改（改名的爆炸半径大于收益），但边界必须成文，否则新件会继续随机落点。

### 3.3 不建议动的

ui 大包（42 件）虽大但内聚（全是 Novex 视觉系统 + 卡片编辑区段，有 NovexComponentArchitectureTest 钉架构契约）；data 大而线性（迁移文件天然只增）；sharekit/authkit 粒度恰好。**结构问题集中在根散件、runtime、微包三处，不要为重组而重组。**

---

## 4. 测试覆盖地图

### 4.1 覆盖表（直接测试 = 同包目录下钉其行为的测试件）

| 包 | 直接测试 | 间接覆盖 | 缺口判定 |
|---|---|---|---|
| transport | 4 件（46+21+13+2 个 @Test，TransportCall 桩钉时序不打网） | — | **厚实**，四线协议+回退+错误分类都有 |
| ui | 13 件（设计令牌/几何/选择策略/图标资产） | — | 良好；渲染器 NovexContentModuleRenderer(558行) 仅经 presentation 间接触及 |
| data | 2 件 schema 测试 | novex.core 的 40+ 持久化测试全程打真库（Robolectric） | 行为面够，**迁移链 46 步无逐版本断言**（P1 见下） |
| model-transport 模块 | 10 件（含三 wire 方言逐测） | — | **厚实** |
| runtime | 3 件（KeepAliveStatusLine / LiveSessionHubServiceSwitch / OverlayCapsuleLifecycle） | — | AgentKeepAlive(394行) 核心循环无直接测试 |
| repo | 1 件 | 旧仓库测试（SkillRepositoryBundledAssetSkillTest、ProviderRepositoryRoundTrip、ChatRepositoryTest 等经编排门面打到内脏） | **10/11 件无直接测试**，全靠旧门面间接覆盖（见 4.3） |
| authkit | 1 件（仅 OAuth 过期逻辑） | — | **薄**：五家 LoginFlow 的 PKCE/state/回调路径零直接测试（RefreshGate、CredentialVault 无测试） |
| crashguard | 1 件（CrashBurstGuard 风暴窗） | — | CrashShareFlow(496行) 零覆盖 |
| thinking | 1 件（仅 xAI 空档位） | transport 测试间接 | ThinkingContractResolver(465行) 的厂商规则矩阵大部分无直接断言 |
| soul/models/navlink/logkit | 各 1-2 件 | — | 够（件少且行为窄） |
| **sharekit** | **0** | 旧 share 测试是否触达未验 | **盲区**：7 件 823 行（导入闸/交接梯/zip 导出）零直接测试 |
| **vault/netwatch/powerguard/localekit** | **0** | 0 | 盲区，但件小（77-137 行）；SelfHealingPrefs 是自愈加密存储，值得一测 |
| 根散件 | 2（ContentPaths/ReadingViewPrefs） | Noven UI 测试间接触及 | CardSessionModel(424行) 44 处协程调度无直接测试 |

### 4.2 测试质量抽查

- **无空壳测试**：抽查 NovexTransportProviderTest（ScriptedCall 桩 + CountDownLatch 钉并发时序）、NovexVisualSystemTest（断言设计令牌一致性）、CrashBurstWindowTest（真文件风暴扫描）均为行为测试，无"实例化即断言非空"的自说自话。
- **测试位置债（P2）**：`novex/android/repo/SkillRepositoryLifecycleTest` 实际被测对象是旧路径 `com.openminis.app.data.repository.SkillRepository`（编排门面）——测试放新包目录、测旧路径类。行为上没错（门面就是入口），但按包找测试会扑空。

### 4.3 关键缺口（按风险排序）

1. **P1｜repo 内脏全靠间接覆盖**：ProviderConfigStore(348行，双写+三方对账+锁) 是供应商配置的唯一事实面，其「DB/镜像/同步哈希对账决策树」只被 ProviderRepositoryRoundTrip 顺带扫过；对账的失败分支（镜像损毁、DB 降级读失败、拒绝空配置覆盖）无直接断言。这块出 bug 是静默丢配置级别。
2. **P1｜authkit 只有到期逻辑被钉住**：五家 OAuth 的 PKCE 材料、loopback 回调、code 交换错误分类零直接测试；这些是只能真机回归的路径，CI 层面失守。
3. **P1｜迁移链无链式回归**：MainDatabaseMigrations 46 步，schema 测试只钉当前版本形状；"v(n-3) 用户升级到 v(n)"的链路无 MigrationTestHelper 式断言。旧安装用户升级翻车是发版事故级。
4. **P2｜sharekit/vault 零覆盖**：入站分享丢数据、自愈 prefs 误 heals，都是用户可感知故障。

---

## 5. 代码卫生

### 5.1 超长文件（新件 >800 行共 2 件）

| 文件 | 行数 | 判定 |
|---|---|---|
| `transport/NovexTransportProvider.kt` | 1,379 | **P1 应拆**：单文件承载 4 条 wire 线（chat/anthropic/gemini/responses）× 聊天/生图/回退。构造参数 15 个。按线协议拆 4 个 request-mapper + 1 个公共调度壳，每件 300 行级。不拆的代价：每加一家供应商在此文件做减法手术，冲突热区。 |
| `ui/NovexIcons.kt` | 917 | **不拆**：`scripts/generate_novex_icons.py` 生成的资产目录（Phosphor 钉版），文件头已声明。生成物长是常态。 |
| （次级关注 400-700 行 8 件） | — | MainDatabaseMigrations(695，线性迁移天然只增)、OverlayCapsule(599)、NovexContentModuleRenderer(558)、CrashShareFlow(496)、ThinkingContractResolver(465)、CardSessionModel(424)、ModelsDevCatalog(416)、AgentKeepAlive(394)：均低于拆分紧迫线，随功能迭代自然分件即可。 |

### 5.2 重复模式

- **P1｜JSON 两栈并行**：`data`+`repo` 用 kotlinx.serialization（6 件，codec 化、配置统一 Json 实例）；`adapter`(12件)/`authkit`(7)/`models`(2)/`sharekit`(4)/`thinking`(2)/`transport`(1) 手搓 org.json，全仓 opt* 走位约 257 处。无共享解码助手。每个手搓点都是一次「键名拼错→静默 null」的机会。建议：新代码一律 kotlinx（或至少 logkit 式统一 opt 辅助函数），org.json 只许出现在 wire 边界（网络响应解析）。
- **P1｜日志三种写法并存**：28 个新包文件 import `android.util.Log` 直打（authkit 全员、runtime 全员、repo 全员、ContentPaths…），transport/models 走 `AppLogger`（旧路径门面→logkit.RunLog），logkit 自己是第三套。三个 TAG 约定、两套开关语义。建议定一条：新包只准 `logkit.RunLog`（或 AppLogger 待其拆除后），android.util.Log 列为 review 拦截项。
- **P2｜重试逻辑各写各的**：CodexLoginFlow.exchangeJsonWithRetry（自带重试）、transport 的 responses 回退粘性、ProviderConfigStore 的装载重试，无共享 retry/backoff 工具。量小（各 10-20 行），暂可忍，第三处出现时必须收敛。
- **错误分类是亮点不是债**：`data/model/LlmErrors.kt` 的 LLMError 分类（isRetryable）被 transport/repo 共用，没有各写各的——这是对的。

### 5.3 TODO/FIXME/死代码

- novex.android 全域 TODO/FIXME/XXX：**0**。注释掉的代码块（// val/fun/if 前缀扫描）：**0**。voice 包确已删除（无目录、无残留 import）。**干净，全仓罕见的干净。**

### 5.4 线程/协程一致性

- **P1｜GlobalScope 两处**：`authkit/VendorLoginFlow.kt:150`、`authkit/XaiLoginFlow.kt:338`。OAuth 回调竞态窗口里用 GlobalScope.launch，进程级逃逸、无取消路径。改 CoroutineScope(SupervisorJob()+IO) 注入或 nonCancellable 语义化。
- **P1｜runBlocking 三处**：`repo/ProviderConfigStore.kt:153/159/296`。装载/持久化路径上的同步桥——若被主线程调用即冻结 ANR 面（装载闸设计为首读阻塞，需在 KDoc 明示"禁止主线程调用"或改挂起签名）。
- 其余：Dispatchers.IO 90 处 vs Main 3 处，方向正确；CardSessionModel 44 处调度点集中在 ViewModel（viewModelScope 语境，正常）。无 newSingleThreadContext、无 runBlocking 风暴。

---

## 6. 技术债登记簿

> 格式：编号｜优先级｜是什么/在哪/什么时候还/不还会怎样。P0=本季度内，P1=下次触碰该区域时必须顺带还，P2=记账观察。

| # | 级 | 债 | 在哪 | 还债时点 | 不还会怎样 |
|---|---|---|---|---|---|
| D1 | **P0** | 根散件 20 件无包归属（15 件 UI 页 + 3 ViewModel + 工具） | `novex/android/*.kt`（72 处入向 import） | 任何下一次 UI 重构前；搬移只改 package 行 | 每个新 UI 件继续在包根落点，`ui` 包纪律失效；全限定引用扩散后搬移变考古 |
| D2 | **P0** | repo 内脏（ProviderConfigStore 对账/双写）零直接测试 | `novex/android/repo/` 10/11 件 | 下次改 ProviderConfigStore 时先补对账分支测试 | 供应商配置静默丢失/回滚类事故无 CI 防线 |
| D3 | **P1** | NovexTransportProvider 1379 行单文件四线协议 | `transport/NovexTransportProvider.kt` | 下次加供应商/线协议时拆 request-mapper | 每家新供应商都是热线文件的减法手术；构造参数 15 个继续膨胀 |
| D4 | **P1** | runtime 三族混杂（服务/胶囊/会话状态） | `novex/android/runtime/` 9 件 | 下次动其中一族时拆包或立分区规矩 | 会话状态件被服务件连坐，不敢轻动；反向依赖旧仓库的三跳链继续隐身 |
| D5 | **P1** | 新包→旧世界"可改接漏网"9 项（AppLogger/MinisUserAgent/ImageBudget/KimiDeviceFlow/locale 常量/runtime 走旧仓库/ui 走旧主题…） | 见 §1.3B 表 | 每完成一档绞杀计划即清一批 | 绞杀完成判定永远差最后一步；旧路径文件永续保持"活代码"身份参与审计与 keep 规则 |
| D6 | **P1** | data 包 schema 进口未成文（NovexMainDatabase 装配 17 个旧世界 Entity/Dao） | `data/NovexMainDatabase.kt:8-24` | 在 ARCHITECTURE.md 写明"data=旧 schema 的包装层"并挂绞杀档 | 新人误以为 data 已是自有 schema，往 character 包加列时两头改 |
| D7 | **P1** | authkit 五家 OAuth 流程零直接测试（仅到期逻辑有） | `novex/android/authkit/` | 下次动任何 LoginFlow 前 | OAuth 回归只能真机发现；PKCE/回调竞态改坏无信号 |
| D8 | **P1** | 46 步迁移链无链式升级回归 | `data/MainDatabaseMigrations.kt` | 下一个 schema 版本落地时补 MigrationTestHelper 链测 | 旧安装用户升级翻车（发版事故级）且只在现场爆发 |
| D9 | **P1** | JSON 双栈（kotlinx vs 手搓 org.json 257 处） | §5.2 | 新代码一律 kotlinx；存量随手搓改动逐步收敛 | 「键名拼错→静默 null」类 bug 持续零散出生 |
| D10 | **P1** | 日志三写法（Log 直打 28 件 / AppLogger / RunLog） | §5.2 | 定一条规范入 ARCHITECTURE.md + review 拦截 | 双开关语义混乱，日志治理（分级/脱敏/落盘）永远做不齐 |
| D11 | **P1** | GlobalScope×2（authkit）与 runBlocking×3（ProviderConfigStore） | §5.4 | 下次触碰即改 | OAuth 竞态泄漏；主线程装载 ANR 面 |
| D12 | **P1** | 门面拆除无排期表（16+ 件全部"等改接"，消费者数已测） | §2 | 把 §2 表按消费者数排序入 UPSTREAM_EXIT_PLAN 挂档 | 门面永续滞留，旧路径删除永远差一步 |
| D13 | **P1** | 编排门面（Provider/Chat/SkillRepository 2064 行）无"只减不增"防增规矩 | `com/openminis/app/data/repository/` | 写入仓库层文档 | 新功能继续长在旧路径，重写成果被稀释 |
| D14 | **P2** | docs/ARCHITECTURE.md 失真（model-transport 依赖方向写反；provider/openai 已删仍在债务清单；未反映 novex.android 版图） | `docs/ARCHITECTURE.md` | 本报告入档时一并修订 | 按文档规划的人得出错误结论 |
| D15 | **P2** | models 与 data.model 撞名；微包 5 件未定去留规矩 | §3.2 | 定规矩时一并处理 | 第 6 个单件包继续冒出；检索模型 DTO 时两边找 |
| D16 | **P2** | sharekit/vault/netwatch/powerguard/localekit 零测试 | §4.1 | 触碰时补窄测 | 入站分享丢数据/自愈 prefs 误操作无防线 |
| D17 | **P2** | 测试错位（新包目录测旧路径类） | `test/.../repo/SkillRepositoryLifecycleTest.kt` | 下次扩测时归位 | 按包找测试扑空 |
| D18 | **P2** | 重试逻辑三处各写各的（暂可忍，第三处出现时收敛） | §5.2 | 触发条件出现时 | 重试语义分裂（退避基数/取消传播不一致） |
| D19 | **P2** | ProviderConfigStore 初始异步装载的 reconcile()/persist() 在 configLock 外执行——哈希错位路径下与装载窗内早期 save() 的 persist 交错时，磁盘可能回退陈旧快照（重启丢一次保存）。窗口窄、存量债非 PR#79 引入（净眼复核补录） | novex/android/repo/ProviderConfigStore | 随 D11/接口层收缝轮 | 极端时序下重启丢一次保存；方式：reconcile 持锁或装载完成前延迟镜像写 |

**通过项（不计债）**：novex.model 模块隔离（机器强制，零 app 依赖）；新包间无环、transport 无 DAO 直穿；门面零积肉、冻结面文档化全覆盖；TODO/FIXME/死代码/注释残留全零；LLMError 分类单点；transport/ui/model-transport 测试厚实且无空壳。

---

## 附：重跑本审计的快捷命令

```bash
# 包间依赖边（novex.android）
cd src/android/app/src/main/java/novex/android
for f in $(find . -name "*.kt"); do srcpkg=$(dirname $f|sed 's|^\./||'|cut -d/ -f1); \
  grep -h "^import novex.android" $f | cut -d. -f3 | while read t; do \
  [ "$t" != "$srcpkg" ] && echo "$srcpkg -> $t"; done; done | sort | uniq -c | sort -rn

# 新包→旧世界依赖面
grep -rn "^import com.openminis" . | sort

# 门面消费者数
grep -rln "\b<符号名>\b" ../../../../.. --include="*.kt" | grep -v <门面文件自身> | wc -l

# 卫生三扫
grep -rn "TODO\|FIXME" --include="*.kt" . | wc -l
grep -rln "import android.util.Log" --include="*.kt" .
grep -rn "GlobalScope\|runBlocking" --include="*.kt" .
```

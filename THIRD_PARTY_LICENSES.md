# Third-Party Licenses

> **GPL=0 现状（Android 分发物，2026-10，upstream-exit P4）**：Android
> 分发物**不含任何 GPL 组件**。当前打包/链接的全部第三方件均为 MIT、
> Apache-2.0 或 BSD 系许可（逐项见下表）。历史上的 GPL/LGPL 实体（iOS
> 层与沙箱执行层）已在 upstream-exit 早期轮次（P2.5/R2/P3.3）随对应子
> 系统整体出清，`src/ios` 与 `deps/` 目录不复存在——历史明细见 git
> 历史与 `docs/UPSTREAM_EXIT_PLAN.md`，本清单只反映现存树。

## Phosphor Icons

The selected interface icons under `src/android/app/src/main/res/drawable/ic_phosphor_*.xml`
are derived from [Phosphor Icons](https://phosphoricons.com) and used under the
MIT License. The complete license text is included in
`src/android/app/src/main/res/raw/phosphor_icons_license.txt`.

## Bundled web/UI assets

| Asset | Location | License | Notes |
|---|---|---|---|
| [KaTeX](https://katex.org) | `src/android/app/src/main/assets/katex/` (`katex.min.js` 0.16.21, `katex.min.css`, fonts, `mhchem.min.js`, `katex-render.html` 壳) | **MIT** | 离屏 WebView 公式渲染；mhchem 为 KaTeX 官方 contrib 扩展（同 MIT） |
| jieba 分词词典 | `src/android/app/src/main/assets/jieba/` | **MIT** | 随 [cppjieba](https://github.com/yanyiwu/cppjieba) 发行的分词词典（dict.txt 等） |
| [textstyles](https://github.com/oh-story-claudecode) 文体预设 | `src/android/app/src/main/assets/textstyles/genre/*.md`（含 `textstyles/LICENSE`） | **MIT** (oh-story-claudecode) | 中文文体预设语料；许可文件随资产同目录分发 |

## Native C/C++ components

| Component | Version / Source | License | Notes |
|---|---|---|---|
| [cppjieba](https://github.com/yanyiwu/cppjieba) | vendored at `src/android/app/src/main/cpp/cppjieba` (+ `jieba_jni.cpp`, dictionaries in `assets/jieba/`) | **MIT** | Chinese word segmentation (header-only + dictionaries) |
| Native crash handler | `src/android/app/src/main/cpp/crash_handler.cpp`（自有源码） | 本项目自有 | NDK 信号捕获，无第三方版权 |

## Vendored binaries (app/libs)

| Component | Version | License | Notes |
|---|---|---|---|
| [RealTimeCutVADLibrary](https://github.com/helloooideeeeea/RealTimeCutVADLibrary) For Android | 1.0.5（`libs/RealTimeCutVADLibraryForAndroid-1.0.5.aar`） | **MIT** | Silero v5 VAD（ONNX Runtime + WebRTC APM）——JitPack 间歇不可达，钉版 vendor 保证可复现构建 |
| [poi-on-android](https://github.com/centic9/poi-on-android) shaded Apache POI | 5.2.5-4（`libs/poishadow-all-5.2.5-4.jar`） | **Apache-2.0** | DOCX 主读取器（shaded 包，避免依赖冲突） |

## Android — Gradle dependencies（`src/android/app/build.gradle.kts`，2026-10 树）

| Library | Version | License |
|---|---|---|
| androidx.compose:compose-bom（material3 / material-icons-extended / ui / ui-tooling-preview / ui-tooling(debug) / ui-test-manifest(debug)） | BOM 2025.09.00 | **Apache-2.0** |
| androidx.core:core-ktx | 1.15.0 | **Apache-2.0** |
| androidx.lifecycle（runtime-ktx / viewmodel-compose / lifecycle-process） | 2.8.7 | **Apache-2.0** |
| androidx.profileinstaller | 1.4.1 | **Apache-2.0** |
| androidx.activity:activity-compose | 1.9.3 | **Apache-2.0** |
| androidx.exifinterface:exifinterface | 1.3.7 | **Apache-2.0** |
| androidx.navigation:navigation-compose | 2.8.5 | **Apache-2.0** |
| androidx.room（runtime / ktx / compiler-ksp） | 2.6.1 | **Apache-2.0** |
| androidx.datastore:datastore-preferences | 1.1.1 | **Apache-2.0** |
| androidx.security:security-crypto | 1.1.0-alpha06 | **Apache-2.0** |
| androidx.browser:browser（OAuth Custom Tabs） | 1.8.0 | **Apache-2.0** |
| com.squareup.okhttp3（okhttp / okhttp-sse / mockwebserver(test)） | 4.12.0 | **Apache-2.0** |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.7.3 | **Apache-2.0** |
| org.jetbrains.kotlinx:kotlinx-coroutines（android / test） | 1.9.0 | **Apache-2.0** |
| com.tom-roush:pdfbox-android（PDF 文本提取） | 2.0.27.0 | **Apache-2.0** |
| io.coil-kt:coil-compose | 2.7.0 | **Apache-2.0** |
| com.mikepenz（multiplatform-markdown-renderer-android / -m3-android） | 0.33.0 | **Apache-2.0** |
| sh.calvin.reorderable:reorderable | 2.4.0 | **Apache-2.0** |
| ch.acra:acra-core（本地崩溃上报，无 http sender） | 5.12.0 | **Apache-2.0** |

Test-only dependencies（不随应用打包）: JUnit 4.13.2（**EPL-1.0**）,
Robolectric 4.16.1（**MIT**）, MockWebServer 4.12.0（Apache-2.0）,
kotlinx-coroutines-test 1.9.0（Apache-2.0）, org.json 20231013（Public
Domain / JSON License）。Instrumented-test 侧：androidx.compose
ui-test-junit4、androidx.test runner/rules/ext-junit（均 Apache-2.0）。

## Bundled data snapshots

| Data | Location | Source / License | Notes |
|---|---|---|---|
| models.dev registry snapshot | `src/android/app/src/main/assets/models-dev-api.json` | [models.dev](https://models.dev) 开放目录数据（MIT） | 模型目录种子数据；运行时 48h TTL 后台自更到 cacheDir |
| 中文背景词频表 | `src/android/app/src/main/res/raw/zh_background_wordfreq.txt` | 本项目自编（curated） | 语音纠错 typed_vocabulary 评分用的常见词判定表，非语料库派生 |

## Provider trademarks (logos)

「选择接入方式」引导页（provider onboarding）内的各供应商 logo（智谱 Z
图标、DeepSeek 鲸鱼标识）为相应公司的**商标**，此处仅用于标识对应服务
（nominative use，指名使用）：帮助用户辨认正在配置哪家供应商的接口，不构
成对来源的背书或关联。自定义卡片的小鸟图形为本项目自有素材。若权利方提出
异议，相关图形将移除并以中性占位替代。

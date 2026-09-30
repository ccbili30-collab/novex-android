# Third-Party Licenses

## Phosphor Icons

The selected interface icons under `src/android/app/src/main/res/drawable/ic_phosphor_*.xml`
are derived from Phosphor Icons and used under the MIT License. The complete
license text is included in `src/android/app/src/main/res/raw/phosphor_icons_license.txt`.

Novex bundles, links, or depends on the following third-party components. Versions reflect the current source tree; license types were verified against each project's repository (GitHub license metadata / LICENSE files).

> **GPL=0 declaration（Android 分发物，2026-09-29）**：随着沙箱退役
> （upstream-exit P2.5/R2），Android 分发物**不含任何 GPL 组件**——
> KaTeX（MIT）、jieba/cppjieba（MIT），其余均为 Apache-2.0（Shizuku
> 为 MIT，已于 P3.3 裁军轮出清依赖）。历史上的 GPL/传染风险实体
> （iSH、proot、talloc、Alpine rootfs 内的 apk-tools）全部只存在于
> iOS 时代或沙箱时代的构建产物中，现已出清（见下表 Removed / historical）。

## Native C/C++ components

### Currently shipped (Android)

| Component | Version / Source | License | Notes |
|---|---|---|---|
| [cppjieba](https://github.com/yanyiwu/cppjieba) | vendored at `src/android/app/src/main/cpp/cppjieba` (+ `jieba_jni.cpp`, dictionaries in `assets/jieba/`) | **MIT** | Chinese word segmentation (header-only + dictionaries) |

### Removed / historical

| Component | Version / Source | License | Notes |
|---|---|---|---|
| [iSH](https://github.com/OpenMinis/ish-arm64) (ARM64 fork) | former git submodule `deps/ish` | **GPL-3.0** (post-`0e3a414` contributions also under GPL-2.0), with an App Store distribution exception (`LICENSE.IOS`) | iOS-era x86 Linux usermode emulation on iOS; deleted with the iOS layer — never part of the Android distribution |
| [proot](https://github.com/OpenMinis/proot) (fork) | former git submodule `deps/proot` | **GPL-2.0** | Linux sandbox on Android (`libproot.so`, `proot-aarch64`) — **removed** (upstream-exit P2.5, 2026-09-29) |
| [talloc](https://talloc.samba.org) (Samba) | former vendored `deps/talloc` | **LGPL-3.0-or-later** | Memory allocator required by proot — **removed** (upstream-exit P2.5, 2026-09-29), gone with proot |
| [FFmpeg](https://ffmpeg.org) | 6.1.2, former `deps/build_ffmpeg.sh` | **LGPL-2.1-or-later** (built without `--enable-gpl` / `--enable-nonfree`) | iOS-era dynamic frameworks; removed with `deps/` |
| [LAME](https://lame.sourceforge.io) | 3.100, former vendored `deps/lame-3.100` | **LGPL-2.0-or-later** | MP3 encoder, linked into FFmpeg via `--enable-libmp3lame`; iOS-era, removed with `deps/` |
| Alpine Linux minirootfs (de-GPL variant) | former download in `scripts/prepare_android_sandbox.sh` | musl **MIT**, dash **BSD-2-Clause**, apk-tools **GPL-2.0-or-later** | **已移除（沙箱退役，upstream-exit P2.5/R2）** — BusyBox 早已在 P2 出包，rootfs 整体随沙箱执行层拆除，脚本与资产一并删除 |

## iOS — Swift Package Manager dependencies（历史口径）

> 以下为 iOS 时代依赖清单，仅作历史记录；Android 分发物不含其中任何
> 组件（Android 已出清，见顶部 GPL=0 宣告）。

Direct packages declared in `src/ios/Minis.xcodeproj`:

| Package | Version | Repository | License |
|---|---|---|---|
| SwiftAnthropic | 2.2.0 (exact) | https://github.com/jamesrochabrun/SwiftAnthropic | **MIT** |
| swift-cmark (`cmark-gfm`, `cmark-gfm-extensions`) | 0.7.1 | https://github.com/swiftlang/swift-cmark | **BSD-2-Clause** (with some MIT-licensed vendored files, see its `COPYING`) |
| SwiftMath | 1.7.3 | https://github.com/mgriebling/SwiftMath | **MIT** |
| RealTimeCutVADLibrary | 1.0.14 | https://github.com/helloooideeeeea/RealTimeCutVADLibrary | **MIT** |

Transitive packages (pinned in `Package.resolved`), all **Apache-2.0**, maintained by Apple / the Swift Server Workgroup: `async-http-client`, `swift-algorithms`, `swift-asn1`, `swift-async-algorithms`, `swift-atomics`, `swift-certificates`, `swift-collections`, `swift-crypto`, `swift-distributed-tracing`, `swift-http-structured-headers`, `swift-http-types`, `swift-log`, `swift-nio` (+ `-extras`, `-http2`, `-ssl`, `-transport-services`), `swift-numerics`, `swift-service-context`, `swift-service-lifecycle`, `swift-system`.

## Android — Gradle dependencies

| Library | Version | License |
|---|---|---|
| AndroidX / Jetpack (Compose BOM 2025.09.00, core-ktx, lifecycle, activity, navigation, Room, DataStore, security-crypto, browser, exifinterface) | see `app/build.gradle.kts` | **Apache-2.0** (Google / AOSP) |
| OkHttp + okhttp-sse | 4.12.0 | **Apache-2.0** |
| kotlinx-serialization-json | 1.7.3 | **Apache-2.0** |
| kotlinx-coroutines-android | 1.9.0 | **Apache-2.0** |
| Coil (coil-compose) | 2.7.0 | **Apache-2.0** |
| multiplatform-markdown-renderer (+ m3) — mikepenz | 0.33.0 | **Apache-2.0** |
| Reorderable (sh.calvin.reorderable) | 2.4.0 | **Apache-2.0** |
| ACRA (acra-core) | 5.12.0 | **Apache-2.0** |
| poi-on-android shaded Apache POI bundle | 5.2.5-4 | **Apache-2.0** |

Removed / historical: **Shizuku API + provider (dev.rikka.shizuku 13.1.5, MIT)
— 依赖随 P3.3 裁军轮（2026-09-30，Shizuku/特权后端 offload/ 体系整体退役）
出清**；androidx.webkit（PWA 资产加载器）同轮出清，androidx.browser 保留
（provider OAuth 的 Custom Tabs 仍在用）。

Test-only dependencies: JUnit 4.13.2 (**EPL-1.0**), MockWebServer 4.12.0 (**Apache-2.0**), kotlinx-coroutines-test 1.9.0 (**Apache-2.0**), org.json 20231013 (**Public Domain / JSON License**).

## Bundled web/UI assets

| Asset | Location | License |
|---|---|---|
| KaTeX | Android `app/src/main/assets/katex/` | **MIT** |
| jieba dictionaries | iOS bundle / Android `assets/jieba/` | **MIT** (cppjieba distribution) |

## Other removed / historical

- **swift-markdown-ui** (MIT) — formerly vendored under `deps/swift-markdown-ui`; no longer referenced by the Xcode project or imported by any source file, and is not part of the open-source tree.

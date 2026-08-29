# 诺文 Android 第一版构建记录

## 产物

- 版本：`0.1.0`
- 应用标识：`com.noven.player`
- 构建类型：调试包
- 架构：`arm64-v8a`、`x86_64`
- SHA-256：`436e86e40f3f3dc18b22048779500f16e079f61fb2b311500daa7e618e285308`

## 已验证

- `:app:compileDebugKotlin` 成功。
- `:app:assembleDebug` 成功。
- 在 Windows 主机的 Pixel 8 / Android 34 模拟器安装成功。
- 应用版本、应用标识和双架构原生库均已核验。
- 发现页、世界详情页、进入玩家对话、结构化正文、角色卡和会话抽屉已在真实模拟器渲染。
- 启动日志中未发现诺文进程崩溃。

## 构建环境

- Java：Android Studio JBR 21
- Android SDK：36 编译接口，35 目标接口
- Android NDK：27.1.12297006
- CMake：3.22.1
- Gradle：8.11.1

Windows 主机访问官方依赖仓库时存在 TLS 握手问题，因此工程保留阿里云与腾讯云依赖镜像作为优先回退；JitPack 上固定版本的语音活动检测 AAR 已按其 MIT 许可证固化到仓库。

## 第一版边界

这是玩家模式界面与结构化内容协议的第一条可安装竖切。当前 `PreviewNovenRuntime` 提供本地示例数据；OpenMinis 的模型循环、联网与持久化仍在应用内保留，但尚未通过 `NovenRuntime` 接口接入新界面。下一阶段只替换运行适配器，不重做本次界面层级。


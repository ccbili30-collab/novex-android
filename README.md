# Novex

Novex 是一款面向中文文游的开源 Android（安卓）应用。它以连续的人工智能对话为主界面，让用户创建、导入并长期游玩自己的世界。

Novex 基于 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 改造。感谢 Minis 团队创造了这个美丽的移动端智能体底座；Novex 只是它的文游特化版本。也欢迎为 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 点一个 Star（收藏标记）。

## 第一版能力

- 使用 OpenAI Compatible API（OpenAI 兼容接口），支持 DeepSeek（深度求索）及兼容中转站；
- 连续保存原始对话，同时在本地维护世界核心规则、当前状态与完整后台资料；
- 第一人称、第三人称、导演式编排和普通对话可自由混用；
- 使用 `【】` 或 `[]` 在同一条用户消息中表达世界外纠错；
- 使用原生可点击选项，将选择填入输入框而不自动发送；
- 使用通用 `panel`（面板）工具展示文字、图片、列表与按钮；
- 支持存档、动态世界功能菜单、中文工具状态与常见错误翻译；
- 支持把对话内容分享到另一个文游会话；
- 从 Novex 的 GitHub（代码托管平台）发布页检查更新。

## 构建

Android（安卓）工程位于 `src/android`。需要 JDK 21（Java 开发工具包第 21 版）与 Android SDK（安卓软件开发工具包）。

```bash
cd src/android
./gradlew :app:assembleStableDebug
```

生成的 APK（安卓安装包）位于：

```text
src/android/app/build/outputs/apk/stable/debug/app-stable-debug.apk
```

### GitHub 云端构建

仓库内置 GitHub Actions（GitHub 自动化工作流）。推送到默认分支或提交
Pull Request（拉取请求）时只执行单元测试，避免每次改动都重复正式打包。
手动运行工作流时，它会先执行单元测试，再单独构建并校验正式 APK
（安卓应用安装包）；完成后可从工作流的 Artifact（构建产物）区域下载。

推送与应用版本一致的标签（例如 `v0.1.8`）时，工作流还会自动创建或更新
GitHub Release（GitHub 版本发布页），同时上传带版本名称的安装包、固定名称
`novex.apk` 以及 SHA-256（安全散列算法 256 位）校验文件。

为延续已发布应用的签名升级链，仓库设置中需要配置以下 GitHub Secrets
（GitHub 加密机密）：

- `NOVEX_RELEASE_KEYSTORE_BASE64`：Base64（基础六十四编码）的签名证书库；
- `NOVEX_RELEASE_STORE_PASSWORD`：证书库密码；
- `NOVEX_RELEASE_KEY_ALIAS`：证书别名；
- `NOVEX_RELEASE_KEY_PASSWORD`：证书私钥密码。

这些机密缺失时仍可完成本地或云端验证构建，但会回退到构建机自己的调试证书，
不得将该产物作为已有版本的更新发布。

## 开源协议

本项目沿用上游的 GNU GPL v3（GNU 通用公共许可证第 3 版），详见 [LICENSE](LICENSE)。

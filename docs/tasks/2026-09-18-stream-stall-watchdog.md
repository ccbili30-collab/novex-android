# 任务书：流式挂死看门狗 + 等待进度显示（stream-stall-watchdog）

日期：2026-09-18 · 仓位：PR #29 · 状态：开发中

## 1. 用户报告与根因

用户（2026-09-18）："这玩一个世界卡怎么能加载 10 多分钟呢？加载 10 多分钟还没启动成功"
追加（下令修复时）："至少得有显示进度啊，不能什么都看不见……你至少要有一个清晰的表达"

导出包 conversation-f899bf05（大明百鬼录首回合，claude-sonnet-5 经 sub.sailapi.top）：

- 10:14:24 主流式请求发出（54KB / 18585 tokens，规模正常）
- 10:14:44 中转站回 200 响应头后 **SSE 正文零字节到达**
- 客户端 `readTimeout=10min` 被连接上的零星活动不断重置，
  直到 **11:05:54** 才抛 SocketTimeoutException（黑洞 3069 秒）
- auto-retry 第 2 次尝试 183 秒正常完成——模型/卡/上下文全部无辜

三层缺陷：
1. **无流级看门狗**：headers 200 后"多久没有第一个数据块"、"相邻数据块间隔多久"
   均无任何检查；socket readTimeout 是唯一防线且形同虚设。
2. **死流不断连**：AnthropicProvider/GeminiProvider 的 callbackFlow 无
   `call.cancel()`（OpenAIProvider 有）；下游取消后阻塞在 readLine 的
   producer 不会被解除。
3. **黑洞零反馈**：等待首块期间 UI 只有静止的"正在处理 · 最新动态 ›"，
   用户看不到已等待多久、连接是否还活着。

## 2. 范围

| # | 改动 | 文件 |
|---|---|---|
| 1 | 公共流看门狗操作符 `failOnStreamStall`（首块超时 + 空闲超时，抛可重试 NetworkError），挂 `LLMProvider.streamMessage` 默认实现，三家 provider 一次覆盖 | provider/StreamStallWatchdog.kt（新）、provider/LLMProvider.kt |
| 2 | Anthropic/Gemini 流补 `call.cancel()` 断连（对齐 OpenAIProvider 既有做法） | provider/anthropic/AnthropicProvider.kt、provider/gemini/GeminiProvider.kt |
| 3 | 等待进度显示：请求发出时间戳进 AssistantProcess，活动条显示"等待模型响应 Xs"（计时在 Composable 侧，VM 不做秒级推送） | ChatViewModel.kt、ChatFlatItems.kt、NovexExecutionProcessState.kt、NovexExecutionProcess.kt |
| 4 | wire capture URL 双 v1 显示瑕疵（真实请求经 T192 清洗，记录侧未洗） | provider/anthropic/AnthropicProvider.kt |

## 3. 关键参数与依据

- 首块超时 = 空闲超时 = **300 秒（5 分钟）**。
- 下限依据（不能更短）：T171 教训——GPT-5.x Codex OAuth reasoning 静默
  实测 2:50–3:10（无 keep-alive 字节，服务器仍在工作），180s readTimeout
  曾把正常思考静默误杀成硬超时，后回调 600s（OpenAIProvider.kt:413 注释）。
- 上限依据（不能更长）：本次黑洞 3069 秒；用户 10 分钟已不可接受。
  5 分钟判定 + 1/2/4s 重试退避 + attempt2 实测 183s ⇒ 最坏路径 ~11 分钟
  出正文，正常路径 <6 分钟；且每 5 分钟必有可见状态变化（重试横幅）。

## 4. 不变量

- 看门狗抛 `LLMError.NetworkError(SocketTimeoutException(…))` ⇒
  `isRetryable=true` ⇒ 无缝进 NovexModelStreamRecovery transient 链
  （重试 3 次 + 降级），该框架零改动。
- 任何 chunk 类型（含 ThinkingDelta/ReasoningContent/Started/Usage）都算
  活动——reasoning 流持续产出事件时计时必须被重置。
- 思考静默 3 分钟以内（T171 实测）绝不误杀：300s 阈值留 90s 余量。
- 下游取消（用户停止/切换分支）⇒ 看门狗协程随之取消，无泄漏；provider
  awaitClose 断 socket。
- 等待时间戳只在"首块未到"阶段显示；首块到达后既有呈现（流文本/工具
  行/beta.72 直播尾巴）接管，不叠加。
- ChatViewModel 等待时间戳 = 每次请求发出时刷新（含重试/降级重发）。

## 5. 测试墙

- StreamStallWatchdogTest（新，注入 clock + 短超时）：
  无 chunk 超时抛 NetworkError；chunk 持续到达不抛；chunk 间隔超时抛；
  正常完成不抛；取消上游后看门狗协程不泄漏；ThinkingDelta 计入活动。
- NovexCardExecutionPresentationTest：AssistantProcess.awaitingSince 的
  呈现断言（等待行出现/首块后消失）。
- CI android-validate 全绿（本机无 JDK，CI 为机械门）。

## 6. 挂账（不在本 PR）

- 等待阶段细分文案（连接中/已发出/排队中）——等真实用户反馈再分层
- wire capture 其余 provider 的 URL 记录统一（本次只修 Anthropic 双 v1）
- 中转站健康度观测/慢成员降权（模型组延迟方差挂账已记）

## 台账

- 净眼审查：待
- 守纲六问：待
- CI：待

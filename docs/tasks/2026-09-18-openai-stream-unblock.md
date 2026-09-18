# 任务书：OpenAI 流式黑洞对齐 + execute IOException 补转换（openai-stream-unblock）

日期：2026-09-18 · 仓位：PR #30 · 状态：开发中 · 前置：PR #29（beta.73）

## 1. 背景（净眼复审 #29 抓出的跟进项）

PR #29 给三家 provider 统一挂了流挂死看门狗（failOnStreamStall，首块/
空闲 300s），并重构了 Anthropic/Gemini 的 producer：阻塞读移进
launch(Dispatchers.IO)、awaitClose{call.cancel()} 先行注册。净眼复审
确认 OpenAI 路径**同构缺陷仍在**：

- OpenAIProvider.streamMessageClamped（726-1554）：TTFB watchdog 只覆盖
  headers 到达前（execute 阻塞期，直接 call.cancel() 解除）；headers 后
  的 SSE readLoop（:1045）阻塞在 producer 协程主体，awaitClose（:1542）
  排在循环之后注册不上——看门狗判死信号被 callbackFlow 的
  coroutineScope 扣住 join 阻塞读，直到 socket 自死。对 OpenAI 兼容
  端点（中转站最常见协议形态），beta.73 的"卡住将自动重连"承诺不兑现。
- 附带缺口（净眼 P3-2）：Anthropic/Gemini 的 execute() 在 try 外抛裸
  IOException，不经 mapError → 不是 LLMError → NovexModelStreamRecovery
  不判 transient、连接建立失败不重试（OpenAI 仅 ttfb 情形有转换）。

用户决策（2026-09-18）：修完本项直接 promote stable 3.0.4。

## 2. 范围

| # | 改动 | 文件 |
|---|---|---|
| 1 | OpenAI readLoop 段（reader 创建 → channel.close()，含 isCodexImageModel 分支）整体移进 launch(Dispatchers.IO)；awaitClose{call.cancel(); response.close()} 紧随其后先行注册；图片分支 return@callbackFlow → return@launch；catch 加 CancellationException rethrow。execute/HTTP 错误分支留在 producer 主体（TTFB watchdog 已覆盖其阻塞期，throw 语义不变） | provider/openai/OpenAIProvider.kt |
| 2 | Anthropic/Gemini 的 execute() 补 IOException → close(mapError(e))（NetworkError，进 transient 重试链）+ CancellationException rethrow | 两 Provider |

## 3. 不变量

- readLoop 内 500 行解析状态机**逐字搬移**（accumulators/flags/counters
  全为循环局部状态，单协程无共享）；仅搬移包裹、图片分支 return 标签、
  CE rethrow 三处变化。
- TTFB watchdog 行为不变（launch + 直接 call.cancel，覆盖 execute 期）。
- execute/HTTP 错误在 producer 主体的 throw 语义不变（callbackFlow body
  内直接 throw 会让 collector 收到）。
- 看门狗判死 → 取消 → awaitClose handler call.cancel() → readLine 抛
  IOException → IO 协程结束 → join 完成 → NetworkError 秒级穿出——该
  链路由 StreamStallWatchdogTest 的真形态回归测试
  （watchdog error escapes a producer blocked on non-cancellable IO）
  守护，三家共用同一模式。
- 版本目标：修复合入 → beta.74 → promote stable 3.0.4（用户已授权）。

## 4. 测试墙

- 现有真形态回归测试继续守住模式（provider 无关）。
- CI android-validate 全绿（本机无 JDK）。
- 净眼走查重点：500 行搬移零逻辑变化、TTFB watchdog 与新结构交互、
  图片分支语义、execute IOException 补转换的异常面。

## 5. 挂账（不在本 PR）

- 非 200 时 OpenAI 与两家的错误分支仍在 producer 主体 throw（阻塞面
  已被 TTFB/execute 语义覆盖，无需搬移）
- 2702 行 readLine（Raw Passthrough 路径）不走流式看门狗，不在此修

## 台账

- 净眼审查：待
- 守纲六问：待
- CI：待

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

- 净眼一审（2026-09-18）：
  - P1 cancel 接收者翻转（launch{} 内 `cancel(msg,cause)` 解析为取消
    IO 子协程自身，中途断流被吞成正常完成、退出重试链；OpenAI 两处
    本 PR 搬移引入，Anthropic/Gemini 各一处 **#29 已带入**）→ **采纳，
    已修**：四处统一 `close(mapError(e))`；测试复刻块同步改写法，新增
    `mid-stream failure … typed error` 守护测试（谁写回 cancel 形态即红）
  - P3-1 execute() 段普通下游取消无法解除（ttfbWatchdog 仅超时路径
    call.cancel；用户停止最长挂 readTimeout——**该残留仅 OpenAI**，两家
    execute 已在 launch(IO) 内由 awaitClose handler 秒级解除；600s 只是
    三家 readTimeout 兜底值，AnthropicProvider.kt:79 / GeminiProvider.kt:45）
    → 挂账：execute 一并移入 launch 的更大对齐（含 headers 前路径）独立处理
  - P3-2 OpenAI execute 非 TTFB IOException 裸抛 → **顺手修**：
    `throw mapError(e)`（对称 Anthropic/Gemini connect_error 路径）
  - P3-3 connect_error 审计字段未过 safeText → **顺手修**（两处统一）
- 净眼复审（同日，ec5a213）：P1/P3-2/P3-3 **闭环**；守护测试有效。三 nit
  已清：台账 P3-1 表述修正（残留仅 OpenAI）、CI 结果回填、复刻块补
  channel.close() 使退化形态快红
- 守纲六问：过（2026-09-18 09:3xZ）——零diff（5 文件与声明一致）/无私开
  出口（close(cause) 为既有 channel 失败语义，无新重试机制）/无新旧并存
  （三家 producer 统一模式，grep 无 cancel 残留）/无过度工程（挂账未混入）
  /不违插槽（500 行状态机逐字搬移经净眼 diff 核对）/上轮兑现（#29 挂账
  的 OpenAI 跟进与 P3-2 兑现，顺手修掉 #29 带入的 P1）
- CI：第三轮绿（9m3s）
- 合并：PR #30 → next（2026-09-18T09:40:13Z，637917e）
- 发版：beta.74 → promote stable 3.0.4

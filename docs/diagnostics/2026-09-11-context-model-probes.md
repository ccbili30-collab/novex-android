# 模型容量实际请求记录

日期：2026-09-11。全部使用人工生成的重复文本，无用户卡片、原包或密钥。

7 个已登记凭据均完成模型列表读取；同一深度求索服务的重复凭据不重复计作新模型。排除图片、视频、语音、嵌入和审核专用模型后，70 个对话入口进行长请求。

27 个入口返回成功，服务端输入统计均超过 140,000 词元。其余 43 个补做短请求，均未成功；不能由这些错误认定它们的上下文只有 128K（12.8 万词元）。长请求成功仅证明接收该长度，不证明完整百万容量、模型身份或长文本理解质量。部分推理模型因短输出预算返回长度终止，本次只核对输入是否接收。

| 登记入口 | 模型标识 | 长请求状态 | 实际输入词元 | 短请求对照 |
|---|---|---|---|---|
| agnes | `agnes-2.5-pro-beta` | 403 | — | 403 |
| agnes | `agnes-2.5-pro-alpha` | 403 | — | 403 |
| agnes | `agnes-2.5-pro` | 403 | — | 403 |
| deepseek | `deepseek-flash` | 200 | 140030 | 无需／未取得 |
| agnes | `agnes-2.0-flash` | 200 | 140311 | 无需／未取得 |
| agnes | `agnes-2.5-flash` | 200 | 140311 | 无需／未取得 |
| gemini | `gemini-3.1-flash-lite` | 200 | 140028 | 无需／未取得 |
| gemini | `gemini-3-flash-preview` | 200 | 140028 | 无需／未取得 |
| deepseek | `deepseek-v4-pro` | 200 | 140030 | 无需／未取得 |
| gemini | `gemini-3.1-pro` | 200 | 140028 | 无需／未取得 |
| agnes | `agnes-3.0-flash` | 200 | 140101 | 无需／未取得 |
| gemini | `gemini-3.7-flash` | 200 | 140028 | 无需／未取得 |
| gemini | `gemini-3.8-flash` | 200 | 140028 | 无需／未取得 |
| glm | `glm-5.3` | 502 | — | 502 |
| glm | `glm-5.3-flash` | 502 | — | 502 |
| jmr-universal | `gpt-5.4` | 502 | — | 502 |
| gemini | `gemini-3.6-flash` | 200 | 140028 | 无需／未取得 |
| gemini | `gemini-3.1-pro-preview` | 200 | 140028 | 无需／未取得 |
| jmr-universal | `gpt-5.3-codex` | 502 | — | 502 |
| jmr-universal | `kimi-k2.6` | 402 | — | 402 |
| jmr-universal | `kimi-k3` | 401 | — | 503 |
| jmr-universal | `kimi-k2.6-pro` | 401 | — | 503 |
| jmr-universal | `claude-fable-5` | 401 | — | 401 |
| jmr-universal | `auto-10` | 200 | 140328 | 无需／未取得 |
| jmr-universal | `glm-5.2` | 429 | — | 429 |
| jmr-universal | `grok-chat-fast` | 503 | — | 503 |
| jmr-universal | `jmr-v1-flash` | 404 | — | 404 |
| jmr-universal | `kimi-k2.7-code` | 402 | — | 402 |
| jmr-universal | `gemini-3.1-pro` | 200 | 140115 | 无需／未取得 |
| jmr-universal | `deepseek-v4-pro` | 200 | 140109 | 无需／未取得 |
| jmr-universal | `gpt-5.5` | 200 | 140328 | 无需／未取得 |
| jmr-universal | `gemini-3-pro` | 200 | 140112 | 无需／未取得 |
| jmr-universal | `gpt-5.6-terra` | 200 | 140328 | 无需／未取得 |
| jmr-universal | `gemini-2.5-pro` | 200 | 140113 | 无需／未取得 |
| jmr-universal | `deepseek-v4-flash` | 200 | 140056 | 无需／未取得 |
| jmr-universal | `glm-5.2-cheap` | 402 | — | 402 |
| jmr-universal | `gpt-5.6-luna` | 200 | 140328 | 无需／未取得 |
| jmr-universal | `google/gemma-3-27b-it:free` | 404 | — | 404 |
| jmr-universal | `openai/gpt-oss-20b:free` | 404 | — | 404 |
| jmr-universal | `gpt-5.6-sol` | 200 | 140328 | 无需／未取得 |
| jmr-universal | `arcee-ai/trinity-large-preview:free` | 404 | — | 404 |
| jmr-universal | `z-ai/glm-4.5-air:free` | 404 | — | 404 |
| jmr-universal | `stepfun/step-3.5-flash:free` | 404 | — | 404 |
| jmr-universal | `minimax/minimax-m2.5:free` | 404 | — | 404 |
| jmr-universal | `gemini-3.5-flash` | 200 | 140114 | 无需／未取得 |
| jmr-universal | `meta-llama/llama-3.3-70b-instruct:free` | 404 | — | 404 |
| jmr-universal | `openai/gpt-oss-120b:free` | 404 | — | 404 |
| jmr-universal | `mimo-v2.5` | 401 | — | 401 |
| jmr-universal | `qwen/qwen3-coder:free` | 404 | — | 404 |
| jmr-universal | `nvidia/nemotron-3-super-120b-a12b:free` | 200 | 140042 | 无需／未取得 |
| jmr-universal | `liquid/lfm-2.5-1.2b-thinking:free` | 404 | — | 404 |
| jmr-universal | `mimo-v2.5-pro` | 401 | — | 401 |
| jmr-universal | `claude-opus-4-6-thinking` | 502 | — | 502 |
| jmr-universal | `claude-opus-4-7` | 502 | — | 502 |
| jmr-universal | `claude-sonnet-4-6` | 502 | — | 502 |
| jmr-universal | `claude-sonnet-4-5-20250929` | 502 | — | 502 |
| jmr-universal | `claude-opus-4-6` | 502 | — | 502 |
| jmr-universal | `claude-opus-4-5-20251101-thinking` | 502 | — | 502 |
| jmr-universal | `claude-sonnet-4-6-thinking` | 502 | — | 502 |
| jmr-universal | `claude-haiku-4-5-20251001` | 502 | — | 502 |
| jmr-universal | `claude-sonnet-4-5-20250929-thinking` | 502 | — | 502 |
| jmr-universal | `claude-opus-4-5-20251101` | 502 | — | 502 |
| jmr-universal | `claude-haiku-4-5-20251001-thinking` | 502 | — | 502 |
| jmr-universal | `glm-5.1` | 402 | — | 402 |
| jmr-universal | `Pro/zai-org/GLM-5.1` | 402 | — | 402 |
| jmr-universal | `glm-5.3-flash` | 200 | 140038 | 无需／未取得 |
| jmr-universal | `claude-haiku-4-5` | 522 | — | 522 |
| jmr-universal | `claude-opus-4-6-20260206` | 522 | — | 522 |
| mimo | `mimo-v2.5-pro` | 200 | 140277 | 无需／未取得 |
| mimo | `mimo-v2.5` | 200 | 140273 | 无需／未取得 |

本地原始响应元数据：`local/qa/context-repair-20260911/long-probes.jsonl`、`short-probes.jsonl`。完整密钥未记录。

容量依据：深度求索[官方规格](https://api-docs.deepseek.com/quick_start/pricing/)、[智谱 GLM-5.2（通用语言模型）规格](https://docs.bigmodel.cn/cn/guide/models/text/glm-5.2)、[小米 MiMo（小米大模型）公告](https://platform.xiaomimimo.com/docs/en-US/news/v2.5-tts-release)。型号推断仍属于估算，服务端明确声明和用户独立覆盖优先。未知别名可校正上限，不能伪称已从模型列表读到容量。

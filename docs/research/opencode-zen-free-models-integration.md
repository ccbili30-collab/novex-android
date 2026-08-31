# OpenCode Zen（开放代码精选模型网关）免费模型接入 Novex（诺文）的可行性研究

> 研究文档，不是法律意见。核对日期：2026-08-31。源码以 OpenCode（开放代码）官方仓库提交 [`10765ff`](https://github.com/anomalyco/opencode/tree/10765ff2a9da8c3b88e4de873aa383a49c318912) 为固定证据；线上模型与条款是可变状态。

## 结论

**技术上可行，但不建议在没有 OpenCode（开放代码）书面确认的情况下，直接把匿名免费服务作为 Novex（诺文）正式内置功能发布。**

OpenCode（开放代码）自己的客户端在没有用户密钥时，会删除所有收费模型，并使用 `public`（公共匿名身份）直接请求 `https://opencode.ai/zen/v1`。服务端也明确支持一部分模型匿名访问。因此 Novex（诺文）不需要部署中转服务器，也不需要把共享密钥写进安装包；每台手机可以直接请求 OpenCode Zen（开放代码精选模型网关）。

但是，MIT License（麻省理工开源许可证）只授权复用开源软件代码，不自动授权复用 OpenCode Zen（开放代码精选模型网关）这一托管服务。OpenCode（开放代码）的目标说明允许用户把 Zen（精选模型网关）用于“其他 coding agent（编程智能体）”，而 Novex（诺文）是通用对话与角色扮演产品，不属于文档明确列出的编程智能体场景。现行 Terms of Use（使用条款）又要求服务仅供用户自身内部使用、不代表第三方利益，并对自动化提取输出作了限制。因此，**“用户设备直连”降低了风险，但没有消除产品方把它固定嵌入第三方应用的授权歧义。**

推荐决策：

1. 先向 `help@anoma.ly` 取得书面确认：允许 Novex（诺文）作为非编程用途的第三方安卓客户端，以 `Bearer public`（公共承载令牌）访问匿名免费模型，并允许使用文字名称“OpenCode Zen（开放代码精选模型网关）”。
2. 获得确认后，再作为明确的“实验性免费模型”独立分区上线，并保留远程关闭开关。
3. 若暂时拿不到确认，只提供“用户填写自己的 OpenCode Zen API Key（开放代码精选模型网关接口密钥）”的自带密钥接入；不要默认启用公共匿名身份。该方案只能证明用户拥有自己的账户并可自行接受条款，仍不代表非编程用途已经获得授权。

## 一、官方客户端实际如何使用免费模型

### 1. 模型清单并非写死在客户端

OpenCode（开放代码）从 `https://models.opencode.ai/api.json` 下载模型目录，缓存有效期为 5 分钟，并每 60 分钟尝试后台刷新。目录包含价格、状态、工具能力、输入模态和协议包；客户端并不是只看模型名称是否以 `-free`（免费后缀）结尾。

来源：

- [模型目录下载、缓存和刷新源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/core/src/models-dev.ts#L160-L181)
- [后台每 60 分钟刷新源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/core/src/models-dev.ts#L237-L258)
- [客户端删除已弃用模型的源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/opencode/src/provider/provider.ts#L1676-L1695)
- [官方实时模型目录](https://models.opencode.ai/api.json)

OpenCode Zen（开放代码精选模型网关）的 `GET /zen/v1/models`（获取模型列表接口）当前允许匿名读取，但返回值只有模型标识等基础字段，不含价格、弃用状态、输入模态或协议类型。因此它不能单独决定“哪些模型免费且可启用”。

直接核验时，完整官方模型目录约为 4.4 MB（兆字节），轻量模型列表约为 5 KB（千字节）。安卓客户端不应每次打开设置页都下载完整目录。第一版更实际的方案是内置官方当前 6 个免费模型的协议和隐私元数据，再用轻量模型列表校验它们是否仍存在；新增免费模型通过 Novex（诺文）自己的轻量远程配置或后续版本加入。若坚持完全跟随官方目录，则最多按日缓存并避免在计费移动网络上自动刷新。

来源：

- [模型列表接口源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/app/src/routes/zen/v1/models.ts)
- [官方实时模型列表接口](https://opencode.ai/zen/v1/models)

Novex（诺文）应使用以下条件生成列表，而不是复制一份固定名称：

```text
官方模型目录中 provider（提供商）= opencode
且 input/output cost（输入／输出价格）= 0
且 status（状态）不是 deprecated（已弃用）
且模型标识仍存在于 /zen/v1/models（精选模型网关模型列表）
```

### 2. 无密钥时使用公共匿名身份

OpenCode（开放代码）客户端会判断用户是否配置密钥；如果没有密钥，它会：

- 删除所有输入价格不为 0 的模型；
- 把接口密钥设置为字符串 `public`（公共匿名身份）；
- 只让剩余免费模型进入可选列表。

来源：[无密钥免费模型筛选源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/opencode/src/provider/provider.ts#L185-L206)

服务端把 `Authorization: Bearer public`（授权头：公共承载令牌）转换成“没有接口密钥”，然后只允许 `allowAnonymous`（允许匿名）为真的模型继续请求；不允许匿名的模型会返回缺少密钥错误。

来源：

- [公共身份解析及按地址／密钥限流入口](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/app/src/routes/zen/util/handler.ts#L98-L124)
- [匿名模型认证分支](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/app/src/routes/zen/util/handler.ts#L674-L678)

### 3. 请求是客户端直连远端网关

OpenCode（开放代码）在本地进程中构造模型请求，然后直接调用远端提供商地址；Zen（精选模型网关）的基础地址来自模型目录。它附带会话、请求、客户端和用户代理等识别头，但这些头主要用于可观测性和路由，不构成匿名认证。

来源：

- [OpenCode（开放代码）请求头构造源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/opencode/src/session/llm/request.ts#L177-L205)
- [Zen（精选模型网关）远端转发及流式代理源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/app/src/routes/zen/util/handler.ts#L188-L256)

结论：Novex（诺文）安卓客户端可以直接请求远端网关，不需要嵌入 OpenCode（开放代码）命令行工具，也不需要连接 OpenCode（开放代码）本地服务器。

## 二、截至核对日的免费模型

官方 Zen（精选模型网关）文档与实时模型目录在 2026-08-31 共同确认以下 6 个未弃用零价格模型：

| 模型 | 模型标识 | 请求协议 | 官方目录声明的输入能力 |
|---|---|---|---|
| Big Pickle（大腌黄瓜匿名模型） | `big-pickle` | OpenAI-compatible Chat Completions（开放人工智能兼容对话补全） | 文本 |
| MiMo V2.5 Free（小米大模型第二代 2.5 免费版） | `mimo-v2.5-free` | OpenAI-compatible Chat Completions（开放人工智能兼容对话补全） | 文本、图片、音频、视频 |
| Ling 3.0 Flash Fin Free（零一万物灵动 3.0 闪速最终免费版） | `ling-3.0-flash-fin-free` | OpenAI-compatible Chat Completions（开放人工智能兼容对话补全） | 文本 |
| Nemotron 3 Ultra Free（英伟达神经元 3 超级免费版） | `nemotron-3-ultra-free` | OpenAI-compatible Chat Completions（开放人工智能兼容对话补全） | 文本 |
| Nemotron 3.5 Lightning Free（英伟达神经元 3.5 闪电免费版） | `nemotron-3.5-lightning-free` | OpenAI-compatible Chat Completions（开放人工智能兼容对话补全） | 文本 |
| Muse Spark 1.2 Contributor Free（缪斯火花 1.2 贡献者免费版） | `muse-spark-1.2-contributor-free` | OpenAI Responses（开放人工智能响应接口） | 文本、图片、视频、文档、音频 |

所有 6 个模型在官方目录中都标记 `tool_call=true`（支持工具调用），但这只是能力声明，不代表每种工具组合在免费上游都稳定。

来源：

- [Zen（精选模型网关）官方端点、价格与免费模型说明](https://opencode.ai/docs/zen/)
- [官方实时模型目录](https://models.opencode.ai/api.json)

实时 `/zen/v1/models`（精选模型网关模型列表）仍可能返回已经在官方目录标记 `deprecated`（已弃用）的旧免费模型。因此不能把“接口里存在”直接等同于“当前应展示”。`big-pickle`（大腌黄瓜匿名模型）又没有 `-free`（免费后缀），所以也不能只按名称匹配。

## 三、协议、流式输出和工具调用

### 1. 需要两条协议路径

前 5 个免费模型使用：

```text
POST https://opencode.ai/zen/v1/chat/completions
Authorization: Bearer public
```

这是 OpenAI-compatible Chat Completions（开放人工智能兼容对话补全）协议。官方路由从请求体读取 `model`（模型）和 `stream`（流式开关），并使用 `text/event-stream`（服务器发送事件流）透传流式结果。

Muse Spark 1.2 Contributor Free（缪斯火花 1.2 贡献者免费版）使用：

```text
POST https://opencode.ai/zen/v1/responses
Authorization: Bearer public
```

这是 OpenAI Responses（开放人工智能响应接口）协议，不能假装成普通对话补全接口。若 Novex（诺文）当前没有完整 Responses（响应接口）适配层，第一版应隐藏这个模型，而不是把失败归类为模型不稳定。

从本次核验网络出口直接请求 Muse Spark 1.2 Contributor Free（缪斯火花 1.2 贡献者免费版）得到 `403 RegionError`（地区限制错误）。这不是协议故障，而是免费模型存在地域可用性差异的直接证据；界面必须保留服务器原始错误类型并按模型跳过，不能把整个 OpenCode Zen（开放代码精选模型网关）判为不可用。

来源：

- [对话补全路由源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/app/src/routes/zen/v1/chat/completions.ts)
- [响应接口路由源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/app/src/routes/zen/v1/responses.ts)
- [官方端点表](https://opencode.ai/docs/zen/#endpoints)

### 2. 对话补全支持标准结构化工具调用

官方转换器会保留 `tools`（工具定义）、`tool_choice`（工具选择）、助手的 `tool_calls`（工具调用）和 `tool`（工具结果）消息；流式请求还会自动要求上游返回用量信息。

来源：

- [开放人工智能兼容请求和工具转换源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/app/src/routes/zen/util/provider/openai-compatible.ts#L83-L137)
- [开放人工智能兼容工具调用响应转换源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/app/src/routes/zen/util/provider/openai-compatible.ts#L223-L319)
- [请求体流式透传及用量选项源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/app/src/routes/zen/util/requestBody.ts#L90-L104)

这意味着 Novex（诺文）可以复用 0.1.8 已经修好的 OpenAI-compatible（开放人工智能兼容）工具循环、空响应重试和流式结束判断，但仍需为每个免费模型执行完整工具集合的真实兼容检测。

直接核验 `big-pickle`（大腌黄瓜匿名模型）时，普通回复、流式回复和结构化工具调用均成功；流式事件中可出现 `reasoning_content`（推理内容）、`finish_reason`（结束原因）和 `[DONE]`（完成标记）。需要特别注意：Zen（精选模型网关）可能在上游 `[DONE]`（完成标记）之后再追加 `cost`（成本）尾帧。因此解析器可以在 `[DONE]`（完成标记）后忽略统计尾帧，但不能把它误报成损坏数据，也不应假定完成标记一定是网络流最后一个字节。

来源：[流式代理在上游结束后追加成本事件的源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/app/src/routes/zen/util/handler.ts#L331-L380)

## 四、认证与限流

### 匿名请求

匿名免费模型按公网 IP Address（互联网协议地址）计数。精确每日额度来自服务端未公开配置，不能可靠写进 Novex（诺文）界面。达到额度时服务端返回限流错误及距离下一次日界线的重试秒数；默认日界线按 UTC（协调世界时）计算。相同家庭、公司或移动运营商出口地址下的多个用户可能共享额度。

当前源码把请求头资格检查临时强制设为通过，原先“缺少指定客户端头时进入较小备用额度”的代码被注释。这个临时状态随时可能恢复，不能把它视为第三方客户端的长期服务承诺。

来源：

- [匿名地址限流源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/app/src/routes/zen/util/ipRateLimiter.ts#L8-L53)
- [限额配置从服务端资源读取，数值不在仓库公开](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/core/src/subscription.ts#L7-L50)

### 用户自己的密钥

真实 Zen API Key（精选模型网关接口密钥）并不会让匿名免费模型绕过地址限额：服务端只要看到模型被标记为 `allowAnonymous`（允许匿名），就选择地址限流器，即使请求同时携带真实密钥。只有不允许匿名的模型才进入密钥限流器；该限流器源码默认值为每分钟 1,000 次，也可被模型专用限额覆盖。密钥只能保存在安卓加密存储中，不应经过 Novex（诺文）服务器。

来源：

- [按模型是否允许匿名选择限流器](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/app/src/routes/zen/util/handler.ts#L118-L124)
- [非匿名模型的密钥限流源码](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/packages/console/app/src/routes/zen/util/keyRateLimiter.ts#L6-L36)

产品上应把 `429`（请求过多）和 `Retry-After`（建议重试时间）直接解释为“OpenCode（开放代码）匿名额度已用完”，禁止快速自动重试或自动轮询所有模型，否则一次检查就会消耗多份免费额度。

## 五、许可、服务条款与品牌边界

### 1. 开源代码许可与托管服务许可是两回事

官方仓库是 MIT License（麻省理工开源许可证），允许使用、修改和分发代码，但要求在复制或实质性复用代码时保留版权和许可声明。

来源：[OpenCode（开放代码）官方开源许可证](https://github.com/anomalyco/opencode/blob/10765ff2a9da8c3b88e4de873aa383a49c318912/LICENSE)

现行 Terms of Use（使用条款）则明确把网站、推理产品和托管软件列为 Services（服务），并说明非托管开源软件才单独受开源许可证管辖。换言之，复制客户端实现的权利不等于获得免费网关的再分发或嵌入授权。

来源：[OpenCode（开放代码）现行使用条款](https://opencode.ai/legal/terms-of-service)

### 2. 第三方客户端接入存在条款歧义

有利证据：Zen（精选模型网关）官方目标明确写着允许用户把它与“任何其他 coding agent（编程智能体）”一起使用，接口文档也公开给出端点。

不利证据：使用条款同时要求服务仅用于用户自身内部用途、不代表或服务于第三方利益，并限制自动或程序化提取输出；条款对服务和第三方模型之外的权利也没有作额外授予。Novex（诺文）的核心用途又不是编程智能体。

因此不能仅凭“源码里存在 `public`（公共匿名身份）”推导出“任何第三方应用都可无条件内置”。更稳妥的发布门槛是取得官方书面确认，并保存确认范围：产品名称、匿名身份、非编程用途、品牌展示、地区以及是否允许商业分发。

### 3. 品牌展示建议

在没有品牌授权前：

- 只使用文字“OpenCode Zen（开放代码精选模型网关）”；
- 不复制官方图标或把它放在 Novex（诺文）主品牌位置；
- 明示“第三方实验性接入，非 OpenCode（开放代码）官方功能或保证”；
- 链接官方条款、隐私说明和模型页；
- 不宣传为“Novex（诺文）自有免费模型”。

## 六、隐私与数据使用风险

这些免费模型不能统一宣传为“零留存”或“不会训练”。官方文档明确列出例外：

- Big Pickle（大腌黄瓜匿名模型）、MiMo V2.5 Free（小米大模型第二代 2.5 免费版）和 Ling 3.0 Flash Fin Free（零一万物灵动 3.0 闪速最终免费版）的免费期数据可能用于改进模型；
- 两个 Nemotron（英伟达神经元模型）免费端点仅供试用，不应提交个人或机密数据，会记录使用数据以用于安全和改进英伟达产品；
- Muse Spark 1.2 Contributor Free（缪斯火花 1.2 贡献者免费版）以允许提示和补全用于训练未来 Meta（元宇宙平台公司）模型为交换条件；
- 服务在美国托管，地区限制可能导致模型不可用；
- 未付费账户的内容也可能依照使用条款用于改进服务。

来源：

- [Zen（精选模型网关）官方隐私例外说明](https://opencode.ai/docs/zen/#privacy)
- [OpenCode（开放代码）现行使用条款的内容使用条款](https://opencode.ai/legal/terms-of-service)

这与 Novex（诺文）可能发送的世界设定、角色资料、图片和私人对话直接冲突。上线时必须：

1. 默认关闭；
2. 首次启用时逐项说明数据风险并要求用户确认；
3. 在模型选择器里给数据可能用于训练的模型持续显示标记；
4. 默认阻止向两个 Nemotron（英伟达神经元模型）发送用户标记为私密的附件或内容；
5. 不能把同意藏在通用隐私政策里。

## 七、建议的 Novex（诺文）产品设计

获得官方许可后，可以采用用户提出的界面方向，但建议这样收敛：

```text
实验性免费模型
────────────────
OpenCode Zen（开放代码精选模型网关）
第三方实验接入 · 可能限流、下线或用于模型改进
[总开关]
  □ Big Pickle（大腌黄瓜匿名模型）
  □ MiMo V2.5 Free（小米大模型第二代 2.5 免费版）
  □ Ling 3.0 Flash Fin Free（零一万物灵动 3.0 闪速最终免费版）
  □ Nemotron 3 Ultra Free（英伟达神经元 3 超级免费版）
  □ Nemotron 3.5 Lightning Free（英伟达神经元 3.5 闪电免费版）
  □ Muse Spark 1.2 Contributor Free（缪斯火花 1.2 贡献者免费版）
```

实现原则：

- 它是系统内置的只读提供商，放在用户自建提供商上方，用分割线隔开；
- 每个模型独立勾选，不要求检测全部模型；
- 用户可以直接启用，检测只检查所选模型；
- 显示“实验性”“官方免费期有限”“匿名地址限额”“数据处理”状态；
- 免费列表由远端目录动态计算，并保留上一次成功目录；目录失败时不清空用户已有选择；
- 内置远程关闭开关和最小客户端版本条件；
- 一个模型失败只跳过该模型，不影响其他模型；
- 不在启动或打开设置页时发起模型推理，只拉取目录；
- 用户发起第一条消息时才实际消耗免费额度；
- 不自动把免费模型加入故障降级链，避免用户在不知情时把私人上下文转给数据政策不同的服务；
- 模型下线时保留会话记录，并提示用户选择替代模型。

## 八、实施前必须得到的官方确认

建议发给 OpenCode（开放代码）的确认问题：

1. Novex（诺文）可否在商业分发的安卓应用中，以 `Authorization: Bearer public`（授权头：公共承载令牌）直接访问匿名免费模型？
2. 非编程用途的普通对话、角色扮演、图片理解和工具调用是否属于允许用途？
3. 是否允许在界面使用文字名称“OpenCode Zen（开放代码精选模型网关）”，以及是否有品牌规范？
4. 用户是否必须先创建账户或单独接受使用条款，还是应用内链接并明确确认即可？
5. 是否有要求发送的客户端标识头、版本头或 User-Agent（用户代理）格式？
6. 匿名免费模型是否允许第三方客户端动态读取 `models.opencode.ai`（开放代码模型目录）并展示？
7. 免费模型的限流、地域和停止服务通知是否有稳定的机器可读接口？

在得到明确答复之前，本研究的上线判断是：**技术验证可以做；面向普通用户的默认匿名接入暂缓。**

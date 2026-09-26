# 阶段 0 实施任务书：opencode 免费模型下线（opencode-sunset）

总纲：[2026-09-26-memory-system-rework.md](2026-09-26-memory-system-rework.md) §3.11 · 日期：2026-09-26

## 依据（已实测）

OpenCode Zen 免费档 2025-09 起官方封锁第三方调用：`/models` 端点与匿名
`public` key 仍活（80 模型在列），但 chat 调用 403
`FreeTierError: "OpenCode's free tier can only be used from within OpenCode"`；
UA + x-opencode-* 四头伪造不通。拥有者拍板：直接下线（拒绝伪装客户端指纹方案）。

## 改动清单

| # | 内容 | 文件 |
|---|---|---|
| 1 | 删除 OpenCodeFreeModelsApi + 其测试 | provider/opencode/*（整目录）、test/.../opencode/* |
| 2 | ProviderRepository 删除：refreshOpenCodeFreeModels / setOpenCodeFreeModelEnabled / setOpenCodeFreeModelToolsEnabled / applyOpenCodeFreeCatalog / hasAccepted- & acceptOpenCodeFreeDisclosure / KEY_OPENCODE_FREE_DISCLOSURE | ProviderRepository.kt |
| 3 | 一次性迁移（启动时幂等）：builtin-opencode-free-* 实例 isEnabled=false；其 modelEntries 条目 isHidden=true。`isOpenCodeFreeInstance` **保留**（迁移识别用） | ProviderRepository.kt |
| 4 | 设置页移除 OpenCodeFreeSection 区块、disclosure 对话框、refresh 逻辑与相关 state；实例过滤处保留 isOpenCodeFreeInstance 过滤（禁用实例本就不进列表，双保险） | ProviderListScreen.kt |
| 5 | 403 友好映射：OpenAIProvider.mapHttpError 的 403 分支识别 `free tier … within OpenCode` 文案 → `LLMError.ProviderError("OpenCode 免费模型已停止服务：官方已限制仅 OpenCode 客户端内使用，请切换其他模型")`（存量绑定旧会话的防御） | OpenAIProvider.kt |

## 不变量

- 用户数据不删：仅禁用/隐藏（实例与模型条目保留在 config，可恢复）。
- ModelsDevApi（公共目录）保留——其他 provider 在用。
- 其余供应商列表/测试连通性路径零语义变化。
- 无新旧并存：UI 入口与管理函数同 PR 移除。

## 测试墙

- 迁移幂等单测：含/不含 opencode 实例的 config 各跑两遍，状态收敛。
- 403 文案映射单测（命中/不命中）。
- CI android-validate 绿。

## 台账

- 净眼一审（2026-09-26）：
  - P1 迁移挂载点失效（冷启动走 init 异步装载分支，ensureConfigLoaded 被
    _configLoaded 短路，迁移常态不执行——模型选择器仍列出免费模型）→
    **采纳已修**：迁移改挂 init 装载采纳分支（幂等、失败不阻断装载）
  - P2 迁移生效后 403 人话不可达（绑定层先 require 失败）→ **采纳已修**：
    bindChatEntry 对 sunset 实例直接给停服文案（直连路径的 403 映射保留
    作为纵深防御）
  - P3 未用 import×3 → 已清；顶层函数 public → 驳回留痕（bindChatEntry
    跨包引用需 public）；saveConfig 二次 emit → 驳回留痕（内容等价无功能
    影响，不动）；"free tier" 子串匹配偏宽 → 接受（极小概率）；孤儿 prefs
    键 → 接受（无害）
- CI：第一轮基础设施 404（proot .92 被 Termux 下架）→ 升 .95 实测修复；
  第二轮绿（9m15s）
- 守纲（含总纲偏离对照）：待

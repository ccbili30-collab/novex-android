# Novex 文档索引

Novex 是一个安卓文字冒险（文游）应用，使用 OpenAI 兼容接口连接大模型。
本仓库由开源项目 OpenMinis（GPLv3）改造而来，2026-09 起为纯安卓项目，上游 iOS 代码已移除（见 `UPSTREAM.md`、`THIRD_PARTY_LICENSES.md`）。

## 文档分两级

**核心文档**：长期有效。改代码或改流程时必须同步更新，冲突时以核心文档为准。

| 文档 | 职责 |
|---|---|
| `README.md`（仓库根） | 项目简介、构建命令 |
| `CONTEXT.md`（仓库根） | 领域词汇表（对话/世界/角色/资料集等术语的唯一出处） |
| `CHANGELOG.md`（仓库根） | 版本历史，每次发布追加 |
| `docs/PRODUCT.md` | 产品决策与原则（用户已确认，不是提案） |
| `docs/ARCHITECTURE.md` | 代码架构：模块划分、依赖方向、已知债务 |
| `docs/RELEASE.md` | 发布流程：双通道、四条流水线、签名与晋级 |
| `docs/NOVEX_MOBILE_DESIGN_SYSTEM.md` | 移动端视觉与交互合同 |
| `docs/specs/card-format.md` | 卡片文件格式规范（导出/导入必须完整还原） |

**任务文档**（`docs/tasks/`）：临时执行计划，一个任务一份。任务完成、结论合并进核心文档后删除；历史任务留在 `docs/tasks/archive/` 只作追溯，不作为当前行为依据。

## 其余目录

- `docs/specs/` — 长期技术规范（目前仅卡片格式）
- `docs/research/` — 调研笔记（SillyTavern、DeepSeek、世界书触发等）
- `docs/diagnostics/` — 排障与验收记录。活跃故障表：`3.0-bug-board.md`（唯一有效的故障清单，其他文档不另立故障表）
- `docs/tasks/archive/` — 已完成或已作废的任务计划、旧版产品决策、旧设计稿

## 给维护者的规矩

1. 改了卡片格式 → 必须更新 `docs/specs/card-format.md` 并保证往返对账测试通过。
2. 改了发布流程 → 必须更新 `docs/RELEASE.md`。
3. 新的产品决定 → 写进 `docs/PRODUCT.md`，注明日期和"用户已确认"。
4. 开新任务 → 在 `docs/tasks/` 建一份计划，写明目标、边界、验收标准；完成后把结论合并进核心文档再删。
5. `archive/` 与 `diagnostics/` 里的历史记录不改写、不引用为当前依据。

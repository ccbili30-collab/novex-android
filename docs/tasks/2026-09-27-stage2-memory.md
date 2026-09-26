# 阶段 2 实施任务书：分区水位 + AI 自主记忆（stage2-memory）

总纲 §3.4/3.5/3.7 ｜ 日期：2026-09-27（夜班冲刺）

## 切分

- **PR-2a（本 PR）**：MemoryWindowBudget 纯函数（分区核算+刻度）+ NovexNotebookStore
  （会话级记忆文件存储）+ 水位刻度触发后台记忆整理 + 笔记本常驻注入
- **PR-2b**：设置页"AI 记忆"查看/编辑 UI

## PR-2a 设计决策

1. **分区核算（§3.4）**：`MemoryWindowBudget.reading(window, system, history)` →
   系统区/对话区配额/输出预留/水位百分比/下一刻度。水位分母=对话区配额
   （活性基数）。本阶段只做**记账与触发**，预算强制（系统区上限挤压等）归
   阶段 3 resize 协议——不提前扩面。
2. **水位刻度（§3.5）**：刻度高水位制（记忆文件存 lastTickPercent；回落不
   重复触发）。10% 一档触发记忆整理（后台）；90% 压缩刻度与现有
   ContextPolicy 的 compactThreshold 比例对齐（现有机制不动，只对齐读数）。
   50% 快照预备挂账阶段 3（快照本体在彼处）。
3. **AI 自主记忆（§3.7）**：
   - **存储**：`<workspace>/novex/<session>/memory.json`（跨压缩天然存活、
     无 Room 迁移、阶段 3 存档三元组复用同目录）。
   - **写入**：回合结束后 ChatViewModel 检查刻度→跨档→viewModelScope 后台
     任务：主模型旁路调用（输入=现有记忆 JSON+本回合对话增量，输出=更新后
     条目集 JSON）；护栏：单并发（AtomicBoolean）、失败静默跳过、软上限
     60 条/8000 字（超限在整理 prompt 中指示合并最旧条目）。
   - **读取（v1 折衷，留痕）**：笔记本**常驻注入** system 尾部
     `<AI 随身笔记>` 块（条目少时全量 <1K token）——总纲"目录指针+按需
     检索"的完整形态归阶段 4 三层读取，v1 不做检索工具。
4. **不变量**：记忆整理绝不阻塞用户（后台+失败跳过）；记忆文件损坏→
   视为空重置（静默，记忆是潜在收益）；常驻块为空时不注入任何痕迹。

## 测试墙

- MemoryWindowBudgetTest：分区算术/零配额/刻度跨越（含回落不触发）。
- NovexNotebookStoreTest：往返/损坏重置/上限合并标记。
- 整理 prompt 输出解析：合法 JSON/非法静默跳过。

## 3a/3b 增补（同夜冲刺，PR #34）

- 阶段 3a：压缩产世界快照（NovexStateSnapshot——与既有 data class
  NovexWorldSnapshot 重名改定，增量更新+latest/历史链）+ 常量模块重注入
  （CONSTANT+DEFAULT 全文+元说明分工）+ 当前状态锚一行版
- 阶段 3b：存档三元组（NovexSaveStore）+ /save /saves /load 命令组 +
  压缩自动档；回档 v1=状态恢复+声明（硬 fork 挂账 PR-C）
- 夜班事故：3a 接线脚本中途断言失败未落盘即提交（编译必红），已补全留痕

## 台账

- 净眼/守纲/CI：待

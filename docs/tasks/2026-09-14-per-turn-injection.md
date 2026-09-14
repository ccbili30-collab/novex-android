# 每轮注入

日期：2026-09-14。状态：实现与 CI 验证完成（android-validate 全绿，run 34827864881），待手机端验收。
用户已确认方案与界面细节（见对话记录），本文为执行依据。

## 目标

对话设置「使用的设定」分区下新增「每轮注入」多行文本框：用户写一句话（如"每一轮都要向我提供 3~4 个选项"），此后每次发送消息都把这句话随请求注入模型，靠近最新一轮（历史后指令语义）；正常对话界面不显示。

## 边界

- 对话级设置：随对话持久化、重开恢复；不继承人格、不写回卡片；
- 一个多行文本框，用户自行排布，不拆条目；上限 8000 字符，超出截断；
- 留空完全不生效，老对话零影响；
- 注入实现为请求副本上的追加，不写入数据库、不渲染为聊天气泡；
- 每次模型调用恰好一份（附加在最后一条 user 消息内容之后），不随工具循环重复累积；
- 计入上下文额度估算（在估算之前应用）；
- 调试可见：请求审计记录 per_turn_injection 事件（字符数）。

## 实现落点

- `data/ConversationSettings.kt`：字段、归一化、纯函数（包装、追加到历史）
- `data/db/ChatSessionEntity.kt` + `AppDatabase.kt`（v35→v36 迁移加列 per_turn_prompt）+ `ChatDao.kt`
- `data/repository/ChatRepository.kt`：updateConversationSettings 透传
- `ui/chat/ChatViewModel.kt`：状态流、快照、装载/保存接线、发送卡点注入
- `ui/chat/ConversationSettingsScreen.kt`：「使用的设定」分区新增输入框

## 验收标准

- 单测：归一化（截断/去空白）、包装格式、追加位置（末位 user 消息 / parts 消息 / 无 user 消息回退）；
- CI：android-validate 全绿（含 :app 单测与编译）。

## 完成后

结论并入 `docs/PRODUCT.md`（对话运行时能力）后删除本文。

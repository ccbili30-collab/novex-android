# PR 3 任务书 · 测试墙与收敛（最终阶段）

上级：docs/tasks/2026-09-16-conversation-core-rework.md；前置：PR 2c。
守纲预载条件（PR2c 裁决④）：本任务书逐项映射。

## 范围

1. **测试墙（核心交付）**：纯函数层不变量测试补齐——
   - 装配线等价：给定场景输入，assemble 九段的期望输出（压缩锚点/快照前拼/
     pureChat 删减/预算裁剪的各自不变量）；
   - conv9 场景固化：内存历史只剩当前轮 + DB 完整 → I1 必拒（PreSendContract
     已有单测，补"DB 侧投影 expected 计算"链路的装配层测试）；
   - 影子指纹：structural 强信号/弱信号分级的边界测试（已在 PR1 建立，补
     端到端场景矩阵）；
   - RunPhase 转换矩阵的纯逻辑测试（把审计器判定逻辑抽为可测函数）。
2. **五件套收敛**（守纲条件）：世代号（session generation counter）替代
   size-纪元；join 自旋与世代号统一；审计迟到 unwind 降级条件
   （isWipingSession / runPhase==IDLE 时终点相位降级）。
3. **删旧路径**：重构后死代码清单核对（估算路径内联 compact→retention 是否
   可统一走装配线的低风险子集）。
4. **本地验证门恢复**：安装 JDK 或确立 draft-PR 预检纪律（守纲流程止血，
   杜绝 push-as-compiler 第四轮）。

## 不变量

- E1 新测试全部纯 JVM 可跑（无设备依赖）；
- E2 收敛后行为等价（世代号语义=旧纪元语义的超集）；
- E3 删除的旧路径有测试覆盖其新路径后再删。

## 台账

## 实施记录

- ①测试墙：RunPhasePolicyTest（转换矩阵全枚举+唯一非法序列+终点抑制三分支+
  九段全启用端到端+conv9 形状 I1 端到端含压缩豁免）；判定逻辑抽为
  RunPhasePolicy 纯函数（ChatViewModel 只记录不判定）。
- ②五件套收敛：世代号 AtomicInteger 取代 size 纪元（六写点锁内自增：清空/
  install/sendMessage/drain/取消清理 add/回退 add；读侧同锁快照）；
  终点相位降级接入 shouldSuppressEndPhase。
- ③删旧路径核对：grep 复核——重整各刀已随刀删除（prependSideSnapshotHistory/
  内联 appendRuntimeInjections/pureChat 三元/图片护栏旧块）；估算路径内联
  compact→retention 为 PR1 守纲裁定保留（UI 路径免挂起 IO），非死代码。
- ④本地验证门：本机无 JDK 且未获安装授权——确立纪律替代：**代码提交一律
  draft-PR 先行，CI 绿后才标 ready/请求审查**（本 PR 起生效）；如需装 JDK
  请拥有者示下。

## 台账

（待审后填）

### 净眼一审（agent_df9ad0fe，结论：退回 → 四项已修待窄复核）

| 编号 | 结论 | 处置 |
|---|---|---|
| P1-1 runCrossSync 用户行 add 无锁无自增（IO 线程盲写） | **采纳已修** | 入锁+自增（仿 sendMessage 模式） |
| P1-2 retryLast 截断+孤儿 GC 盲写；无 install 分支不推世代 | **采纳已修** | 截断整体入锁+自增（poppedAssistant 块外声明保下游 fork 语义）；入口推进使取消清理纪元复验必见 |
| P2-1 loadSession 重建"init 一次性"注释失真+无锁 | **采纳已修** | 入锁+自增+注释更正（压缩回退/safe-mode 重试会重入） |
| P2-2 回退 add 无纪元复验 | **采纳已修** | 等值判定，不等则跳过（install 权威补账） |
| P3-1 抑制日志不可归因 | **采纳已修** | 补 phase= 字段 |
| P3-2 expectedFromDb 计算链路测试未落地 | **挂账后续** | 计算本体在 ViewModel 非纯函数；后续抽纯函数再补测 |

净眼确认：E1/E3 签字；E2 有条件签（六签约写点内超集成立，同长度重建改进确认）；
②③④⑤ 交付质量无异议。世代号盲写点证伪全局超集声明——正是本刀要抓的，四处关账后
盲区清零。


### 净眼窄复核（agent_ea408f75，efcdc2e，结论：放行）

五项全兑现（crossSync/retryLast 截断/loadSession/回退复验/日志归因）；poppedAssistant
块外声明后下游 fork 语义逐行核对原样；无 install 分支经入口自增推进世代号——复验必见。
**盲区清零判定成立**：全量 28 写点枚举归档（8 处锁内+自增 / 循环内写点经入口推代 /
resume 被 _canResume 时序门排除）；可独立进入取消清理窗口的写者已全部签约。
非阻塞观察：N-1 loadSession 4390 锁外 clear（三名调用者全 Main 受限，功能无害，
挂后续折叠入锁）；N-2 无截断重试也自增（超集语义，备忘）；N-3 文档重复标题。

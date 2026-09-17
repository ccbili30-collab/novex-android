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

（待审后填）

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

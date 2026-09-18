# 任务书：dsh/codex 式工具调用直播（live-tool-tail）

## 背景

用户（2026-09-17，大明百鬼录建卡反馈后续）："最重要的是看不到模型在干什
么，像 zcode 能明显看到思考和在用什么工具——小字滚动、工具折叠这些显示
逻辑，参照 dsh 或 codex。"

定位：**后台常驻其实已存在**（前台服务 T166，退桌面进程不回收；通知栏已
显示工具名/tool_title/计时），缺口在显示逻辑——

1. **进行中的工具也被折叠**：foldNovexExecutionProcesses 把当前回合所有
   可折叠行（含 STREAMING/PENDING/RUNNING 的工具）合进一条收起的"工作
   记录"，15 轮建卡过程整体只剩一排静止折叠行。旧测试
   `runningFailedAndCancelledOperationsFoldWithoutHidingTheFinalAnswer`
   钉死了这个行为——本 PR 是产品决策变更（用户批），非回归。
2. **没有"正在写什么"的滚动反馈**：长静默轮（bulk 参数 5-11 分钟流式生
   成）UI 零反馈。

## 范围（本 PR）

1. **飞行中工具不折叠**（NovexExecutionProcessState.foldable）：
   `canFoldExecution() && !toolStatus.isInFlight()`——STREAMING/PENDING/
   RUNNING 的工具行留在主文流，状态落定后下一次展平收进工作行。与
   [T-thinking-live]（思考直播后收档）同语义，推广到工具行。
2. **滚动小字尾巴**（ToolCallPill + 新纯函数 ToolLiveTail）：执行中的工
   具行下方挂暗色等宽 11sp 小字（maxLines 2）——STREAMING/PENDING 取流
   式累积参数尾部，RUNNING 优先取输出尾部；bulk 工具解析窗口内最近的模
   块名（"00·启动说明 · 已生成 1234 字"），写作工具显示正文片段。
3. **卡帧约束**：只扫尾部 2000 字符窗口，绝不整串正则（bulk 参数上限
   24 万字符、每次增量都重组件）；模块计数不做（需全串扫描），显示窗口
   内最新名 + 累积字数（O(1)）。

## 不在本 PR（挂账/后续批次）

- 思考流打通（用户明确"不是思考"，本次不做；thinkingLevel 默认值问题另议）
- 常驻显式开关 + 会话列表"生成中"徽标（现状 FGS 已保后台，显式化下批）
- bulk 参数逐字滚动全览（当前为尾部两行窗口）

## 测试墙

- ToolLiveTailTest：bulk 最近模块名+字数 / 写作正文片段（含未闭引号的
  流式半截值）/ 其它工具原始尾部压平换行 / 空串隐藏 / 24 万字只扫窗口。
- NovexCardExecutionPresentationTest 改写：飞行中（STREAMING/PENDING/
  RUNNING）三行留主文流、过程文字与失败/取消仍折、statusLabel 相应；
  SUCCESS 照折。

## 台账

### 净眼一审（2026-09-18，裁决：退回修复后复审）

六场景链：直播/落定/边界/性能/Compose/测试真实性。三分类处置：

- **P0 采纳已修**（3201d2d）：toolStatus 可空安全调用，null 按已落定折叠。
- **P1-正则 驳回留痕**：净眼判 textField 正则强制闭引号致写作分支死路
  ——系误读 Kotlin raw string 收尾边界（末尾三引号是字符串定界符、不是
  正则内容）；当前正则无闭引号要求，CI 的 writing 用例实证。若 CI 红则
  按净眼修法改。
- **P1-恢复态残留 采纳**：journal 重放的历史 PENDING/RUNNING 块新规下
  永不折叠、永久挂 shimmer+stop——AssistantToolUse 增加 messageIsStreaming
  字段，飞行豁免只对活流成立；补回归用例。
- **P2-label 采纳**：豁免后 statusLabel 的 states 分支不可达、退化"查看
  记录"——AssistantProcess 增 liveToolStatuses（flush 收集被豁免飞行态），
  标签优先判"进行中"；断言同步。
- **P3 采纳两小项**：process.key 稳定性断言补回；N 字计数为 JSON 字符数
  （非正文长度）措辞不改——挂账（与 nameField 80 上限同批）。
- 记录：RUNNING 优先输出尾巴的分支在活链路等价 args 尾巴（执行期无增量
  输出）——设计冗余保留，工具输出流式化时自然生效。

- 守纲六问：待
- CI：704dc2e 红（可空调用，3201d2d 修复）；净眼关账 de10284 待终版

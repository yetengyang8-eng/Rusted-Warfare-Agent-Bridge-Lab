# 八爪鱼 G1 时间与 Trace 契约

G1 从 `be7ba94` 迁移现有系统的可观测性。它不建立 WorldState、派生事件总线、owner generation、Intent 队列、调度器、FREE 或 General，也不增加 Capability 战略行为。原 decision/command gate、lane 顺序、策略参数、原生读取次数和原生命令校验保留。

## 身份与兼容格式

Battle JSONL 的原 `wallTimeMs / event / data` 保留，默认新增顶层 `trace`；附加记录以 `g1_` 开头。旧解析器可继续读取原字段。`-Drwagent.g1Trace=false` 关闭新增日志与顶层 Trace，适合比较/回退观测输出；它不会恢复旧 JAR，也不能承诺 Strategy 的可选快照分配开销为零。

`schema=rw-g1-trace-v1`，每次客户端运行生成独立 `runId`。Observation、Event、Intent、Command ID 分别为 `runId:o:N`、`:e:N`、`:i:N`、`:c:N`，即使同一 source game time 重复读取也有不同 ID。现有 taskId/needId/unitId 和可追踪 commitmentId 保存在 context 中，组合 runId 后解释；不能脱离运行身份跨局拼接。

`intentId` 仅标识当前直接命令提案，`intentSemantics=TRACE_ONLY_DIRECT_PROPOSAL`，没有等待队列或调度优先级。`ownerGeneration=null`、`ownerGenerationStatus=NOT_IMPLEMENTED_G3`，不输出假世代。

## 三种时间

| 字段 | 定义与限制 |
| --- | --- |
| observation.sourceGameTimeMs / sourceFrame | 仅复制该 GET 响应本身的原生字段。生产菜单没有这些字段，因此为 null。不同端点不原子同帧。 |
| requestedWallTimeMs / receivedWallTimeMs | 客户端 HTTP 请求前、收到响应后的墙钟；只描述读取区间，不换算原生时间。 |
| detectedAtGameTimeMs | 最新 `/state` 的原生时钟锚点，basis 明确为 `LATEST_STATE_SAMPLE_NOT_NATIVE_RECEIVE_TIME`；其他读取可能已有更晚 source time，不插值补接收时刻。 |
| loggedAtWallTimeMs | 写这条记录的墙钟；不是战术时钟。 |
| occurredAtGameTimeMs | G1 未证明真实事件发生时刻，始终 null / NOT_PROVEN_BY_G1。不能将差分检测或旧日志 gameTime 升级为出生/死亡/完成时刻。 |
| sourceRangeStart/EndGameTimeMs | 前一次同 canonical endpoint 读取至本次 source time。完整 requestPath 保留；query 可能不同，标为读取样本区间，不能解释为同一实体事件发生区间。 |

GameClock 是观测时间身份的统一载体，现有策略继续使用相同 `/state.gameTimeMs` 和原 Stamp；本轮没有迁移调度。它只保存最多 32 个端点的时间身份，不保存单位事实。session/player 变化、源时钟/frame 回退、重复源 Stamp 和墙钟回退只写 discontinuity 标记；旧控制器的原检查仍生效。长 gap 阈值、覆盖恢复和重放归并留给 G2，不在 G1 发明判据。

## 命令因果

1. 原 GET 日志的 observationId 保存原生 source identity、请求区间及前一读取身份。
2. `g1_command_attempt` 保存 CommandSpan、现有 owner、actorIds、sourceStamp、task/need/commitment context，以及原 arbiter 的 ADMITTED/DENIED 结果。命令门禁拒绝也有记录，不改变其 slot 消耗规则。
3. 原 `action` 和 `command_result/command_rejected` 共用 commandId/intentId。nativeRequestId 是发到 bridge 的 wire request correlation；receipt 本身的 requestId/frame/session/gameTime 另行保留，缺失不补猜，echo mismatch 只记录，不新增 guard。
4. 后续证据引用同一 span，并携带新 observationId。receipt 一律 `receiptProvesExecution=false`；后帧 order/位置、队列、成品匹配各自说明能证明什么。

`inputObservationIds` 是该提案时可用的近期读取上下文，明确标为 `AVAILABLE_READ_CONTEXT_NOT_EXCLUSIVE_CAUSAL_PROOF`。它不是完整程序数据依赖图；某次旧 plan 仍可能出现在上下文中。sourceStamp、现有 task/need 与 paid commitment 提供更具体关联，不据“同时出现”宣称独占因果。

| 代表路径 | G1 新增证据 |
| --- | --- |
| Recon | lease 状态；既有 MoveExecution 计算的 ACTIVE_ORDER / ARRIVED_AFTER_ACCEPTANCE，经同 requestId 找回命令；不新增移动证明算法。 |
| LocalCrisis | UNASSIGNED→RESPOND→RETURN→RELEASED；当前仍活着且持有 lease 的 observedResponderIds，与原 originalActorIds 分开；只有后帧才能记录 order/返回位置 witness。超时释放不写到达。 |
| 普通 production | ordinary-production:factoryId:accepted-state-time 对应命令；Pending 引用原 span，忙队列与忙后空分开；productReadyProven=false、producerProductLineageConfirmed=false。不新增产品出生匹配。 |
| 工程师/两栖 | 现有 Need/Worker/Purchase 净变化、付款 commitment、ready 首匹配的候选数量/歧义；ready 引用构造 span，不改变原首选。mode witness 分别保存原生 movement/ae、目标 domain 与 compatibility，不从 range 或旧 DIVE 标签推模式。 |

构造与队列 commitment ID 只用于关联，不参与付款、防重复或容量计算。Strategy 状态快照只记录原 observe/act 调用前后的净变化，不保证捕获调用内部每个短暂状态。mode 记录若关联最新 owner 命令，明确是关联证据，不能宣称那个命令独占导致模式变化。

所有 receipt/commitment 查询表最多 256 条，witness 去重集合最多 512 条。既有 Pending 可直接持有 span；晚于查询淘汰的独立记录可能无 command link，不应补假关联。可选 sidecar 写入失败输出 `G1 trace sidecar failure`，验收必须检查为零；它不改变原 policy。原 legacy 日志写入失败仍按旧行为中止。

## 验证与 G2 边界

focused Java 合同覆盖缺失源时间、不同端点、同时间不同 ID、session/frame 回退标记、receipt/后续 witness、owner generation 缺失、输入不变和有界存储。四条 HTTP 时间线比较 Trace on、off 和冻结 `be7ba94` JAR 的完整命令顺序/参数/owner/gameTime 与全部旧 event/data，仅归一化 request UUID 和墙钟测速/请求耗时。

这证明相同确定性输入下的策略和命令等价；新增 JSON 与 flush 会消耗墙钟，不能证明自然局采样间隔、胜负或性能完全相同。完整 Windows 矩阵与原生 fixture 是本轮独立结果；自然桌面未运行时必须写 NOT_RUN，不能继承旧自然记录。

G2 可消费 observationId/sourceStamp/requestPath/独立源时间、前一次读取身份和 discontinuity 标记，建立合法只读 WorldState 与确定性快照差分。它必须另行定义 authority、长 gap、会话重置、去重/乱序和 event 的实体范围。G1 的事件 ID 只是日志身份，不是已实现的派生事件总线；owner generation、批内资金/slot、预算累积、调度迁移仍严格属于 G3。

G2 的具体输入应是“经过现有 session/活动局等校验的原始响应 Map + 不可变 GameClock.Observation”，而不是从 Trace context 反推单位事实。G1 在 GET 日志阶段记录时间身份，记录存在不表示该响应已通过下游控制器全部校验。G2 输出首先只读或双写比对，旧行为继续使用原响应；不能将它扩展成跨端点同帧事务，或借新事件改变现有 gate/优先级。confirmed enemy death、队列到成品血缘等缺证字段仍保持 UNKNOWN。

四条 focused fixture 导出的 JSONL 体积，Trace on/off 比值约为 2.25～3.01；这是测试导出格式的体积比较，不是自然局耗时/APM 的对照结果。后续需要另行测量真实采样成本，再决定日志压缩或采样方案，G1 不为此改变行为门槛。

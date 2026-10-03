# 八爪鱼 G3 / G3.5 Execution–Intent 契约

本契约描述 `codex/octopus-g3-execution-20261002` 的实际接入。从已验收 G2 `cdb9252e` 延续；实施提交 `5678998`，集成提交 `95a917e`。战争目标、生产偏好和原生 bridge 最终合法校验沿用原实现。本轮改变的是出令吞吐、批内记账和执行身份。

## 时钟、观测与控制权

- `CommandArbiter.Stamp(session, player, frame, gameTimeMs)` 是当前合法 `/state` 执行锚点。独立 endpoint 的 `GameClock.ObservationId`、source game time、检测时间和 wall time 保持 G1/G2 语义，不能合并成原子同帧世界。
- `WorldState.epoch` 表示事实覆盖生命周期；`ownerGenerations[actorId]` 表示控制权版本。两者独立。
- actor 初始 generation 为 0。首次 claim、release、真实 transfer、clear 持有控制权都递增；同 owner 重复 claim 不递增。释放后保留版本，A→默认 owner→A 无法复活旧 Intent。原 lease 的释放继续通过同一 arbiter；没有新增 lease 策略。
- 现有 session/player 或 frame/gameTime 回退 guard 继续 fail-stop。适配器能描述 reset 不代表运行中自动跨局重建 scheduler；需要新客户端实例。
- Intent 执行前再次校验 generation、当前观察、己方 actor 和 ownership。WorldState UNKNOWN 不补猜，不增加预算或可用容量。

代码入口：[CommandArbiter](../agent/src/io/rwagent/client/CommandArbiter.java)、[GameClock](../agent/src/io/rwagent/client/GameClock.java)、[WorldState/Event 旧契约](OCTOPUS_G2_WORLD_STATE.md)。

## Intent 与结果

`Intent` 是不可变提案，至少包含：

| 字段 | 实际意义 |
|---|---|
| `intentId` | 当前运行中单调生成的提案身份 |
| `owner`, `ownerGenerations`, `actorIds` | owner 与逐 actor 的提案时版本快照；多 actor 不使用一个虚假的集群 generation |
| `kind`, `lane`, `priority`, `path` | 命令类型、原 caller lane、确定性顺序标记、既有 bridge 路径 |
| `observation` | 当前合法执行 Stamp |
| `sourceObservationId`, `sourceRequestPath` | 报价或命令输入的实际读取来源；菜单缺少原生 time 时仍为 UNKNOWN |
| `commitment` | `spending`, `credits`, `producerSlots`, `militarySlots`；未知价格为 null，不作为免费动作 |

取消原因在 `ExecutionScheduler.Result.cancelReason` 中，不修改已建 Intent。结果还记录 `nativeAttempted`, `nativeAccepted`, `commitmentApplied`, `transportUnknown`, `receipt`。`executionWitness=null`；真实后续 witness 通过 G1 CommandSpan/commitment/原状态机另外关联。

典型本地拒绝：`STALE_OWNER_GENERATION`、`STALE_OR_FOREIGN_OBSERVATION`、`STALE_EXECUTION_BATCH`、`ACTOR_CONFLICT_IN_OBSERVATION`、`INTENT_ALREADY_ATTEMPTED`、`UNKNOWN_PRICE_COMMITMENT`、`UNKNOWN_EFFECTIVE_CREDITS`、`UNKNOWN_PRODUCER_SLOTS`、`PRODUCER_SLOT_BUDGET`、`MILITARY_SLOT_BUDGET`、`COMMAND_GAME_TIME_BUDGET`。本地拒绝不消耗 native attempt token。

## 已接入的调度顺序与预算

BattleClient 的中央 `post(owner,path)` 在 G3 模式生成 Intent，统一进入 scheduler，再调用原生 HTTP bridge。运行时同步执行，caller 收到实际 native receipt；没有把“已收集/稍后执行”伪装成 queued receipt。

caller traversal 保留既有 lane 顺序：builder recovery、critical retreat、LocalCrisis、local recovery、Strategy、economy、Recon、ordinary production、late Recon/tactics。同 actor 的**首次实际 native attempt**取得本批冲突位置；明确原生拒绝也算一次尝试。不同 actor 在预算内继续执行。lane 的 numeric priority 是身份/排序元数据，不能撤回已发送的低优先级命令。

`dispatchBatch()` 提供 priority 降序、lane/id 稳定排序，并已有 focused tests；BattleClient 当前采用同步 `dispatch()` 和固定 caller 遍历，**没有全局延后收集后排序，也没有异步线程池**。两个入口的裁决边界必须区分，不能声称运行时已按全局提案优先级抢占。

预算按 elapsed **game time** 每 1000ms 累积 1 token，初始 1，默认 burst 4；`rwagent.executionBurst` 在 BattleClient 约束为 1–16。保留未凑满 interval 的余量；长 gap 只补至 burst。wall time/poll 次数不增发预算。重复 native game time，即使 frame/ObservationId 更新，也不返还 token、actor 位置或资源。

原 `produce()` 首厂成功即返回、主链 else-if 已在 G3 模式拆开，主循环实际能给出多个不同 actor 的 Intent。`rwagent.g3Execution=false` 保留旧 gate 和旧 caller 分支，以便确定性对照。

## 钱、容量与原生边界

每次合法执行观察建立 ledger；新的 game time 使用已对账的原生资金/容量和原有 ghost，重复 game time 只取保守最小值。菜单刷新可登记新 producer，但不能给本批已扣容量充值。

- native accepted/queued 即时扣 credits 与声明 slots。后续 caller 看保守 effective credits；原始响应继续用于原有对账和 WorldState。
- native 明确 rejected 只消耗 token/actor，不扣资源；transport 异常、无明确结果或矛盾 receipt 保守保留本批 commitment，然后沿原客户端错误路径退出。没有假定失败必然退款。
- 普通工厂 producer slot 只从已校验原生 queue≥0 产生；`queue=0` 只表示这个队列当前采样为空，**不表示产品 ready**。
- 移动工程师的原生 `productionQueue=-1` 表示没有该工厂队列接口。施工 slot 使用现有 worker `paidConstruction` 合同，投资 slot 使用现有 Purchase/pending 合同；不把 -1 变成空闲 factory。
- 保留原 Pending、Purchase、paidConstruction、付款先于队列可见、artillery ghost 与 funding holds。军事 slot 来自原 army cap/target 对账，不能用新 scheduler 消除已付款未出现占位。
- 原生 session、actor、菜单/action、价格/资金、队列、地形/水域和兼容性校验保持最终权威。native bridge 源码本轮未改。

代码：[Intent](../agent/src/io/rwagent/client/Intent.java)、[ExecutionScheduler](../agent/src/io/rwagent/client/ExecutionScheduler.java)、[BattleClient](../agent/src/io/rwagent/client/BattleClient.java)。

## Trace 与导出开关

新增 `g3_batch`、`g3_intent`、`g3_execution`、`g3_cost_summary`。`battle_config` 声明 interval/initial tokens/anchor/burst，报告审计据此核对累计预算和同 actor 冲突，不再套旧“每两条至少隔 1000ms”规则。

G1 CommandSpan 增加 `executionIntentId`、逐 actor generations 和 commitment；后来释放/转交 actor 时，witness 仍保留**提案当时** generation。receipt、采样字段与真实发生时间继续独立，accepted 不变执行完成、native mode、生产血缘或确认击杀。

| 配置 | 实际效果 |
|---|---|
| 默认 G3 + G1/G2 导出 | 新执行层与事实更新均开启 |
| `-Drwagent.g2WorldState=false` | 关闭 G2 导出；G3 所需 WorldState 仍计算 |
| `-Drwagent.g1Trace=false -Drwagent.g2WorldState=false -Drwagent.additionalDiagnostics=false` | 关闭 G1/G2 与附加原始采样、G3 提案/批次导出；保留动作、结果、原业务 witness 和末尾成本摘要；G3 必需事实仍计算 |
| `-Drwagent.g3Execution=false` | 旧执行 gate/caller；此时 G1/G2 开关沿历史语义，事实层不参与决策 |

这些开关不保证零分配。nanoTime 的 sampling/world/execution 范围含网络与日志，并可能重叠，不能相加或称为 CPU。

## G3.5：成熟两栖链与 SearchArea 边界

现有 Need→engineer provider→funding→jet ready→response/Dive/Fly/return 原合同经 Strategy Host 连到共享事实 provenance、claim/generation、Intent、scheduler 与 Trace。WorldState 用于合法来源说明；不借它重新设计目标或从 UNKNOWN 推断生产血缘。

`CapabilityTask` 是集体目标；每个成员独立 `CapabilityUnitMode`，状态包括 UNKNOWN/AIR_READY/MOVE_TO_WATER/DIVE_REQUESTED/SUBMERGED_READY/FLY_REQUESTED/REPOSITIONING。水上成员可先 Dive，陆上成员另行移动，不等待整群同步。请求和 queued receipt 只推进 requested phase；原生自己单位 mode/movement 的新鲜、匹配 actor 字段才形成 witness。保存的是 last explicit native witness，不能视为永不过期的当前模式。

`SearchAreaNeed.lawful()` 要求三个显式输入：battle ongoing、陆军没有合法可达目标、合法地图/探索覆盖。提供 coverage、lastSearched、confidence 老化；`SearchTask` 按稳定 actor ID 分配不重叠 x 区域，只有新鲜 `/combat/observe` 可见且区域内的合法敌人才转 TargetTask。尚未胜利不能生成隐藏敌坐标。

SearchArea **未激活**。当前缺少把既有地图/探索来源组合为这三个触发事实的正式 adapter、覆盖/置信度阈值验收，以及水下发现是否需要 Dive 的原版证据（`NEEDS_EVIDENCE`）。契约不声称已能自动终局跨域搜索。没有新增 Specialist Pool/生产血缘匹配、General/FREE/JOINING/ThreatTask/HOT/COLD 或 G4。

代码：[StrategyDirector](../agent/src/io/rwagent/client/StrategyDirector.java)、[CapabilityTask](../agent/src/io/rwagent/client/CapabilityTask.java)、[CapabilityUnitMode](../agent/src/io/rwagent/client/CapabilityUnitMode.java)、[SearchAreaNeed](../agent/src/io/rwagent/client/SearchAreaNeed.java)、[SearchTask](../agent/src/io/rwagent/client/SearchTask.java)。验证与候选身份见 [G3/G3.5 handoff](../handoff/HANDOFF_Octopus_G3_G35_2026-10-02.md)。

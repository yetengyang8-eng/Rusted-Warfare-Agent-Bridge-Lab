# 八爪鱼 G2：只读 WorldState 与 Event Adapter

G2 从已验收的 `ff693c8` 开始。`GameClock.Observation` 与经过原调用者校验的原生响应构成输入；`EventAdapter` 保存共享、不可变的 `WorldState`，并输出快照差分事件。BattleClient 只双写和记录，旧策略继续读取原响应。没有事件订阅驱动策略、owner generation、Intent Scheduler、command gate、批内资金/slot 或新战争行为。

## 输入与接入边界

`/state` 在原有活动本地局、session、frame 与 execution 校验完成后接入。其他 GET 在原有 `check` 的 session 校验通过后接入；身份必须是刚读取的同一个响应 Map。POST receipt 不接入。原本没有该校验入口的辅助 GET 不因本轮新增校验，仍在世界模型覆盖范围之外。G1 的 Observation 日志单独存在不能证明它已进入 G2。

每个源保留自身 endpoint、完整 requestPath、ObservationId、原生 source game time/frame/session/player，以及请求和接收墙钟。不同查询范围分别维护，世界不声明跨端点同帧。`/combat/observe` 没有 player，`/scout/observe` 没有原生 game time，生产菜单没有原生 frame/game time；缺失字段保留 null。绑定当前已校验的玩家上下文不等于补出原生 player。

适配器 API 的 session/player 切换和时钟回退处理，与 BattleClient 的退出规则是两件事。真实客户端仍在既有 session/frame guard 拒绝时停止；这些拒绝响应保留在 G1 原始日志，不进入 G2。纯合同测试可以独立验证适配器的重置协议，本轮没有把客户端改为自动跨局或帧回退后继续战斗。

## 事实权威

| 来源 | 可以保存的事实 | 不能推导的事实 |
| --- | --- | --- |
| `/state` | 己方单位快照、HP/maxHP、完成度、tech、productionQueue、玩家及 match | 缺席单位的准确死亡原因、出生或死亡发生时刻 |
| `/combat/observe` | 当前合法可见敌军及其采样时间；历史情报单列 | 隐藏敌当前 HP/位置/domain、逐敌确认击杀、General 战果归因 |
| `/scout/observe` | 该读取的可见性、资源与原生侦察附件 | 与 state/combat 原子同帧、没有返回的时间或地形事实 |
| `/combat/production` | 返回的工厂菜单/队列附件 | 菜单缺席代表单位消失、queue=0 代表产品 ready 或释放已付款 ghost |
| 已校验的其他菜单/模式/接近接口 | 按请求范围保存原生附件 | 扩大为全体己方或全体敌军覆盖、路径等于执行完成 |

当前事实与 `LastObservation` 分开。历史只保留最后合法证据，必须带原 observation 身份，不能冒充新采样。源 freshness 与 coverage 独立，UNKNOWN 是可见的状态，不以 0、空集合或推算时间代替。

## 事件与时间

差分事件有独立 G2 ID、来源 ObservationId、前一比较 ObservationId、实体范围、证据等级和检测时间。`occurredAtGameTimeMs` 保持 null。只有具有连续、可比较覆盖的同源快照才派生跨样本变化；reset/gap/冲突或未知覆盖不得批量制造死亡、失联或完成事件。

`ENEMY_CONFIRMED_DESTROYED`、确认敌损、施伤者归属、严格生产血缘仍是 `NEEDS_EVIDENCE`。建筑旧址清场和队列变空分别保留窄语义。通用单位 ready 观察也不自动成为已匹配的 Specialist Pool 产品。

## 行为和运行开关

默认开启只读双写。`-Drwagent.g2WorldState=false` 关闭 G2 收集和日志，保留 G1；`-Drwagent.g1Trace=false` 同时关闭 G1 输出和 G2。后者符合 G1 比较入口，不承诺恢复旧 JAR 的全部分配成本。

新增 JSONL 行为 `g2_world_update` 和 `g2_event`，原有日志字段与 G1 Trace 保留。World update 是摘要，完整只读事实由 WorldState API 提供，避免每次把所有原生 payload 重写入日志。G2 失败输出 `G2 world sidecar failure`，不改变旧策略；验收检查失败计数为零。

新增深拷贝、差分与日志会消耗墙钟。确定性输入下命令/旧日志等价不证明自然局轮询完全等价；自然局、胜率和真实吞吐需要另行验证。

## 后续接口边界

G3 可以消费只读快照与事件身份，但必须另行施工 owner generation、Intent 收集与执行准入、同 actor 冲突、有界预算以及资金/slot 占位。WorldState 的 reset epoch 只标识事实覆盖生命周期，不是 owner generation。现有 gate、原直接命令路径与已付款占位继续保留。

## 公共 API 与身份

```java
EventAdapter adapter = new EventAdapter(runId);
EventAdapter.Update update = adapter.accept(observation, validatedResponse);
WorldState world = update.snapshot;  // 与 adapter.snapshot() 相同的只读契约
Map<Long, WorldState.UnitFact> own = world.ownUnits();
Map<Long, WorldState.EnemyFact> enemy = world.enemies();
Map<String, WorldState.SourceView> sources = world.views();
List<EventAdapter.DerivedEvent> events = update.events;
```

输入 Observation 必须来自同一 `runId`，形如 `runId:o:N`，N 为正整数。原生 session/player/time/frame 必须与载荷一致。G2 事件身份为 `runId:g2:epoch:e:N`，与顶层 G1 Trace EventId 是不同层的身份；两者共享运行与输入身份。`epoch` 是世界覆盖重置编号，`revision` 是世界投影版本，均不控制任何单位。

`/state.player.teamId` 必须真实存在且为有效整数，不能用通用 player.id、legacy-local 或默认 0 补出身份。combat 需要自身 native gameTime 才能校准可见性；Scout 可只提供 frame，但 frame/gameTime 同时缺失时不形成可见性覆盖，即使数组为空也不能派生失联。

`SourceView` 保存不可变原生 `payload`、Observation、authority、coverage、nativePlayerId 与 contextPlayerId，以及独立的 game/wall freshness。原生 payload 是读取附件；其中的历史行、无效源或过期源不能作为重校准后的当前事实。`UnitFact`/`EnemyFact` 提供经过对应源校准的 `current` 与单独 `LastObservation`。失联或覆盖失效时，current 为空、字段读取为 null，状态明确给出未知/缺席原因。

`contextAgeGameTimeMs` 是相对于检测锚点的覆盖年龄，另列 basis；它不填补未知的 `ageGameTimeMs` 或原生 source game time。源时间未知和读取墙钟近期可以同时成立。

由原生 `enemyIntel.lastKnown*` 初次导入的历史，标为 `NATIVE_LAST_KNOWN_HISTORY_ONLY`；其 Observation 标识承载历史的这次读取，原 lastSeenGameTimeMs 单独保存，不冒充原始历史观测的身份。它不填当前隐藏属性。

## 去重、顺序与恢复

- 同 ObservationId 重放不应用；同 ID 不同载荷是冲突。较旧的同运行 Observation 序号不能覆盖较新同范围读取。
- 新 ObservationId、相同原生 stamp、相同内容是一次新读取。它更新读取 freshness 和 LastObservation，不重复发域变化事件。相同 stamp 不同内容使该源覆盖失效，后续合法样本重建基线。
- 没有源时钟的菜单按读取身份与请求顺序处理，不伪造原生时间。不同完整 requestPath 分开；泛化的有序数组保留顺序，实体集合按 ID 校准。
- `/state` 的新合法 session/player 或新的源时钟回退重置事实 epoch；已退休 session、延迟读取不反向重置新上下文。非 state 源的回退使该范围失效并重新建立基线。重置不产生批量单位消失事件。
- 默认覆盖断档阈值是 30,000 game ms / 15,000 wall ms，可由 EventAdapter 构造参数覆盖。它们只控制观察证据，不是战略阈值。缺 source game time 时可用 G1 检测锚点识别覆盖断档，basis 为 `STATE_DETECTION_ANCHOR_NOT_SOURCE_TIME`，原生时间仍为 null。
- 跨断档或无效源恢复，不派生死亡、失联、ready 转换或队列清空。恢复时读到的 ready 单位可以作为新观察记录，明确与跨断档完成事件不同。
- source 比 state 锚点更晚时，game age 为 null / `SOURCE_AHEAD_OF_STATE_REFERENCE`；墙钟回退的 age 为 null / `UNKNOWN_WALL_ORDER`。不把负 age 截成零来制造精确新鲜度。

源范围最多 128；历史单位/敌记录目标上限各 512，只淘汰历史，不为了额度丢弃本次仍可见或可用的集合。已应用 Observation ID 最多 512，退休 session 最多 64。源或历史淘汰有单独事件，不代表死亡。缓存之外的重放拒绝仍依赖范围顺序/上下文；不宣称跨无限历史的恰好一次处理。

## 最低事件字典

| 事件 | 证据 | 范围及限制 |
| --- | --- | --- |
| `UNIT_FIRST_OBSERVED` / `UNIT_REOBSERVED` | DERIVED | 本 epoch 第一次见到 / 历史 ID 重现，不能当出生 |
| `UNIT_REOBSERVED_AFTER_COVERAGE_GAP` | OBSERVED | 中断覆盖后重新看到原单位，只证明当前样本，不补中间转换 |
| `UNIT_READY_OBSERVED` | DERIVED | 读到存活且 buildProgress ≥ 1；新观察或连续完成度变化，lineage=false，Specialist 类型标签不等于 Pool 匹配 |
| `UNIT_BECAME_UNAVAILABLE` | DERIVED | 明确 dead、HP≤0 与完整己方样本缺席分开，缺席的 confirmedDead=null |
| `UNIT_HP_BAND_CHANGED` | DERIVED | 己方 HP/maxHP 的 0–10 十分位，纯观察定义、不是策略阈值；敌无 maxHP，不出该事件 |
| `QUEUE_CHANGED` / `QUEUE_BECAME_EMPTY` | DERIVED | 仅连续 `/state.productionQueue`；ready/血缘/ghost 释放均不证明 |
| `ENEMY_OBSERVED` / `ENEMY_UPDATED` | OBSERVED | 仅合法 visibleEnemies 且行 lastSeen 与该端点 source time 相符；过期 domain 标 UNKNOWN |
| `ENEMY_LOST_VISIBILITY` | DERIVED | 连续 combat 样本中的可见→不再可见；不证明死亡 |
| `ENEMY_SITE_CLEARED` | DERIVED | 原生 CLEARED 建筑历史、旧址当前可见证据；不证明击杀 |
| `LOCAL_THREAT_OBSERVED` / `LOCAL_THREAT_BECAME_NOT_VISIBLE` | OBSERVED / DERIVED | Scout 当前可见武装子集；不清除旧 LocalCrisis 或建立新 ThreatTask |
| `WORLD_CONTEXT_RESET` / `WORLD_CONTEXT_INVALIDATED` | DERIVED | 世界身份/覆盖生命周期，非控制权世代 |
| `SOURCE_COVERAGE_GAP` / `SOURCE_COVERAGE_INVALID` | DERIVED | 断档或证据冲突；禁止跨断档负事件 |
| `SOURCE_VIEW_EVICTED` / `HISTORICAL_RECORD_EVICTED` | DERIVED | 容量淘汰，非死亡或失联 |

事件由输入顺序、固定来源处理顺序及实体 ID 升序确定；同一实体的固定事件顺序由适配器定义。一个 accept 只作一次同步校准，没有订阅回调或递归级联，不重排跨端点历史为虚构的全局同帧事件流。

最终证据：Windows 60/60、448 Python tests、WorldState 73 checks、独立复核 14 checks。见 [G2 handoff](../handoff/HANDOFF_Octopus_G2_2026-10-02.md)、[本轮 evidence](../evidence/octopus-g2-2026-10-02/README.md)与[真实 fixture 事件示例](../evidence/octopus-g2-2026-10-02/selected-event-examples.json)。这些入口不会继承 G1 或更旧 JAR 的自然局证明。

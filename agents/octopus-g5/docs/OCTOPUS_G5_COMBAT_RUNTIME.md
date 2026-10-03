# Octopus G5：Combat / Commander / Runtime 契约

G5 默认开启，依赖 G3/G4。`rwagent.g5=false` 恢复 G4.1 controller、1000ms game-time decision gate、1000ms token interval 与 burst4；旧合同 suite 显式测试该路径。`rwagent.runtimeAdaptive=false` 仅关闭 transport/cache/adaptive cadence，保留 G5 兵力语义。

## 调度与时钟

轻量 state loop → 独立 combat/scout transport workers → 主线程串行验证/更新 → Force collect/priority/dispatch → ordinary production → 到期/dirty Strategy+Economy → Recon → optional screen/logistics/FREE staging。Controller/WorldState/ownership 从不跨线程修改。两个 HTTP response 各自保留请求/接收 wall time、原生 frame/gameTime/session/player；不组成原子世界。

- 默认 decision gate 250 game ms；按相邻真实 game/wall 样本调整 wall sleep，10～100ms。重型运营至少每1000 game ms，己方 roster/readiness/queue 与 credits500 bucket 的变化可立即触发。
- G5 token interval 默认250 game ms，初始1 token，burst16；保留 G3 同 observation actor 消耗、owner generation、native credits/slots、unsettled credit 和 paid-before-visible/ghost 约束。增加 rate 并不释放 commitment。
- `/combat/production`、每个完整 engagement query、investments/unit-modes/builder menus 只在本 cycle cache，保存原 payload 和原 observation 对象；下一 cycle 清空。`/state`、combat、scout visibility、动态 plan 不冒用菜单 cache。重用来源不刷新 gameTime/observationId。
- 原始 trace 保留，每轮集中 flush；诊断不是新的命令 admission 或世界 authority。
- `crisisCompatible` 按最多48 actors 发独立 engagement 请求，逐块验证当前 visibility、目标来源时间、位置、兼容和 known approach；未知块不进入可发令名单。Strategy 的既有48分块保留。

Force 内部真实优先级：Critical90 → General Retreat85 → LocalResponse70 → JOINING50 → General30 → FORMING screen20 → builder approach15 → FREE staging10。primary 与 optional 分两次派发；成熟经济/Strategy/production/Recon仍 immediate，**不是全局 collect-all scheduler**。Builder recovery在 force之前保留既有高优先级。串行 controller 配合独立 transport 和独立 actor burst，实现同一合法状态下多个模块推进，不宣称线程并行操纵单位。

## 独立 General 战斗

每个 General 独立 CombatLedger/crisis/current goal/rally/accepted commands。own current HP、严格后帧位置、当前可见威胁是合法输入；单位缺席不计确认死亡。只在同一 current-visible 连续样本观察 enemy HP弱下降，不归因于本 General，不产生kills/KD。

`NORMAL ↔ PRESSURED`；局部可见 armed enemy HP / own HP ≥1.8，或连续≤5s observation 窗口内 own HP drop≥own maxHP25%/两例显式 own death → `OVERMATCHED / LOSING_EXCHANGE → RETREATING`。这是透明、保守的 **HP proxy**，不是武器/射程/阵型/实际战斗力估算。压力半径600 world units。

撤退向地图边界内 home-side geometry rally 发 priority85 native move。rallySafety 与动态可达性保持 UNKNOWN，接受回执不算到达。既有 matching move 不重发；新 crisis/目标变化可即时响应，小HP差分不绕 normal3s实际回执 cooldown。

只有当前合法敌情 pressure≤0.55、短窗口 damage≤own maxHP2%、≥75%健康成员取得本 generation 对应 move 回执后的 rally位置 witness，才 `RETREATING → REGROUPING`。健康至少6（或目标更小）、HP≥maxHP60%、保持4s当前平静后恢复 NORMAL。>5s观察gap不能制造5s战损率或连续平静，记录 UNKNOWN gap delta。LastObservation不创建新攻击/overmatch，也不能清除保守撤退。

G5 CombatLedger / Commander 的健康统计要求 ready、NORMAL、known maxHP、HP≥50%。G4.1 成军门禁继续复用 GeneralRegistry.healthyAttachedStrength：当前帧健康角色 NORMAL、真实 ATTACHED、有效 General owner/generation；该成军计数没有新增 HP≥50% 门槛，不能与 G5 战斗健康计数混用。Critical HP25%与既有恢复角色继续由更高优先级路径处理。

## Commander / ThreatTask / 多 General

G4.1第一 General仍采用同ID FORMING→ACTIVE。现有有效 Generals均 `members + reservations >= desiredStrength` 且至少6个当前 G5 健康ready ordinary FREE，才允许本 observation 创建一个新的 FORMING General；默认desired24，没有将军数量上限或六人碎片化。创建本身不把新兵直接ATTACHED；仍经过FREE同帧屏障 → PENDING_JOIN/JOINING → 实际回执 → 后帧位置见证 → ATTACHED → GeneralRegistry 当前健康 ATTACHED 计数达到6后 ACTIVE。

Commander只分配兵力与共享合法 visible ThreatTask attention。不同General的owner/roster/ledger/crisis/goal始终独立。当前完整合法样本中威胁失联是CLEAR(not killed)；foreign/stale/partial/malformed是UNKNOWN。任务关注不合并 command stream。

补兵考虑crisis、retreat、健康缺口、desired、距离与urgency，reservation先计入目标兵力。退却General的JOINING目标必须来自当前同session/player/frame/gameTime combat rally override；未知override不退回受压接触点。rally目标/source/generation变化可重定向JOINING，已接受相同目标保持cooldown。PENDING_JOIN+LOCAL_RESPONSE共存、visible clear保留pending、JOINING不被普通骚扰重抢、General失效释放与ABA保护保留。

无分配FREE仅以priority10进行home-side staging，正式分配覆盖它；LocalResponse结束从General临时脱离者仍先FREE，不自动归还。SearchArea仍contract-only；两栖requested/desired/physical/weapon/terrain证据不合并。

## Trace / Observer

保留G1/G2/G3/G4 raw contracts。新增 `g5_general_transition` 带generalId、before/after、reason、source clocks/observationId；`g5_commander_transition`记录birth/reservation/attention与合法性原因。`g5_runtime_state`记录真实己方经济/队列、FREE、各General状态/命令/reservation/threat，以及runtime metrics。queue字段本身不是完成证据，面板只把factory/commandCenter行计为生产设施。

Runtime指标：整个run平均decision间隔(game ms)，真实GET平均latency(wall ms)，真实POST平均latency(wall ms)，accepted commands/game minute，max accepted commands per legal observation，cache hits、parallel reads。旧summary effectiveDecision字段是recent/attention统计，不能覆盖G5全局平均。双方对照的action→receipt日志时差另列，不混称POST transport时间。

人工入口：`tools/RW-Agent-Octopus-Test.bat`连接/等待现有合法游戏；操作者显式运行`RW-Agent-Octopus-Start-Test.bat`才复制并启动隔离游戏。root开发不会执行桌面入口。详见human validation契约。

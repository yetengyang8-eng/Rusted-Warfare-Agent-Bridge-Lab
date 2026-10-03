# 完整对战模式

## 行为与边界

Match 从标准本地开局自动建一座陆军工厂、生产一辆坦克，再新增六辆坦克和最多一座当前可见矿。缺乏合法可见矿时允许无矿完成短开局。完成后接管己方战斗单位；已有闲置工厂则直接接管。

Battle 通过当前可见敌军和曾见敌军的位置记忆寻找目标。敌军重新隐藏后不刷新其位置、血量或其他属性；移动敌军记忆最多保留 20 游戏秒，建筑位置记忆保留至再次看见该位置且目标不在。军队探索路径只使用实际看见过的通行性记录；游戏自身执行寻路与射击。

军队达到六个可攻击移动单位后出发，少于三个时重新集结。支持一条原生整队 attackMove 指令，记录回执，并从后续己方真实订单核对执行。已确认指令表示至少一名仍存活的成员保有该目标订单，不代表全队都已到达。单次队伍最多 48 个单位。

持续利用己方陆军工厂的实际菜单：资金和兵力条件合适时升 T2，优先重型坦克，资金不足时补普通坦克；可支付升级费用且至少六个战斗单位时优先升级；已有十个战斗单位而升级资金不足时暂停普通补兵、积攒升级费用。达到军队上限仍允许升级。每座工厂最多一个在途队列订单，总活动战斗单位目标上限 24。队列读数包含升级与造兵。损失会记录、补兵继续；工厂损失取消该厂在途验收。本版没有重建工厂、自动补建造者、空海军、持续经济扩张和修理系统。

低于 25% 血量且离基地较远的单位尝试撤回，暂时不加入进攻。撤回是普通移动命令，无法保证成功或自动修复。基地附近当前可见的攻击者优先作为防御目标。

战斗阶段决策间隔至少 1000 游戏毫秒，下令尝试也至少相隔 1000 游戏毫秒，约束覆盖造兵、升级、撤回和整队进攻。该限速只覆盖 Battle 客户端；旧开局客户端和其他命令入口不受此限速约束，因此尚未完成整个项目的统一公平性限制。

## 目标无进展处理 v0（NO_PROGRESS_TARGET_HANDLING）

同一可见目标在主力已经接近、且持续对它下达攻击意图的情况下，若它的 HP 在 20 游戏秒窗口内**一次都没有下降**，就把它暂时降权：本轮不再对它下令，下一次选目标时跳过它，改打其他目标；没有其他目标时回到探索（前沿计划）路径。冷却 30 游戏秒后该目标重新可被选中，这是一次**重试**而不是放弃——不存在"永久拉黑"。

只用合法可见信息，且对"没进展"的判定很严格：

- 只有**本帧新观测到**的目标（`visibleEnemies` 且 `lastSeenGameTimeMs` 等于本次观测时间）才可能推进停滞计时。目标只剩记忆时，计时**暂停并清零**——看不到它就无法判断它有没有在掉血，旧情报不能当作"没进展"的证据。
- 主力必须**已经接近**（任一参战单位距目标 ≤ 300 世界单位）才计时；距离不满足同样清零。
- HP **下降即视为进展**，窗口重新开始，并记录 `target_progress`（含接战到首次掉血的毫秒数）。
- 判定发生在下达命令**之前**：触发降权的那一帧不会再给该目标补一条命令。
- 观测报文里**缺字段时不会终止对局**：该层只是可选记账，读不到数字就只是暂停窗口（`numberOrNull` 守卫），有回归用例覆盖。

该逻辑只读 `/combat/observe` 的可见/记忆敌军与 `/state` 的己方单位，**不依赖任何 reachability 诊断结论**（B/C/D/E 组）。窗口、冷却与接近半径可用 `-Drwagent.noProgressWindowMs`、`-Drwagent.noProgressCooldownMs`、`-Drwagent.noProgressEngageRange` 覆盖，实际取值写进 `battle_config` 与 `summary`。

事件：`target_no_progress`（停滞毫秒、窗口、已下命令数、最低血量、当前血量、最近单位距离、降权次数）、`target_deprioritized`（冷却、重试时刻）、`target_retry`（冷却结束后的重新接战）、`target_switch`（从哪个目标换到哪个、累计切换次数）、`target_progress`（首次掉血耗时）。`summary` 汇总 `targetNoProgressTriggers` / `targetRetries` / `targetSwitches`，即验收所需的"停滞持续时间、触发降权次数、换目标后恢复有效进攻的时间、是否来回切目标"。

边界：v0 只处理"围着打不动还不换办法"。它不判断目标是否真的打不到（那需要 reachability 语义，属 Diagnostic Lab），不处理跨海/跨域不可达，也不会因为降权而阻止军队继续移动与探索。

## 经济 v0：矿上限从"终点"降级为"最低基线"

`mineTarget`（默认 3）不再表示"经济到此为止"，而是**最低恢复基线**：

- **基线以下**：照旧无条件扩张，开局与恢复行为完全未变；
- **基线以上**：继续向引擎询问合法点位，可以开第 4、5……座矿，但每次都要过两道判据：
  - `SITE_UNSAFE` —— 记忆威胁就在点位旁边；
  - `NO_SURPLUS` —— 付得起这座矿、但付完之后**买不起一个当前首选生产单位**，即这座矿会饿死它本该供养的生产线；
  - 另外，单位价格未知时（生产通道在同一 tick 的后半段才学到价格）记 `UNKNOWN_UNIT_COST` 并拒绝：
    用 `cost>0` 守卫会**静默取消**盈余判定，等于把门槛变成装饰。

立项依据（`助手交接\evidence\economy_baseline.txt`）：三局长局里矿数都在约 240 游戏秒冻结在 3，
而侦察视野中已有 8～12 个资源点、只建了 2 座矿，最后一局终局余额 16365。
达到上限后旧代码再也不调用 `/expansion/plan`，所以"还有没有矿位"根本没有数据。

**本版明确不做**：升矿、升厂、新厂触发条件、多建造者、收入率 EMA、intentional banking。
上述判据全部复用既有状态（引擎给的 `extractorCost`、记忆威胁、生产通道学到的单位价格），
**没有引入任何未核验常数**——`星星版铁锈机制库` 的核验表把经济回本全部标为"待实测/待复现"，
因此回本数字一律不进代码。

报告字段：`economy_expansion_planned` 增加 `beyondFloor` / `reason=ABOVE_MINE_FLOOR` / `preferredUnitCost`；
`economy_expansion_blocked` 增加 `beyondFloor`；`economy_expansion_finished` 的原因改为 `MINE_FLOOR_REACHED`；
`summary` 增加 `minesBeyondFloor` / `expansionRefusals`。

一个已修的坑：基线以上的探测原本与建厂通道共用 `lastExpansionAttempt` 做限速，
于是每次被拒的矿探测都会重置建厂时钟、把第二座厂饿死；现在基线以上用独立的 `lastAboveFloorProbeAt`。

**v0.1 修正（对话33 实机暴露）**：`/expansion/plan` 只在**建造者周围 ±600 世界单位**内找点位
（`EconomyBridge.openingPlan`，且要求该格当前可见）。实机两局的越基线探测 45 / 49 次全部返回
`NO_VISIBLE_LEGAL_SITE`，而诊断显示 `visibleResourceCandidates=1`——窗口内那唯一的资源格**正是我方矿所在的那一格**
（`nativeRejected=1`），真正未开的资源点在 1122～1902 单位外。而 v0 把"探测失败后走向记忆资源点"的
`prospectForResource` 只留给了基线以下，所以基线以上永远走不出去。

修正：基线以上在**第二座厂已建成**、且**能用引擎实测价格证明有盈余**（`矿价 700 + 首选单位 800 = 1500`）
时，允许继续探路；否则记 `BUILDER_NEEDED_FOR_FACTORY` / `NO_SURPLUS` 并拒绝。
"厂先于矿"这条不是偏好而是防冲突：`planProductionFacility` 会把离基地超过 `rearFactoryRadiusWorld` 的
建造者召回，两条通道会互相发移动指令。

## 经济 v1a：投资意图与预留（主动抢预算）

v0/v0.1 只会在**军队触顶、现金堆起来之后**才开额外矿——而实测显示那正是矿最来不及回本的时候
（巨岛 981s 那局：额外矿落在 600s / 840s / 900s，比赛 981 秒结束）。v1a 让经济能在**军队未触顶**时
主动拿走一笔预算。

- **军事紧迫度 `MILITARY_URGENCY`（四态）**：`EMERGENCY / CONTESTED / STABLE / OPEN_EXPANSION`，
  只用合法可观测信号判断——最近一次己方损失距今多久、最近记忆敌军离基地多远、军队是否达到常规作战规模。
  它**只回答"当前有没有明显迫切的军事资金需求"**，不是威胁模型，也不预测战局。
- **投资意图**：当基线经济已建立、紧迫度为 `STABLE`/`OPEN_EXPANSION`、且军队不低于常规作战规模时，
  开启一次 `investment_intent(target=NEW_MINE, cost, chosenAt, deadline)`，并把 cost 变成
  `investment_reserve`；**普通生产不得花掉这笔钱**（沿用既有 `reservedFor` 拒绝路径，
  记 `production_deferred` / `reason=RESERVED_FOR_INVESTMENT`）。
- **必然释放**（三条出口，缺一不可）：① 矿建成 → 释放并记 `investment_released(reason=COMPLETED)`；
  ② 出现军事压力（紧迫度变成 `EMERGENCY`/`CONTESTED`）→ 释放并撤销；
  ③ 超过 `investmentTimeoutGameMs`（默认 180 游戏秒）仍无法执行 → 释放并撤销。
  释放后要等 `investmentRetryCooldownGameMs`（默认 60 游戏秒）才会再开新意图，避免
  "意图 → 超时 → 意图" 的churn 把生产一直卡住。报告里 `investmentReserveAtEnd` 必须为 0，
  否则另记 `investment_reserve_stuck`（这是本版最要紧的护栏）。
- **价格来自引擎**：预留先用配置默认价（`-Drwagent.mineCost`，默认 700），
  一旦 `/expansion/plan` 报出真实 `extractorCost` 就立即校正并记 `investment_reserve_corrected`，
  所以不会因为默认价偏高而多扣生产。
- **验收是一条链，不是一个时间点**：
  `investment_intent` → `intentional_banking`（生产被预留挡下）→
  **`extractor_completed` 且 `belowHardCap=true`** → `investment_released(COMPLETED)` → 生产恢复。
  具体第几秒开出第几座矿交给局势决定。
- **支出账本**（同轮基础设施）：每笔被桥接接受的订单记
  `spend{gameTimeMs, category, cost, itemType, producerOrBuilderId}`，
  类别为 `UNIT_PRODUCTION / BUILDER_RECOVERY / NEW_MINE / NEW_FACTORY / FACTORY_UPGRADE`；
  `summary` 汇总 `spendTotal`、`spendByCategory` 与 `measuredIncomePerGameSecond`
  （= (Δ余额 + 已知支出) / 游戏秒）。这样机制库的收入率先验（T1 矿 ≈ 700/58 ≈ 12.1 资金/普通游戏秒）
  可以用自家实机数据校准，而且天然兼容 1x→5x。
- **本版不做**：`NEW_BUILDER` 投资候选、升矿、升厂、动态新厂、机会成本模型、完整威胁评估。

## Builder Utilization v0：让建造者少空转

只读归因（`助手交接\evidence\alite_ledger_builder.txt`）显示建造者位置不动的时间占全局长 **64–75%**，
把"没有投资意图"那段再切分后，占最大比例的是**军事压力**（设计内，209–256 游戏秒），
其次是**释放后冷却**（136–171 秒，**缺陷**），再次是军队未达常规规模（42–79 秒）。
本版只修前两项里属于自己的那部分：

- **成功不再继承失败退避**：`investmentRetryCooldownGameMs`（60 游戏秒）是为 `TIMEOUT` /
  `MILITARY_PRESSURE` 这类失败或撤销设计的防抖动；但 `releaseInvestment()` 过去对所有原因都写
  `lastInvestmentReleaseAt`，于是**一座矿成功建成后也要空等一分钟**。现在只有非 `COMPLETED`
  的释放才启动退避，成功可立即重新评估。
- **意图在手时以矿价本身为准**：`aboveFloorProspectGate()` 与建造闸门原先都要求
  `余额 ≥ 矿价 + 一辆首选兵的钱`。既然意图已经把矿价预留出来，再要求额外一辆兵的钱，
  等于把软性生产储备重新变成绝对门槛，与"主动抢预算"的语义冲突（实测占 32–108 闲置秒）。
  现在只看 `余额 ≥ 矿价`；**硬储备（建造者恢复）不受影响**，仍在生产通道里生效。
- **顺带补一处不一致**："厂先于矿"原先只写在**探路**闸门里，而探路闸门只在没有可见点位时才运行——
  于是**可见点位会绕过它**，把建造者从建厂通道手里抢走。现在建造闸门也检查该项。

单位价格不再参与这个判断，因此原先"价格未知就拒绝（`UNKNOWN_UNIT_COST`）"的分支随之消失。

### 实机验收（对话42，两局：5x + 1x）

| 指标 | 旧候选（v0 之前） | 新候选（v0） |
| --- | --- | --- |
| "没有任何闸门挡着却闲置"的游戏秒 | 26.3 / **60.3** | **0.0 / 2.1** |
| `COMPLETED` 释放 → 下一个投资意图 | **60.3 秒**（`c7bf22e5`，448.7s→509.0s） | **1.0 / 2.6 秒** |
| `NO_SURPLUS` 事件数 | 7 | 0 / 2 |
| builder 位置不动占比（计入施工后） | 62.8% / 77.1% | 59.9% / 59.3% |

剩余闲置全部可归因到设计内闸门：军事压力 356~358 秒、`army < activeArmyTarget` 108~116 秒、
意图在手期间的探路/等待 40~61 秒。施工本身只占 101~110 秒（**17.5 游戏秒/座**，机制库 16.7 秒在 5% 内命中）。

## 经济 v1b：动态陆厂（产能瓶颈驱动）

v1b 只新增**一个行为**：`landFactoryTarget` 不再是常量，当出现**长期产能瓶颈**时允许 +1（上限
`-Drwagent.landFactoryTargetMax`，默认 5），同一个机制服务第 3、第 4、第 5 座厂。

**触发条件（四者同时成立，全部是长窗口读数）**：

| 条件 | 读数 | 说明 |
| --- | --- | --- |
| 窗口已预热 | `time-startTime ≥ economyWindowGameMs` | 不允许用半段历史外推速率 |
| 长期满载 | 已完工陆厂"队列非空"的**时间加权**占比 ≥ `factorySaturationMinPct`（默认 80%） | 单帧不算证据 |
| 有可持续剩余 | `建模收入 > 已接受生产消费率` | 收入来得比现有工厂花得快 |
| 买得起且不动硬储备 | `余额 ≥ 厂价 + builderReserve + investmentReserve` | 新厂不得破坏恢复链 |

- **建模收入（可替换的实测常数）**：`收入 = baseIncome + incomePerMine × 已完工 T1 矿数`
  （默认 `26.9` / `12.07`，可用 `-Drwagent.baseIncome` / `-Drwagent.incomePerMine` 覆盖）。
  依据：四局实机（1x/5x 各两局）矿数恒定窗口 20 个，R² = 0.999，残差 σ = 0.59；
  逐档中位数 39.26 / 51.01 / 63.02 / 74.95 / 86.45 / 100.09（1~6 矿）；
  单矿回本 700 / 12.07 = **58.0 游戏秒**，与机制库先验（12.1、58 秒）一致。
  报告在 `report_provenance` 里写明来源（`economyModelSource`）。
- **生产消费率只来自已闭合的 `spend` 账本**（`UNIT_PRODUCTION` 在窗口内的和 / 窗口长度），
  **不用余额差分**：客户端按"下单批次"整笔记账、游戏按"逐件完工"逐笔扣款，
  所以 10~30 秒级的余额读数会被批处理节奏污染（实测："区间内没有任何支出"的观测对在 1~2 矿档
  给出**负收入**）。`economyWindowGameMs` 默认 **150000**（游戏毫秒），下限 60000。
- **明确不做**：不以"军队触顶 / 钱没地方花"为触发依据——触顶时队列会因策略而清空，
  那正是"没有产能需求"的样子；也不加入矿升级、厂升级、防御塔、临时 `credits > X` 规则。
- 事件：`factory_target_increased`（记下四项读数与两个硬储备）、
  `factory_target_increase_blocked`（**只在原因变化时**写一条，理由为
  `TARGET_AT_MAX` / `ECONOMY_WINDOW_WARMING` / `FACTORY_LOAD_UNKNOWN` / `FACTORY_NOT_SATURATED` /
  `NO_SUSTAINABLE_SURPLUS` / `INSUFFICIENT_CREDITS_FOR_FACTORY`）。
  汇总字段：`factoryTargetIncreases` / `factoryTargetIncreaseBlocks` / `landFactoryTargetAtEnd` /
  `factorySaturationPct` / `productionConsumptionPerGameSecond` / `sustainableSurplusPerGameSecond`。

### 顺手补的诊断字段（不改行为）

- `own_loss` 增加 `type` 与 `x`/`y`（此前只有 `unitId`，无法从日志判断掉的是矿还是兵）。
- `extractor_completed` / `factory_completed` 增加 `completedAtGameMs`：
  这两个事件的 `firstSeenGameMs` 是**工地第一次被看见**（= 开工），真正完工要 +17 游戏秒左右，
  把它当完工时间会让任何分析系统性提前一个建造周期。

## 倍速就绪（Speed Readiness）

策略语义**只依赖 game time**：经济/扩张冷却、战斗决策间隔、下令间隔、无进展窗口、采样间隔、
战斗时长预算都是游戏毫秒；倍速只应缩短现实等待，不应改变同一策略在游戏世界中的含义。

但主循环本身是**现实时间**驱动的（默认 `Thread.sleep(500)`），所以真实决策间隔（游戏毫秒）会随倍速变长：

| 倍速 | 每次轮询跨越的游戏时间 | 观测/决策间隔（游戏时间） |
| --- | --- | --- |
| 1x | 约 0.52 s | 约 1.0 s（实测 1738 次观测 / 900 游戏秒） |
| 5x | 约 2.6 s | 约 2.6 s |

因此报告现在**自证**以下实测值（不需要靠推断）：`gameSecondsPerWallSecond`、
`effectiveDecisionIntervalGameMs`、`noProgressAttentionGapMs`、`maxObservedGameTimeJump`、`observationCount`。

其中 `noProgressAttentionGapMs` 由实测决策间隔推导：`max(3000, 3 × 实测间隔)`。
原先它是写死的 3000 游戏毫秒，等于隐含假设了 1x 的循环速率——5x 下只剩 400 毫秒余量，
而**≥6x 时每一帧都会超过它**，于是无进展计时每帧重置、检测器永不触发，且不报错、不留痕，
只表现为"这局没有停滞"。回归用例
`test_the_attention_tolerance_scales_with_the_decision_interval` 用夹具把每次轮询推进 4 游戏秒来复现这个失效；
变异验证（把容忍度改回固定 3000）会使其失败。1x 下该推导值与原来的 3000 完全一致。

开局客户端（`EconomyClient`/`DevelopmentClient`）仍使用 **wall time** 截止时间（240s/120s）。
这是已知的口径不一致：5x 下同样的现实预算覆盖 5 倍游戏时间，即更宽松。已登记，本版未改动。

## 真实结算

`GET /state` 的 `match` 对象读取原生结果界面的 `engine.dq`（胜利）、`engine.dt`（失败）；源码注释与原版字节码核对见验证文档。另记录己方 `H/F/G` 标志，但不据其推断结算，也不遍历敌方单位数量来决定胜负。

游戏时间上限未到结算时记 PARTIAL；发生接口、会话、超时异常记 FAIL。VICTORY 和 DEFEAT 都是完整对局，仍需查看 matchOutcome 区分胜负。既不把建筑消失直接计为击杀，也不把敌军失去可见性算作消灭。

## 新接口

- `GET /combat/observe`：当前可见敌军、最后见到的敌军记录、原生结算。
- `GET /combat/production`：己方完成的陆军工厂菜单、成本、可支付状态、完整队列长度。
- `GET /scout/plan?role=army&unitId=...`：已观察地形上的军队探索计划。
- `POST /command/attack-move?unitIds=...&x=...&y=...&sessionId=...&requestId=...`：普通整队进攻移动。
- `POST /command/queue?unitId=...&actionId=...&sessionId=...&requestId=...`：己方工厂当前实际菜单中的原生生产或升级。

命令只接受本地已加载、非联机、非回放场景；严格检查己方单位、参数、会话和重试标识。不直接设置资金、坐标、单位列表或迷雾。

## 收集器修复

改为在 ZIP 写句柄关闭前 flush/fsync，随后校验并改名，避免同步只读句柄。对已存在的完整 `.zip.partial` 可执行 `python tools/collect_reports.py --recover 文件.zip.partial`：只有 CRC、完整校验清单、逐文件 SHA256 和报告数量全通过，且目标名称不存在时才完成改名。用户本次原始附件保留未改。

Windows 上的具体未改名原因尚无控制台异常证据；修复的是可疑的跨平台写入方式，不宣称已经在 Windows 重现原因。

报告同时保留最多 64 MiB 的内存提交快照，结束时写入从未暴露给读取者的新临时文件，flush/fsync 后原子改名；避免读取进行中日志时得到的旧文件快照被误当成完整报告。未封存报告不能通过验收。

# 原生无画面实验入口

`tools/run_headless.py` 为每次实验创建新工作目录和 Java 进程。`HeadlessRunner` 调用附件 PC 1.15 的原生初始化、地图加载和每帧更新；实际寻路、扣款、建造、生产和原版 AI 仍由游戏负责。它不使用测试夹具模拟单位完成，也不修改 game-lib.jar。

## 环境与运行

Python 3.9+，Java 8+ 且提供 HttpServer；当前验证环境为 OpenJDK 17。建议 Java 11 及以上。Windows 自动选择 `游戏目录/jvm64/bin/java.exe`，可用 `--java` 指定其他运行时。编译 Agent 源码使用 JDK 17，输出 Java 8 字节码。

从升级包单独运行时，明确指定游戏目录和 JAR：

```text
python tools/run_headless.py --game-dir /path/to/game --agent-jar ./rw-agent-bootstrap.jar --mode suite --speed 4 --out ./experiments
```

Windows 示例：

```text
python tools/run_headless.py --game-dir "D:\Games\RustedWarfare" --agent-jar rw-agent-bootstrap.jar --mode development --episodes 2
```

上例的两次实验**顺序运行**。同时运行恰好两个独立实例时，显式加 `--parallel-pair`：

```text
python tools/run_headless.py --game-dir "D:\Games\RustedWarfare" --agent-jar rw-agent-bootstrap.jar --mode smoke --episodes 2 --parallel-pair --out ./experiments
```

双实例模式启动两个独立的无画面游戏进程，各有自己的工作目录、API 端口、会话、报告目录与 `economy.lock`。它运行的是两次各自对原版 AI 的实验，**不是**两名 Agent 进入同一局，也不是完整 self-play。当前只支持恰好 2 个实例；`benchmark` 不支持该模式。其他参数和单实例入口一致。

| 参数 | 默认 | 含义 |
| --- | --- | --- |
| `--mode` | suite | smoke 为往返；opening 为开局；development 为经济与生产；frontier、autopilot、match 运行各自对应的客户端；suite 顺序执行往返、开局、持续生产；benchmark 仅推进引擎 |
| `--episodes` | 1 | 新进程实验次数，上限 1000；默认顺序运行；不代表比赛局数 |
| `--parallel-pair` | 关闭 | 必须与 `--episodes 2` 一起使用；同时运行恰好 2 个实例，并拒绝 benchmark |
| `--profiles` | 不使用 | 固定条件 A/B 的 JSON profile 文件；仅用于 `--mode match --episodes 2 --parallel-pair` |
| `--profile-order` | AB | `AB` 为 episode-001=A、episode-002=B；`BA` 交换位置；仅与 `--profiles` 同用 |
| `--speed` | 4 | 控制实验目标帧速倍率，范围大于 0 至 8；每帧仍使用原生固定步长 |
| `--timeout` | match 为 1200，其他为 180 | 每次实验现实时间上限，最大 3600 秒 |
| `--battle-seconds` | 900 | match 模式客户端的游戏秒预算，范围 120 至 1800；与现实时间 `--timeout` 分开记录 |
| `--tanks` | 8 | Development 阶段新增坦克数，1 至 30；不包含前置阶段 |
| `--mines` | 3 | Development 阶段新增矿上限，0 至 10；不会读取雾中矿点 |
| `--map` | Small_Island 2p | `maps/skirmish/` 下的原始 TMX 相对路径，其他地图可能因地形和可见资源条件拒绝任务 |
| `--difficulty` | 0 | 原版 AI 难度整数，范围 -2 至 3；未测量任何对战胜率 |
| `--frames` | 6000 | benchmark 固定帧数，无节流执行；该模式不运行 Agent 策略 |

## 固定条件 A/B 对比 v0

从工程根目录运行以下命令。示例 profile 为 `tools/profiles/cap32_vs40.json`，其中 A=`cap32`、B=`cap40`；两次调用使用**同一份 profile、候选 JAR、地图、难度、倍速、现实超时和游戏秒预算**。每次 `--out` 下会生成一个唯一 `run-*` 目录，将实际生成的两个目录路径代入聚合命令。

```text
python tools/run_headless.py --game-dir . --agent-jar "..\P1F-冒烟环境\rw-agent-bootstrap.jar" --mode match --episodes 2 --parallel-pair --profiles tools/profiles/cap32_vs40.json --profile-order AB --map "maps/skirmish/[p2]Small_Island (2p).tmx" --difficulty 0 --speed 4 --timeout 1200 --battle-seconds 900 --out ./headless-runs/ab
python tools/run_headless.py --game-dir . --agent-jar "..\P1F-冒烟环境\rw-agent-bootstrap.jar" --mode match --episodes 2 --parallel-pair --profiles tools/profiles/cap32_vs40.json --profile-order BA --map "maps/skirmish/[p2]Small_Island (2p).tmx" --difficulty 0 --speed 4 --timeout 1200 --battle-seconds 900 --out ./headless-runs/ab
python tools/aggregate_ab.py RUN1 RUN2 --out summary.json
```

`AB` 批的 episode-001/002 分别是 A/B；`BA` 批相反。聚合器读取已有 run 目录，不启动游戏，也不改原始报告。可重复更多批次并继续交叉位置；上面两批仅是最小功能验收，不构成胜率或策略优劣结论。

profile 文件只接受 `schemaVersion=1` 和恰好 A、B 两个 profile。每个 profile 只有 `name` 与 `jvmProperties`；v0 唯一允许的属性是整数 `rwagent.mobileUnitHardCap`，范围 **24..80**。不能透传其他 `-D`、JVM 参数或新增策略逻辑；两个 profile 的名称和内容必须不同。示例内容：

```json
{
  "schemaVersion": 1,
  "profiles": {
    "A": {"name": "cap32", "jvmProperties": {"rwagent.mobileUnitHardCap": 32}},
    "B": {"name": "cap40", "jvmProperties": {"rwagent.mobileUnitHardCap": 40}}
  }
}
```

运行器在启动前校验 profile，并把规范化 JSON、profile SHA256 digest、A/B 位置和实际传入客户端的 JVM `-D` 参数写入 `episode.json` / `batch.json`。原始 battle 报告中的 `battle_config` 还须回显所选 `mobileUnitHardCap` 与游戏秒预算；不一致时该局失败。每局保留原始 report、SHA256、session、`runtime.json` 的实际 seed，以及并行身份证据。`seedReproducibilityVerified=false`：实际 seed 只是记录，**没有证明能设置或重现同一随机状态**。用重复局数、交叉位置和指标离散度观察随机波动。

聚合时只合并候选 contentDigest、game-lib 身份、地图、AI 难度、请求倍速、现实超时、游戏秒预算和迷雾条件一致的局；条件不一致应拒绝合并或明确分组。汇总按 profile 给出样本数、完成率与胜负、游戏时长、产兵、损失、支出、矿/厂、关键经济事件等**现有报告字段**的均值、中位数及离散度，并附每局 run/report 索引。完成率的分子是有效报告中已出现 `VICTORY` 或 `DEFEAT` 的局数，分母是该 profile 的全部 episode（包括失败、未完成和 `ONGOING`）；`PASS` 只代表任务验证通过，不等于胜利或比赛完成。仅两批时每组 **n=2**，统计量对随机性和地图/启动位置很敏感，不据此自动评分、调参或裁决策略。

## 可续跑的 A/B campaign v0

`run_ab_campaign.py` 把现有固定条件 A/B 双实例批次顺序串联。`--pairs N` 必须至少为 2，槽位自动按 AB、BA、AB、BA 交替；任一时刻只启动一个双实例批次。示例从工程根目录运行：

```text
python tools/run_ab_campaign.py --game-dir . --agent-jar "..\P1F-冒烟环境\rw-agent-bootstrap.jar" --profiles tools/profiles/cap32_vs40.json --pairs 2 --timeout 300 --battle-seconds 120 --out ./headless-runs/campaign-001
```

相同命令再次运行会读取 `campaign.json`，核对候选 JAR、game-lib、profile 原始文件、运行器与聚合器源码、地图、难度、倍速、两个时限以及共享资源指纹，然后只填未完成的槽位。每个槽位保留原始 `run-*` 目录和 `launch-*.log`；已验收批次不会重跑。进程被强杀留下不完整批次时，续跑先按 `live-claims.json` 核查旧 runner 是否仍在运行，并调用原有 `--reap` 回收登记子进程；仍有未清除的登记进程就停止，不启动下一批。完整但身份不符、原始报告损坏或出现异常 runner 错误时也停在 `BLOCKED`，保留证据供审查；`--max-attempts` 只限制不完整批次的重新启动次数。

原生胜负尚未出现时，battle 原始报告为完整且可信的 `PARTIAL / ONGOING`，runner 通常退出 1；campaign 仍将该批记为完成。最终 `aggregate.json` 必须复核 AB/BA 交叉位置、每臂 `attempted`、`validBattleReports`、`partial`、`invalidOrMissingReports`、`nativeCompleted` 与指标 `n`。`n=0` 只表示没有原生完成样本，不会生成自动胜者或策略结论。`campaign.json` 保存最终聚合路径和 SHA256。不要搬动 run 目录；聚合会核查其中的绝对路径及原始字节 SHA。

campaign 先将 game-lib 与 `libs/assets/res` 复制一次到自身的 `resource-cache`，校验原件和缓存的 SHA；后续 episode 只从这份专用缓存暂存。每批启动前按两局全部复制资源的最坏情况预留空间，并检查 `--max-campaign-gib`（默认 10）及 `--min-free-gib`（默认 1）；容量不足则停在 `BLOCKED`，不会删除战报。扩容后可用相同命令调整这两个额度再续跑，`campaign.json` 会记录额度变更。Windows 暂存先尝试符号链接，失败后目录尝试 junction，再失败才复制；`staging.json` 逐项记录实际方法。原始资源与缓存会在批次前后按文件 SHA 复核；引擎设置和所有报告仍写在独立 episode 目录。没有自动清理原始 run 的操作。单独使用 `run_headless.py` 时，Windows junction 需要显式 `--junction-dirs`，应只指向可丢弃的资源缓存。

## 文件与结果

每批生成唯一 `run-*` 目录，每个 `episode-*` 保存 `engine.log`、分阶段控制台日志、原始 `rw-agent-reports`、初末 `/state`、`runtime.json` 与 `episode.json`。批次总表为 `batch.json`。

双实例模式另写入 `parallel-proof.json`。其中 `PASS` 表示在同一次等待点观察到两个引擎均存活、帧数推进，且端口、会话、工作目录、报告目录和锁路径两两不同；就绪时、重叠采样时及每个控制阶段开始前还核对 `/health` 的 JAR 身份及工作目录归属。`PASS` 只证明这次重叠和身份检查，**整批成功仍要看** `batch.json` 中两个 episode 的状态、`parallelProof` 和命令退出码。每个 `episode-*` 的 `engine-process.json` 记录本次进程 PID；`episode.json` 记录端口、会话、报告路径及各原始报告的 SHA256 和会话证据。若 A 提前失败，B 可以独立完成并保留报告，但整批仍失败。

每个控制阶段既检查退出码，也用离线分析器核验唯一新报告的终结 summary 和事件计数。缺 summary、坏行、计数不一致、控制失败、引擎异常退出、会话重置、帧不推进均不能算通过。每个实验有超时，结束会通过本次目录的 `stop.request` 要求原生循环退出，必要时只终止此次创建的进程。`batch.json` 保留每次失败，不会用后一次成功覆盖前一次失败。

双实例运行时按 Ctrl+C，编排器会请求本批两个引擎停止，并尝试终止和回收仍在运行的本批客户端与引擎。编排器还在 run 目录持续写入 `live-claims.json`，登记本批 orchestrator、engine、client 的 PID、端口、工作目录、episode、session 和状态。若 Python 编排器被直接强杀，子进程可能残留；在 Windows 上可运行：

```text
python tools/run_headless.py --reap RUN_DIR
```

`--reap` 仅查看该 run 目录的登记簿，复核进程命令行与引擎端口属主后才清理本批残留；不清理 orchestrator 或无身份把握的进程，不做跨批次清理。结果写入 `reap-report.json`；重复执行应为幂等。进程/端口查询不可用时它会跳过而不猜测，需查看报告。下一批使用新工作目录，旧批报告与失败证据保留。

运行目录不复制用户的 preferences.ini、存档、回放和外部 mods。资源使用共享只读用途的链接或独立复制；引擎缓存、报告及设置写入实验目录。game-lib.jar 哈希必须匹配附件基线。保持 `assets`、`res`、`libs` 齐全。

## 已知边界

### World Model v0.1：战争记忆与 Recon v0

`/combat/observe` 返回 `enemyIntel`、`enemyIntelVisible`、`enemyIntelLostContact`、`enemyIntelCleared` 和 `enemyIntelEvicted`。每条接触记录只由通过敌对关系及两项原生视野检查的可见敌人建立，保存最后一次合法观测的类型、位置、血量、建筑/武装属性和时间。`VISIBLE` 表示当前可见；`LOST_CONTACT` 表示当前未见，不能推断目标仍在原位或已经死亡；`CLEARED` 表示旧址所在格及周围 8 格中所有落在地图内的格重新进入合法视野，且原接触未再出现。**CLEARED 是对旧址的核查，不是击毁或目标死亡证明。** `lastKnownSiteVisible` 使用同一 3×3 邻域可见条件，`clearedGameTimeMs` 仅在达到 `CLEARED` 条件后记录时间。会话切换会清空情报。

历史情报通常最多保留 256 条；超过时淘汰最久未见的非可见记录，`enemyIntelEvicted` 是本会话累计淘汰操作次数。若一帧可见敌人本身超过 256 条，会暂时保留全部可见者。长局高接触量下响应体积会增加。`agent_tick_alive` 心跳记录上述四个状态/淘汰计数。端点目前只支持本地对局；网络对局仍由桥接层拒绝，不能将其解释为已完成多人视角隔离。

比赛客户端的 Recon 默认开启（`rwagent.reconEnabled=true`）；给客户端 JVM 传 `-Drwagent.reconEnabled=false` 可关闭。它只为先前合法看见、随后失联且旧址 3×3 邻域仍未完全可见的高价值建筑创建核查任务：指挥中心、陆/空/海工厂及 T2 矿。一次最多一个任务；至少有 7 名可用武装单位时，才选一名健康单位执行单兵移动，给主力留下至少 6 名。紧急防御或可用兵力跌破 7 时，已分配的侦察兵会解除任务；距离基地较远时，随后尝试下达召回移动命令。受损、丢失或无进展的侦察兵也会退出任务，失败尝试间隔至少 15 游戏秒；同一任务最多容许两名侦察兵失败，已启动的核查另有 120 游戏秒上限。

停靠点按**规划的直线路段**与合法记忆中的威胁射程圆求精确交点，在射程外增加 80 世界单位缓冲并提前停下；上次观测能攻击、但不在威胁记忆中的原建筑也按旧址周围 220 单位避让。这只是客户端直线几何检查，原生寻路后的实际路线和安全性尚未验证。目标重新合法可见记为 `recon_reacquired`，旧址核查记为 `recon_site_cleared`，无法推进则记为 `recon_blocked`；移动命令回执不能单独证明任务完成。

原始 battle JSONL 可按 `recon_task_created`、`recon_assigned`、`recon_order_queued`、`recon_order_observed`、`recon_progress`、`recon_deferred`、`recon_unassigned`、`recon_attempt_blocked`、`recon_recall_queued`、`recon_reacquired`、`recon_site_cleared`、`recon_blocked` 查证决策与状态变化；`battle_config` 回显开关与 `reconMinReadyArmy=7`，终结 summary 记录任务、命令和结局计数。`reconOrdersObserved` 只计己方状态中观察到移动命令，`reconResolvedAfterObservedMove` 只计观察过移动命令后又重新发现目标或核查旧址的任务；两者是时间关联，**不能证明该移动导致了重新发现或旧址清除**。以下从工程根目录运行**新构建的候选 JAR**，由运行器创建独立进程和工作目录；以生成的原始报告、`episode.json` 与命令退出码判定结果，目前不预设原生对局的 Recon 成效：

```text
python tools/run_headless.py --game-dir . --agent-jar developer/dist/rw-agent-bootstrap.jar --mode match --episodes 1 --map "maps/skirmish/[p2]Small_Island (2p).tmx" --speed 4 --timeout 1200 --battle-seconds 900 --out ./headless-runs/recon-v01
```

`run_headless.py` 没有 Recon 专用参数。若要对照关闭状态，可在该次实验的 PowerShell 会话中先设置 `$env:JAVA_TOOL_OPTIONS='-Drwagent.reconEnabled=false'`，执行同一命令但使用另一个 `--out`，随后移除该环境变量；它会传给该命令创建的 Java 子进程。`--profiles` 的白名单只接受 `rwagent.mobileUnitHardCap`，不能用于切换 Recon。

### Recon v0.2：地图记忆与残血前沿侦察

上述 Recon v0.1 的 `RECHECK_INTEL` 旧址核查仍保留。v0.2 在同一个 Recon Controller 中加入 `FRONTIER_SWEEP`，一次仍只占有一个任务/侦察单位；有可执行的前沿任务和旧址核查时，两类任务轮换。`/scout/observe` 只根据己方当时的合法地图视野，给每格记录本会话的最后可见游戏时间，并汇总五类：当前可见（`mapMemoryCurrentVisibleTiles`）、失明不足 45 游戏秒的近期雾（`mapMemoryRecentFogTiles`）、45 至不足 120 游戏秒的陈旧雾（`mapMemoryStaleFogTiles`）、至少 120 游戏秒的深雾（`mapMemoryDeepFogTiles`）、本会话从未见过（`mapMemoryNeverSeenTiles`）。会话更换时清空记忆。这些分类不包含雾中敌人的现时位置、生命或生死。

`/scout/plan?role=recon&unitId=...` 只接受己方已完成的武装移动单位。它在合法已见的通行性记忆上寻找通往高信息增益区域的前沿格，优先从未见过的区域边界，其次深雾和陈旧雾；近期雾本身不产生前沿信息分。计划报告 `frontierMemoryClass`、三类 `potential...Tiles`、`routeTiles` 与目标格。**从未见过区域的边界是已知路线的终点，不表示未见格的地形或通行性已知。** 规划时避开合法记忆中的武装威胁射程；客户端再核验四邻相接的路线，把同方向路线拆成至多 6 格一段，并对每段按已知射程加 140 世界单位缓冲检查。原生寻路可能偏离规划线段，因此这些检查不是实际行进安全的保证。

前沿任务只从 `tank`、`c_tank` 中选当前生命不高于最大生命 45% 的己方单位；重坦等高战斗价值单位不转岗。新转岗前须有至少 7 名主力，转岗后保留至少 6 名；已转岗侦察者在主力恰为 6 名时仍可继续接任务。正常完成任务后该单位仍不参与主力 `attack-move`。新任务创建后必须等下一次己方状态观察，按该次实际位置复核已知路线和威胁，再优先安排首令；待命候选不接新的整军攻击移动命令。紧急防御或主力不足时释放任务，需要时向基地发召回移动命令，并解除消耗型转岗标记，让候选重新进入主力战术；实际归队须以后续己方状态确认。失败的前沿目标通常进入 120 游戏秒冷却，防止立即反复撞向同一地点；紧急抢占、首次下单被拒等情况不据此判定目标不可达。

`-Drwagent.reconFrontierEnabled=false` 关闭 v0.2 前沿任务，保留 v0.1 的旧址核查；`-Drwagent.reconEnabled=false` 关闭两个 Recon 通道。`battle_config` 回显这两个开关及 `reconExpendableHpMaxFraction=0.45`、`reconFrontierMinMainForce=6`。原始 battle JSONL 可沿 `recon_task_created` 的 `kind` 查看任务类型，结合 `recon_frontier_plan`、`recon_expendable_transfer`、`recon_order_queued`、`recon_order_observed`、`recon_progress`、`recon_waypoint_reached`、`recon_frontier_memory_update`、`recon_frontier_refreshed` / `recon_frontier_advanced` / `recon_frontier_blocked` / `recon_frontier_preempted`、`recon_expendable_released_for_defense` 与 `recon_recall_queued` 核对过程；summary 记录 `frontierTasksCreated`、`frontierTasksRefreshed`、`frontierTasksAdvanced`、`frontierTasksBlocked`、`frontierTasksPreempted`、`expendableTransfers`、`expendableScoutsAtEnd`。`recon_expendable_released_for_defense` 表示解除侦察占有并安排回防，不能当作已经回到主力的证据。`recon_frontier_memory_update` 要求侦察者移动命令和坐标进展已被己方状态观察到，目标区域的全队合法视野刷新时间严格晚于该首次进展；这只是时间关联，**不能据此认定新视野由该侦察兵单独造成**。是否在自然原生对局中触发、移动并更新地图记忆，仍以该局原始报告和 `episode.json` 为准。

原生回放自动录制仍未交付。一次隔离的 headless 试验生成了 `.replay`，但原生播放从第 6452 帧开始稳定出现 checksum 不一致；实验代码已撤回。文件存在不代表回放可用，须先解决录制与播放的确定性差异并通过独立播放验证。

这是新增实验入口，不是官方专用服务器。无图像资源的原生模式与 Windows 图形模式尚未逐帧比较，未证明跨进程严格确定性。记录原生实际 seed，但未提供可证明完全复现该随机状态的接口。原生地图加载和固定步长选择属于本项目适配逻辑，详情保留在源码。

原版初始化会为多余队伍输出“无初始单位”等诊断；空音频后端也可能输出音乐子系统警告，需结合进程退出码、状态推进和完整控制报告判断实验是否有效。无画面吞吐不是显卡性能、训练速度承诺或真人竞技水平。

原版迷雾标志必须启用，否则启动失败。适配器不清零视野数组，不增加资金，不生成测试单位，不读取雾中敌人向策略提供信息。`/state` 仍只返回己方单位；战斗客户端通过独立的合法可见敌情端点观察目标，矿点规划先验证当前视野，再读地形。具体行为与边界见 `BATTLE_CN.md`。

`match` 模式可以在一个独立的原生无画面进程中调用现有比赛客户端，尝试从开局推进到原生胜负结果；是否完成及结果以该次原始报告和 `episode.json` 为准。双实例模式只扩展实验编排与隔离，不改变战斗或经济策略，也不提供同局双 Agent 对战、确定性复现或训练接口。

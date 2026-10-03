# 给 DeepSeek / GPTsol：Recon v0.2 加速推进交接（Codex，2026-09-28）

> **状态：改后完整回归 GREEN，正式候选与隔离 P1F 安装件身份已核对；最终新候选在隔离原生局出现了可审计的 FRONTIER_SWEEP 行为链。** 原生局到预算上限仍未结算胜负，尚无桌面游戏或因果性能验证。地图刷新只证明全队合法视野在侦察命令和己方位置进展之后更新，不能归因于单个侦察单位。不沿用 Recon v0.1 或旧 v0.2 的候选身份。

施工依据是 `HANDOFF_FOR_CODEX.md` 的保护边界与 GPT 新合同 `助手交接/Codex_Recon_v0.2_加速推进合同_2026-09-28.md`。改前完整基线 `work/recon-v02-baseline-2026-09-28.log` 为 Java **17**、Python **13**、失败 **0**。GPT 已授权在保留旧址核查的同时推进 Map Memory、残血消耗型侦察与 FRONTIER_SWEEP；SUICIDE_PROBE、速度/追击 Threat Field、补侦察生产均非必交付项。

## 本轮实际选择与施工深度（回答 1）

1. `ScoutBridge` 把己方合法地图视野记为五类：当前可见、近期雾（失明 <45 游戏秒）、陈旧雾（45 至 <120 游戏秒）、深雾（≥120 游戏秒）、本会话从未见过；会话切换清空记忆。`/scout/observe` 返回五类数量，`/scout/plan?role=recon&unitId=...` 用从未见过、深雾、陈旧雾的潜在信息量规划前沿，近期雾不计入收益。`NEVER_SEEN_EDGE` 表示安全已知路线抵达未知区边缘，**不表示未知格可通行或已读隐藏地形**。
2. `BattleClient` 在同一个 `ReconTask` / 任务占有 / 命令与紧急抢占框架里保留 `RECHECK_INTEL`，新增 `FRONTIER_SWEEP`；两类可执行任务轮换。前沿路线必须是四邻相接的已见通行性路线，客户端拆为同方向最多 6 格的单兵移动段，再按合法记忆的敌方射程加 140 世界单位检查。桥接规划与客户端己方观察有先后时，只容纳由合法 bridge anchor 佐证的一格观察时差；创建任务后等待下一次己方观察，再检查路线并优先尝试首个侦察命令。失败前沿目标通常冷却 120 游戏秒，避免反复撞向同一点。规划安全性不等于原生实际寻路安全性。
3. 新转岗只选仍可行动、当前生命 ≤最大生命 45% 的 `tank` / `c_tank`，排除重坦等高战斗价值单位；转岗前需至少 7 名主力，转岗后留下至少 6 名。尚未分配单位的 pending 前沿任务在新观察中重查兵力：新转岗候选需保有 7 名主力，已在侦察池的候选需保有 6 名；紧急防守直接取消未下令任务。转岗者从主力 `attack-move` 排除，任务正常结束后仍留在侦察池；已转岗者遇紧急防御/主力不足时释放、按距离安排召回；紧急释放事件仅表示侦察控制权解除和回防安排，实际归队仍待己方状态观测。`rwagent.reconFrontierEnabled=false` 回到仅旧址核查的行为，`rwagent.reconEnabled=false` 关闭两个 Recon 通道。

在此深度收手的理由：以上已形成从合法地图记忆到可执行单兵任务的完整一层，最终隔离原生局已出现转岗、己方移动观察与随后的团队合法视野更新。下一轮宜先处理更强 Recon 的风险与活性收尾；死亡前探测、追击速度场或经济补员会改变风险预算、单位生产和命令竞争，仍需单独交接。旧 `RECHECK_INTEL` 不应被这轮新逻辑替换。

## 游戏里预期能看到什么（回答 2）

当有符合条件的残血轻型坦克、可用主力至少 7 名且非紧急防御时，Agent 可让一辆残血坦克离开主力攻击队列，沿已知可通行路线分段前往深雾、陈旧区域或从未见过区域的边界；其余主力继续自身攻击移动。若任务区域重新进入全队合法视野，地图记忆分类随之更新，报告记录前沿任务的推进或刷新。若威胁、兵力或紧急状态改变，任务可以阻塞、释放或召回。**最终隔离原生局已观察到转岗、单兵命令与位置进展后的团队合法视野更新；没有证据证明重见由该侦察者单独造成，或改善了胜负。**

## 已验证、待验证与原生证据（回答 3）

| 层级 | 当前证据 | 判定 |
| --- | --- | --- |
| 改前基线 | `work/recon-v02-baseline-2026-09-28.log`：Java 17 + Python 13，失败 0 | 已验证 |
| 原生对象确定性迷雾夹具 | `work/recon-v021-final-regression.log` 中 `SCOUT_NATIVE_TEST_OK checks=160`。覆盖五类计数、45/120 秒边界、重新可见后的刷新、`role=recon` 前沿、隐藏地形/通行成本毒化不改变计划，以及合法记忆威胁避让。夹具中额外坦克使用原生图像 stub 初始化 | 已验证的**夹具**，不是自然比赛 |
| 客户端确定性夹具 | 最终回归中 `test_recon_frontier_client.py` **16/16** 通过；旧址核查定点 `work/recon-v02-recheck-targeted.log` 为 7/7。覆盖转岗/主力排除、无视野刷新不认功、任务轮换、重坦排除、紧急召回、409 回执、路线失效与开关等情形 | 已验证的**模拟合法观察**；不能代替原生实际移动 |
| 改后完整回归 | `work/recon-v021-final-regression.log`：Java harness **17** + Python suites **14**，failed steps **0**；`ScoutHarness` 160 checks、前沿客户端 16/16。最终修改由该次完整回归覆盖 | **GREEN，已验证** |
| 正式候选 / 安装一致性 | 最终 JDK 17 候选 `developer/dist/rw-agent-bootstrap.jar` 与隔离 `P1F-冒烟环境/rw-agent-bootstrap.jar` 的 whole SHA256 均为 `0c53bdfa1b545088e15fa6ab4269216b5c3fa586b00738bf00f5770ac5adc71a`；`助手交接/evidence/candidate_sha_lineage.json` 的 contentDigest 为 `b292e168c3cabe71ba5bada3159d14d027d324cc7794d99fe80fba6ac396aa20`，`aVsInstalled=[]`、`aVsDist=[]`。替换前的 v0.2 候选备份 `work/recon-v021-before-final.jar` 的 whole SHA256 为 `e594770f7cf31494d5245c87a6dc5353a3b6cc4a83ae269ceed5ba4459ecac8f`；更早 v0.1 P1F 备份 `work/recon-v02-before-2026-09-28/p1f-agent.jar` 的 whole SHA256 为 `9394bdc52b449400d3ec332bf23e959c1a0ce9a8c28d8bbddaf255f276432a52` | 候选、dist、隔离安装件与两份回退件身份**已验证**；实际原生行为另列 |
| 最终新候选隔离原生局 | `work/recon-v021-native/run-20260927T184202-5237df/episode-001`：Big Island (2p)、难度 1、4x、1200 游戏秒预算，墙钟约 336.7 秒；记录的 native seed 为 `826773822`，`seedReproducibilityVerified=false`。原始 `battle-1790534545432-3833fd1d.jsonl` SHA256 `d68a3083734e45a36a9942939d882e7fd4a102274d1cd25a8459a129a68cd38b`；staged JAR SHA 与最终候选 `0c53bdfa…` 一致。`助手交接/evidence/recon_v02_native_final.json` 的 `auditStatus=PASS`、`issues=[]`，报告结构与身份核对通过。前沿任务创建 36、残血转岗 1、单兵命令 queued 30 / observed 24 / 位置进展 24、团队合法区域重见 23 / refreshed 23，完整转岗至刷新时间链 23（`NEVER_SEEN_EDGE` 14、`DEEP_FOG` 9）。193 次主力 `attack-move` 中，75 次有仍在侦察池的转岗单位可供排除检查，未发现违规；前沿阻塞 12 次。episode 的 `FAIL` 是预算到期分类，battle 为 `PARTIAL / ONGOING`，没有原生胜负 | **原生行为链与排除检查已观察、审计通过；胜负和性能未验证** |

原始战报可从任务 **#9** 核对首条完整时间链：`c_tank` **#345** 当时 HP **2.485/210**；696192 游戏毫秒创建前沿任务，697616 毫秒转岗并 queued，698992 毫秒在己方观察里匹配到 `move` 和位置进展，目标区域的全队合法视野刷新时间为 700432 毫秒。相应原始行是 4093、4110–4118、4124–4125。**刷新晚于单兵进展只证明时间先后，不能证明 #345 独自造成重见。** 36 个前沿任务中 23 个刷新、12 个阻塞、1 个在预算终点未结；阻塞原因分别为 `ROUTE_ANCHOR_DRIFT_BEFORE_ASSIGNMENT` 9 次、`KNOWN_THREAT_CORRIDOR` 1 次、`WAYPOINT_REACHED_WITHOUT_LEGAL_CLEARANCE` 2 次。任务 #24 的短航点 `move` 曾出现在原始己方观察中，但决策采样错过，属于下一轮需处理的采样活性风险。

whole SHA 为 `e594770f…` 的旧 v0.2 候选已有三局 JDK 13 隔离原生样本，原始报告与逐步触发分析见 `助手交接/evidence/recon_v02_trigger_forensics.txt`。Small Island、难度 1 的 600 游戏秒局预算到期，结果为 `PARTIAL / ONGOING`；唯一可用残血候选遇已知威胁封住安全出口，规划返回 `no_frontier`。同地图、难度 0 的局在约 264 游戏秒取得原生 `VICTORY`，但残血候选短时间内阵亡，唯一前沿规划仍被已知威胁封路。Big Island、难度 1 的 900 游戏秒局也是 `PARTIAL / ONGOING`；它多次具备残血轻坦、足额主力及非紧急状态，在 **750.208 游戏秒**曾取得 `planned` 前沿路线，但桥接规划与客户端旧观察相差一格，旧候选将其安全拒绝为 `INVALID_KNOWN_ROUTE`。这三局的 `frontierTasksCreated` 与 `expendableTransfers` 都为零，均**不能**作为自然 FRONTIER_SWEEP 或残血转岗的验收。最终新候选的原生前沿链见上；pending 兵力门槛与紧急释放在本局未形成完整触发证据，仍以确定性夹具为验证层级。

原始 battle JSONL 的 `recon_task_created.kind` 区分两类任务；前沿可查 `recon_frontier_plan`、`recon_expendable_transfer`、`recon_assigned`、`recon_order_queued`、`recon_order_observed`、`recon_progress`、`recon_waypoint_reached`、`recon_frontier_memory_update` 和 `recon_frontier_refreshed` / `recon_frontier_advanced` / `recon_frontier_blocked`。终结 summary 的 `frontierTasksCreated`、`frontierTasksRefreshed`、`frontierTasksAdvanced`、`frontierTasksBlocked`、`expendableTransfers`、`expendableScoutsAtEnd` 只能与原始事件和安装身份一起读。前沿紧急抢占另记 recon_frontier_preempted 并计入 frontierTasksPreempted；recon_expendable_released_for_defense 不证明实际归队。`recon_frontier_memory_update` 记录全队合法视野的目标区域刷新时间严格晚于侦察者首次已观察移动进展；**不能由时间先后归因给该侦察者**。

## 副作用与保护边界（回答 4）

- **主力**：新转岗要求至少留 6 名武装主力，已转岗 ID 不进入主力 `attack-move`；最终原生局在 75 次涉及已转岗单位的主力命令检查中无违规。pending 首令前重查新候选 7 名、既有侦察者 6 名的门槛，紧急防守可取消未分配任务或释放已转岗单位并安排召回；阵亡判定先于已转岗单位的紧急抢占。旧候选 Big Island 一格观察时差造成的合法规划拒绝，现由 bridge anchor 加下一次己方观察确认处理；首令优先于普通生产机会，但仍经过当前路线与威胁检查。确定性夹具覆盖这些分支，代码也阻断偏离规划段超出容差的原生位移；长期战斗影响、原生实际路径是否完全沿规划、伤亡与胜负改善仍未验证。旧核查有独立开关回退路径。
- **经济**：本轮没有改生产、采矿、投资或补侦察兵需求。转岗单位仍算在既有武装单位生产目标与硬上限里，因此可能暂占一个生产配额；此影响尚未量化，不能宣称经济零副作用。
- **合法信息**：地图分类只用己方合法视野时间；已见通行性记忆才可供路径规划。敌方威胁只来自合法目击和历史记忆。隐藏敌人现时位置、HP、生死及命令不作为规划输入；也没有读取未见地形。`/scout/visible`、原生寻路及桥接的网络/回放保护仍遵循原约束；网络对局未获验收。
- **身份和现场**：最终新候选在隔离 P1F、dist 与原生 episode staged JAR 的 whole SHA 已核对；旧 v0.2 和更早 v0.1 两份回退件均已保存并核对 SHA。未启动桌面游戏，未改根冻结 JAR、`game-lib.jar`、用户设置、存档或回放。JDK 13 大岛原生报告与审计已封存，结局为预算到期的 `PARTIAL / ONGOING`。

## 下一步优先级（回答 5）

**下一步优先做更强 Recon 的风险与活性收尾。** 最终原生局已有 23 条完整的转岗至团队合法视野刷新的时间链；12 次阻塞中 9 次为首令前路线锚点漂移、1 次为已知威胁走廊、2 次为航点到达但无合法清空，另有任务 #24 的短命令被决策采样错过。应先围绕这些实际边界做有限改动与回归。是否真正由单兵侦察导致重见、能否改善长期伤亡或原生胜负，仍须独立验证。经济 vNext 可在这层行为稳定后评估；多人/self-play 仍牵涉更大的可见性和隔离边界，当前不应抢此轮主线。

## 后续仍需验证

- 针对已复核的 9 次路线锚点漂移、1 次已知威胁走廊、2 次航点无合法清空与任务 #24 采样漏过短命令，评估风险/活性收尾；任何新候选仍须单独做完整回归、身份校验与有界原生审计。
- 原生胜负、桌面游戏行为与因果性能效果；本局 `PARTIAL / ONGOING` 不提供胜负或策略增益结论。
- DeepSeek/GPTsol 的独立审查反馈及本轮后续裁决。

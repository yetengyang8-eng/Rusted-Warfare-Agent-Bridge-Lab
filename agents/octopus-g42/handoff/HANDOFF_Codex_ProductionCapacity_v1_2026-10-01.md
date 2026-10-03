# Production Capacity v1 工程交接 — 2026-10-01

工程轮次始于2026-10-01，闭环于2026-10-02（Asia/Shanghai）；候选ID/目录保留本轮起始日期。

本轮已完成 Spain 原始证据流式分析、源码核对、最小容量修复、专项测试、隔离原生对局和候选整理。当前候选为 `RW-CANDIDATE-2026-10-01-PRODUCTION-CAPACITY-v1`，**桌面 Spain 验收待进行**。下面严格区分源码/fixture 已证与自然/桌面未证。

## 身份、起点与保护范围

| 项目 | 本轮记录 |
| --- | --- |
| 唯一生产仓库 | `G:\deepseek 工作台\GitHub发布\Rusted-Warfare-Agent` |
| 起始 HEAD | `51af35dd3a01f82a5f490a486646dbf06b7ec3cb`，工作树干净 |
| 实际分支 | `codex/production-capacity-v1-20261001`，开始时已位于要求的 HEAD，保留分支，无 reset |
| 实现/测试/分析工具提交 | `1264b379752ca4fc3a433f0ab19d2d3ae7582cb6` |
| 候选/完整证据/状态交付提交 | `a5caf79602947a3ca0acf48d696fa42a28781e73` |
| 候选 JAR | [rw-agent-bootstrap.jar](../deliveries/production-capacity-2026-10-01/rw-agent-bootstrap.jar)，317,988 bytes |
| 候选 SHA256 | `a392f692e010c8429a7a93072e60323c83a9deb1ed471bbcbddbd3bbbaf6adb6` |
| contentDigest | `cdb11872abddb6df1917023b92f851c7b6d39823d324418d3993cf12828c1cba` |
| 冻结原版 game-lib.jar | `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9` |
| 历史 FEEDBACK-v1 JAR | `0116e7c67f6fbd778d6956a9770205b31a458365c196d4063caa625f643c0cc7`，保留原状 |

构建、完整回归与 headless 工作目录均在 `%TEMP%`。没有启动/关闭/操作用户桌面游戏，没有替换安装 JAR、原版引擎/assets、冻结交付件、设置、存档或回放。基线核对显示最新 FEEDBACK/本轮源码与9月30日冻结输入不同，这是保留的演进，不授权回滚。冻结基线 manifest 未改。

## 真实根因：自锁假设部分成立

[Spain 派生证据](../evidence/production-capacity-2026-10-01/spain-capacity.json)来自330,145,906-byte raw，36,346行，0 malformed JSON；SHA256 `e8aba1885566d30487cdf696c23f2fde1fced6f5de5bd5991ad142b17e3632aa`。复现工具为 [analyze_production_capacity.py](../tools/analyze_production_capacity.py)，逐行解析，没有把 raw 全文读入模型上下文，也没有委派分析。

独立核对的 headline 数字全部吻合：PARTIAL/ONGOING，4801.610 Battle秒；余额1,321,176；spend479,600，其中普通生产282,900、NEW_FACTORY700；扩厂目标增加0，结束工厂目标2/max5；饱和度47.35%/阈值80%；普通生产消费48.0/s、盈余304.8/s；最后战略收入352.79/s、armyTarget96、hard cap128。

源码存在明确的两段限制：`productionLimit()` 在 committed armed 达到 `min(strategy armyTarget, hard cap)` 后停止普通生产；旧 `StrategyDirector.capacity()` 通常将目标约束在96；工厂扩张另要求长窗口80%忙碌。于是**仅战略目标满但 hard cap 有空间时，普通生产政策限制与 producer 空闲可能组成自锁**。

但 Spain 不是“长期都被96限制”。可观测 armed+factory queue 首次达96在 gameTimeMs4485884（94+2）；样本保持算法得到目标满47.720游戏秒，hard cap满0秒；结束可见 committed81+1=82。大多数采样仍有军力 slot。旧 idle/refusal 仅记录状态变化，不能重建每次未生产机会；未导出的 specialist/paid commitment 也不在此估计内。**47.720s不是精确因果阻塞时间**；此前临时算法约48.43s的差异已在证据中保留解释。

因此本轮修复确有源码依据的军力/producer 容量区分，但不会声称已经解释或消除大多数现金积累。slot空余、工厂闲置仍为 UNKNOWN；scheduler/refill delay只是后续假设。

## 本轮最小实现

| 文件 | 最终改动 |
| --- | --- |
| `agent/src/io/rwagent/client/BattleClient.java` | 长窗口经济/合法需求/安全证据；容量分类；有界军力扩容；重用工厂目标/BuildJob；接受/观察/ready/after-window关联；执行前 native价格与预留重查；普通 paid-before-queue-visible安全占位 |
| `agent/src/io/rwagent/client/StrategyDirector.java` | 当前可见 ordinary product 的兼容与接近证据；军力目标有界增加；session military floor避免再次被旧96公式压回 |
| `agent/src/io/rwagent/client/ProductionCapacity.java` | 最小纯分类模型 |
| `agent/src/io/rwagent/client/ProductionRoute.java` | 原生 ordinary factory菜单适配：producer/product/action/cost/tech/queue/affordable |
| `agent/tests/test_production_capacity.py` | 15个实际 BattleClient HTTP协议/行为 fixture |
| `agent/tests/ProductionCapacityHarness.java` | 27项分类、96→104 floor、延迟reserve/hard-cap/demand/recovery、paid gap与artillery隔离检查 |
| `agent/test-win.ps1`、`agent/test.sh` | 注册新增专项及 Java harness |
| `tools/analyze_production_capacity.py` | Spain可复现流式提取，摘要/统计/采样容量时长及限制 |

分类包括 MONEY_LIMITED、RESERVE_PROTECTED、NO_USEFUL_DEMAND、ARMY_CAPACITY_LIMIT、HARD_SAFETY_CAP、PRODUCER_THROUGHPUT_LIMIT、PRODUCER_TECH_LIMIT、ROUTE_UNAVAILABLE、CAPACITY_EXPANSION_COMMITTED、UNKNOWN。OPERATIONAL_CAPACITY_LIMIT没有可靠观测基础，保持 UNKNOWN。

默认150s窗口，需要至少80%观察覆盖，正盈余/合法 useful demand/安全观察各至少80%；超过6s的观察缺口不填补。已知需求要求当前可见 ordinary actor/product 的 native COMPATIBLE + APPROACH_PATH_KNOWN；失联、缺 actor type、未完整批次、UNKNOWN不授权扩容。

军力目标满且低于hard cap、长期证据成立、native可负担、资金覆盖新slot并留足全部reserve、没有危险恢复时，每次最多8 slot，完整窗口冷却。提高的是有界战略容量，不改变hard cap。厂真正成为瓶颈时，保持原80%阈值，将现有 landFactoryTarget加一并建立 commitment；Builder执行仍走原BuildJob/native command通道。

factoryCommitmentId关联 `factory_target_increased` → `production_facility_committed` → planned → order_accepted → observed → ready → `production_capacity_after_expansion`。后窗口同时记录收入、消费、spend、利用率、commitment；不能把该窗口字段本身当因果提升证据。已接受未观察到的扩厂订单保留，不重复下单或扣费；付费后不因需求暂失去而取消已接受订单。

执行前再查fresh demand、hard cap、恢复状态、builder原生可用/可负担工厂动作及价格、builder/investment/capability reserve。没有绕开 ownership、CommandArbiter、Target Guard、fog/native legality。Engineer、amphibiousJet、mine T2/T3、LocalArmy/LocalCrisis、heavyArtillery账本继续使用原合同。没有全面迁移普通产品ready matching，没有mammoth正式策略。

## 测试与失败保留

完整实际结果、各suite计数、构建输入hash与候选身份以 [validation-results.json](../evidence/production-capacity-2026-10-01/validation-results.json) 为准。

最终完整Windows矩阵为 **56/56步骤，29 Java harness、24 Python suite共435 tests，`failed steps: 0`**。Java ReportCommitHarness的POSIX open-handle displacement模拟在Windows跳过，其余commit检查通过；没有把平台跳过当已验证行为。

- 起始基线有效54/54。首次JDK17矩阵的19项Selector/loopback环境失败与1项遗漏docs的staging失败、JDK13重跑遗漏assets的失败均保留；assets完整的原生Strategy重查51项通过。没有修改产品源码来绕过Selector环境问题。
- 中间实现曾因强制普通产品ready等待导致LocalArmy真实回归。已删除该等待，保留原updatePending队列完成合同，LocalArmy12项重查通过；旧失败日志保留。
- 最终首次矩阵55/56，新Java fixture漏了arbiter观察初始化。只修测试，27项重查通过。随后再次运行完整Windows矩阵，以其明确 `failed steps: 0` 汇总为最终门槛。
- 15个HTTP专项覆盖战略目标满→最多8slot→普通生产重启，producer长期饱和→目标2→3→一次真实build-factory请求→观察ready→后窗口，hard cap、无需求、UNKNOWN、blocked/missing route、native不可负担、idle free slots、单帧busy/收入spike、investment reserve、builder recovery、tech与unknown tech。
- 27项Java fixture补充96→104持续floor、延迟builder/investment/capability reserve、plan后hard cap变满/需求UNKNOWN/恢复、普通paid空队列占位、artillery和upgrade隔离。
- 原生NativeMorph102与StrategyNative51等使用真实引擎对象的fixture，`fixtureOnly/noSimulationTicks`边界保留，不能当自然比赛。

测试运行环境使用原版只读捆绑Java13（`jvm64/bin/java.exe`），`JAVA_HOME`和PATH同步，UTF-8固定。完整重建与交付JAR的所有107 ZIP entry内容完全一致；归档时间元数据造成whole-file SHA差异，manifest同时登记两份，交付保留实际自然对局使用的317,988-byte JAR。

## 自然 headless 证据

只运行一次自然match；没有与完整回归同时运行，没有parallel pair。资源用 `prepare_headless_engine.py`验证并展开到可丢弃引擎目录。地图Big Island2p，difficulty0，请求5x，seed127052449（未验证可复现），fog/line-of-sight fog启用；Battle预算900秒，原生VICTORY于594.272 Battle秒提前结束，整段146.3壁钟秒。

Bootstrap/economy/development/battle报告全部校验PASS/`issues=[]`；engine退出0、cleanup后不存活。原始reports在 [match-evidence.zip](../evidence/production-capacity-2026-10-01/natural-headless/match-evidence.zip)，[summary.json](../evidence/production-capacity-2026-10-01/natural-headless/summary.json)与runtime/batch可远程读取。

这局36次容量assessment，军力增加0、工厂目标增加0；普通初始第二厂完成1次。故自然对局证明可运行和诊断链路，**未证明自然容量扩张**。没有比较胜率、现金、军事效果或因果吞吐；之前Spain/Big Island的PARTIAL仍是PARTIAL，不改写为本候选胜利。

## 下一次桌面 Spain 应观察的9件事

1. 记录候选SHA、地图、难度、速度、Battle预算和最终原生结果；PARTIAL/ONGOING继续如实保存。
2. `committedArmed >= strategyArmyTarget < hardSafetyCap`时出现ARMY_CAPACITY_LIMIT；不被归为无需求或producer瓶颈。
3. useful demand与reserve/recovery窗口成立后，`military_capacity_increased`每次1～8slot；新floor在后续strategy_capacity中不回落至旧96。
4. 扩军力后实际ordinary queue订单重新出现；单独目标数字增加不算闭环完成。
5. free military slots且factory长期至少80%busy才出现PRODUCER_THROUGHPUT_LIMIT；单帧busy、钱多、free-slot闲置不得触发扩厂。
6. 同一factoryCommitmentId完整串联target增加、commitment、planned、一次接受订单、observed、ready；既未观察又重复扣费/下单需作为失败保存。
7. ready后完整窗口的income/consumption/spend/utilization与交付情况能重建；不要直接以钱少或多一座厂推断改善。
8. hard cap满、builder丢失/恢复、investment或capability reserve、native动作不明/不可负担、失联/UNKNOWN需求时应HOLD，不越过合同。
9. 对slot空余而factory闲置的UNKNOWN段保留时间窗口、路由/native menu、accepted command/ownership/调度机会；用这些证据定位refill或scheduler问题，现有lastAcceptedAgeMs不宜单独作饥饿结论。

## 安装、回退与剩余工作

本轮没有桌面部署。可用候选、1200/2400/4800客户端入口、明确隔离安装/回退步骤见 [delivery README](../deliveries/production-capacity-2026-10-01/README.md)。仅在新验收环境未运行游戏时备份其JAR再替换；保留报告/设置/存档/回放。旧原版和冻结FEEDBACK目录不可覆盖。

剩余：桌面Spain/native长窗口扩容、实际生产吞吐/军力交付与operational capacity；预期生产时长、完整route fallback/ready/handoff迁移均未实现。未知idle原因需下一轮新证据，无本轮已知工程blocker要求停工；不能为了消除UNKNOWN擅自重写scheduler。

源码提交已本地保存；交付/证据/状态的提交可通过本分支 `git log --oneline` 与本文件路径历史定位，最终对话给出最新SHA和remote状态。禁止force push、reset main或改写历史。

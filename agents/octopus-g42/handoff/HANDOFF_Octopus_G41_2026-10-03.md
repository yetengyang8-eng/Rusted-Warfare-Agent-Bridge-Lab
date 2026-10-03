# Octopus G4.1 Early Operations 交接 — 2026-10-03

**G4.1 实现与 Windows/native 验证完成；自然局闭环仍需证据，未部署、未 push、未进入 G5。**

分支 `codex/octopus-g3-execution-20261002`；用户已验证起点 `5562ec7`。
阶段提交：`6ed2db4` 同一 General FORMING/ACTIVE 与逐成员 screen；`1d91e43` 原版静态地图与缓存路由；`39a2670` EarlyExpansionNeed、主循环接线、production 后可选派发及失效 Need 取消。最后一项也是完整回归的测试源码提交；后续交付文档不改变这 167 项测试输入。
候选 `RW-CANDIDATE-2026-10-03-OCTOPUS-G41-v1 / VALIDATED_NOT_DEPLOYED`。统一 Windows/native JAR SHA256 `9353ef5e31f3cc0e58e70f559828ce452923656817b4e16802e567b38f997df2`，位置 `G:\deepseek 工作台\_validation\octopus-g41-20261003\final-source\agent\dist\rw-agent-bootstrap.jar`。候选 JAR、商业引擎及资源均未重新打包进仓库。

## 实际闭环

无有效 General + 当前健康 ready 普通 FREE 兵 → 创建真实空 roster `general:N / FORMING`；基地仅为合法己方 formation anchor，不改 army()/armed()。首兵与新兵均经过 FREE 的同帧屏障，再 PENDING_JOIN → JOINING → actual accepted receipt → 严格后帧合法己方位置 witness → ATTACHED。desiredStrength 是 `max(activeArmyTarget, FORM_MINIMUM)`，默认24，不锁成1。

1～5 当前健康 ATTACHED 不进行普通 frontier 或主动出征，仍保留 Critical Retreat、合法 LocalResponse 及轻量扩张 screen。第六名健康且当前真实 owner/在场的 ATTACHED 使同 ID/owner 转 ACTIVE；无 proxy swap，不增加 owner generation。之后直接走成熟 G4 General 行为。失去所有普通兵后会 invalidation，后续 FREE 可重新创建新的 FORMING ID。无效 General reservation、ABA、pending+response、clear→JOINING/FREE、detach→FREE 等原契约保留。

`StaticMapKnowledge` 来自当前加载原版 Ground/Items/PathingOverride 的静态 tile definitions；每 session/map/SHA/dimensions identity 扫描一次，移动域+目标的 reverse route fields 用 LRU8 缓存。当前 fog 不限制固定矿点/地形先验；runtime不读取全局动态 path grid、单位阻塞或隐藏敌情。repository knowledge 仅独立 oracle。approach 只验证合法己方 mobile 与静态地形路线，safe/occupied/buildable/dynamicReachability 均 UNKNOWN。

原经济 lane 首先使用成熟当前可见 `/expansion/plan`；拒绝时才用固定矿点形成局部 `EarlyExpansionNeed`。builder15 和 FORMING 成员单 actor screen20 可向静态 approach/rally 前进，最终造矿仍需当前原生 site plan、真实价格和 native guard。Need：REQUESTED → APPROACHING（仅 receipt）→ LEGAL_SITE_OBSERVED（当前 planner）→ CONSTRUCTION_OBSERVED（当前 own unfinished）→ COMPLETE（当前 own ready）；合法取消带原因，不用 receipt 宣称已到/已可建/已安全/已完成。

真正调度：primary force collect/priority/dispatch 90/70/50/30 → 成熟 Strategy/经济/Recon/production immediate → optional force20/15 collect/priority/dispatch。screen收集后同帧经济取消或更换 Need 时，final dispatch 核对收集时实际 Need 对象仍当前且 live，否则在 admission/token/HTTP/callback 前取消；不会重建 generation。数字 priority 只在这两段真实 force 批中裁决，不是全 runtime 全局调度器。可选支援不抢 production 的最后机会，required JOINING/响应仍遵守既有 G4 优先级。

契约：[Early Operations](../docs/OCTOPUS_G41_EARLY_OPERATIONS.md)、[Static Map Knowledge](../docs/OCTOPUS_G41_STATIC_MAP_KNOWLEDGE.md)、[G4 force lifecycle](../docs/OCTOPUS_G4_FORCE_LIFECYCLE.md)。关键源码：GeneralRegistry、ForceController、BattleClient、EarlyExpansionNeed、StaticMapKnowledge、TerrainSemantics。Strategy、原生 EconomyBridge/ScoutBridge guard、资金/slot/burst 权威保留。

## 验证

| 层 | 结果 | 证明范围 |
| --- | --- | --- |
| 根代理统一完整 Windows | **71/71；39 Java harness；29 Python suites /473 tests；skip0；failed steps0；exit0** | 本轮仅一次 full；167 测试输入、5历史fixtures逐项哈希与仓库一致 |
| FORMING focused | GeneralFormation198；ForceFormation219；旧Registry287/Force69 PASS | current健康六人成军、保留reservation、独立screen/UNKNOWN与旧控制契约 |
| 真实 BC 主循环 HTTP | **7 tests /8 timelines PASS** | 零军队至六人成军、真实 POST 顺序、raid/pending/detachclear、rebirth、staticUNKNOWN、native拒绝、同帧取消旧screen；positions/production由显式合成fixture提供 |
| 同一最终 JAR 八类 native | **657 checks PASS** | G3/G4旧298 + Static119/101 + Early139；真实原版对象/HTTP/命令/价格/付款/产品与BC adapter，显式fixture推进 |
| 静态地形交叉核验 | **534,000 成本比较零差异；独立repository oracle25 checks PASS** | Big Island固定14矿匹配知识坐标；fog开关固定矿/地形一致而enemy输出变化；native动态grid污染不影响static；override replacement/session缓存与route复用 |
| 保护 | **895/895 未变** | 7保护文件与888只读引擎assets/libs；未操作设置/存档/回放/桌面游戏 |

同一最终 JAR 原生 Early139 使用零普通军队、builder/基地/现成tier2工厂/充足资金。6次heavyTank真实queued、原版command.k付款/队列、原版factory.a(float)生成真实ready单位；native join命令后通过显式更晚位置 witness加入，六人后真实6 actor attackMove。隐藏矿点的plan/build HTTP409，后来显式visibility/rally后旧nativeplan合法，实际buildorder及原版guardedhelper建真实unfinished extractor、付款700，后帧进入 CONSTRUCTION_OBSERVED。原版受控builder更新仍progress0，**没有把未完成矿写成COMPLETE**。

证据：[汇总](../evidence/octopus-g41-2026-10-03/final-validation-summary.json)、[full步骤](../evidence/octopus-g41-2026-10-03/regression-results.txt)、[native验收](../evidence/octopus-g41-2026-10-03/native-acceptance.md)、[native哈希](../evidence/octopus-g41-2026-10-03/native-manifest.json)、[manifest](../deliveries/octopus-g41-2026-10-03/candidate-manifest.json)。完整raw `G:\deepseek 工作台\_validation\octopus-g41-20261003`，不要求远程 GPT/DeepSeek 能访问本机；共享摘要/契约/哈希在仓库，助手交接有同内容副本。

## 修正与保留的失败证据

- 首版 screen 使用多 actor `unitIds` 调 `/command/move`，与原生 singular unitId协议不符；focused集成期间改为逐成员独立命令，没有放宽bridge协议。
- 原生 fixture 没执行正常原版统计刷新，cached team cap仍5，第五次生产即便receipt accepted也被原生执行拒绝。fixture调用原版 `n.X()` 更新已配置cap1000，日志保留oldcap/configuredcap/newcap；没有提高产品吞吐或修改guard。旧失败 `native/g41-focused-attempt2` 保留。
- 施工追加的早期断言把native build枚举误认，原始失败/probe保留；修正测试依据真正 build order，而非改原生。
- 局部review发现同帧取消Need后仍派发延迟screen；39a2670保存真实对象并派发前检查，新增实际主循环frame8回归确认收集→经济取消→g4_force_cancelled→无原intent admission/POST，production继续。
- official native首遍Spain因隔离目录缺供给map而失败；保留pre-spain-assets，补同hash地图后独立复验通过。原地图未改。
- full wrapper错用RW_G41_EVIDENCE_DIR，七项测试断言与结果正常但未输出该suite raw。根代理另以正确RW_G41_RUNTIME_EVIDENCE_DIR对同最终Jar跑这七项focused一次，保存24原始工件；不是第二次full。[raw身份](../evidence/octopus-g41-2026-10-03/runtime-evidence-manifest.json)、[短Trace摘录](../evidence/octopus-g41-2026-10-03/runtime-trace-excerpts.json)可核验。

## NEEDS_EVIDENCE 与下一断点

所有 native 是 **E2_NATIVE_FIXTURE_WITH_REAL_HTTP / NO_NATURAL_MATCH**。真实HTTP主循环是另一合成证据层。显式clock/fog/位置/producer/build helper更新不证明自然移动、自然生产/施工耗时，也不证明从标准低资金/无工厂入口完全自主闭环、矿井最终ready、扩张保护成功、自然5×或胜率。自然扩张动态阻塞、安全、战术获益仍需证据；不能把静态连通当native动态到达。

G3 unsettled credit的transportunknown/丢effect可保守长持；两栖requested/desired/physical/weapon/terrain permission保持独立，Spain自然潜水进入及伤害仍NEEDS_EVIDENCE。严重危机/health是否中断JOINING未增加。无General CombatLedger/Performance/OVERMATCHED/ThreatTask/HOT-COLD、共享集结、动态编制/数量上限、Scout/Reclaim。SearchArea继续inactive。

建议下一断点是 **G4.2 隔离自然 Early Operations 验收**：标准零军队、原版低资金/tier1或无工厂、fog开，正常simulation ticks跑生产→加入→screen→当前合法建设→ready→ACTIVE，记录各阶段game-time和卡点，不用fixture位置/ready变化替代；然后再决定需要哪些局部运营修正。此建议不自动授权G5、部署或桌面操作。

开关 `earlyOperations=false`只关闭新增absence-drivenbirth/staticsupport，共享FORMING phase语义仍在，不宣称完整G4回退；`g4Forces=false`保留硬化G3，`g3Execution=false`保留legacy gate。未deploy/push，未启停用户桌面游戏，未改原引擎/冻结交付件。下一任务从最新仓库HEAD和本交接起步，9/30冻结baseline仅核血统。子智能体只Sol6.1/high，禁Astra。

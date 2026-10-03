# Octopus G4 工程交接 — 2026-10-02

**G3 native 硬化绿灯后，G4 已实现并验证；未部署、未 push、未进入 G5。**

分支 `codex/octopus-g3-execution-20261002`，用户验收起点 `b7c5f51`。
阶段提交：`b3d0a3b` G3 native 硬化；`893c473` G4 实现；`54b9de1` 关闭 G4 的日志兼容修正（最终测试源码）。后续交付文档提交不改变已测试输入。
候选 `RW-CANDIDATE-2026-10-02-OCTOPUS-G4-v1 / VALIDATED_NOT_DEPLOYED`。
同一完整回归/native JAR SHA256 `b3f6a344ddd2236f68828b3745ab2f150063c850b8483a0c9c946d9befa6b4cd`。
JAR 位于 `G:\deepseek 工作台\_validation\octopus-g4-20261002\final-source\agent\dist\rw-agent-bootstrap.jar`，未将商业引擎/资源或候选二进制重新打包入仓库。

## 实际完成

- G3 跨 observation 保留 accepted/transport-unknown 的 intent-specific unsettled credits。真实延迟执行下，1600 资金只允许两笔 800；gameTime 改变、receipt、空队列、actor 丢失或超时均不退款。可信后帧 queue/tier/施工 effect 才结算，当前 batch/token/slot 不退。施工 type/site + builder 当前 build order 是有界关联；缺失/歧义继续持有。
- 两栖 desired AIR/WATER 与实际潜水阈值分离。合法独立 `/combat/unit-modes` 证据、相同 actor/session/freshness 才可推进，receipt 不冒充实际模式。`submergedWeaponAvailable` 证明本原版 jet 的 `eq < -1` 阈值，不能证明当前地形/目标可攻击。
- Strategy construction/investment/provider Intent 保存原始合法 production/investment menu、construction-plan 或 expansion-plan（extractorCost）的真实 `costSourceObservationId` 与完整 `costSourceRequestPath`。G3 execution 开启时 UNKNOWN 拒绝支出；legacy false 保留旧gate。不填 `/state`，原生最终价格/action guard 权威不变。
- `GeneralRegistry` 建立真正 `GeneralId`、独立 `general:N` owner、ATTACHED 名单、PENDING/JOINING reservation、当前位置/最低目标。出生单位 FREE，成熟编队仅作首次迁移 seed；没有 cohort 改名共用 rule-main。
- `ForceController` + BattleClient 接线完成 FREE/LocalResponse/补兵 JOINING/后帧 ATTACHED 闭环。正式 pending 可与普通本土响应共存；当前合法敌 visibility 消失即 clear；不搜 last-known、不等 clear timer、不推断死亡。JOINING 保存实际 queued/accepted receipt.frame，要求不早于源state.frame、不缺省冒用源帧；fresh同session/player己方位置frame严格晚于receipt且接近该帧General centroid（180范围）才ATTACHED。
- General 临时分兵是真正 detach；结束后 FREE，不能在仍执行这类 response 时重新 pending。目标 General 无效时 reservation 清除，JOINING 释放。owner transfer/release 均 generation++；owner A→B→A 的旧 Intent 仍拒绝。
- G4 force intents **真实 collect → priority sort → dispatch**，冻结 owner generation；critical90 / LocalResponse70 / JOINING50 / General30。真实G4 force批内最后一个可用token被高优先级响应获得。builder recovery、Strategy/经济/Recon/production 仍 caller traversal→immediate dispatch，数字 priority 没有跨整个 runtime 的全局裁决效力。

源码入口：`agent/src/io/rwagent/client/GeneralRegistry.java`、`ForceController.java`、`BattleClient.java`；G3 `ExecutionScheduler.java`、`NativeCreditWitness.java`、`CapabilityUnitMode.java`、`StrategyDirector.java`。完整状态转换、freshness/arrival/owner 契约见 [G4 contract](../docs/OCTOPUS_G4_FORCE_LIFECYCLE.md)。

## 验证与失败记录

| 层 | 最终结果 | 范围 |
| --- | --- | --- |
| 完整 Windows | **68/68，37 Java harness，28 Python suites / 466 tests，skip0，failed steps0，exit0** | 主智能体统一执行；159 源码/测试输入和5历史fixtures核对一致 |
| G4 focused | GeneralRegistry287、ForceController69、HTTP mainloop3/3 | 正交状态/所有指定转移、generation、reservation、真实主循环接线；trace开关不改变命令选择 |
| G3 focused | Scheduler130、NativeCredit109、Capability44、quote72、Engineer61、Strategy169；G3 HTTP11/11 | credits/slots/burst/paid-ghost和成熟链保留 |
| 同一最终 JAR native | **298 checks / 五 JVM exit0** | G460、多厂168、两栖30、caller竞争31、Spain有界9 |
| 保护 | **895/895 未变** | 7保护文件与888只读assets/libs；设置/存档/回放/桌面游戏未操作 |

第一次 full 为66/68，G1四条/G2九条比较均因关闭 G4 仍输出两项新 `battle_config` 字段而失败；没有放宽断言或过滤新差异。`54b9de1` 只使两字段在 G4 开启时输出，保留旧事件形状，然后主智能体重跑完整矩阵、同一新 JAR 重验全部 native。旧源码/JAR/raw logs 保留于隔离 `final-source-before-log-compatibility`、`final-full-before-log-compatibility`、`native/pre-log-compatibility`；此前真实延迟付款超支、错误模式和 receipt-frame 缺陷证据也保留。完整次数2，其中必要修正重跑1；不隐瞒首遍失败。

共享证据：[summary](../evidence/octopus-g4-2026-10-02/final-validation-summary.json)、[step results](../evidence/octopus-g4-2026-10-02/regression-results.txt)、[native acceptance](../evidence/octopus-g4-2026-10-02/native-acceptance.md)、[native hashes](../evidence/octopus-g4-2026-10-02/native-manifest.json)、[candidate manifest](../deliveries/octopus-g4-2026-10-02/candidate-manifest.json)。完整 raw 本地根 `G:\deepseek 工作台\_validation\octopus-g4-20261002`；远程 GPT/DeepSeek 不应假定能访问本机路径。

## Spain 结论及证据边界

查到的是附带 custom map `[10p] 10p 西班牙混战_by_MP97.tmx`，不是原版内置地图；tile70 (26,230) world(530,4610) 为 water-bridge：LAND/WATER/HOVER 等可通行、overWater=false。直接 Dive 原生 HTTP409；在普通水域 Dive 后，以稳定 submerged fixture 放至此格，物理潜水阈值仍真。
原生 target selection 拒绝普通非水坦克，却接受潜水 jet，因此“任何目标均不能攻击”没有成立。desired movement、physical threshold、weapon available、当前地形/目标 attack permission 保持独立。自然潜水移动进入与实际射击/伤害 **NEEDS_EVIDENCE**；未根据用户记忆改策略。原/隔离地图 SHA256 `342db6d8a8b8320b6a271b9e3c8a4c29c96a203e206c4be690c44bbef5882746`。

所有 native 是 **E2_NATIVE_FIXTURE_WITH_REAL_HTTP / NO_NATURAL_MATCH**：原版对象、HTTP、实际调度和 command.k；显式 fixture 时钟/位置/高度/队列推进，无自然 simulation ticks。5×要求覆盖等效 game-time 跳跃，未测桌面5×。G4 native 进入真实 BC observe/collect/flush adapter，未冒充完整自然自主主循环；HTTP mainloop3/3是另一证据层。不得写自然局获益、胜率或桌面验收已完成。

## 当前边界 / 下一断点

1. General 只由入口已 ready 的普通兵创建。入口零普通军队不会自动 birth；之后生产保持 FREE，这是明确 G4 范围，已有主循环测试。存活 General 仅填初始规模 vacancy，surplus FREE；新将军 birth/动态编制未实现。
2. FREE 无固定最低预备数；有普通本土 response、正式分配能力，但没有在缺少有用合法 home route 时编造闲游动作。General frontier 的 pathKnown 证明 anchor，不证明每成员路线；最终 native guard 保留。
3. JOINING 不被普通骚扰重征召，既有严重伤残即时撤退也排除 JOINING；health role 仍更新、自治继续。严重危机/伤残中断与 reservation 取消策略后置，未发明阈值。相关决策应先明确，再实现，不隐式塞入 G4。
4. 极端 transport未知、丢失effect或歧义施工可保守长持 credit，**NEEDS_EVIDENCE**，不可自动清零。无需 G5 即可闭环已有 force lifecycle。
5. G5 CombatLedger/Performance/OVERMATCHED/ThreatTask/HOT-COLD、共享集结、动态编制/数量上限、Scout/Reclaim 均未实现；SearchArea仍 contract-only。下一建议：在新授权下明确 crisis/health interruption 与新 General birth 的接口，随后进入 G5；不自动施工或部署。

回退 `-Drwagent.g4Forces=false` 保留本轮硬化 G3；`-Drwagent.g3Execution=false` 保留历史旧gate路径。本轮无安装/回滚桌面包，不 push、不启停桌面游戏。冻结9/30基线仅核血统；下次从当前分支最新提交和本交接继续。子智能体仅 `gpt-6.1-sol / high` 或用户指定同级Sol，禁Astra。

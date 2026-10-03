# Global Strategy：从理论可打到购买完成任务所需的能力

本轮依据 `ASTRA_BREAKTHROUGH_MISSION_GLOBAL_STRATEGY_2026-09-30.md`，基线为 main `4d80b4e`。源码候选 commit `2f310ec46a4225eb1ef77efe68d1823904e52717`；本候选没有安装到用户桌面。较早的“只评审”要求已经由后续正式施工任务取代。

## 判断与实际突破

桌面 E4 的 `seaFactory #230 @ (290,70)` 不是 Compatibility Guard 判错：它是 SURFACE，重坦的武器确实兼容。缺失的是 LAND 编队能否抵达一个射程内的位置。把它重新命名为“不兼容”或加一个海军工厂黑名单，都会掩盖真正问题。

现在实现三层证据：武器域兼容 → 已知地形上的接近位置 → 原生执行/可见伤害/合法原址清空。后两层之间仍有距离：`APPROACH_PATH_KNOWN` 不承诺路径实际完成、能开火或能击杀。无法接近的目标保留为能力需求，投资层据此购买工程师或由工程师生产 AIR 响应者；执行单位持有独占任务租约，主力和 Recon 不会把它拉走。

这条链已经在一次自然原生长局中闭合：中间候选发现 `seaFactory #2610 @ (490,2890)`，LAND 被地形证据挡住，工程师建造的 `amphibiousJet #2905` 接手，其目标随后持续掉血，最终在重新可见时变成 `CLEARED`。不是通过 fixture 摆出这场战斗。伤害只记为团队伤害，没有独占击杀归因。该局的精确 JAR 与最终候选不同，明确归档为**中间原生样本**，不冒充最终 JAR 的实机记录。

## 最终身份与证据入口

- 最终 JAR：`astra-deliveries/global-strategy-2026-09-30/rw-agent-bootstrap.jar`
- SHA256：`76711a8ee2716af5d5b0b66d5c53b159ae28f4f744dcbb24cae492d96f0342ed`
- contentDigest：`8092b61130c3497b370e12ce249d3ec17e6331d502fefbcb50bbd95a32f03120`
- 精确 lineage / JDK / frozen game 与知识包 SHA：`evidence/global-strategy-2026-09-30/candidate-manifest.json`
- 最终回归、最终原生对局、中间长局样本的分开统计：同目录 `regression-summary.json`、`native-summary.json`、`final-native-audit.json`。
- 原始报告与日志：同目录 `raw-evidence.zip`，逐文件 SHA 在 `raw-evidence-manifest.json`。`intermediate-seaFactory-2610-events.jsonl` 每行带原报告行号。

生产构建输入是 `ca00a20`，交付源码 `2f310ec` 仅再修正 native E2 fixture 的初始化先后；`agent/src`、资源与 manifest 构建输入完全一致。最终 native E2 为 46 项检查。更早 `a3a320dd...` 的自然样本已经产生 87,341,909-byte 完整报告，14 次远处矿点到达与 6 座战略工人完成的新矿；这几项也按前一候选独立记录。

最终 JAR 的自然原生对局已在 Battle 644.512 游戏秒得到 `VICTORY`：84/84 攻击命令有执行确认，最大观察到 56 个 mobile armed，报告完整性与任务/地形事件审计均无违规。但该局在能力投资执行前结束，不能把中间候选的工程师、矿升级和跨域清场归给这份最终报告，也不能称其已经跑满 2400 秒。

最终交付 JAR 的完整 Linux 矩阵为 **21 Java Harness + 16 Python Suite，0 失败**；Python 共 281 项，其中 2 项 Windows 文件锁语义测试在 Linux 跳过。独立 native E2 为 46 项。完整日志、实际执行脚本和 SHA 在原始证据 ZIP 中，未继承旧候选的通过结果。

## 实现边界

### Feasibility

`CombatBridge /combat/engagement` 只读取当前己方单位和已经合法观测的敌人记忆。地形仍由 Scout 的先验视野检查写入记忆；从未探索的地图不会因为工程目录里存在 TMX 就进入策略。

`EngagementGeometry` 对同一目标、移动域和射程共享反向场。正向证据需要已知地形四邻接路线和射程内位置；负向证据使用更宽松的八邻接图、未知格可走、射程再放宽一个完整格对角线。连这个乐观图都到不了才给 `BLOCKED_TERRAIN`。普通武器的射程不擅自加碰撞半径；冻结原生 `y.o(am)` 仅在 `aV()` 为真时加双方半径。

拒绝记录按目标和己方单位保存，时间流逝及 UNKNOWN 不会清空它。新的合法可见位置变化、新的已知可接近能力、原址合法 CLEARED 才有相应状态转换。编队超过 48 时分批请求；移动目标在批次间位移超过 20 时，不把批次拼成一条完整编队证明，记录 `engagement_assessment_deferred`。最终审计使用 `engagement_assessment` 的已提交证据，而非任意原始 HTTP 片段。

### 工程师、响应者与投资

`StrategyDirector` 是会话内控制层，复用现有 CommandArbiter 的所有权和全局命令间隔。工程师、两栖喷气机、额外 Builder 在 Recon 分配前认领。普通 Builder 中保留一个给已有 Economy；失去基础 Builder 后允许空闲额外 Builder 移交。

- 工程师由真实 T2 陆厂菜单生产。子任务包含跨域响应、返回、原生建造、本地矿建设和远处已知矿点接近。
- 工程师不会单独主动冲向周围已知威胁数量大于 1 的目标；地表跨域缺口可通过移动工坊建造两栖喷气机。水下目标要求工程师存在已知水面接战位置。
- 响应固定目标 ID；可见目标移动只更新同一个任务的位置。失联时是明确标注的旧址调查，绝不把 UNKNOWN 升格为当前目标可打。
- 无进展、任务时限、死亡、重伤会结束/中断任务；同一能力需求最多三次失败尝试。回执、己方实际运动、目标可见伤害、投资成品观察分别记账。
- 在本地没有建设点时，额外 Builder 可通过 `/scout/resource-approach` 去已合法记忆、沿已知安全路线可接近的资源点。未见资源和敌方单位被拒绝；每次分配最多查询四个点。
- 投资先保护现有 Builder/矿/建设储备，再比较能力缺口、额外施工能力、军力恢复、新矿与 T2 矿升级。T1→T2 的 action 和 1400 成本来自加载后的原生菜单；收益是实测 T1 收入乘冻结资产 12/8 比例的估计，不冒称新实测值。

### 容量与长局

军力需求由地图面积、可见威胁和未满足任务产生，收入支持扩容；默认软目标不超过 96，硬安全线为 128，每 30 游戏秒最多增加 8 个目标槽位。已付费队列、未完成武装单位和战略建造均计入硬上限。显式 `rwagent.mobileUnitHardCap` 仍可进行固定 cap 实验。

active / reserve 在这一版是容量预算分解，**不是已经实现多个战术编队**。旧 Battle 心跳仍含旧的 readiness 数字；动态容量以 `strategy_capacity` 和 summary 内的 `strategy` 为准。Builder 默认在收入、军力、地图/建设 backlog 同时成立时从 1 扩展到 2，余额高本身不触发。

Battle/Match 默认仍为 900 秒。产品预算与测试安全上限分离：默认安全游戏时限 7200，可配置至 21600；墙钟仍有独立上限。headless 新增显式 `--poll-ms`，不再随游戏倍速隐式提高采样密度。

报告写入采用 8 MiB 内存快照 + SHA 校验的磁盘分块，完成时原子提交单个 JSONL；默认逻辑报告磁盘预算 1 GiB，可配置 64..4096 MiB。72 MiB 回归在 `-Xmx96m` 下检查全部字节 SHA，并保留此前的陈旧 journal 替换用例。本轮没有实施最终报告 multipart/manifest 生态；提交期间磁盘峰值约为报告体积的三倍，旧消费者仍可能整份读入内存。

## 明确未解决

1. 经济溢出仍在。中间 2400 秒样本末尾 credits 为 272439；不能把更多矿升级称为已消除过剩投资。当前收益竞争是可解释的启发式，不是成熟的边际价值优化器。
2. 一条自然海厂闭环不等于跨域作战可靠：响应者仍会损失，护航、威胁域细分、实力配比和成组攻击尚缺。喷气机只使用原生 AIR 形态，没有接入潜水切换；没有完整海军/运输。
3. 工程师的修理、回收、repair bay 角色尚未启用。重伤返回不是“已验证修理策略”。同一工人已经尝试但无路的资源点，本轮不随之后地图知识增长自动重试。
4. 未知地形只能 UNKNOWN；这一版不能凭不完整地图证明所有远端任务都不可达。地形路线也不能证明动态碰撞、战斗安全和任务成本合理。
5. 主力在多个都可达的目标之间的战术稳定性、全图终局清理、地图尺度胜率、多 PlayerContext 与同局双玩家均未完成。没有本轮桌面 E4、Windows 实机运行或 A/B 因果胜率结论。

## 低成本复跑与下一轮

```bash
python tools/prepare_headless_engine.py --out .engine/rw115
bash agent/test.sh "$PWD/.engine/rw115/game-lib.jar" "$PWD/.engine/rw115/libs"
python tools/run_headless.py --game-dir .engine/rw115 \
  --agent-jar astra-deliveries/global-strategy-2026-09-30/rw-agent-bootstrap.jar \
  --mode match --speed 5 --poll-ms 250 --timeout 900 --difficulty 1 \
  --battle-seconds 2400 --map 'maps/skirmish/[p2]Big Island (2p).tmx' \
  --out headless-runs/global-strategy
python tools/audit_global_strategy.py PATH_TO_COMMITTED_BATTLE.jsonl --out strategy-audit.json
```

原生 E2 单独编译 `agent/tests/TerrainNativeCostHarness.java` 与 `agent/tests/StrategyNativeHarness.java`，classpath 为候选 JAR、冻结 game-lib.jar、libs/* 和测试输出目录；工作目录设为解压引擎根，`-Djava.library.path=libs`，运行 `StrategyNativeHarness`。它明确重设 fixture 的单位、资金和迷雾，不推进模拟；只能记为 E2。

Windows 常规入口仍为 `agent/test-win.ps1`，本轮矩阵扩展到 21 Java runs / 16 Python suites。先核对 `.gitattributes` 固定的知识资源 LF 与候选身份，不要把旧版 Windows 20/15 结果继承为这一版已通过。

下一轮先做一场正常迷雾桌面验收：检查不可接近海厂不会重新吸走 LAND 主力；能力需求是否被工程师/响应者接手，最终是否出现可见伤害和合法清场。若命令抢占或 UNKNOWN 放开负证据，优先修契约；若契约稳定但响应者损失多，集中做成组响应、威胁匹配和终局清理。不要先把 cap 再翻倍，也不建议立即进入 same-game 双玩家。

`-Drwagent.globalStrategy=false` 可做旧策略对照，但长局/报告基础设施仍是新版本；它不是所有改动的完全消融。已有 A/B campaign 的 1800 秒校验未在本轮扩展。

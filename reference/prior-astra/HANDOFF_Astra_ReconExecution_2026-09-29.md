# Astra 交接：Recon 执行与任务所有权突破（2026-09-29）

## 状态与身份

本轮正式任务为 `ASTRA_BREAKTHROUGH_MISSION_2026-09-29.md`。基于公开仓库 `4853dae2bcdcbe0be9fe23cc54edc57345e69856`，分支 `astra/recon-execution-20260929`。

**交付为待原生验收的客户端候选，未安装到用户本机。已部署候选仍是 KnowledgeBacked v0。** 没有访问 G 盘、原版游戏文件、Replay 或桌面游戏。没有新的 E4、胜率或自对弈结论。

- 新候选 whole-file SHA256：`feb7ad39cb137bda1ef105f6d449bbf1c5875bd9132c0b91790165a824a68f81`。
- 新候选 contentDigest：`1985cb52b52aafb468845898c33b4419b8368f200b4475494cf09796b62a676f`。
- 冻结基底 JAR：`5741e241ce8ec1b921f36ed3274087609d0430f9281c44f6f62c096de84c5f54`，未修改。
- `agent/dist/rw-agent-recon-execution.jar` 是 **client overlay**：只从当前源码编译 BattleClient、CommandArbiter、MoveExecution 及内部类，其他字节来自冻结基底。不是全量源码重建。
- JDK 17、Java 8 字节码；两次独立编译/组装得到相同 JAR 字节。36 个原生桥接、headless、知识资源条目逐项同字节，详见 `artifact-verification.json`。

## 为什么选择这条路线

桌面记录给出 26 次 assignment 前 anchor drift 的信号，但公开 HTML 不含足够原始事件来逐次定因。本轮先构造能够复现机制的 HTTP 场景，运行真实旧版 BattleClient 进程，再运行候选。**这些场景是 E2 模拟，不是原生引擎对局，也不能证明已解释那 26 次的全部原因。**

| 可复现场景 | 冻结旧版 | 新候选 |
| --- | --- | --- |
| 单位沿旧 attackMove 行进，frontier 路线需要转向 | 3 次 assignment 前 drift、0 次 Recon 下令 | 先原生命令接管，再规划；1 次路线执行和严格时序的团队合法刷新，0 次 drift 阻断 |
| move 只出现在两次策略决策之间的己方观察里 | 0 次活动命令观察、路线止于第一段 | 每次己方观察都采执行证据；2 段命令均观察到，合法刷新完成 |
| move 在两次轮询之间完成，观察时命令已清空 | 仅 1 段下令 | 2 段均通过到达证据完成，团队视野目标满足；不伪造活动命令观察 |
| 首段仅 10.5 世界单位 | 固定 12 单位进展门槛阻止后续路线 | 根据本段长度确认进展；两段完成，目标满足 |

另外覆盖：接受但未执行的接管不能授权规划；接管会超时并释放；延迟合法视野更新有从到达时开始的等待窗口。

## 实际改动

1. `CommandArbiter` 提供 session/player/frame/gameTime 观察身份、单位任务归属和共享的 1000 游戏毫秒命令门槛。BattleClient 所有现有 POST 路径进入该门槛；整个单位组先检查再消耗时隙。被原生拒绝的尝试不返还时隙。策略产生的 ID 不能自己补入己方单位集合；来自已校验会话的原生 production menu 的己方工厂观察可以补入，下一次 /state 会替换该集合。
2. Recon 使用带观察身份的 `MoveIntent`。新 frontier 在规划前取得单位归属，主力选择排除该单位。若仍有旧原生命令，先通过正常 move 到合法己方观察位置接管，后续观察确认后才规划。接管不是瞬移、暂停引擎或直接清除原生队列。
3. `MoveExecution` 在每次 /state 后检查回执帧之后的己方观察。活动 move 和到达后命令已清空是两类证据。后者要求位置进展且不存在冲突命令；静止、仅 queued、同帧、其他玩家或其他单位均不算执行。
4. 短航段按自身长度衡量进展；航点还要位于正确格子。到达后的视野等待从观察到达时计时，发令分支不能提前结束等待。未分配任务和接管都有明确期限。
5. 保留旧 `recon_frontier_refreshed` 的严格时序要求。若合法区域已在采样间隔内刷新、但无法证明严格的“观察进展先于刷新”，使用独立 `recon_frontier_satisfied` / `frontierTasksSatisfiedByTeamVision`。它表示信息目标已满足，不表示侦察者独自造成刷新。`recon_order_observed` 也不把到达证据混进去。
6. 吸收上一轮 patch 中的 Windows 构建资源打包和 Linux 回归入口缺项，并把 ExecutionContractHarness 登记到两套完整回归入口。**上一轮非 Windows reap 修复等其余内容未在本轮吸收。**

保留：攻击兼容 Guard、UNKNOWN 与合法地形来源、六名主力最低保护、builder recovery 和经济预留逻辑。未调宽 anchor 容差，未修改军力 cap、兵种评分、原生桥接或启用危险反射。

## 架构边界与剩余限制

这次形成的是实际被 Recon 和战斗命令路径使用的执行边界，不是完整策略框架。现有经济预算仍在原控制器中；大部分规则仍在 BattleClient。原生桥接仍绑定单个本地玩家，HTTP 观察仍可能跨帧；本轮没有宣称同帧 observation bundle 或 same-game self-play 已完成。

Stamp 对真实桥接使用 teamId；旧测试端点省略 teamId 时明确使用 `legacy-local`，不能据此声称有独立双玩家视角。命令额度仅覆盖 Battle 阶段；openingApmLimited 仍为 false，不是完整竞技公平认证。

接管额外消耗一次正常命令。拥堵、碰撞、接管延迟、5x 原生采样行为和真实区域刷新需要下一步原生验证。失败采取显式释放/阻断；没有无限重试或自动把未知地形当作可达。

## 验证与复跑

最终候选相关 Python **128 项通过**：frontier 22、旧址侦察 7、战斗策略 78、目标兼容 6、报告 15。另 **32 项 Java 执行契约检查通过**。这是选定的受影响路径验证，不是原项目完整原生回归。

`evidence/recon-execution-2026-09-29/summary.json` 保存最终测试身份、日志哈希和旧/新对照计数。`raw-fixtures.zip` 内有 baseline / final 的原始 JSONL 与模拟服务器调用记录，也保留早期失败迭代；全部标为 E2。不要把其中模拟的 native_result_screen 字段当 E4。

```bash
python tools/build_recon_client_overlay.py --out agent/dist/rw-agent-recon-execution.jar
python agent/tests/test_recon_frontier_client.py agent/dist/rw-agent-recon-execution.jar
python agent/tests/test_recon_client.py agent/dist/rw-agent-recon-execution.jar
python agent/tests/test_battle_client.py agent/dist/rw-agent-recon-execution.jar
python agent/tests/test_target_compatibility.py agent/dist/rw-agent-recon-execution.jar
python agent/tests/test_battle_reports.py
java -m jdk.compiler/com.sun.tools.javac.Main --release 8 -cp agent/dist/rw-agent-recon-execution.jar -d agent/build/recon-client-tests agent/tests/ExecutionContractHarness.java
java -cp agent/dist/rw-agent-recon-execution.jar:agent/build/recon-client-tests io.rwagent.client.ExecutionContractHarness
```

最后一条 classpath 分隔符在 Windows 改为分号；也可在有合法引擎依赖时使用 `agent/test-win.ps1` 跑全量回归。Windows BAT 和游戏相关 Java harness 本环境未运行。

## 用户环境的下一步验收

在兼容 1.15 的独立测试副本中，保留旧 JAR，完整退出旧游戏和客户端后部署交付包中的 `rw-agent-bootstrap.jar`。游戏启动端与 Match 客户端必须使用同一个新 JAR；不要把新客户端挂到仍加载旧 JAR 的游戏上，否则现有报告中的桥接 JAR 身份不能代表策略身份。

沿用 Start → 裸开局遭遇战 → Match → Collect Reports。先在相同地图条件分别做 1x / 5x，重点观察旧命令接管是否完成、主力是否持续作战、frontier 是否执行及正确释放。保存所有失败、超时与原始报告。不要以单局胜负评价收益，也不要把 satisfied 直接并入严格 refreshed。

## 下一轮最值得继续的两件事

1. 在原生环境复核本候选，按 queued → 活动订单/到达证据 → 真实位置 → 团队合法刷新分类，量化 assignment drift 是否下降及接管成本。如果原生暴露稳定阻塞，再针对那个机制收尾。
2. 以已经存在的 Stamp / MoveIntent / 命令仲裁为客户端边界，推进原生 PlayerContext 和限制规则 same-game 双玩家最小实验；优先隔离各自视野记忆和控制权限。不要以切换 engine.bs 或删除 network/replay guard 冒充双玩家支持。

# 通用玩家桥：实现边界

## 原生同局路线

采用两个原版 1.15 引擎进程，通过 native multiplayer TCP host/join 进入同一局。每个进程只拥有一个原生本地玩家；HTTP 是该玩家的本机观察/操作接口，游戏同步仍走原版网络。并行启动两个独立单机进程不构成同局证据。

```mermaid
flowchart TD
    A["冻结 Agent A / 外部 Agent"] --> GA["A 独立本机 gateway"]
    GA --> BA["A local-player bridge"]
    BA --> EA["原版 native host"]
    EA <-->|"原版 TCP lockstep"| EB["原版 native join"]
    EB <-- BB["B local-player bridge"]
    BB <-- GB["B 独立本机 gateway"]
    GB <-- B["冻结 Agent B / 外部 Agent"]
    EA -.-> R["私有 referee 文件"]
    EB -.-> R
```

| 层 | 职责 | 不持有的权限 |
|---|---|---|
| `bridge/src/io/rwbridge/engine/NativeNetworkRunner.java` | 原版 host/join、地图加载、推进 tick、生命周期证据 | 不替换原版网络命令执行 |
| `bridge/src/io/rwagent/bootstrap/` | 绑定一个 native local player；合法观察、自己的动作及原版队列 | 不切换 `engine.bs`，不调用策略模块 |
| `protocol/gateway.py` | v1 identity/observe/action/lifecycle，独立会话和动作序列 | 不转发其他玩家观察，不读取 referee 全知证据 |
| `adapters/` | 将两种冻结 Java Agent 的既有决策循环接入 gateway | 不统一或重写两套策略架构 |
| `orchestrator/`、`referee/` | 进程归属、端口、换边、超时、日志及证据判定 | 不将超时视为胜负，不向 Agent 提供隐藏状态 |

桥中的 `TargetCatalog`、`EngagementGeometry`、`Json` 是复制到 bootstrap 的静态规则/数据辅助，没有 `GeneralRegistry`、`Commander` 或冻结客户端策略依赖。冻结源目录和 JAR 保持不变；桥 overlay 位于 classpath 前方。冻结客户端各自使用独立的权限 guard overlay。

## 身份和授权

默认 `RuntimeBridge.start` 保留 network 拒绝行为。只有原生 runner 完成真实地图加载、确认 `engine.bX.B=true`、`engine.bX.z==engine.bs`、期望槽位与 `engine.bs.k` 一致后，才能调用 `startNativeNetwork`。拒绝 spectator 和 replay。

`PlayerBinding` 固定记录 engine、native network 对象、地图对象、local player 对象、槽位、联盟组和 match ID。断线、换图、换 local player 或改联盟组会永久撤销此绑定；恢复原字段不能复活它。观察与命令分别校验权限，禁用动作的合法本地绑定仍可读观察。不会通过交替修改全局 `engine.bs` 来伪造双玩家隔离。

| 字段 | 含义 |
|---|---|
| `matchId` | runner/orchestrator 分配的比赛标识；必须另以 native server ID、地图和 seed 佐证同局 |
| `sessionId` | 每个本地桥单独生成，地图/身份/加载状态或回退 frame 改变时更新 |
| v1 / `/state` 顶层 `playerId` | native `n.k`，玩家槽位 |
| v1 / `/state` 顶层 `teamId` | native `n.r`，真实联盟组 |
| 兼容 `/state.player.teamId` | 历史字段，仍表示槽位 `n.k`，供冻结客户端读取 |
| `/state.player.slotId`、`playerId` | 同一槽位 `n.k` 的明确别名 |
| `/state.player.allyGroup` | 真实联盟组 `n.r` |
| `networked` | 原生实际网络状态，网络对局始终为 `true` |
| `nativeNetworkPlayerV1`、`identity.localPlayerVerified` | 当前显式原生本地玩家授权是否有效 |
| `transport`、`commandTransport` | 有效网络绑定为 `native-network-local-player` |

Gateway 固定首次验证的 `{matchId,sessionId,playerId,teamId}`，后续变化即拒绝。一个玩家的 session 不能用于另一个玩家。

## 视野与命令

自己的单位和经济只按绑定 player 的所有权读取。敌人必须同时通过原版 `unit.d(player)` 和地图当前 fog 判断后，才复制实时属性。失联记忆保存上次合法样本；看不见目标不会推断死亡，也不会刷新隐藏位置、HP、模式或目标类型。v1 的 `currentVisibleEnemies` 只包含当前可见样本。

固定地图先验只读 Ground/Items/PathingOverride。合法 scout 路线只读取静态 terrain cost 和已合法看到的 building footprint，禁止完整动态 path blocker 网格：隐藏建筑延伸到可见边界的 footprint 也不能成为侧信道。`/combat/reachability` 全局原生网格诊断在此通用桥上返回 403。

建设规划返回可见范围内的静态地形/已观察占用候选，不调用会查询全局隐藏碰撞的 `ghost.b(false,player)`。原生执行仍可能因隐藏阻挡或更完整的原版放置规则拒绝该候选；规划和排队都不构成最终合法放置证明。

动作只解析自己的 actor ID；外方 ID 和不存在 ID 返回同类错误。Bridge 通过原版 `engine.cf.b(boundPlayer)` 建立命令，network 模式由原版进入 `cf.d` 预处理/网络路径，随后参与原生同步。动作不会在桥中直接移动、生产、扣款或生成单位。

`queued` 是本地原版队列收据。v1 标注 `executedEffect=UNKNOWN`；它不证明对端执行、到达、命中、击杀或生产完成。实际效果需要后续合法观察或独立 referee 原生证据。客户端 request ID 重试缓存、会话检查和 gateway 序列检查用于避免重复提交。

结果只读自己原版玩家的 `G`（defeated，包括投降）、`H`（victorious），以及 GUI 原生结果 `dt/dq`。`F` 只表示没有建筑/建造者，不是最终 defeat。结果来源为 `native_player_flags` 或 `native_result_screen`；断线、人工停止和 wall timeout 独立记录，不伪造胜负。

## 验证入口和边界

```sh
python tools/build_bridge.py
python tests/player_scope/run.py
python -m orchestrator.run_match --help
```

实际双进程运行参数以 `python -m orchestrator.run_match --help` 为准。

`tests/player_scope/run.py` 使用真实 1.15 数据对象检查授权、foreign-ID 拒绝、原版网络队列、隐藏 blocker 不变性、可见/失联记忆以及 binding 撤销。它不推进完整游戏，不能替代 native same-game 实验。Native 运行报告和原始日志位于所选 `headless-runs/` 输出目录，最新验收状态应查看其证据报告。

Windows 原版桌面 Human-vs-Agent 的人工验收流程见 [WINDOWS_HUMAN_VALIDATION.md](WINDOWS_HUMAN_VALIDATION.md)。当前没有用户桌面会话或 GUI 操作证据，文档步骤不等于桌面验收完成。

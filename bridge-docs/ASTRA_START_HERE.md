# Astra Start Here — 通用对战桥

你的任务是搭建一个 **Agent-agnostic Rusted Warfare player bridge**，而不是为某个具体战略模块写专用接口。

桥的公共边界应围绕：
- player / team identity
- session / match lifecycle
- legal observation
- action submission
- capability negotiation
- result / disconnect / referee

桥不应该知道 `GeneralRegistry`、`CombatLedger`、`ThreatTask` 或某个旧 Agent 的内部策略类。

## 推荐先检查

- `agents/octopus-g42/agent/src/io/rwagent/bootstrap/RuntimeBridge.java`
- `agents/octopus-g42/agent/src/io/rwagent/bootstrap/CombatBridge.java`
- `agents/octopus-g42/agent/src/io/rwagent/client/BattleClient.java`
- `agents/octopus-g42/tools/run_headless.py`
- `agents/octopus-g42/agent/tests/test_headless_parallel.py`
- `agents/octopus-g42/docs/HEADLESS_CN.md`
- `reference/prior-astra/HANDOFF_Astra_ReconExecution_2026-09-29.md`

## 关键事实

当前桥层显式拒绝 networked games；当前 `--parallel-pair` 是两个独立原版实例，各自对原版 AI，不是 self-play。

优先做一个真正 same-game 的最小实验。可以自行比较：
1. 两个原版进程通过 native multiplayer host/join，各自 local bridge 控一个玩家；
2. 单进程显式 PlayerContext 双玩家。

先追求可运行、可证明的 same-game，再决定是否值得做更重的单进程 PlayerContext 重构。

# Rusted Warfare Universal Match Bridge

这是独立的通用玩家桥工程。两个原版 1.15 引擎进程通过 **native multiplayer host/join 进入同一局**，每个进程绑定一个原生本地玩家。Legacy、Octopus 与外部 Agent 通过各自本机 gateway 接入。

- Agent ↔ Agent 可以进入同一局原版 Rusted Warfare 对战；
- Human ↔ Agent 可以通过同一套桥运行；
- Legacy Agent、Octopus Agent 和未来其他 Agent 都可通过 adapter 接入；
- 每个玩家拥有独立、合法的视野、控制权、会话和结果证据。

## 运行

需要 Python 3.10+、JDK 17 和仓库中已提供的兼容引擎包。原版资源及两套冻结 Agent 不修改。

```sh
python agents/octopus-g42/tools/prepare_headless_engine.py --archive engine-relay/headless-engine-1.15.zip --manifest engine-relay/HEADLESS_ENGINE_MANIFEST.json --out .engine/rw115
python tools/build_bridge.py
python tools/test_bridge.py

# M0：真实同局、双方合法远端观察、越权拒绝、原版投降及胜负
python orchestrator/run_match.py --transport-proof --speed 4 --timeout 5 --out headless-runs/m0

# 两套原始决策循环，同局并换边；超时如实记录 TIMEOUT
python orchestrator/run_match.py --agent-a legacy --agent-b octopus --matches 2 --swap-sides --speed 4 --timeout 120 --out headless-runs/legacy-octopus

# 同结构自对弈
python orchestrator/run_match.py --agent-a octopus --agent-b octopus --speed 4 --timeout 120 --out headless-runs/octopus-selfplay
```

每次 `--out` 使用新目录。比赛报告、原始观察、命令回执、子进程日志和退出归属写入该目录。沙箱若禁止 loopback socket/子进程管理，需要在允许这些操作的环境运行；这不是原版引擎限制。

## 当前交付

| 范围 | 实现与证据边界 |
|---|---|
| M0 native transport | 两个独立原版进程、不同玩家、同一原生 server ID；双方移动效果经对端合法视野验证，原版投降产生一致胜负 |
| M1 adapters | 两套冻结 Agent 使用各自 capability guard overlay；公共 v1 包含身份、观察、动作、菜单、生命周期和 queue-only receipt |
| M2 Agent vs Agent | Legacy/Octopus 同局运行和换边；未决对局保持 TIMEOUT，不据此评价强弱 |
| M3 Human vs Agent | 复用同一 native host/join；提供 Windows 本地接入和验收步骤，GUI 互通未在远端实测 |

精确的候选身份、测试数量、原始报告和限制见 [本轮原生证据](evidence/native-bridge-20261003/README.md)。

- [架构、身份和视野边界](docs/ARCHITECTURE.md)
- [公共协议](protocol/README.md) / [冻结 Agent adapters](adapters/README.md)
- [比赛 runner、batch、换边和清理](orchestrator/README.md) / [结果判定](referee/README.md)
- [Windows Human vs Agent 验收](docs/WINDOWS_HUMAN_VALIDATION.md)

## 原始交接入口

1. `bridge-docs/ASTRA_START_HERE.md`
2. `bridge-docs/ENVIRONMENT_CAPABILITY_MATRIX.md`
3. `bridge-docs/SOURCE_IDENTITIES.md`
4. `bridge-docs/BRIDGE_TASK_BRIEF.md`

## 目录

- `bridge/`：原版网络 runner 与固定玩家授权的原生桥。
- `protocol/`：面向第三方 Agent 的小型 HTTP v1 合同。
- `adapters/`：两套冻结决策循环的适配与来源校验。
- `orchestrator/`、`referee/`：同局比赛管理及证据归属。
- `tests/`、`tools/`：focused tests、原生黑盒验收、可复现构建。
- `agents/octopus-g42/`：冻结的 Octopus 基线源码，源自 `83c09fb`，不含正在本地施工的 G5。
- `agents/legacy-production-capacity/`：冻结的上一代 Production Capacity 基线，源自 `be7ba94`。
- `binaries/`：两套冻结 Agent JAR，方便协议/运行时实验。
- `engine-relay/`：原版 1.15 headless 引擎包、manifest 和说明。
- `reference/prior-astra/`：Astra 以前关于 execution / PlayerContext / same-game 的交接记录。

旧 `agents/*/tools/run_headless.py --parallel-pair` 仍表示两个独立单机对局。只有新的同局 runner 使用原版 multiplayer；`parallel-proof PASS` 不能代替这里的原生同局证据。

# Rusted Warfare Agent Bridge Workspace

这是给远端 Astra 使用的“通用对战桥”独立工作区。目标不是继续开发某一套 Agent，而是建立一个与 Agent 内部架构解耦的桥，使：

- Agent ↔ Agent 可以进入同一局原版 Rusted Warfare 对战；
- Human ↔ Agent 可以通过同一套桥运行；
- Legacy Agent、Octopus Agent 和未来其他 Agent 都可通过 adapter 接入；
- 每个玩家拥有独立、合法的视野、控制权、会话和结果证据。

## 先读

1. `bridge-docs/ASTRA_START_HERE.md`
2. `bridge-docs/ENVIRONMENT_CAPABILITY_MATRIX.md`
3. `bridge-docs/SOURCE_IDENTITIES.md`
4. `bridge-docs/BRIDGE_TASK_BRIEF.md`

## 目录

- `agents/octopus-g42/`：冻结的 Octopus 基线源码，源自 `83c09fb`，不含正在本地施工的 G5。
- `agents/legacy-production-capacity/`：冻结的上一代 Production Capacity 基线，源自 `be7ba94`。
- `binaries/`：两套冻结 Agent JAR，方便协议/运行时实验。
- `engine-relay/`：原版 1.15 headless 引擎包、manifest 和说明。
- `reference/prior-astra/`：Astra 以前关于 execution / PlayerContext / same-game 的交接记录。

不要假设这里已经支持 multiplayer。现有 headless 双实例只证明两个独立原版进程可并行运行；same-game host/join、双玩家视野隔离与网络命令提交仍是桥工程要解决的核心问题。

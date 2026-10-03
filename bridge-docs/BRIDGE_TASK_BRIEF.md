# Bridge Task Brief

目标：设计并实现一个与 Agent 内部结构解耦的 Rusted Warfare 双玩家桥，最终支持：

- Agent A vs Agent B，同一局原版游戏；
- Human vs Agent，同一套 player bridge；
- Legacy Agent 与 Octopus Agent 结构不同仍可接入；
- 未来 Java/Python/LLM Agent 可通过 adapter 接入。

## 公共桥合同

建议将边界放在：
- `PlayerObservation`：session/player/team/frame/gameTime、己方单位、合法可见敌人、经济、固定地图信息、比赛状态；
- `PlayerAction`：move/attack-move/build/produce/upgrade/unit-mode 等；
- `Lifecycle`：connect/ready/start/end/disconnect/result；
- `Capabilities`：协议版本、可用 observation/action；
- `Referee`：地图、槽位、换边、结果、超时和日志。

Agent-specific adapter 可以知道内部策略结构；公共 bridge 不应知道。

## 第一阶段优先目标

1. 证明两个 player contexts 真正处于 **同一 match**，不是两个独立单机实例。
2. 证明双方 observation 绑定各自 player/team，并遵守各自 fog。
3. 证明双方命令只控制各自单位，并经原版引擎/网络生效。
4. 证明 victory/defeat/disconnect 可以归属于正确玩家。
5. 在此基础上让两种冻结 Agent 都能经 adapter 启动。

## 关键硬边界

- 不要通过反复切换全局 `engine.bs` 冒充双玩家隔离。
- 不要简单删除 `networked` guards 后就宣称多人支持。
- 不可把 fog 中隐藏敌方实时状态暴露给任一 Agent。
- 不同玩家必须拥有独立 observation memory / command authority / session-player identity。
- receipt / queued 不等于执行、到达或命中。
- lost visibility 不等于目标死亡。

## 工程主动权

Astra 可以自主：
- 研究 `game-lib.jar` 中 multiplayer host/join/network command path；
- 扩展现有双进程 runner；
- 引入 PlayerGateway / adapter / referee；
- 必要时重构 RuntimeBridge 的 player binding；
- 创建 focused native experiments。

优先做最短可运行 same-game 闭环，再做自动 lobby、批量比赛和单进程优化。不要先为了理论完整性进行大型抽象重写。

## 推荐验收顺序

`same-match identity → fog isolation → command isolation → human/manual host-join MVP → dual-Agent MVP → side swap/result logging → automation/batch`

开发阶段以 focused 实验为主；不要用大量与桥无关的旧全矩阵回归消耗工程时间。

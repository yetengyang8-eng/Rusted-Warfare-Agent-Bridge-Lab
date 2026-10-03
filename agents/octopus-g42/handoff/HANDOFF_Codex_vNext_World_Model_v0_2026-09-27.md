# 给 DeepSeek / GPTsol：vNext 只读战争记忆已施工并完成本机验证（Codex，2026-09-27）

**状态：本机 PASS，待独立验收。** 我先读 `HANDOFF_FOR_CODEX.md` 和你们的 `HANDOFF_对话48.md`、`Codex_vNext_能力扩张与路线决策合同_2026-09-27.md`，改代码前跑完整回归基线（17 Java + 12 Python、失败 0）。DeepSeek 对 A/B campaign v0 的 ACCEPT 已纳入现状；本轮没有继续扩大 cap32/40 样本，也没有改策略语义。选定 World Model v0 的第一步：让桥接层实际记住合法见过的敌人，同时先不给战术层新的行为。

## 已实现、未实现

- `CombatBridge.observe()` 新增独立于原有战术 `rememberedEnemies` 的 `enemyIntel`，按 unitId 保存首次目击、末次目击、最后已知类型/位置/HP/建筑/攻击标记，状态仅 `VISIBLE` 或 `LOST_CONTACT`。只从通过原有敌对过滤与两项原生视野检查后的可见快照更新。旧位置重见为空时不宣布死亡；隐藏后不读底层敌人属性。会话切换清空。最多保留 256 条失联/可见历史的常态上限；超限先淘汰最久未见的失联项，同时可见超过上限时短暂保留全部可见。
- `BattleClient` 仅把可见、失联、累计淘汰三个计数记入 `agent_tick_alive`。当前目标选择、军力紧急度、搜索、生产和经济逻辑依旧只使用原有 `visibleEnemies` / `rememberedEnemies` 与其他旧数据；没有策略 A/B 的效果主张。
- 原生 replay 自动录制**未交付**：试验性原生文件能生成和打开，但三次播放均从 frame 6452 起持续 checksum 不一致。实验改动已从正式 `HeadlessRunner.java` / `run_headless.py` 撤回。多人 Match/self-play 的同局双 Agent、合法双视角与命令归属也未实现；本地桥接仍对网络局 409。经济 vNext 的候选竞争涉及策略语义，本轮未碰。

## 验证与身份

- 代码改后第一轮完整回归仅 `CombatHarness` 失败，原因是测试在模拟换局后继续拿旧 sessionId 测后续生产命令；这是新增夹具排序问题。修正并增加“隐藏期间推进游戏时间、末次目击时间不变”的断言后，定向 CombatHarness **105 checks PASS**，最终完整回归 **17 Java + 12 Python、失败 0**。完整日志在本次 Codex 工作目录 `work/vnext-world-final-regression-2026-09-27.log`；基线在 `work/vnext-baseline-regression-2026-09-27.log`。
- 隔离无画面原生对局使用的 JAR 与现安装件、dist **逐字节同一 SHA**：`09dca50b15270c4e2b1d2410edfb2698c8d81f7a2bca9e1d0ad181f08d5645fa`。源码/安装件 contentDigest：`63db4e6543b3052c9f065c80f27412f5372d2d66ef5267d11b32dcda6156a635`；`candidate_sha_lineage.json` 的 `aVsInstalled=[]`、`aVsDist=[]`。旧 P1F JAR 备份于本次 Codex `work/p1f-before-world-model-v0.jar`，SHA `0f43f697…25a`。冻结环境根 JAR 和原版 game-lib 未改。
- 原生局 `G:\deepseek 工作台\_analysis\vnext-world-native-2026-09-27\run-20260927T150542-914c74\episode-001`，小岛 2p、4x、战斗预算 300 游戏秒；原始报告 SHA `72aed40174f391c2aadd57489a9ce49c51fa246fae7a0aae5dcf8dc93847823b`，与 `episode.json` 一致。29 条心跳，可见情报最多 5、失联最多 35；114s/135s 可见与原战术记忆都为 0 时，新历史记忆仍保留 17/18 条。终局未出现，报告 **PARTIAL/ONGOING**、integrity issues 为空，runner 因未见原生终局退出 1；绝不能把它写成胜利或完整比赛。
- 独立复算脚本 `_analysis/world_model_v0_native_audit.py` 与结果 `evidence/world_model_v0_native.json`，详细证据 `evidence/world_model_v0.txt`；源码、原始报告和失败的 replay 验证日志在 `evidence/world_model_v0_review.zip`，内容按文件 SHA 清单核对。说明文档 `docs/HEADLESS_CN.md`。

## 风险与下一步裁决

1. World Model 目前是合法情报保存能力，**未让 Agent 利用历史目标行动**；下一步若用失联目标派侦察、改变搜索/攻击或经济，应先交接策略语义、可回退开关、与旧行为的比较口径，再用已经 ACCEPT 的 campaign 做有效验证。不要凭这一个 PARTIAL 样本调参。
2. 没有 `CONFIRMED_DEAD`：旧坐标清空并不能证明死亡；当前状态不代表敌人还活着、位置没动或 HP 未变。会话内 unitId 重用、动态变更联盟以及 >256 大规模接触尚无实局验证。网络局桥接为 409，多玩家视角未验证。
3. 原生 replay 先找 frame 6452 之前录制/播放状态分歧，要求 checksum 全程一致后才可交付。多人 Match 先只读证明双本地玩家、各自迷雾和命令归属的端到端路径，不能写未知引擎字段。

## 子智能体使用

本轮使用 **3 个只读子智能体**，共享目录由 Codex 单独改代码：`deepseek_acceptance` 审核合法视野来源、历史语义与测试缺口，其建议促成 lastSeen 时间断言和可见者不被循环淘汰；`gpt_plan` 审计多人 Match 的 headless/slot/team/网络阻断；`runner_audit` 追查沙盒“启动录像”的原生入口和录制/播放边界。后两项结论只进入风险图，没有进入正式实现。请独立验收桥接视野、会话隔离、回归、原生 raw/episode 身份，以及回放失败结论。

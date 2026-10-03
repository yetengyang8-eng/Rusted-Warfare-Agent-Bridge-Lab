# 八爪鱼 G2 交接

G2 从用户验收的 ff693c8 开始，交付只读 WorldState 与合法快照差分。旧策略继续使用原响应，不读取新世界，不由事件发令。最终完整 Windows 回归通过，本轮停在 G2。源码与测试已冻结，后续提交只补交付文档。

## 身份

- 分支：codex/octopus-g2-world-state-20261002。
- 起点：ff693c805c4069a62ed451e33cffb882245cea35。
- 实施：5e30a344f7a6f46e53765ba9b95e86df7b3f3691。
- 最终源码修正：5dd76e41d95887ec6c8dc6647476491b9b5241a6。
- 候选：RW-CANDIDATE-2026-10-02-OCTOPUS-G2-v1。NOT_DEPLOYED。
- 构建 JAR：1dbeb3084f9ee7dd154b7290daef6c9fc2fe780d9e033fd45539fb3aa01ac7d9，362190 bytes；最终矩阵及固定候选逐字一致。
- contentDigest：9e51d99cc67e00833080ae1b1a23387015f7b8bafc460b70206bee9a4c03998b，121 非 manifest 条目。

## 实际接入

WorldState 不可变，SourceView 按 endpoint +完整 requestPath 分别保存 authority、coverage、source/receive 时钟。UnitFact/EnemyFact 的当前字段与 LastObservation 分开。G1 native identity 必须一致、同运行；state 要真实整数 teamId。G2 epoch 只是事实覆盖生命周期，不是控制权 generation。

BattleClient 仅在原有活动局/session/frame/执行观察校验后双写 state，其他已校验 GET 在原 session check 之后双写。Map 引用必须是刚才那个 GET；POST receipt 不进入。原本不走校验的辅助 GET 保持不纳入，未新增 GET/guard，也未改变出令路径。

EventAdapter 处理 session/player reset、frame/gameTime rollback、断档、同 Observation 重放、同 stamp 重复/冲突与迟到。重复合法读取更新 freshness 但不重复派生变化；冲突和断档重新建基线，UNKNOWN 不变为 0/空集合证明。Source game age、wall age、检测锚点 age 分列，不插值；源领先 state/墙钟回退年龄未知。不同端点不原子同帧。

派生首次/重现/ready、己方不可用与 HP band、队列变化/空、合法可见敌更新/失联、旧建筑址清场和 Scout 子集变化。缺 enemy maxHP 不出相对档位；隐藏动态 current 为空，只留历史；confirmedEnemyLoss=null/NEEDS_EVIDENCE。queue 空不证明产品 ready、血缘或 ghost 释放。ready 只证明合法样本，Specialist 类型标签不等于 Pool 匹配。

源码的主决策循环、post 命令路径逐字保留（只归一化换行）；55 份原有 production/tools 文件内容未变。CommandArbiter、原生 bridges、Strategy、LocalArmy、LocalCrisis、Production、Mine/Surplus 所有原机制未改。

## 事件与开关

完整契约见 [WorldState/Event 契约](../docs/OCTOPUS_G2_WORLD_STATE.md)，交付入口见 [证据](../evidence/octopus-g2-2026-10-02/README.md)及[候选 manifest](../deliveries/octopus-g2-2026-10-02/candidate-manifest.json)。新增 g2_world_update（摘要）/g2_event（差分证据），顶层保留 G1 Trace。G2 eventId 为 run:g2:epoch:e:N，occurredAtGameTimeMs=null。

-Drwagent.g2WorldState=false 关闭 G2，保留 G1；-Drwagent.g1Trace=false 同时关闭 G1 输出及 G2。不承诺零分配/自然轮询成本。

## 验证与限制

**最终完整 Windows：60/60 步、31 次 Java harness、26 个 Python 套件/448 tests、failed steps: 0、exit 0；Python skip 0。** 原有 ReportCommitHarness 的 Windows/POSIX displacement 子项平台跳过仍在，不宣称所有平台分支均执行。冻结 ff693c8 基线为 58/58、30 Java、25 Python/439 tests、failed steps: 0。

132 份源码/测试/资源/tools/runner 的测试前后及当前仓库字节一致。候选 JAR 测试期间不变；受保护引擎、已安装 agent、设置、存档、20 libraries 和 868 assets 均未改变。73 core focused、14 independent probes；G1 41/Strategy169/Engineer61 focused；四主路径及真实 native 缺时间字段补充输入三方等价；四安全拒绝路径三方实际退出规则等价。

G2 HTTP 对照为同一适配后的合法 fixture 输入，显式补齐原 Recon fixture 缺失的 teamId=0，对所有变体完全相同；另有 native-stamps 变体去掉 scout gameTime 和菜单 frame/gameTime。原未适配 G1 四路径仍保留并完整比较。比较所有 HTTP 请求/原响应、命令以及旧 event/data；只归一化 request UUID 和三项原墙钟诊断。

保留初版独立探针五个缺陷、测试夹具把预期 exit1 当失败的四个初次 focused 失败，以及 5e30a34 旧矩阵 PARTIAL_SOURCE_SUPERSEDED（12已完成PASS，非完整矩阵）。最后发现缺时间的空列表可制造失联，补两条覆盖校验后 source73/独立14通过，再跑最终矩阵。

冻结 ff693c8 的 frame2 安全拒绝复现了原有早期 report commit NullPointerException；本轮不修改 finally。正式拒绝对照在已有 lastState 的 frame3 使用真实 exit1，G2 on/off/基线相等。这是已有报告健壮性后续问题，不是 G2 行为漂移。

确定性合同与等价测试不证明自然 wall polling 完全相同。新增深拷贝、差分、JSON/flush 有成本；G1 Trace 在双方均开启时，G2 on/off 导出字节比例：Recon 2.759028、LocalCrisis 2.310343、工程师 provider 2.272116、普通 production 1.828950。这只是诊断导出量，不是 CPU/延迟/胜率数据。自然 headless/桌面 NOT_RUN，新 JAR 未安装。原 engine/历史交付/设置/存档/回放未写，未启停用户桌面游戏。

## 后续边界

下一断点仅建议 G3，需另有用户授权：在当前事实基础上迁移旧 lane 的 ownership/Intent/execution，不改战争策略时先证明 gate 与 caller 两层迁移。需要 owner generation、旧意图拒绝、同 actor 冲突、有界预算、批内有效资金/slot 与 paid commitment 保留；不能把 G2 epoch 当 generation。G3.5 再做已有工程师/两栖链的纵向迁移，FREE/General/新 Capability 继续后置。

用户新增协作偏好：后续如安排子智能体，优先指定 gpt-6.1-sol、high，不使用 Astra。


## 复核入口与回退

- 只读事实：[WorldState.java](../agent/src/io/rwagent/client/WorldState.java)。差分与覆盖规则：[EventAdapter.java](../agent/src/io/rwagent/client/EventAdapter.java)。原调用者接入：[BattleClient.java](../agent/src/io/rwagent/client/BattleClient.java)。
- 确定性合同：[WorldStateHarness.java](../agent/tests/WorldStateHarness.java)；HTTP 三方等价与拒绝测试：[test_g2_world_state.py](../agent/tests/test_g2_world_state.py)。
- 完整回归原始输出：[full-windows-regression.txt](../evidence/octopus-g2-2026-10-02/full-windows-regression.txt)，逐项结果：[final-validation-summary.json](../evidence/octopus-g2-2026-10-02/final-validation-summary.json)。
- 真实 fixture Trace 示例与对应 Observation：[selected-event-examples.json](../evidence/octopus-g2-2026-10-02/selected-event-examples.json)。27 份完整报告及 HTTP/outcome 的本地路径与哈希：[raw-g2-artifact-index.json](../evidence/octopus-g2-2026-10-02/raw-g2-artifact-index.json)。远程审阅者不能假定本地 raw 自动可访问。
- 本地隔离验证根：`G:\deepseek 工作台\_validation\octopus-g2-20261002`。固定候选：`fixed-candidate/rw-agent-bootstrap.jar`。不要从 superseded `final-source/final-full` 取 JAR。
- 可复现的运行环境、调用和保留失败说明：[LOCAL_VALIDATION_README.md](../evidence/octopus-g2-2026-10-02/LOCAL_VALIDATION_README.md)。矩阵由隔离目录 `run-final-v2.ps1` 执行，完整过程未启停桌面游戏。
- 运行时只回退 G2 收集可用 `-Drwagent.g2WorldState=false`；恢复历史候选需要另行授权安装。不要覆盖原游戏目录，也不要因冻结基线核对报源码偏离而强行 reset。

本轮仅本地提交，未推送，未部署。正式交付 commit 是源码提交之后的 docs 提交；构建源码身份固定为上面的 5dd76e41，不把 docs commit 冒充另一次已测试构建。

冻结 GS 血统只读核对仍报告 `source_drift`（exit1），对应其后已授权的 capacity/G1/G2 改动；不是当前 ff693c8→G2 回归失败。环境核对无其他失败，本轮不 reset。原输出见 [frozen-lineage-verification.json](../evidence/octopus-g2-2026-10-02/frozen-lineage-verification.json)。

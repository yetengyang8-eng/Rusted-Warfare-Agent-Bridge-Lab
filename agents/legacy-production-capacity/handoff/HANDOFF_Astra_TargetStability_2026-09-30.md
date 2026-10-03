# 目标振荡修复交接（2026-09-30 反馈轮）

## 本轮范围与基线

从 main `795146a` 建立 `astra/target-stability-20260930`，先集成已桌面验收的 Recon 源码提交 `28e4655`（本分支对应 `109f414`），再修目标资格。本轮没有改经济参数、军力上限、原生桥接、战争迷雾或侦察威胁缓冲。

已消费 `ASTRA_FEEDBACK_RECON_EXECUTION_2026-09-30.md` 及两局桌面证据；1x Recon 的自然 E4 正验收成立，5x Frontier 未创建不能升格成正验收。本轮不重复网页制作或上一轮基础 E2 资料整理。

## 原始 5x 报告确认的根因

原始 battle SHA256：`3d6ba9bd309e52eb55366509c46fedd780efcdb6658fd93e03b838028eb92cb3`。

目标 `lightSub #1026`：219 条 `INCOMPATIBLE`、235 条 `UNKNOWN` Guard；114 条指向该目标的进军意图全部发生在失联时，113 次切回该目标。其中 13 次仍有其他合法可见敌人。

例如原始 JSONL 第 1956 行的意图发生在 combat observation `628876 game ms`，沿用 `626246` 的最后观察；第 1959 行实际切换 `225 → 1026`。随后第 1992 行 `1026 → 774`，第 2016 行又 `774 → 1026`。这里是绝对游戏时钟，不是 Battle 阶段已运行时长。

Guard 已在候选筛选层拒绝当前明确不兼容目标，并非只在 POST 攻击时拦截。真正缺口是：进入迷雾后 Guard 正确变成 UNKNOWN，上层却把它当作重新取得目标资格；原有距离评分只给 UNKNOWN 加 120，无法保住当前可见目标。已有 no-progress 冷却只依据持续可见、接近、无伤害的观察，失联会重置计时，不能解决这条链。无需调大 cooldown。

## 新的行为合同

- 每次合法 combat observation 都处理拒绝证据，先于生产/建造消耗本次命令机会。只对非空可分配编队、合法当前域、全部攻击者明确不兼容建立抑制；暂时没有主力不产生拒绝证据。
- 抑制是当前控制器会话内的策略记忆，携带 session/player、目标 ID、观察时钟、域来源、目录 SHA 和各攻击者判断。它不覆盖原生 enemy memory，也不把未见目标的当前状态改成 INCOMPATIBLE。
- 失联、过了任意时间、同类增援、目录暂时 UNKNOWN、出现未知兵种，均不能解除。重新看见相同不兼容状态只更新证据。
- 解除必须同时满足：**更新的合法可见目标观察 + Guard 重新得到 COMPATIBLE 子集**。目标浮出水面，或我方新增已知兼容兵种，都可在这次新观察中恢复资格。只有自身增援、目标仍失联时不会恢复。这是有意保守的边界；Recon 可以继续获取信息。
- 抑制同时挡住战术候选和旧址 search objective；已经建立的旧址追击会被取消。不会把已拒绝目标留在另一个上层目标入口。
- 当前合法可见候选优先于 remembered 候选，原距离、建筑和防御评分仍在各自层内使用。从未有过明确拒绝的 UNKNOWN 保留合法 fallback；混编仍只分配兼容子集。
- `target_suppression_started / released` 记录状态转换，`target_selection_suppressed` 记录首次实际挡住选择；`target_guard` 继续如实报告 UNKNOWN。summary 增加抑制计数。

ownership 小修：`CommandArbiter.release()` 返回是否真正移除了租约，BattleClient 仅在返回 true 时记录 release。重复清理仍幂等，控制语义不变。

## 验证与证据口径

`tools/replay_target_policy.py` 校验原始报告 SHA，按原顺序把 own state / combat observation 送给真实 JVM BattleClient。生产菜单与 Scout planner 关闭，视野与位置固定，发出的命令不改变后续输入。因此这是 **E2 开环决策对照，输入来自 E4；不是原生重放、反事实轨迹或新的胜负证据**。输出报告中的终止标记来自测试夹具。

| 指标 | 桌面原局 | 原 Recon 候选的决策重放 | 修复候选的决策重放 |
| --- | ---: | ---: | ---: |
| 向 #1026 下达目标进军意图 | 114 | 106 | 0 |
| 切回 #1026 | 113 | 104 | 0 |
| #1026 的 UNKNOWN Guard | 235 | 213 | 214 |

原局与重放计数不同是因为重放隔离了生产/Recon 调度。有效比较是两列使用相同固定输入的决策重放；不能据此推算减少损失或胜率。

新增 9 个目标资格转换测试：旧候选 7 例失败，修复后全部通过；加原 6 例 Target Compatibility 共 15/15。7 例 Recon recheck 回归增加 acquire/release 配对断言，全通过；ExecutionContractHarness 增加真实释放与重复释放判断。

完整矩阵已分段完成：**20 个 Java Harness、15 个 Python Suite，273 个 Python 用例中 271 PASS / 2 Windows 专用用例 SKIP，剩余失败 0**；ExecutionContractHarness 为 35 checks。完整脚本先完成 18 Java / 12 Python，再在原有 Linux 差异处停止；修复对应 Python 运行器问题后，重跑该套件及其两个 A/B 消费者，并补完末尾两个 Java Harness。不是声称某次完整脚本返回了 0。原始失败、恢复执行及逐项日志均保留。

发现的两项属于 main 原有代码：POSIX 允许替换已打开文件，Windows 文件共享锁测试不能直接套用；非 Windows 身份查询原来错误返回空集合，使仍活着的无关进程被记作 GONE。此次仅吸收先前审计 `ccf90cea` 中对应的运行器/测试修复：不支持的身份查询返回 None，reap 明确 FAIL 并保留 claims；Windows 原测试保留，加跨平台拒绝注入测试。Linux 没有新增杀进程能力，Windows 路径未改。详细矩阵见 `evidence/target-stability-2026-09-30/regression-summary.json`。

## 新原生 5x 对局

正式有效 run：`run-20260929T165059-dc097a`，Big Island (2p)、难度 1、请求 5x、正常 LOS fog、墙钟轮询 500 ms。实际引擎约 4.8 游戏秒/墙钟秒；Battle 完整达到 **1801.024 游戏秒、PARTIAL / ONGOING**，三份报告为 economy PASS / development PASS / battle PARTIAL，完整性 `issues=[]`。运行器将未决胜负的预算到期标作 episode FAIL，不能改写为胜利或整局 PASS。

- 383 条命令、545 次己方观察、240 条进军命令 / 239 条确认、61 个新战斗单位、39 次己方损失；2 次原生 command rejection，未发生控制器或引擎崩溃。
- `lightSub #2098 / #2150 / #2325` 自然触发三次明确拒绝并建立抑制。之后分别有 12 / 8 / 8 次失联记忆观察，Guard 仍分别记录 9 / 6 / 6 次 UNKNOWN；抑制期间指向这三个目标的进军意图 **0**，审计违规 **0**。这补充了真实引擎中的目标抑制行为证据；恢复兼容后的解除分支仍以 E2 测试为证。
- ownership 16 acquire / 16 release，无未配对释放或遗留租约。
- 7 个 Frontier 任务，5 个 `recon_frontier_satisfied`、2 blocked、0 strict refreshed；8 次 Recon move 为 2 次活动命令观察 + 6 次采样间完成证据。合法团队视野满足没有被冒充严格刷新因果。
- 原生 battle SHA256：`66dc82c31faf3bb814bdfbcf1c49bdef58afb43b7ffe2e78973df5377b4166ad`。运行器未证明 seed 可复现；此单局不证明性能或胜率提升，也不替代用户桌面验收。

更早的 100 ms headless 自动轮询尝试 `run-20260929T164302-0158ee` 触发**现有 64 MiB 报告提交上限**，没有完整 Battle 报告，按失败保留；没有调高上限。正式样本使用 `_JAVA_OPTIONS=-Drwagent.pollMs=500` 覆盖 runner 自动值，与桌面墙钟轮询一致。这是运行设置，不是策略参数调整。raw-evidence.zip 同时保存失败尝试和有效样本。

## 交付候选身份

- JAR SHA256：`165bd3b2207f96cee41a2dc3c8e25c36ce3ae799dd46a268304fe8dd65acf245`。
- contentDigest：`cfaeeab48d1b7e3d2eb238a87f3aeb92de6a1be6e0661fc4ff839ca99a9f712e`，按 `tools/run_headless.py` 的全部非 manifest 条目算法。
- 交付 JAR 就是两次原生尝试所用的字节；完整回归的重建 JAR 有不同 ZIP 时间戳，其全部非 manifest 条目与交付件相同。源码和目录身份见 `candidate-manifest.json`。

## 构建、复现与原生引擎

```bash
python tools/prepare_headless_engine.py --out .engine/rw115
bash agent/test.sh .engine/rw115/game-lib.jar .engine/rw115/libs
python tools/replay_target_policy.py --jar agent/dist/rw-agent-bootstrap.jar --out agent/build/target-replay
_JAVA_OPTIONS=-Drwagent.pollMs=500 python tools/run_headless.py --game-dir .engine/rw115 --agent-jar agent/dist/rw-agent-bootstrap.jar --mode match --speed 5 --difficulty 1 --battle-seconds 1800 --timeout 600 --map 'maps/skirmish/[p2]Big Island (2p).tmx' --out headless-runs/target-stability
python tools/replay_target_policy.py --audit-report PATH_TO_BATTLE.jsonl --out agent/build/native-target-audit
```

本轮发现公开 headless ZIP 的条目名使用 Windows 反斜杠，原 prepare 脚本在 Linux 生成错误的单层文件名。已改成先规范化分隔符再解压，拒绝越界路径；ZIP 身份及 1209 个文件的大小/SHA 检查全部保留并通过。没有修改游戏字节；`.engine/` 加入忽略列表。

当前交付是 JDK 17 / Java 8 字节码的全源码构建，不是上一轮 overlay。冻结目录资源保持 LF，SHA 仍为 `263cdaaa3e923e8307c37f059e50bee529b8ecd7c3e215937ce2adf615a0e236`。相对相同编译器的集成基线，全量 JAR 只有 BattleClient 及 CommandArbiter 类变化；原生桥接/headless/知识资源逐字节不变。

## 判断与后续

两次 Frontier 威胁阻断和两次快速 SCOUT_LOST 不足以证明稳定的侦察系统性缺陷，本轮不调参数。Target Compatibility 仍只判断域与已知能力，不代表路可达、武器已开火或一定造成伤害。

本轮修的是有明确证据的失联目标重新吸走主力；其他可见目标之间的频繁切换、全局行军承诺和地图战略仍可能需要单独工作。不会把这次小修称为全部目标振荡问题已消失。

在这份候选的桌面复测确认后，下一项建议进入 PlayerContext / same-game 双玩家最小实验。目标资格记录已有会话和玩家证据，适合作为随后拆分控制器状态的一个明确边界；不建议同时叠加经济 cap 或侦察参数实验。

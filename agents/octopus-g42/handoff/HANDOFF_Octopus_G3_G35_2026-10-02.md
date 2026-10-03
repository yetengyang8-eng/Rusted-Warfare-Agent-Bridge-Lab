# 八爪鱼 G3 / G3.5 交接 — 2026-10-02

本轮从用户已验收的 G2 `cdb9252e` 开始，完成实际出令迁移：逐 actor generation、显式 Intent、统一 scheduler、有界 game-time burst 与批内 credits/slots；原工程师→两栖飞机→Dive/Fly/return 链接入同一身份和 Trace。当前停在 G3/G3.5，候选未部署。

## 当前身份

- 分支：`codex/octopus-g3-execution-20261002`。
- 起点：`cdb9252e19ffcfaceba7f0d74f6523b7b0ae606d`。
- 第一阶段：`5678998`，generation/Intent/scheduler 与敌情差分收紧。
- 第二阶段/最终测试源码：`95a917ef1157b7a7dcebcce50bf5fff4ff8bc80d`，BattleClient、G3.5、Trace、报告审计与测试接入。后续文档提交不改变这些测试输入。
- 候选：`RW-CANDIDATE-2026-10-02-OCTOPUS-G3-G35-v1 / VALIDATED_NOT_DEPLOYED`。
- 固定 JAR：`G:\deepseek 工作台\_validation\octopus-g3-20261002\fixed-candidate\rw-agent-bootstrap.jar`，394924 bytes，SHA256 `7b51bf4a34db9fbae860cf559ab9fa9eb94e02c97f2898182201a4287136703d`。
- contentDigest `ffcfea8260fd6617a6c66ec77b2dde1d12aa7bb9c027e397e31d2aa7129124d8`，137 个非 manifest 条目。性能测量的 focused JAR SHA `85ed0fb4…` 与最终 JAR 的完整非 manifest 内容逐字一致，整包 SHA 因 ZIP 元数据不同而不同；没有把两者冒充同一个文件。
- [候选 manifest](../deliveries/octopus-g3-2026-10-02/candidate-manifest.json)、[Execution/Intent 契约](../docs/OCTOPUS_G3_EXECUTION_INTENT.md)、[证据入口](../evidence/octopus-g3-2026-10-02/README.md)。

## 实际改变与证据

`CommandArbiter` 的 actor generation 在 claim/release/transfer/clear 上递增，release 后保留；旧 generation Intent 拒绝，G2 epoch 不借用作控制权。G1 witness 保留提案版本，不因后来 ABA 改写历史。

BattleClient 中央 `post()` 实际生成 Intent→scheduler→原 bridge。主循环拆掉 G3 模式的 else-if 抑制，普通生产不再首厂成功即 return，Strategy 多 worker 可在剩余预算内继续。批内钱即时扣减；后续 caller 使用 effective credits。已付款未见队列继续由原 Pending/Purchase/paidConstruction/ghost 合同占位。

运行时仍同步收取 native receipt，保持原 lane 遍历；同 actor 首次真实 native attempt 占住本批位置，明确拒绝也消耗该尝试。`dispatchBatch()` 的稳定优先级排序有单测，但当前 BattleClient 没有全局延后收集/排序、异步并发或抢占。多个独立 actor 在同一个合法观察顺序执行，这才是本轮“并行推进”的实际范围。

预算初始 1，每 1000 game ms 补 1，默认 burst 4，长 gap 只饱和到 4；重复 game time 不退款。原生 session/己方 actor/menu/action/价格/地形/兼容性最终 guards 未改。accepted/queued 不证明执行、交付、击杀或生产血缘；transport unknown 保守记 commitment，并沿既有错误路径退出。

定向 review 发现原生移动工程师 `productionQueue=-1` 与旧夹具 0 的差异。本轮修复使用已有 `paidConstruction` 施工容量证据，不把 -1 伪装为 factory queue empty；HTTP 原生形状 provider 链已验证。矿升级使用原 Purchase 合同；extractor 原生可具有 factory 队列，初次 fixture mutation 错改成 -1 的失败已保留并修正夹具，未改 MineInvestmentPolicy。

持续相同合法敌情只刷新 Source/LastObservation，真实关键事实变化才发 ENEMY_UPDATED。WorldStateHarness 从 73 增至 144 checks，包含 40 次重复可见、不产生转换洪水及原 freshness/reset/gap/UNKNOWN 边界。

`CapabilityTask` 集体目标内每架 jet 独立 Mode。32-check lifecycle 验证湿/干两架成员分别 Dive/接近水面、请求/后来模式 witness、Fly/return、合法站点清空和旧 ghost，不存在 group.mode=DIVE。WorldState 只提供合法来源和 UNKNOWN 说明；策略仍使用原响应，不以新事实重新选战争目标。

`SearchAreaNeed/SearchTask` 已有合法触发、coverage/lastSearched/confidence aging、稳定分区、合法可见发现→TargetTask 契约及测试，**未激活搜索行为**。缺正式 coverage/trigger adapter 与原版水下发现规则；是否必须 Dive 才能发现水下敌人 `NEEDS_EVIDENCE`。没有新增 Specialist Pool 血缘匹配、General/FREE/JOINING/ThreatTask/HOT/COLD 或 G4。

## 验证结果及首遍环境失败

最终有效 Windows 验证 **63/63 项通过，33 Java harness，27 Python 套件/463 tests，Python skip 0；校正后的验证汇总 failed steps: 0**。本轮只跑一次完整矩阵；首遍原始结果为 **62/63、exit 1**，唯一失败是隔离复制漏了 5 份历史 `docs/acceptance-*` 夹具。补齐原文件并核对哈希后，仅重跑 `test_reports` **16/16 PASS、exit 0**，没有改源码，没有抹掉首遍日志，也没有把原始 exit 1 改写为 exit 0。证据 JSON 分列 raw full 与环境补验。

历史 Python suites 显式 `rwagent.g3Execution=false` 验证旧路径；G1 四路径与 G2 五正常/四 guard 三方对照继续使用冻结旧 JAR。新 G3 HTTP suite 显式 true，完整矩阵中 **11/11 PASS**，覆盖跳时、多厂、credits、military/producer slots、paid-before-visible，以及既有 Recon、LocalCrisis、普通 reserve、provider/funding/Dive 和矿升级原断言。不能把所有历史 suites 说成默认 G3 已全部实测。

关键 Java focused/full checks：ExecutionScheduler 87；CapabilityLifecycle 32；WorldState 144；G1Trace 46；StrategyContract 169；EngineerProvider 61。原 ReportCommitHarness 的 Windows/POSIX displacement 子项平台跳过保留，不能宣称所有平台分支执行。

142 份源码/测试/资源/tools/runner 在测试前、隔离输入及结束后逐字一致；5 份补齐的历史夹具一致。测试 JAR 期间未变。7 份保护文件与 888 份只读 assets/libs 输入哈希未变。未写原版设置、存档/回放或安装 JAR；未启停用户桌面游戏。Java 原生 harness 在隔离 cwd 使用既有引擎 JAR，所有新输出留在隔离目录。

## 吞吐和导出成本

合法合成 HTTP 夹具：12 家工厂，同一原生 heavy 动作/800 credits，不改变生产偏好。5 种配置各 3 次、共 15 次交错新 JVM 运行：

| 配置 | 接受命令总数 | 截至 7000 game ms | JVM wall 中位数 | report bytes 中位数 |
|---|---:|---:|---:|---:|
| 已验收 G2 | 7 | 2 | 0.8380 s | 808973 |
| 当前 JAR 旧 gate | 7 | 2 | 0.8064 s | 810041 |
| G3，G1/G2 导出开启 | 12 | 7 | 0.8700 s | 1093272 |
| G3，仅 G1 导出 | 12 | 7 | 0.8278 s | 584259 |
| G3，Trace/附加诊断关闭 | 12 | 7 | 0.8535 s | 53844 |

三种 G3 native 选择逐字相同，批次为 3500:3、7000:4、67000:4、69500:1。长 gap 具备足够候选并打满 4，但未超过 burst。关闭附加导出字节下降约 **95.1%**。

| G3 导出设置 | sampling 总计/每次，51 calls | world 总计/每次，43 calls | execution 总计/每次，12 calls |
|---|---:|---:|---:|
| G1/G2 开启 | 122.184 / 2.396 ms | 42.657 / 0.992 ms | 31.282 / 2.607 ms |
| 仅 G1 | 120.606 / 2.365 ms | 26.798 / 0.623 ms | 35.703 / 2.975 ms |
| Trace/附加诊断关闭 | 108.340 / 2.124 ms | 31.612 / 0.735 ms | 21.108 / 1.759 ms |

这些是 elapsed 范围，含 HTTP/IO，可重叠，不能相加或叫 CPU。3 次小样本不能推导显著性、墙钟性能因果或自然局战绩。G3 必需 WorldState 在导出关闭时仍计算。

## 接手边界与使用

- 后续从仓库当前 G3/G3.5 交付开始；冻结 9/30 基线仅核血统，不授权回滚。测试源码是 `95a917e`，文档提交可以更晚。
- `-Drwagent.g3Execution=false` 可切回旧执行 gate/caller 对照。尽量关闭导出用 `-Drwagent.g1Trace=false -Drwagent.g2WorldState=false -Drwagent.additionalDiagnostics=false`；仍计算执行所需事实。
- 未部署、未 push、未跑 G3 自然局或桌面验收。固定候选在本地隔离目录；此次不使用旧安装指令覆盖桌面。
- 建议下一独立任务先做受控隔离 native 两栖/多厂验收，检查实际高倍速预算、队列延迟和多 jet 独立 mode；记录日志成本。SearchArea 激活前补 coverage adapter/原版发现证据。G4 必须有新授权，本交接不是自动施工指令。
- 本轮新建子智能体均为 `gpt-6.1-sol / high`；复用前序已落盘 handoff/契约，未重做全仓 G0/G2 审计，各子任务仅 focused/review，根任务统一完整回归。

完整 raw：`G:\deepseek 工作台\_validation\octopus-g3-20261002\final-full\`；15 次测量：同根 `events\cost-final\`；原失败索引在仓库 evidence。固定候选/原始包 SHA、测试 mode 和缺证据边界应随 GPT/DeepSeek 的后续评审传递。

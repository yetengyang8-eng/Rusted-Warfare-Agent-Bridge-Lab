# HANDOFF：固定条件 A/B 对比 v0（Codex，2026-09-27）

**状态：实现与本机验收完成，停在独立验收前。** 本轮依据 `Codex_AB_v0_现在可以开始_2026-09-27.md`；没有修改 Java 策略语义、引擎 seed、P0/Diagnostic Lab 或冻结件，也没有按这四局结果调参。

## 已实现

1. `游戏环境\Rusted-Warfare-1.15-Agent-0.07\tools\run_headless.py` 新增 `--profiles JSON --profile-order AB|BA`，仅接受 `--mode match --episodes 2 --parallel-pair`。profile 格式为 `schemaVersion=1`、恰好 A/B 两臂；v0 JVM 白名单只有现有 `rwagent.mobileUnitHardCap`，整数 24..80，A/B 名称和值必须不同。拒绝重复键、未知字段、额外 JVM 属性和非整数值。示例 `tools\profiles\cap32_vs40.json`。无 profile 入口保持旧格式与行为。
2. 每个 episode/batch 写 A/B 位置、profile 名、规范化 JSON 与 SHA256 digest、实际客户端 JVM `-D` 参数、候选 contentDigest/whole-file SHA、game-lib SHA、地图、难度、请求倍速、现实超时、battle 游戏秒预算和迷雾期望。每个 match 的原始 `battle_config` 必须回显本臂 cap 与秒数，并且恰好产生 battle/development/economy 各一份报告。runtime 再核地图、难度、倍速、两项迷雾、实际 seed 与 `seedReproducibilityVerified=false`。profile 参数只进入客户端 JVM，未传给引擎。
3. 新增只读 `tools\aggregate_ab.py RUN1 RUN2 [more...] --out SUMMARY.json`。至少一批 AB、一批 BA；跨批核候选 contentDigest、game-lib、地图/难度/倍速/时限/迷雾与 profile；逐局核 parallel proof、暂存 JAR、runtime、raw battle JSONL 的 SHA/session/JAR 来源、`battle_config` 和经济支出账本。结果按 profile 报尝试数、原生完成率/胜负、运行器通过数、指标 n/均值/中位数/总体标准差/分位数、每局原始路径与缺失原因。仅从 battle 报告取指标；PARTIAL 单列，不把 FAIL/无报告算输，不输出综合评分或自动赢家。
4. 独立审计发现并修正 `--reap` 的几个身份边界：进程/端口查询不可得时不杀且不报 PASS；杀后复核不可得时报 FAIL；拒绝外批登记簿与不属本批 episode 的工作目录；端口参数按完整 token 匹配，避免 `1234` 误认 `12345`；客户端登记写失败仍 kill/wait。battle 原始终局与运行器是否通过分开记录。安全修正没有扩大可回收的对象范围。
5. 更新 `docs\HEADLESS_CN.md`、`developer\test-win.ps1` / `test.sh`、`CURRENT_STATE.md` 与证据索引。源目录及 `P1F-冒烟环境` 的运行器、聚合器和示例 profile SHA 对应相同。

## 测试与真实运行

- **改代码前基线**：完整 `developer\test-win.ps1`，Java harness 17 + Python 10 套，failed steps 0。
- **改动后完整回归**：游戏自带 JDK 13 跑同一入口，Java harness **17** + Python **11** 套，failed steps **0**；`test_battle_client` 78、`test_headless_parallel` **22**、`test_ab_aggregate` **14** 项。项目日志在 `developer\build\logs`；Codex 完整控制台记录为 `C:\Users\Administrator\Documents\Codex\2026-09-27\g-deepseek\work\ab-final-regression-2026-09-27.log`。
- **JDK 17 交付与谱系**：重新编译 Java 8 字节码、打包 dist 并复制到 `P1F-冒烟环境`。现 dist/安装件 whole-file SHA256 为 `efd51831d20918ab23814c2671bae28d3af73cd7580c172a9cbebd78e036ebfd`；contentDigest 保持 `6fec7a0cc1920e3ede94ac408a88788ed7f604a89d6d701cecf6448e57015294`。`_analysis/sha_lineage.py` + `assemble_lineage.py` 核出 `aVsInstalled=[]`、`aVsDist=[]`。环境根冻结 JAR `0681b4f7ef632a9c9372fff418fec591f240894fd99373ff6c7ca3b72d4a9eb1` 与 game-lib `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9` 均未变。
- **真实 A/B**：在同一候选与上述固定条件下运行 `Small_Island (2p)`、难度 0、请求倍速 4、现实超时 1200 秒、battle 预算 900 游戏秒。AB 批 `G:\deepseek 工作台\_analysis\ab-v0-runs\run-20260927T100638-0e98c0`：ep1 cap32、ep2 cap40；BA 批 `...\run-20260927T100850-0811b5`：ep1 cap40、ep2 cap32。两批各自 `parallelProof=PASS`，四局均 `PASS/VICTORY`，四份 battle 原始 JSONL 经聚合器复核为 VALID。实际 seed 分别为 `919945426 / 450256682 / 783188450 / 534485354`，无 seed 可复现性承诺。
- **描述性汇总**：A(cap32) 与 B(cap40) 各尝试 2 局、原生完成 2 局、胜 2 局。A/B 的 battle 游戏秒均值为 **316.952 / 269.240**，新观察到的武装移动单位均值 **16 / 14**，观察到的己损均值 **14.5 / 11**，已接受订单支出均值 **17450 / 15500**。标准差、分位数、矿/厂与经济事件、逐局路径及完整证据见 `evidence\ab_comparison_v0.json`。**n=2/臂，不能据此说哪个 profile 更好。**
- **无 profile 兼容**：安装位置以最终 JAR 再跑双实例 smoke，目录 `G:\deepseek 工作台\_analysis\ab-v0-no-profile-smoke\run-20260927T102323-5f1428`，2/2 PASS、parallel proof PASS，batch 无 A/B 字段。已结束目录连跑两次 `--reap`，均 PASS、killed=0。

## 证据位置和复核

- `助手交接\evidence\ab_comparison_v0.txt`：固定条件、四局原始 battle 报告文件名/完整 SHA、方法和限制。
- `助手交接\evidence\ab_comparison_v0.json`：只读聚合结果，SHA256 `9e69c122ac429e489dffe49389ccc5bc2962f57457b3f948e08d02be71c98253`。
- `助手交接\evidence\ab_comparison_v0_runs.zip`：两批的 batch/proof/episode/runtime/进程身份/日志和 12 份原始 JSONL；内含 50 文件 SHA 清单 `MANIFEST.json`，ZIP CRC 与清单均验证；ZIP SHA256 `58ad244968717d94b4051b9c0f3fd8b87ebc5fadbd99a2468e7c01818f66a986`。原始完整 run 目录保留在 `G:\deepseek 工作台\_analysis\ab-v0-runs`，聚合器可直接对它们重跑。
- `助手交接\evidence\ab_comparison_v0_sources.zip`：本轮 Python 运行器/聚合器、profile、两份测试、回归入口及文档共 8 个文件，内附 SHA 清单；ZIP SHA256 `ab38ba0b6980dba359869123f8c93db87a796810253b8fa0018b6ca8e8fd9f61`。
- `助手交接\evidence\candidate_sha_lineage.json`、两份一致的 `CURRENT_STATE.md` 与 `EVIDENCE_INDEX.md` 记录当前交付身份。

## 未验证与下一步

- **未验证**：seed 的写入/严格复现、跨进程完全确定性、桌面图形模式等价性；每组只有两局，无法估计稳定胜率或因果优劣。新 A/B 只用于重复独立原生对局，不能称为同局 self-play。
- **保留风险**：`--reap` 依赖 Windows 进程/端口查询；查询失败会 FAIL 且不杀。直接强杀编排器恰在 Popen 后、登记前，仍可能漏登子进程；`reap-report.json` 的 `skipped` 要单独检查，PASS 不对被跳过的外部进程作保证。进程身份查询与终止之间仍有短暂 TOCTOU 窗口。
- **请 DeepSeek/ChatGPT 独立验收**：从两批原始目录/ZIP 复算候选与四局 SHA/session、AB/BA 位置、battle_config 实际 cap、proof 中独立端口/会话/帧推进；核对聚合器能拒绝不同候选/条件或损坏报告，再核当前 dist/安装件/冻结件谱系。验收后若想比较策略效果，应先决定样本数、地图与统计口径；本轮不自动扩大实例数、不调参、不改策略语义。

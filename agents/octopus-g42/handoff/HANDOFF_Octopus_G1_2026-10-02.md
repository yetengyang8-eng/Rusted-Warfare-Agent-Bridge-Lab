# 八爪鱼 G1 交接

G1 已完成，限定为 GameClock 与 Trace；现有策略不迁移。后续从本分支读取最新状态，不重新实现 G1，也不把本轮认作 G2/G3 已交付。

## 身份

- 分支：`codex/octopus-g1-clock-trace-20261002`。
- 起点：`be7ba9485c9a589483d9a586c4a35a975e061a5b`；实施提交：`5cc3e3fa3836f1f82b0db069f86bdbea7fef185c`。交接与证据由后续文档提交登记。
- 工程候选：`RW-CANDIDATE-2026-10-02-OCTOPUS-G1-v1`。
- 完整矩阵实际 JAR SHA256：`ab5b7fd1fe97a4e4ae0b8cb59cfd7d189c492e8a8e99a8400ce1d3eb93e7287a`，336,094 bytes。
- contentDigest：`43da2c441785314a2089f5fab01715103b5d2e82af252fd49daf98e65e1eec13`。
- 原版 engine SHA256：`8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`，未修改。
- 本地新 JAR：`G:\deepseek 工作台\_validation\octopus-g1-20261002\final-source\agent\dist\rw-agent-bootstrap.jar`。未安装到游戏目录；桌面仍为上一 a392…候选。

## 交付与未做

`GameClock` 保存独立 endpoint 的 source time/frame/session/player、请求/接收墙钟、ObservationId、上次读取区间与 discontinuity。缺源时间为 null，不插值；detected time 明确为最新 `/state` 锚点，不是接收时刻。

`G1Trace` 提供 scoped EventId/IntentId/CommandId；IntentId 只是直接提案的 Trace 身份。旧 JSONL 字段及原 native 请求保留，新增顶层 `trace` 和 `g1_` sidecar。owner generation 仍为 null / NOT_IMPLEMENTED_G3。所有真实发生时刻仍 NOT_PROVEN_BY_G1。

四条代表路径：Recon lease 与原 MoveExecution witness；LocalCrisis 净状态与幸存受控响应者的后帧 order/返回位置；普通 Pending 的付款命令、忙队列及忙后空；Strategy Need/Worker/Purchase 净变化、provider construct commitment、ready 候选歧义及 mode 原生字段。队列空不证明产品 ready；ready 匹配不证明严格生产血缘；旧 DIVE 标签不证明当前原生潜水。

未做 WorldState、通用派生事件总线、FREE/General、generation、Intent Scheduler、累积带宽、批内 credits/slots、新 Capability route 或搜索。Battle 主循环、决策/命令门禁、lane 优先级、150s/80%/hard cap 和原生校验保留；14 份关键机制源码与 be7ba94 一致。

详见 [Trace 格式与边界](../docs/OCTOPUS_G1_TRACE.md)和[精选真实 Trace 链](../evidence/octopus-g1-2026-10-02/trace-chain-examples.json)。

## 本轮新运行证据

| 项目 | 结果与范围 |
| --- | --- |
| 不可变 be7ba94 Windows 基线 | 首轮55/56；唯一失败为隔离 cwd 缺原版 assets。补齐隔离资源后原 StrategyNativeHarness 51 checks 通过，有效56/56；首次失败保留。 |
| G1Trace / EngineerProvider / Strategy focused Java | 41 / 61 / 169 checks PASS。工程师匹配歧义与 optional sink 故障不改变原命令/summary有合同。 |
| 最终 focused HTTP | 四个测试 PASS；Trace on/off/pre-G1 三方命令和全部旧日志相等。 |
| 最终完整 Windows 矩阵 | **58/58 steps，30 Java runs，25 Python suites，439 tests，failed steps: 0，exit 0**。矩阵中再用精确 ab5b… JAR 完成三方比较。 |
| 原生 fixture | NativeMorph 102、StrategyNative 51 checks PASS；不是自然对局。 |
| sidecar / 源码冻结 | sidecar failure=0；106 source/test/runner 文件前后哈希一致、隔离构建与仓库代码一致。 |
| 自然桌面/自主 headless 战局 | **NOT_RUN**；没有继承旧自然局、胜利或效果证明。 |

Windows 原版捆绑 Java13 与 bundled Python 固定，标准入口 `agent/test-win.ps1 GAME_JAR LIBS` 在完整隔离源码 cwd 运行；原版 assets 复制到该 cwd，仅用于原生 fixture。`JAVA_HOME` 与 PATH 同步，JAVA_TOOL_OPTIONS固定UTF-8。G1套件启用 `RW_G1_BASELINE_JAR`、`RW_G1_EVIDENCE_DIR` 留存三方证据。

Python 用例无失败/跳过；ReportCommitHarness 保留原 Windows POSIX open-handle displacement simulation 的平台跳过，其余72MiB提交合同照常运行。

完整日志 SHA256：`a12cce9c14dc27a864c03ba5b0c23cca21518a952f06f24c6994b9c35830788f`。日志、结构化矩阵、源码哈希、原始失败和精选链见 [证据索引](../evidence/octopus-g1-2026-10-02/README.md)。全部12变体报告位于本地 `_validation/octopus-g1-20261002/final-full-four-path/`，每份hash已登记。

此前 focused 整包 SHA 未在 full build 覆盖前记录，不将 ab5b…追溯归给该次运行；保留的104个 class与最终104个 class逐一一致。最终完整矩阵已重新覆盖精确 JAR；身份限制文件保留。

## 等价性、成本与回退

四条确定性输入下，新旧命令顺序/参数/owner/gameTime和旧 event/data/outcome相同，仅归一化UUID与墙钟测速/请求耗时。没有观察到受测策略漂移。新JSON/flush消耗墙钟，尚不能证明自然局采样间隔完全相同。四条 fixture 导出体积约为 off 的2.25～3.01倍；不把体积比写成自然局性能结论。

Trace开关 `-Drwagent.g1Trace=false` 可关闭附加输出，策略不变；Strategy的可选快照分配仍发生，不声称零开销。完整撤销实施可在另一个工作分支 revert `5cc3e3f`；不要重置冻结历史件或覆盖用户修改。本轮未部署，因此不需要在桌面游戏执行回退。

原版引擎、历史交付件、游戏设置、存档、回放未写入，未启停用户桌面游戏。测试进程、资源复制、构建及日志均在独立目录。

## 唯一建议下一断点 G2

输入接口：**经过现有活动局/session/合法性校验的响应 Map + 不可变 GameClock.Observation**。Observation的存在不表示已通过控制器全部下游校验；inputObservationIds是读取上下文，不是可直接还原WorldState的程序依赖图。

G2建立只读WorldState和合法快照差分，分别定义authority/freshness、session reset/frame rollback/长gap、重复/乱序、实体范围和UNKNOWN。先双写或比对，旧策略继续读原响应；不要制造跨端点同帧世界，不把失联当死亡、queue空当ready，也不消费假generation。G3门禁/带宽、批内资金和调度继续后置；G3.5再纵向试点现有工程师/两栖链。

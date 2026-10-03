# 给 DeepSeek / GPTsol：A/B campaign v0 已施工并完成本机验收（Codex，2026-09-27）

**状态：本机 PASS，待你们独立复核。** 我先读了 `HANDOFF_FOR_CODEX.md` 与最新的 `对话47.txt` / Sprint 安排，并在改代码前跑完整回归确认基线绿。实现范围是受控、可续跑的重复 AB/BA 工程编排，没有改 Java 策略、经济决策、seed、冻结件或实例规模，也没有用小样本给 cap32/cap40 判优。

## 做了什么

1. `游戏环境/Rusted-Warfare-1.15-Agent-0.07/tools/run_ab_campaign.py` 新入口：`--pairs N` 自动 AB/BA 交替，每次只运行一批现有的双实例 match；`campaign.json` 原子落盘，绑定 profile 原文件、A/B digest、候选 JAR whole SHA/contentDigest、game-lib、实验固定条件、资源与脚本身份。OS 文件锁阻止同目录并发启动，重跑只填缺口。
2. 完整、有效的 `PARTIAL / ONGOING` 视作已经取得一份有效但右删失的 battle 报告；runner 退出 1 在这种情况下不等于编排失败。缺 raw、SHA/身份不符、异常 runner 退出或清理错误会 `BLOCKED` 并保留现场。跨批聚合强制检查 AB+BA、每臂 attempted/valid/invalid 与指标 n，不输出自动胜者。
3. 中断恢复复用 `live-claims.json` 和 `--reap`；campaign 另外检查登记进程确实消失，避免旧 reaper 对身份跳过对象返回 PASS 时直接开下一批。完整但无效的旧 batch 需人工审查，不自动遮盖；`--max-attempts` 只用于不完整批次。磁盘额度可带审计记录调整，固定实验身份不可静默改变。
4. 先将原始 `game-lib.jar` 与 `libs/assets/res` 复制一次到 campaign 自身 `resource-cache`；Windows junction 只指向该缓存，避免把原始资源开放为可写链接。原件和缓存在批次前后复核 SHA；`staging.json` 记录并验收每局的实际暂存方法。每批按最坏复制量检查空间上限和剩余空间；不自动删除原始报告。
5. 聚合器单批内部检查增加 `runtime.json` 原件与嵌入值、引擎 PID/端口/工作目录/命令端口的交叉校验；正式 CLI 的至少两批 AB/BA 要求保持不变。测试补了进程记录互换、runtime 篡改、预算续调、PARTIAL 分类、清理跳过、缓存写入隔离与缺口收养。

## 实测与交付身份

- 改前回归 **17 Java + 11 Python，失败 0**；改后 **17 Java + 12 Python，失败 0**。新 `test_ab_campaign` 14 例，扩展后的 `test_ab_aggregate` 17 例。控制台记录在本次 Codex 任务 `work/campaign-*-regression-2026-09-27.log`。
- JDK 17 交付 JAR 已复制到 `P1F-冒烟环境`：dist/安装件 whole SHA `0f43f6974f8730f0bff291bc039b5cfeb812550657b1c09bddb97f9967aca25a`；contentDigest `6fec7a0cc1920e3ede94ac408a88788ed7f604a89d6d701cecf6448e57015294`，`aVsInstalled=[]`、`aVsDist=[]`。冻结 Agent JAR `0681b4f7…`、game-lib `8a550a37…` 未动。新 Python 工具也已同步到安装位置且逐文件 SHA 一致。
- 从**安装位置的工具和 JAR**运行 `G:\deepseek 工作台\_analysis\ab-campaign-v0-final`：AB/BA 各一批、每臂 attempted 2 / valid 2 / partial 2 / invalid 0 / nativeCompleted 0 / 指标 n 0，parallel proof 两批 PASS。重跑同命令仍各启动 1 次，聚合 SHA 不变。另从原位置 run 独立重聚合，JSON 逐字段相同；CLI 的 CRLF 与 campaign 原件的 LF 规范成一致后，字节和 SHA 也相同。
- 故障注入 `G:\deepseek 工作台\_analysis\ab-campaign-v0-recovery`：Codex 操作时核验并只结束该实验的 campaign 与 runner，两条登记引擎仍活且旧批不完整；续跑 `--reap` 按身份杀 2、旧端口释放，保留旧 run 后补第一槽，再运行 BA。最终 `status=COMPLETE`，槽位启动次数 `2,1`；再重跑不增加。归档原件能复核旧批、登记引擎及回收结果；手工结束两个编排进程的命令和桌面进程快照未单独归档。详情和完整 SHA 见 `助手交接/evidence/ab_campaign_v0.txt`。
- Windows 缓存版本安装验收的实占约 **52.5 MB**（单份资源缓存 36.7 MB、两批文件 15.8 MB，遍历不跟随 junction）；缓存与原始资源指纹一致。这个数字仅是本机该地图/该两批的观测。

## 请独立核查的重点

1. 从**原位置**两批 run 的 `batch.json`、`parallel-proof.json`、`episode.json`、`staging.json`、`runtime.json`、`engine-process.json`、raw `battle-*.jsonl` 重推身份、AB/BA 实际位置、客户端 cap 与游戏内 `battle_config`，再运行聚合器；请同时看 valid/invalid/n。`ab_campaign_v0_runs.zip` 含最终与恢复现场的审查快照及 SHA 清单，但解压路径不是可直接重聚合的 run 路径。
2. 检查中断恢复旧 run 的 `reap-report.json`、`live-claims.json` 和新 run；是否确实只回收已登记本批的两条引擎、旧端口释放、旧证据保留、无重复有效样本。回收器本身仍依赖 Windows PID/命令行/端口查询；查询不可得或身份不明时 campaign 应阻塞。
3. 核对资源缓存和原始资源指纹、实际 junction 目标、磁盘预算的保守性，以及 JAR/工具/冻结件谱系。聚合器会拒绝绝对路径身份不符；DeepSeek 旧变异夹具的 SHA 失配还包含**改写 raw 路径**，不能表述成“字节不变的复制也改变 SHA”。

证据入口：`助手交接/evidence/ab_campaign_v0.txt`、`ab_campaign_v0.json`、`ab_campaign_v0_runs.zip`、`ab_campaign_v0_sources.zip`；可复跑入口 `tools/run_ab_campaign.py`，说明 `docs/HEADLESS_CN.md`。**下一步涉及 N、地图、统计口径或任何策略语义，请先交接裁决。** 同局双 Agent/self-play、seed 写入、4 实例只可按原安排做有界只读侦察，本轮未实施。

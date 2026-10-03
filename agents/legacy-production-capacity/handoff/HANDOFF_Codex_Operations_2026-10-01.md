# Codex 运营与队伍调动交接 — 2026-10-01

本轮按用户明确授权，同时推进无建造者起步、多军队控制与经济冗余支出。默认源码仍是本仓库 `agent/`、`tools/`；从 `8109e11` / Specialist Lifecycle v1 继续，保留有界能力资金预留与专属单位生命周期。固定基线 `RW-BASELINE-2026-09-30-GS-v1` 未改写，新增候选为 **RW-CANDIDATE-2026-10-01-OPERATIONS-v1**。

## 身份与接手顺序

- 实现提交：`3d96d98fc05d87569145c09f9f60d3e4a9fd185a`。
- 最终 JAR：[固定交付](../deliveries/operations-2026-10-01/rw-agent-bootstrap.jar)，SHA256 `28668be7c49a2b5d8f7f326fbc3f365a2179d4c1b92a169959c58213ec44ecb7`。
- 内容谱系、冻结 game-lib 与知识资源见 [candidate-manifest.json](../evidence/operations-2026-10-01/candidate-manifest.json)；完整测试见 [validation-summary.json](../evidence/operations-2026-10-01/validation-summary.json)。
- 先读仓库 `AGENTS.md`、`project-state/BASELINE.md`、基线 manifest、`CURRENT_STATE.md`、`NEXT_STAGE_PLAN.md`，再运行只读核对。源码与 v1 的漂移是已完成增量；不能据此回滚。
- 本轮没有替换桌面安装。先前 Specialist Lifecycle 已被外部安装到 `游戏环境/P1F-Astra-SpecialistLifecycle-2026-10-01`，本轮只读确认其 JAR 和两场 raw。一个 522.465 游戏秒胜利，一个 2401.120 游戏秒预算到期；实际 mapPath/difficulty 不在 raw 中，保留 UNKNOWN。

## 已实现

### 无建造者起步

`MatchClient` 先运行 `BootstrapClient`，再重新 preflight，进入既有经济、发展和战斗。已有可用建造者不下生产命令；任一己方生产者已有 builder 队列时只等待；无 builder、无已有队列时最多接受一次正常原生生产。资金不足和忙队列有限等待，默认预算为 180 游戏秒 / 120 墙钟秒；网络、回放、session/team 变化和停滞停止。

原生路径沿用已审计的 `/economy/builder-production`、`/command/produce-builder`。常规完成证据为队列活动→空队列→新 ready 己方 builder；极快生产可用 accepted 命令＋新 ready 己方单位，明确未观察到队列周期。最终再次检查 ready builder 与身份；原选中单位丢失只在另一真实 ready builder 存在时重新选角色。观察匹配不证明工厂出生归属。

### 多军队局部控制

`LocalArmyDirector` 保存己方主力成员映射，至少两队才进入局部控制；单队保留旧流程。最多4队、每队48人、6人成队、少于3人释放；400距离成组，至少6人的远离聚集团在1000距离以上可分队。队伍交叉仍保留身份，小股增援归最近可用队伍。

每队独立保存目标、最后接受命令时间、前沿与进展。1400距离内的合法现有目标保持，失联局部记忆最多30游戏秒；各队分别探索自己的合法前沿。无剩余前沿时，可向当前可见远方合法接触推进，明确记录 `REMOTE_VISIBLE_CONTACT`。所有队伍先观察进展，再轮转下令；同目标的参与者合并用于原有无进展守卫，原生拒绝不会消耗该队 cooldown。

这是 default main owner 内部编组，不新增 arbiter lease。工程师、Recon 和其他任务真实 owner 继续优先；全部命令沿既有全局一游戏秒门槛、Target Guard、UNKNOWN、负证据和接近语义。参数是工程初值，尚未加入战力估价、护送、撤退路径或最佳阈值调参。

### 富余资金出口与升矿约束

`SurplusSpendingPolicy` 在已有军力槽内少量补 `heavyArtillery`，不提高军力上限。要求至少24普通可用主力、基地非紧急、当前可见 SURFACE 建筑、工厂空闲、原生菜单动作可负担，且付款后保留全部既有储备及两次普通补兵价。数量限制为 `min(6, armyTarget/12)`，同时计已观察成品和已付款承诺。

已付款重炮在队列清空而成品未被观察到的间隙仍占名额，队列未知/-1也保留该名额；普通生产、战略能力采购和支援施工共享这项容量保护，避免 soft target 或 hard cap 被抢占。只有新 ready 己方重炮可履约，一个 ID 只匹配一次；生产者损失或180游戏秒观察超时带原因释放。匹配表示可用角色，不能据此宣称生产归属。

升矿继续受原有威胁、回本、军力与储备约束。若军力缺口不超过 `max(2,ceil(target*0.1))`、收入高于生产消费、扣储备和真实矿升级价后仍有 `max(30秒模型收入, 两次原生普通补兵价)` 的缓冲，则推迟增加收入并记录原因。先读真实普通兵价；报价无法获取时保留旧保守路径，不擅自宣布饱和。消费和收入是既有模型，不能代替实战收益。

## 验证与原始失败

完整 Windows 回归 **47/47步通过**：23 Java runs、21 Python suites、342 Python tests、0 Python skipped、failed steps=0。最终报告解析器补做 **25/25** 契约及 **7/7** 控兵联动，合并矩阵为352个不同Python用例；没有把重跑次数相加成新增覆盖。策略169项、编队372项、经济42项合同通过，98个非manifest归档项与固定JAR相同。Windows文件锁真实执行；POSIX-only Java模拟按平台跳过，Linux本轮未运行。

两次普通Big Island原生局Battle均为VICTORY，修正运行器报告数量兼容后的复核整轮PASS。两次无builder夹具局都自动只生产1 builder，经济/发展PASS，Battle在1201.040/1201.104游戏秒预算到期PARTIAL/ONGOING。最新夹具局实际最多4队、106次局部命令、5笔原生报价3100重炮采购共15500、5个ready成品，末pending为0。该局矿富余拒绝和付款空窗未自然采到；前者有7场经济专项覆盖，后者有hard-slot时间线覆盖。4组16份报告运营审计与原全局/专属策略审计无违规，最终严格离线解析没有issues。

原运行器失败原样保留：旧三报告检查及旧单前沿解析都未适配新增报告/多队；修正后的离线结论另存。原始无builder地图只删除隔离副本中的己方初始builder，不能当作未修改Spain桌面验收。

保留的失败：Windows默认临时目录触发 JDK loopback 错误，短 TEMP/TMP 后通过；初次经济合流暴露首轮普通报价未知，已经在投资前读取原生报价；矿夹具动态 target 后期升至52，已把该单场固定真实 hard40并检查前提。第一次原生运行仍按旧三报告数量判定失败，即使 Battle 已胜利或正常预算到期；兼容扩展后复核结果单列，旧 batch 不回写。旧 Match 夹具末尾 NPE 来自不完整战斗数据，只用其“未生产 builder 就进入 Battle”顺序证据。

完整 Windows 回归使用隔离 `G:\deepseek 工作台\_validation\operations-20261001\source` 与短 `tmp`。冻结最终 JAR 和回归重建比较非 manifest 内容，归档目录项也计入；仅 whole SHA 不同不能等同源码不同。Linux 本轮未运行。

## 后续工作与复用

1. 用户桌面按真实原始出生位复核新候选，保存 mapPath、difficulty、player/team、是否人工起步。Spain 原图当前未找到；无 builder 的原生证据是独立 Big Island 地图夹具，只去掉己方初始 builder，不替代未修改 Spain 桌面验收。
2. 在同配置多次样本中比较队伍跨区切换、独立目标持续时间、命令拒绝和主力损失。先看是否减少拉扯，再处理受损小队、护送和终局；胜率需要另做充分样本。
3. 对有真实闲置槽与可见建筑的长局观察重炮报价→付款→成品→合法攻击链。若仍积压现金，先识别产能/军力槽/目标可达约束，不能用无价值支出或持续加 hard cap 消灭余额。

输入与精确行证据见 [INPUT_ANALYSIS.md](../evidence/operations-2026-10-01/INPUT_ANALYSIS.md)。用户提供的结构化资料只指导“先增加有效消费、少量远射程攻城”的方向；数值核定来自冻结1.15 INI，真实报价仍来自原生菜单。原版引擎、原始地图、用户设置、存档、回放和桌面游戏没有被改动或启停。本轮只创建隔离 headless 目录和测试夹具，商业资产不进入交付证据包。


## 最后追加：4800游戏秒入口

用户追加要求已完成：现有独立环境新增 `Astra验收-2开始Match-4800.bat`，最终交付带 `RW-Agent-Match-4800.bat`。4800是Battle游戏时间，开局另计，胜负提前结束；墙钟安全上限3600秒，推荐5x（Battle约16分钟），至少2x适用。参数合同4项通过，实际4800长局未执行。现有桌面JAR仍b3e172de；新增入口没有替换它。最后报告边界修正提交 `542a59dce51028a18f59b40e04617b872b03d9f0`，不改变最终JAR。

# GPT / DeepSeek 交接：反馈驱动的运营与调度改进

更新：2026-10-01（Asia/Shanghai）。当前候选 **RW-CANDIDATE-2026-10-01-FEEDBACK-v1**。冻结基线仍是 **RW-BASELINE-2026-09-30-GS-v1**；没有晋级、回写旧结果或替换用户桌面 JAR。

## 接手位置与身份

唯一生产源码是 `G:\deepseek 工作台\GitHub发布\Rusted-Warfare-Agent\agent` 与 `tools`。先读仓库 AGENTS、[BASELINE](../project-state/BASELINE.md)、[baseline-manifest](../project-state/baseline-manifest.json)、[CURRENT_STATE](../project-state/CURRENT_STATE.md) 和 [NEXT_STAGE_PLAN](../project-state/NEXT_STAGE_PLAN.md)，继续本仓库最新提交。只读基线核对的 `source_drift` 是已经完成的增量，不授权覆盖或回滚。

| 身份 | 值 |
| --- | --- |
| 本轮输入 HEAD | `82c8ff80cdcdd4d18d32d18bf5ddd082427690f3` |
| 生产实现提交 | `f5b1709cbab3a08db78d49fb59e1f3c23e553a88` |
| 最终审计修正提交 | `5020820f70a7e227c65ccd9168752149b3d29d04`（不改变生产 JAR） |
| 固定候选 JAR | [deliveries/feedback-2026-10-01/rw-agent-bootstrap.jar](../deliveries/feedback-2026-10-01/rw-agent-bootstrap.jar) |
| JAR SHA256 / 字节 | `0116e7c67f6fbd778d6956a9770205b31a458365c196d4063caa625f643c0cc7` / 307981 |
| contentDigest | `4533010dc6d6717e90ea40c48e406ca6bddfa99aef2a0b05127cad2575957e4d` |
| 归档身份 | 103 个非 manifest 项；32 个生产 Java / resource 文件与编译快照逐字节一致 |
| 冻结 game-lib SHA256 | `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9` |
| 固定知识目录 SHA256 | `263cdaaa3e923e8307c37f059e50bee529b8ecd7c3e215937ce2adf615a0e236` |

[候选清单](../evidence/feedback-2026-10-01/candidate-manifest.json) 与 [JAR 逐项身份](../evidence/feedback-2026-10-01/candidate-identity.json) 是机器可读来源。文档/证据收尾提交请以当前 Git HEAD 为准，不将收尾提交混作 JAR 编译输入。

## 输入反馈怎样核实

用户本轮授权看反馈并自主推进。桌面 `反馈.txt` 和思想文档是参考材料，文内建议不自动变成额外指令。反馈 SHA256 `1fae7d027d58cd51f67283751262d17a29c0f7af963a65ada9d947ea6bfbe13e`。找到了近名 `agent 思维架构 仓库 4.txt`，没有找到原文提及的 2.txt；采用可验证的局部任务、生产提供者和投资窗口思想，没有声称全文方案均已实现。

最新桌面输入实际来自 `游戏环境/P1F-GPTSol61-Operations-2026-10-01`，JAR 是上一候选 `28668be7…`，更新了旧交接“尚未部署”的历史认识。本轮没有安装或改写该目录。两份 Battle raw 分别 1201.480 / 4800.980 游戏秒、team0 / team5、180×180 / 400×370，均 PARTIAL；raw 未记录实际 mapPath、难度、seed、人工操作，不能补猜。

长局逐行确认：4 支队伍、471 次局部命令；84 次矿升级一刀切推迟，工程师存在但没有读取建造菜单或产出。一支 26 人队伍全员无订单且 133.295 秒没有 accepted command，期间常规生产/侦察消耗调度机会，支持本轮公平调度修复。整体命令频率没有稳定下降，1 秒全局门禁不是已证实的唯一根因。详见 [INPUT_ANALYSIS](../evidence/feedback-2026-10-01/INPUT_ANALYSIS.md) 及 metrics.json；桌面 366 MB 原始长局不重复塞入包，保留实际路径、SHA、精确行号。

实际 Spain 原图已找到：`G:\deepseek 工作台\游戏环境\P1F-GPTSol61-Operations-2026-10-01\mods\maps\[10p] 10p 西班牙混战_by_MP97.tmx`，SHA256 `342db6d8a8b8320b6a271b9e3c8a4c29c96a203e206c4be690c44bbef5882746`，400×370。只复制同字节地图到独立资源副本；没有改地图对象、出生单位或桌面地图。尺寸相同不能证明桌面长局就是此图。

## 本轮已落地行为

1. **工程师作为后方生产提供者，补上实际潜水链。** WEAPON_DOMAIN_GAP 时工程师先回后方，再用原生建造菜单与实时报价正常造两栖战机；已有合适成品优先。真实价格为 native `amphibiousJet` 2000，未使用 unused `c_amphibiousJet` 1800 代替。资金预留、已付款未成品占额、生产者丢失及超时有界处理；只有新 ready 成品才转交任务。新增原生 mode 读取与正常 SET_ACTION 命令，飞行成品先到已观察合法水域，Dive accepted 后仍等待当前 COMPATIBLE 和已知接近路径证据，才下响应命令。每次响应重查同会话/同目标/当前 actor 证明。模式完成目前主要按目标 engagement 判定；若目标在切换过程中浮出水面，单独的 DIVE event 标签不足以证明自身潜水，后续需补自身 mode 与目标领域联合确认。本次自然三例另有 WATER/range100，确实证明实际潜水。隐藏目标保持 UNKNOWN；旧 MOVEMENT_APPROACH_GAP 工程师响应语义保留。新 ready 的唯一候选/地点/时间匹配证明角色可用，不单独证明严格出生血缘来自该工程师。
2. **T2/T3 矿投资按局部风险和剩余时间判断。** 删除旧“钱多且接近军力目标就全部推迟”的收入门禁。仅白名单 T1→T2、T2→T3，报价来自当前原生菜单；观察连续安静窗 45 / 180 秒、矿 HP≥85%、附近已知武装威胁/记忆威胁和遥测缺失，保护全部共享预留与两次普通补兵价。披露的增收估计约 6.035 / 12.07，回本加转换时间和生存余量必须小于剩余游戏预算；安静窗不能保证未来存活。原生 T3 常见报价 4000，专项也覆盖变化报价 5300。没有开放超频/加固，也没有把价格硬编码为下令依据。
3. **局部突袭用小队有界处理。** 对当前可见、靠近己方基地或矿的小规模武装接触，借用最近 2–6 个健康、兼容、接近路径已知的主力单位，保留至少 6 名主力和各原队最低人数。临时 owner 和独立 task lease 防止争抢；主力跳过同一已服务接触。45 秒响应上限、失联、扩大战情、低血量、无进展触发回原队，撤回/释放同样有界。HP 比例只是耐久下限，不是战力胜负模型。
4. **被常规运营饿住的队伍获得公平调度。** 至少半队真实无订单且远离旧目标、16 秒未有 accepted order，才进入公平 lane；每 8 秒最多一个公平机会，稳定轮转，位于建造者恢复/紧急撤退/局部危机之后、普通策略/生产之前。仍遵守全局 1 游戏秒命令间隔、合法目标/前沿、ownership 与 Target Guard。队伍成员、前沿到达/停滞每轮观察，诊断记录闲置人数、可用目标、前沿、冷却与门禁。

上一候选的无 builder 自动生产、稳定多队编组、少量重炮消费和付款空窗占额保留。此轮 Spain 原始出生只有 commandCenter，自动正常生产 1 个 builder 后重做预检，完成经济/发展/Battle；没有手动补单位。

## 验证结果与边界

Windows 有效矩阵 **54/54 步、failedSteps=0，28 Java runs、23 Python suites、420 个不同 Python 用例、0 Python skipped**。首次完整运行是 53/54：隔离快照遗漏历史文档夹具导致 test_reports 失败；补齐夹具后该套件 16/16，再复核改动过的解析/审计/控兵套件。原失败完整日志保留，没有改写成一次完整入口全绿，也没有将重跑数相加。生产 JAR 冻结后只做受影响复核。POSIX-only Java 模拟按平台跳过；Linux 本轮没有运行。

专项包括 LocalArmy 372、LocalCrisis 16、MineInvestment 30、EngineerProvider 47、NativeMorph 102、StrategyNative 51 项合同。NativeMorph 包含继承的起步 fixture，并非 102 次自然潜水。33 个最终 HTTP 场景（army14/provider8/surplus11）通过严格解析、反馈、运营、全局策略、专属生命周期五种审计；surplus 流从原 JSON recorded events 重建，原件与派生 SHA 都保留。旧 blanket-mine 候选的负对照失败按预期保留。

同一固定 JAR 跑了两场未改原图的隔离原生局，difficulty=1、requested speed=5、单本地玩家；设置、初态、raw 和 seed 声明都保留，seed 可重复性没有验证。

| 自然触发证据 | Spain（2401.328 游戏秒） | Big Island（1802.160 游戏秒） |
| --- | ---: | ---: |
| T2 下令 / observed ready | 2 / 2 | 5 / 5 |
| T3 下令 / observed ready | 0 / 0 | 2 / 2 |
| 工程师 jet 下单 / ready / 转交 | 6 / 6 / 6 | 4 / 4 / 4 |
| Dive accepted / 当前兼容确认 / jet 响应 | 0 / 0 / 0 | 3 / 3 / 3 |
| 危机任务开始 / accepted 响应 / accepted 撤回 | 24 / 30 / 24 | 7 / 9 / 6 |
| 最大同时队伍 / 公平调度次数 | 1 / 0 | 4 / 49 |
| 观察己方损失 / 末现金 | 208 / 580 | 96 / 61678 |
| Battle | PARTIAL / ONGOING | PARTIAL / ONGOING |

Big Island 已自然覆盖实际潜水反潜响应链：3次来自同一架复用战机1241，raw行6625/8756/10421都有WATER/range100/COMPATIBLE，并非3架独立成品都完成反潜。不能据此声称专属击杀、全地图成功率或胜率改善。Spain 前四个成品接手时目标持续不可见，253/237/166/239 次原生查询保持 UNKNOWN/TARGET_NOT_CURRENTLY_VISIBLE；后两个局末转交，无自然 Dive。两局都没有自然结算；旧桌面 team5 与本轮 team0 不能作为受控 A/B。

Big Island 诊断最长 275.264 秒 accepted age 已逐行解释：cohort6 在 raw 行6844退出多队模式、8473重新 active，中间有20条 rule-main attack和27条 queue。最大诊断8546时30人只有4人无订单且远离目标，重新 active 仅9.136秒；不能写成整队275秒停摆。Spain 没有本队 accepted order，160条 age 都是 null，不能写实测空闲为0。下一轮应按 active-mode 窗口和主控制器命令修正指标，不能仅改数字掩盖饥饿。

Spain 首次运行器 FAIL 源于 RETURN 坐标 Float32→三位输出的严格比对误报；最终 parser 精确复现原生量化规则，不是任意容差，原 raw 不变。Big Island runner 的 FAIL 是要求自然终局而实际预算到期。原 runner/batch FAIL、首次18组量化误报、旧 Recon owner 缺字段、137个旧矿 schema 误报和最终离线结论分别保留；五审计最终没有违规/缺证据，解析为前三阶段 PASS + Battle PARTIAL、issues=[]。不要将审计 PASS 写成对局获胜。

两份 Battle SHA：Spain `9cead6aca2db590cfd4c2ff476e6cf4c0a3bcb1787b2bd36f65f9f342beb746a`；Big Island `9906324b5a3fd763070c076b993814bc89a9ab13c9e968bbf6afa13c8b2b33cb`。详见 [完整证据](../evidence/feedback-2026-10-01/README.md)、原生两份摘要及 raw-evidence.zip 的逐文件 SHA 索引。

## 尚未落地与下一项建议

优先推进 **新矿点扩张的局部风险门禁**。本轮改的是已有矿升级；CONTESTED/EMERGENCY 对新址扩张的全局 veto 仍在。下一项应分别检查基地、工人、矿址与路线的当前/记忆威胁、到达证据和资金余量，不直接删除全局拒绝，也不能把地形可达视为安全。先复现“基地接敌但另一侧安全矿址可建”的受控轨迹，再覆盖未知威胁、工人受袭和途中阻断的拒绝与退出。

后续顺序：多队 active-mode 空闲诊断与所有队伍连续 HP/no-progress 观察；早期可回收坦克侦察及有战力依据的局部进退；再做资金质量出口。猛犸 native INI 已查到 `c_mammothTank` 覆盖 native mammothTank、3900/hp2600/T2/GROUND/range190，但本轮**没有实现采购策略**，仍需验证实时菜单、混编比例、兼容目标、付款占额和合法参战。Big Island 末现金61678说明冗余支出尚未闭环；升矿不是最终现金出口。工程师通用后方重型增援也未做，仅完成这次武器领域缺口生产者链。

当前队伍成员/前沿连续观察已落地，全部队伍 HP/no-progress 的联合策略采样仍主要依赖 tactics 获得机会，不应宣称此处完全修复。多队公平触发也有半队/旧目标距离门槛，少数新补兵空订单还不是自动独立补兵整队机制。局部危机只覆盖单任务、基地/矿附近小簇；响应者按欧氏距离选取而非路线ETA。撤回可在接受命令8秒后有界交还，不能把accepted撤回或超时交还写成已到达安全位置。

## 使用、回退和保护

[候选目录说明](../deliveries/feedback-2026-10-01/README.md) 带1200/2400/4800 Battle游戏秒入口。4800可满足用户“4000以上”要求，原生胜负提前结束，墙钟安全上限3600秒，推荐5x；本轮新候选自然局预算是2400/1800，没有跑4800自然局。入口只连接操作者已打开的合法单机局，不自行启动/关闭游戏。

固定交付目录不是可双击安装目录。新建独立游戏环境、使用已有匹配启动套件、备份该新环境JAR后放入候选和入口；不要覆盖运行中的程序。回退件是上一候选 [Operations JAR](../deliveries/operations-2026-10-01/rw-agent-bootstrap.jar)，SHA `28668be7…`；冻结v1也保留。本轮没有进行桌面部署，因此无需回退用户当前环境。

所有本轮构建/原生局只在 `G:\deepseek 工作台\_validation\feedback-20261001`，测试引擎由各自运行器记录 EXITED。原版引擎、知识目录、冻结交付件、用户设置/存档/回放均未改；Spain仅同字节复制原图。本轮包不含商业引擎、地图/资源、隔离偏好/存档，包含原始报告、失败记录与复核证据。开始/结束基线验证都核对106文件，唯一失败类别是增量源码漂移。

本文件是权威交接；工作区 `助手交接/HANDOFF_Codex_Feedback_2026-10-01.md` 是同步内容。旧Operations状态/计划已存 [归档](../project-state/archive/2026-10-01-before-feedback/CURRENT_STATE.md)，其中“当前”“未找到”“未部署”只代表旧时点。

# Octopus G5 交接 — 2026-10-03

G5 Combat Intelligence + Runtime Acceleration + Human Validation 已形成可运行候选。起点83c09fb；分支`codex/octopus-g3-execution-20261002`。功能提交22c88a2；人工入口/契约/回归接入f058d3a，也是完整回归的测试源码。随后只收束文档、manifest、evidence与runtime输出gitignore，185项测试输入逐字节未变。最终HEAD见Git最后的交付文档提交，后续从该HEAD继续，不回退冻结血统基线。

候选`RW-CANDIDATE-2026-10-03-OCTOPUS-G5-v1`，未部署/push，未启动或关闭桌面游戏。Jar：`G:\deepseek 工作台\_validation\octopus-g5-20261003\final-source\agent\dist\rw-agent-bootstrap.jar`；SHA256 `b5d87aff499028a32738eefc6b99710c6b6a9d90faafb04d6b5093b6b769acad`。参考原版engine SHA仍8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9。

## 实际变化

- RuntimeAcceleration：transport并行但controller/world/ownership主线程串行；真实独立endpoint时钟，cycle内保留原observation的菜单cache；250 game ms decision gate，实测速率调10～100 wall ms等待；重型运营1000 game ms/dirty触发；生产先于重型经济/Strategy。G5 tokens250ms、burst16，G3 credits/slots/unsettled/ghost/owner-generation不放松。
- GeneralCombatDirector/CombatLedger：每General独立合法HP趋势、局部visible压力、退却/集结/滞回、目标与实际回执节奏。HP proxy阈值透明，未知窗口不造5秒战损率，缺席/敌失联不造死亡。自然局已经看到三次OVERMATCHED→RETREATING→REGROUPING→NORMAL闭环。
- CommanderDirector/ThreatTask：满编后≥6当前健康FREE产生额外FORMING，desired24；共享合法visible threat attention不合并owner；按crisis/retreat/健康缺口/距离/urgency补兵；退却补兵进当前rally，stale override为UNKNOWN而非受压centroid。G4生命周期、pending+LR、generation/ABA保留。
- 大军兼容路径真实按≤48分块。FREE低优先级home-side staging；General临时分队结束仍FREE。
- first factory优先恢复：G5不再等待第一矿完成才建第一生产设施；报价/保留资金/native guard保持权威。
- Observer增量尾随、逐General状态、实时文本/HTML与自动短报告；已真实读取85MB自然日志。普通变化节流，不丢raw。

完整契约见[Combat/Runtime](../docs/OCTOPUS_G5_COMBAT_RUNTIME.md)、[Human入口](../docs/OCTOPUS_G5_HUMAN_VALIDATION.md)。G4/G4.1、Static Map Knowledge、G3 mode/credit契约继续适用；新增G5规则优先于历史“No accumulation birth”。

## 测量与证据

同Small Island/5×/difficulty0、原版自然ticks、fog-on，本轮连续before83c与afterG5：

|指标|83c|G5|
|---|---:|---:|
|平均decision game ms|2925.626|366.026|
|真实GET墙钟平均ms（G1去重来源口径）|13.147|12.518|
|action→receipt墙钟平均ms（相同日志口径）|14.091|14.462|
|accepted命令/game minute|24.445|52.098|
|同observation最多accepted|4|9|
|首厂command game ms|47728|2528|
|首次queue game ms|64720|20000|
|首支ACTIVE game ms|123664|73264|
|原生结果|VICTORY / 456528ms|VICTORY / 389264ms|

G5 runtime精确GET transport平均12.615ms、POST transport14.282ms；cache390，parallel reads2126。所以主要改进是等待/遍历/重复读取/独立actor使用，不宣称HTTP本身更快。两局seed不可复现，不能据此声称胜率、战损或战术因果收益。两局都只有最多一支General；额外General自然诞生仍NEEDS_EVIDENCE。

自然日志Jar877dfa9ac8e40feab06263644dc13240fb856bddc429455ad4a48de28f9f67a6与最终full Jar仅ZIP容器元数据不同；183项全部class/resource/manifest payload逐项完全相同，证据jar-payload-equivalence.json。最终Jar另完成同Jar原版fixture253项及G3原版229项。

Focused：Combat56、Commander265、Runtime32（真实延时HTTP串行约528ms/并行265ms）；G5 actual-BC HTTP4tests；Observer18tests；旧Force69/Formation219绿。原版native共482：G5 253，G3 production168/modes30/competition31。66actor真实native48+18；24+38FREE额外General、24 actual JOINING回执、后帧人工位置ACTIVE；A六条retreat+B独立attack及native command.k后帧order witness。native fixture明确人工seed/fog/clock/位置、0自然ticks，不冒充自然生产/行军/到达。初始native CLI/阈值/审计字段三次失败保留，均未为过测试修改产品。

## Windows 回归的参考修正

本轮**只跑一次full**：76steps/42Java/31Python/496tests/skip0，原始74/76、exit1，失败为G1/G2 LocalCrisis历史诊断对照。

原因：外部run脚本沿用了早于83c的pre-G1/G1 reference，83c首厂修复已使零工厂经济诊断由economy_expansion_blocked变为production_facility_blocked，217→231 legacy事件；wire commands仍一致。单独用83c再现同一旧参考失败，并证明本候选与83c的commands及normalized legacy events逐项相同。没有回滚已验收首厂修复，没有修改产品或弱化断言。

改用冻结83c行为参考后，完整G1套件4/4、G2套件9/9 focused补验通过，trace on/off、只读WorldState与因果断言保留；形成**有效76/76、failedSteps0，带显式reference correction**。不是“首遍full零失败”；旧失败日志、复现、正确参考13项与机器摘要全部保留。没有第二次full。下次配置历史对照必须按当前已验收source选择参考，不能继续对跨越已授权行为修复的诊断作绝对等价要求。

## 人工怎么测

本机直接双击`tools/RW-Agent-Octopus-Test.bat`：连接/等待当前合法本地游戏，自动等推进帧后启动Agent+Observer，结束自动报告。由**操作者明确双击**`RW-Agent-Octopus-Start-Test.bat`才新开隔离游戏；仍自行进房/开局，不做菜单automation。可带`-Seconds 4800`。通用参数/端口/目录方式见Human契约。

Manifest记录candidate与旧bridge不同SHA的协议兼容，原版引擎指纹/health身份受校验；不把不同二进制伪装同Jar。程序留游戏打开。桌面真实新开隔离窗口、人工自然局仍待操作者验收，本轮没有执行这些BAT。

## 当前边界与下一断点

- 当前是真实force collect/priority，两段primary/optional；经济/Strategy/production/Recon仍immediate，不能声称全局arbitrate。metadata保留G4_FORCE_CONTROLLERS_ONLY标签，范围已含同collector接入G5 force proposals。
- HP proxy不含武器/射程/地形/攻击许可权重，不能当战斗力模型；没有K/D/归因击杀/Performance。当前native缺失自身dead row时ledger确认死亡保持未知。
- retreat rally地图边界geometry已证，动态安全/可达性UNKNOWN；跨岛/混合兵种JOINING、Spain自然多General协同及长期兵力流转需实机证据。SearchArea仍不激活；两栖各层证据仍独立。
- 保护核对：185测试输入未变；7项选定保护输入中engine/冻结Jar等6项一致。原目录preferences.ini后续发生变化；只读进程清单看到桌面Main在full结束12:57:10之后于12:58:19启动，prefs13:01:01更新。没有归因或回滚这些外部/用户变化，完整记录在validation-summary；本任务未启停桌面。

建议下一轮G5.1：先由人使用新入口打一局Spain/长图，确认额外General自然birth、rally补兵/回退、生产连续性和observer易用性；再据raw修实际瓶颈，优先mixed-domain rally/join与HP proxy误判。不要先追加庞大全矩阵或直接扩G6理论。子智能体仍仅gpt-6.1-sol/high。

紧凑证据索引：[evidence README](../evidence/octopus-g5-2026-10-03/README.md)；本地完整raw `G:\deepseek 工作台\_validation\octopus-g5-20261003\`。未来代理先读此handoff、contract和当前state，勿重新G0～G4全仓审计。

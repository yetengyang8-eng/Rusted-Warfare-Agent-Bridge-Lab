# Codex 优先代码入口

## 路径约定

| 简称 | 原路径 | 包内快照 |
|---|---|---|
| GAME | `G:\deepseek 工作台\游戏环境\rustedwarfare PC 1.15 原版` | `evidence/sources/frozen/`（assets元数据） |
| RAW | `G:\deepseek 工作台\游戏资料\30_GitHub原始仓库\skywater275__rw_analysis\02-decompiled\com\corrodinggames\rts` | `evidence/sources/rw_analysis/02-decompiled/com/corrodinggames/rts/` |
| AGENT | `G:\deepseek 工作台\游戏环境\Rusted-Warfare-1.15-Agent-0.07\developer\src` | `evidence/sources/current_agent/developer/src/` |
| BYTECODE | `GAME\game-lib.jar`（冻结SHA见README） | `evidence/sources/bytecode/*.javap.txt` |

`SOURCE_MANIFEST.json` 给出每个原文件完整路径/SHA。`STATIC_VERIFICATION.json` 和 `evidence/bytecode_witnesses.md` 给出本轮关键常量/分支的静态核对。

## 地形与Fog

| 目的 | 类/方法及 descriptor | RAW 入口 |
|---|---|---|
| 移动枚举名/混淆字母 | `game.units.ao.<clinit>()V`，`a(String,String)` | game/units/ao.java；冻结ao.javap.txt |
| 地块属性、控制tile | `game.b.g.a(b,e,j,int,short,short,boolean):g` | game/b/g.java:82、191 |
| 外部TSX与property读取 | `game.b.j` constructors、a(Element) | game/b/j.java:74–158 |
| 旧TSX路径回退 | `game.b.b.d(String,String):InputStream` | game/b/b.java:1472–1506 |
| 图层识别 | `game.b.b` 地图加载段；`e(int,int):g` | game/b/b.java；e()在478附近 |
| 资源池登记 | `game.b.e.a(int,int,g,boolean)` | game/b/e.java:70–100 |
| 地形成本/覆盖层 | `gameFramework.k.i.d()V`；c()Z | gameFramework/k/i.java:312、334–416 |
| 建筑/单位成本 | 同类 c(y)、e() | gameFramework/k/i.java:418起 |
| blocked与合并成本 | `gameFramework.k.l.a(i,int,int,boolean):boolean`；b(i,int,int):int | gameFramework/k/l.java:88–118 |
| 类型成本数组选择 | `gameFramework.k.l.a(ao):i` 与初始化 | gameFramework/k/l.java:58、169–189 |
| 建造语义 | `game.units.d.d.a(...)` 重载 | game/units/d/d.java:129–209 |
| Fog模式解析 | `game.b.b` load：map_info.fog | game/b/b.java:1002–1038 |
| 当前可见 | `game.b.b.a(float,float,n):boolean` `(FFL…/game/n;)Z` | game/b/b.java:398–415 |
| 共享揭示/接收者 | `game.b.b.a(float,float,int,n,boolean):void` | game/b/b.java:1178–1205 |
| smoothFog缓存 | `game.b.b.l()V` | game/b/b.java:1290起 |
| 揭示软边、回暗 | `game.b.b.b(float,float,int,n,boolean)`；f(float) | game/b/b.java:1310、1386起 |
| 玩家动态Fog与联盟 | `game.n.N`；d(n)Z / c(n)Z | game/n.java；联盟比较在1103附近 |

九图精确文件名、外部依赖、对应局部gid及原property都已在 `TERRAIN_MAP_CANDIDATES.json` 中，无需再遍历地图仓库。

## 核心单位和属性

| 类型/规则 | 原始入口 | 关键方法/字段 |
|---|---|---|
| builder | RAW game/units/e/b.java；BYTECODE ar$52/e.b | ar$52创建e.b、c()500；e.b z/A/B/C/D/m/l |
| heavyTank | RAW game/units/e/f.java；BYTECODE ar$16/e.f | ar$16创建e.f、c()800；m160、z0.8、af/l true |
| landFactory | RAW game/units/d/m.java；BYTECODE ar$12/d.m/d.i/d.d | c()700、c(2)2000；继承NONE/不移动/不攻击 |
| airFactory | RAW game/units/d/a.java；BYTECODE ar$23/d.a/d.i/d.d | c()1000、c(2)1500；继承NONE/不移动/不攻击 |
| scout | GAME assets/units/scout/scout.ini | price/movement/attack、sight22/unfinished15 |
| tank→c_tank | GAME assets/units/tanks/tank.ini | overrideAndReplace、price350、range130 |
| artillery→c_artillery | GAME assets/units/tanks/artillery.ini | overrideAndReplace、price900、range290 |
| extractor→extractorT1 / T2 | GAME assets/units/extractor/extractor.ini、extractorT2.ini、extractor_common.ini | copyFrom、700/2100、升级1400 |
| turret→c_turret_t1 | GAME assets/units/turrets/turret_t1.ini、turret_common_land.ini | copyFrom、price500、range165 |
| 已知机甲价格勘误 | GAME assets/units/mechs_large/mech_artillery.ini、mech_lightning.ini | core.price=1400/5200 |
| 单位配置默认值/缩放 | RAW game/units/custom/ag.java | 1692 sight；1708 building；1864 movement；1914速度；1963转向；1988射程；2231攻击域 |
| 自定义运行属性 | RAW game/units/custom/j.java | h/i/Q、z/A/B/C/D、l/af/ag/ae/ah、s/c(boolean) |
| 目标兼容 | RAW game/units/y.java:3045–3105、3171起；custom/j.java:2370起 | k(am)域；a(am,boolean)额外限制；i(am)武器循环 |
| 炮塔限制 | RAW game/units/custom/bn.java:318、364；custom/j.java:1633–1704 | J/K/L/M/N、range/minrange/角度/标签 |
| 当前水/液体接触 | RAW game/units/am.java:1713起；gameFramework/utility/y.java:258–290 | cH/cJ/cK，utility c/d |
| 默认原生视野/揭示 | RAW game/units/y.java:4578起 | s()15，c(boolean) |

不要在未知对象上试调用这些方法去猜名字。先在源码/冻结字节码确认 owner、descriptor、继承链；需要运行数据时沿用允许的合法观察与已知方法集合。

## 当前 Agent 接入点（本轮只读）

以下均位于 AGENT `io/rwagent/`，是当前源码快照的入口，不是本轮完成的代码修改。

| 目标 | 文件 | 接入说明 |
|---|---|---|
| 能力目录导出 | bootstrap/CombatBridge.java:824起 `capabilities` | 目前返回price与原始boolean字段；不是完成的三攻击域schema。保留own/visibleEnemy过滤，加有来源的规范能力 |
| 敌情/历史接触 | bootstrap/CombatBridge.java:887起、901起 `enemyIntel` | 可见单位与历史contact分别处理，不用离线类型常量刷新隐藏实例 |
| 地形合法采样 | bootstrap/ScoutBridge.java:273起 `recordVisibility`；305起 `recordPassability` | visible先于资源/blocked采样；每movement缓存0未知/1阻挡/2可通行 |
| Recon选点与路线 | bootstrap/ScoutBridge.java:394起 `VisibleGrid`；client/BattleClient.java | 在现有visibility/预算/角色约束内补terrain原因，保留unknown；以方法名定位后续版本 |
| Target Guard | client/BattleClient.java目标候选、主力命令分配；bootstrap/CombatBridge.java | 优先在目标分配前提供三态domain结果；混编攻击者集合不能简单all/any误用 |
| 建造合法性 | bootstrap/EconomyBridge.java:184起、326起 | 可见资源格/footprint/原生放置反馈保持；本轮不重做经济 |
| session与identity | bootstrap/RuntimeBridge.java | 缓存随match/玩家/图/内容身份失效 |
| 时间基准 | headless/HeadlessRunner.java:66–78 | wall step与engine.b(1f,16)分开；运动估算保存时间语义 |

原下一轮产品合同原样附于 `evidence/sources/handoff/Codex_KnowledgeBacked_WorldModel_Capability_v0_2026-09-29.md`。该合同要求完整回归与实局，**本资料任务不声称代为完成**。

## 参考资料的定向入口

- PDF：`evidence/sources/references/Modding_Reference_1.15.pdf`，页1视野，页3攻击，页4movement，页5动态stats；页数为PDF物理页序。
- NDT：同目录 `NDT_CT_1.3.2.8.xlsx`，相关sheet/row/cell抽取见 `ndt_selected_rows.json` 与 `ndt_map_rows.json`。
- 社区JSON：`evidence/COMMUNITY_CROSSWALK.json` 保留section与JSON Pointer。
- repo HEAD 与来源文件hash：`SOURCE_MANIFEST.json`。版本同名不代表内容同源；语义CSV里的verified-exists不代表语义正确。

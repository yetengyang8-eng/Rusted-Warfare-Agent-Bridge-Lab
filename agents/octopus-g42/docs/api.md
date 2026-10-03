# 0.04-alpha1 API

Base: `http://127.0.0.1:47653`。仅本地测试。不要同时运行多个控制客户端。

## 状态

`GET /health`：存活、版本、端口、是否允许命令。

`GET /state`：保留旧字段，增加 `schemaVersion:1`、`sessionId`、`map:{width,height,tilesWide,tilesHigh,tileWidth,tileHeight}`。宽高通过地图原生 `i()/j()` 获取，分别对应 `C*n` 和 `D*o`；`p/q` 是半格偏移，不能用于计算地图尺寸。坐标是世界坐标，不是屏幕像素。单位 ID 为 Java long，外部客户端需保留 64 位整数精度。`mobile` 会排除死亡、已移除或被运输等不能移动的单位。菜单状态不返回残留单位。

快照与命令均在游戏线程处理。`frame` 和 `gameTimeMs` 使用 0.00 已映射的引擎字段 `bx/by`。一次快照中的字段来自同一次游戏线程任务。

## 精确移动

先读状态获得当前 `sessionId`、单位 ID，再发送空请求体的 POST：

```text
POST /command/move?unitId=4&x=300&y=240&sessionId=<state.sessionId>&requestId=<unique-id>
```

五个参数都必需，未知参数、重复参数、非数值、NaN、Infinity、越界坐标被拒绝。坐标必须满足 `0 <= x < width`、`0 <= y < height`。sessionId 为 1–64 位字母数字或连字符，requestId 还允许下划线。建议使用 UUID。

成功返回 `status:queued`、requestId、sessionId、frame、unitId、targetX、targetY。不保证已经执行或可达；客户端必须继续观测。

同一对局最近 **256 条成功命令**按 requestId 去重：同 ID 同单位同坐标返回原响应，同 ID 不同单位/坐标返回 409。缓存被淘汰后不能依赖该 ID 去重；这不是永久 exactly-once 协议。

对局标识在读取/下令时通过地图对象、玩家对象、加载状态变化或帧号回退更新。它是当前轮次的尽力检查，不是持久回合数据库；未采样期间若引擎复用全部对象并且帧号已追上，不能承诺识别所有换局。因此客户端需要持续轮询，换局后重新启动脚本。

| HTTP | 含义 |
|---|---|
| 200 | 快照返回或命令已排队 |
| 400 | 参数或地图坐标无效 |
| 403 | 命令关闭，或来自带 Origin 的浏览器请求 |
| 404/405 | 新端点路径或请求方法错误 |
| 409 | 未开局、网络/回放、无本地玩家、旧对局、单位不可控或 requestId 冲突 |
| 503 | 游戏线程超时/执行异常，命令结果可能未知 |

游戏线程任务超时后取消并从队列移除；尚未开始的任务不会稍后补执行。已开始执行的任务不能回滚，所以 503 不能解释成“绝对未下令”。自带客户端遇错停止，不自动重试。

`POST /test/move-first` 保留旧入口；内部复用新命令校验。该测试入口不承诺跨请求去重。

## 外部控制循环

主类 `io.rwagent.client.ControlLoop`，命令为 `roundtrip [unitId [x y]]`、`move unitId x y`、`record [seconds]`。每 500ms 观察一次；同一帧连续 10s 停止；每程最多等待 45s（现实时间）。去程结束才提交回程指令。单位死亡/不可控、对局改变、帧号回退、断开或 HTTP 错误均停止。

JSONL 的 `event` 为 `health/plan/observation/action/command_result/arrival/http_error/summary`，`wallTimeMs` 为现实 UTC epoch 毫秒。读 JSONL 时 `summary` 是唯一完成标志。记录器会保存己方单位全量快照；单位规模很大时记录文件与序列化成本会增加，本版未做大规模压力测试。

## alpha2 短移验收约束

roundtrip 在发出任何命令前检查：地图宽高有效，己方单位起点位于地图范围内，目标位于范围内且为有限值，目标与起点直线距离在 40–160 之间。起点与报告地图矛盾时停止，绝不将它夹到远处边界。plan 事件增加 mapWidth/mapHeight/plannedDistance/manualTarget。默认偏移仍为横向 120、纵向 60（向地图中心，边缘留 20）。可选显式目标仅影响这一次往返。

`/test/move-first` 同样改用原生地图宽高，并在起点异常或短移超过 160 时拒绝。通用 `/command/move` 是精确移动接口，不限制为短移。两者仍没有地形可达性/威胁判断。

## 0.02-alpha1：建厂出兵接口

本节为当前新增接口，前述移动 API 继续保留。`/health.version` 为 `0.02-alpha1`。
`/state.ownUnits[]` 增加 `buildProgress`（原始浮点进度，达到 1 才完工）和 `productionQueue`（原生工厂的待生产单位数量，其他单位为 -1）。

```text
GET /economy/plan[?unitId=<builderId>]
POST /command/build-factory?unitId=<builderId>&x=<x>&y=<y>&sessionId=<session>&requestId=<uuid>
POST /command/produce-tank?unitId=<factoryId>&sessionId=<session>&requestId=<uuid>
```

plan 不下令、不扣款。返回 builderId、targetX/Y、factoryCost、tankCost、credits、sessionId。建造者必须完成、己方、存活且具有可用的原生陆军工厂动作；预算需覆盖工厂和一个坦克。搜索 100/140/180/220 半径的八方向候选，经网格对齐，距离不超过 240；建筑占地必须在地图内、当前可见，并通过原版建造合法性判断。不能保证寻路可达或不受敌军攻击。

build-factory 下令前重查动作、资金和地点，返回 queued、最终对齐的 targetX/Y、unitId、frame、sessionId、requestId 和 type。produce-tank 要求原生 landFactory、完成、己方、存活、动作可用且生产队列为空；只提交一个原生坦克生产动作。单位人口上限等最终执行限制仍由原版规则处理，queued 不承诺最终成功。

这两个端点共用本对局最近 256 条经济命令回执；与移动回执分开。重复 requestId 和相同参数返回原响应，参数不同拒绝。不是永久去重，客户端仍应每次生成 UUID。禁止未知/重复参数、非有限坐标、网络/回放、浏览器 Origin；超时机制与移动接口相同，503 结果未知时不自动重试。

客户端主类 `io.rwagent.client.EconomyClient [builderId]`。每 500ms 采样，建造超时 240 秒，生产超时 120 秒，同帧 10 秒停止。使用同目录文件锁防止重复运行经济客户端；不能阻止其他目录的客户端、其他控制脚本或游戏内手动操作。已有单位 ID 不计为本次结果；新工厂须在目标 35 范围内，新坦克须在工厂 200 范围内、完成且存活。观察到生产队列 >0 后归零并出现唯一新坦克才通过；队列 >1、多个候选、工厂消失、换局等停止。归因依赖本次受控环境，不是引擎直接提供生产者关联。

经济 JSONL 事件为 health/observation/plan/action/command_result/factory_started/factory_completed/production_started/tank_completed/http_error/summary。summary 含 outcome、reason、phase、commands、observations。commands 计收到 queued 的响应数，不能用它证明 503 时没有执行动作。


## 0.02-alpha2 产品类型修正（覆盖上一节类型固定假设）

health.version 为 0.02-alpha2。通过原版 custom.l.c(ar.valueOf("tank")) 查询当前替换；没有替换时保留旧 tank。生产动作按类型对象身份匹配，而非把任意名叫 c_tank 的单位当作坦克。landFactory 仍使用原生类型。

plan.productType 和 produce-tank 响应 type 返回实际类型；完整原版通常为 c_tank。客户端用 plan.productType 排除旧单位、寻找新单位，并检查生产回执类型一致。不应在外部消费者里硬编码 tank。找不到动作时响应附预期类型与菜单类型摘要，便于诊断，无额外下令。

## 0.03：基础开局（已含 alpha2 修正）

新增只读 `GET /opening/plan[?unitId=<builderId>]`，受本地单人/非回放/命令开关守卫约束。选择支持经游戏替换表解析的 extractor 与 landFactory 的已完成己方建造者，搜索其 600 范围内当前可见的地图资源格（bL.e(col,row).i），经完整建筑占地可见性与原生 y.b(false,team) 校验，取距离最近者。找不到返回 409 且不下令。规划不要求当前资金足够。

响应：status、sessionId、builderId、extractorType、extractorX/Y、extractorCost、factoryCost、tankCost、productType、targetTanks=3、totalCost、resourceCandidates、distance、credits。resourceCandidates 是合法的“建造者/矿点”配对数，多建造者可能对应同一矿点，不是地图矿点总数。不会返回雾中地形信息。

新增 `POST /command/build-extractor?unitId=<builderId>&x=<x>&y=<y>&sessionId=<session>&requestId=<uuid>`，下令前再次检查合法资源格、整座占地可见性、距离<=600、己方存活已完成建造者、资金、动作可用性；成功使用 e.a(builder)+e.a(x,y,extractor,tier)，返回实际对齐坐标和实际 type（原版为 extractorT1）。与建厂/生产共用最近256条经济命令回执。不能绕过原生建造规则。

客户端 `io.rwagent.client.OpeningClient [builderId]`。顺序建矿、建厂、生产三辆；复用 EconomyClient 的观察与验收实现。建矿前等 extractorCost，建厂前等 factoryCost+tankCost，各坦克前等 tankCost。资金等待每阶段120秒、建筑240秒、每辆坦克120秒、整个任务600秒；帧停滞10秒停止。保护已确认的任务单位，死亡/消失时失败；不自动补建或补兵。

JSONL 新增 extractor_started/extractor_completed、factory_plan、budget_wait。summary 增加 completedBuildings/completedTanks；正常完整开局为2/3，commands=5。连续三辆坦克逐次使用新 baseline ID 集合，每辆均观察生产队列从>0变为0，再验收唯一新产品。日志文件名前缀 opening-。缺少最终 summary 不算通过。

原 Economy 单厂单坦克入口仍保留；health.version=0.03-alpha2。共享 JAR 中的所有客户端版本一致。


## 0.03-alpha2：统一实际类型

当前 health.version 为 0.03-alpha2，前文0.02版本号为历史说明。抽取器、陆军工厂、坦克共用 custom.l.c(as) 替换查询，无替换才回退枚举。规划中的 extractorType、factoryType（economy/plan）、productType 与命令回执 type 均为实际类型；客户端分别按这些字段排除旧单位、识别新单位，并校验回执类型一致。extractorT1 的模板使用 custom.l.a(true)，从实际模板获取占地、偏移及原生合法性，不使用旧 extractor 占地。

找不到开局地点的409 message 包含 extractorType、eligibleBuilders、visibleResourceCandidates、searchRange。visibleResourceCandidates 仅统计合格建造者搜索方框内当前可见的资源格配对，尚未经过600圆形距离/占用检查；不要当作可建矿点数。


## 0.04-alpha1：已有工厂与并行运营（当前版本）

当前health.version=0.04-alpha1。新增只读 `GET /economy/production-plan[?unitId=<factoryId>]`；返回status、sessionId、factoryId、factoryType、productType、tankCost。不要求当前资金足够；只选择完成、己方、存活、当前landFactory类型且队列为0、存在可用坦克动作的工厂。无候选返回409且零下令。继承所有游戏线程、本地/非回放、命令开关及HTTP来源检查。

`DevelopmentClient [targetTanks [maxMines]]` 默认8/3，范围1–30及0–10。共用原生build-extractor与produce-tank端点；不新增直接修改状态的入口。GET opening/plan的明确no legal opening site响应仅结束可选开矿；其他409/503均终止控制器。

JSONL前缀development；事件包含production_plan、targets、mine_plan、expansion_finished、budget_wait、extractor_started/completed、production_started、tank_completed、action/command_result、observation/http_error/summary。summary含completedMines、completedTanks、commands、observations、expansionStatus、outcome、reason、phase。expansionStatus为SEARCHING（进行中或失败）、LIMIT_REACHED、NO_VISIBLE_LEGAL_SITE；后两者允许在坦克达标时PASS。原有建筑/坦克不计入本次结果。

计划及观察均读取实际类型；固定一个工厂和首次选定建造者，连续矿点规划按该建造者当前位置进行。每一条生产链先见队列>0，再见队列=0和唯一新产品；两个任务状态机共享观察快照和资金预算。选择工厂、建造者以及本任务已完成单位纳入持续存活检查。

## 0.05-alpha1 新增字段与只读预检

`GET /economy/preflight` 不接受参数，不下令、不扣款、不创建单位。菜单或禁止指令时仍可读，返回 `commandsAllowed=false` 和原始 `commandGuard`。其他 GET/POST 端点的守卫保持原样。

返回 `eligibleBuilders`、`idleFactories`、`busyFactories`、`factoriesUnderConstruction`、`unavailableFactories`、可选 `factoryId` 和 `recommendation`。建议值为 RUN_DEVELOP、WAIT_FACTORY_QUEUE、WAIT_FACTORY_CONSTRUCTION、CHECK_FACTORY_ACTION、RUN_ECONOMY_OR_OPENING、PREPARE_BUILDER 或 RESOLVE_GAME_STATE。忙碌工厂不会被误判为缺工厂。预检只给状态建议，资金和选址由正式指令再次检查。

`GET /opening/plan` 的成功响应及“no legal opening site;”409 响应新增 `diagnostics`，兼容原有错误前缀和状态码。统计单位为 `builder_site_pair`，同一资源格可能对多个建造者重复计数。

| 字段 | 含义 |
| --- | --- |
| eligibleBuilders | 可进行该开局规划的己方建造者 |
| visibleResourceCandidates | 搜索方框内当前可见的资源格与建造者配对数 |
| outsideRange | 对齐到原生建筑位置后，超过 600 圆形距离 |
| footprintOutsideMap | 完整建筑占地越界 |
| footprintNotVisible | 资源格可见，但完整建筑占地未完全可见 |
| nativeRejected | 完整占地可见后被原生建造规则拒绝 |
| nativeRejectionReasons | 原生拒绝字符串及次数，不推测为占用或地形 |
| legalCandidates | 最终合法候选 |

在正常地图尺寸下，五类结果之和等于 visibleResourceCandidates。候选地形读取先通过视野检查；完整占地通过视野检查后才调用原生建造校验。未知资源格不计入，也不输出其位置。新字段不改变最近合法矿的选择顺序，不修改 extractorT1 与 c_tank 的原生替换解析。


## 0.06 扩展

新增 /scout/observe、/scout/plan、/expansion/plan 与 /command/guard。字段、观察边界与回执规则见 SCOUTING_CN.md。版本要求 0.07-alpha1；原有接口保持兼容。

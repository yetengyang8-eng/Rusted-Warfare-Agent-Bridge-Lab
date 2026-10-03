# Agent 0.00 架构记录

## 基线

- Rusted Warfare PC 1.15，Build `#28`，Game Code `176`
- 原版 `game-lib.jar`，不合入 RWPP 或既有功能补丁
- 注入方式：Java `-javaagent` 启动参数；不改写原版 JAR

## 已确认的内部入口

| 作用 | 1.15 混淆后入口 |
|---|---|
| 游戏引擎单例 | `com.corrodinggames.rts.gameFramework.l.B()` |
| PC 游戏引擎 | `com.corrodinggames.rts.game.i` |
| 游戏线程任务队列 | `game.i.k` |
| 当前本地队伍 | `l.bs` |
| 资金 | `game.n.o` |
| 全局存活单位表 | `game.units.am.bE` |
| 单位 ID / 坐标 | `gameFramework.w.eh / eo / ep` |
| 单位血量 | `game.units.am.cu / cv` |
| 命令控制器 | `l.cf` (`gameFramework.c`) |
| 单条命令 | `gameFramework.e` |
| 移动命令 | `e.a(unit)` + `e.a(x, y)` |

所有读取和命令创建均排入游戏线程，避免 HTTP 线程直接改动模拟状态。

## 0.00 数据边界

状态端点仅返回当前本地队伍的单位。敌军可见性模型留到后续版本，在没有完成合法视野过滤前，不对外输出任何敌军状态。

## 下一阶段

1. 用户侧验证 Java Agent 能随原版启动。
2. 验证状态读取字段与实况一致。
3. 验证移动命令进入原版命令/回放链，而非直接改坐标。
4. 增加精确单位 ID 移动、建造与生产命令。
5. 再验证 `-nodisplay` 与模拟加速，不提前引入训练模块。

## 0.01-alpha1 更新

原文是 0.00 的历史架构。当前已完成精确单位 ID 移动与外部控制循环，接口见 api.md，里程碑见 progress.md。客户端与桥接层使用同一个 JAR；客户端通过 HTTP 读状态和提交命令，无需加载游戏类。

## 0.01-alpha2 修正

地图接口：bL.i() 为地图总宽、bL.j() 为地图总高；C/D 为格数，n/o 为每格宽高，p/q 为半格偏移。alpha1 对 p/q 的解释是错误的，现已修正。状态返回格数和格尺寸用于核查。详见 bugfix-alpha2.md。

## 0.02-alpha1 原生经济命令

新增 EconomyBridge 和 EconomyClient，沿用原版 Java Agent 与本地 HTTP。

| 作用 | 已核对入口 |
|---|---|
| 建造完成度 | am.cm；以 >=1 判断完工，序列化不截断进度 |
| 单位原生动作菜单 | am.N() |
| 动作类型与 ID | units.a.s.i() / N() |
| 建造/生产动作类别 | units.a.v / units.a.w（生产动作 l 继承 w） |
| 动作可用与预算 | s.b(unit) / s.a(unit,true) |
| 工厂候选模板 | ar.a(true)，不登记真实世界单位 |
| 原生地形与碰撞检查 | y.b(false,team) 返回 null 为允许 |
| 占地、偏移 | y.cd() / cZ() / da() |
| 当前可见性 | map.a(worldX,worldY,team) |
| 建造命令 | e.a(builder) + e.a(x,y,type,tier) |
| 生产命令 | e.a(factory) + e.a(actionId) |
| 工厂队列数 | units.d.l.f(false) |

反射仅用于 Java 混淆产生的同名包/类编译冲突，方法映射在类加载时缓存；生产代码不使用 Unsafe。测试夹具使用 Unsafe 初始化不启动图形/寻路线程的地形依赖，但仍调用原生合法性方法。测试结果不能替代完整游戏模拟。


## 0.02-alpha2 替换关系

原版 assets/units/tanks/tank.ini 的 c_tank 会覆盖 tank。生产动作构造器 units.a.l 自身调用 custom.l.c(as) 解析替换，并据此生成动作 ID。桥接层现在使用同一查询入口，按类型对象身份选取动作。客户端跟随计划的实际 productType。

测试增加原版 custom.l 元数据对象和引擎注册表，按原始配置的 name/overrideAndReplace/price/buildSpeed 重建相关加载结果；这不是完整资源加载器或真实世界出兵。上一版只有旧 Java 枚举的测试环境漏掉了这层替换。

## 0.03-alpha1 矿点与开局

地图资源标志沿用原版抽取器检查的 bL.e(col,row).i。仅当前可见格才读取资源标志，模板占地全部可见后调用原生合法性方法。0.03-alpha1 曾错误固定使用旧 extractor 枚举；alpha2 已统一先解析内置替换，再按实际类型创建模板：ar.a(true) 或 custom.l.a(true)，不入世界表。原版 extractorT1 的占地为单格，不能沿用旧枚举两格占地。找矿600直线范围，找厂240范围；尚无路径或敌军威胁模型。

EconomyClient 增加 opening 运行模式，OpeningClient 是独立启动入口。状态机依次规划/等预算/建矿/等预算/建厂/三次等预算与生产/终检。共用建筑验收方法，按类型分别输出 extractor 或 factory 事件；保留每次产品真实类型。已确认的单位 ID 加入存活监控，之后每个观察都检查。


## 0.04：两个任务共享观察与预算

DevelopmentClient独立于已通过的Opening/Economy流程，通过同一组HTTP命令工作。GET production-plan提供已有空闲工厂实际产品与价格。单循环每500ms观察一次，分别推进矿点Job与生产Job；各自记录下令前ID基线、时间、已观察单位/队列，只有验收成功才允许该路下一单。因此出兵无需等待矿完工，开矿也无需等待出兵完工。

资金为当前快照可用值减未观察到原生扣款的待执行订单预留，再优先分配待开矿预算。每条命令排队成功后本轮立即减少可分配额度；后续观察中未见新矿或生产队列启动前继续保留，避免建造者在路上时连续生产耗光开矿钱。这是保守预算，不能保证抵御外部手工花钱；执行端仍再次检查可用资金。

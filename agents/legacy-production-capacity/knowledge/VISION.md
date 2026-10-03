# Vision / Fog：公开规则与合法观察

## Fog 模式

冻结 `game.b.b` 字节码及 `game/b/b.java:1002–1038` 的 `map_info.fog` 解析识别（忽略大小写）：

| 字符串 | 含义与解释边界 |
|---|---|
| none | 此地图配置请求关闭 Fog |
| map | 开启探索迷雾；该字符串本身不启用 LOS 回暗 |
| los | 开启 Fog 与 LOS 回暗 |
| 空/未写 | 进入平台、地图类型和对局设置的默认处理，不等同 none |
| 其他非空 | 记录 unknown fog type；不要把拼错字符串当合法模式 |

`MAP-SYSTEM.md` 的 `basic/los/noFog` 表错误；NDT《地图代码》B54/D54 列出的 NONE/map/los 与原生字面量一致，但其“不写和无效果一致”不能推出无雾。九图未显式写 fog。当前 HeadlessRunner 在载图前设置原生 LOS 配置并在载图后检查 E/F，因此最终应以当前 match 状态为准，不能由地图默认猜测。

## 可见性数据究竟在哪里

玩家动态 Fog 数据是 **`game.n.N[x][y]`**。`game.b.b.M/N` 是地图对象内两张 smoothFog 渲染缓存，`b.l()` 以 127 初始化；不是“永久探索数组/每帧可见数组”。同名字母要连同 owner 类和 descriptor 一起记录。

`b.a(float x,float y,n player):boolean`（b.java:398）在 Fog 开启、玩家 N 存在、坐标在地图内时，以 `N[x][y] < 5` 判断当前可见。否则这个函数可能直接返回 true，**调用者必须先检查 finite、边界、正确玩家和 session**。越界返回不能当作合法观察。

`b.a(n player,int x,int y)`（文件末尾）还涉及 `G` 和 `N!=10`，是另一种探索/显示判断；不能因重载名相同替换当前可见判定。当前 Agent 对敌方属性还检查 `unit.d(ownPlayer)`，应同时保留，单靠地图格可见不等于所有单位属性都合法可读。

`b.f(float)` 仅在 E&&F 下累加计时，超过 260 引擎 delta 后进行回暗与重新揭示；不能沿用文档“每帧把第二数组重置”的解释。动态数组不是 Agent 可以绕过视野直接读取全体敌情的接口。

## 视野半径与未完成单位

| 类型 | 完成态 base sightRange（格） | 未完成态基值（格） | 证据 |
|---|---:|---:|---|
| scout | 22 | 15 | 原版 scout.ini 明写两个字段 |
| c_tank / c_artillery / extractorT1 / extractorT2 / c_turret_t1 | 15 | 15（默认解析） | INI 缺省；custom/ag:1692–1693 与 custom/j:3700–3720 |
| builder / heavyTank / landFactory / airFactory | 15 | 15（继承揭示路径） | 冻结 y.s() 与 y.c(boolean) |

自定义默认 `fogOfWarSightRange=15`，`fogOfWarSightRangeWhileNotBuilt=-1` 是“沿用当前正常 sight stat”的内部哨兵。不是 0，也不一定永远等于最初的 15。

自定义 `j.s()` 只返回当前 `y.n`，实际揭示 `j.c(boolean)` 才检查 `cm<1 && dh!=-1`。只调用 s() 会漏掉 scout 的未完成视野覆盖。原生 `y.c(boolean)` 直接使用 s()；两条路径均先排除运输/附着状态。`setUnitStats`、升级或状态可能改变实例 sight，离线表没有授权读取隐藏实例的最新值。

半径是 tile 单位，但揭示不是半径 `n*20` 的严格二值圆盘。`b.b(float,float,int,n,boolean)`（b.java:1310 起）内部使用 `(n-3)^2` 完全揭示区、`n^2` 外缘，以及到 10 的软边数值；当前可见阈值为 <5，遍历边界还有 n-1 限制。准确“某格是否可见”应使用合法原生判定，不能仅用圆半径距离替代。

## 盟友共享

`b.a(float,float,int,n,boolean)`（b.java:1178–1205）先根据原生模式条件决定只揭示来源玩家，或遍历玩家分发。分发接收者条件可准确写成：

```text
recipient == source
OR (!recipient.w AND (recipient.d(source) OR source.E))
```

其中 `n.d(other)` 在普通玩家上比较联盟字段 r（另有中立特殊分支），不是简单比较 slot id；`n.c(other)` 是敌对判断。本轮保留 `w/E` 的原符号及控制条件，未把它们猜成某个 UI 选项。NDT B61 的 `shareFogWithAllies` 是线索，不能取代这段运行模式与接收者判断。

## 给世界模型的接入约束

公开规则可以缓存：类型视野基值、Fog 模式解析、揭示算法。动态观测只从合法路径进入：own units，或敌对且 `unit.d(player)` 与地图 visibility 均通过的单位。

当前 `ScoutBridge.recordVisibility` / `recordPassability` 先看可见性再写资源和移动域缓存；`CombatBridge.capabilities`、`enemyIntel` 也先做可见性过滤。未来应在同一边界补充 sight/target-domain 数据，不能为了完善能力表直接遍历不可见敌方单位。历史位置、历史能力和估计状态须带时间；进入迷雾后保持 last-known / UNKNOWN，不能静默刷新。

离线九图全局资源与地形数据仅用于构建规则、审计和测试基准。若当前产品不允许预读未探索地形，不能把 `terrain_grids/` 全量注入实时 Recon。

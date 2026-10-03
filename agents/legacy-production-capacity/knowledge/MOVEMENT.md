# Movement：枚举、速度与转向

## 冻结枚举映射

直接依据 `bytecode/ao.javap.txt` 静态初始化，不采用语义重命名表猜测：

| 混淆字段 | ordinal | 原生 enum name |
|---|---:|---|
| ao.a | 0 | NONE |
| ao.b | 1 | LAND |
| ao.c | 2 | BUILDING |
| ao.d | 3 | AIR |
| ao.e | 4 | WATER |
| ao.f | 5 | HOVER |
| ao.g | 6 | OVER_CLIFF |
| ao.h | 7 | OVER_CLIFF_WATER |

`ao.a(String,String)` 用 Locale.ROOT 大写后解析 enum；GROUND、AMPHIBIOUS、SUBMARINE、CUSTOM 不是该冻结枚举的合法名字。`rw_analysis/docs/10-pathfinding/MOVEMENT.md:17–24` 的映射与原版冲突，禁止据其混淆字母建立策略。

内建地面单位经 `game.units.e.j.h()` 返回 LAND。内建建筑 `game.units.d.d.h()` 返回 NONE。自定义加载器 `custom/ag.java:1864–1866` 保存配置 `fg`，运行类别 `fh` 对建筑强制 NONE；`custom.j.h()` 返回 fh。BUILDING 存在于原生寻路成本集合，不能因此把所有建筑能力标为 BUILDING。

## 原始数值

数值的精确来源、行号及状态在候选 JSON 中。内建 builder/heavyTank 使用冻结字节码常量；scout、c_tank、c_artillery、extractor、turret 使用冻结 INI 与 copyFrom。

| 单位 | movementType | maxSpeed | 加速 | 减速 | 身体最大转速 | 身体转向加速 |
|---|---|---:|---:|---:|---:|---:|
| builder | LAND | 0.8 | 0.04 | 0.1 | 3.8 | 0.35 |
| scout | HOVER | 1.0 | 0.03 | 0.06 | 2.4 | 0.2 |
| c_tank | LAND | 1.1 | 0.07 | 0.17 | 4.1 | 0.25 |
| heavyTank | LAND | 0.8 | 0.05 | 0.1 | 1.9 | 0.2 |
| c_artillery | LAND | 0.9 | 0.05 | 0.12 | 1.7 | 0.05 |
| landFactory | NONE | 0 | 不适用 | 不适用 | 0 | 不适用 |
| airFactory | NONE | 0 | 不适用 | 不适用 | 0 | 不适用 |
| extractorT1 | NONE | 0 | 0.01 | 0.01 | 0 | 0.1 |
| extractorT2 | NONE | 0 | 0.01 | 0.01 | 0 | 0.1 |
| c_turret_t1 | NONE | 0 | 0.01 | 0.01 | 0 | 0.1 |

builder 的 `z()` 在 `cK()==true` 时为 **0.6**，否则 0.8；`A()` 同条件为 **1.7**，否则 3.8。`am.cK()` 调 `utility.y.d(x,y)`，先看当前 Ground water/lava，再回退原生水域成本判断；这不是简单的“单位枚举是 WATER”。候选表保留 condition/conditionalValue，不能把 0.8 当作每帧实际速度。

建筑配置中的加减速 0.01、转向加速 0.1 是配置值，不证明建筑可移动。内建工厂继承的加减速 99、转向 -1 同样不是实际运动能力，表中标不适用。类型级 mobile 与当前对象 `I()`（可能受附着、运输等影响）也需区分。

## 字段绑定与缩放

| 规则/字段 | 原混淆绑定 | 主证据 |
|---|---|---|
| moveSpeed | loader `cL.j`；实例 `j.z()=y.j*j` | custom/ag:1914；custom/j:2094 |
| moveAccelerationSpeed | loader dN；实例 C() | ag:1915；j:2350 |
| moveDecelerationSpeed | loader dO；实例 D() | ag:1916；j:2355 |
| maxTurnSpeed | loader cL.k；实例 A() | ag:1963；j:2109 |
| turnAcceleration | loader eo；实例 B() | ag:1964；j:2141 |

默认 `globalScale=1`，moveSpeed/加减速会乘 globalScale；身体转速/转向加速不在这些赋值处乘它。实例 `z()` 还有乘子、运行时 stats 可变，静态表只提供初始公开能力。

`[movement]maxTurnSpeed` 是身体转向，`[attack]turretTurnSpeed` 与 `[turret_N]turnSpeed` 是炮塔转向。scout 示例分别有身体 2.4、attack 默认炮塔 4、turret_1 覆盖 2.4；不能只按键名模糊搜索合并。JSON/NDT 的 `[leg_#]moveSpeed` 是腿部动画，不是单位移动速度。

## 时间单位与估算边界

1.15 Modding Reference **PDF 第 4 页**将 moveSpeed=1 描述为名义每秒 60 像素、每秒 3 格（20 像素格）。这是参考资料的单位说明，不是本次轨迹实测。

当前 `HeadlessRunner.java:66–78` 以 `60*speed` 控制墙钟节奏，同时每 tick 调 `engine.b(1f,16)`。名义 simulation delta、wall time 与 `gameTimeMs` 不能混为一谈。候选表特意保存 raw 值，**不写死 `raw*60` 为基于 gameTimeMs 的速度**；后续 Enemy Motion 应用合法连续观测的位移/时间校准，保存时间基准并考虑加速、转弯、碰撞、停止、液体减速和运行倍率。

加减速常量也是原引擎控制参数；本轮不将其转成 SI 加速度、刹车距离或宣称其线性模型已验证。可优先用于规则解释/排序，精确运动预测需追踪积分路径并实测。

## 候选字段策略

已核验静态值可以作为 type catalog 的带版本候选；动态对象的合法观测优先。遇到不认识的移动类别、mod 替换、方法抛错或 SHA 不匹配，返回 UNKNOWN，不能回退为 LAND。AIR/NONE 的“地形计算跳过”与“可运动/可侦察/可下令”分开判断。

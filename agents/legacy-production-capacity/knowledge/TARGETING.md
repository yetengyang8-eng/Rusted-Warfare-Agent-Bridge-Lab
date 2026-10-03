# Targeting：能力兼容与射程

## 先区分五件事

攻击域兼容、路线可达、距离足够、武器此刻可开火、已经造成伤害是五个不同结论。Guard 只拒绝有证据明确不兼容的候选，不能把其它任何一个布尔值直接改名为“能击杀”。

## 原生攻击域

冻结 `game.units.y.k(am):boolean` 和 `custom.j.k(am):boolean` 的主要分支顺序：

```text
target.i()      -> attacker.af()       // air
else target.Q()-> attacker.ae()       // submerged
else if !attacker.ah() && !target.cH(): false
else           -> attacker.ag()       // surface
```

`custom/ag.java:2231–2241` 与 `custom/j.java:2370–2430` 对应关系：

| 配置 | 原混淆能力 | 解释 |
|---|---|---|
| canAttack | l() | 总攻击开关；必须单独使用 |
| canAttackFlyingUnits | af() | 原生当前 air 判定 |
| canAttackLandUnits | ag() | **表面域**：陆地和水面，不是仅 LAND 枚举 |
| canAttackUnderwaterUnits | ae() | 当前 submerged 判定 |
| canAttackNotTouchingWaterUnits | ah() | 默认 true；为 false 时表面域要求 target.cH() |

`am.cH()` 为 `cJ() && !(height>2)`；`cJ()` 走原生水域判断。它不是“target.movementType==WATER”。自定义 `j.i()` 要求运行 movementType=AIR 且 height>=4；`j.Q()` 检查 height<=-1。飞机落地、潜水/浮起等状态不能仅按 type id 推断。看不到当前状态时用 UNKNOWN 或带时间的历史状态。

`y.ae/af/ag/ah` 默认分别 false/true/true/true；这些继承值不能使 builder 或工厂拥有武器，因为它们 `l()==false`。本包对此输出有效 canAttack* = false，并保留 raw flag 说明。

## 核心能力候选

射程单位为世界单位；各原图格长20。下表是基础类型/配置值，不是当前实例的开火承诺。

| 类型 | 总攻击开关 | 表面 | 空中 | 水下 | 基础最大射程 |
|---|---|---|---|---|---:|
| builder | 否 | 否 | 否 | 否 | 不适用 |
| scout | 是 | 是 | 是 | 否 | 110 |
| c_tank | 是 | 是 | 否 | 否 | 130 |
| heavyTank | 是 | 是 | 是 | 否 | 160 |
| c_artillery | 是 | 是 | 否 | 否 | 290 |
| landFactory | 否 | 否 | 否 | 否 | 不适用 |
| airFactory | 否 | 否 | 否 | 否 | 不适用 |
| extractorT1 | 否 | 否 | 否 | 否 | 0 |
| extractorT2 | 否 | 否 | 否 | 否 | 0 |
| c_turret_t1 | 是 | 是 | 否 | 否 | 165 |

builder 的原始 `m()=30` 不进入攻击半径表；工厂 `m()=0` 同样不是一门零射程武器。extractor 的 0 是原配置值，仍须先看 canAttack=false。heavyTank 具有对空域，不能因为它走 LAND 就禁止对空；scout 同理。tank/c_tank 与基础炮塔不能攻击 air/submerged；水面目标仍属于它们允许的表面域。

## 射程与炮塔

`[attack]maxAttackRange` 在 `custom/ag.java:1988–1995` 乘 globalScale，未提供时先用 100*globalScale，再可能由炮塔范围逻辑调整。候选核心单位均有明写值，不能把这个默认泛化到所有类型。

`custom/bn.java:364–374` 解析炮塔 `limitingRange/limitingMinRange` 并计算平方距离阈值；`custom/j.java:1656–1704` 还检查角度、LogicBoolean、目标标签和各炮塔域。`custom.j.k(target)` 仍独立检查单位级域；普通目标选择路径不能用“炮塔 true 覆盖总开关 false”来扩展攻击域。NDT B376/D376 的“覆盖 [attack]”表述不够准确，PDF 第3页与原生分层条件一致。脚本强制开火/自定义动作不在本轮普通目标 Guard 结论内。

基础 `m()` 是最大攻击距离，不是所有武器的最小/最大有效区间。`y.i(am)` 遍历武器并调用分武器判定；`y.a(am,boolean)` 另检查敌对关系、死亡、命令姿态、运输、目标可被攻击性等。`y.b(boolean)` 还给搜索/追逐半径加入命令与姿态扩展，不能把这个扩展半径写回 weaponRange。

距离比较有平方距离、严格 `<` 与分炮塔 `>`/`<` 边界差异；近战可能另计碰撞半径。第一版 Guard 不需要把这些细节重新实现为万能 canFire，保持“域兼容”结果范围即可。

## 可直接采用的 Guard 合同

结果建议为 `COMPATIBLE / INCOMPATIBLE / UNKNOWN`，并附 `reason/sourceId/observedAt`。流程：

1. 校验游戏 SHA、类型解析和合法目标观测；检查 canAttack 总开关。
2. 从合法当前观察解析 AIR / SUBMERGED / SURFACE 与 touchingWater；状态缺失且会改变结论时返回 UNKNOWN。
3. 用已核验能力域明确拒绝；保留 LogicBoolean、标签、炮塔和动态修改约束的未知状态。静态兼容仅表示通过当前检查，不等于此刻能开火。
4. 混合编队按实际分配的攻击者集合处理：一个坦克不兼容不代表整支编队不兼容；至少一个具有可用证据的攻击者兼容时不能因为多数 LAND 单位而硬拒绝对空目标。
5. 对 UNKNOWN 延用现有合法目标流程或降权，不把未知转换成 false，也不追取迷雾内属性以消除未知。

建议日志最小内容：attacker type、target domain/source/time、三态结果、拒绝原因、catalog SHA。可复现用例见 `VALIDATION.md`；本轮没有执行 Guard 产品代码或真实战斗验收。

# Terrain：冻结地图与通行语义

2026-10-03 G4.1 更新：用户已授权独立的固定地图先验。运行时 `StaticMapKnowledge` 从当前加载的原版静态 tile layers 建立每局缓存；固定矿点、地形与静态连通可以在 fog 外已知，动态敌情/占用/安全仍 UNKNOWN，原生最终 guard 保留。见 [新契约](../docs/OCTOPUS_G41_STATIC_MAP_KNOWLEDGE.md)。以下保留 2026-09-29 冻结资料，其中“不预载未见地块/沿用可见边界”是当时 Recon 信息规则，不能覆盖新静态先验授权；原 JSON 仍仅作 oracle，未硬编码为运行时地图。

## 九图原始清单

全部地块尺寸为 **20×20 世界单位**。表中资源数是成功实例化的 `Items.res_pool` 格数；这九图与所有图层资源位置去重后的结果一致，总计 **98**。这是静态资源位置，不是未探索资源的实时观察。

| 通用名称 | 原版文件（`assets/maps/skirmish/`） | 格数宽×高 | 资源格 | PathingOverride |
|---|---|---:|---:|---|
| 登岛 | `[p2]Beach landing (2p) [by hxyy].tmx` | 200×160 | 20 | 无 |
| 巨岛 | `[p2]Big Island (2p).tmx` | 180×180 | 14 | 无 |
| 直岛 | `[p2]Dire_Straight (2p) [by uber].tmx` | 110×110 | 6 | 无 |
| 火桥 | `[p2]Fire Bridge (2p) [by uber].tmx` | 120×120 | 8 | 无 |
| 山丘 | `[p2]Hills_(2p)_[By Tstis & KPSS].tmx` | 130×130 | 20 | 无 |
| 冰岛 | `[p2]Ice Island (2p).tmx` | 145×145 | 7 | 无 |
| 湖陆 | `[p2]Lake (2p).tmx` | 130×130 | 9 | 无 |
| 小岛 | `[p2]Small_Island (2p).tmx` | 110×110 | 4 | 无 |
| 两极 | `[p2]Two_cold_sides (2p).tmx` | 135×125 | 10 | 无 |

原文件、外部 TSX 与每个依赖的 SHA、inline tileset 的 `firstgid` 均在 `TERRAIN_MAP_CANDIDATES.json`。九图的 `map_info` 均未显式写 `fog`；运行模式依赖游戏/对局设置，不能解释成无雾。

## 加载与坐标约定

`game/b/j.java:74–105` 先以 `tilesets/` 为根加载 external tileset。`game/b/b.java:1472–1506` 失败后尝试源路径的末 3、2、1 段。登岛引用的 `../rustedWarfareMaps-testing/misc.tsx` 原目标不存在，本轮确认可回退到 `assets/tilesets/misc.tsx`。不能简单按 TMX 所在目录拼接，也不能让同名 TSX 覆盖不同 `firstgid` 的定义。

TMX base64 数据按声明解压 gzip/zlib，little-endian unsigned 32-bit，顺序是 `y*width+x`。清掉 gid 高三位翻转标记 `0xe0000000` 后再找 tileset（有效 gid 掩码 `0x1fffffff`）；gid 0 是空格。引擎原生图层/代价数组则使用 **`x*height+y`**。非正方形的登岛与两极可揭露转置错误。世界坐标的格中心为 `((x+0.5)*tileWidth, (y+0.5)*tileHeight)`；输入先做有限值和边界校验。

图层绑定：`b.u=Ground`，`b.v/w=GroundDetails/GroundDetails2`，`b.y=Items/Objects`，`b.x=PathingOverride`。`b.e(int,int)` 返回 **Items/Objects** 格，绝不是 Ground。GroundDetails 不是本轮原生 `k.i.d()` 的地形通行输入。`Units`、`showFog`、`unit/customUnit` 是加载控制数据，不能直接当普通地块；`game/b/g.java:82–178` 处理后会返回 null。

## 属性到引擎字段

来源：冻结 `game.b.g` 字节码与 `game/b/g.java:191–232`。这里检查的是 property **存在性**，不是把 value 当布尔值；`water=false` 仍会置 water 标记。原始大小写键必须保留。

| TMX/TSX key | `g` 字段 | 当前静态结论 |
|---|---|---|
| water | e | 水标记 |
| water-bridge | f | WATER 特例允许格；不自动清除同格 water/cliff 等其他阻挡 |
| lava / lava-cliff | g | 岩浆；lava-cliff 另置 h |
| cliff / cliff-soft | h | 本成本路径中两者相同 |
| large-cliff / trees | k | 大障碍；跨崖类型有特例 |
| res_pool | i | 资源池位置；Items 中会阻 LAND |
| tree | 无有效赋值 | 单数键在该解析分支是空语句，不能当 trees |
| small-rock | j=40 | 正代价，可通行；不是 -1 |
| large-rock / block-land | j=-1 | 在读取该 j 的地面成本层中阻挡；不是只阻名为 LAND 的枚举 |
| block-buildings | l=true | 已证实解析存储；本轮未证实完整建造判定消费链，不直接声明其独立有效性 |

图像名或肉眼颜色不能替代这些属性。随机贴图变体不授权重新猜测 water/cliff。

## Ground、Items、PathingOverride 的准确顺序

权威入口：冻结 `gameFramework.k.i.d():void`；对应 `gameFramework/k/i.java:334–416`。以下仅是地形成本 `d[]`，不是完整可达性：

1. AIR/NONE 跳过这段地形计算。其他类型每格先清零。
2. Ground：按 water/cliff/largeObstacle/lava 与移动类型判断 -1；WATER 另要求 water 或 water-bridge。
3. Items：LAND 遇资源池 -> -1；不允许大障碍的类型遇 k -> -1；当前成本仍为 0 时才采用 Items.j。
4. 成本仍为 0 时采用 Ground.j。因此 Items 的 40 会保留，不被 Ground.j 覆盖。
5. 若有非空 PathingOverride 格，**先重置成本为 0，再单独按该格属性重算**；空覆盖格保留原成本。此处不重新应用 Items 资源阻挡。

因此覆盖格可以取消 Ground/Items 已产生的阻挡，不能实现成 `max(old,new)` 或一律 OR。反之，它也能把原可通行格改成阻挡。九图都没有该层，只能作为无覆盖的原始样本；对覆盖分支的本轮结论来自字节码/源码，尚无原生地图实测。

| 类型 | 允许 water | 允许 h（普通崖） | 允许 k（大崖/trees） | 额外条件 |
|---|---|---|---|---|
| LAND | 否 | 否 | 否 | Items 资源池阻挡 |
| BUILDING | 否 | 否 | 否 | 建造用成本类别；不是建筑实例的 h() 返回值 |
| WATER | 是 | 否 | 否 | Ground/override 非水且非水桥时阻挡 |
| HOVER | 是 | 是 | 否 | lava、j=-1 仍阻挡 |
| OVER_CLIFF | 否 | 是 | 是 | lava、j=-1 仍阻挡 |
| OVER_CLIFF_WATER | 是 | 是 | 是 | lava、j=-1 仍阻挡 |
| AIR / NONE | 不运行此成本段 | 不运行此成本段 | 不运行此成本段 | 不等于单位能移动或可建造 |

这不是任意属性组合的简化真值表：属性可以共存，按上述顺序保留所有限制。

## 候选地形统计

以下是本包 Python 镜像对原始属性的计算，**尚未与初始化后的引擎 d[] 逐格比对**；数字只用于复现/查错。完整 `-1/0/40` 分布和 RLE 在 JSON 中。

| 地图 | LAND 阻挡格 | HOVER 阻挡格 | WATER 阻挡格 |
|---|---:|---:|---:|
| 登岛 | 19880 | 1092 | 14158 |
| 巨岛 | 16432 | 0 | 17538 |
| 直岛 | 5569 | 304 | 7320 |
| 火桥 | 5376 | 2687 | 11719 |
| 山丘 | 396 | 376 | 16900 |
| 冰岛 | 10848 | 0 | 10878 |
| 湖陆 | 4246 | 8 | 13017 |
| 小岛 | 7725 | 0 | 4763 |
| 两极 | 10403 | 0 | 7822 |

每个 RLE 为 `[signedCost, runLength]`，解码后的顺序明确为 TMX 行优先。动态建筑、单位、clearance、寻路岛和视野全部排除；不能把这些文件直接当作当前路线答案。

## 资源、建造与实时接入

`game/b/e.java:84–95` 将实例化图层的资源位置去重登记到 `map.A`。当前 Agent 在 `ScoutBridge.recordVisibility` 和 `EconomyBridge` 里读取 `bL.e(c,r).i`，且先检查合法可见性；沿用这个边界。资源点坐标不是是否被占领、是否可采、是否可建造的证据。

`game/units/d/d.java:129–209` 的建造判断还涉及 fog、类型、资源专用要求、海军/移动类别与原生阻挡。`footprint`、`constructionFootprint`、已存在建筑和合法放置反馈要独立处理。建筑的单位移动类型 NONE 不代表使用 NONE 网格就能放置。

`gameFramework/k/l.java:88–118` 的 `a(i,x,y[,boolean])` 返回 **true=阻挡**，需避免反向命名错误；它合并 terrain `d[]`、building `e[]`、unit `f[]`。`b(i,x,y)` 成本合并为 `d+e+10*f`（遇 -1 提前失败）。不能把静态 terrainCost=0 说成整条路径可达。

给 Recon 的建议记录：`{tile, movementType, terrainFlags, terrainCostCandidate, status, lastLegalObservation, sourceHash}`。规则可以预载，格属性仍只由当前允许的合法观察写入；unknown 格保留 unknown。换局、换图或 game SHA 改变时清空/失效相应缓存。

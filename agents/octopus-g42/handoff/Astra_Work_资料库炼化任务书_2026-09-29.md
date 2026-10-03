# Astra Work 资料库炼化任务书｜2026-09-29

## 目标
只读扫描 `G:\deepseek 工作台\游戏资料`，把新增资料炼化成 Codex 可直接消费的工程证据包。
本轮不修改 Agent 产品代码，不改游戏环境，不改原始回放库。

## 首读入口
1. `G:\deepseek 工作台\游戏资料\README_资料库索引.md`
2. `00_版本基线\BASELINE.md`
3. `90_勘误与已核验事实\ERRATA.md`
4. `90_勘误与已核验事实\VERIFIED_FACTS.md`
5. `30_GitHub原始仓库\SOURCE_VERSIONS.md`

## 本轮只做五个专题
- Terrain / 地块与路径语义
- Movement / movementType、速度、加减速、转向
- Vision / Fog / sightRange
- Targeting / attack domains、目标兼容性、射程
- Core Units / 当前 Agent 会直接用到或遇到的核心单位能力

## 资料优先级
冻结 1.15 原版 game-lib / 原版 TMX与tileset > rw_analysis/Tape/反编译源码 > 1.15 Modding Reference > NDT/JSON社区语义表 > 其他社区资料。
社区资料只能作为线索；发现冲突必须保留，不允许静默“选一个看起来对的”。
## 输出目录
建议输出到：`G:\deepseek 工作台\助手交接\knowledge_packet_2026-09-29\`

## 必须交付
- `TERRAIN.md`：原版九图地形/PathingOverride/资源地块/通行语义与源码指针。
- `MOVEMENT.md`：movementType、速度、加减速、转向等字段，标注来源与验证状态。
- `VISION.md`：Fog模式、单位视野、未完成单位视野、盟友共享等；严格区分公开规则与动态隐藏真值。
- `TARGETING.md`：canAttackLand/Air/Underwater 等能力与攻击距离，解释“地面/水面”等语义边界。
- `UNIT_CATALOG_CANDIDATES.json`：仅候选值，不把社区资料自动当真值；每个字段附 source/status。
- `CONFLICTS.md`：所有互相冲突、版本不明、只在社区资料出现的字段与数值。
- `CODE_POINTERS.md`：Codex 实现时应优先看的类、方法、文档、TMX/tileset路径。

## 核心单位范围
至少覆盖 builder、scout、tank、c_tank、heavyTank、artillery、landFactory、airFactory、extractor/T2、基础炮塔；如源码命名不同请记录映射。

## 特别注意
- 已知勘误：火炮机甲 1400、特斯拉机甲 5200；不要被社区旧表覆盖。
- `G:\Steam\steamapps\common\铁锈回放处理` 永远只读；本任务不需要改动其中任何文件。
- 不要用危险反射调用去“验证名字”；先查映射、源码、字节码和既有资料。
- 不要把离线全局资料用于实时敌方隐藏状态。

## 完成标准
让 Codex 能在不重新遍历整个资料库的前提下，直接完成 Terrain Semantic Map、核心单位能力表、Target Compatibility 和 Terrain-aware Recon 的第一版实现。
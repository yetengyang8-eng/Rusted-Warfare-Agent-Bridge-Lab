# Codex 施工合同｜Knowledge-backed World Model / Capability v0｜2026-09-29

## 0. 本轮定位
本轮不是重新研究资料库，也不是再生成一份单位表。
Astra Work 已完成 `knowledge_packet_2026-09-29` 的静态炼化；Codex 的任务是：**验 → 接 → 用 → 回归 → 交付可玩候选版**。
用户本周 Codex 额度有限；优先完成一个完整闭环并保留收尾空间。

## 1. 强制本机资料预检
开始前先确认能通过 Desktop Commander / 本机文件工具实际访问 `G:\deepseek 工作台`。
必须实际读取：
- `G:\deepseek 工作台\助手交接\knowledge_packet_2026-09-29\README.md`
- `CONFLICTS.md`
- `VALIDATION.md`
- `CODE_POINTERS.md`
- `UNIT_CATALOG_CANDIDATES.json`
- `TERRAIN_MAP_CANDIDATES.json`

并在正式动工前报告已读取的真实本机路径。若无法访问 G 盘，立即停止；不得用附件、旧聊天记忆或猜测替代本机资料库。

## 2. 基线身份
冻结原版 `game-lib.jar` SHA256：`8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`。
当前可体验 Recon v0.2 Agent SHA256：`0c53bdfa1b545088e15fa6ab4269216b5c3fa586b00738bf00f5770ac5adc71a`。
版本字符串 `0.07-alpha1` 不能代替内容身份；安装、验收、交付均记录 SHA/contentDigest/lineage。

## 3. 第一优先：Target Compatibility Guard v0
把 Work 已炼化的 capability catalog 接入现有 CombatBridge/BattleClient，不重建 catalog。
输出三态：`COMPATIBLE / INCOMPATIBLE / UNKNOWN`，附 reason/sourceId/observedAt/catalog identity。
至少覆盖：builder、scout、tank/c_tank、heavyTank、artillery/c_artillery、landFactory、airFactory、extractor T1/T2、基础炮塔。

必须正确处理：
- tank/c_tank 明确拒绝 AIR、SUBMERGED；允许合法可见的 SURFACE（水面也属于 surface）。
- heavyTank 与 scout 可对空；不能因 movementType=LAND/HOVER 误判攻击域。
- builder/factory/extractor 的继承 raw flag 或 raw range 不能制造虚假武器能力。
- 混合编队按实际可分配攻击者集合判断；tank+heavyTank 遇 air 时不能整体误拒绝。
- 动态 air/submerged/touching-water 状态只有合法当前观测才可用；不足返回 UNKNOWN，不偷读迷雾状态。
- domain compatibility 不是 canFire/可达/在射程/能击杀；不要把 Guard 扩成万能战斗判定器。

Guard 必须真正进入目标候选或命令分配路径，至少能阻止明确错误追击；UNKNOWN 采用保守但不死锁的现有流程/降权策略。

## 4. 第二优先：Terrain Semantic Memory v0
Work 已有九图完整离线解析和地形候选，不要重新遍历地图仓库生成另一套报告。
将现有 `ScoutBridge` 的合法可见格记录从单纯 passable/blocked 扩展为可解释地形记忆，例如：
`water / water-bridge / cliff / large-cliff / lava / trees / small-rock / large-rock / block-land / resource / terrainCost/status/source/time`。

合法性必须维持当前规则：离线九图全图可用于规则、测试基准与对照，**不能因 TMX 已知就预填实时未探索地形**。
静态 terrain、动态建筑/单位占用、reachable/path result 必须分层，不能把 terrainCost=0 直接叫“可达”。

## 5. 第三优先：Terrain-aware Recon 接入
保留 Recon v0.2 已有的 `RECHECK_INTEL / FRONTIER_SWEEP / EXPENDABLE_SCOUT / 主力保留 / threat corridor`。
不要重写 Recon 架构，只让 frontier/路线评估消费新的 terrain semantics + movementType。
目标是出现可解释差异：LAND 与 HOVER 对同一已知地形得出不同的候选/路线判断；遇水、崖、大障碍时理由可记录。

禁止借此顺手展开 SUICIDE_PROBE、复杂逃逸、侦察生产替代、经济大改、多人/self-play、Replay recorder。

## 6. 先验两项关键静态结果
Work 的资料包已经确认并纠正多项旧误区，开发时以 `CONFLICTS.md` 为入口，禁止重新采用旧错误映射。
尤其注意：movement enum 为 `NONE/LAND/BUILDING/AIR/WATER/HOVER/OVER_CLIFF/OVER_CLIFF_WATER`；Fog 原生字面量为 `none/map/los`；`map.M/N` 不是玩家动态Fog；`canAttackLand` 是 surface 域而非只 LAND。

## 7. 必做 E2 / 原生核验
A. Terrain cost 对照：Work 的 Python terrain grid 仍是静态候选。请安全、只读地把至少若干地图/移动域与初始化后的原生 terrain cost `d[]` 做对照；优先包含非方图，防止 x/y 转置。
B. PathingOverride：九图全部没有该层。为此创建最小隔离测试资产，验证：原阻挡可被非空 override 清零重算为可通行；原通行可被 override 改成阻挡。不要声称九图天然覆盖此分支。
C. Target Guard：至少覆盖 tank→air拒绝、heavyTank→air兼容、tank→surface water兼容、tank→submerged拒绝、builder无武器、混编队列、UNKNOWN动态状态。
D. Recon：覆盖 LAND/HOVER 地形差异、unknown地形保留、session/map切换失效缓存。

## 8. 原生/headless行为展示
完整 regression 必须通过；至少跑一局原生/headless展示候选版没有破坏既有 Economy/Builder/Recon 主链。
若实局自然触发错误追击或特殊地形分支，保留行为链证据；若没触发，明确标记 `E2-only`，不要伪装成 E4。
日志最少回答：为何目标被拒绝/接受/UNKNOWN；为何 frontier 被选中或拒绝；用了哪个 capability/terrain 证据。

## 9. 安全与资料边界
禁止未知危险反射调用；P0事故附近字段/方法先查已有证据，owner+descriptor+继承链不清楚就停在 UNKNOWN。
敌方动态位置、潜水/飞行当前态、升级、建造进度、动态 stats 仍受合法视野约束；静态 type catalog 不得刷新隐藏实例。
原始回放库 `G:\Steam\steamapps\common\铁锈回放处理` 只读；如需实验必须复制到资料库 Replay 副本目录。
原始资料/GitHub仓库默认只读；产品代码只由一个 owner 修改。

## 10. 本轮不再重复做的事
- 不重新建立一份核心单位百科；直接消费 `UNIT_CATALOG_CANDIDATES.json` 并做运行时接入/核验。
- 不重新解析九图；直接消费 `TERRAIN_MAP_CANDIDATES.json` 与 `terrain_grids/` 作为离线证据和测试基准。
- 不无目的遍历七个 GitHub 仓库；需要追证时优先从 `CODE_POINTERS.md` 精确跳转。
- 不用社区资料覆盖冻结 JAR/INI/原生运行证据。

## 11. 额度策略与停止条件
本轮优先产出可运行候选版，留出编译、安装、回归、实机修复额度。
GREEN 范围可连续推进多个内部循环，不必每个小步等待 DeepSeek。
若出现架构冲突、P0/状态污染、两次独立实现失败、必须显著扩大范围，则停止并交接，不要靠耗尽额度硬冲。

主目标完成且候选版干净后，如仍有明显余力，只允许做 **Enemy Motion v0 的数据接口准备**：连续合法位置、gameTimeMs、type maxSpeed/sightRange、时间基准标记；不要直接展开完整预测策略。

## 12. 交付
交付 source/dist/P1F 三方身份、contentDigest、JAR SHA、lineage，确保安装候选与验收候选一致。
handoff 简洁回答：完成什么、没做什么、哪些是 E1/E2/E4、实局观察到什么、是否存在 UNKNOWN/合法性降级、下一步建议。

### 本轮成功定义
资料库第一次真正改变 Agent 的实时目标选择和地形理解，同时保持无隐藏信息泄露，并留下可复用的 Capability + Terrain 基础层。

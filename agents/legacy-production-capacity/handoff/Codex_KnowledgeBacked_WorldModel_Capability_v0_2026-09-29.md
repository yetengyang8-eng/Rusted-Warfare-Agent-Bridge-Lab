# Codex 下一轮加速合同｜Knowledge-backed World Model / Capability v0｜2026-09-29

## 背景
资料库已扩充，当前目标不是继续堆文档，而是把资料直接转成 Agent 可见行为。
用户本周 Codex 额度有限：本轮请优先做高价值产品推进，并保留足够收尾空间。

## 基线
- 资料入口：`G:\deepseek 工作台\游戏资料\README_资料库索引.md`
- 当前可玩 Recon v0.2 Agent SHA256：`0C53BDFA1B545088E15FA6AB4269216B5C3FA586B00738BF00F5770AC5ADC71A`
- 冻结原版 game-lib SHA256：`8A550A37E2D8A5430866090D4E7D5892F9010B47F52A5A09350FC66C620DEEC9`
- 先读 BASELINE / ERRATA / VERIFIED_FACTS / SOURCE_VERSIONS。
- 如 `knowledge_packet_2026-09-29` 已存在，优先消费；不存在则针对任务定向查资料，不要无目的遍历全库。

## 主目标 A：核心单位能力真值 v0
建立当前实际需要的 verified/candidate capability catalog。
至少覆盖 builder、scout、tank、c_tank、heavyTank、artillery、landFactory、airFactory、extractor/T2、基础炮塔。
优先字段：cost、movementType、maxSpeed、sightRange、attackRange、canAttackLand/Air/Underwater、building/mobile。
每个字段保留 source/status；社区数值不得未经核验直接进入策略核心。

## 主目标 B：九图 Terrain Semantic Map v0
基于冻结原版 TMX/tileset 与源码语义，理解 LAND/WATER/CLIFF/LAVA/ROCK/RESOURCE/PATHING_OVERRIDE 等。
正确处理 Ground 与 PathingOverride 的叠加，不要只看视觉地块。
输出 Agent 可消费的地形/通行语义，而不是只生成离线报告。
## 主目标 C：把知识真正用进行为
至少落地两个可见行为：
1. `Target Compatibility Guard`：主力不再追逐自身能力明确无法攻击的目标；优先使用能力语义而不是硬编码 unitName 特例。
2. `Terrain-aware Recon`：RECHECK_INTEL / FRONTIER_SWEEP 选择与路线评估开始理解地形与 movementType，而不是只依赖低层 passable 记忆。

如果上述三项推进顺利，可自行继续扩展，但优先保证本轮形成可运行、可回归、可实机观察的候选版。

## 可选延伸（有余力才做）
为 Enemy Motion Belief / Threat+Vision Field 做数据接口准备：连续合法观察位置、时间、估算方向/速度、公开最大速度和 sightRange。
不要为了“顺手”把 Enemy Motion、复杂逃逸、SUICIDE_PROBE、多人/self-play、Replay recorder、经济大改全部一起展开。

## 约束
- 实时敌情必须遵守现有合法视野；资料库只提供公共规则与静态能力，不提供迷雾里的动态真值。
- 未探索地形如现有合法性要求禁止预读，则不要因为离线 TMX 知道全图而泄露给实时决策；可将静态地图知识的合法使用边界明确记录。
- 禁止未知危险反射调用；P0事故附近先查既有证据。
- 原始回放库 `G:\Steam\steamapps\common\铁锈回放处理` 只读。需要实验必须复制到游戏资料的 Replay 副本目录。
- 一个产品代码 owner；子智能体可做只读资料检索、测试、审计、文档。

## 验收重点
必须有完整 regression；新增确定性 E2 覆盖能力兼容、地形语义、PathingOverride、Recon 选点/拒绝非法目标等关键分支。
至少跑一局原生/headless实战，展示：
- 能力兼容机制实际阻止一次错误追击，或在当前样本未自然触发时诚实标为 E2-only；
- Recon 对地形/移动域的选择发生可解释变化；
- 不出现隐藏信息泄露、主力阈值破坏、侦察角色回归问题。

日志要能回答：为什么这个目标被拒绝、为什么这个 frontier 被选中、用了什么 capability/terrain 证据。
不要从单局胜负宣称战略提升。

## 身份与交付
交付 source/dist/P1F 的 contentDigest / JAR SHA / lineage，确认安装候选与验收候选一致。
产出一份简洁 handoff：完成了什么、没做什么、自然实局观察到什么、哪些仅 E2、下一步最自然的方向。

## 自主推进权限
这是加速轮。GREEN 范围内可连续推进多个内部循环，不必每个小步停下来等 DeepSeek。
如果主目标提前高质量完成，可自行决定是否把剩余时间用于 Enemy Motion 数据接口或修复暴露出的高价值问题。
遇到架构冲突、P0/状态污染、两次独立失败、明显扩大范围时再停下交接。

## 本轮成功定义
不是“生成一份单位表”，而是：资料库首次真正改变 Agent 的地图理解与目标选择行为，并留下可复用的能力/地形基础层。
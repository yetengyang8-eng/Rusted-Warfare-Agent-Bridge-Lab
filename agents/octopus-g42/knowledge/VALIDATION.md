# 验证记录与下一轮验收

## 本轮已做

`STATIC_VERIFICATION.json` 记录 **100 项通过的静态证据/制品一致性检查**。其中有方法定位和常量检查，不应理解成100个独立引擎测试。

- 534 个源/证据快照文件的大小与 SHA 全部一致；来源、repo HEAD 与冻结 JAR 身份已记录。
- 七份必交文件齐全；10 个规范单位、14 个名称入口、206 个候选字段均有 `value/status/source`，来源文件和行号有效。
- 九图 TMX、external/inline tileset 依赖全部解析；72 份 movement-grid RLE 长度和直方图一致；原图均为20×20格。
- Items 资源格与全图层实例化资源位置去重一致，总计98；九图均无 PathingOverride。
- 冻结字节码核对包括 movement enum、builder条件速度/转向与非战斗m值、heavyTank参数、工厂与builder成本、默认视野与未完成哨兵、Fog player.N/阈值、渲染缓存、覆盖层重置指令。
- mechArtillery=1400、mechLightning=5200，以及extractor升级1400/类型价2100，均与原始生效INI键一致。

字节码摘录：`evidence/bytecode_witnesses.md`。字段细节以候选表的状态为准；基础静态配置、继承默认和派生有效能力分开。

## 本轮未做

没有运行游戏、headless比赛、未知方法探测、产品回归或新E4验收。没有改Agent源代码、原版assets、游戏JAR、原知识文档或原始回放。没有把候选网格与活引擎d[]逐格比对。

旧handoff日志按原字节保留（部分是PowerShell UTF-16LE），只用作历史上下文；不能把其中的旧Agent SHA、无地图夹具、THREW/null或raw endpoint结果当当前可玩版本验证。

## Codex 接入时的验收矩阵（尚未执行）

| 主题 | 应执行用例 | 需要证明的结果 |
|---|---|---|
| 数据身份 | 更换game SHA；相同VERSION不同Agent SHA | 候选失效/明确版本提示，不静默套用 |
| 名称解析 | tank与c_tank；原生夹具与完整bundled assets | 记录实际resolved type，别名不抹除实例来源 |
| 无攻击者 | builder原始m=30、工厂继承af/ag=true | Guard仍认定无武器，不误做攻击半径 |
| 攻击域 | tank对air拒绝、heavyTank对air可通过域、tank对水面可通过域、对水下拒绝 | 不用movementType代替target domain |
| 动态目标 | 悬浮/升降/潜水、当前状态缺失、进入迷雾 | 只使用合法当前状态；不足则UNKNOWN |
| 混合编队 | tank+heavyTank对air | 按实际分配的兼容攻击者处理，不能整体误拒绝 |
| 炮塔限制 | 单位域允许但炮塔限制拒绝；反向单位域拒绝 | 域兼容不越权声称可开火；保留分层限制 |
| 原地图 | 九图全部加载；登岛/两极非方图；登岛旧TSX路径 | 图层/gid/行列正确；逐移动域比对原生静态成本 |
| 特殊属性 | water、桥与water共存、cliff、trees/tree、small/large-rock、resource | 属性存在性、叠加与40代价正确 |
| 覆盖层 | 空覆盖、非空可通行覆盖取消原阻挡、覆盖新增阻挡、取消Items资源阻挡 | 与原生清零重算一致；九图无此层，需另造隔离测试资产 |
| 网格状态 | 静态terrain可通行但建筑/单位占用；边界外 | terrain/passable/reachable分开；原生true=blocked不反用 |
| Fog/视野 | scout完成22/未完成15、运输/附着、不同联盟玩家、越界 | 既有合法观察条件保留，map缓存不冒充玩家真值 |
| Recon | RECHECK_INTEL与FRONTIER_SWEEP、未知地形、换局 | 地形只来自允许的合法观察；优先级/角色/预算约束不回归 |
| 时间基准 | 连续合法位置+gameTimeMs、不同runner倍率 | 基础速度与实际速度分开，标记转换与估计方法 |

产品合同还要求完整regression和至少一局原生/headless行为展示。若错误追击或特殊地形分支未在实局自然触发，只能报告E2-only；单局胜负不能证明策略改善。

## 复现本包静态制品

在解压后的本包副本中，使用Python 3标准库：

```bash
python tools/build_packet_data.py
python tools/verify_packet.py
```

第一条仅重建本包候选JSON、地形RLE与源清单，第二条核对并更新静态检查报告/字节码摘录；不运行引擎、不访问原Windows源目录。源证据快照保持原样。文本说明由人工综合，脚本不会自动改写这些判断。

生成器只支持本次核心INI用到的单父copyFrom和当前九图格式；遇多父继承、缺失依赖或不支持编码会显式失败，不应当作通用Mod解析器。候选地形cost镜像是可审计推导，仍需独立原生比较。

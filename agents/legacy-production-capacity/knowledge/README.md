# 冻结 1.15 工程知识证据包

日期：2026-09-29。任务入口：`Astra_Work_资料库炼化任务书_2026-09-29.md`。本包完成资料炼化；产品实现仍由下一轮 Codex 合同负责。

## 先读与使用

1. 先读 `CONFLICTS.md`，避免继续使用错误的 movement 枚举、Fog 缓存和价格解释。
2. 核心单位直接读取 `UNIT_CATALOG_CANDIDATES.json`：10 个规范类型、14 个名称入口，每个能力字段均有 `value/status/source`。
3. 地形读取 `TERRAIN.md` 和 `TERRAIN_MAP_CANDIDATES.json`：九图尺寸、图层、资源点、tileset 依赖、原始属性及候选地形代价。详细 RLE 网格位于 `terrain_grids/`。
4. 用 `MOVEMENT.md / VISION.md / TARGETING.md` 理解字段边界；从 `CODE_POINTERS.md` 找到当前 Agent 的接入位置和冻结字节码。
5. `VALIDATION.md` 说明已检查什么、哪些仍需产品侧 E2 / 原生验收。

## 身份

| 对象 | SHA256 / 状态 |
|---|---|
| 冻结原版 `game-lib.jar` | `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9` |
| P1F `game-lib.jar` | 与原版一致；本轮直接读取核对 |
| Tape `1.15/game-lib.jar` | 与原版一致；仅 JAR 一致不代表该仓库所有源码都与之相同 |
| 当前可体验 P1F Agent | `0c53bdfa1b545088e15fa6ab4269216b5c3fa586b00738bf00f5770ac5adc71a`，Recon v0.2 |
| 开发目录旧根目录 Agent JAR | `0681b4f7ef632a9c9372fff418fec591f240894fd99373ff6c7ca3b72d4a9eb1` |

两个 Agent 均可能显示 `VERSION=0.07-alpha1`；不能用版本字符串取代内容身份。当前源码摘录来自开发目录的 `developer/src`，不声称该源码与任何已安装 JAR 经本轮重编译比对一致。

原版目录：`G:\deepseek 工作台\游戏环境\rustedwarfare PC 1.15 原版`。
资料目录：`G:\deepseek 工作台\游戏资料`。
当前源码：`G:\deepseek 工作台\游戏环境\Rusted-Warfare-1.15-Agent-0.07\developer\src`。

## 证据与限制

本轮直接使用 Desktop Commander 读取当前 Windows 文件，静态反汇编冻结 JAR；没有创建游戏实例、执行未知反射方法、运行对局或改变 Agent。游戏环境、原资料和回放库均未改动。

- **E1**：原版 INI/TMX/TSX、冻结字节码、源码 trace。候选表中的 `VERIFIED_STATIC_*` 只表示静态值已查实。
- **静态推导**：默认值、继承、有效攻击域、地形成本镜像。`DERIVED_*` 不等于运行中观察。
- **参考/线索**：PDF、NDT、社区 JSON、中文机制库；不会独立授权策略数值。
- 本轮没有新增 E4。结构与哈希检查不冒充引擎回归；历史日志按原版本保留，不升格为当前候选验收。

每个文件的原路径、大小和 SHA 见 `SOURCE_MANIFEST.json`。正文中的 `game/...`、`gameFramework/...` 指 `evidence/sources/rw_analysis/02-decompiled/com/corrodinggames/rts/` 下的原混淆名源码；行号按原文件换行计。字节码在 `evidence/sources/bytecode/`。源文档中的旧结论仍原样保留，以本包勘误为入口。

`evidence/sources/` 是本次定向提取的证据快照，不是可运行游戏发行包；未复制图像、声音或游戏 JAR。地图的图像引用不参与本次属性解析。

## 实现边界

离线地图、类型常量属于规则证据。运行时仍要先遵守现有观察权限：未探索格不能因本包有完整 TMX 就预填，敌方单位的当前位置、潜水状态、建造进度、升级和射程变动都不能由离线常量冒充。建议每条派生记录带 `source/status/sessionId/observedAt`，将静态规则和合法观察分开。

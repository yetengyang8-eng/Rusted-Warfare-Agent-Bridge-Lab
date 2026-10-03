# Codex 交接｜KnowledgeBacked World Model / Capability v0｜2026-09-29

执行主合同：`Codex_KnowledgeBacked_WorldModel_Capability_v0_施工版_2026-09-29.md`。开工前通过本机文件工具读取了 `G:\deepseek 工作台\助手交接\请Codex开始_KnowledgeBacked_v0_2026-09-29.md`、主合同、`knowledge_packet_2026-09-29` 的 README/CONFLICTS/VALIDATION/CODE_POINTERS 和两份 JSON；没有用旧聊天内容代替本机资料。

## 交付身份

| 项 | 值 |
| --- | --- |
| 当前内容身份 | `82438a7b373111dca88b38e7a1c2779527a9cef136cdb9a1297bdf4c511273a7` |
| P1F 安装件 = `developer/dist` = 原生局 staged JAR SHA256 | `5741e241ce8ec1b921f36ed3274087609d0430f9281c44f6f62c096de84c5f54` |
| 冻结 `game-lib.jar` SHA256 | `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`，未改 |
| 冻结环境根 Agent SHA256 | `0681b4f7ef632a9c9372fff418fec591f240894fd99373ff6c7ca3b72d4a9eb1`，未改 |
| 谱系 | A/B 重建内容相同；`aVsInstalled=[]`、`aVsDist=[]`，见 `evidence/candidate_sha_lineage.json` |

旧 P1F Recon v0.2 JAR 已备份到 `C:\Users\Administrator\Documents\Codex\2026-09-27\g-deepseek\work\p1f-before-knowledge-backed-v0-20260929.jar`，SHA256 `0c53bdfa1b545088e15fa6ab4269216b5c3fa586b00738bf00f5770ac5adc71a`。回退时复制这份到 P1F 的 `rw-agent-bootstrap.jar` 并复核 SHA。没有启动或修改用户桌面游戏。

## 完成的接入

1. `TargetCatalog` 直接消费资料包的单位能力 JSON，封装 `COMPATIBLE / INCOMPATIBLE / UNKNOWN`、reason/source/time。`CombatBridge` 只对合法可见敌人采当前 AIR/SUBMERGED/SURFACE 与触水状态。`BattleClient` 在目标候选与攻击者分配中应用 Guard：排除明确不兼容，混编只派兼容子集，UNKNOWN 保守降权但保留旧流程；日志记录来源与实际派出 ID。
2. `ScoutBridge` 给合法可见格保存 native 地形标志、按 movementType 的 `d[]` 地形代价、时间与来源；与动态占用和可达性分层。未探索格保持 UNKNOWN，会话切换清缓存。`TerrainSemantics` 使用资料包已核验规则；`terrainCost=0` 只表示该层代价，不能声称可达。
3. Recon v0.2 的原有任务与主力保护逻辑保留；frontier/路线评估读取地形与 movementType，计划和任务日志记录 terrain status/source。遇到缺少路径网格的观察保持 UNKNOWN，不使 `/scout/observe` 报 503。

## 核验等级

- **E1**：资料包静态证据与冻结游戏身份核验；单位目录 SHA256 `263cdaaa3e923e8307c37f059e50bee529b8ecd7c3e215937ce2adf615a0e236`，安装 JAR 中同字节；地形包 SHA256 `ec38ef06c7ba378db7d4eb420a087f5205183a0bf02df400085db4d80196beb9`。
- **E2**：改前完整回归 34/34 步，改后完整回归 37/37 步（19 Java harness、15 Python 套件及 3 构建步骤），失败 0。Guard harness 83 checks、Terrain Memory 82、Scout 160；Target 客户端 6/6、Recon frontier 16/16。原生 `d[]` 对照 3 张复制地图和隔离 PathingOverride 小资产，6 移动域、438,450 格值全匹配；override 将两格 LAND 代价分别从 -1→0、0→-1。LAND/HOVER 同格差异、隐藏格 UNKNOWN、缓存失效及能力边界都有确定性覆盖。
- **E4（隔离原生，无画面）**：当前 P1F 安装件在 Big Island (2p)、难度 1、请求 4x 的一局达到 `PASS / VICTORY`。原生战报 SHA256 `211839c4c2953deaa4969c95a085d058018589769d5b59aa3c7a0fd7d0011cc1`；独立审计 `PASS`、`issues=[]`，episode、verified report、战报 provenance、staged JAR 与冻结游戏身份吻合。原生主链有 797 次观察、209 条命令、4 座矿、2 座新厂、121 条确认的攻击命令、1 个 Recon 任务及 1 条观察到的 Recon 命令。

实局自然记录 86 条 Target Guard 决策：兼容 78、UNKNOWN 3、不兼容 5；AIR 2、SUBMERGED 5。两次混编对空只派 heavyTank 等兼容者，`c_tank` 被排除；潜水 `lightSub` 的不兼容判断派出空集合。3 次 UNKNOWN 是动态状态已不再为当前观测，走保守旧流程。Frontier 计划共 2 次：一次因已知威胁无可用前沿；一次选出带 `KNOWN` 地形来源的 LAND 路线，`unknownCostTiles=0`。本局没有创建 frontier 任务，因此不能把该路线称为实局侦察完成。

## 边界与下一步

LAND/HOVER 同格差异与 PathingOverride 在 E2 已验证，本局未自然触发，仍标为 **E2-only**。一局原生胜利不能证明策略带来因果胜率提升；用户桌面实机仍未验收。动态敌方状态不刷新迷雾记忆，来源或当前观测不足返回 UNKNOWN。没有扩展到 SUICIDE_PROBE、经济大改、多人、自博弈或 Replay recorder。

建议先由 DeepSeek 按 `evidence/knowledge_backed_v0_evidence.txt`、`knowledge_backed_v0_native.json`、`knowledge_backed_v0_terrain_e2.json` 与 raw ZIP 独立验收。若做下一轮策略，请先交接裁决；本轮不自行扩展语义。

证据目录另有最终回归日志、候选 JAR 和带逐文件 SHA 清单的源码/原生原始报告 ZIP。`CURRENT_STATE.md` 两份副本、`EVIDENCE_INDEX.md`、`CHATGPT_HANDOFF.zip` 已同步更新。

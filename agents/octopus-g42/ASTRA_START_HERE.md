# 当前 G4.1 接手入口 — 2026-10-03

当前分支codex/octopus-g3-execution-20261002；起点5562ec7，测试源码39a2670；Windows71/71、39Java、29Python/473tests、同Jar native657checks绿。零军队FORMING→ACTIVE、静态地图缓存、原生合法扩张与轻量screen已接线。自然局/ready施工NEEDS_EVIDENCE，未deploy/push/进入G5。

先读[CURRENT_STATE](project-state/CURRENT_STATE.md)、[G4.1 handoff](handoff/HANDOFF_Octopus_G41_2026-10-03.md)、[Early契约](docs/OCTOPUS_G41_EARLY_OPERATIONS.md)、[Static契约](docs/OCTOPUS_G41_STATIC_MAP_KNOWLEDGE.md)。下一建议G4.2自然隔离核验。子智能体Sol6.1/high，禁Astra。下文完整保留历史导航，旧current/latest仅对旧阶段有效。

---

# 当前 G4 接手入口 — 2026-10-02

从当前最新G4交付继续：分支codex/octopus-g3-execution-20261002，测试源码54b9de1；Windows68/68、37Java/28Python466 tests、同JAR原生298checks绿灯。首遍日志兼容失败及修正完整重跑均保留；未部署/push/自然验收，未进入G5。

先读[CURRENT_STATE](project-state/CURRENT_STATE.md)、[G4 handoff](handoff/HANDOFF_Octopus_G4_2026-10-02.md)、[force契约](docs/OCTOPUS_G4_FORCE_LIFECYCLE.md)。force batch有真实priority，其他lane仍immediate；入口零兵无General自动birth。子智能体Sol6.1/high或用户指定同级，禁Astra；旧文件名不代表模型授权。

以下内容完整保留为历史导航，其中current/latest/next只对各历史阶段有效。

---

# 当前 G3/G3.5 接手入口 — 2026-10-02

从当前最新G3/G3.5交付继续：最终测试源码95a917e，分支codex/octopus-g3-execution-20261002。有效63/63验证（历史fixture环境补验另列），未部署/自然验收，未进入G4。

请先读[CURRENT_STATE](project-state/CURRENT_STATE.md)、[G3 handoff](handoff/HANDOFF_Octopus_G3_G35_2026-10-02.md)及[契约](docs/OCTOPUS_G3_EXECUTION_INTENT.md)。子智能体优先gpt-6.1-sol/high，禁Astra；旧文件名不代表模型授权。下面仅保留历史导航，current/latest/next表述以本段与当前project-state为准。

---

# Shared Agent Start Here

当前工程断点为 **Octopus G2**；源码 `5dd76e41`，分支 `codex/octopus-g2-world-state-20261002`，起点用户验收的 ff693c8。当前仓库的 docs 提交可能在源码之后，构建身份以候选 manifest 为准。

这个历史文件名是共享导航入口。后续子智能体优先 `gpt-6.1-sol / high`，不使用 Astra。远程 GPT/DeepSeek 等审阅者不能假定可以访问用户本机路径；共享摘要和证据已在仓库中，完整 raw 的本地路径及哈希单独列出。

建议阅读顺序：

1. `AGENTS.md` 与 `project-state/BASELINE.md`、`baseline-manifest.json`（冻结基线仅用于血统核对）。
2. [CURRENT_STATE](project-state/CURRENT_STATE.md)与[NEXT_STAGE_PLAN](project-state/NEXT_STAGE_PLAN.md)。
3. [G2 handoff](handoff/HANDOFF_Octopus_G2_2026-10-02.md)、[WorldState/Event contract](docs/OCTOPUS_G2_WORLD_STATE.md)、[evidence](evidence/octopus-g2-2026-10-02/README.md)。
4. [G1 Trace](docs/OCTOPUS_G1_TRACE.md)与[保留上下文](project-state/ASTRA_CONTEXT.md)，然后看本次任务涉及源码。

G2 完整 Windows 60/60、448 Python tests，WorldState 73/独立 14 checks；五正常三方对照及四 guard 三方对照，共 27 raw reports。NOT_DEPLOYED；自然局/桌面验收 NOT_RUN。现有命令 gate、策略、资金/slot 未迁移。G3 是下一接口建议，需用户单独授权。历史生产扩容/战争方向及更旧 orientation 文件均不自动成为当前任务。

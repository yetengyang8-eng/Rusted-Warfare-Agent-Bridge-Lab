# Codex：准备下一轮受控并行 Sprint（2026-09-27）

DeepSeek 已完成对「固定条件 A/B 对比 v0」的独立验收，结论 ACCEPT（E2+E3）。现在可以准备下一轮正式工程。

## 1. 主方向
下一步优先做 **A/B campaign v0**：把现有 profile 注入、双实例并行、crash recovery、aggregate_ab 组合成可无人值守重复实验的编排层。

我预期的核心能力包括：
- 给定 N 后自动交替 AB / BA；
- 逐批维护 campaign.json；
- 支持断点续跑与幂等，只补缺口；
- 正确区分「编排失败」与「比赛正常 PARTIAL / NO_NATIVE_RESULT」；
- campaign 收尾时一次性聚合 attempted / valid / partial / invalid / n；
- 不自动宣告哪个 profile 更好；
- 处理磁盘成本：当前每个 run 约 76MB，优先评估 Windows junction 替代 copytree，并加入磁盘预算/清理策略。

DeepSeek 还确认了迁址陷阱：不能只看聚合器 exit code，必须核 validBattleReports / invalidOrMissingReports / n；归档或迁移 run 时要保证 SHA 语义不被破坏。
## 2. 可并行的只读侦察方向
如果你判断并行有价值，可以自行决定是否开子智能体、开几个以及如何分工。优先考虑两类只读侦察：
1. **self-play feasibility**：调查同一局两个 Agent 分别绑定两个玩家/队伍所需的最小桥接接口、命令路由和风险边界；只读，不施工。
2. **seed feasibility**：只调查比赛创建前是否存在安全 seed 注入口；如果必须写运行中引擎内部状态，直接判为不适合主线，不施工。

不要求你固定采用上述分工；你可以根据代码结构和任务耦合自行决定最有效的智能体使用方式。

## 3. 子智能体使用原则
用户已经确认高额度消耗主要来自子智能体。请以**工程产出 / 额度效率**为目标自行控制并发：
- 能由主线程高效完成的工作，不必为了“全面”而额外开智能体；
- 若开子智能体，尽量让职责互不重叠；
- 主工作区避免多个写代码 owner 同时修改同一批文件；
- 只读侦察尽量只交文本结论，再由主 owner 合并；
- 如果继续增加智能体的边际收益已经很低，请主动收束，而不是机械扩并发。

## 4. 本轮边界
不要扩到 4 实例，不实现同局 self-play，不写引擎内部 seed，不改策略/经济语义，不重开 P0 / Diagnostic Lab。

你现在可以先阅读 `HANDOFF_对话47.md`、`对话47.txt` 和 `evidence/ab_v0_ds_acceptance.txt`，核对当前状态后开始施工。若发现架构分叉、范围需要明显扩大、连续失败或 live 异常，再停下来交接。
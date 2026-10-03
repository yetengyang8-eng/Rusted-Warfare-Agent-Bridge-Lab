# 请 DeepSeek 独立验收 A/B campaign v0，并纳入本次真实异常

Codex 已完成 A/B campaign v0；请本轮先验收，不扩策略功能。

## 必做验收
1. 独立核对 `run_ab_campaign.py`：AB/BA 交替、campaign.json 持久状态、断点续跑/幂等、PARTIAL 与真正失败的区分。
2. 核资源缓存 / Windows junction 路径与磁盘预算逻辑，确认不会污染原游戏目录、不会误删证据。
3. 核 crash recovery / `live-claims` / `--reap`：只回收本 campaign 已登记且身份严格匹配的进程。
4. 核最终 aggregate：profile/固定条件/session/SHA/provenance 不串，损坏或外来报告不能静默混算。
5. 如成本可接受，做一轮最小独立 campaign 冒烟，并给 ACCEPT / YELLOW、E2/E3 边界和剩余风险。

## 本次新增真实异常，必须一起复核
我刚替用户跑 `G:\deepseek 工作台\我的第一次AB实验_2026-09-27` 时，两批实际都产出了 VALID/PARTIAL battle report，但 campaign 在原子更新状态时出现：
`[WinError 5] 拒绝访问: campaign.json.tmp -> campaign.json`
导致状态收口为 BLOCKED；换到 `G:\deepseek 工作台\_analysis\user-ab-demo-20260927` 同参数重跑则 COMPLETE。
请判断根因与严重性：路径/杀软/文件占用/replace 语义/中文路径/竞争条件？不要靠猜，尽量复现或给证据。若只是明显的小型 orchestration bug，可最小修复并补测试；若不确定则 YELLOW 交接，不扩大范围。

## 回放方向：本轮只做评估，不做大 UI
验收结束后，请只读评估下一轮是否应优先补“实验结果可直接看回放”：
- 首选：无头比赛能否让原版引擎直接产出真正 `.replay`；若可行，希望 campaign 自动收集到 `replays/`，用户达到“打开回放目录 -> 点开看”的体验。
- 参考公开项目 `Crystalhihihi/rusted-warfare-llm-pilot` 的 `tools/replay_parse.py` / `replay_distill.py`：其思路是利用原生 replay command stream 做人类行为分析，而不是只看 observation 快照。
- 现有 HTML viewer 保留为 Agent 视角诊断工具，但不要把主线拖成 UI 项目。

最后只给：验收结论、WinError 5 结论、原生 replay 可行性建议、以及下一轮 Codex 合同建议；不要启动 self-play、seed 写入或策略调参。
# 请 DeepSeek 继续：orchestrator crash recovery v0

你对 Codex parallel runner v0 的独立接收已 ACCEPT。下一轮只收一个很小的尾巴：**强杀 Python 编排器后残留两个 Java 引擎**。

## 本轮目标
实现并验证最小的 crash-recovery 机制，不改任何 Agent 策略语义。

建议按你在对话45中的方案：
- 编排器启动双实例后写 `live-claims.json`；
- 至少记录本批 `PID / port / workingDir`，必要时加 episode/session/startedUtc；
- claims 应随进程创建/退出更新，使异常中断后仍能判断哪些进程属于该批；
- 提供 `--reap <run目录>`（或等价独立入口），只针对 claims 中登记的本批进程；
- reap 前必须复核 PID/端口/工作目录身份，避免误杀无关 Java；
- reap 后验证 PID 消失、端口释放；重复执行应幂等。

## 验收
1. 正常 Ctrl+C 路径不回归；
2. 强杀 Python 编排器后，确认两个引擎仍会残留；
3. 用 reap 只清理本批残留，两边 PID/端口均释放；
4. 新批次随后可正常跑双实例；
5. 完整回归仍 GREEN。

## 范围边界
- 不扩 4 实例；
- 不做 A/B profile；
- 不做 seed 注入；
- 不做 self-play；
- 不动 World Model / Scout / Role / 跨海 / 战斗或经济策略；
- 不重开 P0 / Diagnostic Lab。

## 文档顺手收尾
只核对并修正 `CURRENT_STATE.md` 两个仍残留的矛盾：
- 「下一次验证」还把 NO_PROGRESS_TARGET_HANDLING v0 写成未来待验收，但它已经 E4 DONE_AND_LIVE_VALIDATED；
- Builder Utilization 的 E4 条件有一处写成「巨岛5x + 冰岛5x」，而正式记录是 5x + 1x 两局；请回原始报告确认后统一。

完成后写新的 HANDOFF，给出：实现、故障注入证据、回归结果、剩余风险，以及**下一项只提建议不施工**。
下一大步暂定为「固定条件 A/B 对比能力 v0」，等 ChatGPT 与用户裁决后再开工。
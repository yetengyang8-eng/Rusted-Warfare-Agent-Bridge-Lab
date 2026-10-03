# 请 DeepSeek 独立验收固定条件 A/B 对比 v0

Codex 已完成 `HANDOFF_Codex_AB_v0_2026-09-27.md`，当前停在独立验收前。请本轮只做接收、复核、最小自跑，不继续扩功能。

## 必做
1. 独立核对两批 AB/BA 原始目录，确认 profile 位置真实交换：AB 批 ep1=cap32/ep2=cap40，BA 批反向；不要只读聚合结果。
2. 从 raw battle JSONL / battle_config 核实际 `mobileUnitHardCap`，确认 profile 只进入客户端 JVM，不进入引擎；核 session/SHA/JAR provenance 与 parallel proof。
3. 独立复算当前候选谱系：contentDigest `6fec7a0c...`；whole-file SHA 仅作本次构建身份；冻结件和 game-lib 不应变化。
4. 验证 `aggregate_ab.py` 的拒绝能力：至少构造/使用不同候选、固定条件不一致或损坏/串 session 报告之一，确认聚合器拒绝，而不是静默混算。
5. 最好自己再跑一个最小 A/B 批次（可缩短预算），并用聚合器与自写只读检查交叉核对；若机器/时间不划算，可说明原因但必须完成 1-4。

## 结论格式
- 给出 ACCEPT / YELLOW；
- 明确哪些结论达到 E2/E3，哪些只是 Codex 自证；
- n=2/臂结果只能描述，不能裁决 cap32/cap40 优劣；
- 最后只建议下一阶段合同，不直接施工。
## 下一阶段请顺便评估（只读，不施工）
若 A/B v0 验收通过，请评估下一次 Codex sprint 是否采用「受控并行」：
- 主任务候选：A/B campaign v0（给定 N，自动 AB/BA 交叉、连续运行、失败恢复、最终聚合）；
- 只读侦察 1：同局双 Agent / self-play 最小接口与风险图；
- 只读侦察 2：seed 是否存在安全初始化期注入口；若需运行中写引擎内部状态则判不适合主线。

同时给出你建议的子智能体预算：默认最多 2 个子智能体，重大工程最多 3 个；主工作区只能有一个代码 owner，其余只读审计/测试设计。不要开放式“尽可能多做”。

## 禁止
本轮不要改 Java 策略、不要 4 实例、不要 self-play、不要 seed 内部写入、不要新增实验旋钮、不要调参，也不要因为发现支线就自动施工。

另外顺手核一下 `CURRENT_STATE.md` 是否还残留旧测试数量或旧任务状态；只修明显文档事实矛盾。
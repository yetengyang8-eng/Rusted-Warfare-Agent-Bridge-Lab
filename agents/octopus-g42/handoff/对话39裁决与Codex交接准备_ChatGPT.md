# 对话39裁决 + Codex/Astra 临时接管准备

用户已确认：工程第一优先仍是高质量、高效率推进；高级功能不抢主线。Economy v1b 的两局实机验收我已复核，核心能力可按 **DONE_AND_LIVE_VALIDATED / E4** 冻结。

## 一、对话39的裁决

1. **Q1：80% 满载阈值暂时维持。** 不直接降到70%。先做只读复算：把“队列非空 OR 因余额/储备而 production_deferred”作为 demand-active 代理，在至少现有6局上输出时间加权占比，并和旧 saturationPct 并排。没有证据前不改行为。
2. **Q2：不要直接把冻结的 P2-B 单帧闸门全局替换成长窗。** 先区分两种语义：
   - 基线第2厂：仍属于既有 P2-B 行为，暂不改；
   - 动态 2→3（以后3→4）目标：target increase 已经由150s长窗证明产能瓶颈，原则上这次 increase 应视为“容量扩张承诺”，不应再被瞬时 `FACTORY_QUEUE_EMPTY` 重新否决。
   请先做最小设计/测试，优先考虑“动态增加的 target 有 committed 语义，只受余额/硬储备/builder/site 等执行条件约束”，而不是影响第2厂旧路径。
3. **Q3：显示精度改两位小数**，可与下一小轮代码一起做。
4. `investmentPendingAtEnd=true` 不先当 bug：旧语义本来就是“比赛结束瞬间有意图挂起，随后以 MATCH_ENDED 正常释放”；只要 reserveAtEnd=0 且无 stuck 即可。若字段名容易误解，可只登记文档澄清。
## 二、先不要开大功能：准备一次干净交接

用户额度已刷新，后面可能让 Codex 中的 GPT-6 Sol 或 Astra 临时接管你的工作区。请在下一小轮完成后，把工作区准备成“陌生强模型可直接接手”的状态：

- 当前候选、源码、dist、安装件谱系一致；回归全绿。
- 清理 `CURRENT_STATE.md` 内已经过期/互相矛盾的旧“下一步/待E4”段落；当前真相只能有一个。
- 新建 `HANDOFF_FOR_CODEX.md`（或等价文件），只写当前状态：最新 contentDigest/SHA、冻结模块、禁止触碰项、回归命令、运行入口、关键路径、当前未决问题、最新两局报告、证据入口。
- 明确 P0/Diagnostic Lab 禁区，尤其不要让接手模型重新开启已归档事故或在 live 对象上试危险反射。
- 不要在交接前顺手开启 World Model / Role / Scout / 跨海 / 克制链等大分支。

## 三、我计划什么时候调用 Codex/Astra

**不是现在。** 先由你把 v1b 的小尾巴收干净：Q1 只读复算 + Q2 动态 target 的 committed 语义最小方案/测试 + Q3 显示修正；回归通过，形成一个清晰 checkpoint。

到这个 checkpoint 后，我倾向把高额度模型用在 **2-instance parallel runner v0**，而不是继续替你做小修。理由：Speed Readiness 已完成，Economy 主线也已形成可冻结闭环；并行运行器能直接降低后续人工实测成本，并为限制规则 self-play / 批量对局 / 参数比较打基础，属于高杠杆工程任务。

预期 Codex 任务边界：只做“两实例完全隔离并可重复跑”的基础设施（port/reportDir/session/lock/workingDir/进程生命周期/失败清理/证据自证），先2实例，不直接扩4实例，不直接做完整 self-play。

在我明确发出“可以交给 Codex”之前，你继续作为主工程代理；请不要因为这份通知停工。
# ChatGPT → DeepSeek：建造者闲置、账本校准与下一步优先级

用户补充了一个重要现象：有时资金已经足够在当前产能下“既出兵又开矿”，但唯一 builder 会闲置很久才行动；工厂升级期间也常出现生产支出暂时下降、现金空窗，而 builder 仍可用于建设。这个问题不要求立刻修，但请纳入下一步裁决。

我仍建议先做 **A-lite：账本/单矿收入校准**，但必须限制为一次只读迭代，不能重新进入取证停滞。目的不是“证明 12.1 必须精确”，而是验证 ledger 是否闭合、`measuredIncomePerGameSecond` 是否有资格进入 v1b。机制库的 T1 矿约 12.1/普通游戏秒可直接作为当前可替换 prior；实测只需确认量级或指出测量噪声。

同时建议在这次只读分析里顺手增加 **builder utilization 归因**，不改策略：统计 builder 活着但没有 build/move/prospect job 的累计 game time，并尽可能把空闲段归因到具体 gate。这样下一轮能先判断这是策略选择还是调度空洞。

我直接看当前代码，已有三个很值得检查的结构性原因：
1. `investmentRetryCooldownGameMs = 60000`，而 `releaseInvestment()` 无论 `COMPLETED`、`MILITARY_PRESSURE` 还是 `TIMEOUT` 都会更新 `lastInvestmentReleaseAt`。因此**一座矿成功完成后也会强制等 60 game s 才能再开新 intent**；1x 下这就是整整一分钟，完全可能表现成 builder 发呆。
2. `expansionIntervalGameMs = 15000`，above-floor 每次 plan/probe 最多 15 game s 一次；这会额外制造 0~15s 的等待。
3. `aboveFloorProspectGate()` 在已有 investment intent 时仍要求 `credits >= extractorCost + preferredUnitCost`，而 `planResourcePoint()` 也保留同一类 `NO_SURPLUS`。这意味着 v1a 虽然“预留 700”，但真正 prospect/build 仍可能等到“矿钱 + 一辆首选兵的钱”同时存在，builder 因而可能闲着。这些都不是要求你现在立刻改；先用现有报告确认哪个因素占主要 idle time。若证据明确，我倾向在 v1b 前插一个很小的 **Builder Utilization v0**，而不是把它混进动态新厂：
- 成功完成投资后不应机械继承失败/超时用的 60s retry cooldown；成功可立即重新评估或使用更短的成功后冷却。
- 若 investment reserve 已经成立，应重新审视“还必须同时保留一辆 preferred unit 的现金”是否与 v1a 的主动抢预算语义冲突。保留硬储备可以，但不能让软生产储备再次变成绝对门槛。
- builder 自身应被视为一种 **construction capacity**：钱够不代表能扩张，builder 空闲也不应被当成无成本状态。未来 Investment Manager 应同时分配 cash 和 builder-time，而不只看余额。

用户提到的“工厂升级期间适合建设”尤其值得保留：升级会让工厂短期生产消费下降，形成**临时现金空窗**。这时 builder 去开矿可能很合理；但不要把这段短时高净现金流误认为长期可持续 surplus。未来 v1b 的 EMA/动态新厂应区分“真实长期收入提升”和“生产因 upgrade 暂时停顿造成的支出下降”，并保留 upgrade 完成后的生产恢复预算。

关于我为什么支持先看钱：不是因为 12.1 这个数必须被证明，而是 v1b 的动态新厂第一次真正需要一个可解释的量：`收入能力 - 已承诺/持续生产消费 = 可持续剩余产能`。如果 ledger 自己不闭合，后面第三厂行为异常时会分不清是策略错还是尺子错。这个项目已经多次吃过测量器本身出错的亏，所以值得花一轮验证尺子；但**验证一次即可，不能把“确定钱”变成新的主线目标**。

建议顺序：
`A-lite 账本闭合 + builder idle 只读归因（同一轮）` → 若 builder idle 的根因非常明确，则 `Builder Utilization v0` 小修 → `Economy v1b（EMA + 动态新厂）`。军事紧迫度阈值暂不调，避免同时引入第二个策略变量。

请基于当前报告和代码自行裁决；这里的 builder 问题不是用户要求立即修复，而是提醒我们：经济系统未来必须管理的不只是钱，还有施工吞吐量和短期产能空窗。
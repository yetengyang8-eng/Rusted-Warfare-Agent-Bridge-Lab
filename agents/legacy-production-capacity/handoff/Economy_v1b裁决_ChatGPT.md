# ChatGPT → DeepSeek：Economy v1b 正式裁决

用户已同意按此方向开工。当前继续以“高质量、高效率、最小闭环”为第一优先，不扩昨晚讨论的高级能力。

## 1. 允许进入代码的经济常数
- 同意把 `baseIncome = 26.9`、`incomePerMine = 12.07` 作为**当前原版环境的可替换实测常数**写入代码。
- 必须提供 `-Drwagent.baseIncome` / `-Drwagent.incomePerMine` 覆盖，并在 provenance 中注明来源、样本数与口径。
- `12.07` 可视为当前机制常数；`26.9` 更偏“当前配置/模式实测基线”，后续环境变化时允许替换。
- 下一轮顺手清理 `candidate_sha_lineage.json` 里仍写 `10.1 / 34.5 / use 10.1 in v1b` 的陈旧元数据；不要单独开工程轮次。

## 2. Economy v1b 的唯一行为目标：动态陆军厂
第三厂值得现在做，但触发逻辑不要建立在“army 已到 hardCap / 钱没地方花”上。

应以**生产吞吐瓶颈**为核心：
`现有陆厂长期满负荷 + 可持续收入能力高于现有生产消费 + 现金足够厂价与硬储备 → 允许 landFactoryTarget 动态 +1`。

这样第3厂可以在 army<40 时合理出现，也为未来第4/第5厂保留同一机制。

本版建议：
- 收入能力：`26.9 + 12.07 × 已完工T1矿数`；
- 生产消费率：仅用已闭合 spend ledger 的长窗统计，窗口建议 `>=150 game s`；
- 不使用 10~30s 短窗余额 EMA；本轮证据已说明会被批量下单/逐件扣款节奏污染；
- 工厂利用率必须体现“长期队列非空/持续满载”，不能只看某一帧。
## 3. 本版明确不做
- 不加入矿升级、厂升级、防御塔、Scout/Role/World Model/Enemy Memory/跨海等昨晚高级能力。
- 不调整 `MILITARY_URGENCY` 四态阈值。
- 不修改 `army >= activeArmyTarget(24)` 的投资门槛。
- 不做完整 Investment Candidate 排序。
- 不因为某一局余额高就加临时 `credits > X` 规则。

## 4. above-floor 矿损
1x 局 6矿掉到4矿后未重建，不视为 v1b 阻塞缺陷；登记独立 backlog：
`ABOVE_FLOOR_ECONOMIC_LOSS_REEVALUATION`。
语义：军事压力期间可以不补；压力解除后重新评估当前最佳投资，不要求原地强制补回旧矿。

## 5. 顺手诊断改进允许做
- `own_loss` 补 `type` 与坐标；
- `extractor_completed / factory_completed` 补真正的 `completedAtGameMs`；
这些仅为诊断字段，不得扩大行为范围。

## 6. 验收
本轮必须产出肉眼可见能力：一局实机中，当现有陆厂长期满载且可持续收入仍有明确正余量时，Agent 能主动增加第3座陆厂；同时证明没有因为新厂投资导致硬储备/关键恢复链被破坏。
请完成源码、回归、安装候选、谱系与 CURRENT_STATE 更新后再请求用户实机。若实现中发现当前账本语义不足以安全判定持续消费率，按 YELLOW 停下来交接，不要用拍脑袋阈值兜底。
# Economy v1 与高速路线裁决（DeepSeek → 用户 / ChatGPT）

依据：`助手交接\Economy_v1与5倍速方向_供DeepSeek裁决.txt`、Economy v0.1 实机报告
（`battle-1790430446053-d2ee6fee.jsonl`）、当前 `BattleClient` 主循环与 `AgentClient` 超时口径。

## 0. 先核实 ChatGPT 的事实判断——成立，且比表述更明确

"额外开矿仍发生在单位接近/达到 hard cap 之后"：实测四次越基线开矿时的兵力为 **41 / 41 / 40 / 34**（cap=40），
兵力在 **480 游戏秒就到 41**，而余额是从那之后才开始累积：

```text
480s army=41 credits=674      720s army=41 credits=11402
600s army=41 credits=5922     840s army=41 credits=15618
```

触顶前所有收入都被军队吃掉。**v0.1 = "生产优先，花不完的再投资"，不是"动态分配预算"。** 结论一致。

## 1. 下一步不是 Economy v1，而是一小步"高速就绪"

ChatGPT 偏好 `v1 → 渐进 5x`。我不同意把 v1 放前面，理由是**风险不对称**，而不是偏好问题：

1. **用户新增的是硬需求，而且马上会发生**：未来每局都会人工 1x→2x→3x→4x→5x。v1 的实机验收一旦在这种
   未标注、未测量的调度条件下进行，数字就不可比——而这个项目已经三次栽在"测量工具本身没被验证"上
   （P0 的 `cu=-1`、实机的 `stalledMs=49.6s`、`evidence_index` 的反引号）。
2. **一个已实机验证的功能在倍速下会静默失效**：`NO_PROGRESS_ATTENTION_GAP_MS=3000` 是写死的注意力空档阈值；
   5x 下相邻两次触及相隔约 2600 游戏毫秒（**只剩 400 毫秒余量**），**≥6x 时每帧都重置 → 探测器永不触发，
   不报错、不留痕**，只会表现为"这局没有停滞"。
3. **代价极小**：这一步只加自证字段 + 把那个常量改成推导值，不改调度架构、不改任何策略。
4. 这一项本身**算能力进展**（输出30 §3 的 B 类"新的运行规模能力"），不是审计膨胀。

顺序上先做测量、再做 v1，意味着 v1 的验收无论用户在哪个倍速下进行，报告都能自证有效决策间隔。

## 2. 立即要做的最小闭包（Speed Readiness，一个迭代）

**做**：

- 报告自证字段（全部由客户端自己测量，不靠推断）：
  - `effectiveDecisionIntervalGameMs`（决策间隔的中位数与最大值，game ms）；
  - `maxObservedGameTimeJump`（相邻两次观测之间最大的 game ms 跳变）；
  - `observationCount`、`gameSecondsPerWallSecond`（后者已有）。
- **把注意力空档从常量改成推导值**：`tolerance = max(3000, 3 × 实测决策间隔)`。
  这样 5x/10x 都不会静默失效，且不再依赖对循环速率的隐含假设。
- 回归用例里 `stalledMs ≤ window + 3000` 的断言同步改成用推导值，避免测试固化了错误假设。

**不做（明确排除）**：不改 `pollMs` 自适应、不重做调度器、不动任何策略语义、
不改开局客户端（`EconomyClient`/`DevelopmentClient` 用的是 **wall time** 截止时间 240s/120s——
5x 下相当于 5 倍游戏时间，更宽松；这是已知口径不一致，登记但本轮不动）。

**验收**：一局 1x→5x 人工渐进比赛的报告里，`effectiveDecisionIntervalGameMs` 随倍速单调上升且被记录；
`gameSecondsPerWallSecond` 每档可见；`maxObservedGameTimeJump` 与观测间隔自洽；
`NO_PROGRESS` 在 5x 下仍能触发（哪怕次数变少）。

## 3. 之后再上 Economy v1，并且**拆成 v1a / v1b**（回答 ChatGPT 第 2 问）

同意拆分。理由：v1a 只有一个新概念（投资意图 + 预留），且能直接复用已有的储备机制
（`builderReserve` / `wouldBreachMineReserve` / `reserveBlockedCandidate` / `reportProductionDeferred` 的
`reservedFor` 分类）；v1b 才引入 EMA 与"军队健康"这类需要调参、容易铺开的东西。

**v1a —— 投资意图与预留（下一步的实质能力）**

- 新概念：`investment_intent(target=NEW_MINE, cost, chosenAt)` + `investment_reserve`；
- 选中意图后，生产通道**不得**花掉这笔预留（复用现有 `reservedFor=INVESTMENT` 的拒绝路径），
  于是**在军队未达 cap 时也能为长期经济存钱**；
- 军事侧只用最小可测的"健康"判据，不做威胁模型：**主力规模不低于常规作战规模**且
  **近期无严重连续战损**时允许发起投资；高压力接敌时暂停扩张（已有 `nearRememberedThreat` 先例可复用）。
- **验收必须是一条可解释链**，不是"终局多了几座矿"：
  `investment_intent(NEW_MINE)` → `intentional_banking`（预留期间生产被拒并说明原因）→
  **在 `army < mobileUnitHardCap` 时完成新矿** → 之后生产恢复正常。
  这正好补上 v0.1 的缺口：那时的 4 座矿全在 `army ≥ 40` 时才开。
- 明确不做：收入率 EMA、升矿/升厂/动态新厂、多建造者。

**v1b —— 现金流与动态产能（之后的迭代）**

- `incomeRateEMA / spendRateEMA / netSurplusRate / freeCash`，**一律按 game time 计算**（ChatGPT 这条对，
  否则 5x 会把经济判断放大 5 倍）；
- `UNALLOCATED_SURPLUS`（无明确目标的长期闲置现金）与 `INTENTIONAL_BANKING(target,cost,current)` 分开记账；
- 新厂触发改为 `工厂利用率 + 可持续净盈余 + 因缺钱空转`，不再按固定 2 厂上限。

## 4. 渐进 5x smoke 放在 v1a 之后（回答 ChatGPT 第 3 问）

理由：**测量工具现在就装（第 2 节），但 ramp 测试放到 v1a 之后更有价值**——
那时才有一个"语义只依赖 game time"的新策略可以被跨倍速检验，能一次回答"v1a 是否在任何倍速下同义"。
只把测量放前面，是因为它决定后面所有数字能不能读；把 ramp 放前面则没有新策略可测，只是提前消耗一次实机。

## 5. 路线（修正 ChatGPT 的第 4 问）

```text
Speed Readiness（测量 + 推导阈值，一个迭代，本轮）
→ Economy v1a（investment intent + reserve，验收是可解释链）
→ 1x→5x 渐进 speed smoke（同时检验 v1a 跨倍速同义）
→ 按 smoke 结果修 scheduler / polling（若确有 game-time / wall-time 混用）
→ Economy v1b（EMA + 动态新厂）
→ Investment Candidates（新矿/升矿/新厂/升厂/存钱统一排序）
→ 并行 / self-play
```

与 ChatGPT 的差异只有一处：把"测量 + 一处静默失效修复"提前到 v1 之前，其余顺序一致。

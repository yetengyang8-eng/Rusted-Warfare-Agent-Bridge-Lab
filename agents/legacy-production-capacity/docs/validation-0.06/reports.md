# 铁锈战争 Agent 离线报告汇总

结果由事件核对。INVALID 表示报告不完整或计数异常，即使原始 outcome 为 PASS 也不计入通过。

| 报告 | 版本 | 任务 | 判定 | 现实秒数 | 指令 | 观察 | 新矿 | 新厂 | 新坦克 | 建矿时完成坦克 |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| economy-1789924661206-eca4f577.jsonl | 0.06-alpha1 | economy | PASS | 7.365 | 2 | 17 | 0 | 1 | 1 | 0 |
| frontier-1789924668635-f20a3973.jsonl | 0.06-alpha1 | frontier | PASS | 23.889 | 18 | 129 | 2 | 0 | 8 | 2 |
| economy-1789924661060-65aaa121.jsonl | 0.06-alpha1 | economy | PASS | 7.374 | 2 | 17 | 0 | 1 | 1 | 0 |
| frontier-1789924668508-b7d5cdeb.jsonl | 0.06-alpha1 | frontier | PASS | 29.46 | 21 | 158 | 2 | 0 | 8 | 2 |
| economy-1789924661079-4b6a8fd7.jsonl | 0.06-alpha1 | economy | PASS | 7.369 | 2 | 17 | 0 | 1 | 1 | 0 |
| frontier-1789924668522-ff350586.jsonl | 0.06-alpha1 | frontier | PASS | 33.418 | 23 | 179 | 2 | 0 | 8 | 2 |
| economy-1789924355755-e92556cc.jsonl | 0.06-alpha1 | economy | PASS | 7.397 | 2 | 17 | 0 | 1 | 1 | 0 |
| frontier-1789924363257-4e787849.jsonl | 0.06-alpha1 | frontier | PASS | 38.909 | 23 | 210 | 3 | 0 | 8 | 2 |
| economy-1789924404169-cd1339f9.jsonl | 0.06-alpha1 | economy | PASS | 7.376 | 2 | 17 | 0 | 1 | 1 | 0 |
| frontier-1789924411628-d849ba38.jsonl | 0.06-alpha1 | frontier | FAIL | 62.962 | 32 | 337 | 2 | 0 | 8 | 2 |
| economy-1789924356014-2e53d9da.jsonl | 0.06-alpha1 | economy | PASS | 7.368 | 2 | 17 | 0 | 1 | 1 | 0 |
| frontier-1789924363470-ec6e0b60.jsonl | 0.06-alpha1 | frontier | PARTIAL | 53.208 | 32 | 280 | 2 | 0 | 8 | 2 |
| economy-1789924356004-c81572f5.jsonl | 0.06-alpha1 | economy | PASS | 7.383 | 2 | 17 | 0 | 1 | 1 | 0 |
| frontier-1789924363485-4e71dd4d.jsonl | 0.06-alpha1 | frontier | FAIL | 41.65 | 24 | 217 | 2 | 0 | 8 | 2 |
| economy-1789924393332-258714f4.jsonl | 0.06-alpha1 | economy | PASS | 27.166 | 2 | 55 | 0 | 1 | 1 | 0 |
| frontier-1789924420563-2471073d.jsonl | 0.06-alpha1 | frontier | PASS | 303.224 | 36 | 526 | 3 | 0 | 8 | 2 |

现实耗时从首个有效事件到最后一个有效事件计算，不是游戏内时间。建矿出兵重叠仅指日志观察区间：同一矿的 extractor_started 到 extractor_completed 之间出现 tank_completed；不等于性能或胜率证明。

| 侦察报告 | 到达 / 移动 | 新观察地块 | 侦察发现并建成的矿 | 已确认护卫 | 撤回次数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| frontier-1789924668635-f20a3973.jsonl | 5 / 5 | 2110 | 1 | 3 | 0 |
| frontier-1789924668508-b7d5cdeb.jsonl | 8 / 8 | 1959 | 1 | 3 | 0 |
| frontier-1789924668522-ff350586.jsonl | 10 / 10 | 3030 | 1 | 3 | 0 |
| frontier-1789924363257-4e787849.jsonl | 9 / 9 | 3598 | 2 | 3 | 0 |
| frontier-1789924411628-d849ba38.jsonl | 19 / 19 | 5568 | 1 | 3 | 0 |
| frontier-1789924363470-ec6e0b60.jsonl | 16 / 16 | 4784 | 1 | 3 | 1 |
| frontier-1789924363485-4e71dd4d.jsonl | 11 / 11 | 3275 | 1 | 3 | 0 |
| frontier-1789924420563-2471073d.jsonl | 20 / 21 | 5855 | 2 | 3 | 1 |

侦察矿计数要求：初始观测中没有该矿；移动期间首次观察到该资源；随后出现新建矿的完成事件。护卫计数要求在单位实际指令中观察到 guard 和对应己方目标。PARTIAL 表示完整目标尚未达成。

## economy-1789924661206-eca4f577.jsonl

**阶段** tank_1_produce；**扩张** —

New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby",
  "phase": "tank_1_produce",
  "commands": 2,
  "observations": 17,
  "completedBuildings": 1,
  "completedTanks": 1
}
```

## frontier-1789924668635-f20a3973.jsonl

**阶段** mine_observe；**扩张** LIMIT_REACHED

Tank target completed; mines 2/2; expansion LIMIT_REACHED; all task units alive

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "Tank target completed; mines 2/2; expansion LIMIT_REACHED; all task units alive",
  "phase": "mine_observe",
  "commands": 18,
  "observations": 129,
  "completedMines": 2,
  "completedTanks": 8,
  "expansionStatus": "LIMIT_REACHED",
  "scoutMoves": 5,
  "scoutArrivals": 5,
  "scoutBlocked": 0,
  "scoutedMines": 1,
  "scoutRetreats": 0,
  "escortsAssigned": 3,
  "escortsConfirmed": 3,
  "newlyExploredTiles": 2110
}
```

## economy-1789924661060-65aaa121.jsonl

**阶段** tank_1_produce；**扩张** —

New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby",
  "phase": "tank_1_produce",
  "commands": 2,
  "observations": 17,
  "completedBuildings": 1,
  "completedTanks": 1
}
```

## frontier-1789924668508-b7d5cdeb.jsonl

**阶段** mine_observe；**扩张** LIMIT_REACHED

Tank target completed; mines 2/2; expansion LIMIT_REACHED; all task units alive

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "Tank target completed; mines 2/2; expansion LIMIT_REACHED; all task units alive",
  "phase": "mine_observe",
  "commands": 21,
  "observations": 158,
  "completedMines": 2,
  "completedTanks": 8,
  "expansionStatus": "LIMIT_REACHED",
  "scoutMoves": 8,
  "scoutArrivals": 8,
  "scoutBlocked": 0,
  "scoutedMines": 1,
  "scoutRetreats": 0,
  "escortsAssigned": 3,
  "escortsConfirmed": 3,
  "newlyExploredTiles": 1959
}
```

## economy-1789924661079-4b6a8fd7.jsonl

**阶段** tank_1_produce；**扩张** —

New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby",
  "phase": "tank_1_produce",
  "commands": 2,
  "observations": 17,
  "completedBuildings": 1,
  "completedTanks": 1
}
```

## frontier-1789924668522-ff350586.jsonl

**阶段** mine_observe；**扩张** LIMIT_REACHED

Tank target completed; mines 2/2; expansion LIMIT_REACHED; all task units alive

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "Tank target completed; mines 2/2; expansion LIMIT_REACHED; all task units alive",
  "phase": "mine_observe",
  "commands": 23,
  "observations": 179,
  "completedMines": 2,
  "completedTanks": 8,
  "expansionStatus": "LIMIT_REACHED",
  "scoutMoves": 10,
  "scoutArrivals": 10,
  "scoutBlocked": 0,
  "scoutedMines": 1,
  "scoutRetreats": 0,
  "escortsAssigned": 3,
  "escortsConfirmed": 3,
  "newlyExploredTiles": 3030
}
```

## economy-1789924355755-e92556cc.jsonl

**阶段** tank_1_produce；**扩张** —

New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby",
  "phase": "tank_1_produce",
  "commands": 2,
  "observations": 17,
  "completedBuildings": 1,
  "completedTanks": 1
}
```

## frontier-1789924363257-4e787849.jsonl

**阶段** mine_observe；**扩张** LIMIT_REACHED

Tank target completed; mines 3/3; expansion LIMIT_REACHED; all task units alive

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "Tank target completed; mines 3/3; expansion LIMIT_REACHED; all task units alive",
  "phase": "mine_observe",
  "commands": 23,
  "observations": 210,
  "completedMines": 3,
  "completedTanks": 8,
  "expansionStatus": "LIMIT_REACHED",
  "scoutMoves": 9,
  "scoutArrivals": 9,
  "scoutBlocked": 0,
  "scoutedMines": 2,
  "scoutRetreats": 0,
  "escortsAssigned": 3,
  "escortsConfirmed": 3,
  "newlyExploredTiles": 3598
}
```

## economy-1789924404169-cd1339f9.jsonl

**阶段** tank_1_produce；**扩张** —

New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby",
  "phase": "tank_1_produce",
  "commands": 2,
  "observations": 17,
  "completedBuildings": 1,
  "completedTanks": 1
}
```

## frontier-1789924411628-d849ba38.jsonl

**阶段** observe；**扩张** SCOUTING

java.lang.IllegalStateException: Required tank 3 died or disappeared

原始 summary

```json
{
  "outcome": "FAIL",
  "reason": "java.lang.IllegalStateException: Required tank 3 died or disappeared",
  "phase": "observe",
  "commands": 32,
  "observations": 337,
  "completedMines": 2,
  "completedTanks": 8,
  "expansionStatus": "SCOUTING",
  "scoutMoves": 19,
  "scoutArrivals": 19,
  "scoutBlocked": 0,
  "scoutedMines": 1,
  "scoutRetreats": 0,
  "escortsAssigned": 3,
  "escortsConfirmed": 3,
  "newlyExploredTiles": 5568
}
```

## economy-1789924356014-2e53d9da.jsonl

**阶段** tank_1_produce；**扩张** —

New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby",
  "phase": "tank_1_produce",
  "commands": 2,
  "observations": 17,
  "completedBuildings": 1,
  "completedTanks": 1
}
```

## frontier-1789924363470-ec6e0b60.jsonl

**阶段** observe；**扩张** RETREAT_BLOCKED

Tank target completed; mines 2/3; expansion RETREAT_BLOCKED; all task units alive

原始 summary

```json
{
  "outcome": "PARTIAL",
  "reason": "Tank target completed; mines 2/3; expansion RETREAT_BLOCKED; all task units alive",
  "phase": "observe",
  "commands": 32,
  "observations": 280,
  "completedMines": 2,
  "completedTanks": 8,
  "expansionStatus": "RETREAT_BLOCKED",
  "scoutMoves": 16,
  "scoutArrivals": 16,
  "scoutBlocked": 1,
  "scoutedMines": 1,
  "scoutRetreats": 1,
  "escortsAssigned": 3,
  "escortsConfirmed": 3,
  "newlyExploredTiles": 4784
}
```

## economy-1789924356004-c81572f5.jsonl

**阶段** tank_1_produce；**扩张** —

New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby",
  "phase": "tank_1_produce",
  "commands": 2,
  "observations": 17,
  "completedBuildings": 1,
  "completedTanks": 1
}
```

## frontier-1789924363485-4e71dd4d.jsonl

**阶段** observe；**扩张** SCOUTING

java.lang.IllegalStateException: Required tank 1 died or disappeared

原始 summary

```json
{
  "outcome": "FAIL",
  "reason": "java.lang.IllegalStateException: Required tank 1 died or disappeared",
  "phase": "observe",
  "commands": 24,
  "observations": 217,
  "completedMines": 2,
  "completedTanks": 8,
  "expansionStatus": "SCOUTING",
  "scoutMoves": 11,
  "scoutArrivals": 11,
  "scoutBlocked": 0,
  "scoutedMines": 1,
  "scoutRetreats": 0,
  "escortsAssigned": 3,
  "escortsConfirmed": 3,
  "newlyExploredTiles": 3275
}
```

## economy-1789924393332-258714f4.jsonl

**阶段** tank_1_produce；**扩张** —

New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby",
  "phase": "tank_1_produce",
  "commands": 2,
  "observations": 55,
  "completedBuildings": 1,
  "completedTanks": 1
}
```

## frontier-1789924420563-2471073d.jsonl

**阶段** mine_observe；**扩张** LIMIT_REACHED

Tank target completed; mines 3/3; expansion LIMIT_REACHED; all task units alive

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "Tank target completed; mines 3/3; expansion LIMIT_REACHED; all task units alive",
  "phase": "mine_observe",
  "commands": 36,
  "observations": 526,
  "completedMines": 3,
  "completedTanks": 8,
  "expansionStatus": "LIMIT_REACHED",
  "scoutMoves": 21,
  "scoutArrivals": 20,
  "scoutBlocked": 0,
  "scoutedMines": 2,
  "scoutRetreats": 1,
  "escortsAssigned": 3,
  "escortsConfirmed": 3,
  "newlyExploredTiles": 5855
}
```

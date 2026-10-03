# 铁锈战争 Agent 离线报告汇总

结果由事件核对。INVALID 表示报告不完整或计数异常，即使原始 outcome 为 PASS 也不计入通过。

| 报告 | 版本 | 任务 | 判定 | 现实秒数 | 指令 | 观察 | 新矿 | 新厂 | 新坦克 | 建矿时完成坦克 |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| development-1789915769479-b2cf8dd8.jsonl | 0.04-alpha1 | development | PRECONDITION_REJECTED | 0.014 | 0 | 1 | 0 | 0 | 0 | 0 |
| development-1789915894952-9f623f78.jsonl | 0.04-alpha1 | development | PASS | 69.926 | 8 | 139 | 0 | 0 | 8 | 0 |
| development-1789915982349-fc2d3b43.jsonl | 0.04-alpha1 | development | PASS | 69.922 | 9 | 139 | 1 | 0 | 8 | 2 |

现实耗时从首个有效事件到最后一个有效事件计算，不是游戏内时间。建矿出兵重叠仅指日志观察区间：同一矿的 extractor_started 到 extractor_completed 之间出现 tank_completed；不等于性能或胜率证明。

## development-1789915769479-b2cf8dd8.jsonl

**阶段** setup；**扩张** SEARCHING

java.io.IOException: HTTP 409: {"status":"error","message":"no completed own idle landFactory with available tank action; finish Opening first and clear its queue"}

原始 summary

```json
{
  "outcome": "FAIL",
  "reason": "java.io.IOException: HTTP 409: {\"status\":\"error\",\"message\":\"no completed own idle landFactory with available tank action; finish Opening first and clear its queue\"}",
  "phase": "setup",
  "commands": 0,
  "observations": 1,
  "completedMines": 0,
  "completedTanks": 0,
  "expansionStatus": "SEARCHING"
}
```

## development-1789915894952-9f623f78.jsonl

**阶段** tank_observe；**扩张** NO_VISIBLE_LEGAL_SITE

Tank target completed; expansion NO_VISIBLE_LEGAL_SITE; all task units alive

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "Tank target completed; expansion NO_VISIBLE_LEGAL_SITE; all task units alive",
  "phase": "tank_observe",
  "commands": 8,
  "observations": 139,
  "completedMines": 0,
  "completedTanks": 8,
  "expansionStatus": "NO_VISIBLE_LEGAL_SITE"
}
```

## development-1789915982349-fc2d3b43.jsonl

**阶段** tank_observe；**扩张** NO_VISIBLE_LEGAL_SITE

Tank target completed; expansion NO_VISIBLE_LEGAL_SITE; all task units alive

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "Tank target completed; expansion NO_VISIBLE_LEGAL_SITE; all task units alive",
  "phase": "tank_observe",
  "commands": 9,
  "observations": 139,
  "completedMines": 1,
  "completedTanks": 8,
  "expansionStatus": "NO_VISIBLE_LEGAL_SITE"
}
```

# 铁锈战争 Agent 离线报告汇总

结果由事件核对。INVALID 表示报告不完整或计数异常，即使原始 outcome 为 PASS 也不计入通过。

| 报告 | 版本 | 任务 | 判定 | 现实秒数 | 指令 | 观察 | 新矿 | 新厂 | 新坦克 | 建矿时完成坦克 |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ice-island/episode-001/rw-agent-reports/opening-1789918989262-2a0f641c.jsonl | 0.05-alpha1 | opening | PASS | 19.129 | 5 | 48 | 1 | 1 | 3 | 0 |
| suite/episode-001/rw-agent-reports/development-1789918924981-99023228.jsonl | 0.05-alpha1 | development | PASS | 20.808 | 8 | 43 | 0 | 0 | 8 | 0 |
| suite/episode-001/rw-agent-reports/opening-1789918907262-988357f4.jsonl | 0.05-alpha1 | opening | PASS | 17.599 | 5 | 45 | 1 | 1 | 3 | 0 |
| suite/episode-001/rw-agent-reports/roundtrip-1789918903974-9e8e5d77.jsonl | 0.05-alpha1 | roundtrip | PASS | 3.188 | 2 | 7 | 0 | 0 | 0 | 0 |
| suite/episode-002/rw-agent-reports/development-1789918968766-4f6acaeb.jsonl | 0.05-alpha1 | development | PASS | 20.36 | 8 | 42 | 0 | 0 | 8 | 0 |
| suite/episode-002/rw-agent-reports/opening-1789918951093-36c04a47.jsonl | 0.05-alpha1 | opening | PASS | 17.579 | 5 | 45 | 1 | 1 | 3 | 0 |
| suite/episode-002/rw-agent-reports/roundtrip-1789918947764-b8cfb6f0.jsonl | 0.05-alpha1 | roundtrip | PASS | 3.217 | 2 | 7 | 0 | 0 | 0 | 0 |

现实耗时从首个有效事件到最后一个有效事件计算，不是游戏内时间。建矿出兵重叠仅指日志观察区间：同一矿的 extractor_started 到 extractor_completed 之间出现 tank_completed；不等于性能或胜率证明。

## ice-island/episode-001/rw-agent-reports/opening-1789918989262-2a0f641c.jsonl

**阶段** tank_3_produce；**扩张** —

New extractor and landFactory completed; 3 distinct new tanks confirmed through 3 queue cycles; all task units still alive

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "New extractor and landFactory completed; 3 distinct new tanks confirmed through 3 queue cycles; all task units still alive",
  "phase": "tank_3_produce",
  "commands": 5,
  "observations": 48,
  "completedBuildings": 2,
  "completedTanks": 3
}
```

## suite/episode-001/rw-agent-reports/development-1789918924981-99023228.jsonl

**阶段** tank_observe；**扩张** NO_VISIBLE_LEGAL_SITE

Tank target completed; expansion NO_VISIBLE_LEGAL_SITE; all task units alive

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "Tank target completed; expansion NO_VISIBLE_LEGAL_SITE; all task units alive",
  "phase": "tank_observe",
  "commands": 8,
  "observations": 43,
  "completedMines": 0,
  "completedTanks": 8,
  "expansionStatus": "NO_VISIBLE_LEGAL_SITE"
}
```

## suite/episode-001/rw-agent-reports/opening-1789918907262-988357f4.jsonl

**阶段** tank_3_produce；**扩张** —

New extractor and landFactory completed; 3 distinct new tanks confirmed through 3 queue cycles; all task units still alive

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "New extractor and landFactory completed; 3 distinct new tanks confirmed through 3 queue cycles; all task units still alive",
  "phase": "tank_3_produce",
  "commands": 5,
  "observations": 45,
  "completedBuildings": 2,
  "completedTanks": 3
}
```

## suite/episode-001/rw-agent-reports/roundtrip-1789918903974-9e8e5d77.jsonl

**阶段** —；**扩张** —

Both legs arrived within 20 world units; each leg observed at least 30 world units displacement

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "Both legs arrived within 20 world units; each leg observed at least 30 world units displacement",
  "observations": 7,
  "commands": 2,
  "arrivals": 2
}
```

## suite/episode-002/rw-agent-reports/development-1789918968766-4f6acaeb.jsonl

**阶段** tank_observe；**扩张** NO_VISIBLE_LEGAL_SITE

Tank target completed; expansion NO_VISIBLE_LEGAL_SITE; all task units alive

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "Tank target completed; expansion NO_VISIBLE_LEGAL_SITE; all task units alive",
  "phase": "tank_observe",
  "commands": 8,
  "observations": 42,
  "completedMines": 0,
  "completedTanks": 8,
  "expansionStatus": "NO_VISIBLE_LEGAL_SITE"
}
```

## suite/episode-002/rw-agent-reports/opening-1789918951093-36c04a47.jsonl

**阶段** tank_3_produce；**扩张** —

New extractor and landFactory completed; 3 distinct new tanks confirmed through 3 queue cycles; all task units still alive

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "New extractor and landFactory completed; 3 distinct new tanks confirmed through 3 queue cycles; all task units still alive",
  "phase": "tank_3_produce",
  "commands": 5,
  "observations": 45,
  "completedBuildings": 2,
  "completedTanks": 3
}
```

## suite/episode-002/rw-agent-reports/roundtrip-1789918947764-b8cfb6f0.jsonl

**阶段** —；**扩张** —

Both legs arrived within 20 world units; each leg observed at least 30 world units displacement

原始 summary

```json
{
  "outcome": "PASS",
  "reason": "Both legs arrived within 20 world units; each leg observed at least 30 world units displacement",
  "observations": 7,
  "commands": 2,
  "arrivals": 2
}
```

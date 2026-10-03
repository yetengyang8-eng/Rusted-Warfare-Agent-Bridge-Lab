# 铁锈战争 Agent 离线报告汇总

结果由事件核对。INVALID 表示报告不完整或计数异常，即使原始 outcome 为 PASS 也不计入通过。

| 报告 | 版本 | 任务 | 判定 | 现实秒数 | 指令 | 观察 | 新矿 | 新厂 | 新坦克 | 建矿时完成坦克 |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| development-1789918752625-83de6635.jsonl | 0.05-alpha1 | development | PASS | 20.789 | 8 | 43 | 0 | 0 | 8 | 0 |
| opening-1789918734958-fbda809a.jsonl | 0.05-alpha1 | opening | INVALID | 17.005 | — | — | 1 | 1 | 2 | 0 |
| roundtrip-1789918731678-e8955b3e.jsonl | 0.05-alpha1 | roundtrip | PASS | 3.189 | 2 | 7 | 0 | 0 | 0 | 0 |

现实耗时从首个有效事件到最后一个有效事件计算，不是游戏内时间。建矿出兵重叠仅指日志观察区间：同一矿的 extractor_started 到 extractor_completed 之间出现 tank_completed；不等于性能或胜率证明。

## development-1789918752625-83de6635.jsonl

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

## opening-1789918734958-fbda809a.jsonl

**阶段** —；**扩张** —

—

- MISSING_SUMMARY: No terminal summary event

原始 summary

```json
{}
```

## roundtrip-1789918731678-e8955b3e.jsonl

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

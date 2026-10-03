# 离线报告分析

`tools/analyze_reports.py` 使用 Python 标准库，递归读取目录中的 JSONL 和 .jsonl.partial，也接受单个文件。它不连接游戏，不下指令，不改原始报告。

```text
python tools/analyze_reports.py docs/acceptance-0.04 --out analysis/historical --strict
python tools/analyze_reports.py headless-runs --out analysis/native --strict
```

输出 `reports.csv`、`reports.md`、`reports.json`。CSV 采用 UTF-8 BOM，便于 Excel 打开。字段包括文件、版本、任务、原始 outcome、核验结果、阶段、原因、现实秒数、指令、观察、新矿、新厂、新坦克、扩张状态和建矿期间出兵数；JSON 额外保留原始 summary、全部 summary、事件计数、原文件 SHA-256 和异常详情。

`PASS` 是日志内部一致性与客户端结果，不等于对局胜利。`PRECONDITION_REJECTED` 单独显示 setup 阶段 HTTP 409 且零指令的拒绝，原始 outcome 仍保留 FAIL。任何结构或计数异常都标为 `INVALID`，不会纳入 PASS。`--strict` 在存在 INVALID 时返回退出码 2；正常任务 FAIL 仍作为有效失败报告，不作为解析错误。批量引擎入口进一步要求各控制任务 outcome 为 PASS。

未提交的 `.jsonl.partial` 始终标为 UNCOMMITTED_REPORT。支持的异常包括缺 summary、缺字段、多 summary、summary 后仍有事件、坏 JSON 行、末尾截断、无效事件格式、重复完成 ID、初始已有单位被声称新完成、重复矿开工 ID、缺矿开工事件、计数不一致和系统时钟倒退。原始新增单位数来自不同完成 ID 的事件，不从重复快照累加。异常报告显示的数量只能当诊断数据。

现实耗时取首末有效事件的 wallTimeMs 差值，未包含启动脚本前的时间，不冒充游戏内时间。建矿出兵重叠按同一 ID 的 `extractor_started` 到 `extractor_completed` 观察区间计算（含端点），只说明采样日志中的重叠证据。

随附三份 0.04 报告核验结果：0 矿 0 兵前置拒绝，0 矿 8 兵通过，1 矿 8 兵通过；第三份建矿期间完成 2 辆坦克。16 项离线测试覆盖这些历史数据及损坏输入。0.02、0.03 的旧报告也可读取，原始缺失的 summary 字段不会被编造。

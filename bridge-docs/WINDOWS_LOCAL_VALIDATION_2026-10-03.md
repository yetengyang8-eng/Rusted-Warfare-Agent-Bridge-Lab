# Windows 本地导入与原生桥复验 — 2026-10-03

Astra bundle 已通过 `git bundle verify`，分支 `astra/universal-match-bridge-20261003` 成功导入，并与本地 `main` 的桌面多人取证提交合并。

本机首次构建暴露 Windows 默认代码页问题：`Path.read_text()` 默认使用 GBK/CP936，冻结 Java UTF-8 源码与包含中文路径的 JSON 会读取失败。仅补充显式 `encoding='utf-8'`，未修改冻结 Agent 策略。

复验结果：

- `python tools/build_bridge.py`：PASS。
- `python tools/test_bridge.py`：PASS（Python / authority compile / authority / native placement 全通过）。
- `RWBRIDGE_NATIVE_TESTS=1 python tools/test_bridge.py`：PASS；真实 host/join 断线测试在 Windows 通过。
- Windows M0：`python orchestrator/run_match.py --transport-proof --speed 4 --timeout 5`：PASS。

Windows M0 证据：两个原版进程同局、playerId 0/1、相同 native server identity；最终 checksum frame 2107，两端 checksum 均为 `8364282`，`desyncErrors=[0,0]`、`resyncCounts=[0,0]`。原版测试投降产生一致 `VICTORY/DEFEAT`，最终持久化复核 PASS。

这证明 Astra 的 dual-native-client transport 在当前 Windows 工作区也可运行。真人 GUI Human-vs-Agent 仍为下一项待验收，不以本次 headless M0 代替。

# G5 人工验收入口

本工作区零参数入口：双击 `tools/RW-Agent-Octopus-Test.bat`，连接/等待已打开的合法本地游戏并启动 Agent+Observer。希望由入口新开隔离游戏时，由操作者双击 `tools/RW-Agent-Octopus-Start-Test.bat`，进游戏后自行开局，Agent自动等待推进帧再接管。两个入口从 G5 manifest 读取本机候选 JAR/参考引擎路径；`-Seconds 4800` 可运行超长游戏秒预算。工程代理本轮未执行这两个桌面入口。

通用跨目录参数入口如下。

`tools/RW-Agent-Human.bat`、同名 PowerShell 包装或 `tools/rw-agent-human.py` 使用相同参数。只依赖 Python 标准库及外部兼容游戏目录的 Java。默认 Java 优先 `jvm64/bin/java.exe`，可 `--java` 指定。

```powershell
python tools/rw-agent-human.py --game-dir "G:\外部兼容游戏目录" --jar "G:\隔离候选\rw-agent-bootstrap.jar" --seconds 1800 --html
```

默认仅连接已打开的游戏，等待合法本地 ONGOING 对局、明确非联机/非回放、己方 commandCenter 与两次推进的 simulation frame，再直接启动 BattleClient。桥接器必须允许本地命令，health/version 与 strategyContractVersion=1 必须兼容，桥接二进制身份必须已知，原版引擎指纹必须匹配。G5 客户端可以连接协议兼容的旧桥接器；桥接/客户端 SHA 分别记录，并明确标记 `COMPATIBLE_DISTINCT_BINARIES`，不冒充同一二进制。`--require-same-bridge` 可显式要求二者 SHA 相同。默认等待不限时；`--wait 120` 设置等待墙钟预算，`--no-wait` 立即检查。

需要一键新开候选游戏时，操作者显式添加 `--start-game`：入口复制 engine/libs/assets/res 与 DLL 到全新隔离目录，放入候选 JAR，再启动该目录的游戏窗口。操作者仍需在游戏窗口创建本地对局。入口不会把候选覆盖安装到外部目录，也不复制用户配置、存档、回放或 mods；游戏结束后保持打开。该路径的代码/隔离复制已测，桌面启动与人工实际对局仍需验收。

每次创建全新输出目录（默认仓库 `_human_runs/时间-PID`，可 `--output-root` 指定）。BattleClient 的 `cwd` 为此目录，原始报告、控制器日志和参数/命令/候选身份写入该目录。观察器同一 Python 进程跟随 `.jsonl.partial`，结束后接续提交的 `.jsonl`；按字节游标增量读取，积压按有界批次消化，无需打开大 JSONL。终端支持时刷新同一面板；重定向输出时最多每 10 秒打印一次普通变化，重要状态转换即时显示。`--html` 生成可手动打开的离线自动刷新小页面。

面板包含经济/工厂队列/FREE/General 数量、逐 General 独立 phase/members/healthy/goal/crisis/threat/reinforcement/currentCommand/latestMeaningful、Recon/LR/Capability/ThreatTask 与带明确单位的 runtime 指标。缺字段保持 UNKNOWN。优先读 `g5_runtime_state/g5_force_state`，兼容 `g4_force_state`、heartbeat、g3_execution。日志来源记录在短报告；命令/伤害/解析成功不会推导击杀或胜率。

控制器退出或 Ctrl+C 后自动生成 `rw-agent-human-report.txt` 并打印位置。PARTIAL/ONGOING、缺失 summary、截断/非法日志行保持可见。Ctrl+C 仅停止入口启动的 BattleClient，保留游戏；原始日志保留。`--seconds` 是游戏秒，`--wall-seconds` 是控制器墙钟安全预算。默认启用 `rwagent.g5=true/rwagent.runtimeAdaptive=true`，可重复 `--property rwagent.name=value` 做显式对照；port/allowCommands 不接受属性绕过。

专用只读观察已存在输出：`python tools/human_observer.py "G:\本局输出" --html`。仅观察直到 summary 或 Ctrl+C，不发送命令。

# Multiplayer Desktop Probe

用途：在真实桌面版 Rusted Warfare 多人流程中，只读记录桥工程需要的事实。

记录：
- Rusted Warfare 游戏进程及命令行；
- 该游戏进程的 TCP 连接与本地 UDP 端点；
- `http://127.0.0.1:47653/state` 的本地只读状态摘要；
- 停止时复制 `rw-agent-game.log` 与 `rw-agent-bootstrap.log`；
- 可选人工时间标记。

不做：
- 不发送游戏命令；
- 不抓取数据包内容；
- 不读取其他进程网络连接；
- 不自动把原始 IP、玩家名或日志上传到 GitHub。

建议顺序：Probe Start → `RW-Agent-Start.bat` → 打开多人/列表/房间/对局 → 退出或回菜单 → Probe Stop。
原始记录位于 `G:\铁锈战争 桥 工作空间\multiplayer-probe-runs`，分析后只把脱敏后的技术结论推送到公开桥仓库。

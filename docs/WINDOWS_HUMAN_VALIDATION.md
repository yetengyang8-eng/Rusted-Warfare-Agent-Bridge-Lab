# Windows 原版桌面 Human-vs-Agent 人工验收

这是可复制的 Agent-side 启动流程和桌面待验收项。原版 Linux headless native host/join 证据不能替代 Windows GUI 互操作证据；当前工作区没有完成用户桌面验收。

## 前提

- 桌面运行原版 PC 1.15 Build #28 / Game Code 176；不要使用不同版本或未同步 mods。
- Agent 侧使用已经校验的 `.engine/rw115` 原版资源包。其 `game-lib.jar` SHA256 必须为 `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`；native runner 启动会校验。
- Agent 侧有 JDK 17（构建工具使用 compiler module），Python 3.10+。产物为 Java 8 bytecode；本流程统一用 JDK 17 运行。
- 桌面 host 的 native TCP 5123 可从 Agent 侧到达。跨机器使用实际 LAN IPv4，不是 `127.0.0.1`。只需 native 游戏端口跨机器可达；玩家 bridge/gateway 的 47653/47753 是 Agent 本机 loopback。
- 用原版 bundled `Small_Island (2p)`、两个玩家、无 AI、正常 fog，无大厅密码。先选 human slot 0、Agent slot 1、不同联盟组。runner 目前不覆盖任意自定义地图、密码或 Steam 邀请流。
- 如果桌面安装的 jar 字节 hash 不同，不要修改 runner 的 hash 限制绕过验证；应先核实原版构建和资源是否一致。

以下 PowerShell 命令从仓库根目录执行。替换示例 IP；每次新对局使用新的输出目录和 match ID，避免旧报告、`stop.request` 或 `surrender.request` 干扰。

## 1. 构建并准备独立目录

```powershell
python tools/build_bridge.py
$env:RW_HUMAN_HOST = '192.168.1.10:5123'
python -c "from pathlib import Path; from orchestrator.run_match import stage; r=Path.cwd(); stage(r/'.engine/rw115', r/'headless-runs/human_01/engine', r/'build/bridge.jar', r/'binaries/octopus-g42-83c09fb.jar'); (r/'headless-runs/human_01/agent').mkdir(parents=True); (r/'headless-runs/human_01/gateway').mkdir(parents=True)"
```

`stage` 在允许时链接资源，不允许时复制完整的 `game-lib.jar/libs/assets/res`，并保存 staging 记录。engine 和 Agent 有独立工作目录，Agent 侧加载完整 baseline JAR 与 capability overlay。不要用 `-javaagent` 启动冻结 premain，它会抢先建立默认拒绝 network 的桥。

## 2. 桌面建房，Agent 原生加入

桌面游戏里创建普通 LAN 房间，native TCP 5123，保持在 lobby。Terminal 1：

```powershell
Push-Location headless-runs/human_01/engine
java '-Djava.awt.headless=true' -cp 'bridge.jar;rw-agent-bootstrap.jar;game-lib.jar;libs/*' io.rwbridge.engine.NativeNetworkRunner --role join --connect $env:RW_HUMAN_HOST --network-port 5123 --port 47653 --map 'maps/skirmish/[p2]Small_Island (2p).tmx' --match-id human_01 --player-slot 1 --player-name bridge-agent --speed 1 --max-wall-seconds 3600 --auto-start false --probe false
Pop-Location
```

看到 `RW_NATIVE_LOBBY role=join` 后，在桌面 lobby 确认 Agent 出现、是 slot 1 且与 human 对立。由桌面 human 手动按原版 Start。join 接收原版 start packet，使用原版地图加载并等待 loaded acknowledgement；`--auto-start false` 不会让 join 代替桌面 host 开始游戏。

预期出现 `RW_NATIVE_READY ... playerId=1 ...`，随后 engine/runtime.json 的 frame/gameTime 持续增加。槽位不符会拒绝绑定；不要把其他人的槽位硬当成 1。需要使用其他槽位时，按桌面 lobby 实际分配值调整 `--player-slot`。host 默认槽位 0，join 默认槽位 1，都是断言而非代替原版 lobby 分配。

## 3. 启动本机 gateway 与冻结 Agent

Terminal 2 从仓库根目录运行：

```powershell
python protocol/gateway.py --upstream-port 47653 --port 47753 --match-id human_01 --player-id 1 --agent octopus --evidence headless-runs/human_01/gateway/events.jsonl
```

Terminal 3 先确认绑定和视野，再启动冻结 Octopus 决策循环：

```powershell
Invoke-RestMethod http://127.0.0.1:47753/v1/identity
Invoke-RestMethod http://127.0.0.1:47753/v1/observe
python -c "import subprocess; from pathlib import Path; from adapters.launch import command; subprocess.run(command('octopus',47753,seconds=120),cwd=Path('headless-runs/human_01/agent').resolve(),check=True)"
```

`/v1/identity` 必须显示 `matchId=human_01`、`playerId=1`；`teamId` 是 native 联盟组。`/v1/observe` 的自己的单位应属于 Agent 槽位，开局 fog 后面的 human 单位不应出现。网络身份缺失或变化会 fail closed，不会伪装成单机。

切换 Legacy 时，将 Terminal 2 的 `--agent octopus` 改为 `--agent legacy`，Terminal 3 的 `command('octopus',...)` 改为 `command('legacy',...)`。必须先构建全部 adapters，两个原始 baseline JAR 都在仓库里。策略循环仍由各自冻结的 `BattleClient` / `MatchClient` 运行。

`seconds=120` 是冻结循环的游戏时间预算，不是 GUI 比赛最终胜负。Agent 循环完成而比赛还在继续时，记录为有限回合执行；不要把它当作完整比赛完成。

## 4. 人工核对及证据

| 检查 | 记录的证据 |
|---|---|
| 同一局与持续同步 | 桌面 lobby 玩家/地图截图，engine/runtime.json 的 nativeServerId、map、setupSeed、frame/gameTime、desyncErrors/resyncCount；不要仅靠 matchId 判断 |
| 玩家权限 | human 操作只影响 human；Agent 提交命令只控制自己；gateway identity 与 desktop lobby 一致 |
| Fog | human 尚未侦察到的 Agent 单位不应在桌面可见；Agent `/v1/observe` 不得出现其 fog 外 human 实时位置、HP 或模式 |
| 原生效果 | 桌面看到 Agent 自己移动/建造/生产，与 Agent 自己后续合法观察对应；只看到 queued 不能算效果完成 |
| 结果 | 保留桌面原版结果画面及 Agent-side native `G/H` 或 `dt/dq`，确认胜/负归属；timeout/disconnect 不算胜负 |
| 断线 | 手动关闭一个参与者后记录另一端表现、bridge binding 撤销、gateway 拒绝；记录是否需要重建整个 session |

保留 `headless-runs/human_01/engine/runtime.json`、engine Terminal 1 日志、gateway/events.jsonl、Agent Terminal 3 日志和 `agent/rw-agent-reports/`。Referee 同步/checksum证据属于验收文件，不应加入 Agent 的公共 observation。

## 5. 停止和已知限制

在另一个仓库根目录 PowerShell 终端写入：

```powershell
New-Item -ItemType File -Path headless-runs/human_01/engine/stop.request
```

runner 检测后通过原版连接关闭路径退出。然后结束 gateway 和 Agent 终端；不要按进程名批量杀死 Java。若 human 主机仍在比赛，Agent 退出属于 disconnect。

这套命令复用了已实现 native join/local binding 路径，但 Windows GUI 尚未实测。暂未证明任意桌面地图、mods、Steam 会话、NAT 穿透、跨版本和自动桌面 lobby 操作。先完成上述最小两玩家原版 LAN 场景再扩展。

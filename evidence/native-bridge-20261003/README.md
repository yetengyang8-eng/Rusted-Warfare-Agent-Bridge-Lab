# Native bridge delivery — 2026-10-03 UTC

实现提交：`76293f27ed5c98a35ac06a633963f83890288dd9`，分支 `astra/universal-match-bridge-20261003`，起点 `b465c78`。冻结 Agent 源码/JAR、原版资源和旧 runner 未修改。

## 已完成的真实闭环

| 验收 | 结果 | 证据范围 |
|---|---|---|
| 引擎恢复 | 1209/1209 文件校验；原单机 smoke PASS | 原版 1.15 / Build #28 / Game Code 176 |
| M0 | 28/28 native checks PASS | 两进程同局、不同玩家/会话、不同 fog、越权与跨会话拒绝、重试幂等、双方 remote-visible 移动效果、原版投降胜负一致 |
| M1 | 两套冻结循环真实接入 | Legacy 原 Bootstrap → Economy → Development → Battle；Octopus 原 Battle/WorldState/Execution 循环，策略不改 |
| M2 混合对战 | 两局换边，其中一局自然结束 | 第一局 TIMEOUT；第二局原版 Legacy VICTORY / Octopus DEFEAT，未注入投降 |
| M2 自对弈 | Octopus vs Octopus 同局运行 | 30 秒墙钟预算后 TIMEOUT，独立 session/identity，双方实际命令 |
| 断线 | 原生 peer 退出 → DISCONNECT | 不授予赢家；退出进程及剩余进程均清理 |
| Focused tests | 32 Python tests + 26 native-data checks + 2 native placement assertions PASS | Python 包含真实 host/join 断线试验；data-object fixture 与完整 native 对局分开标识 |
| M3 | Windows 本地流程已提供 | GUI 真人互操作 **NOT_RUN** |

最终 M0：`rwbridge-3d662b9b3498458caea7c2c578547473`，原版 server ID `5d5b4a78-af34-4296-9750-d4aac369d8ea`。双方 checksum frame 1505 的原版 checksum 均为 `8352991`；desync/resync 均为 0。每侧三条公共 v1 move 收据；关键移动分别由另一玩家的当前合法可见样本确认。测试调用 public v1 handler，再经过 HTTP native player bridge 和原版网络；没有直接修改单位位置。

## 正式 Agent 对局

同一张原版 `maps/skirmish/[p2]Small_Island (2p).tmx`，LOS fog、4000 初始资金、请求 speed 4。A 固定代表 Legacy，B 固定代表 Octopus；换边改变其 host/join 和槽位，不改变参与者身份。

| Run / match | Legacy slot | Octopus slot | Legacy attempts / queued / rejected | Octopus attempts / queued / rejected | 原版结果 |
|---|---:|---:|---:|---:|---|
| `m2-final-swap/001` | 0 | 1 | 90 / 88 / 2 | 159 / 158 / 1 | TIMEOUT，winner=null |
| `m2-final-swap/002` | 1 | 0 | 69 / 68 / 1 | 128 / 128 / 0 | Legacy VICTORY，Octopus DEFEAT |

第二局双方分别推进到约 320–322 游戏秒；Legacy 原 BattleClient 输出自然 VICTORY summary。两局最终同帧 checksum 分别为 `121610080@28896`、`124122378@19866`，两端一致，desync/resync 均为 0。三处拒绝加另一局一处拒绝来自 gateway 对当前不再属于 live-own 集合的旧单位命令检查；拒绝不算已排队。

`m2-selfplay` 双方分别 25/24/1、39/39/0（attempts/queued/rejected），checksum `45825951@7224` 一致。自对弈是有限预算运行，未产生原版胜负。

这些结果证明桥的运行闭环，不用于比较 Agent 强弱。生产、建造、攻击及升级有真实 Agent 行为/己方后续观察；只有 M0 move 做了逐命令的远端合法可见效果证明。所有普通 queue receipts 仍标记 `executedEffect=UNKNOWN`。

## 产物与原始记录

- [validation.json](validation.json)：精选最终结果、精确身份和修正后的命令计数。
- [build-manifest.json](build-manifest.json)：实际运行 JAR 和全部编译源 SHA256。
- [candidate-overlays.zip](candidate-overlays.zip)：测试过的 bridge 和两种 adapter overlay；解压到仓库根目录后得到 `build/`。不包含或修改商业引擎资源。
- [raw-evidence.zip](raw-evidence.zip)：188 个日志/报告/观察文件，含失败及中间版本；目录名区分候选。
- [files.json](files.json)：归档逐文件 SHA256；[summary.json](summary.json) 是含历史运行的归档索引，不等于所有历史运行通过。
- [corrected-command-counts.json](corrected-command-counts.json)：保留来源哈希的历史计数修正。

最终 bridge SHA256：`765cadc84e505955e2b313a3c8e35a1f87cfd0f60d13b2229e45a779089d224b`。

Octopus overlay SHA256：`450bccc5b500f14f90fdfbed15bc919a3d20484956bef8fad78fba73520aa3d6`。

Legacy overlay SHA256：`435075bc3194fa03231df4063e9c231ffe1f0adfc74a542b3381e85d521e0f4e`。

Raw archive SHA256：`77c67badb2cf2c0d68712ec9d5090e8c2fbfa661e00b5f3aede893df62f6ef0b`。

## 保留的失败与修正

1. 初始环境的默认沙箱禁 socket，第一次 smoke 失败；获准启用 loopback 后同一原包通过。系统 Java 需补 `LD_LIBRARY_PATH` 指向已安装 JDK 的 lib/server。
2. 早期 network lobby 报告对 null server ID 序列化失败；已修。最初 native ready ACK 缺失导致开局等待超时；改为原版 loaded ACK 后正常开局。
3. 首轮混合对战一侧 Legacy 首厂 queued 后没有执行。精确原地图回归确认 bridge 把 native `NONE` 建筑类型误用于放置 terrain cost，漏判矿点。现按原版放置规则映射 LAND，拒绝矿点重叠，替代地点经原版检查合法；最终两边均完成首厂。
4. 最早断线探针把 HTTP EOF 早于 native peer-loss 更新误判为 crash。现等待最多 2 秒获取原版连接证据，最终 native disconnect 试验通过。
5. 原 `attempted` 漏计 gateway 在上游之前拒绝的命令。现独立计算 native/gateway 拒绝和非命令拒绝；原始两局报告保留，修正计数附原始文件 SHA，不伪装成新的对局。
6. 中间 `m0-final/match.json` 在最终 batch 生成后出现旧快照恢复迹象，保留的 mtime 早于结束而 ctime 晚于结束；具体外部写入来源未证实。该次持久化验收标 FAIL，raw 保留。现在最终全文回读、batch 内报告 SHA 和独立 `--verify-run` 检查均为 gate。替代 `m0-verified`、正式混合对局、自对弈及最终断线报告已独立通过一致性检查。

被 supervisor 结束的 Agent 报告可能仍为 `.jsonl.partial`，按原样保留；不会把它们改写成完整 Agent summary。原版结果归属来自双方 native flags，而不是报告文件名或进程退出码。

## 可复跑与剩余边界

使用根 README 的构建/运行命令；最终测试入口为 `RWBRIDGE_NATIVE_TESTS=1 python tools/test_bridge.py`（Windows PowerShell 使用 `$env:RWBRIDGE_NATIVE_TESTS='1'`）。运行后用 `python orchestrator/run_match.py --verify-run RUN_DIRECTORY` 校验持久报告。

Windows GUI 步骤见 [本地验收](../../docs/WINDOWS_HUMAN_VALIDATION.md)。尚未证明真实桌面人机互通、密码大厅、Steam 邀请、NAT、任意 mods/custom maps、多玩家联盟图与自动 reconnect。v1 不支持重新绑定；断线后建立新进程/session。当前仍是 dual native client，不是单进程 PlayerContext。公共观察会显式记录跨 endpoint 的采样帧，不声称同帧原子快照。

下一工程断点：按 Windows 原版 LAN 步骤完成 Human-vs-Agent GUI 验收，使用同一 native transport 留存双方实际可见效果和结果画面。

# Codex 交接：2-instance parallel runner v0（2026-09-27）

给 DeepSeek、GPT Sol 5.6 和 ChatGPT：我已按 `Codex_现在可以开始_2026-09-27.md` 授权的边界完成双实例无头编排。请以 `CURRENT_STATE.md` 与本文件作为这轮现状；策略语义没有改动，也没有启动用户的桌面比赛入口。

## 实现与安装

- `游戏环境/Rusted-Warfare-1.15-Agent-0.07/tools/run_headless.py` 增加 `--parallel-pair`，必须配合 `--episodes 2`；不加开关时原有顺序模式保留。两个 episode 各自 stage、启动 Java 引擎和客户端，使用不同的工作目录、端口、session、报告目录与锁路径。
- 就绪屏障在两进程存活时检查 `/health` 的端口、工作目录、报告目录、JAR 路径及哈希，再观察两个 session 的帧数同时前进。每个控制阶段前重新核对所有权。每份新原始报告须只包含本 episode 的 session；报告 SHA256、PID、清理结果写入 `episode.json`、`batch.json` 和 `parallel-proof.json`。
- A 提前失败不会抹掉 B 的结果；整批返回非零。Ctrl+C 会请求本批进程停止并回收客户端；直接强杀 Python 编排器仍可能留下子进程，详见 `docs/HEADLESS_CN.md`。
- 源包及 `P1F-冒烟环境/tools/run_headless.py` SHA256 均为 `7d032322cbe9ba1c95ae9ed575f68975d3bdd67ea3dd38abe483293f90cb3b76`。安装 JAR 与 JDK 17 重建的 `developer/dist/rw-agent-bootstrap.jar` whole-file SHA256 均为 `cd586ff422ad6e81cf64ad5126f361cfb73d84e019b64a9d7700fe190692943b`；二者 contentDigest 仍为 `6fec7a0cc1920e3ede94ac408a88788d7f604a89d6d701cecf6448e57015294`，`aVsInstalled/aVsDist = []`。环境根冻结 JAR `0681b4f7…` 与 game-lib `8a550a37…` 哈希未变。

## 已验证

| 验收 | 结果 | 原始目录 |
| --- | --- | --- |
| 双实例 smoke | 2/2 PASS；屏障证明两个 PID 同时存活、各自帧数推进；port/session/cwd/reportDir/lockPath 不同 | `C:/Users/Administrator/Documents/Codex/2026-09-27/g-deepseek/work/parallel-tests/run-20260927T064507-d2b0a2` |
| 故障注入，强制结束 A | A=FAIL、B 的完整 suite=PASS；B 在 A 退出后继续产生控制报告；无引擎进程残留 | `C:/Users/Administrator/Documents/Codex/2026-09-27/g-deepseek/work/parallel-kill-a/case-20260927T144923-15932/run-20260927T064924-0d9892` |
| 故障后的新批次 | 双实例 suite 2/2 PASS，两边都创建各自的 `economy.lock`，原始报告会话未串局 | `C:/Users/Administrator/Documents/Codex/2026-09-27/g-deepseek/work/parallel-clean-rerun/run-20260927T065042-1b2ba4` |
| 安装位置复验 | 用冒烟环境的 runner 与安装 JAR 再跑双实例 smoke，2/2 PASS | `C:/Users/Administrator/Documents/Codex/2026-09-27/g-deepseek/work/installed-smoke/run-20260927T065905-fc6a1a` |

上述是原生无画面 E2/集成证据，详细的独立复核、逐份报告 SHA/session 和小型原件包见 `助手交接/evidence/parallel_runner_v0.txt`、`parallel_runner_v0_runs.zip`。原件包只收 JSON/JSONL 证据，没有游戏资产、设置、存档或回放。`parallel-proof.json=PASS` 只证明同一时段的进程与身份；整批通过另需两个 `episode=PASS` 和退出码 0。

完整回归：用游戏自带 JDK 13 运行统一入口 `developer/test-win.ps1`，Java harness 17 项 + Python 10 套，failed steps 0；`test_battle_client.py` 为 78 用例，新 `test_headless_parallel.py` 为 7 用例。JDK 13 编译相同 Java 源码会得到不同的 class 内容摘要，因此交付 JAR 随后由项目原定 JDK 17 重建；重建与安装后均验明 `6fec7a0c…`。Codex 子进程内的 JDK 17 `Selector.open()`/loopback 故障仍属已记录的执行环境问题；在本轮前，宿主机 JDK 17 原有 17+9 基线全绿。Java 源码本轮未修改；真实无头验收使用的是既定 `6fec7a0c…` 安装 JAR。

早期探索中，故障注入控制脚本先因 Windows GBK 解码错误中断；修正后一次 120 游戏秒的 match 中 B 继续运行，但按既有规则因时间预算到达而给出 PARTIAL。两次都未作为通过证据。最终使用已验证可完成的 suite 取得上表的 A 失败/B 成功结果。

## 修改文件与边界

- `tools/run_headless.py`；`developer/tests/test_headless_parallel.py`；统一回归入口 `developer/test-win.ps1`、`developer/test.sh`；`docs/HEADLESS_CN.md`；源包和冒烟环境中的运行器副本。
- `CURRENT_STATE.md` 的根目录和 evidence 副本保持同一 SHA；`candidate_sha_lineage.json`、`EVIDENCE_INDEX.md` 与 `CHATGPT_HANDOFF.zip` 随本轮同步。
- 没有做 4 实例、同局双 Agent、self-play、策略/经济语义改动或用户桌面实机双开。后续方向请由你们按原交接规则裁决，不要把此轮无头通过写成策略 E4 实机通过。

回滚备份在 `C:/Users/Administrator/Documents/Codex/2026-09-27/g-deepseek/work/runner-backup-2026-09-27/`，包括改动前源包/冒烟环境 runner 与安装 JAR；当前无回滚需求。

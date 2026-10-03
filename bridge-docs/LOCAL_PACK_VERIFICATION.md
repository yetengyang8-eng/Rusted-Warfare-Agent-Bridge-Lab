# Local Pack Verification — 2026-10-03

本工作区已在新目录中做最小原生 headless 自检。

执行：

```text
python agents/octopus-g42/tools/prepare_headless_engine.py --archive engine-relay/headless-engine-1.15.zip --manifest engine-relay/HEADLESS_ENGINE_MANIFEST.json --out .engine/rw115
python agents/octopus-g42/tools/run_headless.py --game-dir .engine/rw115 --agent-jar binaries/octopus-g42-83c09fb.jar --mode smoke --speed 4 --timeout 120 --out headless-runs/bridge-pack-smoke
```

结果：
- engine relay：PASS，1209/1209 文件验证通过；
- `game-lib.jar` SHA256 匹配 `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`；
- Octopus G4.2 JAR smoke episode：PASS；
- 本验证只证明远端工作包可以解压并运行现有单实例原生 headless smoke；
- **不证明** same-game multiplayer、双玩家 fog、network command、Agent-vs-Agent 或 Human-vs-Agent 已实现。

`.engine/` 与 `headless-runs/` 已加入 `.gitignore`，不作为仓库输入提交。

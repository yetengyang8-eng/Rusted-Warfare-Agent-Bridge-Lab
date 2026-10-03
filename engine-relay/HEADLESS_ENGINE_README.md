# Headless 原生引擎测试包

本目录现在提供 `headless-engine-1.15.zip`，用于让无法访问用户 G: 盘的 Astra / Codex 在自己的 Java 环境中复跑本项目的原生无画面测试。

包内只包含当前 `tools/run_headless.py` 实际要求的四项：
- `game-lib.jar`
- `assets/`
- `libs/`
- `res/`

不包含 Windows EXE、Steam 组件、用户存档/Replay、preferences 或 bundled JVM。运行方需要自行提供 Java 8+（项目当前推荐 JDK 17）。

## 身份
- Rusted Warfare PC 1.15 Build #28 / Game Code 176
- `game-lib.jar` SHA256: `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`
- `game-lib.jar` Git blob SHA1: `4d4034ac49311bfcb8870f49a56d4d01d622b3ec`
- ZIP SHA256: `6e73da52173f12f3873ff092081287b58a0177b1b1b985092cc5b32b959e6e30`

同一个 `game-lib.jar` 已公开存在于 `TapeRTS/Tape` 的 `1.15/game-lib.jar`；GitHub 记录的 blob SHA 与本项目本机文件的 `git hash-object` 完全一致。`HEADLESS_ENGINE_MANIFEST.json` 记录 1209 个文件的逐文件 SHA256。

## 使用
```text
python tools/prepare_headless_engine.py --out .engine/rw115
python tools/run_headless.py --game-dir .engine/rw115 --agent-jar YOUR_AGENT.jar --mode smoke --speed 4 --timeout 120 --out headless-runs/smoke
```

`prepare_headless_engine.py` 会先验证 ZIP SHA，再解压并逐文件核对 manifest；失败时不应继续实验。

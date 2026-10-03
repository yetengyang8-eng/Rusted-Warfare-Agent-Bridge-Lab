# 第三方公开参考源

远程 Agent 不需要复制这些仓库进本项目；需要源码/字节码/地图/协议证据时可直接访问原仓库。冻结过的 HEAD 记录见 `knowledge/SOURCE_MANIFEST.json`。

- `https://github.com/skywater275/rw_analysis` — 原版 AI、地图、网络、寻路、单位/动作、存档等广泛逆向资料。
- `https://github.com/Crystalhihihi/rusted-warfare-llm-pilot` — Java Agent / Python LLM / replay parser / 合法迷雾相关实现参考。
- `https://github.com/TapeRTS/Tape` — 版本字节码/映射；`1.15/game-lib.jar` 与本项目冻结 1.15 JAR 的 Git blob SHA 完全一致。
- `https://github.com/coolomet/RustedWarfare` — 1.15 反编译/部分去混淆源码，只作只读语义参考；重建 JAR 不视为原版等价物。
- `https://github.com/NaomiUGR/Rusted-Warfare-Graphics-Repository` — 1.15 Modding Reference、默认单位、地图/tileset/图形资源参考。
- `https://github.com/RWPP-Team/RWPP` — 玩家/队伍/多人客户端与服务端方向的未来参考。
- `https://github.com/n9tank/rwTool` — replay/save → TMX、地图处理相关工具参考。

## 1.15 `game-lib.jar` 等价性

GitHub 上 `TapeRTS/Tape` 的 `1.15/game-lib.jar` blob SHA 是 `4d4034ac49311bfcb8870f49a56d4d01d622b3ec`。本项目本机冻结文件执行 `git hash-object` 得到同一个值，且本机 SHA256 为 `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`。

因此需要独立核验 JAR 来源时，可以同时核对 `HEADLESS_ENGINE_MANIFEST.json`、SHA256 和该公开 blob 身份；不要只凭文件名或版本字符串。

# Rusted Warfare Agent 版本基线

更新时间：2026-09-29

## 游戏基线
- 游戏：Rusted Warfare PC 1.15
- 原版目录：`G:\deepseek 工作台\游戏环境\rustedwarfare PC 1.15 原版`
- 原版 `game-lib.jar` SHA256：`8A550A37E2D8A5430866090D4E7D5892F9010B47F52A5A09350FC66C620DEEC9`
- P1F 冒烟环境 `game-lib.jar` SHA256 与原版一致：`8A550A37E2D8A5430866090D4E7D5892F9010B47F52A5A09350FC66C620DEEC9`

## 当前可体验 Agent 基线
- 环境：`G:\deepseek 工作台\游戏环境\P1F-冒烟环境`
- VERSION：`0.07-alpha1`（版本字符串未随近期 Recon 小步更新）
- `rw-agent-bootstrap.jar` SHA256：`0C53BDFA1B545088E15FA6AB4269216B5C3FA586B00738BF00F5770AC5ADC71A`
- 此 JAR 是最近核验过的 World Model / Recon v0.2 可体验候选。

## 开发环境注意
- `G:\deepseek 工作台\游戏环境\Rusted-Warfare-1.15-Agent-0.07` 的 VERSION 同为 `0.07-alpha1`。
- 其中 `rw-agent-bootstrap.jar` SHA256：`0681B4F7EF632A9C9372FFF418FEC591F240894FD99373FF6C7CA3B72D4A9EB1`。
- 因此“版本字符串相同”不代表 JAR 内容相同；工程判断必须优先看 SHA/contentDigest。

## 维护规则
每次切换正式体验候选时，更新本文件中的 Agent SHA；游戏 `game-lib.jar` 若变化，视为新的冻结基线并重新核对逆向资料。
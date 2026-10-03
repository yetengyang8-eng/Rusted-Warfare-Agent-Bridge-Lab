# 已核验事实（只放高可信结论）

本文件不是资料摘抄。只有已经由当前冻结版本源码/字节码、可复现测试、独立交叉验证或真实原生实测确认的结论才进入这里。

证据等级：E0观察；E1 trace/源码/字节码；E2可复现测试；E3独立交叉验证；E4真实原生游戏验收。

## 版本身份
- [E1] 当前原版与 P1F 冒烟环境的 `game-lib.jar` SHA256 均为 `8A550A37E2D8A5430866090D4E7D5892F9010B47F52A5A09350FC66C620DEEC9`。
- [E1] 当前可体验 P1F Agent JAR SHA256 为 `0C53BDFA1B545088E15FA6AB4269216B5C3FA586B00738BF00F5770AC5ADC71A`。
- [E1] `VERSION=0.07-alpha1` 不能单独证明 Agent 内容相同；已有同版本字符串但不同 JAR SHA 的实例。

## 已确认社区资料勘误
- [用户实机确认] `mechArtillery` 火炮机甲价格：1400；v2.6单位表写成1600。
- [用户实机确认] `mechLightning` 特斯拉机甲价格：5200；v2.6单位表写成5500。

## 待逐步沉淀的栏目
- 单位：price / movementType / maxSpeed / sightRange / attackRange / target domains。
- 地图：tile语义 / PathingOverride / buildability / movement passability。
- Fog：原生 fog mode、合法 visibility 判定、单位视野范围。
- Replay：字段、checksum、命令流与版本差异。
- 网络：player/team slot、command ownership、独立 POV。

若新结论与这里冲突，先记录证据和版本，不直接覆盖；完成复核后再修改。
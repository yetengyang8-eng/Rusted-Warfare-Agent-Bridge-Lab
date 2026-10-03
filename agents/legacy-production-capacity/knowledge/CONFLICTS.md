# 冲突、勘误与未决项

范围：本任务五专题与核心单位。这里保留定向核对中发现的全部冲突/歧义；不声称已验证整个资料库每个字段。原文件不回写。详见同包原始快照及 `SOURCE_MANIFEST.json`。

| ID | 来源说法 / 易误用处 | 冻结证据与处理 | 状态 |
|---|---|---|---|
| C01 | rw_analysis `docs/10-pathfinding/MOVEMENT.md:17–24` 将 ao.b=WATER、c=AIR、d=GROUND、e=AMPHIBIOUS、f=SUBMARINE、g=HOVER、h=CUSTOM | ao 字节码为 NONE/LAND/BUILDING/AIR/WATER/HOVER/OVER_CLIFF/OVER_CLIFF_WATER；正文与候选均用冻结 enum | 已纠正，E1 |
| C02 | `docs/05-map/MAP-SYSTEM.md:225,253–255,445` 写 basic/los/noFog | 原生 b 的字符串是 none/map/los；basic/noFog 不是该解析器支持的模式 | 已纠正，E1 |
| C03 | 同文档 260–261、422–423 将 map.M/N 解释为永久/当前玩家迷雾 | b.M/N 是 smoothFog 缓存；player.N 才是该玩家动态 Fog | 已纠正，E1 |
| C04 | 同文档称当前 Fog 每帧更新/重置 | b.f(float) 在 E&&F 下计时>260才走此回暗段；其它揭示调用独立存在 | 已纠正，E1 |
| C05 | 社区 v2.6 单位表 mechArtillery=1600、mechLightning=5500 | 原版 `mechs_large/mech_artillery.ini`/`mech_lightning.ini` core.price 分别1400、5200，与既有用户实机勘误一致 | 原值保留，静态再次确认 |
| C06 | extractorT2 price=2100 易当升级费用；extractor.ini 有旧1200注释 | T1 core.price=700；action_upgradeT2.price=1400；T2 core.price=2100；注释不是生效键 | 候选分字段，E1 |
| C07 | tank 与 c_tank、artillery 与 c_artillery、extractor 与 extractorT1、turret 与 c_turret_t1 重名/替换 | 原 INI overrideAndReplace 明确映射。正常原版 bundled definitions 与只加载原生类的旧测试夹具不能混为一类运行身份 | 静态别名已给出；仍要记录实际 resolved type |
| C08 | canAttackLand 字面看似只针对陆地 | y.k/j.k 在排除 air/submerged 后判表面；NDT也明确包括水面 | 已纠正，E1 |
| C09 | 原始 af/ag=true 或 m()>0 被理解成可攻击 | builder l=false、m=30；工厂 l=false、m=0；总开关与 raw继承值分开 | 候选输出有效域，E1-derived |
| C10 | builder 速度固定0.8、转速固定3.8 | 冻结 e.b 的 cK() 分支返回0.6/1.7；cK是液体位置判定，非枚举名比较 | 保留条件值，E1 |
| C11 | NDT 单位代码 D376 “炮塔可攻击空中覆盖 [attack]” | j.k 保留单位级域，分炮塔 a(...) 另检查炮塔域；不能用炮塔true绕过普通选敌的单位级false | 限定解释，E1；特殊强制动作未覆盖 |
| C12 | 只用 s() 推断未完成单位视野 | custom.j.c(boolean) 才应用 dh；scout 完成22/未完成15；-1表示沿用正常视野，不是0 | 已纠正，E1 |
| C13 | 把 rawSpeed 当世界单位/秒或直接按60倍写进 gameTimeMs 模型 | PDF p4是名义60Hz说明；当前 runner b(1f,16) 与墙钟节奏不同 | raw保留；动态换算待校准 |
| C14 | water=false 看似取消水属性 | g解析只检查键非null；false字符串同样置位 | 已纠正，E1 |
| C15 | block-land 仅阻LAND；small-rock不可通行 | block-land/large-rock j=-1作用于该成本计算类型；small-rock j=40，仍可通行 | 已纠正，E1 |
| C16 | NDT 地图代码 D33 的单数tree描述延续trees阻挡 | g中tree分支空语句；trees才置k。其“被废弃”备注应保留 | 不合并键，E1 |
| C17 | NDT 地图代码 D31 称block-buildings在1.14无效 | 1.15 g确实解析l=true，但本轮未完成实际建造消费链/实测 | 版本不明/效果待验，不承诺独立禁建 |
| C18 | NDT water-bridge“都能通过、建筑都可建造” | f只使WATER非水限制例外；water/cliff/j等共存标记及完整放置检查仍可能拒绝 | 社区绝对表述不采用 |
| C19 | PathingOverride 被做成只追加阻挡；或声称九图覆盖了该测试 | 原生非空覆盖先清零重算；九图没有该层 | 规则E1；运行验证未做 |
| C20 | b.e(c,r) 被当Ground；静态资源坐标被当可见资源 | b.e返回Items；当前Agent先过visibility再读i；离线资源总数98不授权实时预读 | 已纠正，E1 |
| C21 | NDT D54“不写和无效果一致”被理解成无雾 | 缺省走平台/地图/对局配置；九图均缺省，当前runner显式LOS | 不采用该推论 |
| C22 | mapping CSV 的 verified 标签被当语义保证 | mappings.csv:41 把units.d.j叫BuilderUnit；实际builder由ar$52构造units.e.b，d.j继承bq而非单位am | 字段存在≠语义正确；优先混淆类+descriptor |
| C23 | VERSION=0.07-alpha1 相同，或历史 CURRENT_STATE 段落旧SHA被当最新安装身份 | P1F当前0c53…与开发根目录0681…不同；用本包记录的文件SHA和来源路径 | 已记录版本边界 |
| C24 | 将 PDF、NDT、JSON 中示例值写成默认/核心单位值 | 如 NDT E533=1.2是示例；moveSpeed在movement/leg两节不同；price在core/action等不同 | 保留section/key；示例不进能力真值 |

## 只保留为线索的内容

- NDT《单位代码》B87/88、B301–306、B376–379、B384、B532–542、B595、B872：已抽取对应单元格，限定到视野/攻击/移动/动态stats。已由原版支持的规则见专题；没有由NDT单独批准任何核心价格、动态射程或速度。
- NDT《地图代码》B12、B23–34、B38–41、B46、B54、B61：属性键、覆盖层与Fog线索；建造/降落的绝对描述和“1.14无效”等版本备注未自动提升。
- 社区 JSON 本轮抽取28条、保留完整 section 与 JSON Pointer，见 `evidence/COMMUNITY_CROSSWALK.json`。它没有独立的冻结原版构建指纹；缺失映射、逻辑表达式示例与其它章节语义继续是 COMMUNITY_CLUE_ONLY。
- `星星版铁锈机制库_清洗转写.txt` 是清洗转写来源，本轮没有逐条建立原生版本/测试链，未从中填充任何核心字段。未涉及专题的攻略、DPS、收益或极限行为不在已核验清单中。
- PDF 标注1.15，足以作字段说明的次级参考；它不是所有动作路径和时间基准的运行真值。

## 需要下一轮解决的明确问题

1. 将静态候选地形代价与已初始化原生网格逐格/分类型比对；为原九图不存在的覆盖层准备受控样本。
2. 使用合法实例核对 active resolved type 与基础/当前能力区别；覆盖动态LogicBoolean、升级和炮塔限制。
3. 校准以 gameTimeMs 计的最大速度与时间基准，避免用墙钟或名义帧率偷换。
4. 如果产品要实现禁建语义，再补block-buildings消费链与原生放置验收；当前只能保留标记。
5. 实局验证Guard与Recon行为；静态完成不等于回归/E4通过。

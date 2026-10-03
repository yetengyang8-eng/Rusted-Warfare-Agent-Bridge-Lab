> 历史交接记录：其中前三项已由 0.05 完成，当前交付与后续优先级见 DELIVERY_CN.md。

# 工程状态与交接 / 2026-09-20

## 当前成果

交付版本0.04-alpha1：在已实机通过的开局之上，新增已有工厂持续出兵与单建造者可见矿点扩张并行调度。默认新增8辆坦克、最多3座矿；预算预留、待扣款命令保留预算、独立任务验收、资产存活监控、任务总上限。本版已有三份实机报告：缺厂零下令拒绝；无合法矿时8新坦克PASS；额外扩大视野后1新矿+8新坦克PASS，已确认并行。连续多矿与自主侦察尚未验证。

本次只需运行 RW-Agent-Develop.bat，收取 development JSONL。用户额度约4%，希望少讨论、交付完整可运行阶段。不要重复要求往返、单厂单坦克测试。更新需要重启：可保存已完成开局的对局，换版读档后运行Develop；没有存档才先运行Opening准备。

## 已实机通过

- 0.00：原版 Java Agent 启动、状态读取、一次移动。
- 0.01-alpha2：3 份往返报告 PASS；两局 2200×2200、一局 2900×2900。
- 0.02-alpha2：economy-1789911485173-723e3284(1).jsonl，PASS。builder=4、factory=7、c_tank=10；2 次下令、55 次观测、26.726 秒。对应副本 acceptance-0.02-alpha2.jsonl。

- 0.03-alpha2：opening-1789914599047-219de4b0.jsonl，PASS。62.189秒、5次下令、133次观测；extractorT1=6、landFactory=8、c_tank=11/12/13。副本 acceptance-0.03-alpha2.jsonl。

## 已修复的重要错误

1. 0.01-alpha1 把地图半格偏移 p/q 当成格数，导致 2200×2200 错报 200×200。必须使用 bL.i()/j()，格数是 C/D，格尺寸 n/o。不要改回旧映射。
2. 0.02-alpha1 只认 Java enum tank，遗漏原版资源自带 c_tank 的 overrideAndReplace: tank；五份实机报告均 plan HTTP409、commands=0。必须使用 custom.l.c(as) 解析替换，再匹配实际菜单动作。客户端验收跟随 plan.productType，不能硬编码 tank。
3. 建筑进度 cm 不可保留三位小数，否则 0.99999 会误判完工。使用原始 float 文本，>=1 才进入下一步。

4. 0.03-alpha1 重复犯了替换遗漏：extractor 被原版 extractorT1 替代。所有角色共用 resolved(as)，模板必须调用实际类型的 a(true)，不能强转 ar 或调用会创建世界单位的 a()；客户端建筑验收跟随计划类型。原版资源的 extractorT1 占地与旧枚举不同，不能只修名字。新增旧枚举与实际 custom.j 模板双路径检查。

## 开发入口

- bootstrap/RuntimeBridge.java：游戏线程、会话标识、状态、移动、超时。
- bootstrap/EconomyBridge.java：原版动作、工厂/抽取器建造、坦克生产、两种规划入口、地形视野检查、成功回执去重。
- client/EconomyClient.java：单厂单坦克与完整开局共享的观察/下令/验收逻辑。
- client/OpeningClient.java：完整开局启动入口。
- client/DevelopmentClient.java：单循环驱动建造/生产两个任务，预算预留、并行观察与有界验收。
- GET /economy/production-plan：只读选择已有空闲工厂，资金不足时仍可规划。
- developer/test.sh：构建 + 原版对象检查 + 开局/经济/移动客户端测试；结果在 docs/test-results.txt。

固定原版 game-lib.jar SHA-256：8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9。不分发原版依赖，不混入 RWPP/辅助框架，不直接生成单位、改坐标或改钱。

## 本轮具体边界

Develop只接管一座已有、完成、己方、空闲的陆军工厂和最多一个建造者；不开新厂，不复用旧单位计数，不断点恢复。每完成一座矿重新规划下一处可见矿；无地点就停止矿点扩张，持续生产到目标。PASS允许实际矿数低于上限，必须同时查看completedMines和expansionStatus。

预算：待提交矿成本优先保留；已提交但尚未观察到新建筑的矿成本也保留，避免途中资金被出兵耗尽。尚未观察到队列开始的生产成本同样保留。原生订单一旦出错即停止，不自动重试。

找矿仅600直线范围、当前可见合法占地；没有路径连通性或敌军威胁检查。全程600秒，单矿240秒、单兵120秒、预算120秒。原有Opening/Economy与Develop共用同目录economy.lock。其他目录、鼠标或旧移动脚本无法由该锁约束。

## 后续顺序

本次development JSONL已核对完成，证据详见acceptance-0.04.md。若无空矿但出兵完成，生产部分有效，不能因此宣称实机验证了连续开矿。下一批优先补路径可达性/受阻恢复、多建造者多工厂分配；随后推进合法视野下敌军观察与攻击、自动重开与批量评测。

长期目标仍是九图1v1合法视野完整对战。当前不是训练模型或成熟对战AI，0.7–0.9是目标阶段。不要把Agent路线与RWPP辅助框架版本混淆。


当前接续以START_HERE_CN.md与docs/SOL_NEXT_TASKS_CN.md为准；先做离线汇总、矿点诊断，再考虑启动预检。程序冻结0.04-alpha1，未因文档封存而虚升版本。


## 0.06-alpha1

已接入只基于实际观察的探索路径记忆、可见资源发现与扩矿闭环、原生坦克护卫确认、建造者威胁撤回、严格的目标完成判定，以及一键原始报告收集。新增策略详见 SCOUTING_CN.md；最终原版引擎验收与已知局限见 DELIVERY_CN.md。

# 0.04-alpha1实机验收

## 三次报告

1. b2cf8dd8：setup阶段HTTP409，缺少已完成空闲陆军工厂。commands=0，是已声明前置条件的拒绝；不是游戏崩溃或命令执行失败。后续启动体验可改善。
2. 9f623f78：69.926秒，commands=8，observations=139，completedMines=0，completedTanks=8，PASS。复用工厂4，新坦克8–15。找矿时eligibleBuilders=1、visibleResourceCandidates=1但没有合法地点。
3. fc2d3b43：69.922秒，commands=9，observations=139，completedMines=1，completedTanks=8，PASS。复用工厂4；建造者2在(750,1890)建extractorT1=16；新坦克17–24。矿started至completed之间有2条tank_completed事件，验证实际并行。

原始JSONL、bootstrap及game日志在acceptance-0.04/，summary.json便于机器读取。

## 为何停下

第三次矿完成后，建造者约位于(831.033,1891.971)，再次规划得到eligibleBuilders=1、visibleResourceCandidates=3，结果仍为no legal opening site。源码中这个计数是搜索方框内可见资源格与建造者的配对数，包含尚未通过圆形距离、占用及完整占地验证的候选。因此3不代表还有3座能建的矿，也不能据此认定“只是迷雾”。

客户端收到该明确409后设置NO_VISIBLE_LEGAL_SITE，结束本轮找矿；工厂独立完成剩余坦克。这与0.04的有界任务设计一致。maxNewMines=3是上限，不是必定完成3矿的承诺。扩大视野、移位或其他环境变化后需要新一次运行才会重新找矿。

## 已验证与未验证

已实机验证：没有工厂时零下令拒绝；复用已有工厂连续8次生产；新任务排除旧坦克；无合法矿仍能完成生产；有矿时开矿与生产并行；结束时资产仍存活。

未实机验证：连续新增多座矿、低资金竞争、跨水/复杂地形寻路、受攻击恢复、自动侦察、多建造者或多工厂。第三次视野由用户额外开启，不作为自然迷雾下自主扩张的证据。本机回归只能补充，不能替代这些实机条件。

## 附带问题

rw-agent-game(4).log在22:54:18记录自动存档临时文件重命名失败。末尾又出现Autosaved，日志展示有误导性。可建议用户另存新文件并验证；当前证据不足以定位原因，不要未经确认去修改权限、删除存档或覆盖游戏文件。

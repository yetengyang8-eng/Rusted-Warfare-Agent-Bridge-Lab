# 当前构建与回归入口

基线为 **RW-BASELINE-2026-09-30-GS-v1**。先读 [基线定义](../project-state/BASELINE.md) 和 [最新验证](../evidence/baseline-2026-09-30/VALIDATION.md)。

本仓库源码是 agent/src，资源是 agent/resources；不要照历史文档去编译旧游戏环境 developer 树。必须带上 resources/knowledge/UNIT_CATALOG_CANDIDATES.json。

## 工具与输入

- 带编译模块的 JDK 17，目标字节码使用 --release 8。
- Python 3 用于开发回归；本次本机可用 Python 的具体路径和版本见验收记录。
- 兼容 game-lib.jar 的 SHA256：8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9。
- 所有构建和测试在隔离源码副本中进行，产物进入副本 agent/build、agent/dist。不要并发跑共享端口或同一个输出目录的两份回归。
- 跨平台复制时保持知识资源既定 LF。中文路径需 UTF-8，并正确引用带空格的路径。

## 标准命令（在隔离仓库副本根目录）

Windows：powershell -NoProfile -ExecutionPolicy Bypass -File agent/test-win.ps1 "<game-lib.jar>" "<libs目录>"

Linux：bash agent/test.sh "<game-lib.jar>" "<libs目录>"

Linux 仅构建：bash agent/build.sh "<game-lib.jar>"

Windows 含非 ASCII 路径优先使用 test-win.ps1 的构建步骤；旧 build.bat 的 argfile 曾有编码问题。

两套回归入口应保持 21 Java harness runs / 16 Python suites 的集合一致；以当次实际日志为最终计数。真实 Windows/POSIX 文件语义的跳过项应显式列出，不能把某平台跳过的用例写成已验证。

## 候选与安装

不可变运行基线在 astra-deliveries/global-strategy-2026-09-30/rw-agent-bootstrap.jar。重建件必须登记 whole SHA、contentDigest、编译器和资源身份，再做内容差异核对。通过回归不意味着已部署，也不能自动继承不同 JAR 的实机结论。

本次工作不安装新件。以后安装需命名独立目标、备份、回退及安装后身份核对；不得运行历史文档中无条件复制到 P1F-冒烟环境 的命令。

## 历史资料

旧开发说明完整保存在 [归档 README](../project-state/archive/2026-09-30-pre-baseline/repository-agent-README_CN.md)，用于理解旧映射和事故；其中 developer 路径、用例数、旧候选、旧部署步骤不定义当前状态。

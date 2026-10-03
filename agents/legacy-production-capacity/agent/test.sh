#!/usr/bin/env bash
# Regression: the same 28 Java runs and 23 Python suites as test-win.ps1.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
game_jar="${1:?usage: test.sh /path/to/game-lib.jar /path/to/libs}"
libs_dir="${2:?usage: test.sh /path/to/game-lib.jar /path/to/libs}"
bash "$root/build.sh" "$game_jar"
classpath="$game_jar:$libs_dir/*:$root/dist/rw-agent-bootstrap.jar"
mkdir -p "$root/build/tests"
java -m jdk.compiler/com.sun.tools.javac.Main -cp "$classpath" -d "$root/build/tests" "$root/tests/SmokeHarness.java" "$root/tests/BridgeHarness.java" "$root/tests/EconomyHarness.java" "$root/tests/OpeningHarness.java" "$root/tests/ProductionPlanHarness.java" "$root/tests/DiagnosticsHarness.java" "$root/tests/PreflightHarness.java" "$root/tests/ScoutHarness.java" "$root/tests/TerrainMemoryHarness.java" "$root/tests/GuardHarness.java" "$root/tests/CapabilityHarness.java" "$root/tests/TargetCompatibilityHarness.java" "$root/tests/BuilderHarness.java" "$root/tests/ReachabilityHarness.java" "$root/tests/CombatHarness.java" "$root/tests/ReportCommitHarness.java" "$root/tests/ExecutionContractHarness.java" "$root/tests/StrategyContractHarness.java"
java -m jdk.compiler/com.sun.tools.javac.Main -cp "$classpath" -d "$root/build/tests" "$root/tests/LocalArmyContractHarness.java" "$root/tests/LocalCrisisContractHarness.java" "$root/tests/SurplusSpendingHarness.java" "$root/tests/MineInvestmentHarness.java" "$root/tests/EngineerProviderHarness.java" "$root/tests/NativeMorphHarness.java" "$root/tests/StrategyNativeHarness.java"
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" SmokeHarness
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" BridgeHarness
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" BridgeHarness disabled
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" EconomyHarness
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" OpeningHarness
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" OpeningHarness replacement
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" ProductionPlanHarness
python3 "$root/tests/test_development.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_opening.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_economy.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_client.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_match_bootstrap.py" "$root/dist/rw-agent-bootstrap.jar"

java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" DiagnosticsHarness
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" PreflightHarness
python3 "$root/tests/test_reports.py"

java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" ScoutHarness
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" TerrainMemoryHarness
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" GuardHarness
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" CapabilityHarness
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" TargetCompatibilityHarness
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" BuilderHarness
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" ReachabilityHarness
# Isolated harness only; do not enable diagnostics on the user's live game.
java --add-modules jdk.httpserver -Drwagent.reachabilityDiagnostics=true -cp "$classpath:$root/build/tests" ReachabilityHarness
python3 "$root/tests/test_frontier.py" "$root/dist/rw-agent-bootstrap.jar"

python3 "$root/tests/test_frontier_reports.py"

java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" CombatHarness
python3 "$root/tests/test_battle_reports.py"

python3 "$root/tests/test_battle_client.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_production_capacity.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_strategy_funding.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_specialist_lifecycle.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_local_army.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_surplus_spending.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_engineer_provider.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_target_compatibility.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_recon_client.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_recon_frontier_client.py" "$root/dist/rw-agent-bootstrap.jar"
python3 "$root/tests/test_headless_parallel.py"
python3 "$root/tests/test_ab_aggregate.py"
python3 "$root/tests/test_ab_campaign.py"

java -Xmx96m -cp "$classpath:$root/build/tests" io.rwagent.client.ReportCommitHarness

java -cp "$classpath:$root/build/tests" io.rwagent.client.ExecutionContractHarness

java -cp "$classpath:$root/build/tests" io.rwagent.client.StrategyContractHarness
java -m jdk.compiler/com.sun.tools.javac.Main -cp "$classpath" -d "$root/build/tests" "$root/tests/ProductionCapacityHarness.java"
java -cp "$classpath:$root/build/tests" io.rwagent.client.ProductionCapacityHarness
java -cp "$classpath:$root/build/tests" io.rwagent.client.LocalArmyContractHarness
java -cp "$classpath:$root/build/tests" io.rwagent.client.LocalCrisisContractHarness
java -cp "$classpath:$root/build/tests" io.rwagent.client.SurplusSpendingHarness
java -cp "$classpath:$root/build/tests" io.rwagent.client.MineInvestmentHarness
java -cp "$classpath:$root/build/tests" io.rwagent.client.EngineerProviderHarness
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" NativeMorphHarness
java --add-modules jdk.httpserver -cp "$classpath:$root/build/tests" StrategyNativeHarness
python3 "$root/tests/test_feedback_progress_audit.py"

python3 "$root/tests/test_global_strategy_audit.py"

#!/usr/bin/env python3
"""Focused native data-object authority/fog tests; this is not a multiplayer simulation proof."""
from pathlib import Path
import os
import argparse
import subprocess

ROOT = Path(__file__).resolve().parents[2]
GAME = ROOT / '.engine/rw115'
OUT = ROOT / 'build/player_scope/classes'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-placement",action="store_true",help="also compare the first factory candidate on original Small Island")
    args=parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    jdk = Path('/usr/lib/jvm/java-17-openjdk-amd64')
    if jdk.exists():
        env['LD_LIBRARY_PATH'] = str(jdk / 'lib') + ':' + str(jdk / 'lib/server') + (':' + env['LD_LIBRARY_PATH'] if env.get('LD_LIBRARY_PATH') else '')
    cp = os.pathsep.join([str(GAME / 'game-lib.jar'), str(GAME / 'libs/*')])
    sources = sorted((ROOT / 'bridge/src/io/rwagent/bootstrap').glob('*.java')) + [Path(__file__).with_name('PlayerScopeHarness.java')]
    subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-source','8','-target','8','-encoding','UTF-8','-classpath',cp,'-d',str(OUT),*[str(s) for s in sources]],check=True,cwd=ROOT,env=env)
    subprocess.run(['java','-classpath',str(OUT)+os.pathsep+cp,'io.rwagent.bootstrap.PlayerScopeHarness'],check=True,cwd=ROOT,env=env)
    if args.native_placement:
        baseline=ROOT/'binaries/octopus-g42-83c09fb.jar'
        native_cp=os.pathsep.join([str(OUT),str(baseline),cp])
        fixture_sources=[ROOT/'agents/octopus-g42/agent/tests/TerrainNativeCostHarness.java',Path(__file__).with_name('NativePlacementHarness.java')]
        subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-source','8','-target','8','-classpath',native_cp,'-d',str(OUT),*[str(s) for s in fixture_sources]],check=True,cwd=ROOT,env=env)
        work=ROOT/'build/player_scope/native-engine'
        if not work.exists():
            import sys
            sys.path.insert(0,str(ROOT))
            from orchestrator.run_match import stage
            stage(GAME,work,ROOT/'build/bridge.jar',baseline)
        subprocess.run(['java','-classpath',native_cp,'io.rwagent.bootstrap.NativePlacementHarness'],check=True,cwd=work,env=env)

if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Independent E2 native terrain comparison in a copied game tree.

Usage: python test_terrain_native.py GAME_ROOT PACKET_ROOT AGENT_JAR WORK_DIR
The work directory must not exist. Nothing under GAME_ROOT or PACKET_ROOT is edited.
"""

import base64
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET


GAME_SHA = '8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9'
MAPS = (
    ('beach_landing', 'beach_landing.json'),
    ('two_cold_sides', 'two_cold_sides.json'),
    ('small_island', 'small_island.json'),
)
FIXTURE_NAME = 'E2_PathingOverride_two_cells.tmx'


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(65536), b''):
            digest.update(block)
    return digest.hexdigest()


def require(ok, reason):
    if not ok:
        raise RuntimeError(reason)


def layer_gids(layer, width, height):
    data = layer.find('data')
    require(data is not None and data.get('encoding') == 'base64'
            and data.get('compression') == 'gzip', 'fixture source layer encoding changed')
    raw = gzip.decompress(base64.b64decode(''.join(data.itertext())))
    require(len(raw) == width * height * 4, 'fixture source layer length changed')
    return struct.unpack('<%dI' % (width * height), raw)


def packet_cost(grid, movement, x, y):
    row_index = y * int(grid['width']) + x
    consumed = 0
    for value, length in grid['costRle'][movement]:
        consumed += length
        if row_index < consumed:
            return value
    raise RuntimeError('packet RLE too short')


def make_fixture(stage, packet):
    map_file = stage / 'assets' / 'maps' / 'skirmish' / '[p2]Small_Island (2p).tmx'
    grid = json.loads((packet / 'terrain_grids' / 'small_island.json').read_text(encoding='utf-8'))
    require(sha(map_file) == grid['mapSha256'], 'Small Island source hash differs from packet')
    require(packet_cost(grid, 'LAND', 10, 10) == -1
            and packet_cost(grid, 'LAND', 55, 55) == 0, 'fixture cost baseline changed')
    tree = ET.parse(map_file)
    root = tree.getroot()
    width, height = int(root.get('width')), int(root.get('height'))
    require((width, height) == (110, 110), 'Small Island dimensions changed')
    layers = {item.get('name'): item for item in root.findall('layer')}
    require('PathingOverride' not in layers, 'source already has PathingOverride')
    ground = layer_gids(layers['Ground'], width, height)
    items = layer_gids(layers['Items'], width, height)
    require((ground[10 * width + 10], items[10 * width + 10]) == (192, 0),
            'source water control changed')
    require((ground[55 * width + 55], items[55 * width + 55]) == (36, 0),
            'source land control changed')
    first_tileset = root.findall('tileset')[0]
    require(first_tileset.get('firstgid') == '1', 'Mountain firstgid changed')
    rock = next((tile for tile in first_tileset.findall('tile') if tile.get('id') == '21'), None)
    require(rock is not None and any(prop.get('name') == 'large-rock'
            for prop in rock.findall('./properties/property')), 'gid 22 lost large-rock property')
    gids = [0] * (width * height)
    gids[10 * width + 10] = 1     # Ordinary tile: clears original water blocking for LAND.
    gids[55 * width + 55] = 22    # Mountain local id 21: large-rock, j=-1.
    encoded = base64.b64encode(gzip.compress(struct.pack('<%dI' % len(gids), *gids),
                                               mtime=0)).decode('ascii')
    layer = ET.Element('layer', {'name': 'PathingOverride', 'width': str(width),
                                 'height': str(height)})
    ET.SubElement(layer, 'data', {'encoding': 'base64', 'compression': 'gzip'}).text = '\n' + encoded + '\n'
    insert_at = next((index for index, child in enumerate(root) if child.tag == 'objectgroup'), len(root))
    root.insert(insert_at, layer)
    fixture = map_file.with_name(FIXTURE_NAME)
    tree.write(fixture, encoding='utf-8', xml_declaration=True)
    check = ET.parse(fixture).getroot()
    override = next(item for item in check.findall('layer') if item.get('name') == 'PathingOverride')
    require(sum(gid != 0 for gid in layer_gids(override, width, height)) == 2,
            'fixture must have exactly two override cells')
    return fixture


def execute(command, cwd, log_path, timeout=240):
    with log_path.open('w', encoding='utf-8') as output:
        process = subprocess.run(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT,
                                 timeout=timeout, check=False)
    return process.returncode


def run(game, packet, agent, work):
    game, packet, agent, work = (path.resolve() for path in (game, packet, agent, work))
    require(game.is_dir() and packet.is_dir() and agent.is_file(), 'source path missing')
    require(not work.exists(), 'work directory already exists; choose a new isolated path')
    source_jar = game / 'game-lib.jar'
    require(sha(source_jar) == GAME_SHA, 'frozen game-lib.jar SHA mismatch')
    catalog = json.loads((packet / 'TERRAIN_MAP_CANDIDATES.json').read_text(encoding='utf-8'))
    maps = {item['id']: item for item in catalog['maps']}
    for map_id, grid_name in MAPS:
        item = maps[map_id]
        grid = json.loads((packet / 'terrain_grids' / grid_name).read_text(encoding='utf-8'))
        source = game / 'assets' / 'maps' / 'skirmish' / item['filename']
        require(sha(source) == item['sha256'] == grid['mapSha256'],
                'map identity differs from packet: ' + map_id)
    work.mkdir(parents=True)
    try:
        for folder in ('assets', 'libs', 'res'):
            shutil.copytree(game / folder, work / folder)
        shutil.copy2(source_jar, work / 'game-lib.jar')
        shutil.copy2(agent, work / 'rw-agent-bootstrap.jar')
        require(sha(work / 'game-lib.jar') == GAME_SHA, 'staged game JAR mismatch')
        fixture = make_fixture(work, packet)
        java = game / 'jvm64' / 'bin' / 'java.exe'
        require(java.is_file(), 'bundled Java runtime missing')
        classes = work / 'test-classes'
        classes.mkdir()
        evidence = work / 'evidence'
        evidence.mkdir()
        cp = os.pathsep.join(('game-lib.jar', 'libs/*', 'rw-agent-bootstrap.jar'))
        harness = Path(__file__).with_name('TerrainNativeCostHarness.java').resolve()
        compile_command = [str(java), '-m', 'jdk.compiler/com.sun.tools.javac.Main',
                           '--release', '8', '-encoding', 'UTF-8', '-cp', cp,
                           '-d', str(classes), str(harness)]
        compile_log = evidence / 'compile.log'
        require(execute(compile_command, work, compile_log) == 0,
                'E2 Java harness compile failed: ' + str(compile_log))
        runtime_cp = os.pathsep.join((cp, str(classes)))
        results = []
        for map_id, grid_name in MAPS:
            item = maps[map_id]
            results.append((map_id, 'baseline', item['filename'], grid_name))
        results.append(('override_two_cells', 'override', FIXTURE_NAME, 'small_island.json'))
        report = {'schemaVersion': 1, 'status': 'RUNNING', 'evidenceLevel': 'E2',
                  'gameJarSha256': GAME_SHA,
                  'agentJarSha256': sha(work / 'rw-agent-bootstrap.jar'),
                  'terrainPacketSha256': sha(packet / 'TERRAIN_MAP_CANDIDATES.json'),
                  'fixtureSha256': sha(fixture), 'fixtureSourceMap': maps['small_island']['filename'],
                  'fixtureCells': [{'x': 10, 'y': 10, 'baselineLand': -1, 'overrideLand': 0},
                                   {'x': 55, 'y': 55, 'baselineLand': 0, 'overrideLand': -1}],
                  'cases': []}
        report_path = evidence / 'report.json'
        for case_id, mode, filename, grid_name in results:
            map_arg = 'maps/skirmish/' + filename
            grid_path = packet / 'terrain_grids' / grid_name
            command = [str(java), '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
                       '-cp', runtime_cp, 'TerrainNativeCostHarness', map_arg,
                       str(grid_path), mode]
            log_path = evidence / (case_id + '.log')
            rc = execute(command, work, log_path)
            log = log_path.read_text(encoding='utf-8', errors='replace')
            passed = rc == 0 and 'E2_TERRAIN_PASS mode=' + mode in log
            case = {'id': case_id, 'mode': mode, 'map': map_arg,
                    'grid': grid_name, 'exitCode': rc, 'pass': passed,
                    'gridChecks': log.count('E2_GRID_PASS'), 'log': str(log_path)}
            report['cases'].append(case)
            report['status'] = 'RUNNING' if passed else 'FAILED'
            report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            print(('PASS' if passed else 'FAIL') + ' ' + case_id + ' gridChecks=' + str(case['gridChecks']))
            if not passed:
                raise RuntimeError('E2 native case failed: ' + str(log_path))
        report['status'] = 'PASS'
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print('E2_NATIVE_TERRAIN_PASS cases=' + str(len(report['cases']))
              + ' report=' + str(report_path))
    except BaseException:
        raise


if __name__ == '__main__':
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    run(*(Path(value) for value in sys.argv[1:]))

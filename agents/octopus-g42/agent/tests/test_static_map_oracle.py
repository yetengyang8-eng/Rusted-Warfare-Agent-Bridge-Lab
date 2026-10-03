#!/usr/bin/env python3
"""Compare real /static-map/observe HTTP evidence with the repository static packet.

Usage: test_static_map_oracle.py HTTP_JSONL TERRAIN_MAP_CANDIDATES.json OUTPUT.json
This offline comparison does not change the historical packet or consume native map.A.
"""
import hashlib
import json
from pathlib import Path
import sys


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def compare(log_path, packet_path, output):
    log_path, packet_path, output = map(Path, (log_path, packet_path, output))
    packets = [json.loads(line) for line in log_path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    observation = next(row['response'] for row in packets
                       if row['requestPath'].split('?')[0] == '/static-map/observe'
                       and row['response'].get('status') == 'KNOWN')
    packet = json.loads(packet_path.read_text(encoding='utf-8'))
    source = next(item for item in packet['maps']
                  if observation['mapPath'] == 'maps/skirmish/' + item['filename'])
    checks = 0

    def require(value, reason):
        nonlocal checks
        checks += 1
        if not value:
            raise AssertionError(reason)

    bounds = observation['bounds']
    require(observation['staticOnly'] is True, 'runtime evidence must identify authorized static prior')
    require(observation['gameJarSha256'] == packet['gameJarSha256'], 'frozen rule source SHA differs')
    require((bounds['widthTiles'], bounds['heightTiles']) == (source['width'], source['height']), 'map dimensions differ')
    require((bounds['tileWidth'], bounds['tileHeight']) == (source['tileWidth'], source['tileHeight']), 'tile dimensions differ')
    coordinates = next(layer['resourceCoordinates'] for layer in source['layers'] if layer['name'] == 'Items')
    expected = sorted(x * source['height'] + y for x, y in coordinates)
    actual = sorted(tile['tile'] for tile in observation['resourceTiles'])
    require(len(actual) == len(set(actual)), 'runtime resources must be unique')
    require(actual == expected, 'runtime resource coordinates differ from repository Items resourceCoordinates')
    require(len(actual) == source['resourcesItemsLayer'], 'resource count differs')
    for tile in observation['resourceTiles']:
        x, y = divmod(tile['tile'], source['height'])
        require(tile['x'] == (x + .5) * source['tileWidth'] and tile['y'] == (y + .5) * source['tileHeight'], 'runtime world coordinate/index differs')
    for boundary in ('occupied', 'safe', 'buildable', 'dynamicReachability'):
        require(observation[boundary] == 'UNKNOWN', boundary + ' must remain UNKNOWN')
    report = dict(status='PASS', checks=checks, scope='INDEPENDENT_REPOSITORY_STATIC_RESOURCE_ORACLE',
                  nativeMapAConsumed=False, evidenceClass='OFFLINE_COMPARISON_OF_REAL_HTTP_PACKET',
                  mapId=source['id'], mapPath=observation['mapPath'], resourceTiles=len(actual),
                  expectedTiles=expected, actualTiles=actual, knowledgeId=observation['knowledgeId'],
                  httpEvidence=dict(path=str(log_path.resolve()), sha256=sha(log_path)),
                  repositoryOracle=dict(path=str(packet_path.resolve()), sha256=sha(packet_path),
                                        mapSha256=source['sha256'], field='maps[].layers[Items].resourceCoordinates'),
                  historicalPacketUnmodified=True, naturalMatch=False)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('STATIC_MAP_REPOSITORY_ORACLE_PASS checks=%d resources=%d report=%s' % (checks, len(actual), output))


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    compare(*sys.argv[1:])

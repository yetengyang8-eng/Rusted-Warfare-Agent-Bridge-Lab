#!/usr/bin/env python3
"""Compile the Recon client change without redistributing or requiring the game.

All native bridge/resource bytes come from the fingerprinted public candidate.
This is explicitly a client overlay, NOT a full source rebuild or native validation.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

BASE_SHA = '5741e241ce8ec1b921f36ed3274087609d0430f9281c44f6f62c096de84c5f54'
CLASSES = ('BattleClient', 'CommandArbiter', 'MoveExecution')
PREFIX = 'io/rwagent/client/'
META = 'META-INF/astra-recon-client-overlay.json'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def replaceable(name):
    return any(name == PREFIX + c + '.class' or
               name.startswith(PREFIX + c + '$') and name.endswith('.class')
               for c in CLASSES)


def build(root, base, output):
    if base.resolve() == output.resolve():
        raise ValueError('The frozen base must not be overwritten')
    if sha(base.read_bytes()) != BASE_SHA:
        raise ValueError('Base candidate fingerprint mismatch')
    sources = [root / 'agent/src' / (PREFIX + c + '.java') for c in CLASSES]
    with zipfile.ZipFile(base) as archive:
        original = {name: archive.read(name) for name in archive.namelist()}
    compiler = subprocess.run(['java', '-version'], capture_output=True, text=True, check=True)
    metadata = {
        'schemaVersion': 1, 'kind': 'CLIENT_OVERLAY_NATIVE_UNVERIFIED',
        'baseJarSha256': BASE_SHA,
        'sourceSha256': {str(p.relative_to(root)): sha(p.read_bytes()) for p in sources},
        'compiler': (compiler.stdout + compiler.stderr).strip(),
        'release': 8,
        'limits': ['Native bridge and knowledge resources are inherited unchanged.',
                   'No game engine, native match, self-play or performance validation is implied.'],
    }
    with tempfile.TemporaryDirectory(prefix='rw-recon-compile-') as temporary:
        classes = Path(temporary)
        subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main',
                        '--release', '8', '-encoding', 'UTF-8', '-cp', str(base.resolve()),
                        '-d', str(classes), *map(str, sources)], check=True)
        compiled = {str(p.relative_to(classes)): p.read_bytes() for p in classes.rglob('*.class')}
    if not compiled or any(not replaceable(name) for name in compiled):
        raise ValueError('Unexpected compiler output')
    updated = {name: data for name, data in original.items() if not replaceable(name)}
    updated.update(compiled)
    updated[META] = (json.dumps(metadata, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    # Stable ZIP metadata allows repeated builds with the same compiler to be compared byte for byte.
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(updated.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    changed = sorted(name for name in set(original) | set(updated)
                     if original.get(name) != updated.get(name))
    if any(not replaceable(name) and name != META for name in changed):
        raise ValueError('Unexpected change outside client overlay')
    report = dict(metadata, agentJarSha256=sha(output.read_bytes()), changedEntries=changed,
                  preservedEntries=sum(original.get(n) == data for n, data in updated.items()),
                  contentDigest=sha('\n'.join('%s %s' % (n, sha(updated[n])) for n in sorted(updated)
                                              if n != 'META-INF/MANIFEST.MF').encode()))
    output.with_suffix('.manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    result = build(root, args.base or root / 'astra-relay/runtime-overlay/rw-agent-bootstrap.jar', args.out)
    print(json.dumps(result, ensure_ascii=False, indent=2))

#!/usr/bin/env python3
"""Assemble numbered RAR volumes without renaming or modifying originals.
Requires the system libarchive library with RAR5 support. No third-party Python modules.
"""
import argparse
import ctypes as C
import ctypes.util
import hashlib
import json
import os
import re
from pathlib import Path, PurePosixPath

EXPECTED_GAME_SHA256 = '8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9'

def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

def assemble(source, output, manifest, expected_parts=18):
    numbered = {}
    for p in source.glob('*.rar'):
        m = re.search(r'part(\d+)', p.name, re.I)
        if m:
            number = int(m[1])
            if number in numbered:
                raise ValueError('Duplicate volume %s' % number)
            numbered[number] = p
    if set(numbered) != set(range(1, expected_parts + 1)):
        raise ValueError('Expected volumes 1..%d; found %s' % (expected_parts, sorted(numbered)))
    files = [numbered[i] for i in sorted(numbered)]
    for p in files:
        with p.open('rb') as f:
            if f.read(8) != b'Rar!\x1a\x07\x01\x00':
                raise ValueError('Not a RAR5 volume: %s' % p)
    if output.exists() and any(output.iterdir()):
        raise ValueError('Output must be empty; existing files are never overwritten')
    libpath = ctypes.util.find_library('archive')
    if not libpath:
        raise RuntimeError('libarchive with RAR5 support is required. On Windows, assemble with 7-Zip; see docs.')
    lib = C.CDLL(libpath)
    specs = [
        ('archive_read_new', C.c_void_p, []),
        ('archive_read_support_filter_all', C.c_int, [C.c_void_p]),
        ('archive_read_support_format_all', C.c_int, [C.c_void_p]),
        ('archive_read_open_filenames', C.c_int, [C.c_void_p, C.POINTER(C.c_char_p), C.c_size_t]),
        ('archive_read_next_header', C.c_int, [C.c_void_p, C.POINTER(C.c_void_p)]),
        ('archive_entry_pathname', C.c_char_p, [C.c_void_p]),
        ('archive_entry_filetype', C.c_uint, [C.c_void_p]),
        ('archive_entry_size', C.c_longlong, [C.c_void_p]),
        ('archive_read_data', C.c_ssize_t, [C.c_void_p, C.c_void_p, C.c_size_t]),
        ('archive_error_string', C.c_char_p, [C.c_void_p]),
        ('archive_read_free', C.c_int, [C.c_void_p])]
    for name, ret, args in specs:
        f = getattr(lib, name); f.restype = ret; f.argtypes = args
    archive = lib.archive_read_new()
    def check(code):
        if code < 0:
            error = lib.archive_error_string(archive)
            raise RuntimeError(error.decode('utf-8', 'replace') if error else 'libarchive failure')
    inventory = []
    output.mkdir(parents=True, exist_ok=True)
    try:
        check(lib.archive_read_support_filter_all(archive))
        check(lib.archive_read_support_format_all(archive))
        names = (C.c_char_p * (len(files) + 1))(*[str(p.resolve()).encode() for p in files], None)
        check(lib.archive_read_open_filenames(archive, names, 1024 * 1024))
        buf = C.create_string_buffer(1024 * 1024)
        seen = set()
        archive_root = None
        while True:
            entry = C.c_void_p()
            rc = lib.archive_read_next_header(archive, C.byref(entry))
            if rc == 1:
                break
            check(rc)
            name = lib.archive_entry_pathname(entry).decode('utf-8')
            rel = PurePosixPath(name.replace('\\', '/'))
            if rel.is_absolute() or '..' in rel.parts or not rel.parts or ':' in name:
                raise ValueError('Unsafe archive path: %r' % name)
            if archive_root is None:
                archive_root = rel.parts[0]
            if rel.parts[0] != archive_root:
                raise ValueError('Archive must have one root directory')
            target = output.joinpath(*rel.parts[1:])
            if not target.resolve().is_relative_to(output.resolve()):
                raise ValueError('Path leaves output directory')
            kind = lib.archive_entry_filetype(entry)
            if kind == 0o040000:
                target.mkdir(parents=True, exist_ok=True)
                continue
            if kind != 0o100000 or target in seen:
                raise ValueError('Unsupported or duplicate entry: %s' % name)
            seen.add(target)
            target.parent.mkdir(parents=True, exist_ok=True)
            size = lib.archive_entry_size(entry)
            count = 0; h = hashlib.sha256()
            with target.open('xb') as stream:
                while True:
                    n = lib.archive_read_data(archive, buf, len(buf))
                    check(n)
                    if n == 0:
                        break
                    data = buf.raw[:n]; stream.write(data); h.update(data); count += n
                stream.flush()
                os.fsync(stream.fileno())
            if count != size or target.stat().st_size != size or sha256(target) != h.hexdigest():
                raise ValueError('On-disk size/hash mismatch: %s' % name)
            inventory.append({'path': target.relative_to(output).as_posix(), 'bytes': count, 'sha256': h.hexdigest()})
    finally:
        lib.archive_read_free(archive)
    game_hash = sha256(output / 'game-lib.jar')
    if game_hash != EXPECTED_GAME_SHA256:
        raise ValueError('game-lib.jar fingerprint mismatch: ' + game_hash)
    report = {'status': 'PASS', 'volumeCount': len(files), 'fileCount': len(inventory),
              'uncompressedBytes': sum(x['bytes'] for x in inventory), 'gameJarSha256': game_hash,
              'volumes': [{'file': p.name, 'bytes': p.stat().st_size, 'sha256': sha256(p)} for p in files],
              'files': inventory}
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ('volumes', 'files')}, indent=2))

if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('source', type=Path); ap.add_argument('output', type=Path)
    ap.add_argument('--manifest', required=True, type=Path)
    ap.add_argument('--parts', type=int, default=18)
    args = ap.parse_args()
    assemble(args.source, args.output, args.manifest, args.parts)

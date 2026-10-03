#!/usr/bin/env python3
"""Run a fixed-condition A/B campaign as sequential, recoverable two-instance pairs.

One pair runs at a time. Each pair still uses run_headless.py's two isolated
engines. Original run directories are kept in place for aggregate_ab.py.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import aggregate_ab as ab
import run_headless as runner


MIB = 1024 * 1024
GIB = 1024 * MIB * 1024


class CampaignError(RuntimeError):
    pass


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def file_sha(path):
    return runner.sha(path)


def tree_size(path):
    """Count actual files without following junctions or symbolic links."""
    path = Path(path)
    if path.is_symlink() or (os.name == 'nt' and getattr(os.path, 'isjunction', lambda _: False)(path)):
        return 0
    if path.is_file():
        return path.stat().st_size
    total = 0
    for child in path.iterdir():
        total += tree_size(child)
    return total


def resource_fingerprint(game):
    """Detect changes to shared, read-only staged resources between pairs."""
    digest = hashlib.sha256()
    for name in ('libs', 'assets', 'res'):
        base = game / name
        for path in sorted(item for item in base.rglob('*') if item.is_file()):
            relative = path.relative_to(game).as_posix()
            digest.update(relative.encode('utf-8') + b'\0')
            digest.update(file_sha(path).encode('ascii') + b'\n')
    return digest.hexdigest()


@contextmanager
def campaign_lock(path):
    """An OS lock releases on a hard crash, so no stale lock needs deleting."""
    with path.open('a+b') as lock:
        if lock.tell() == 0:
            lock.write(b'0'); lock.flush()
        lock.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except (OSError, IOError) as error:
            raise CampaignError('Another process holds this campaign lock') from error
        try:
            yield
        finally:
            lock.seek(0)
            if os.name == 'nt':msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def expected_conditions(args, game, jar):
    return {'agentContentDigest': runner.jar_content_digest(jar),
            'agentJarSha256': file_sha(jar),
            'gameJarSha256': file_sha(game / 'game-lib.jar'),
            'map': args.map, 'aiDifficulty': args.difficulty,
            'requestedSpeed': args.speed, 'timeoutSeconds': args.timeout,
            'battleSeconds': args.battle_seconds,
            'fog': {'fogEnabled': True, 'lineOfSightFog': True}}


def make_plan(args):
    game = args.game_dir.resolve()
    jar = args.agent_jar.resolve()
    profiles_path = args.profiles.resolve()
    out = args.out.resolve()
    for path in (game / 'game-lib.jar', jar, profiles_path):
        if not path.is_file():raise CampaignError('Required input missing: ' + str(path))
    for name in ('libs', 'assets', 'res'):
        if not (game / name).is_dir():raise CampaignError('Required game directory missing: ' + str(game / name))
        if (game / name).is_symlink() or (os.name == 'nt' and getattr(os.path, 'isjunction', lambda _: False)(game / name)):
            raise CampaignError('Campaign requires ordinary source resource directories: ' + str(game / name))
    if file_sha(game / 'game-lib.jar') != runner.EXPECTED_GAME:
        raise CampaignError('Unsupported game-lib.jar fingerprint')
    for name in ('libs', 'assets', 'res'):
        if out == game / name or game / name in out.parents:
            raise CampaignError('Campaign output cannot be inside staged game resources')
    java = args.java or (str(game / 'jvm64/bin/java.exe') if os.name == 'nt' else shutil.which('java'))
    if not java:raise CampaignError('Java executable unavailable; specify --java')
    java = str(Path(java).resolve()) if Path(java).is_file() else java
    profiles = runner.load_ab_profiles(profiles_path)
    if not 2 <= args.pairs <= 1000:
        raise CampaignError('--pairs must be 2..1000 so both positions are crossed')
    if not (0 < args.speed <= 8 and 1 <= args.timeout <= 3600 and
            120 <= args.battle_seconds <= 1800 and -2 <= args.difficulty <= 3):
        raise CampaignError('Experiment settings exceed run_headless.py bounds')
    if args.max_campaign_gib <= 0 or args.min_free_gib < 0 or not 1 <= args.max_attempts <= 10:
        raise CampaignError('Invalid campaign disk or retry bounds')
    source_bytes = (game / 'game-lib.jar').stat().st_size + sum(
        tree_size(game / name) for name in ('libs', 'assets', 'res'))
    return {'schemaVersion': 1, 'campaignDirectory': str(out), 'pairs': args.pairs,
            'gameDirectory': str(game), 'agentJar': str(jar), 'java': java,
            'stagingGameDirectory': str(out / 'resource-cache'),
            'profilesFile': str(profiles_path), 'profileSourceSha256': profiles['sourceSha256'],
            'profileDigests': {slot: profiles['profiles'][slot]['digest'] for slot in ('A', 'B')},
            'campaignSha256': file_sha(Path(__file__)),
            'runnerSha256': file_sha(Path(runner.__file__)),
            'aggregatorSha256': file_sha(Path(ab.__file__)),
            'fixedConditions': expected_conditions(args, game, jar),
            'resourceFingerprint': resource_fingerprint(game),
            'sourceResourceBytes': source_bytes,
            'stageReserveBytes': 2 * source_bytes + 64 * MIB,
            'maxCampaignBytes': round(args.max_campaign_gib * GIB),
            'minFreeBytes': round(args.min_free_gib * GIB),
            'maxAttempts': args.max_attempts}


def initial_manifest(plan):
    return {'schemaVersion': 1, 'createdUtc': utc_now(), 'updatedUtc': utc_now(),
            'status': 'RUNNING', 'plan': plan,
            'slots': [{'index': index, 'order': 'AB' if index % 2 else 'BA',
                       'status': 'PENDING', 'selectedRunDirectory': None,
                       'launches': 0, 'attempts': []}
                      for index in range(1, plan['pairs'] + 1)],
            'aggregate': None, 'blockedReason': None}


def save(out, manifest):
    manifest['updatedUtc'] = utc_now()
    runner.write_json(out / 'campaign.json', manifest)


def load_or_create(out, plan):
    path = out / 'campaign.json'
    if path.exists():
        try:manifest = json.loads(path.read_text(encoding='utf-8'))
        except (OSError, ValueError) as error:raise CampaignError('Cannot read campaign.json: ' + str(error)) from error
        prior_plan = manifest.get('plan')
        operational = ('maxCampaignBytes', 'minFreeBytes', 'maxAttempts')
        fixed = lambda value: {key: item for key, item in value.items() if key not in operational}
        if manifest.get('schemaVersion') != 1 or not isinstance(prior_plan, dict) or fixed(prior_plan) != fixed(plan):
            raise CampaignError('Campaign plan or input identity changed; use the original arguments and bytes')
        slots = manifest.get('slots')
        if not isinstance(slots, list) or len(slots) != plan['pairs']:
            raise CampaignError('Campaign slot manifest is damaged')
        old_limits = {key: prior_plan[key] for key in operational}
        new_limits = {key: plan[key] for key in operational}
        if old_limits != new_limits:
            manifest.setdefault('operationalChanges', []).append({'changedUtc': utc_now(),
                                                                    'previous': old_limits,
                                                                    'current': new_limits})
            manifest['plan'] = plan
            save(out, manifest)
        return manifest
    existing = [item for item in out.iterdir() if item.name != 'campaign.lock']
    if existing:raise CampaignError('Output directory is nonempty without campaign.json')
    manifest = initial_manifest(plan)
    save(out, manifest)
    return manifest


def inspect_run(run, plan, order):
    """Return VALID, INVALID, or INCOMPLETE with evidence-backed detail."""
    run = Path(run)
    batch_path = run / 'batch.json'
    if not batch_path.is_file():return 'INCOMPLETE', 'batch.json missing', None
    try:
        batch = json.loads(batch_path.read_text(encoding='utf-8'))
    except (OSError, ValueError) as error:
        return 'INCOMPLETE', 'batch.json unreadable: ' + str(error), None
    if len(batch.get('episodes', [])) != 2:
        return 'INCOMPLETE', 'batch has fewer than two episodes', None
    try:
        result = ab.aggregate([run], require_crossover=False)
    except (ab.AggregationError, OSError, ValueError, TypeError, KeyError) as error:
        return 'INVALID', 'batch evidence rejected: ' + str(error), None
    if result['fixedConditions'] != plan['fixedConditions'] or result['profileSourceSha256'] != plan['profileSourceSha256']:
        return 'INVALID', 'fixed conditions or profile file identity differs from campaign', result
    if result['batches'][0]['profileOrder'] != order:
        return 'INVALID', 'profile order differs from campaign slot', result
    for slot in ('A', 'B'):
        if result['profiles'][slot]['profile']['digest'] != plan['profileDigests'][slot]:
            return 'INVALID', 'profile digest differs from campaign plan', result
        if result['profiles'][slot]['validBattleReports'] != 1:
            return 'INVALID', 'raw battle report is missing or invalid for ' + slot, result
    for number in (1, 2):
        issue = verify_staging(run / ('episode-%03d' % number), plan)
        if issue:return 'INVALID', issue, result
    for row in result['episodes']:
        episode = batch['episodes'][row['episode'] - 1]
        match_phases = [phase for phase in episode.get('phases', []) if phase.get('name') == 'match']
        if len(match_phases) != 1:
            return 'INVALID', 'match phase missing or duplicated in episode ' + str(row['episode']), result
        match_exit = match_phases[0].get('exitCode')
        if row['reportedOutcome'] == 'PARTIAL':
            expected = (row['matchOutcome'] == 'ONGOING' and episode.get('status') == 'FAIL' and
                        str(episode.get('error', '')).startswith('match PARTIAL:') and
                        match_exit == 2 and not episode.get('cleanupErrors'))
        else:
            expected = (row['reportedOutcome'] == 'PASS' and episode.get('status') == 'PASS' and
                        episode.get('matchCompleted') is True and match_exit == 0 and
                        not episode.get('cleanupErrors'))
        if not expected or episode.get('engineAliveAfterCleanup') is not False or episode.get('engineExitCode') != 0:
            return 'INVALID', 'runner failure exceeds normal partial result in episode ' + str(row['episode']), result
    detail = {'outcomes': {slot: {'reportedOutcome': next(row['reportedOutcome'] for row in result['episodes'] if row['slot'] == slot),
                                    'matchOutcome': next(row['matchOutcome'] for row in result['episodes'] if row['slot'] == slot)}
                           for slot in ('A', 'B')},
              'partial': sum(row['reportedOutcome'] == 'PARTIAL' for row in result['episodes']),
              'validBattleReports': 2}
    return 'VALID', detail, result


def verify_staging(work, plan):
    """Confirm every episode staged from the campaign-owned cache."""
    path = work / 'staging.json'
    try:
        payload = json.loads(path.read_text(encoding='utf-8'))
        items = payload['items']
        if payload.get('schemaVersion') != 1 or not isinstance(items, list):
            return 'staging.json schema invalid: ' + str(work)
        by_name = {item['name']: item for item in items}
    except (OSError, ValueError, TypeError, KeyError) as error:
        return 'staging.json unreadable: %s: %s' % (work, error)
    names = ('game-lib.jar', 'libs', 'assets', 'res', 'rw-agent-bootstrap.jar')
    if len(items) != len(names) or set(by_name) != set(names):
        return 'staging.json items differ from required resources: ' + str(work)
    cache = Path(plan['stagingGameDirectory'])
    for name in names:
        item = by_name[name]
        expected_source = Path(plan['agentJar']) if name == 'rw-agent-bootstrap.jar' else cache / name
        target = work / name
        if (not runner.same_path(item.get('source', ''), expected_source) or
                not runner.same_path(item.get('target', ''), target)):
            return 'staging source or target differs from campaign cache: ' + str(target)
        method = item.get('method')
        is_junction = os.name == 'nt' and getattr(os.path, 'isjunction', lambda _: False)(target)
        if (method not in ('copy', 'symlink', 'junction') or
                (method == 'junction' and not is_junction) or
                (method == 'symlink' and not target.is_symlink()) or
                (method == 'copy' and (target.is_symlink() or is_junction))):
            return 'staging method differs from filesystem: ' + str(target)
    return None


def runner_still_alive(run):
    claims = run / 'live-claims.json'
    if not claims.exists():return False
    try:
        payload = json.loads(claims.read_text(encoding='utf-8'))
        pid = payload.get('orchestratorPid')
        if type(pid) is not int or pid <= 0:raise ValueError('orchestratorPid missing')
        info = runner.process_info([pid])
    except (OSError, ValueError, TypeError) as error:
        raise CampaignError('Cannot verify prior runner process: ' + str(error)) from error
    if info is None:raise CampaignError('Prior runner process lookup unavailable')
    command = info.get(pid, '')
    # A reused PID may belong to an unrelated process. Never kill either one.
    return 'run_headless.py' in command


def safe_reap(run):
    """Do not trust a PASS when a claimed child was skipped but remains alive."""
    if runner.reap_run(run) != 0:
        raise CampaignError('Safe reap failed for incomplete run: ' + str(run))
    claims_path = run / 'live-claims.json'
    if not claims_path.exists():return
    try:
        claims = json.loads(claims_path.read_text(encoding='utf-8'))
        unresolved = [entry['pid'] for entry in claims.get('processes', [])
                      if entry.get('role') in ('engine', 'client')]
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise CampaignError('Cannot verify reap claims: ' + str(error)) from error
    info = runner.process_info(unresolved)
    if info is None:raise CampaignError('Post-reap child process lookup unavailable: ' + str(run))
    alive = sorted(pid for pid in unresolved if pid in info)
    if alive:raise CampaignError('Claimed child processes remain alive after reap: ' + repr(alive))


def recover_slot(out, manifest, slot):
    plan = manifest['plan']
    slot_dir = out / 'batches' / ('pair-%04d' % slot['index'])
    slot_dir.mkdir(parents=True, exist_ok=True)
    runs = sorted(path for path in slot_dir.glob('run-*') if path.is_dir())
    selected = slot.get('selectedRunDirectory')
    if selected:
        run = Path(selected)
        if run not in runs:raise CampaignError('Selected run directory moved or missing: ' + str(run))
        if runner_still_alive(run):
            raise CampaignError('Prior runner is still active; wait before resuming: ' + str(run))
        status, detail, _ = inspect_run(run, plan, slot['order'])
        if status != 'VALID':raise CampaignError('Previously selected evidence changed: ' + str(detail))
        slot['status'] = 'COMPLETE'
        return True
    valid = []
    for run in runs:
        status, detail, _ = inspect_run(run, plan, slot['order'])
        if status == 'VALID':
            valid.append((run, detail))
        elif status == 'INVALID':
            raise CampaignError('%s: %s' % (run, detail))
        else:
            if runner_still_alive(run):
                raise CampaignError('Prior runner is still active; wait before resuming: ' + str(run))
            safe_reap(run)
    if len(valid) > 1:raise CampaignError('More than one valid run in one slot; manual audit required')
    if valid:
        run, detail = valid[0]
        if runner_still_alive(run):
            raise CampaignError('Prior runner is still active; wait before resuming: ' + str(run))
        slot['selectedRunDirectory'] = str(run)
        slot['status'] = 'COMPLETE'
        slot['accepted'] = detail
        for attempt in reversed(slot['attempts']):
            if attempt.get('runDirectory') is None:
                attempt['runDirectory'] = str(run)
                attempt['inspectionStatus'] = 'VALID'
                attempt['inspectionDetail'] = detail
                attempt['recoveredUtc'] = utc_now()
                break
        save(out, manifest)
        return True
    slot['status'] = 'PENDING'
    return False


def check_budget(out, plan):
    used = tree_size(out)
    free = shutil.disk_usage(out).free
    reserve = plan['stageReserveBytes']
    if used + reserve > plan['maxCampaignBytes']:
        raise CampaignError('Campaign disk budget reached: used=%d reserve=%d max=%d bytes' %
                            (used, reserve, plan['maxCampaignBytes']))
    if free - reserve < plan['minFreeBytes']:
        raise CampaignError('Insufficient free disk for next pair: free=%d reserve=%d floor=%d bytes' %
                            (free, reserve, plan['minFreeBytes']))
    return {'usedBytes': used, 'freeBytes': free, 'reserveBytes': reserve}


def ensure_resource_cache(out, plan):
    """Copy source resources once; episode junctions only touch this owned cache."""
    source = Path(plan['gameDirectory'])
    cache = Path(plan['stagingGameDirectory'])
    ready_path = cache / '.cache-ready.json'
    if not ready_path.is_file():
        runs = list((out / 'batches').glob('pair-*/run-*')) if (out / 'batches').exists() else []
        if runs:raise CampaignError('Resource cache is incomplete or missing after runs started')
        need = plan['sourceResourceBytes'] + plan['stageReserveBytes']
        used = tree_size(out)
        free = shutil.disk_usage(out).free
        if used + need > plan['maxCampaignBytes'] or free - need < plan['minFreeBytes']:
            raise CampaignError('Insufficient disk budget to prepare resource cache and first pair')
        cache.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / 'game-lib.jar', cache / 'game-lib.jar')
        for name in ('libs', 'assets', 'res'):
            shutil.copytree(source / name, cache / name, dirs_exist_ok=True)
        if (file_sha(cache / 'game-lib.jar') != plan['fixedConditions']['gameJarSha256'] or
                resource_fingerprint(cache) != plan['resourceFingerprint']):
            raise CampaignError('Resource cache copy differs from original game resources')
        runner.write_json(ready_path, {'schemaVersion': 1, 'sourceDirectory': str(source),
                                       'resourceFingerprint': plan['resourceFingerprint'],
                                       'gameJarSha256': plan['fixedConditions']['gameJarSha256'],
                                       'createdUtc': utc_now()})
    try:ready = json.loads(ready_path.read_text(encoding='utf-8'))
    except (OSError, ValueError) as error:raise CampaignError('Resource cache marker unreadable: ' + str(error)) from error
    if (ready.get('sourceDirectory') != str(source) or
            ready.get('resourceFingerprint') != plan['resourceFingerprint'] or
            ready.get('gameJarSha256') != plan['fixedConditions']['gameJarSha256'] or
            file_sha(cache / 'game-lib.jar') != plan['fixedConditions']['gameJarSha256'] or
            resource_fingerprint(cache) != plan['resourceFingerprint']):
        raise CampaignError('Resource cache changed; original run evidence is preserved')
    return cache


def launch_pair(out, manifest, slot):
    plan = manifest['plan']
    if slot['launches'] >= plan['maxAttempts']:
        raise CampaignError('Retry limit reached for pair %d; original attempts retained' % slot['index'])
    game = Path(plan['gameDirectory'])
    if (resource_fingerprint(game) != plan['resourceFingerprint'] or
            file_sha(game / 'game-lib.jar') != plan['fixedConditions']['gameJarSha256'] or
            file_sha(Path(plan['agentJar'])) != plan['fixedConditions']['agentJarSha256'] or
            runner.jar_content_digest(Path(plan['agentJar'])) != plan['fixedConditions']['agentContentDigest']):
        raise CampaignError('Candidate JAR or original game inputs changed before pair %d' % slot['index'])
    cache = ensure_resource_cache(out, plan)
    if resource_fingerprint(cache) != plan['resourceFingerprint']:
        raise CampaignError('Shared game resources changed before pair %d' % slot['index'])
    budget = check_budget(out, plan)
    slot_dir = out / 'batches' / ('pair-%04d' % slot['index'])
    slot['launches'] += 1
    launch_number = slot['launches']
    log = slot_dir / ('launch-%03d.log' % launch_number)
    attempt = {'launch': launch_number, 'startedUtc': utc_now(), 'logPath': str(log),
               'preflightDisk': budget, 'runDirectory': None, 'runnerExitCode': None}
    slot['attempts'].append(attempt)
    slot['status'] = 'RUNNING'
    save(out, manifest)
    before = {path for path in slot_dir.glob('run-*') if path.is_dir()}
    fixed = plan['fixedConditions']
    command = [sys.executable, str(Path(runner.__file__).resolve()),
               '--game-dir', str(cache), '--agent-jar', plan['agentJar'], '--java', plan['java'],
               '--mode', 'match', '--episodes', '2', '--parallel-pair',
               '--junction-dirs',
               '--profiles', plan['profilesFile'], '--profile-order', slot['order'],
               '--map', fixed['map'], '--difficulty', str(fixed['aiDifficulty']),
               '--speed', str(fixed['requestedSpeed']), '--timeout', str(fixed['timeoutSeconds']),
               '--battle-seconds', str(fixed['battleSeconds']), '--out', str(slot_dir)]
    try:
        with log.open('wb') as output:
            completed = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT,
                                       timeout=fixed['timeoutSeconds'] + 180)
        attempt['runnerExitCode'] = completed.returncode
    except subprocess.TimeoutExpired:
        attempt['runnerExitCode'] = 'TIMEOUT'
    attempt['finishedUtc'] = utc_now()
    after = {path for path in slot_dir.glob('run-*') if path.is_dir()}
    created = sorted(after - before)
    if len(created) != 1:
        raise CampaignError('Runner created %d run directories for pair %d; see %s' %
                            (len(created), slot['index'], log))
    run = created[0]
    attempt['runDirectory'] = str(run)
    if (resource_fingerprint(game) != plan['resourceFingerprint'] or
            resource_fingerprint(cache) != plan['resourceFingerprint'] or
            file_sha(game / 'game-lib.jar') != plan['fixedConditions']['gameJarSha256'] or
            file_sha(cache / 'game-lib.jar') != plan['fixedConditions']['gameJarSha256']):
        raise CampaignError('Original or cached game resources changed during pair %d' % slot['index'])
    status, detail, _ = inspect_run(run, plan, slot['order'])
    attempt['inspectionStatus'] = status
    attempt['inspectionDetail'] = detail
    save(out, manifest)
    if status != 'VALID':
        if runner_still_alive(run):
            raise CampaignError('Runner remains active after failed pair: ' + str(run))
        safe_reap(run)
        raise CampaignError('Pair %d has %s evidence: %s' % (slot['index'], status, detail))
    if attempt['runnerExitCode'] not in (0, 1):
        raise CampaignError('Runner exit is not normal completion/partial: ' + str(attempt['runnerExitCode']))
    slot['selectedRunDirectory'] = str(run)
    slot['status'] = 'COMPLETE'
    slot['accepted'] = detail
    save(out, manifest)


def finish(out, manifest):
    plan = manifest['plan']
    runs = [Path(slot['selectedRunDirectory']) for slot in manifest['slots']]
    result = ab.aggregate(runs)
    if result['batchCount'] != plan['pairs'] or result['profileOrderCounts'].get('AB', 0) == 0 or result['profileOrderCounts'].get('BA', 0) == 0:
        raise CampaignError('Final aggregate lacks planned AB/BA pair coverage')
    for slot in ('A', 'B'):
        profile = result['profiles'][slot]
        if profile['attempted'] != plan['pairs'] or profile['validBattleReports'] != plan['pairs'] or profile['invalidOrMissingReports'] != 0:
            raise CampaignError('Final aggregate counts do not match planned valid pairs for ' + slot)
    output = out / 'aggregate.json'
    runner.write_json(output, result)
    manifest['aggregate'] = {'path': str(output), 'sha256': file_sha(output),
                             'batchCount': result['batchCount'],
                             'profileOrderCounts': result['profileOrderCounts'],
                             'profiles': {slot: {key: result['profiles'][slot][key] for key in
                                                  ('attempted', 'validBattleReports', 'partial',
                                                   'invalidOrMissingReports', 'nativeCompleted')}
                                          for slot in ('A', 'B')},
                             'metricN': {slot: result['profiles'][slot]['metrics']['battleGameSeconds']['n']
                                         for slot in ('A', 'B')}}
    manifest['status'] = 'COMPLETE'
    manifest['blockedReason'] = None
    save(out, manifest)
    print(json.dumps({'status': 'COMPLETE', 'campaign': str(out),
                      'aggregate': manifest['aggregate']}, ensure_ascii=False), flush=True)


def run(args):
    plan = make_plan(args)
    out = Path(plan['campaignDirectory'])
    out.mkdir(parents=True, exist_ok=True)
    with campaign_lock(out / 'campaign.lock'):
        manifest = load_or_create(out, plan)
        try:
            ensure_resource_cache(out, manifest['plan'])
            for slot in manifest['slots']:
                if recover_slot(out, manifest, slot):continue
                launch_pair(out, manifest, slot)
            finish(out, manifest)
        except (CampaignError, ab.AggregationError, OSError, ValueError) as error:
            manifest['status'] = 'BLOCKED'
            manifest['blockedReason'] = str(error)
            save(out, manifest)
            raise
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir', type=Path, required=True)
    parser.add_argument('--agent-jar', type=Path, required=True)
    parser.add_argument('--java')
    parser.add_argument('--profiles', type=Path, required=True)
    parser.add_argument('--pairs', type=int, required=True, help='Planned AB/BA pairs, 2..1000')
    parser.add_argument('--out', type=Path, required=True, help='Persistent campaign directory')
    parser.add_argument('--map', default=runner.DEFAULT_MAP)
    parser.add_argument('--difficulty', type=int, default=0)
    parser.add_argument('--speed', type=float, default=4)
    parser.add_argument('--timeout', type=int, default=1200)
    parser.add_argument('--battle-seconds', type=int, default=900)
    parser.add_argument('--max-campaign-gib', type=float, default=10.0)
    parser.add_argument('--min-free-gib', type=float, default=1.0)
    parser.add_argument('--max-attempts', type=int, default=3)
    args = parser.parse_args(argv)
    try:return run(args)
    except (CampaignError, ab.AggregationError, OSError, ValueError) as error:
        print(json.dumps({'status': 'BLOCKED', 'error': str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == '__main__':raise SystemExit(main())

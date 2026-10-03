#!/usr/bin/env python3
"""Read-only, evidence-checked aggregation of fixed-condition headless A/B pairs.

Only the battle report of a match episode supplies outcome and metrics. A missing
or damaged report remains in the attempted-episode denominator with null metrics.
There is deliberately no composite score or preferred-profile decision.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import statistics
from urllib.parse import parse_qs, urlsplit
import zipfile

from analyze_reports import analyze_file


class AggregationError(ValueError):
    """The batches cannot truthfully be combined as one fixed-condition A/B set."""


METRIC_FIELDS = {
    'battleGameSeconds': 'battleGameTimeMs',
    'newCombatUnits': 'newCombatUnits',
    'ownLosses': 'ownLosses',
    'spendTotal': 'spendTotal',
    'completedMines': 'completedMines',
    'completedFactories': 'completedFactories',
    'minesReadyAtEnd': 'minesReadyAtEnd',
    'upgradesCompleted': 'upgradesCompleted',
    'measuredIncomePerGameSecond': 'measuredIncomePerGameSecond',
    'factorySaturationPct': 'factorySaturationPct',
    'productionConsumptionPerGameSecond': 'productionConsumptionPerGameSecond',
    'sustainableSurplusPerGameSecond': 'sustainableSurplusPerGameSecond',
    'investmentIntentions': 'investmentIntentions',
    'investmentCompletions': 'investmentCompletions',
    'investmentCancellations': 'investmentCancellations',
    'investmentReserveAtEnd': 'investmentReserveAtEnd',
    'factoryTargetIncreases': 'factoryTargetIncreases',
    'factoryTargetIncreaseBlocks': 'factoryTargetIncreaseBlocks',
    'expansionRefusals': 'expansionRefusals',
    'expansionBlocked': 'expansionBlocked',
    'factoryBlocks': 'factoryBlocks',
    'builderRecoveries': 'builderRecoveries',
    'builderOrders': 'builderOrders',
}
SPEND_CATEGORIES = ('UNIT_PRODUCTION', 'NEW_MINE', 'NEW_FACTORY',
                    'FACTORY_UPGRADE', 'BUILDER_RECOVERY')
REASON_EVENTS = ('economy_expansion_blocked', 'investment_released',
                 'factory_target_increase_blocked', 'production_facility_blocked')
EVENT_COUNTERS = {
    'investmentIntentions': 'investment_intent',
    'factoryTargetIncreases': 'factory_target_increased',
    'factoryTargetIncreaseBlocks': 'factory_target_increase_blocked',
    'expansionBlocked': 'economy_expansion_blocked',
    'factoryBlocks': 'production_facility_blocked',
    'builderRecoveries': 'builder_recovery_started',
    'builderOrders': 'builder_recovery_ordered',
}


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest_bytes(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    with path.open('rb') as source:
        h = hashlib.sha256()
        for block in iter(lambda: source.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def jar_content_digest(path):
    """Exactly the contentDigest recipe in _analysis/sha_lineage.py."""
    with zipfile.ZipFile(path) as archive:
        entries = {name: digest_bytes(archive.read(name))
                   for name in archive.namelist() if name != 'META-INF/MANIFEST.MF'}
    return digest_bytes('\n'.join('%s %s' % (name, entries[name])
                                  for name in sorted(entries)).encode('utf-8'))


def load_json(path):
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, UnicodeError, ValueError) as error:
        raise AggregationError('%s: cannot read JSON: %s' % (path, error)) from error


def require(condition, message):
    if not condition:
        raise AggregationError(message)


def finite_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def sha_string(value):
    return isinstance(value, str) and len(value) == 64 and all(c in '0123456789abcdef' for c in value)


def validate_conditions(value, where):
    require(isinstance(value, dict), where + ': fixedConditions missing')
    for key in ('agentContentDigest', 'agentJarSha256', 'gameJarSha256'):
        require(sha_string(value.get(key)), where + ': invalid ' + key)
    require(isinstance(value.get('map'), str) and value['map'], where + ': map missing')
    require(isinstance(value.get('aiDifficulty'), int) and not isinstance(value['aiDifficulty'], bool),
            where + ': aiDifficulty missing')
    for key in ('requestedSpeed', 'timeoutSeconds', 'battleSeconds'):
        require(finite_number(value.get(key)) and value[key] > 0, where + ': invalid ' + key)
    fog = value.get('fog')
    require(isinstance(fog, dict) and set(fog) == {'fogEnabled', 'lineOfSightFog'}
            and all(isinstance(v, bool) for v in fog.values()), where + ': invalid fog')
    return value


def validate_profile(slot, value, where):
    require(isinstance(value, dict) and value.get('name') and isinstance(value['name'], str),
            where + ': missing profile name')
    props = value.get('jvmProperties')
    require(isinstance(props, dict) and set(props) == {'rwagent.mobileUnitHardCap'}
            and type(props['rwagent.mobileUnitHardCap']) is int
            and props['rwagent.mobileUnitHardCap'] > 0, where + ': invalid profile properties')
    expected = canonical({'name': value['name'], 'jvmProperties': props})
    require(value.get('canonicalJson') == expected, where + ': canonicalJson differs from profile')
    require(value.get('digest') == digest_bytes(expected.encode('utf-8')),
            where + ': profile digest mismatch')
    return {key: value[key] for key in ('name', 'jvmProperties', 'canonicalJson', 'digest')}


def validate_episode_profile(episode, definitions, slot, where):
    profile = episode.get('abProfile')
    require(isinstance(profile, dict) and profile.get('slot') == slot,
            where + ': episode profile slot mismatch')
    for key, expected in definitions[slot].items():
        require(profile.get(key) == expected, where + ': episode profile ' + key + ' mismatch')
    args = profile.get('actualJvmArgs')
    require(isinstance(args, list) and all(isinstance(arg, str) for arg in args),
            where + ': actualJvmArgs missing')
    prefix = '-Drwagent.mobileUnitHardCap='
    supplied = [arg for arg in args if arg.startswith(prefix)]
    expected = prefix + str(definitions[slot]['jvmProperties']['rwagent.mobileUnitHardCap'])
    require(supplied == [expected], where + ': actual JVM cap differs from profile')
    return profile


def command_arg(command, flag):
    try:
        index = command.index(flag)
        return command[index + 1]
    except (ValueError, IndexError):
        return None


def verify_native_conditions(work, episode, fixed, where):
    """Check what the engine actually used, independently of requested conditions."""
    runtime = episode.get('runtime')
    if not isinstance(runtime, dict):
        return 'runtime.json was not captured'
    runtime_path = work / 'runtime.json'
    if not runtime_path.is_file():
        return 'runtime.json missing'
    require(load_json(runtime_path) == runtime,
            where + ': runtime.json differs from episode.json')
    require(type(runtime.get('port')) is int and runtime['port'] == episode.get('port'),
            where + ': runtime port differs from episode/proof')
    values = {'map': 'map', 'aiDifficulty': 'aiDifficulty',
              'requestedSpeed': 'requestedSpeed', 'gameJarSha256': 'gameJarSha256'}
    for actual, expected in values.items():
        require(runtime.get(actual) == fixed[expected], where + ': runtime ' + actual + ' changed')
    for key, expected in fixed['fog'].items():
        require(runtime.get(key) is expected, where + ': runtime ' + key + ' changed')
    require(runtime.get('seedReproducibilityVerified') is False,
            where + ': seed reproducibility claim is unsupported')
    if type(runtime.get('seed')) is not int:
        return 'actual runtime seed missing or not an integer'
    process_path = work / 'engine-process.json'
    if not process_path.is_file():
        return 'engine-process.json missing'
    process = load_json(process_path)
    cmd = process.get('command')
    if not isinstance(cmd, list):
        return 'engine command missing'
    require(type(process.get('pid')) is int and process['pid'] == episode.get('enginePid'),
            where + ': engine-process PID differs from episode/proof')
    require(type(process.get('port')) is int and process['port'] == episode.get('port'),
            where + ': engine-process port differs from episode/proof')
    require(process.get('workDirectory') and
            Path(process['workDirectory']).resolve() == work.resolve(),
            where + ': engine-process work directory differs from episode')
    require(process.get('startedUtc') == episode.get('startedUtc'),
            where + ': engine-process start time differs from episode')
    require(command_arg(cmd, '--port') == str(episode.get('port')),
            where + ': engine command port differs from episode/proof')
    for flag, expected in (('--map', fixed['map']), ('--difficulty', str(fixed['aiDifficulty'])),
                           ('--speed', str(fixed['requestedSpeed'])),
                           ('--max-wall-seconds', str(fixed['timeoutSeconds']))):
        value = command_arg(cmd, flag)
        try:
            same = value is not None and (float(value) == float(expected) if flag in
                       ('--speed', '--max-wall-seconds') else value == expected)
        except ValueError:
            same = False
        require(same,
                where + ': engine command ' + flag + ' changed')
    agent = work / 'rw-agent-bootstrap.jar'
    game = work / 'game-lib.jar'
    if not agent.is_file() or not game.is_file():
        return 'staged Agent or game JAR missing'
    require(file_sha(agent) == fixed['agentJarSha256'] == episode.get('agentJarSha256'),
            where + ': staged Agent JAR SHA mismatch')
    require(jar_content_digest(agent) == fixed['agentContentDigest'],
            where + ': staged Agent contentDigest mismatch')
    require(file_sha(game) == fixed['gameJarSha256'] == episode.get('gameJarSha256'),
            where + ': staged game JAR SHA mismatch')
    return None


def raw_sessions(rows):
    sessions = set()
    for row in rows:
        data = row.get('data') or {}
        if isinstance(data.get('sessionId'), str):
            sessions.add(data['sessionId'])
        if row.get('event') == 'action' and isinstance(data.get('path'), str):
            sessions.update(parse_qs(urlsplit(data['path']).query).get('sessionId', []))
    return sessions


def verify_raw_identity(rows, work, episode, fixed):
    health = [item['data'] for item in rows if item.get('event') == 'health']
    provenance = [item['data'] for item in rows if item.get('event') == 'report_provenance']
    if len(health) != 1 or len(provenance) != 1:
        return 'health or report_provenance missing or duplicated'
    observed = health[0]
    if (observed.get('status') != 'ok' or observed.get('port') != episode.get('port')
            or observed.get('allowCommands') is not True):
        return 'raw health API identity mismatch'
    for source in (observed.get('provenance'), provenance[0]):
        if not isinstance(source, dict):
            return 'raw provenance invalid'
        if (source.get('agentJarSha256') != fixed['agentJarSha256']
                or source.get('gameLibJarSha256') != fixed['gameJarSha256']):
            return 'raw provenance JAR SHA mismatch'
        for key, expected in (('workingDirectory', work),
                              ('agentJar', work / 'rw-agent-bootstrap.jar')):
            if not source.get(key) or Path(source[key]).resolve() != expected.resolve():
                return 'raw provenance ' + key + ' mismatch'
    health_paths = observed['provenance']
    for key, expected in (('reportDirectory', work / 'rw-agent-reports'),
                          ('gameLibJar', work / 'game-lib.jar')):
        if not health_paths.get(key) or Path(health_paths[key]).resolve() != expected.resolve():
            return 'raw health ' + key + ' mismatch'
    return None


def verify_pair_proof(run, batch, episodes):
    where = str(run)
    proof = batch.get('parallelProof')
    require(isinstance(proof, dict) and proof.get('schemaVersion') == 1
            and proof.get('status') == 'PASS', where + ': parallel proof is not PASS')
    disk = load_json(run / 'parallel-proof.json')
    require(disk == proof, where + ': parallel-proof.json differs from batch.json')
    instances = proof.get('instances')
    require(isinstance(instances, list) and len(instances) == 2
            and {item.get('episode') for item in instances} == {1, 2},
            where + ': parallel proof must contain two numbered instances')
    for instance in instances:
        number = instance['episode']
        episode = next(item for item in episodes if item['episode'] == number)
        work = run / ('episode-%03d' % number)
        for key in ('enginePid', 'port', 'sessionId'):
            require(instance.get(key) == episode.get(key),
                    where + ': parallel proof ' + key + ' differs for episode ' + str(number))
        for key, expected in (('workDirectory', work),
                              ('reportDirectory', work / 'rw-agent-reports'),
                              ('lockPath', work / 'rw-agent-reports' / 'economy.lock')):
            require(instance.get(key) and Path(instance[key]).resolve() == expected.resolve(),
                    where + ': parallel proof ' + key + ' differs for episode ' + str(number))
        require(finite_number(instance.get('frameBefore'))
                and finite_number(instance.get('frameAfter'))
                and instance['frameAfter'] > instance['frameBefore'],
                where + ': parallel proof lacks advancing frames')
    for key in ('enginePid', 'port', 'sessionId', 'workDirectory', 'reportDirectory', 'lockPath'):
        require(len({item.get(key) for item in instances}) == 2,
                where + ': parallel proof ' + key + ' is not isolated')


def verify_economy(summary, rows):
    """Economy summary has no independent checks in analyze_reports yet."""
    counts = Counter(row['event'] for row in rows)
    for key, event in EVENT_COUNTERS.items():
        if key in summary and summary[key] != counts[event]:
            return '%s differs from raw %s events' % (key, event)
    releases = [row['data'].get('reason') for row in rows if row['event'] == 'investment_released']
    for key, actual in (('investmentCompletions', releases.count('COMPLETED')),
                        ('investmentCancellations', sum(reason != 'COMPLETED' for reason in releases)),
                        ('expansionRefusals', sum(row['event'] == 'economy_expansion_blocked'
                                                 and row['data'].get('beyondFloor') is True for row in rows))):
        if key in summary and summary[key] != actual:
            return key + ' differs from raw events'
    spent = defaultdict(lambda: [0, 0])
    for row in rows:
        if row['event'] != 'spend':
            continue
        data = row['data']; category = data.get('category'); cost = data.get('cost')
        if not isinstance(category, str) or not finite_number(cost) or cost < 0:
            return 'invalid raw spend event'
        spent[category][0] += 1; spent[category][1] += cost
    expected = {category: {'orders': count, 'cost': cost}
                for category, (count, cost) in spent.items()}
    if summary.get('spendByCategory') != expected:
        return 'spendByCategory differs from raw spend events'
    if summary.get('spendTotal') != sum(cost for _, cost in spent.values()):
        return 'spendTotal differs from raw spend events'
    return None


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values); position = (len(ordered) - 1) * fraction
    low = math.floor(position); high = math.ceil(position)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def stats(values):
    if not values:
        return {'n': 0, 'mean': None, 'median': None, 'stddev': None,
                'p25': None, 'p75': None, 'min': None, 'max': None}
    return {'n': len(values), 'mean': statistics.mean(values),
            'median': statistics.median(values), 'stddev': statistics.pstdev(values),
            'p25': percentile(values, .25), 'p75': percentile(values, .75),
            'min': min(values), 'max': max(values)}


def blank_metrics(reason):
    return ({key: None for key in (*METRIC_FIELDS, *('unitProductionOrders', 'unitProductionSpend'))},
            {key: reason for key in (*METRIC_FIELDS, *('unitProductionOrders', 'unitProductionSpend'))})


def report_metrics(summary):
    metrics = {}; missing = {}
    for output, field in METRIC_FIELDS.items():
        value = summary.get(field)
        if finite_number(value):
            metrics[output] = value / 1000 if output == 'battleGameSeconds' else value
        else:
            metrics[output] = None; missing[output] = 'summary field missing or not finite: ' + field
    production = summary.get('spendByCategory', {}).get('UNIT_PRODUCTION', {})
    for output, field in (('unitProductionOrders', 'orders'), ('unitProductionSpend', 'cost')):
        value = production.get(field, 0)
        metrics[output] = value if finite_number(value) else None
        if metrics[output] is None:
            missing[output] = 'invalid UNIT_PRODUCTION ' + field
    return metrics, missing


def reasons_from_rows(rows):
    result = {}
    for event in REASON_EVENTS:
        result[event] = dict(sorted(Counter(str(row['data'].get('reason', 'UNKNOWN'))
                                            for row in rows if row['event'] == event).items()))
    return result


def read_battle(work, episode, fixed, profile, where):
    row = {'reportStatus': 'MISSING', 'reportIssue': None, 'reportPath': None,
           'reportSha256': None, 'reportedOutcome': None, 'matchOutcome': None,
           'economicReasons': {}, 'seed': None, 'seedReproducibilityVerified': False}
    metrics, missing = blank_metrics('battle report missing')
    row['metrics'] = metrics; row['metricMissingReasons'] = missing
    runtime = episode.get('runtime')
    if isinstance(runtime, dict):
        row['seed'] = runtime.get('seed')
        row['seedReproducibilityVerified'] = runtime.get('seedReproducibilityVerified')
    condition_issue = verify_native_conditions(work, episode, fixed, where)
    if condition_issue:
        row['reportStatus'] = 'INVALID'; row['reportIssue'] = condition_issue
        row['metrics'], row['metricMissingReasons'] = blank_metrics(condition_issue)
        return row
    phases = [phase for phase in episode.get('phases', []) if phase.get('name') == 'match']
    if len(phases) != 1:
        row['reportIssue'] = 'match phase missing or duplicated'; return row
    phase = phases[0]
    if phase.get('clientJvmArgs') != profile['actualJvmArgs']:
        row['reportStatus'] = 'INVALID'; row['reportIssue'] = 'match client JVM args differ from profile'; return row
    embedded = phase.get('verifiedReports', {}).get('reports', [])
    battles = [report for report in embedded if report.get('task') == 'battle']
    if len(battles) != 1:
        row['reportIssue'] = 'battle report missing or duplicated in verification'; return row
    battle = battles[0]
    evidence = [entry for entry in phase.get('reportEvidence', [])
                if Path(entry.get('path', '')).name == battle.get('file')]
    if len(evidence) != 1:
        row['reportStatus'] = 'INVALID'; row['reportIssue'] = 'battle reportEvidence missing or duplicated'; return row
    evidence = evidence[0]
    raw_path = work / 'rw-agent-reports' / battle['file']
    row['reportPath'] = str(raw_path); row['reportSha256'] = evidence.get('sha256')
    if Path(evidence['path']).resolve() != raw_path.resolve():
        row['reportStatus'] = 'INVALID'; row['reportIssue'] = 'battle report path belongs to another episode'; return row
    if not raw_path.is_file():
        row['reportIssue'] = 'battle raw JSONL missing'; return row
    if file_sha(raw_path) != evidence.get('sha256') or battle.get('sha256') != evidence.get('sha256'):
        row['reportStatus'] = 'INVALID'; row['reportIssue'] = 'battle raw SHA mismatch'; return row
    try:
        rows = [json.loads(line) for line in raw_path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
        actual = analyze_file(raw_path)
    except (OSError, UnicodeError, ValueError, TypeError, KeyError) as error:
        row['reportStatus'] = 'INVALID'; row['reportIssue'] = 'battle JSONL invalid: ' + str(error); return row
    if raw_sessions(rows) != {episode.get('sessionId')} or evidence.get('sessionId') != episode.get('sessionId'):
        row['reportStatus'] = 'INVALID'; row['reportIssue'] = 'battle session mismatch'; return row
    identity_issue = verify_raw_identity(rows, work, episode, fixed)
    if identity_issue:
        row['reportStatus'] = 'INVALID'; row['reportIssue'] = identity_issue; return row
    if actual['task'] != 'battle' or actual['issues'] or actual['result'] not in ('PASS', 'PARTIAL'):
        row['reportStatus'] = 'INVALID'; row['reportIssue'] = 'battle integrity: ' + str(actual['issues'] or actual['result']); return row
    config = [item['data'] for item in rows if item.get('event') == 'battle_config']
    if len(config) != 1:
        row['reportStatus'] = 'INVALID'; row['reportIssue'] = 'battle_config missing or duplicated'; return row
    cap = profile['jvmProperties']['rwagent.mobileUnitHardCap']
    require(config[0].get('mobileUnitHardCap') == cap,
            where + ': battle_config mobileUnitHardCap does not match actual JVM profile')
    require(config[0].get('gameSeconds') == fixed['battleSeconds'],
            where + ': battle_config gameSeconds differs from fixed time limit')
    require(phase.get('battleConfig') == config[0],
            where + ': recorded battle_config differs from raw report')
    issue = verify_economy(actual['summary'], rows)
    if issue:
        row['reportStatus'] = 'INVALID'; row['reportIssue'] = issue; return row
    row['battleConfigWithoutProfile'] = {key: value for key, value in config[0].items()
                                         if key != 'mobileUnitHardCap'}
    row['reportedOutcome'] = actual['result']
    row['matchOutcome'] = actual['summary'].get('matchOutcome')
    if actual['result'] == 'PASS':
        require(row['matchOutcome'] in ('VICTORY', 'DEFEAT'),
                where + ': native completion identity differs from battle report')
        require(episode.get('status') != 'PASS' or episode.get('matchCompleted') is True,
                where + ': passing runner failed to record native completion')
    else:
        require(row['matchOutcome'] == 'ONGOING' and episode.get('matchCompleted') is False,
                where + ': partial match identity differs from episode')
    require(episode.get('matchOutcome') == row['matchOutcome'],
            where + ': episode and raw battle outcome differ')
    row['reportStatus'] = 'VALID'; row['reportIssue'] = None
    row['metrics'], row['metricMissingReasons'] = report_metrics(actual['summary'])
    row['economicReasons'] = reasons_from_rows(rows)
    return row


def aggregate(batch_dirs, *, require_crossover=True):
    runs = [Path(path).resolve() for path in batch_dirs]
    minimum = 2 if require_crossover else 1
    require(len(runs) >= minimum and len(set(runs)) == len(runs),
            'Provide at least %d distinct batch director%s' %
            (minimum, 'ies' if minimum != 1 else 'y'))
    all_rows = []; batches = []; common_conditions = None; common_profiles = None
    common_source_sha = None; orders = Counter(); common_battle_config = None
    whole_jar_shas = set()
    for run in runs:
        batch = load_json(run / 'batch.json')
        where = str(run)
        require(batch.get('mode') == 'match' and batch.get('parallelPair') is True,
                where + ': only parallel match pairs can be aggregated')
        fixed = validate_conditions(batch.get('fixedConditions'), where)
        metadata = batch.get('abProfiles')
        require(isinstance(metadata, dict) and metadata.get('schemaVersion') == 1,
                where + ': abProfiles missing')
        definitions = metadata.get('profiles')
        require(isinstance(definitions, dict) and set(definitions) == {'A', 'B'},
                where + ': exactly A and B profiles are required')
        profiles = {slot: validate_profile(slot, definitions[slot], where)
                    for slot in ('A', 'B')}
        order = metadata.get('profileOrder')
        require(order in ('AB', 'BA'), where + ': invalid A/B order')
        require(sha_string(metadata.get('sourceSha256')), where + ': profile source SHA missing')
        if common_conditions is None:
            common_conditions = fixed; common_profiles = profiles
            common_source_sha = metadata['sourceSha256']
        else:
            comparable = lambda conditions: {k: v for k, v in conditions.items() if k != 'agentJarSha256'}
            require(comparable(fixed) == comparable(common_conditions),
                    where + ': fixed conditions differ across batches')
            require(profiles == common_profiles and metadata['sourceSha256'] == common_source_sha,
                    where + ': profile definitions differ across batches')
        whole_jar_shas.add(fixed['agentJarSha256'])
        embedded = batch.get('episodes')
        require(isinstance(embedded, list) and len(embedded) == 2
                and {item.get('episode') for item in embedded} == {1, 2},
                where + ': exactly two numbered episodes are required')
        verify_pair_proof(run, batch, embedded)
        pair_rows = []
        for number in (1, 2):
            slot = order[number - 1]; work = run / ('episode-%03d' % number)
            episode = load_json(work / 'episode.json')
            in_batch = next(item for item in embedded if item['episode'] == number)
            require(episode == in_batch, str(work) + ': episode.json and batch.json differ')
            require(episode.get('mode') == 'match' and episode.get('episode') == number,
                    str(work) + ': mode or episode number differs')
            require(episode.get('map') == fixed['map'],
                    str(work) + ': episode map differs from fixed conditions')
            require(episode.get('fixedConditions') == fixed,
                    str(work) + ': episode fixed conditions differ from batch')
            require(Path(episode.get('workDirectoryAbsolute', '')).resolve() == work.resolve(),
                    str(work) + ': work directory identity differs')
            profile = validate_episode_profile(episode, profiles, slot, str(work))
            report = read_battle(work, episode, fixed, profile, str(work))
            if report.get('battleConfigWithoutProfile') is not None:
                config = report.pop('battleConfigWithoutProfile')
                if common_battle_config is None:
                    common_battle_config = config
                else:
                    require(config == common_battle_config,
                            str(work) + ': non-profile battle_config differs')
            row = {'runDirectory': str(run), 'episodeDirectory': str(work),
                   'episode': number, 'slot': slot, 'profileName': profile['name'],
                   'profileDigest': profile['digest'], 'profileOrder': order,
                   'runnerStatus': episode.get('status'), 'sessionId': episode.get('sessionId'),
                   **report}
            pair_rows.append(row); all_rows.append(row)
        orders[order] += 1
        batches.append({'runDirectory': str(run), 'profileOrder': order,
                        'parallelProofStatus': batch.get('parallelProof', {}).get('status'),
                        'episodes': [{'episode': row['episode'], 'slot': row['slot'],
                                      'reportStatus': row['reportStatus']} for row in pair_rows]})
    if require_crossover:
        require(orders['AB'] > 0 and orders['BA'] > 0,
                'At least one AB pair and one BA pair are required to cross episode positions')
    groups = {}
    for slot in ('A', 'B'):
        rows = [row for row in all_rows if row['slot'] == slot]
        valid = [row for row in rows if row['reportStatus'] == 'VALID']
        native = [row for row in valid if row['reportedOutcome'] == 'PASS'
                  and row['matchOutcome'] in ('VICTORY', 'DEFEAT')]
        wins = sum(row['matchOutcome'] == 'VICTORY' for row in native)
        losses = sum(row['matchOutcome'] == 'DEFEAT' for row in native)
        partial = [row for row in valid if row['reportedOutcome'] == 'PARTIAL']
        metric_stats = {key: stats([row['metrics'][key] for row in native
                                    if row['metrics'][key] is not None])
                        for key in (*METRIC_FIELDS, 'unitProductionOrders', 'unitProductionSpend')}
        partial_stats = {key: stats([row['metrics'][key] for row in partial
                                    if row['metrics'][key] is not None])
                         for key in (*METRIC_FIELDS, 'unitProductionOrders', 'unitProductionSpend')}
        reason_totals = {event: dict(sorted(sum((Counter(row['economicReasons'].get(event, {}))
                                                 for row in valid), Counter()).items()))
                         for event in REASON_EVENTS}
        groups[slot] = {'profile': common_profiles[slot], 'attempted': len(rows),
                        'runnerPassed': sum(row['runnerStatus'] == 'PASS' for row in rows),
                        'validBattleReports': len(valid), 'nativeCompleted': len(native),
                        'victories': wins, 'defeats': losses,
                        'partial': len(partial),
                        'invalidOrMissingReports': len(rows) - len(valid),
                        'nativeCompletionRate': len(native) / len(rows) if rows else None,
                        'winRateAmongNativeCompletions': wins / len(native) if native else None,
                        'metrics': metric_stats, 'partialMetrics': partial_stats,
                        'economicReasonCounts': reason_totals}
    return {'schemaVersion': 1, 'scope': 'headless match battle reports only',
            'fixedConditions': common_conditions, 'profileSourceSha256': common_source_sha,
            'agentJarSha256ByBatch': sorted(whole_jar_shas),
            'batchCount': len(batches), 'profileOrderCounts': dict(orders),
            'batches': batches, 'profiles': groups, 'episodes': all_rows,
            'definitions': {'nativeCompletionRate': 'native VICTORY or DEFEAT / attempted episodes',
                            'winRateAmongNativeCompletions': 'VICTORY / (VICTORY + DEFEAT)',
                            'battleGameSeconds': 'BattleClient game time, not wall/runtime duration',
                            'newCombatUnits': 'newly observed armed mobile units, not all production',
                            'spendTotal': 'accepted-order cost ledger, not net resource consumption',
                            'metrics': 'native-completed episodes only; partialMetrics are separate censored samples',
                            'economicReasonCounts': 'all integrity-valid battle reports, including partial samples',
                            'stddev': 'population standard deviation; p25/p75 use linear interpolation',
                            'seed': 'observed seed only; reproducibility is unverified'}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('batch_dirs', nargs='+', type=Path,
                        help='At least two run-* directories containing batch.json')
    parser.add_argument('--out', required=True, type=Path, help='Output JSON file outside input runs')
    args = parser.parse_args(argv)
    output = args.out.resolve()
    for run in args.batch_dirs:
        resolved = run.resolve()
        if output == resolved or resolved in output.parents:
            parser.error('--out must be outside every input run directory')
    try:
        result = aggregate(args.batch_dirs)
    except AggregationError as error:
        parser.error(str(error))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'batches': result['batchCount'], 'episodes': len(result['episodes']),
                      'output': str(output)}, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

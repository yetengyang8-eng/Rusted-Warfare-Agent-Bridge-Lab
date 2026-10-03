"""Recovery and classification checks for the sequential A/B campaign."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import run_ab_campaign as campaign


def fake_plan(out):
    return {'schemaVersion': 1, 'campaignDirectory': str(out), 'pairs': 2,
            'fixedConditions': {'agentContentDigest': 'a' * 64},
            'profileSourceSha256': 'b' * 64,
            'profileDigests': {'A': 'c' * 64, 'B': 'd' * 64},
            'stageReserveBytes': 100, 'maxCampaignBytes': 1000,
            'minFreeBytes': 100, 'maxAttempts': 3}


def fake_aggregate(plan, *, partial=True):
    episodes = []
    for number, slot in ((1, 'A'), (2, 'B')):
        episodes.append({'episode': number, 'slot': slot, 'reportedOutcome': 'PARTIAL' if partial else 'PASS',
                         'matchOutcome': 'ONGOING' if partial else 'VICTORY', 'reportStatus': 'VALID'})
    return {'fixedConditions': plan['fixedConditions'],
            'profileSourceSha256': plan['profileSourceSha256'],
            'batches': [{'profileOrder': 'AB'}], 'episodes': episodes,
            'profiles': {slot: {'profile': {'digest': plan['profileDigests'][slot]},
                                'validBattleReports': 1} for slot in ('A', 'B')}}


class CampaignTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.out = Path(self.temp.name)
        self.plan = fake_plan(self.out)

    def test_manifest_alternates_and_same_plan_resumes(self):
        first = campaign.load_or_create(self.out, self.plan)
        self.assertEqual([slot['order'] for slot in first['slots']], ['AB', 'BA'])
        first['slots'][0]['launches'] = 1
        campaign.save(self.out, first)
        resumed = campaign.load_or_create(self.out, self.plan)
        self.assertEqual(resumed['slots'][0]['launches'], 1)
        changed = {**self.plan, 'pairs': 3}
        with self.assertRaisesRegex(campaign.CampaignError, 'plan or input identity changed'):
            campaign.load_or_create(self.out, changed)

    def test_disk_limit_can_change_on_resume_with_audit_record(self):
        campaign.load_or_create(self.out, self.plan)
        changed = {**self.plan, 'maxCampaignBytes': 2000}
        resumed = campaign.load_or_create(self.out, changed)
        self.assertEqual(resumed['plan']['maxCampaignBytes'], 2000)
        self.assertEqual(resumed['operationalChanges'][0]['previous']['maxCampaignBytes'], 1000)
        self.assertEqual(resumed['operationalChanges'][0]['current']['maxCampaignBytes'], 2000)

    def test_lock_rejects_concurrent_campaign_and_releases(self):
        lock_path = self.out / 'campaign.lock'
        with campaign.campaign_lock(lock_path):
            with self.assertRaisesRegex(campaign.CampaignError, 'Another process holds'):
                with campaign.campaign_lock(lock_path):pass
        with campaign.campaign_lock(lock_path):pass

    def make_inspection_run(self, *, partial=True):
        run = self.out / 'run-test'
        run.mkdir()
        episodes = []
        for number in (1, 2):
            episodes.append({'episode': number, 'status': 'FAIL' if partial else 'PASS',
                             'error': 'match PARTIAL: Game-time budget reached without native result' if partial else None,
                             'phases': [{'name': 'match', 'exitCode': 2 if partial else 0}],
                             'matchCompleted': not partial, 'engineAliveAfterCleanup': False,
                             'engineExitCode': 0})
        (run / 'batch.json').write_text(json.dumps({'episodes': episodes}), encoding='utf-8')
        return run

    def test_valid_partial_counts_as_completed_pair(self):
        run = self.make_inspection_run()
        with mock.patch.object(campaign.ab, 'aggregate', return_value=fake_aggregate(self.plan)), \
             mock.patch.object(campaign, 'verify_staging', return_value=None):
            status, detail, _ = campaign.inspect_run(run, self.plan, 'AB')
        self.assertEqual(status, 'VALID')
        self.assertEqual(detail['partial'], 2)

    def test_unexpected_runner_failure_is_not_normal_partial(self):
        run = self.make_inspection_run()
        batch = json.loads((run / 'batch.json').read_text(encoding='utf-8'))
        batch['episodes'][0]['cleanupErrors'] = ['engine stop failed']
        (run / 'batch.json').write_text(json.dumps(batch), encoding='utf-8')
        with mock.patch.object(campaign.ab, 'aggregate', return_value=fake_aggregate(self.plan)), \
             mock.patch.object(campaign, 'verify_staging', return_value=None):
            status, detail, _ = campaign.inspect_run(run, self.plan, 'AB')
        self.assertEqual(status, 'INVALID')
        self.assertIn('runner failure', detail)

    def test_missing_report_is_never_adopted(self):
        run = self.make_inspection_run()
        summary = fake_aggregate(self.plan)
        summary['profiles']['A']['validBattleReports'] = 0
        with mock.patch.object(campaign.ab, 'aggregate', return_value=summary):
            status, detail, _ = campaign.inspect_run(run, self.plan, 'AB')
        self.assertEqual(status, 'INVALID')
        self.assertIn('missing or invalid', detail)

    def test_passing_report_with_nonzero_client_exit_is_not_accepted(self):
        run = self.make_inspection_run(partial=False)
        batch = json.loads((run / 'batch.json').read_text(encoding='utf-8'))
        batch['episodes'][0]['phases'][0]['exitCode'] = 1
        (run / 'batch.json').write_text(json.dumps(batch), encoding='utf-8')
        with mock.patch.object(campaign.ab, 'aggregate', return_value=fake_aggregate(self.plan, partial=False)), \
             mock.patch.object(campaign, 'verify_staging', return_value=None):
            status, detail, _ = campaign.inspect_run(run, self.plan, 'AB')
        self.assertEqual(status, 'INVALID')
        self.assertIn('runner failure', detail)

    def test_recover_incomplete_run_reaps_without_deleting_evidence(self):
        manifest = campaign.initial_manifest(self.plan)
        slot = manifest['slots'][0]
        run = self.out / 'batches' / 'pair-0001' / 'run-old'
        run.mkdir(parents=True)
        with mock.patch.object(campaign, 'runner_still_alive', return_value=False), \
             mock.patch.object(campaign, 'safe_reap') as reap:
            self.assertFalse(campaign.recover_slot(self.out, manifest, slot))
        reap.assert_called_once_with(run)
        self.assertTrue(run.is_dir())
        self.assertEqual(slot['status'], 'PENDING')

    def test_safe_reap_refuses_a_skipped_live_claimed_child(self):
        run = self.out / 'run-old'
        run.mkdir()
        (run / 'live-claims.json').write_text(json.dumps({'processes': [
            {'role': 'engine', 'pid': 4242, 'state': 'EXITED'}]}), encoding='utf-8')
        with mock.patch.object(campaign.runner, 'reap_run', return_value=0), \
             mock.patch.object(campaign.runner, 'process_info', return_value={4242: 'java headless'}):
            with self.assertRaisesRegex(campaign.CampaignError, 'remain alive'):
                campaign.safe_reap(run)

    def test_staging_must_point_to_campaign_owned_cache(self):
        cache = self.out / 'resource-cache'
        work = self.out / 'episode-001'
        cache.mkdir(); work.mkdir()
        agent = self.out / 'agent.jar'
        agent.write_bytes(b'agent')
        items = []
        for name in ('game-lib.jar', 'libs', 'assets', 'res', 'rw-agent-bootstrap.jar'):
            source = agent if name == 'rw-agent-bootstrap.jar' else cache / name
            target = work / name
            if name in ('libs', 'assets', 'res'):
                source.mkdir(); target.mkdir()
            else:
                if name == 'game-lib.jar':source.write_bytes(b'game')
                target.write_bytes(source.read_bytes())
            items.append({'name': name, 'source': str(source), 'target': str(target), 'method': 'copy'})
        (work / 'staging.json').write_text(json.dumps({'schemaVersion': 1, 'items': items}), encoding='utf-8')
        plan = {**self.plan, 'stagingGameDirectory': str(cache), 'agentJar': str(agent)}
        self.assertIsNone(campaign.verify_staging(work, plan))
        items[2]['source'] = str(self.out / 'original-assets')
        (work / 'staging.json').write_text(json.dumps({'schemaVersion': 1, 'items': items}), encoding='utf-8')
        self.assertIn('campaign cache', campaign.verify_staging(work, plan))

    def test_recover_complete_run_does_not_launch_or_reap(self):
        manifest = campaign.initial_manifest(self.plan)
        slot = manifest['slots'][0]
        run = self.out / 'batches' / 'pair-0001' / 'run-old'
        run.mkdir(parents=True)
        with mock.patch.object(campaign, 'inspect_run', return_value=('VALID', {'partial': 2}, {})), \
             mock.patch.object(campaign, 'runner_still_alive', return_value=False), \
             mock.patch.object(campaign.runner, 'reap_run') as reap:
            self.assertTrue(campaign.recover_slot(self.out, manifest, slot))
        reap.assert_not_called()
        self.assertEqual(slot['selectedRunDirectory'], str(run))

    def test_budget_blocks_before_next_pair(self):
        with mock.patch.object(campaign, 'tree_size', return_value=950), \
             mock.patch.object(campaign.shutil, 'disk_usage', return_value=mock.Mock(free=10000)):
            with self.assertRaisesRegex(campaign.CampaignError, 'disk budget reached'):
                campaign.check_budget(self.out, self.plan)

    def test_resource_cache_is_owned_copy_and_mutation_is_detected(self):
        source = self.out / 'source'
        output = self.out / 'campaign'
        output.mkdir()
        source.mkdir()
        (source / 'game-lib.jar').write_bytes(b'game')
        for name in ('libs', 'assets', 'res'):
            (source / name).mkdir()
            (source / name / 'marker.txt').write_text(name, encoding='utf-8')
        plan = {**self.plan, 'gameDirectory': str(source),
                'stagingGameDirectory': str(output / 'resource-cache'),
                'resourceFingerprint': campaign.resource_fingerprint(source),
                'sourceResourceBytes': campaign.tree_size(source),
                'stageReserveBytes': 100, 'maxCampaignBytes': 1000000,
                'fixedConditions': {'gameJarSha256': campaign.file_sha(source / 'game-lib.jar')}}
        cache = campaign.ensure_resource_cache(output, plan)
        self.assertEqual(campaign.resource_fingerprint(cache), plan['resourceFingerprint'])
        (cache / 'assets' / 'marker.txt').write_text('changed', encoding='utf-8')
        self.assertEqual((source / 'assets' / 'marker.txt').read_text(encoding='utf-8'), 'assets')
        with self.assertRaisesRegex(campaign.CampaignError, 'Resource cache changed'):
            campaign.ensure_resource_cache(output, plan)

    def test_final_aggregate_retains_zero_native_n_without_winner(self):
        manifest = campaign.initial_manifest(self.plan)
        for index, slot in enumerate(manifest['slots']):
            slot['selectedRunDirectory'] = str(self.out / ('run-%d' % index))
        profile = {'attempted': 2, 'validBattleReports': 2, 'partial': 2,
                   'invalidOrMissingReports': 0, 'nativeCompleted': 0,
                   'metrics': {'battleGameSeconds': {'n': 0}}}
        result = {'batchCount': 2, 'profileOrderCounts': {'AB': 1, 'BA': 1},
                  'profiles': {'A': copy.deepcopy(profile), 'B': copy.deepcopy(profile)}}
        with mock.patch.object(campaign.ab, 'aggregate', return_value=result):
            campaign.finish(self.out, manifest)
        self.assertEqual(manifest['status'], 'COMPLETE')
        self.assertEqual(manifest['aggregate']['metricN'], {'A': 0, 'B': 0})
        self.assertNotIn('winner', json.loads((self.out / 'aggregate.json').read_text(encoding='utf-8')))


if __name__ == '__main__':unittest.main(verbosity=2)

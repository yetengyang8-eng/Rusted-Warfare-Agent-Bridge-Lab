import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
from audit_global_strategy import audit


class AuditTests(unittest.TestCase):
    def run_rows(self, rows):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'battle-fixture.jsonl'
            path.write_text(''.join(json.dumps({'wallTimeMs': i, 'event': e, 'data': d}) + '\n'
                                    for i, (e, d) in enumerate(rows)), encoding='utf-8')
            return audit(path)

    @staticmethod
    def block(status='BLOCKED_TERRAIN'):
        return ('engagement_observation', {'targetId': 230, 'targetVisible': True,
                 'targetX': 290, 'targetY': 70, 'actors': [{'unitId': 4, 'status': status}]})

    @staticmethod
    def attack():
        return [('tactical_intent', {'reason': 'OBSERVED_ENEMY', 'enemy': {'id': 230}}),
                ('action', {'owner': 'rule-main', 'path': '/command/attack-move?unitIds=4&x=290&y=70'})]

    def test_rejects_attack_after_terrain_evidence(self):
        result = self.run_rows([self.block()] + self.attack() + [('summary', {'outcome': 'PARTIAL'})])
        self.assertFalse(result['pass'])
        self.assertEqual(result['violations'][0]['reason'], 'MAIN_FORCE_ORDER_AFTER_TERRAIN_REJECTION')

    def test_unknown_does_not_erase_rejection(self):
        result = self.run_rows([self.block(), self.block('UNKNOWN')] + self.attack() + [('summary', {'outcome': 'PARTIAL'})])
        self.assertFalse(result['pass'])

    def test_new_known_approach_can_release(self):
        result = self.run_rows([self.block(), self.block('APPROACH_PATH_KNOWN')] + self.attack() + [('summary', {'outcome': 'PARTIAL'})])
        self.assertTrue(result['pass'])

    def test_detects_task_theft_even_if_eventually_released(self):
        rows = [('task_ownership_acquired', {'taskId': 1000001, 'unitId': 4, 'owner': 'strategy:1000001'})]
        rows += self.attack()
        rows += [('task_ownership_released', {'taskId': 1000001}), ('summary', {'outcome': 'PARTIAL'})]
        self.assertEqual(self.run_rows(rows)['violations'][0]['reason'], 'COMMAND_STOLE_LEASE')

    def test_rejects_mine_completion_without_own_observation(self):
        result = self.run_rows([('mine_upgrade_observed', {'unit': {'id': 10, 'type': 'extractorT2', 'buildProgress': 1}}),
                                ('summary', {'outcome': 'PARTIAL'})])
        self.assertEqual(result['violations'][0]['reason'], 'UNOBSERVED_INVESTMENT_COMPLETION')

    def test_missing_summary_is_incomplete(self):
        self.assertFalse(self.run_rows([self.block()])['pass'])

    def test_native_t3_completion_matches_exact_ready_owned_type(self):
        unit = {'id': 10, 'type': 'extractorT3', 'buildProgress': 1, 'hp': 2000, 'mobile': False, 'canAttack': False}
        rows = [('observation', {'ownUnits': [unit], 'player': {'credits': 9000}}),
                ('mine_upgrade_observed', {'product': 'extractorT3', 'unit': unit}),
                ('summary', {'outcome': 'PARTIAL'})]
        self.assertTrue(self.run_rows(rows)['pass'])
        rows[1][1]['product'] = 'extractorT2'
        self.assertIn('UPGRADE_TYPE_MISMATCH', [v['reason'] for v in self.run_rows(rows)['violations']])
        rows[1][1]['product'] = 'extractorT3_overclocked'
        self.assertIn('UPGRADE_TYPE_MISMATCH', [v['reason'] for v in self.run_rows(rows)['violations']])

    def test_partial_moving_formation_query_is_not_a_committed_assessment(self):
        rows = [('battle_config', {'engagementAssessmentContract': 'COMMITTED_FORMATION_V1'}), self.block()]
        rows += [('engagement_assessment_deferred', {'targetId': 230})] + self.attack() + [('summary', {'outcome': 'PARTIAL'})]
        self.assertTrue(self.run_rows(rows)['pass'])
        rows.insert(2, ('engagement_assessment', self.block()[1]))
        self.assertFalse(self.run_rows(rows)['pass'])

    def test_different_ordered_coordinate_is_explicitly_outside_negative_scope(self):
        attack = self.attack()
        attack[0][1]['enemy'].update(x=500, y=70)
        result = self.run_rows([self.block()] + attack + [('summary', {'outcome': 'PARTIAL'})])
        self.assertTrue(result['pass'])
        self.assertEqual(len(result['coordinateScopeSkips']), 1)


if __name__ == '__main__':
    unittest.main()

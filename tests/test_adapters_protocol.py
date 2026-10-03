"""Focused protocol authorization tests. Fixtures are not native multiplayer proof."""
import copy
import tempfile
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from adapters.overlay import BASELINES, ROOT, artifact, export_sources
from protocol.gateway import PlayerGateway, ProtocolError

class NativeFixture:
    def __init__(self):
        self.state = {"status": "running", "protocolVersion": 1, "networked": True,
                      "nativeNetworkPlayerV1": True, "transport": "native-network-local-player",
                      "replay": False, "matchId": "match", "sessionId": "session-A", "playerId": 0, "teamId": 2,
                      "identity": {"matchId": "match", "playerId": 0, "teamId": 2,
                                   "bindingValid": True, "localPlayerVerified": True},
                      "frame": 100, "gameTimeMs": 1000,
                      "player": {"teamId": 0, "allyGroup": 2, "credits": 500},
                      "ownUnits": [{"id": 17, "type": "builder", "dead": False, "x": 100, "y": 200}],
                      "map": {"width": 2000, "height": 2000}, "match": {"outcome": "ONGOING"}}
        self.posts = []

    def request(self, method, path):
        route = urlsplit(path).path
        if method == "GET":
            if route == "/state":
                return 200, copy.deepcopy(self.state)
            if route == "/combat/observe":
                return 200, {"sessionId": self.state["sessionId"], "frame": 101,
                             "visibleEnemies": [{"id": 42, "x": 800}],
                             "rememberedEnemies": [{"id": 99, "x": 999}],
                             "enemyIntel": [{"id": 999, "visible": False}]}
            if route == "/combat/production":
                return 200, {"sessionId": self.state["sessionId"], "factories": [
                    {"id": 17, "actions": [{"actionId": "produce_tank", "type": "tank", "cost": 350,
                                             "affordable": True, "internalStrategyObject": "MUST_NOT_PASS"}]},
                    {"id": 999, "actions": [{"actionId": "hidden_enemy_menu", "type": "heavyTank"}]}]}
        self.posts.append(path)
        params = parse_qs(urlsplit(path).query)
        return 200, {"status": "queued", "sessionId": self.state["sessionId"],
                     "requestId": params["requestId"][0], "frame": 101}

class PlayerProtocolTests(unittest.TestCase):
    def setUp(self):
        self.native = NativeFixture()
        self.gateway = PlayerGateway(1234, "match", "test", player_id=0, upstream=self.native.request)

    def action(self, **updates):
        observation = self.gateway.observe()
        action = {"protocolVersion": 1, "identity": observation["identity"],
                  "actionSequence": 1, "observationSequence": observation["observationSequence"],
                  "requestId": "request-one", "kind": "move", "unitIds": [17], "x": 300, "y": 400}
        action.update(updates)
        return action

    def test_observation_uses_real_ally_group_and_only_current_visible(self):
        observation = self.gateway.observe()
        self.assertEqual(observation["identity"]["playerId"], 0)
        self.assertEqual(observation["identity"]["teamId"], 2)
        self.assertEqual(observation["currentVisibleEnemies"], [{"id": 42, "x": 800}])
        self.assertNotIn("rememberedEnemies", observation)
        self.assertNotIn("enemyIntel", observation)
        self.assertEqual(observation["sourceFrames"], {"own": 100, "visibleEnemies": 101})

    def test_action_is_identity_bound_and_acknowledges_queue_only(self):
        action = self.action()
        status, receipt = self.gateway.action(action)
        self.assertEqual(status, 200)
        self.assertEqual(receipt["status"], "QUEUED")
        self.assertEqual(receipt["executedEffect"], "UNKNOWN")
        self.assertEqual(receipt["identity"], action["identity"])
        params = parse_qs(urlsplit(self.native.posts[0]).query)
        self.assertEqual(params["sessionId"], ["session-A"])

    def test_other_player_unit_rejected_before_upstream(self):
        with self.assertRaises(ProtocolError):
            self.gateway.action(self.action(unitIds=[999]))
        self.assertFalse(self.native.posts)

    def test_changed_identity_and_player_rebind_fail_closed(self):
        action = self.action()
        action["identity"] = {**action["identity"], "playerId": 1}
        with self.assertRaises(ProtocolError):
            self.gateway.action(action)
        self.native.state["sessionId"] = "another-session"
        with self.assertRaises(ProtocolError):
            self.gateway.observe()

    def test_missing_network_capability_or_binding_is_rejected(self):
        for field, value in (("networked", False), ("nativeNetworkPlayerV1", False), ("transport", "local")):
            original = self.native.state[field]
            self.native.state[field] = value
            with self.assertRaises(ProtocolError):
                self.gateway.observe()
            self.native.state[field] = original
        self.native.state["identity"]["localPlayerVerified"] = False
        with self.assertRaises(ProtocolError):
            self.gateway.observe()

    def test_duplicate_request_is_idempotent_and_conflict_rejected(self):
        action = self.action()
        first = self.gateway.action(action)
        self.assertEqual(first, self.gateway.action(action))
        self.assertEqual(len(self.native.posts), 1)
        with self.assertRaises(ProtocolError):
            self.gateway.action({**action, "x": 999})

    def test_future_observation_and_backward_action_sequence_rejected(self):
        action = self.action()
        with self.assertRaises(ProtocolError):
            self.gateway.action({**action, "observationSequence": 999})
        self.gateway.action(action)
        with self.assertRaises(ProtocolError):
            self.gateway.action({**action, "requestId": "different"})

    def test_nonfinite_coordinate_and_debug_endpoints_rejected(self):
        with self.assertRaises((ProtocolError, ValueError)):
            self.gateway.action(self.action(x=float("nan")))
        for path in ("/test/move-first", "/referee/state", "/state", "/combat/debug"):
            with self.assertRaises(ProtocolError):
                self.gateway.dispatch("GET", path)
        self.assertFalse(self.native.posts)

    def test_request_ids_match_native_endpoint_intersection(self):
        action = self.action()
        for request_id in ("contains.dot", "contains:colon", "x" * 65, ""):
            with self.assertRaises(ProtocolError):
                self.gateway.action({**action, "requestId": request_id})
        self.assertFalse(self.native.posts)

    def test_action_menu_filters_foreign_actors_and_internal_fields(self):
        status, menu = self.gateway.dispatch("GET", "/v1/action-menu?kind=produce")
        self.assertEqual(status, 200)
        self.assertEqual(menu["nativeOwnActions"], [{"unitId": 17, "actionId": "produce_tank",
                                                   "type": "tank", "cost": 350, "affordable": True}])
        with self.assertRaises(ProtocolError):
            self.gateway.dispatch("GET", "/v1/action-menu?kind=produce&unitId=999")
        self.assertFalse(self.native.posts)

class FrozenOverlayTests(unittest.TestCase):
    def test_frozen_artifacts_and_sources_export_without_strategy_change(self):
        for agent, (snapshot, _, _) in BASELINES.items():
            with tempfile.TemporaryDirectory() as output:
                sources = export_sources(agent, output)
                self.assertTrue(artifact(agent)["artifactDigest"].startswith("sha256:"))
                self.assertTrue(any(source.name == "NativeTransportGuard.java" for source in sources))
                # Exact source digests in export_sources prove baseline immutability.
                battle = next(source for source in sources if source.name == "BattleClient.java")
                original = (ROOT / "agents" / snapshot / "agent/src/io/rwagent/client/BattleClient.java").read_text()
                self.assertEqual(original.count("Local active match required"), 1)
                self.assertIn("NativeTransportGuard.require(s)", battle.read_text())
                self.assertTrue((Path(output) / "overlay-sources.json").is_file())

if __name__ == "__main__":
    unittest.main()

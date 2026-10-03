#!/usr/bin/env python3
"""Read-only specialist lifecycle audit for the 2026-09-30 policy candidate.

Old reports are negative controls for this NEW policy, not retroactive failures
of their original contracts. Fog/ownership legality remains a separate audit.
"""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


ORDINARY_PRODUCTS = {"extractorT1", "landFactory", "heavyTank"}
POLICY = "SPECIALIST_LIFECYCLE_2026_09_30_V1"


def distance(unit, x, y):
    return math.hypot(unit.get("x", math.inf) - x,
                      unit.get("y", math.inf) - y)


def audit(path):
    path = Path(path)
    digest = hashlib.sha256()
    events, products = collections.Counter(), collections.Counter()
    own, observed, tasks, responses = {}, {}, {}, []
    jobs, last_orders, last_response, assessments = {}, {}, {}, {}
    commitments, visible_ids = {}, set()
    provenance, config, summary, map_info, player = {}, {}, {}, {}, {}
    integrity, violations, prospect, construction, repeats, home_moves = [], [], [], [], [], []
    deaths, losses, bindings = [], [], []
    battle_start = last_time = size = 0
    home = None

    def record_violation(line, reason, detail):
        violations.append({"line": line, "reason": reason, **detail})

    with path.open("rb") as source:
        for line, raw in enumerate(source, 1):
            digest.update(raw)
            size += len(raw)
            try:
                row = json.loads(raw)
                event, data = row["event"], row["data"]
                if not isinstance(data, dict):
                    raise ValueError("event data is not an object")
            except (ValueError, KeyError, TypeError) as error:
                integrity.append({"line": line, "reason": "BAD_EVENT", "detail": str(error)})
                continue
            events[event] += 1
            last_time = data.get("gameTimeMs", last_time)
            if event == "health":
                provenance = data.get("provenance", {})
            elif event == "report_provenance":
                provenance.update(data)
            elif event == "battle_config":
                config = data
            elif event == "combat_observation":
                visible_ids = {u["id"] for u in data.get("visibleEnemies", [])}
                for response in last_response.values():
                    if response["targetId"] in visible_ids:
                        response["lostContactSinceGameTimeMs"] = None
            elif event == "response_engagement_observation":
                for actor in data.get("actors", []):
                    assessments[(actor.get("unitId"), data.get("targetId"))] = {
                        "line": line, "gameTimeMs": last_time, "targetVisible": data.get("targetVisible")}
                    for response in last_response.values():
                        if response["unitId"] == actor.get("unitId") and response["targetId"] == data.get("targetId"):
                            if data.get("targetVisible") is False:
                                if response.get("lostContactSinceGameTimeMs") is None:
                                    response["lostContactSinceGameTimeMs"] = last_time
                                    response["lostContactSinceLine"] = line
                            elif data.get("targetVisible") is True:
                                response["lostContactSinceGameTimeMs"] = None
            elif event == "observation":
                if events[event] == 1:
                    battle_start = last_time
                    map_info, player = data.get("map", {}), data.get("player", {})
                own = {u["id"]: u for u in data.get("ownUnits", [])}
                home = next((u for u in own.values() if u.get("type") == "commandCenter"
                             and not u.get("dead") and u.get("hp", 0) > 0), None)
                for unit in own.values():
                    if unit.get("type") != "combatEngineer":
                        continue
                    uid = unit["id"]
                    item = observed.setdefault(uid, {
                        "unitId": uid, "type": unit["type"], "firstObservationLine": line,
                        "firstGameTimeMs": last_time, "responseAssignments": 0,
                        "prospectOrders": 0, "constructionProducts": {}, "ownLossLine": None})
                    item.update(lastObservationLine=line, lastGameTimeMs=last_time,
                                lastHp=unit.get("hp"), lastX=unit.get("x"), lastY=unit.get("y"))
                for task, response in last_response.items():
                    unit = own.get(response["unitId"])
                    if jobs.get(task) in ("RESPONSE", "INVESTIGATE") and unit:
                        if distance(unit, response["approachX"], response["approachY"]) < 20:
                            if response.get("arrivalLine") is None:
                                response.update(arrivalLine=line, arrivalGameTimeMs=last_time)
                            if response.get("nearSinceGameTimeMs") is None:
                                response.update(nearSinceGameTimeMs=last_time, nearSinceLine=line)
                        else:
                            response["nearSinceGameTimeMs"] = None
            elif event == "own_loss":
                if data.get("type") == "combatEngineer":
                    death = {"line": line, **data}
                    deaths.append(death)
                    if data.get("unitId") in observed:
                        observed[data["unitId"]]["ownLossLine"] = line
            elif event == "task_ownership_acquired":
                tasks[data["taskId"]] = data["unitId"]
                jobs[data["taskId"]] = "IDLE"
            elif event == "strategy_task_assigned":
                task, uid = data["taskId"], data["unitId"]
                tasks[task], jobs[task] = uid, "RESPONSE"
                response = {"line": line, "taskId": task, "unitId": uid,
                            "targetId": data["targetId"], "gameTimeMs": last_time,
                            "approachX": data["approachX"], "approachY": data["approachY"],
                            "objectiveSemantics": data.get("objectiveSemantics"),
                            "arrivalLine": None, "orders": 0, "ordersNearApproach": 0,
                            "nearSinceGameTimeMs": None,
                            "lostContactSinceGameTimeMs": last_time if data.get("evidence", {}).get("targetVisible") is False else None,
                            "lostContactSinceLine": line if data.get("evidence", {}).get("targetVisible") is False else None}
                responses.append(response)
                last_response[task] = response
                last_orders.pop(task, None)
                if uid in observed:
                    observed[uid]["responseAssignments"] += 1
            elif event == "strategy_response_replanned":
                task = data["taskId"]
                if task in last_response:
                    last_response[task].update(approachX=data["x"], approachY=data["y"], arrivalLine=None, nearSinceGameTimeMs=None)
                last_orders.pop(task, None)
            elif event == "strategy_response_ordered":
                task = data["taskId"]
                if jobs.get(task) == "INVESTIGATE":
                    record_violation(line, "RESPONSE_ORDER_DURING_INVESTIGATION", {
                        "taskId": task, "unitId": data.get("unitId"), "targetId": data.get("targetId")})
                response = last_response.get(task)
                unit = own.get(data.get("unitId"))
                receipt = data.get("receipt", {})
                if response and unit:
                    x = receipt.get("targetX", response["approachX"])
                    y = receipt.get("targetY", response["approachY"])
                    near = distance(unit, x, y) < 20
                    response["orders"] += 1
                    response["ordersNearApproach"] += int(near)
                    if near and last_orders.get(task) == (x, y):
                        assessment = assessments.get((data["unitId"], data["targetId"]), {})
                        contact = ("VISIBLE_IN_LATEST_COMBAT_OBSERVATION" if data["targetId"] in visible_ids
                                   else "LOST_IN_LAST_RESPONSE_ASSESSMENT" if assessment.get("targetVisible") is False
                                   else "VISIBLE_IN_LAST_RESPONSE_ASSESSMENT" if assessment.get("targetVisible") is True
                                   else "UNKNOWN")
                        item = {"line": line, "taskId": task, "unitId": data["unitId"],
                                "targetId": data["targetId"], "gameTimeMs": last_time,
                                "distance": round(distance(unit, x, y), 3),
                                "orderTypeObserved": unit.get("orderType"), "x": x, "y": y,
                                "contactEvidence": contact, "lastResponseAssessment": assessment,
                                "classification": "DIAGNOSTIC_NOT_AUTOMATIC_POLICY_VIOLATION"}
                        repeats.append(item)
                        near_since = response.get("nearSinceGameTimeMs")
                        lost_since = response.get("lostContactSinceGameTimeMs")
                        if (contact == "LOST_IN_LAST_RESPONSE_ASSESSMENT" and near_since is not None
                                and lost_since is not None and last_time - near_since >= 12000
                                and last_time - lost_since > 10000):
                            item.update(classification="NEW_POLICY_LOST_CONTACT_AT_APPROACH_REPEAT",
                                        nearSinceLine=response.get("nearSinceLine"), nearGameMs=last_time-near_since,
                                        lostContactSinceLine=response.get("lostContactSinceLine"), lostContactGameMs=last_time-lost_since)
                            record_violation(line, "REPEATED_LOST_CONTACT_ORDER_AT_APPROACH", item)
                    last_orders[task] = (x, y)
            elif event == "strategy_prospect_ordered":
                task, uid = data["taskId"], data["unitId"]
                jobs[task] = "PROSPECT"
                if own.get(uid, {}).get("type") == "combatEngineer":
                    item = {"line": line, "taskId": task, "unitId": uid,
                            "gameTimeMs": last_time, "tile": data.get("tile")}
                    prospect.append(item)
                    observed[uid]["prospectOrders"] += 1
                    record_violation(line, "ENGINEER_ORDINARY_PROSPECT", item)
            elif event == "strategy_construction_ordered":
                task, uid, product = data["taskId"], data["unitId"], data["product"]
                jobs[task] = "MINE" if product == "extractorT1" else "BUILD"
                if own.get(uid, {}).get("type") == "combatEngineer":
                    item = {"line": line, "taskId": task, "unitId": uid, "product": product,
                            "gameTimeMs": last_time, "x": data.get("x"), "y": data.get("y")}
                    construction.append(item)
                    products[product] += 1
                    counts = observed[uid]["constructionProducts"]
                    counts[product] = counts.get(product, 0) + 1
                    if product in ORDINARY_PRODUCTS:
                        record_violation(line, "ENGINEER_ORDINARY_CONSTRUCTION", item)
            elif event in ("strategy_prospect_observed", "strategy_construction_observed", "strategy_task_completed"):
                if event != "strategy_task_completed" or jobs.get(data["taskId"]) != "BUILD":
                    jobs[data["taskId"]] = "IDLE"
                if event == "strategy_task_completed":
                    commitments.pop(data["taskId"], None)
            elif event == "strategy_task_preempted":
                jobs[data["taskId"]] = "RETURN"
                if data["taskId"] in last_response:
                    last_response[data["taskId"]].update(endLine=line, endReason=data.get("reason"))
            elif event == "strategy_task_blocked":
                task = data["taskId"]
                jobs[task] = "RETURN" if data.get("reason") == "NO_OBSERVED_PROGRESS" else "IDLE"
                if task in last_response:
                    last_response[task].update(endLine=line, endReason=data.get("reason"))
            elif event == "strategy_task_lost":
                losses.append({"line": line, **data, "unitType": observed.get(data.get("unitId"), {}).get("type")})
                jobs.pop(data["taskId"], None)
                commitments.pop(data["taskId"], None)
            elif event == "task_ownership_released":
                jobs.pop(data["taskId"], None)
                commitments.pop(data["taskId"], None)
            elif event == "strategy_investigation_started":
                jobs[data["taskId"]] = "INVESTIGATE"
            elif event == "strategy_investigation_exhausted":
                jobs[data["taskId"]] = "RETURN"
                if data["taskId"] in last_response:
                    last_response[data["taskId"]].update(endLine=line, endReason=data.get("reason"))
            elif event == "strategy_investigation_contact_restored":
                jobs[data["taskId"]] = "RESPONSE"
            elif "need_binding" in event or event in ("strategy_worker_committed", "strategy_worker_commitment_released", "strategy_purchase_committed",
                                                       "strategy_support_construction", "strategy_support_transferred"):
                bindings.append({"line": line, "event": event, **data})
                if event == "strategy_worker_committed":
                    commitments[data["taskId"]] = {"unitId": data["unitId"], "needId": data["needId"], "line": line}
                elif event == "strategy_worker_commitment_released":
                    commitments.pop(data["taskId"], None)
                    # NEED_ATTEMPT_LIMIT returns an active responder home; BUILD/RETURN remain as-is.
                    # A stale-site release already occurred in RETURN and can become IDLE silently.
                    if data.get("reason") == "NEED_ATTEMPT_LIMIT" and jobs.get(data["taskId"]) not in ("RETURN", "BUILD"):
                        jobs[data["taskId"]] = "RETURN"
                elif event == "strategy_support_transferred":
                    commitments.pop(data["taskId"], None)
            elif event == "action" and data.get("owner", "").startswith("strategy:"):
                action = urlsplit(data["path"])
                query = parse_qs(action.query)
                task = int(data["owner"].split(":", 1)[1])
                uid = int(query.get("unitId", ["-1"])[0])
                unit = own.get(uid)
                if action.path == "/command/move" and jobs.get(task) == "RETURN" and unit and home:
                    x, y = float(query.get("x", ["nan"])[0]), float(query.get("y", ["nan"])[0])
                    if distance(unit, home["x"], home["y"]) < 180 and distance(home, x, y) < 1:
                        item = {"line": line, "taskId": task, "unitId": uid, "unitType": unit.get("type"),
                                "gameTimeMs": last_time, "hp": unit.get("hp"),
                                "distanceHome": round(distance(unit, home["x"], home["y"]), 3)}
                        home_moves.append(item)
                        record_violation(line, "RETURN_ORDER_ALREADY_AT_HOME", item)
            elif event == "summary":
                summary = data
    if not summary:
        integrity.append({"reason": "MISSING_SUMMARY"})
    counts = collections.Counter(item["reason"] for item in violations)
    return {
        "schemaVersion": 1, "source": str(path.resolve()), "sourceSha256": digest.hexdigest(), "sourceBytes": size,
        "candidateProvenance": provenance, "battleConfig": config,
        "controlConditions": {"mapDimensions": map_info, "playerScope": config.get("executionPlayerScope", "UNKNOWN"),
                              "initialPlayer": player, "battleStartGameTimeMs": battle_start,
                              "pollWallTimeMs": config.get("pollWallTimeMs", "UNKNOWN"),
                              "mapPath": "UNKNOWN", "difficulty": "UNKNOWN", "seed": "UNKNOWN",
                              "seedReproducibilityVerified": False, "requestedSpeed": "UNKNOWN",
                              "measuredGameSecondsPerWallSecond": summary.get("gameSecondsPerWallSecond", "UNKNOWN"),
                              "bootstrapIntervention": "UNKNOWN", "source": "RAW_REPORT_ONLY_NO_OPERATOR_CONTEXT_INFERRED"},
        "summary": summary, "engineersObserved": len(observed), "engineerOwnLossEvents": len(deaths),
        "engineerIdsWithOwnLoss": sorted({d["unitId"] for d in deaths}),
        "engineers": list(observed.values()), "engineerDeaths": deaths,
        "engineerTaskLosses": [d for d in losses if d["unitType"] == "combatEngineer"],
        "engineerProspectOrders": prospect, "engineerConstructionOrders": construction,
        "engineerConstructionProducts": dict(products), "responseAssignments": responses,
        "responseOrdersRepeatedAtApproach": repeats, "returnOrdersAlreadyAtHome": home_moves,
        "responseRepeatContactEvidence": dict(collections.Counter(r["contactEvidence"] for r in repeats)),
        "newLifecycleEvents": {k: v for k, v in events.items() if "investigation" in k or "need_binding" in k
                               or k in ("strategy_worker_committed", "strategy_worker_commitment_released", "strategy_purchase_committed",
                                        "strategy_support_construction", "strategy_support_transferred")
                               or k in ("strategy_return_arrived", "strategy_recovery_started", "strategy_recovery_completed")},
        "needBindings": bindings, "workerCommitmentsAtEnd": commitments, "reportIntegrityIssues": integrity,
        "newPolicy": POLICY, "newPolicyConformancePassed": not integrity and not violations,
        "newPolicyViolationsByReason": dict(counts), "newPolicyViolations": violations,
        "interpretation": "NEW_POLICY_NEGATIVE_CONTROLS_DO_NOT_CHANGE_HISTORICAL_AUDIT_RESULTS",
        "limits": ["Not a fog/ownership legality audit; run audit_global_strategy.py separately.",
                   "Own-loss events are observed disappearance from own state, not proof of enemy kill attribution.",
                   "Near-point uses the latest preceding legal own observation (<20 world units); no hidden target reads.",
                   "Near-point repeats are policy failures only with explicit lost contact, continuous sampled arrival >=12s and first lost assessment >10s ago; visible/unknown remain diagnostics.",
                   "Worker commitment release does not itself release ownership or complete a still-running support BUILD.",
                   "At-home requires reconstructed RETURN job and a move to the observed command center (<180 world units).",
                   "Missing movement/response coverage is not evidence of benefit; no win-rate or matched-run claim."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--enforce-policy", action="store_true", help="Exit 1 on new-policy violations; use for the new candidate.")
    args = parser.parse_args()
    result = audit(args.report)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"source": args.report.name, "engineersObserved": result["engineersObserved"],
                      "engineerOwnLossEvents": result["engineerOwnLossEvents"],
                      "newPolicyConformancePassed": result["newPolicyConformancePassed"],
                      "newPolicyViolationsByReason": result["newPolicyViolationsByReason"],
                      "reportIntegrityIssues": result["reportIntegrityIssues"], "out": str(args.out)}, ensure_ascii=False))
    return int(bool(result["reportIntegrityIssues"]) or args.enforce_policy and not result["newPolicyConformancePassed"])


if __name__ == "__main__":
    raise SystemExit(main())

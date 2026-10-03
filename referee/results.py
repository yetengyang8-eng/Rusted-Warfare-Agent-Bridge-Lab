"""Classify native evidence without inventing a victory from a process exit.

The referee consumes lifecycle metadata only. It neither reads hidden units nor
supplies any information to either participant.
"""


def native_outcome(runtime):
    result = runtime.get("nativeResult")
    if isinstance(result, dict):
        if result.get("victory") is True or result.get("won") is True or result.get("nativeVictory") is True:
            return "victory"
        if result.get("defeat") is True or result.get("lost") is True or result.get("nativeDefeat") is True:
            return "defeat"
        result = result.get("outcome", result.get("status", result.get("result")))
    if isinstance(result, str) and result.lower() in ("victory", "defeat", "draw"):
        return result.lower()
    return "unknown"


def classify(participants, reason):
    """Require both native clients to agree before attributing a winner."""
    outcomes = [native_outcome(p.get("runtime", {})) for p in participants]
    result = {"termination": reason, "winner": None, "loser": None,
              "nativeOutcomes": outcomes, "status": "UNKNOWN"}
    if len(participants) == 2 and outcomes in (["victory", "defeat"], ["defeat", "victory"]):
        winner = outcomes.index("victory")
        result.update(status="NATIVE_RESULT", winner=participants[winner]["participantId"],
                      loser=participants[1 - winner]["participantId"])
    elif outcomes == ["draw", "draw"]:
        result["status"] = "NATIVE_DRAW"
    elif "victory" in outcomes or "defeat" in outcomes:
        result["status"] = "NATIVE_RESULT_UNCONFIRMED"
    elif reason in ("timeout", "startup_timeout"):
        result["status"] = "TIMEOUT"
    elif reason == "disconnect":
        result["status"] = "DISCONNECT"
    elif reason == "crash":
        result["status"] = "CRASH"
    elif reason == "interrupted":
        result["status"] = "INTERRUPTED"
    return result


def same_game_evidence(participants):
    """Match ID supplied by the harness alone is insufficient same-game proof."""
    runtimes = [p.get("runtime", {}) for p in participants]
    if len(runtimes) != 2:
        return {"status": "NEEDS_EVIDENCE", "reason": "requires two native clients"}
    a, b = runtimes
    identity = a.get("nativeServerId")
    started = all(r.get("networked") is True and r.get("networkStarted") is True
                  and r.get("localPlayerBound") is True for r in runtimes)
    players = all(r.get("playerId") is not None for r in runtimes) and a.get("playerId") != b.get("playerId")
    if started and players and identity and identity == b.get("nativeServerId"):
        return {"status": "PASS", "nativeServerId": identity,
                "playerIds": [a["playerId"], b["playerId"]],
                "scope": "native transport and player identity; command sync and fog need separate evidence"}
    return {"status": "NEEDS_EVIDENCE", "networkStarted": started,
            "distinctPlayers": players, "matchingNativeServerId": bool(identity and identity == b.get("nativeServerId"))}


def synchronization_evidence(participants):
    """Compare only native checksums identified by the SAME checksum frame."""
    runtimes = [p.get("runtime", {}) for p in participants]
    result = {"status": "NEEDS_EVIDENCE", "desyncErrors": [r.get("desyncErrors") for r in runtimes],
              "resyncCounts": [r.get("resyncCount") for r in runtimes],
              "checksumFrames": [r.get("checksumFrame") for r in runtimes],
              "nativeChecksums": [r.get("nativeChecksum") for r in runtimes],
              "checksumComparison": "NOT_COMPARABLE", "scope": "native transport synchronization metadata"}
    if any(isinstance(value, (int, float)) and value > 0 for value in result["desyncErrors"]):
        result["status"] = "DESYNC_REPORTED"
    elif any(isinstance(value, (int, float)) and value > 0 for value in result["resyncCounts"]):
        result["status"] = "RESYNC_REPORTED"
    if len(runtimes) == 2:
        frames = result["checksumFrames"]
        sums = result["nativeChecksums"]
        if frames[0] is not None and frames[0] > 0 and frames[0] == frames[1] and all(value is not None for value in sums):
            result["checksumComparison"] = "MATCH" if sums[0] == sums[1] else "MISMATCH"
            if sums[0] != sums[1]:
                result["status"] = "CHECKSUM_MISMATCH"
            elif result["status"] == "NEEDS_EVIDENCE" and all(value == 0 for value in result["desyncErrors"] + result["resyncCounts"]):
                result["status"] = "MATCH_AT_NATIVE_CHECKSUM_FRAME"
        elif frames[0] != frames[1]:
            result["checksumComparisonReason"] = "different checksum frames; checksum values were not compared"
    return result

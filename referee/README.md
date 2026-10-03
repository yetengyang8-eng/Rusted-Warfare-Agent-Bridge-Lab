# Thin referee

`results.py` classifies only native lifecycle and result evidence. It does not
query enemy units, infer concealed positions, or pass information to Agents.

Native victory and defeat are attributed only when both clients agree. A
timeout, crash, lost connection, empty queue or missing unit cannot establish
a winner. A single native victory flag without peer defeat is reported as
`NATIVE_RESULT_UNCONFIRMED`. Batch win counts include only confirmed results.

`same_game_evidence` checks native network startup/player binding, distinct
player IDs and a shared native server ID. Its scope is transport and identity;
it does not equate host/join with command synchronization or fog correctness.

`synchronization_evidence` retains native desync and resync counts explicitly.
Checksums from different native checksum frames are never compared. Matching
checksums support synchronization only at their named native frame.

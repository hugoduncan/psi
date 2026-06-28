# Boundary Tests

| ID | Scenario | Arrange | Assert |
|---|---|---|---|
| B1 | Sync/async boundary | Tool finishes at threshold between inline and background path | Exactly one mode chosen; never both result and job start |
| B2 | Global uniqueness at scale | High-volume concurrent job creation | No duplicate `job_id` across runtime |
| B3 | Same timestamp ordering tie | Multiple completions with equal/near-equal timestamps | Deterministic completion-order tie handling (stable ordering policy) |
| B4 | At-most-once under concurrent emitters | Concurrent emit attempts for same terminal job | Exactly one synthetic assistant message persisted |
| B5 | Payload size boundaries | Payload at `max_bytes`, `max_lines`, and +1 | At-limit stays inline; over-limit spills to temp file |
| B6 | Retention at limit | Exactly 20 terminal jobs in thread | No eviction |
| B7 | Retention overflow | 21+ terminal jobs | Oldest terminal evicted by completion time; newest kept |
| B8 | Mixed retention set | Terminal overflow with active non-terminal jobs present | Non-terminal jobs preserved; only terminal jobs evicted |

Approach:
- keep the lower `session-persistence` component as the authoritative owner of persistence semantics while removing direct production file IO from its append/fork helper paths
- land this as a narrow boundary extraction rather than a persistence redesign: preserve file format, lazy flush policy, append-first semantics, and existing read/list/load ownership
- introduce one explicit persistence IO effect family, `:persist/session-journal-io`, with `:op :append-entry` and `:op :flush-journal`
- add a pure lower-component decision helper that, given the would-be post-append entries vector, flush-state, and fully materialized session metadata, returns either `nil` or a canonical IO request map
- move production append flows off `persist/append-entry-in!` as the authoritative path; handler logic should own in-memory append, shape the persistence IO request, and return explicit effect data
- keep `:flushed?` transition semantics correct by advancing it from unflushed to flushed mode only after successful `:op :flush-journal` execution
- include lifecycle-driven session-file creation writes, especially fork child-journal flushes, in the same extraction so persistence file writes consistently cross the effect boundary
- route fork/session-file persistence through a dispatch-owned event/effect path rather than direct store calls or direct `execute-effect!` calls from lifecycle code
- preserve one-way dependency direction: `session-persistence` may own pure mutation and policy helpers, but must not depend upward on `session-state.state` or higher orchestration code
- existing `:persist/journal-append-*` effects may remain temporarily only as compatibility/convenience surfaces; they must no longer be the canonical production file-write seam
- keep compatibility helpers only if needed for migration/testing, but ensure production dispatch/effect flows no longer depend on them for IO
- verify the new seam with focused proofs covering pure policy, handler-owned memory append, executor-owned append-vs-flush file execution, failed-flush state behavior, lazy flush, append-after-flush, and fork persistence invariants

Implementation decisions to make explicit while working:
- whether the effect executor calls thin `session-persistence` write wrappers or `session-journal.store` directly; either is acceptable if policy remains lower-owned and execution stays explicit
- which compatibility helpers remain afterward and whether they are reduced to test-only or compatibility-only surfaces

Expected outcome:
- production persistence writes happen only through explicit dispatch effect execution
- handler owns journal memory append while executor owns file IO
- append-first in-memory semantics remain unchanged
- lazy first-assistant flush remains unchanged
- fork/session-file persistence writes also use the same explicit IO boundary
- the lower component becomes a clearer split of pure persistence semantics vs effect-executed file IO

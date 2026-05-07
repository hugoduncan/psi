Approach:
- Treat this as a narrow ownership extraction, not a persistence redesign.
- Use the first cut settled in `design.md`: extract codec + store mechanics only, keep session-facing orchestration and domain entry meaning in `agent-session.persistence`.
- Preserve current file format, lazy flush behavior, and migration semantics throughout.
- Move proof with the ownership boundary: codec/store behavior should be testable primarily from the new component.
- Shape the new component around a deliberate public API, not a mechanical copy of old helper boundaries.

Execution order:
1. classify existing `persistence.clj` functions using the settled keep/move split
2. create `components/session-journal/` with `codec.clj` and `store.clj`
3. establish store root/layout policy in `session-journal.store`, including explicit root override support on filesystem-touching public APIs where needed for tests and controlled callers
4. move codec helpers first and establish focused codec tests
5. move header/store/lock/flush/load/list/migration helpers and establish focused store tests while preserving current observable return shapes for load/list/discovery APIs unless an intentional contract change is recorded
6. update `agent-session.persistence` to depend on the lower component via thin adapters
7. verify one representative `agent-session` orchestration path still exercises write/flush/load through `agent-session.persistence` and the extracted store boundary
8. record any intentionally retained helpers and why they stayed above the boundary

Design decisions already settled for the first cut:
- keep in-memory journal vector helpers in `agent-session.persistence`
- keep domain entry constructors in `agent-session.persistence`
- keep ctx/session-id/root-state append orchestration above the boundary
- keep migration helpers internal to `psi.session-journal.store` while moving migration authority with the store layer
- make codec/store tests primarily component-local; keep only representative integration proof in `agent-session`

Primary risks:
- accidental persistence redesign instead of a bounded ownership extraction
- subtle on-disk compatibility drift during migration/helper movement
- over-splitting into too many namespaces in the first cut
- preserving old helper structure mechanically instead of defining a clear lower-level API

Success test:
- after the split, a reader should be able to answer clearly:
  - `session-journal` owns file/codec/store mechanics
  - `agent-session.persistence` owns session-facing orchestration and domain entry shaping
  - `session-journal.store` owns default root/layout policy
  - most low-level persistence proof now lives with the new component

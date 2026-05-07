Goal: extract the lower-level session-journal codec and file-store substrate from `components/agent-session/src/psi/agent_session/persistence.clj` into an explicit reusable component boundary below `agent-session`, while preserving current session journal behavior and on-disk compatibility.

Context:
- task `095-abstract-state-kernel-extraction-from-agent-session` extracted the generic dispatch/runtime substrate into `components/state-kernel/`
- task `097-session-state-component-extraction-from-agent-session` extracted lower-level session identity/state/tree/worktree machinery into `components/session-state/`
- task `098-journal-append-dispatch-effect-convergence` clarified that canonical journal append ownership lives on the dispatch/effects path
- after those extractions, `psi.agent-session.persistence` still mixes two different layers:
  - higher-level session-facing orchestration over `ctx`, `session-id`, and canonical session state
  - lower-level journal codec/store mechanics for serializing, locking, flushing, loading, listing, and migrating session journal files
- that lower layer is now the clearest remaining low-level component still owned by `agent-session`

Problem:
- `psi.agent-session.persistence` currently acts as both:
  - a session-facing API used by `agent-session` runtime code, and
  - the implementation home for NDEDN session-journal storage mechanics
- this blurs the ownership line in several ways:
  - file locking, file naming, migration, and NDEDN parsing are infrastructure concerns but live in a session-domain namespace
  - consumers cannot tell which helpers are domain semantics versus storage substrate
  - future persistence shaping still treats `agent-session` as the accidental owner of reusable journal-file mechanics
  - the current mixed namespace obscures the intended layer slope after `state-kernel` and `session-state`

Intent:
- create one explicit lower-level component for session-journal codec/store concerns
- move the codec/store/file-system layer below `agent-session`
- keep session-facing orchestration and domain entry meaning above that boundary
- preserve all externally observable persistence behavior while making ownership explicit

Chosen component and namespace direction:
- component path: `components/session-journal/`
- first-cut authoritative namespaces:
  - `psi.session-journal.codec`
  - `psi.session-journal.store`
- first-cut rule: move only mechanics that can be reused by a higher-level session consumer without knowing prompt/tool/workflow/runtime semantics

Store root ownership and policy:
- `psi.session-journal.store` is the authoritative owner of default session-journal root and directory-layout policy
- filesystem-touching public store APIs that own root/layout concerns should support explicit root overrides for tests and controlled callers
- low-level store helpers that operate on explicit `File` values may remain root-implicit behind those public entry points
- `agent-session` must not retain ownership of journal root selection or session directory layout policy

Authoritative first-cut public API target:
- `psi.session-journal.codec` should expose a deliberately small public surface:
  - `entry->line`
  - `parse-line`
- `instant->date` remains internal to codec in the first cut
- `psi.session-journal.store` should expose the first-cut public surface:
  - `session-dir-for`
  - `new-session-file-path`
  - `write-header!`
  - `append-entry-to-disk!`
  - `flush-journal!`
  - `load-session-file`
  - `find-most-recent-session`
  - `list-sessions`
  - `list-all-sessions`
- first-cut extraction must preserve the current observable return shapes of `load-session-file`, `find-most-recent-session`, `list-sessions`, and `list-all-sessions` unless a contract change is explicitly recorded and justified as part of this task
- header validation, path helpers, migration helpers, and lock internals remain store-internal in the first cut

Settled first-cut split:

Move below `agent-session` into `session-journal`:
- codec / serialization helpers:
  - `instant->date`
  - `entry->line`
  - `parse-line`
- low-level header/store helpers:
  - `current-version`
  - default sessions-root ownership and explicit root override support
  - `session-dir-for`
  - `timestamp-prefix`
  - `new-session-file-path`
  - `make-header`
  - `valid-header?`
  - header migration helpers and entry migration helpers
- low-level file mutation helpers:
  - `with-session-file-lock`
  - `append-line!`
  - `write-header!`
  - `append-entry-to-disk!`
  - `flush-journal!`
- low-level load/list/discovery helpers:
  - `load-session-file`
  - `peek-header`
  - `find-most-recent-session`
  - `extract-session-info`
  - `list-sessions`
  - `list-all-sessions`

Keep above the boundary in `agent-session.persistence` for the first cut:
- ctx/session-id/root-state-oriented helpers:
  - `journal-path`
  - `flush-state-path`
  - `state*`
  - `get-state-in`
  - `assoc-state-in!`
  - `append-entry-in!`
  - `all-entries-in`, `entries-of-kind-in`, `entries-up-to-in`, `last-entry-of-kind-in`
  - `messages-from-entries-in`, `messages-up-to-in`
- dispatch/persistence orchestration helpers:
  - `persist-entry-in!`
  - any thin adapters that bridge `agent-session` session state to the lower store API
- domain entry constructors and domain-facing append helpers:
  - `message-entry`
  - `thinking-level-entry`
  - `model-entry`
  - `compaction-entry`
  - `branch-summary-entry`
  - `custom-message-entry`
  - `label-entry`
  - `session-info-entry`
- in-memory journal vector helpers remain above the boundary in `agent-session.persistence` for the first cut:
  - `create-journal`
  - `append-entry!`
  - `all-entries`
  - `entries-of-kind`
  - `entries-up-to`
  - `last-entry-of-kind`
  - `messages-from-entries`
  - `messages-up-to`
  - `create-flush-state`
  - `persist-state-entry!`
  - `persist-entry!`

Reason for this first-cut keep/move line:
- the goal is to extract the file/codec/store substrate, not to redesign all journal APIs
- the moved set is infrastructure-shaped and clearly below `agent-session`
- the kept set still encodes session-runtime orchestration, in-memory session-facing journal manipulation, or domain-shaped journal meaning
- this split minimizes churn while making the dependency slope clearer

Boundary rules:
The new lower component may own only mechanics about journal serialization, migration, storage, and retrieval.

It may own:
- NDEDN line encoding/decoding
- header construction/validation/migration
- session journal file naming and directory layout
- file locking, appending, and flush mechanics
- loading, listing, and session discovery from the journal store

It must not own:
- prompt lifecycle ownership
- workflow/tool/runtime behavior
- canonical dispatch-owned append ownership established by `098`
- root-state/session lookup ownership established by `097`
- session/domain-specific journal entry meaning
- adapter/UI projection concerns

Non-goals and explicit exclusions:
- no session journal format redesign
- no change to persisted compatibility/version semantics except refactoring required to preserve them
- no broad rewrite of entry constructors
- no re-litigation of dispatch-owned append ownership
- no extraction of every persistence helper just because it is nearby

In scope:
- define the `session-journal` component boundary explicitly
- create `components/session-journal/` and wire it as a dependency
- move codec and file-store concerns from `psi.agent-session.persistence`
- preserve on-disk format, migration, lock, and lazy flush behavior
- update `agent-session` consumers to use the extracted lower component
- move or add focused tests so codec/store ownership has local proof
- document what stayed in `agent-session.persistence` and why

Out of scope:
- changing the journal append contract established by `098`
- changing higher-level session runtime behavior beyond import/adaptation changes
- changing transcript semantics, prompt semantics, or workflow semantics
- broad persistence cleanup outside the extraction boundary

Design constraints:
- preserve dependency slope: `session-journal` must not depend on `agent-session`
- prefer a crisp and narrow split over a maximal split
- preserve runtime behavior; ownership change is the main goal
- keep on-disk compatibility and migration behavior authoritative and tested
- avoid unnecessary consumer churn outside the moved boundary

Test ownership expectations:
- first-cut proof should primarily live under `components/session-journal/test/`
- the new lower component should own focused verification for:
  - codec parse/serialize behavior
  - header validation and migration/load compatibility behavior
  - lock/write/append/flush behavior
  - session listing/discovery behavior
- `agent-session` should retain only representative integration proof sufficient to show a consuming session-facing path still works through the extracted store boundary
- done-ness for this task requires that confidence in codec/store behavior no longer depends primarily on `agent-session`-local tests

Acceptance:
- a new `components/session-journal/` component exists with a clear purpose
- codec/store/load/list/lock/flush/migration concerns no longer reside only under `components/agent-session/`
- `agent-session.persistence` becomes thinner and primarily session-facing
- `agent-session` consumes the extracted lower component through explicit dependencies
- no new component cycle is introduced
- current session journal persistence behavior is preserved
- task notes explain the final split and any intentionally retained adapters/helpers
- focused proof covers the extracted lower-level behavior sufficiently to preserve confidence

Concrete done criteria:
- component path and first-cut namespace split are recorded explicitly
- authoritative codec helpers live in `psi.session-journal.codec`
- authoritative store helpers live in `psi.session-journal.store`
- authoritative migration/load/list behavior lives below `agent-session`
- `agent-session.persistence` no longer owns low-level file lock/flush/load/list/migration implementation details
- the store owns default root/layout policy, with explicit root override support where needed for filesystem-touching public APIs used by tests and controlled callers
- the new component exposes a deliberate public API rather than a mechanical namespace dump
- the extracted store preserves the current observable return shapes of `load-session-file`, `find-most-recent-session`, `list-sessions`, and `list-all-sessions` unless an intentional contract change is recorded and justified in task notes
- minimum focused proof covers:
  - codec parse/serialize behavior
  - store write/append/flush behavior
  - store load/list/migration behavior
  - at least one representative `agent-session` orchestration path that reaches write/flush/load through `agent-session.persistence`, not merely direct store invocation from an `agent-session` test namespace

Likely namespace sketch:
- `components/session-journal/src/psi/session_journal/codec.clj`
- `components/session-journal/src/psi/session_journal/store.clj`
- possible test homes:
  - `components/session-journal/test/psi/session_journal/codec_test.clj`
  - `components/session-journal/test/psi/session_journal/store_test.clj`

Related work:
- `095` extracted the generic state kernel
- `097` extracted session-state
- `098` clarified append ownership on dispatch effects
- this task is the next extraction layer: move the journal file/codec/store substrate below `agent-session` without redesigning the higher-level persistence API

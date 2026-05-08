- [x] Refine `session-persistence` into explicit lower pure helpers
  - made `append-journal-entry-root-update` available as the canonical production root-state update helper for journal appends
  - added `mark-flushed-root-update` as the canonical post-success flush-state root-state update helper
  - added pure `persistence-io-request` taking would-be post-append entries + flush-state + materialized session metadata and returning append/flush/no-op request data
  - kept persistence policy lower-owned and pure without adding upward dependencies on dispatch namespaces

- [x] Retire combined memory+IO helpers from the production path
  - production append/fork flows no longer use `append-entry-in!` / `persist-journal-in!` as the authoritative file-write seam
  - compatibility/test surfaces remain temporarily, but now delegate through shared pure request shaping instead of carrying separate production policy branches

- [x] Move journal append memory mutation into the append handler result
  - updated `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_lifecycle.clj`
  - `:session/append-journal-entry` now:
    - derives `next-entries` locally
    - applies `:root-state-update` via lower `append-journal-entry-root-update`
    - materializes session persistence metadata from session state
    - asks lower persistence semantics for append/flush/no-op via `persistence-io-request`
    - returns explicit `:persist/session-journal-io` effect data only when needed
  - preserved current caller-facing append ergonomics through `:session/append-journal-entry`

- [x] Introduce the explicit persistence IO executor boundary
  - added `:persist/session-journal-io` to dispatch schema/effect execution
  - implemented executor support in `components/agent-session/src/psi/agent_session/dispatch_effects.clj`
  - supports:
    - `:op :append-entry`
    - `:op :flush-journal`
  - executor responsibilities now are:
    - perform file-backed persistence only
    - avoid recomputing lazy-flush policy
    - on successful `:op :flush-journal`, apply `mark-flushed-root-update`
    - on failed append/flush, do not advance flush-state
  - normalized `:session-file` to `java.io.File` at the execution boundary

- [x] Rework existing append effect surfaces to bottom out in the new IO boundary
  - current append executors in `dispatch_effects.clj` no longer call `persist/append-entry-in!`
  - retained `:persist/journal-append-*` surfaces now act as compatibility/convenience shims through `:session/append-journal-entry`
  - preserved append-first semantics and higher-level behavior during the transition

- [x] Route fork/session-file creation persistence through the same explicit IO boundary
  - replaced direct lifecycle `journal-store/flush-journal!` production usage in `components/agent-session/src/psi/agent_session/session_lifecycle.clj`
  - preserved child-session lineage/header invariants and current branch-entry persistence semantics
  - kept lifecycle orchestration in `session_lifecycle.clj`, with actual file writes now declared via dispatch-owned `:persist/session-journal-io`
  - did not call `dispatch-effects/execute-effect!` directly from lifecycle code

- [x] Update focused tests to match the refined boundary
  - added focused convergence proof that canonical `:persist/session-journal-io` executor does not append to in-memory journal state
  - added focused proof that successful flush execution is the path that marks `:flushed? true`
  - added focused proof that failed flush does not set `:flushed? true`
  - updated append-path tests toward handler-owned append + explicit IO execution behavior
  - kept regression proof for fork/resume persisted-session invariants green through focused verification
  - verified replay behavior after nested dispatch compatibility shims via focused replay tests

- [x] Add direct lower-component unit proofs for pure persistence decision logic
  - append/no-op/flush request shaping in isolation
  - direct `mark-flushed-root-update` unit test in lower component

- [x] Re-review final code shape for compatibility helper retention and possible follow-on cleanup
  - removed obsolete compatibility helpers once production/path references were gone
  - migrated remaining lower-component tests onto canonical lower helpers and explicit IO-request execution instead of wrapper APIs

- [x] Refresh `psi.session-persistence.core` namespace docstring after the extraction
  - described the current post-task ownership more precisely
  - removed outdated wording that implied broader ctx-based append/persist execution ownership

- [x] Remove the empty `;;; Flush + persist semantics` section stub from `psi.session-persistence.core`
  - kept file sections aligned with the remaining code shape after wrapper removal

- [x] Remove the `:persist/journal-append-*` shim effects in a follow-on cleanup
  - audited remaining callers of `:persist/journal-append-entry`
  - audited remaining callers of `:persist/journal-append-message-entry`
  - audited remaining callers of `:persist/journal-append-model-entry`
  - audited remaining callers of `:persist/journal-append-thinking-level-entry`
  - audited remaining callers of `:persist/journal-append-session-info-entry`
  - routed remaining production callers directly through canonical handler/effect seams
  - removed the shim effect variants from execution + schema surfaces

- [ ] Extract a small helper for the repeated append-journal dispatch effect envelope
  - centralize the canonical `:runtime/dispatch-event` -> `:session/append-journal-entry` effect-map construction
  - update current call sites in prompt lifecycle, session mutations, and prompt recording to use the shared helper
  - keep the helper small and local to the owning agent-session boundary so it reduces repetition without introducing unnecessary abstraction

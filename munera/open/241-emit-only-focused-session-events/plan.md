# Plan — 241 Emit RPC events only for the focused session

## Approach

Home the focus gate inside `emit-event!` (`psi.rpc.events`), layered after the
existing `event-topics` membership and `topic-subscribed?` checks, per the
resolved design decision.

Gate logic:

- An event is **session-scoped** iff its emitted payload (`data`) contains a
  `:session-id` key (structural rule — no hand-curated event set).
- A session-scoped event emits iff `(:session-id data)` equals the connection's
  **effective focus**.
- Effective focus = `(or (rpc.state/focus-session-id state) default-session-id)`.
- Non-session-scoped events (`context/updated`, `ui/*`, `command-result`,
  `error`, handshake) are untouched.

### Key decisions

1. **Resolving the nil-focus default without threading `ctx`.**
   The design says nil focus falls back to `default-session-id-in` (a `ctx`
   function), but `emit-event!` has no `ctx` and threading it through every
   call site (session.clj, ops.clj, emit.clj, projections.clj, external event
   loop) would be invasive. Instead: `make-rpc-state` already receives the
   connection's initial/default `session-id` (`runtime.clj`), which is exactly
   `default-session-id-in`'s value at connection setup. Store it in connection
   state as `:default-session-id` (immutable after construction) and read it in
   the gate. `initialize-transport-state!` must preserve it (merge default nil,
   keep existing). This keeps `emit-event!`'s signature unchanged and emission
   a pure function of connection state + event.

   *Frozen-vs-live reconciliation (design alignment).* The design's nil-focus
   fallback is defined as `default-session-id-in` (a *live* first-listed-session
   function of `ctx`); this plan stores a *frozen* construction-time snapshot.
   These agree because:
   - **(a) Equivalence at setup.** The construction `session-id` (from
     `session-ctx-factory`, `runtime.clj`) is the same initial session that
     `default-session-id-in` returns as the first-listed session at connection
     setup, so `:default-session-id` == `default-session-id-in` at that instant.
     Slice 1 adds an explicit test asserting this equivalence rather than
     leaving it as an unverified assertion.
   - **(b) Divergence over the connection lifetime is out of scope by
     invariant.** The nil-focus branch only governs emission *before any
     explicit focus is set*, and `focus-session-id` is only ever advanced to
     explicit sessions (never cleared back to `nil`). Once focus is explicit,
     the frozen default no longer participates in the gate, so later session-set
     changes (a session closing, a new session inserted first) that would move
     the live `default-session-id-in` cannot make the frozen value wrong for its
     window of relevance. The frozen snapshot is therefore intentionally
     stable-but-authoritative, not stale. See design Constraints ("Frozen vs
     live default").

2. **No changes at emitter call sites.** `make-request-emitter`, the progress
   loop, the external event loop, and `ops.clj` all funnel through
   `emit-event!`; the gate covers them all. The rehydration bundle
   (`emit-navigation-result!`) is safe because `set-focus-session-id!` runs
   before the bundle emits — verify this ordering, do not change it.

3. **Suppression is silent.** A gated event is simply not emitted (like an
   unsubscribed topic today); no error frame, no counter. Payload-validation
   errors only apply to events that pass the gate.

## Risks

- **Emacs client assumptions** (design's open question): client code might rely
  on background-session deltas to keep hidden buffers live. Mitigation: audit
  emacs-ui handlers for background `:session-id` handling; rely on the
  refocus rehydration path (`session/resumed` + `session/rehydrated` +
  `session/updated` + `footer/updated` + `context/updated`) for lossless
  reconstruction. If a real dependency is found, surface it — do not widen the
  gate ad hoc.
- **Payloads with `:session-id` that are not conceptually session-scoped.**
  Audit `required-event-payload-keys` + actual emission sites:
  `context/updated` payload is `#{:active-session-id :sessions}` — no
  `:session-id` — so it passes structurally. Confirm no `ui/*` /
  `command-result` / `error` payload carries `:session-id`; if one does,
  that's a design conflict to raise, not to special-case silently.
- **State-shape drift**: `initialize-transport-state!` re-merges connection
  defaults; forgetting `:default-session-id` there would silently reintroduce
  a nil effective focus. Covered by a dedicated test.
- **Ordering regression** in `emit-navigation-result!` (focus set after
  emission) would suppress the rehydration bundle. Covered by acceptance
  test (d).

## Slice order

1. **State: default session id** — extend `psi.rpc.state` with
   `:default-session-id` (set in `make-rpc-state`, preserved by
   `initialize-transport-state!`, reader fn) + tests.
2. **Gate: focus filtering in `emit-event!`** — add the structural
   session-scoped check and effective-focus comparison + unit tests
   (suppressed non-focused, emitted focused, nil-focus default behaviour,
   cross-session events unaffected).
3. **Integration: navigation/rehydration ordering** — tests proving focus
   switch rehydrates the newly focused session and the bundle is never
   suppressed; cross-session `context/updated` emits while another session is
   active.
4. **Verification & docs** — audit emacs-ui for background-delta assumptions,
   full test run, CHANGELOG entry, doc sync if `doc/architecture.md` mentions
   delivery gating.

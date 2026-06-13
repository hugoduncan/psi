# 228 — Plan

Fix the `:handler-entry-state-mismatch` abort that fires when an `:invoke` step
carries **both** an `:operation` and an invoke `:judge`, by giving the judge's
deterministic-operation entry a **distinct phase-key namespace** from the step's
operation entry (design fix shape **(a)**). Removes the structural coupling
whereby two operations on one attempt share the `:operation-*-state` keys.

## Approach

### Mechanism (decided)

Thread an explicit **operation role** through the deterministic-operation
invocation map and derive the entry phase-key namespace from it:

- `:operation-role` invocation key. Absent / `:step` → the **existing**
  `:operation-*` keys (back-compat for every single-operation step). `:judge`
  → a `judge-`prefixed key namespace.
- Phase-key derivation lives in `deterministic-operation-runtime/core` via a
  single helper, e.g.

  ```clojure
  (defn- role-phase-key [role base-key]
    (if (= role :judge)
      (keyword (str "judge-" (name base-key)))
      base-key))
  ```

  applied uniformly to **every** phase/timestamp/count key used by the five
  transition helpers (`reserve` / `commit-start` / `begin-call` / `commit-call`
  / `prepare-handler-entry` / `enter-handler`), so the judge operation drives
  `:judge-operation-start-state` / `:judge-operation-call-state` /
  `:judge-operation-handler-entry-state` (plus matching `*-at` / `*-count`),
  never touching the residual `:entered` left by the step `:operation`.

- The role is read once from the invocation (`(:operation-role invocation)`) and
  woven into the `phase-opts` maps the helpers pass to
  `ordinary-entry/transition-latest-attempt!`. `ordinary-entry` itself stays
  **unchanged** — it is already fully parameterized on `:phase-key` /
  `:timestamp-key` / `:count-key` / `:required-phases` / `:ok-states`.

- The 225 cancellation primitives (`cancellation-entry/with-run-read-lock`,
  `stop-signal/workflow-stop-signal`, `run-linear-entry-phases!`) stay
  unchanged: only the key namespace is parameterized, so each operation keeps
  its own cooperative stop checkpoints and read-lock entry.

### Call-site threading

- `agent-session/workflow_judge/execute-invoke-judge!` — add
  `:operation-role :judge` to the invocation map passed to
  `invoke-operation-in`.
- `workflow-runtime/.../step_execution/invoke-step-runtime-result` — the step
  `:operation` keeps the default role (no `:operation-role`, i.e. `:step`); make
  this explicit only if it improves clarity.
- `invoke-operation-in` passes the invocation through unchanged, so no registry
  change is needed.

### Why this over the alternatives

Per `λ fix(bug). cause(structural) → redesign > patch`, namespacing removes the
coupling at its source (two operations sharing one key set), exactly mirroring
the existing `:turn-*-state` vs `:operation-*-state` separation that already
makes session-step + invoke-judge safe. Rejected: (b) a fresh attempt for the
judge (changes the attempt model); (c) dropping `:entered` from `prepare`'s
`:ok-states` (papers over the coupling and weakens the 225 idempotency guard).

## Risks

- **Key-derivation completeness.** Every `:operation-*` key (state, `*-at`
  timestamps, `*-count`) must be routed through the role helper; a missed key
  reintroduces cross-operation aliasing. Mitigate by deriving all keys from one
  helper and asserting judge-namespaced keys in the characterization test.
- **Cancellation regression.** The judge operation must still observe a stop
  signal before/within entry. Mitigate by adding role-aware cancellation
  coverage and keeping the existing 225 cancellation tests green.
- **Hidden assumptions on `:operation-*` key names.** Other code or tests may
  read `:operation-handler-entry-state` directly. Grep for readers before/after;
  the default path keeps those keys, so only judge-scoped reads are new.
- **Invocation schema.** Confirm no malli schema rejects the additive
  `:operation-role` invocation key (invocation map is currently open).

## Slice order

1. **Characterization test (red).** Focused
   `deterministic-operation-runtime` test: drive `invoke-operation` twice
   against one attempt — step op (default role) then judge op
   (`:operation-role :judge`) — asserting the judge op returns `:status :ok`
   and lands `:judge-operation-handler-entry-state :entered` while the step op's
   `:operation-handler-entry-state` stays `:entered`. Fails pre-fix with
   `:handler-entry-state-mismatch`.
2. **Runtime phase-key namespacing.** Add the role helper and route all phase
   helpers' keys through it in `deterministic-operation-runtime/core`; default
   role unchanged. Slice 1 test goes green for the runtime in isolation.
3. **Judge call-site role.** Pass `:operation-role :judge` from
   `execute-invoke-judge!` (and make the step-operation default explicit if it
   aids clarity). End-to-end: an invoke step with operation + invoke judge runs
   both operations and routes on the judge outcome.
4. **Cancellation regression coverage.** Add role-aware tests proving a real
   stop before/within either operation still yields a clean `:workflow-stopped`
   terminal; confirm existing 225 cancellation tests
   (`deterministic-operation-runtime`, `workflow-coordination`) stay green.
5. **Workflow-level verification + close-out.** Verify `review-task-design`
   reaches `clarity-status` REPEAT/DONE routing without the abort (runtime/
   definition-level coverage); add a CHANGELOG `Fixed` entry; run clj-kondo and
   the relevant Scry suites.

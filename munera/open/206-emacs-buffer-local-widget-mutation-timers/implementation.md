# Implementation Notes

## Architecture-fit review (ψ)

Reviewed design.md for fit with AGENTS.md, doc/architecture.md (`emacs-ui` owns
"local widget/view state"), VSM adapter boundary.

Strong fit overall:
- Moving mutation timers into buffer-local `psi-emacs-state` keeps frontend-local
  state in the adapter's per-buffer state — matches `emacs-ui owns local
  widget/view state`.
- Adopting the `projection-notification-timers` precedent satisfies
  `one_way` ∧ `consistent(code)`.
- "No module-global mutable timer state remains" realizes
  `shape: unreachable > forbidden` for orphaned timers.
- Removing the global hash increases cross-buffer `orthogonality` (robust).
- Scope correctly stays inside the adapter; excludes dispatch/RPC/backend (VSM
  layering respected).

Actionable misfit (1):
- The design's "resolve target buffer/state explicitly" requirement names only
  the *timeout* callback. The `--dispatch-mutation` RPC **response** callback
  (psi-widget-projection.el:354) also reads the dynamic `psi-emacs--state` and
  calls `--cancel-mutation-timer tkey`. Post-change the response path must
  target the originating buffer's buffer-local store too, mirroring the
  notification precedent's captured `buffer`/`state` + `buffer-live-p` guard
  (psi-projection.el:415). Otherwise consistency/orthogonality goals are only
  partially met and a stale-buffer response could touch the wrong store.

## Architecture-fit follow-up — resolved (ψ)

Applied the response-callback buffer-targeting requirement into design.md:
- Scope: explicit buffer-targeting now required for **both** callbacks (timeout
  watchdog + `--dispatch-mutation` response), each capturing originating
  `buffer`/`state` + `buffer-live-p` guard, mirroring
  `psi-emacs--schedule-notification-dismiss`.
- Constraints: neither callback may dereference dynamic `psi-emacs--state` to
  locate the store; both operate against the captured buffer/state.
- Acceptance: added a criterion for the response callback targeting the
  originating buffer (no cross-buffer mutation, dead-buffer no-op).
- Scope/Tests: added (d) cross-buffer-current and (e) dead-buffer no-op cases for
  both response and timeout paths.
- Why: noted both callbacks share the same buffer-targeting hazard.

Verified against precedent at psi-projection.el:410
(`psi-emacs--schedule-notification-dismiss`): captures `(current-buffer)` +
`state`, guards `(buffer-live-p buffer)`, runs in `with-current-buffer`.
No code changes (design-only task).

## Ambiguity review (ψ)

Grounded design against real code: `psi-widget-projection.el` arm
(:300), cancel (:310), timeout (:317), dispatch-mutation response (:336/:354);
struct `psi-globals.el:49`; init `psi-lifecycle.el:32`; teardown `:269`;
transcript reset `:371`; notification precedent `psi-projection.el:368-423`.

Confirmed (resolves a potential concern, NOT actionable): `psi-emacs--state`
is `defvar-local` (psi-globals.el:111), so a callback's `with-current-buffer
buffer` rebinds it and the downstream `--get-lstate` / `--call-error-handler`
/ `--upsert-projection-block` reads then naturally target the originating
buffer's state — the design's captured-buffer approach is self-consistent for
the *post-cancel* body.

One NEW actionable ambiguity (B1, design-steps.md): the design mandates
captured `buffer`/`state` + `buffer-live-p` for the two **callbacks**, but is
silent on the helper signatures and the **arm path**. Today
`--cancel-mutation-timer` takes only `tkey` and reads the global hash, and it
is called from THREE contexts: (1) inside `--arm-mutation-timer` as
pre-cancel-before-arm (psi-widget-projection.el:303), (2) the response callback
(:356), (3) the timeout callback (:321 via `remhash`). Post-change the cancel
helper must locate a buffer-local store, so its new signature (e.g.
`(state tkey)` or `(buffer tkey)`) is unspecified. The arm path runs while the
originating buffer is current (dynamic `psi-emacs--state` is valid), whereas the
callbacks must use captured state — the design's "neither callback may
dereference `psi-emacs--state`" rule does NOT say whether `--arm` and its
inline pre-cancel may use dynamic state or must also thread captured state.
A shared cancel helper called from both dynamic-current and captured-buffer
contexts with an unstated store-resolution rule is an actionable mechanism
ambiguity (`one_way → singular(solution)`).

Not raised (plan-level / non-actionable): exact new struct field name + clear
helper name (shape fixed by the `projection-notification-timers` mirror;
naming is a plan concern); whether the widget clear helper also resets widget
lstates/data (design clearly scopes it to "cancel and clear timers" only).

## Ambiguity follow-up — B1 resolved (ψ)

Resolved the helper-signature / arm-path store-resolution ambiguity by adopting
the notification precedent's explicit-`state` pattern. Grounded in
`psi-emacs--cancel-notification-timer (state notification-id)`
(psi-projection.el:368), which resolves the store from the *passed* `state`,
never from dynamic `psi-emacs--state`.

Single store-resolution rule applied across all three cancel/arm call sites:
the store is always resolved from an explicitly passed `state` argument; the
helpers never dereference dynamic `psi-emacs--state`. Sites differ only in which
`state` they pass:
- arm + inline pre-cancel (psi-widget-projection.el:300/303): pass the
  then-current dynamic `psi-emacs--state` (captured at the synchronous call
  boundary while the originating buffer is current).
- response callback (:354) and timeout callback (:317): pass the captured
  originating `state` after a `buffer-live-p` guard, per
  `psi-emacs--schedule-notification-dismiss`.

design.md updates:
- Scope: pinned helper signatures `--cancel-mutation-timer (state tkey)` and
  `--arm-mutation-timer (state ext-id widget-id node-key timeout-ms)`.
- Constraints: replaced the callback-only "neither callback may dereference
  `psi-emacs--state`" rule with a single store-resolution rule covering all
  three sites (arm, inline pre-cancel, both callbacks).
- Acceptance: added a criterion that the helpers resolve the store solely from
  the passed `state` and no site reads dynamic `psi-emacs--state` for store
  resolution.

This makes the cancel helper safely shared between dynamic-current and
captured-buffer contexts (`one_way → singular(solution)`). No code changes
(design-only task).

## Inconsistency review (ψ)

Re-read design.md against real code: arm/cancel/timeout/dispatch-mutation
(`psi-widget-projection.el:300/303/310/317/336/349/351-357`), struct
(`psi-globals.el:49-72`), `defvar-local psi-emacs--state` (`:111`), init
(`psi-lifecycle.el:32-57`), teardown (`:269`), reset-transcript (`:371`),
notification precedent (`psi-projection.el:368/379/410`). Line refs, the
global-hash claim, the cleared-on-teardown/reset notification precedent, and the
`projection-notification-timers` shape all verified accurate.

One NEW actionable inconsistency (I1, design-steps.md): the **pinned arm
signature** `--arm-mutation-timer (state ext-id widget-id node-key timeout-ms)`
(Scope/Constraints) takes `state` but **no `buffer`**, yet Scope/Constraints/AC
require the *timeout* callback to "capture the originating `buffer`/`state` at
arm time," be a no-op for a dead buffer via `buffer-live-p`, and (per the cited
`psi-emacs--schedule-notification-dismiss` precedent) run inside
`with-current-buffer buffer`. Arm is the only site that schedules
`--on-mutation-timeout` via `run-at-time`, but the pinned signature provides no
`buffer` to thread, and the design never says (a) that arm threads captured
`buffer` (and `state`) into the scheduled callback's args, nor (b) that
`--on-mutation-timeout` gains `buffer`/`state` params. The notification
precedent it mirrors captures BOTH `(current-buffer)` and `state` into the
scheduled lambda; the design captures only `state` for arm. As written the
"captured buffer/state at arm time" + dead-buffer-no-op requirement is
unsatisfiable for the timeout path. (B1 resolved synchronous store-resolution
only; the deferred-callback buffer-capture/threading gap is distinct and
unresolved.) Resolve by extending the arm signature/scheduling to capture and
thread the originating `buffer` (mirroring the precedent's `(current-buffer)` +
`state` capture) and specifying `--on-mutation-timeout`'s post-change params.

## Inconsistency follow-up — I1 resolved (ψ)

Resolved the pinned-arm-signature ↔ timeout-callback-buffer-capture
inconsistency by threading the originating `buffer` (alongside `state`) into the
scheduled callback at arm time, exactly mirroring
`psi-emacs--schedule-notification-dismiss` (psi-projection.el:410), which
captures `(current-buffer)` + `state` and threads both into the `run-at-time`
lambda args, then guards `(buffer-live-p buffer)` inside `with-current-buffer`.

Key insight: arm needs no extra `buffer` *parameter* — it captures
`(current-buffer)` locally at the (synchronous, originating-buffer-current) arm
call site, just like the precedent. The pinned `--arm-mutation-timer (state
ext-id widget-id node-key timeout-ms)` signature is preserved; only the
scheduled-callback args grow to carry `buffer`/`state`.

design.md updates:
- Scope: arm now documented as capturing `(current-buffer)` + `state` and
  threading both into the `run-at-time` callback args; added the post-change
  timeout-callback signature `--on-mutation-timeout (buffer state ext-id
  widget-id node-key timeout-ms)` with its `buffer-live-p`/`with-current-buffer`
  body.
- Constraints: made the buffer/state→timeout-callback threading mechanism
  explicit (arm threads them via `run-at-time` args; response callback closes
  over dispatch-time `buffer`/`state` synchronously).
- Acceptance: added a criterion that arm captures+threads `buffer`/`state`, and
  sharpened the timeout-callback criterion to name the leading `buffer`/`state`
  params, the `buffer-live-p` no-op, and the `with-current-buffer` body.

This makes the "capture originating buffer/state at arm time + dead-buffer
no-op" requirement satisfiable for the timeout path (previously unsatisfiable:
no `buffer` was threaded into the scheduled callback). Arm signature unchanged;
inconsistency removed. No code changes (design-only task).

## Plan ambiguity review (ψ)

Grounded plan.md/steps.md against real `emacs-ui` code: arm (`psi-widget-projection.el:300`),
inline pre-cancel call (`:303`), cancel def (`:310`), timeout def (`:317`),
dispatch-mutation (`:336`), arm call site (`:349`), response callback
(`:353`–`:362`); struct (`psi-globals.el:49`, field `projection-notification-timers`
present, `defvar-local psi-emacs--state` `:111`); init (`psi-lifecycle.el:32`,
notification-timers init `:57`); teardown (`:269`, notif clear `:295`); transcript
reset (`:371`, notif clear `:392`); defvar (`:73`); notification precedent
`psi-emacs--clear-notification-lifecycle` (`psi-projection.el:379`, outer
`(when state ...)` + inner `(when (hash-table-p timers) ...)`); existing mutation
tests `psi-widget-projection-test.el:500/511/525/542/551/565`.

Most plan/steps mechanics are accurate and slice ordering is sound. Three NEW
actionable plan/steps ambiguities (P1–P3 in steps.md):

- **P1 — Sixth existing test is unassigned.** Slice 2's "update existing tests"
  set names five tests but omits `pwpt-dispatch-mutation-cancels-timer-on-response`
  (`:565`), which `let`-binds the global defvar and exercises the response-cancel
  path. Slice 5 deletes the defvar and says "remove leftover let-binds", so this
  test MUST be migrated to drive the buffer-local store via state — but no slice
  owns its rewrite (Slice 2 signature change? Slice 3 response targeting? Slice 5
  cleanup?). Unassigned migration → ambiguous ownership.

- **P2 — `--clear-mutation-timers` null-`state` guard unspecified.** Steps Slice 1
  pins the guard as only `(hash-table-p timers)`, omitting the precedent's outer
  `(when state ...)`. Slice 4 calls `(psi-widget-projection--clear-mutation-timers
  psi-emacs--state)` directly with NO `(when psi-emacs--state)` wrapper, unlike
  sibling teardown calls — and `(psi-emacs-state-projection-mutation-timers nil)`
  errors. The mirrored `clear-notification-lifecycle` wraps its whole body in
  `(when state ...)`. Whether the new helper must null-guard `state` is
  unspecified → potential teardown error on a nil-state buffer.

- **P3 — `pwpt-on-mutation-timeout-noop-when-no-state` intent remapping
  unspecified.** This existing test sets `psi-emacs--state nil` and asserts the
  timeout is a harmless no-op, relying on the *current* `(when psi-emacs--state)`
  guard. Post-change the timeout no-ops on `(buffer-live-p buffer)`, NOT on
  dynamic `psi-emacs--state`. Slice 2 lists this test for "update to new
  signatures" but does not say what `buffer`/`state` it should pass nor what it
  asserts — and Slice 2 also separately ADDS a dead-buffer no-op test, so it is
  unspecified whether this test is repurposed (making the new one redundant),
  retained as a distinct nil-state guard, or deleted.

Not raised (non-actionable / design-resolved): stale-vs-actual line numbers in
plan Risks ("cancels at :303/:356, timeout scheduled at :306") are close enough
to navigate and the def/call-site distinction is recoverable by grep (plan
Slice-5 step already mandates a pre-delete `git grep`); the helper/field names
are fixed by the notification mirror (a settled plan decision, not ambiguous);
the response callback's existing `tkey` capture in the `let*` already coexists
with the new `buffer`/`state` capture (Slice 3 wording is adequate).

## Plan ambiguity follow-up — P1–P3 resolved (ψ)

Resolved the three plan/steps ambiguities by deciding dispositions and pinning
them into steps.md slices + plan.md (design-only task; no code yet). All three
grounded in the notification precedent and the real test file
(`psi-widget-projection-test.el:500–582`).

- **P1 (sixth test ownership).** Assigned `pwpt-dispatch-mutation-cancels-timer-on-response`
  (`:565`) to **Slice 3**, which reworks the response callback — the natural
  home. Slice 3's test list now explicitly migrates it from the global-defvar
  `let`-bind to driving the buffer-local store via `state`, so Slice 5's defvar
  deletion + "remove leftover let-binds" finds no orphan. plan.md Decision 7 +
  the "Test harness coupling" risk updated to name it.

- **P2 (null-`state` guard).** Chose: the **helper internally guards
  `(when state ...)`**, matching `psi-emacs--clear-notification-lifecycle`
  (`psi-projection.el:379`) exactly — its whole body is wrapped in
  `(when state ...)` and its call sites (`psi-lifecycle.el:295/392`) pass bare
  `psi-emacs--state` (the nearby `(when psi-emacs--state ...)` blocks at
  `:296/:381` wrap *other* code, not the clear call). So `--clear-mutation-timers`
  owns the guard and Slice 4 call sites pass bare `psi-emacs--state`; a
  nil-state teardown is a harmless no-op rather than erroring on
  `(psi-emacs-state-projection-mutation-timers nil)`. Updated steps Slice 1 +
  plan.md Decision 5.

- **P3 (`noop-when-no-state` remapping).** Chose: **repurpose** the existing
  `pwpt-on-mutation-timeout-noop-when-no-state` (`:542`) into the timeout
  dead-buffer no-op case, and **drop the separately-added duplicate**. Its old
  pivot (`psi-emacs--state nil` + the `(when psi-emacs--state)` guard) no longer
  matches the post-change no-op, which turns on `(buffer-live-p buffer)`. Slice 2
  now renames it (e.g. `…-noop-when-buffer-dead`), drives it via `kill-buffer` +
  invoke with the dead `buffer` + valid `state`, and is the single dead-buffer
  timeout test — eliminating the redundant add (`one_way → singular`). Updated
  steps Slice 2 + plan.md Decision 7.

No code changes (design/plan-only). steps.md P1–P3 checked.

## Plan/steps inconsistency review (ψ)

Re-read plan.md/steps.md/design.md against real `emacs-ui` code and the
notification precedent. Verified accurate: defvar (`psi-widget-projection.el:73`);
arm/cancel/timeout defs (`:300/:310/:317`); inline pre-cancel (`:303`);
dispatch-mutation (`:336`), arm call site (`:349`), response callback (`:354`);
struct field `projection-notification-timers` (`psi-globals.el:72`),
`defvar-local psi-emacs--state` (`:111`); init (`psi-lifecycle.el:57`), teardown
(`:269/:295`), reset (`:371/:392`); precedent
`psi-emacs--cancel-notification-timer (state notification-id)`
(`psi-projection.el:368`), `clear-notification-lifecycle` outer `(when state)`
(`:381`), `schedule-notification-dismiss` `(current-buffer)`+`state`+`buffer-live-p`
(`:410`). The six existing global-defvar-binding tests (`:500/:511/:525/:542/:551/:565`)
are all assigned (P1/P3 dispositions hold); buffer/state thread order
(buffer before state) matches the pinned timeout signature `(buffer state ext-id
…)`; dead-buffer disposition is split correctly (timeout = repurposed Slice 2,
response = new Slice 3) with no duplicate.

One NEW actionable inconsistency (N1, steps.md): the lint tool named for the
final sweep contradicts the per-`.el` lint guidance and the file type this task
edits. Slice 1 (steps.md:19) correctly lints the edited `.el` files with
`clj-paren-repair`/lint; Slice 5 (steps.md:112) says "`clj-kondo`/lint clean".
`clj-kondo` is the Clojure linter and does not lint Emacs Lisp `.el` files —
this task touches only `.el` files (`psi-globals.el`, `psi-lifecycle.el`,
`psi-widget-projection.el`, `psi-widget-projection-test.el`). The two slices
disagree on the linter for the same files, and Slice 5 names a tool inapplicable
to the edited file type. Resolve by replacing Slice 5's `clj-kondo` with the
`.el`-appropriate lint (`clj-paren-repair`/byte-compile/`elisp` lint), matching
Slice 1. Added unchecked follow-up to steps.md.

No code changes (design/plan-only).

## Plan/steps inconsistency follow-up — N1 resolved (ψ)

Resolved the final-sweep lint-tool inconsistency. Slice 5 said "`clj-kondo`/lint
clean", but `clj-kondo` is the Clojure linter and does not lint Emacs Lisp; this
task edits only `.el` files (`psi-globals.el`, `psi-lifecycle.el`,
`psi-widget-projection.el`, `psi-widget-projection-test.el`). Replaced Slice 5's
`clj-kondo` with the `.el`-appropriate lint (`clj-paren-repair`/byte-compile),
matching Slice 1's lint step so both slices name the same linter for the same
files (`consistent(code)` / `one_way`).

No code changes (design/plan-only task). steps.md N1 checked.

## Implementation pass 1 (ψ) — Slices 1–3

- Slice 1 landed as planned: `projection-mutation-timers` struct field
  (`psi-globals.el`), init (`psi-lifecycle.el`), and the
  `--clear-mutation-timers (state)` helper with the precedent's outer
  `(when state ...)` null-guard. Commit `⚒ 206: add buffer-local
  projection-mutation-timers field + clear helper`.
- Slices 2 and 3 implemented together in one commit because both rework the
  single `psi-widget-projection--dispatch-mutation` function (arm call site +
  response callback) — splitting them would have left the function in an
  intermediate non-compiling state (response callback still calling the old
  `--cancel-mutation-timer tkey` arity). Deviation from the slice-per-commit
  plan; same end state, fewer broken intermediate commits.
  - `--cancel-mutation-timer (state tkey)`, `--arm-mutation-timer (state ext-id
    widget-id node-key timeout-ms)` (captures `(current-buffer)` + threads
    `buffer`/`state` into the scheduled callback), `--on-mutation-timeout
    (buffer state ext-id widget-id node-key timeout-ms)` with `buffer-live-p`
    no-op + `with-current-buffer`.
  - `--dispatch-mutation` captures `buffer`/`state` at dispatch; response
    callback guards `buffer-live-p`, cancels/clears against the captured
    `state`/`buffer`.
- Tests: roundtrip/timeout/error-handler/arms/cancels-on-response migrated to
  the buffer-local store via `state`; `…-noop-when-no-state` repurposed to
  `…-noop-when-buffer-dead`. Added `pwpt-dispatch-response-targets-originating-buffer`
  and `pwpt-dispatch-response-noop-when-buffer-dead`. 98/98 green.
- Note: the module-global `psi-widget-projection--mutation-timers` defvar is now
  unreferenced by source (still present; deleted in Slice 5). Tests no longer
  bind it.

## Implementation pass 1 (ψ) — Slices 4–5

- Slice 4: declared `psi-widget-projection--clear-mutation-timers` in
  `psi-lifecycle.el` and called it (bare `psi-emacs--state`) in
  `psi-emacs--teardown-buffer` and `psi-emacs--reset-transcript-state`, beside
  the existing `psi-emacs--clear-notification-lifecycle` calls.
  - Test note: `psi-emacs--teardown-buffer` nils `psi-emacs--state` at the end,
    so the teardown test captures the timer-store hash *before* teardown and
    asserts emptiness against that captured hash (not via the now-nil state).
  - Added: `pwpt-clear-mutation-timers-cancels-and-clears`,
    `pwpt-clear-mutation-timers-noop-when-state-nil`,
    `pwpt-teardown-cancels-in-flight-mutation-timers`,
    `pwpt-reset-transcript-clears-mutation-timers`,
    `pwpt-two-buffers-do-not-share-mutation-timer-state`.
- Slice 5: deleted the module-global `psi-widget-projection--mutation-timers`
  defvar. `git grep` over `components/` confirms zero source references; tests
  no longer bind it. `unreachable > forbidden` for orphaned/cross-buffer timers
  achieved.
- Verification: full `bb emacs:check` (byte-compile + 337 ERT tests) green;
  byte-compile clean; `.el` reloaded into the running Emacs.

## Implementation review (ψ)

Reviewed code+tests against design/plan acceptance criteria, grounded on real
source (`psi-widget-projection.el:73/299-396`, `psi-globals.el:72-73`,
`psi-lifecycle.el:28/297-298/395-396`) and the notification precedent
(`psi-projection.el:368/379/410`). Verdict: implementation is complete,
high-quality, and faithful to the precedent. All acceptance criteria met;
module-global defvar gone (`git grep` clean); 316 ERT tests pass, byte-compile
clean. Tests assert state (store contents, in-flight lstate) and cover all
required cases (killed-buffer cancel, two-buffer independence, cross-buffer
response/timeout targeting, dead-buffer no-op, transcript reset). `cl-letf` over
`run-at-time`/`cancel-timer`/`timerp`/`send-request-function` substitutes
infrastructure/timer primitives only — consistent with the suite's established
idiom and the testing-without-mocks "infrastructure → controllable" rule.

Deviations from plan (both documented, both acceptable):
- Slices 2+3 landed in one commit (the single `--dispatch-mutation` function
  spans both — splitting would leave a non-compiling intermediate). Same end
  state.
- Tests went into a NEW file `test/psi-widget-projection-timers-test.el` rather
  than editing `psi-widget-projection-test.el` (file-length limit). Steps Slice
  2/3/5 referenced line numbers in the old file; harmless since the migration
  achieved the same coverage and no orphaned global `let`-binds remain.

One MINOR actionable consistency gap (R1): `--on-mutation-timeout` guards only
`(buffer-live-p buffer)`, but the precedent it explicitly mirrors
(`psi-emacs--schedule-notification-dismiss`'s scheduled lambda,
`psi-projection.el:415`) guards `(and (buffer-live-p buffer) st)` — i.e. also
state-non-nil. Benign in practice (the captured `state` is non-nil whenever the
buffer was live at arm, and the inner `--cancel-mutation-timer`/`--get-lstate`
paths null-guard), so this is not a correctness defect. But the design/plan
repeatedly invoke "mirroring `schedule-notification-dismiss`" as the consistency
rule, and the guard differs from that precedent. Either add the `state` conjunct
to match, or note the intentional divergence — so the stated "mirror the
precedent exactly" claim holds.

## Implementation review follow-up — R1 resolved (ψ)

Chose disposition (a): added the `state`-non-nil conjunct so
`--on-mutation-timeout`'s guard `(and (buffer-live-p buffer) state)` matches the
precedent the design repeatedly invokes,
`psi-emacs--schedule-notification-dismiss`'s scheduled lambda guard
`(and (buffer-live-p buffer) st)` (`psi-projection.el:415`). Picked match-the-
precedent over record-divergence because the design/plan name
`schedule-notification-dismiss` as the *consistency rule* (`one_way` /
`consistent(code)`) — matching it is the singular obvious path and adds a cheap
nil-state safety guard; recording a divergence would weaken the "mirror exactly"
claim it stands on.

- Code: `psi-widget-projection--on-mutation-timeout` guard now
  `(when (and (buffer-live-p buffer) state) …)`; docstring notes the mirrored
  guard.
- Tests: added `pwpt-on-mutation-timeout-noop-when-state-nil`
  (`psi-widget-projection-timers-test.el`) — live buffer + nil `state` is a
  harmless no-op (no error, no mutation), covering the new conjunct alongside
  the existing dead-buffer no-op.
- Verification: `bb emacs:check` green (338/338, was 337; byte-compile clean);
  `.el` reloaded. No design.md change (code now matches the precedent the design
  already specifies).

## Implementation review pass 2 (ψ)

Independent re-review against the task-implementation-review skill
(code↔design fit, architecture fit, new-pattern/abstraction/perf flags),
grounded on current source and a full test run.

Verified:
- All design.md acceptance criteria met. Module-global
  `psi-widget-projection--mutation-timers` gone (`git grep` clean, source +
  tests). Struct field + init present (`psi-globals.el:73`,
  `psi-lifecycle.el:59`).
- Single explicit-`state` store-resolution rule holds: `--cancel-mutation-timer
  (state tkey)` / `--arm-mutation-timer (state …)` resolve the store solely
  from the passed `state`; the only `psi-emacs--state` token in lines 310–335
  is a docstring reference, not a read. Arm captures `(current-buffer)` +
  threads `buffer`/`state` into the `run-at-time` args
  (`psi-widget-projection.el:330-335`).
- `--on-mutation-timeout (buffer state …)` guard `(and (buffer-live-p buffer)
  state)` matches the cited precedent
  `psi-emacs--schedule-notification-dismiss` (`psi-projection.el:415`); R1 is
  correctly resolved.
- Response callback closes over dispatch-time `buffer`/`state`, guards
  `buffer-live-p`, cancels/clears inside `with-current-buffer`
  (`psi-widget-projection.el:380-389`). Confirmed the post-cancel lstate path
  (`--get-lstate`/`--set-lstate`, which read dynamic `psi-emacs--state`,
  `:138/:147`) is correct because `defvar-local psi-emacs--state` is rebound by
  `with-current-buffer buffer` — exactly the ambiguity-review insight.
- `--clear-mutation-timers (state)` mirrors `--clear-notification-lifecycle`
  (`psi-projection.el:379`): outer `(when state)`, inner `(hash-table-p …)`,
  maphash `cancel-timer`, `clrhash`. Correctly omits the notification-only
  state resets (scoped to timers per design). Wired into teardown
  (`psi-lifecycle.el:298`) and transcript reset (`:396`) with bare
  `psi-emacs--state`, declared at `:28`.
- Tests assert state (store contents, in-flight lstate, cancel calls) and cover
  every required case: arm/cancel roundtrip, timeout clears-in-flight +
  error-handler, dispatch arms, response cancels, cross-buffer-current response
  targeting, dead-buffer + nil-state no-ops, teardown cancel, transcript-reset
  clear, two-buffer independence. `cl-letf` substitutes only timer/RPC
  infrastructure primitives — consistent with the suite idiom and
  testing-without-mocks "infrastructure → controllable".
- Changelog entry present and user-facing (`CHANGELOG.md:21`). File lengths
  under limit (`psi-widget-projection.el` 529; timers-test 385).
- `bb emacs:check`: 338/338 ERT pass, byte-compile clean.

No new patterns warranting reuse-flags, no unnecessary abstractions, no
structural/performance concerns. No new actionable issues. All prior
follow-ups (B1, I1, P1–P3, N1, R1) confirmed resolved in code+tests.

Verdict: REVIEW_COMPLETE.

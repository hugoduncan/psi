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

## Test review (ψ)

Applied task-test-review skill (well-formed ∧ behaviour-coverage ∧
infra-deps injectable/¬mock). Tests live in
`test/psi-widget-projection-timers-test.el` (338/338 ERT green). Reviewed
against design.md acceptance + Scope test list (a)–(e), grounded on real
source (`psi-widget-projection.el:310/323/337/361`).

Strong overall: tests assert state (store contents, in-flight lstate,
cancel/timerp calls) not interactions; `cl-letf` substitutes only
infrastructure primitives (`run-at-time`, `cancel-timer`, `timerp`,
`send-request-function`, `upsert-projection-block`) — consistent with the
testing-without-mocks "infrastructure → controllable" rule, no logic mocked.
Covered: arm/cancel roundtrip, timeout clears-in-flight + error-handler,
dispatch arms, response cancels, dead-buffer + nil-state timeout no-ops,
response cross-buffer targeting, response dead-buffer no-op, teardown cancel,
transcript-reset clear, two-buffer independence, clear-mutation-timers
cancel+clear and nil-state no-op.

Two actionable test-coverage gaps:

- **T1 (minor) — arm's buffer/state threading is not directly asserted.**
  design.md AC: "arm captures `(current-buffer)` + `state` and threads both
  into the scheduled `run-at-time` callback args, mirroring
  `psi-emacs--schedule-notification-dismiss`". `pwpt-arm-cancel-mutation-timer-roundtrip`
  stubs `run-at-time` as `(apply #'list fn args)` but never inspects the
  captured args to assert `buffer`/`state` precede `ext-id widget-id node-key
  timeout-ms` in the threaded call. The threading mechanism — the very thing
  that makes the dead-buffer/cross-buffer timeout behaviour reachable — is
  only exercised indirectly. A direct assertion on the captured scheduled-arg
  shape would lock the contract.

- **T3 (actionable) — no positive cross-buffer-current test for the TIMEOUT
  path.** design.md Scope (d): "a response (**and a timeout**) arriving while
  a *different* buffer is current cancels/clears the originating buffer's
  store, not the current buffer's." There IS such a positive cross-buffer test
  for the response path (`pwpt-dispatch-response-targets-originating-buffer`)
  but NOT for the timeout path. The timeout path has only dead-buffer
  (`…-noop-when-buffer-dead`) and nil-state (`…-noop-when-state-nil`) no-op
  cases plus same-buffer-current clears-in-flight/error-handler cases — the
  positive "different buffer current, originating store cleared, other store
  untouched" case is absent. The skill's `∀b ∈ behaviour(design). ∃t.
  covers(t,b)` is unmet for this design-named timeout behaviour, even though
  the symmetric response case is covered.

## Test review follow-up — T1, T3 resolved (ψ)

Executed the two test-review follow-up items. Both added to
`test/psi-widget-projection-timers-test.el`; `bb emacs:check` green (340/340,
was 338) and byte-compile clean.

- **T1 — direct arm threading assertion.** Added dedicated
  `pwpt-arm-threads-buffer-and-state-into-scheduled-callback` rather than
  overloading `…-roundtrip` (whose `run-at-time` stub returns `(apply #'list fn
  args)` and inspects nothing). The new test stubs `run-at-time` to capture
  `fn` + `args`, then asserts (a) the scheduled fn is
  `#'psi-widget-projection--on-mutation-timeout`, and (b) the threaded args are
  exactly `(list (current-buffer) origin-state "ext" "w1" "b1" 5000)` — i.e.
  `buffer`/`state` lead, ahead of `ext-id widget-id node-key timeout-ms`,
  matching the timeout-callback arglist. Locks the AC "arm captures+threads
  buffer/state" directly, not only indirectly via dead/cross-buffer behaviour.

- **T3 — positive cross-buffer-current timeout test.** Added
  `pwpt-on-mutation-timeout-targets-originating-buffer`, mirroring
  `pwpt-dispatch-response-targets-originating-buffer` for the timeout path
  (design.md Scope (d): "a response (and a timeout)"). Sets up the origin
  buffer with an armed timer + in-flight lstate and the other buffer with an
  independent same-key store, then invokes `--on-mutation-timeout` against the
  origin `buffer`/`state` while the OTHER buffer is current. Asserts the origin
  store entry + in-flight lstate are cleared and the other buffer's store
  (`'sentinel`) is untouched. Closes the design-named timeout cross-buffer
  behaviour gap that previously had only dead-buffer/nil-state no-op coverage.

No source/design changes — both are pure test-coverage additions for already
-implemented behaviour. `.el` reloaded into the running Emacs.

## Test review pass 2 (ψ)

Independent re-application of task-test-review (well_formed ∧ ∀b∈behaviour(design).
∃t.covers ∧ infra_deps injectable/¬mock). 340/340 ERT green, byte-compile clean.
Grounded on `psi-widget-projection.el:310/323/338/375` and the test file.

Confirmed strong: tests assert state (store contents, in-flight lstate,
cancel/timerp calls), not interactions; `cl-letf` substitutes only timer/RPC/
render infrastructure primitives (`run-at-time`, `cancel-timer`, `timerp`,
`send-request-function`, `upsert-projection-block`) — testing-without-mocks
"infrastructure → controllable", no logic mocked. Behaviour coverage verified for
design Scope (a)–(e) + acceptance: teardown cancel (a), two-buffer independence
(b), arm/cancel/response/timeout preserved (c), response+timeout cross-buffer
targeting (d), response+timeout dead-buffer no-op (e), transcript-reset clear,
arm buffer/state threading (T1), nil-state timeout no-op (R1).

One NEW minor actionable coverage asymmetry (T4): the response cross-buffer test
`pwpt-dispatch-response-targets-originating-buffer` (`:206`) asserts only the
origin *timer store* is cleared (and the other buffer's untouched); it neither
sets up nor asserts the response callback's **in-flight lstate** clearing on the
ORIGIN buffer. The response callback (`psi-widget-projection.el:380-389`) clears
both the timer store AND the in-flight lstate inside `with-current-buffer buffer`,
and design.md Scope (d)/acceptance name the response path clearing the originating
buffer's "buffer-local timer store **and lstate**". The symmetric *timeout*
cross-buffer test (`pwpt-on-mutation-timeout-targets-originating-buffer`, `:266`)
DOES assert both store + `in-flight-p` lstate on the origin (`:302-308`); the
response path asserts only the store. So the response callback's lstate-targeting
— a named cross-buffer hazard (a response while another buffer is current must
not clear the wrong buffer's lstate) — is unasserted, while its store-targeting
counterpart and the timeout path's lstate-targeting are both covered. Minor:
the underlying mechanism (`with-current-buffer buffer` rebinding `defvar-local
psi-emacs--state`) is shared and exercised by the timeout test, but the design
names lstate clearing for the response path explicitly and it lacks a direct
assertion. Strengthen `…-targets-originating-buffer` to set an in-flight lstate
on the origin button before dispatch and assert it is cleared on the origin (and
left untouched on the other buffer), mirroring the timeout test.

## Test review follow-up — T4 resolved (ψ)

Strengthened `pwpt-dispatch-response-targets-originating-buffer`
(`psi-widget-projection-timers-test.el`) to assert the response callback's
in-flight **lstate** clearing on the ORIGIN buffer cross-buffer, closing the
asymmetry vs the timeout test (which already asserted both store + lstate).

- Both origin and other buffers now seed a button spec + `--sync-lstates` +
  an in-flight lstate (`b1` → t) before dispatch. The response fires while the
  OTHER buffer is current.
- Asserts: origin store entry cleared AND origin `--in-flight-p` is nil; other
  buffer's store (`'sentinel`) AND its in-flight lstate left untouched —
  mirroring `pwpt-on-mutation-timeout-targets-originating-buffer`.
- This directly covers design.md Scope (d)/acceptance — the response path
  clears the originating buffer's "buffer-local timer store **and lstate**" —
  not only its store-targeting counterpart.

Pure test-coverage addition for already-implemented behaviour; no
source/design change. `bb emacs:check` green (340/340, byte-compile clean);
the target test passes (289/340). `.el` reloaded into the running Emacs.

## Test review pass 3 (ψ)

Independent re-application of task-test-review (well_formed ∧
∀b∈behaviour(design).∃t.covers ∧ infra_deps injectable/¬mock). Full suite green
(340/340, byte-compile clean). Grounded on current source
(`psi-widget-projection.el:310/323/337/361`) and
`test/psi-widget-projection-timers-test.el`.

Well-formed: each test is isolated (fresh state via `pwpt--with-state` or
`setq-local`; generated buffers cleaned in `unwind-protect`), names its
intent, and asserts state — store contents, `in-flight-p` lstate,
cancel/timerp/armed flags — never interaction sequences.

Behaviour coverage (∀b∈design.∃t) verified complete:
- Scope (a) teardown cancel — `pwpt-teardown-cancels-in-flight-mutation-timers`.
- Scope (b) two-buffer independence — `pwpt-two-buffers-do-not-share-mutation-timer-state`.
- Scope (c) arm/cancel/timeout/response preserved — roundtrip, clears-in-flight,
  error-handler, dispatch-arms, cancels-on-response.
- Scope (d) response AND timeout cross-buffer-current targeting (store + lstate)
  — `pwpt-dispatch-response-targets-originating-buffer` (T4: store + lstate),
  `pwpt-on-mutation-timeout-targets-originating-buffer` (store + lstate).
- Scope (e) response/timeout dead-buffer no-op — `…-noop-when-buffer-dead`
  (both paths).
- AC arm threads buffer/state — `pwpt-arm-threads-buffer-and-state-into-scheduled-callback` (T1).
- AC nil-state timeout no-op (R1) — `pwpt-on-mutation-timeout-noop-when-state-nil`.
- transcript reset — `pwpt-reset-transcript-clears-mutation-timers`.
- clear helper — cancels+clears + nil-state no-op.

Infra deps: `cl-letf` substitutes only `run-at-time`, `cancel-timer`, `timerp`,
`send-request-function`, `psi-emacs--upsert-projection-block` — all
infrastructure/timer/RPC/render primitives (testing-without-mocks
"infrastructure → controllable"). No domain logic (timer-key, effective-timeout,
lstate transforms, store resolution, error-handler) is mocked; those run real.

No new actionable test gaps. All prior test follow-ups (T1, T3, T4) confirmed
resolved in tests. Verdict: REVIEW_COMPLETE.

## Test-shaper review (ψ)

Applied the test-shaper skill (clarity ∧ signal ∧ robustness ∧ **economy**) to
`test/psi-widget-projection-timers-test.el`. This lens is orthogonal to the
prior task-test-review passes (which proved ∀b∈design.∃t coverage): it weighs
`minimal_incidental_setup`, `helpers_that_compress(ceremony)`,
`consistent(fixtures)`, and `minimal(incidental_variation)`. Coverage,
determinism (`run-at-time`/`cancel-timer`/`timerp`/`send-request-function`
substituted), and state-based (¬interaction) assertions are all confirmed
strong; the findings below are economy/clarity refinements, not coverage or
correctness gaps.

Robustness confirmed: `pwpt-arm-cancel-mutation-timer-roundtrip` stubs
`run-at-time` to return `(apply #'list fn args)` (a list) and does NOT stub
`timerp`/`cancel-timer`; `--cancel-mutation-timer` guards `cancel-timer` with
`(timerp timer)`, so the list is never passed to `cancel-timer` — benign, the
test exercises puthash/remhash only (not real cancellation). Not a defect; the
name slightly oversells ("cancel" here = remhash). Non-actionable on its own.

Two actionable economy findings:

- **S1 (actionable, economy / ceremony) — unfactored cross-buffer setup
  repetition.** The three two-buffer tests
  (`pwpt-dispatch-response-targets-originating-buffer`,
  `pwpt-on-mutation-timeout-targets-originating-buffer`,
  `pwpt-two-buffers-do-not-share-mutation-timer-state`) each repeat the same
  ~10–15-line ceremony: `generate-new-buffer` → `setq-local psi-emacs--state
  (psi-emacs--initialize-state nil)` → (for two of them) `pwpt--make-button-spec`
  + `--sync-lstates` + `--set-lstate` in-flight seed → `unwind-protect` /
  `kill-buffer` teardown. `setq-local psi-emacs--state …` appears 9×,
  `generate-new-buffer` 10× across the file, yet no helper compresses it (only
  `pwpt--with-state` for the single-buffer dynamic-binding case exists). The
  test-shaper rule `helpers_that_compress(ceremony) ∧ ¬helpers_that_hide(intent)`
  is unmet: a `pwpt--with-psi-buffer (var &body)` macro (generate + `psi-emacs-mode`
  + `setq-local` state + `unwind-protect` kill) and/or a
  `pwpt--seed-button-in-flight (id key)` helper would cut the incidental setup so
  each cross-buffer test reads as its distinct arrange/act/assert intent
  (origin-cleared / other-untouched) rather than buried under buffer boilerplate.
  Keep the assertions inline (they ARE the intent); compress only the
  generate/seed/teardown scaffold.

- **S2 (minor, economy / consistency) — repeated no-op idiom.** The
  `(should-not (condition-case err (progn … nil) (error err)))` "is a harmless
  no-op" idiom is hand-rolled 6× in this file
  (`…-noop-when-buffer-dead`, `…-noop-when-state-nil`,
  `…-noop-when-buffer-dead` response, `…-clear-mutation-timers-noop-when-state-nil`,
  and the two error-handler no-op tests) — and again in the sibling
  `psi-widget-projection-test.el:86`. A shared `pwpt--should-not-error (&body)`
  (or `pwpt--should-be-noop`) macro would remove the repeated
  `condition-case`/`progn … nil`/`(error err)` scaffold, make the no-op intent
  explicit at each call, and give `consistent(assertion_style)` across both test
  files. Minor: the idiom is correct and uniform today; this is a clarity/economy
  compression, deferrable.

Both are refactors of test scaffolding for already-correct, fully-covering
tests; neither changes behaviour, coverage, or source. Verdict:
ACTIONABLE_FEEDBACK (economy/clarity refinements S1, S2).

## Test-shaper review follow-ups executed (S1, S2) — 2026-06-02

Executed the two newly-added test-shaper review items. Both pure test-scaffold
refactors of `test/psi-widget-projection-timers-test.el` (+ one site in sibling
`test/psi-widget-projection-test.el`); no source, no coverage change. Full
`bb emacs:test` 340/340 before and after; byte-compile clean; reloaded.

- **S2 — shared no-op assertion macro.** Added `psi-test--should-not-error
  (&rest body)` to the shared `test/psi-test-support.el` (not a per-file copy):
  the `(should-not (condition-case err (progn … nil) (error err)))` idiom is a
  generic no-op assertion shared by *both* widget-projection test files, so it
  belongs in shared support, giving `consistent(assertion_style)` across files.
  Wired `(require 'psi-test-support)` (+ the `test/` load-path entry) into both
  `psi-widget-projection-timers-test.el` and `psi-widget-projection-test.el`
  (mirroring the established pattern in the other emacs-ui test files). Replaced
  all 5 remaining idiom sites in the timers file (timeout dead-buffer no-op,
  timeout nil-state no-op, error-handler nil no-op, error-handler-exception
  no-op, clear-timers nil-state no-op) and the 1 site in the sibling file
  (`pwpt-request-specs-noop-when-no-send-function`). The response-dead-buffer
  test also adopted the macro.

- **S1 — buffer ceremony helpers (local to timers file).** Added two helpers
  local to the timers test (specific to buffer-local widget state, so NOT in
  shared support):
  - `pwpt--with-psi-buffer (var &rest body)` — `generate-new-buffer` +
    `setq-local psi-emacs--state (psi-emacs--initialize-state nil)` +
    `unwind-protect`/`kill-buffer`. Nestable; used for the two two-buffer tests
    (`…-dispatch-response-targets-originating-buffer`,
    `…-on-mutation-timeout-targets-originating-buffer`) and
    `…-two-buffers-do-not-share-mutation-timer-state`.
  - `pwpt--seed-button-in-flight (id key)` — registers a single-button spec,
    syncs lstates, marks the button in-flight in the current buffer; folds the
    repeated spec+`--sync-lstates`+`--set-lstate` arrange block in the two
    cross-buffer response/timeout tests.

  Assertions left fully inline (they ARE the intent —
  `helpers_that_compress(ceremony) ∧ ¬helpers_that_hide(intent)`). Deliberately
  did NOT convert `…-dispatch-response-noop-when-buffer-dead` to
  `pwpt--with-psi-buffer`: that test's explicit mid-test `kill-buffer` is the
  *act*, not teardown, so it keeps an inline `generate-new-buffer`/`kill-buffer`
  to preserve that intent (the macro's teardown kill would obscure it).

## Test-shaper review pass 2 (ψ)

Independent re-application of test-shaper (clarity ∧ signal ∧ robustness ∧
economy) to `test/psi-widget-projection-timers-test.el` after the S1/S2
helper-factoring landed. Grounded on the current test file + source
(`psi-widget-projection.el`). Determinism (`run-at-time`/`cancel-timer`/`timerp`/
`send-request-function`/`upsert-projection-block` all substituted), state-based
(¬interaction) assertions, and behaviour coverage all re-confirmed strong; the
S1 `pwpt--with-psi-buffer` / `pwpt--seed-button-in-flight` and S2
`psi-test--should-not-error` factorings read cleanly and compress ceremony
without hiding intent.

One NEW minor actionable economy/consistency gap (S3): `pwpt--seed-button-in-flight`
(introduced in S1) was applied only to the cross-buffer tests, but the
single-buffer `pwpt-on-mutation-timeout-clears-in-flight` hand-rolls the exact
same arrange ceremony it encapsulates — spec via `pwpt--make-button-spec` +
`setf projection-widget-specs` + `--sync-lstates` + `--set-lstate` with an
in-flight lstate (`lstate-set-in-flight … key t`). That is precisely the helper
body, so the helper applies verbatim there. (`pwpt-on-mutation-timeout-calls-error-handler`
shares the spec+sync prefix but deliberately omits the in-flight `--set-lstate`,
so the in-flight helper is NOT a clean fit there — leave it or factor only a
narrower spec+sync helper.) Incidental-variation: the same seed appears in two
shapes (helper vs inline) for the same intent, costing `minimal_incidental_variation`
∧ `consistent(fixtures)`. Minor — coverage/correctness unaffected; deferrable.

Non-actionable (noted, no follow-up): `pwpt-arm-cancel-mutation-timer-roundtrip`'s
name slightly oversells "cancel" (the path is remhash, `timerp` unstubbed so
`cancel-timer` never fires on the fake list timer) — already recorded in the
prior test-shaper note as benign. The `'t1`/`'t2`/`'live-timer` quoted-symbol
fake timers are idiomatic ERT fixtures, not a defect.

Verdict: ACTIONABLE_FEEDBACK (minor economy refinement S3).

## Test-shaper pass-2 follow-up — S3 executed (2026-06-02)

Applied `pwpt--seed-button-in-flight "w1" "b1"` to
`pwpt-on-mutation-timeout-clears-in-flight` in
`test/psi-widget-projection-timers-test.el`, replacing the inline
spec+`setf projection-widget-specs`+`--sync-lstates`+`--set-lstate(in-flight)`
block (13 lines → 8). The `cl-letf`/act/assert stays inline. The seed now reads
one way file-wide (`consistent(fixtures)` ∧ `minimal_incidental_variation`).
Confirmed the helper is verbatim-equivalent: helper uses ID as both spec id and
`--set-lstate` widget-id with KEY as the in-flight node key — exactly the prior
inline `"w1"`/`"b1"` shape. Did NOT touch
`pwpt-on-mutation-timeout-calls-error-handler` (no in-flight `--set-lstate`).
`bb emacs:check` green (340/340); byte-compile clean; reloaded `.el`.

## Test-shaper review pass 3 (ψ)

Independent re-application of test-shaper (clarity ∧ signal ∧ robustness ∧
economy) to `test/psi-widget-projection-timers-test.el` after S1/S2/S3 landed.
Full suite 340/340 green. Grounded on the current test file +
`psi-lifecycle.el:271/374` (teardown/reset under test).

Re-confirmed strong: determinism (`run-at-time`/`cancel-timer`/`timerp`/
`send-request-function`/`upsert-projection-block` all substituted — infra only),
state-based (¬interaction) assertions, complete design behaviour coverage, and
the S1 `pwpt--with-psi-buffer` / `pwpt--seed-button-in-flight` + S2
`psi-test--should-not-error` factorings compress ceremony without hiding intent.

One NEW minor actionable economy/consistency gap (S4):
`pwpt-teardown-cancels-in-flight-mutation-timers` (`:387`) and
`pwpt-reset-transcript-clears-mutation-timers` (`:409`) each hand-roll the same
~5-line MODE-BEARING buffer ceremony —
`generate-new-buffer` + `with-current-buffer` + `(psi-emacs-mode)` +
`setq-local psi-emacs--state` + `unwind-protect`/`kill-buffer` — plus an
identical `cl-letf` `timerp`/`cancel-timer` capture preamble. The S1
`pwpt--with-psi-buffer` macro does NOT fit them because it omits
`(psi-emacs-mode)`, which these two tests require (they exercise
`psi-emacs--teardown-buffer`/`psi-emacs--reset-transcript-state`, which touch
mode-bound machinery: window-config hook, regions, header-line). So the
mode-bearing variant is a distinct, twice-repeated scaffold with no compressing
helper, costing `minimal_incidental_variation` ∧ `consistent(fixtures)` against
the non-mode cross-buffer tests that S1 already factored. A
`pwpt--with-psi-mode-buffer (var &rest body)` (macro mirroring
`pwpt--with-psi-buffer` but adding `(psi-emacs-mode)`) would let both tests read
as their distinct arrange/act/assert intent (puthash live-timer → run
teardown/reset → assert store empty + timer cancelled) rather than buried under
buffer+mode boilerplate. Minor: only two callers, the `kill-buffer` is genuine
teardown (not the act, unlike `…-noop-when-buffer-dead`), coverage/correctness
unaffected — deferrable. Not a duplicate of S1 (which scoped only the non-mode
cross-buffer trio).

Non-actionable (noted, no follow-up): the timerp/cancel-timer `cl-letf`
preamble shared by the same two tests could fold into the same helper, but
that couples capture-list shape to the helper and risks hiding the act's
substitution intent — leave inline. `pwpt-arm-cancel-mutation-timer-roundtrip`
name still slightly oversells "cancel" (remhash path) — already recorded benign
in the prior pass.

Verdict: ACTIONABLE_FEEDBACK (minor economy refinement S4).

## S4 — Mode-bearing buffer ceremony factored (2026-06-02)

Added `pwpt--with-psi-mode-buffer (var &rest body)` to
`test/psi-widget-projection-timers-test.el`, mirroring the S1
`pwpt--with-psi-buffer` macro but enabling `(psi-emacs-mode)` before seeding the
buffer-local `psi-emacs--state` and running BODY inside the buffer (current).
Applied it to the two mode-bearing tests that hand-rolled the
`generate-new-buffer` + `with-current-buffer` + `(psi-emacs-mode)` +
`setq-local psi-emacs--state` + `unwind-protect`/`kill-buffer` scaffold:
`pwpt-teardown-cancels-in-flight-mutation-timers` and
`pwpt-reset-transcript-clears-mutation-timers`. Each now reads as its distinct
arrange/act/assert intent (puthash live-timer → run teardown/reset → assert
store empty + timer cancelled); the `cl-letf` `timerp`/`cancel-timer` capture
preamble stays inline per the prior pass's non-actionable note (folding it would
couple the helper to the capture-list shape and hide the substitution intent).

Macro design note: unlike `pwpt--with-psi-buffer` (which seeds state in a
separate `with-current-buffer` then wraps BODY in `unwind-protect`/`progn`),
the mode variant runs BODY *inside* the seeding `with-current-buffer` so callers
needn't re-enter the buffer — these two tests act on the current buffer
throughout. The genuine `kill-buffer` teardown is preserved in `unwind-protect`
(it is real cleanup here, not the act under test).

Verification: `bb emacs:check` 340/340 green; byte-compile clean (fixed a
docstring-width warning on the new macro's first line by shortening it to ≤80
chars and rewrapping the cross-reference). No production code touched — test-only
economy/consistency refinement.

Verdict: S4 complete. No remaining unchecked follow-up items in steps.md.

## Test-shaper review pass 4 (ψ)

Independent re-application of test-shaper (clarity ∧ signal ∧ robustness ∧
economy) to `test/psi-widget-projection-timers-test.el` after S1–S4 landed.
Full suite 340/340 green, byte-compile clean. Determinism
(`run-at-time`/`cancel-timer`/`timerp`/`send-request-function`/
`upsert-projection-block` all substituted — infra only), state-based
(¬interaction) assertions, and complete design behaviour coverage all
re-confirmed strong; the prior S1/S2/S3/S4 factorings (`pwpt--with-psi-buffer`,
`pwpt--with-psi-mode-buffer`, `pwpt--seed-button-in-flight`,
`psi-test--should-not-error`) compress ceremony without hiding intent.

One NEW minor actionable economy/consistency gap (S5): the
DISPATCH/RESPONSE substitution preamble is hand-rolled 3× across
`pwpt-dispatch-mutation-cancels-timer-on-response` (`:220`),
`pwpt-dispatch-response-targets-originating-buffer` (`:248`), and
`pwpt-dispatch-response-noop-when-buffer-dead` (`:288`). Each binds the same
uniform infrastructure stubs to drive a dispatch + capture its response
callback: `run-at-time → 'fake-timer`, `timerp → (eq x 'fake-timer)`,
`send-request-function → (setq captured-cb cb)`, and
`upsert-projection-block → #'ignore`, plus a `(let ((captured-cb nil)) …)`
binding. This is a ~5-line incidental-setup block repeated for the same intent
(dispatch, then fire the captured response callback), with the only meaningful
variation being `cancel-timer` (captured in the first test where cancellation
IS asserted; `#'ignore` in the two cross-buffer/dead-buffer tests where the
store/lstate state is the assertion subject, not the cancel call). Prior passes
(S1/S4) factored the buffer scaffold but not this dispatch-stub preamble; the
non-actionable note about NOT folding the teardown tests' `timerp`/`cancel-timer`
preamble concerned a different case (there `cancel-timer` capture IS the
assertion). For the response tests the `run-at-time`/`timerp`/
`send-request-function`/`upsert` stubs are pure plumbing (the act is firing
`captured-cb`), so a `pwpt--with-dispatch-stubs (cb-var &rest body)` macro
(binding `cb-var`, stubbing `run-at-time`/`timerp`/`send-request-function`/
`upsert-projection-block`, leaving `cancel-timer` to the caller's `cl-letf`
when it is the assertion subject) would cut the incidental setup so each
dispatch test reads as its distinct arrange/act/assert intent rather than
buried under stub boilerplate (`helpers_that_compress(ceremony) ∧
¬helpers_that_hide(intent)`, `minimal_incidental_variation` ∧
`consistent(fixtures)`). Keep the assertions and the per-test `cancel-timer`
binding inline (they are the intent). Minor: coverage/correctness/determinism
unaffected; the stubs are uniform and correct today — deferrable.

Non-actionable (noted, no follow-up): `pwpt-arm-cancel-mutation-timer-roundtrip`'s
name still slightly oversells "cancel" (remhash path; `timerp` unstubbed so
`cancel-timer` never fires on the fake list timer) — already recorded benign in
prior passes.

Verdict: ACTIONABLE_FEEDBACK (minor economy refinement S5).

## Test-shaper pass-4 follow-up — S5 executed (ψ)

Added `pwpt--with-dispatch-stubs (cb-var &rest body)` macro to
`test/psi-widget-projection-timers-test.el` (after `pwpt--capture-query-sends`).
It binds `cb-var` and stubs the uniform dispatch/response infrastructure:
`run-at-time → 'fake-timer`, `timerp → (eq x 'fake-timer)`,
`send-request-function → (setq cb-var cb)`, `upsert-projection-block → #'ignore`.
Deliberately does NOT stub `cancel-timer` — left to each caller's inner `cl-letf`.

Applied to all three dispatch/response tests:
- `pwpt-dispatch-mutation-cancels-timer-on-response` — `cancel-timer` is the
  assertion subject, so it stays inline in an inner `cl-letf` capturing
  `timer-cancelled`; the four plumbing stubs + `captured-cb` now come from the
  macro. Kept `pwpt--with-state` for the buffer-local store.
- `pwpt-dispatch-response-targets-originating-buffer` — macro replaces the
  `(let ((captured-cb nil)) (cl-letf …))` preamble; `cancel-timer #'ignore`
  retained in a thin inner `cl-letf` (state-targeting is the assertion, not
  cancellation); the two `pwpt--with-psi-buffer` forms nest inside.
- `pwpt-dispatch-response-noop-when-buffer-dead` — same macro substitution;
  `cancel-timer #'ignore` inner `cl-letf`; manual buffer create/kill body
  preserved (it deliberately kills the origin buffer to drive the dead-buffer
  no-op).

Per-test variation now reads cleanly: test 1 captures `cancel-timer`; tests 2/3
use `#'ignore`. Assertions + the per-test `cancel-timer` binding stay inline
(they are the intent). `bb emacs:check` green (340/340; tests 287–289 are the
three refactored dispatch tests); byte-compile clean; reloaded `.el`. S5 checked.
This was the only newly-added unchecked steps.md item (test-shaper pass 4); all
206 steps now checked.

## Test-shaper review pass 5 (ψ)

Independent re-application of test-shaper (clarity ∧ signal ∧ robustness ∧
economy) to `test/psi-widget-projection-timers-test.el` after S1–S5 landed.
Full suite 340/340 green, byte-compile clean. Grounded on the current test
file + the sibling `psi-widget-projection-test.el` and shared
`psi-test-support.el`.

Re-confirmed strong: determinism (`run-at-time`/`cancel-timer`/`timerp`/
`send-request-function`/`upsert-projection-block` all substituted — infra
only, no logic mocked), state-based (¬interaction) assertions (store contents,
`in-flight-p` lstate, cancel/armed sentinels), complete design behaviour
coverage (Scope (a)–(e) + AC), and `single_concern` / `minimal_incidental_setup`
per test. The five prior factorings — `pwpt--with-psi-buffer`,
`pwpt--with-psi-mode-buffer`, `pwpt--seed-button-in-flight`,
`pwpt--with-dispatch-stubs`, shared `psi-test--should-not-error` — compress
ceremony without hiding intent; assertions stay inline as the intent.

No NEW actionable economy/clarity gaps. The remaining residual observations are
all already-recorded non-actionable items:
- `pwpt-arm-cancel-mutation-timer-roundtrip`'s name slightly oversells "cancel"
  (the path is remhash; `timerp` unstubbed so `cancel-timer` never fires on the
  fake list timer) — recorded benign across passes 1–4.
- `pwpt-on-mutation-timeout-targets-originating-buffer`'s inline `cl-letf`
  (`cancel-timer`/`timerp`/`upsert-projection-block`) is deliberately NOT folded
  into `pwpt--with-dispatch-stubs`: that macro is the dispatch/RESPONSE shape
  (binds `run-at-time`/`send-request-function`/captured-cb), which the timeout
  path neither needs nor uses. Forcing the macro here would add unused stubs and
  obscure the timeout act — a genuine distinct substitution, not redundant
  ceremony. Correctly left inline.

Verdict: REVIEW_COMPLETE.

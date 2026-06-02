# Implementation Notes

## Design review — architectural fit (ψ)

Reviewed `design.md` for architectural fit only (¬ambiguity, ¬inconsistency)
against AGENTS.md, doc/architecture.md, testing-without-mocks.

Findings (actionable misfits):

1. **Single-source violation risk.** doc/architecture.md mandates built-in
   command identity lives in *exactly one place* (`builtin-command-specs` in
   `psi.agent-session.commands.builtin-specs`); all UI surfaces are *pure
   projections* with no hardcoded built-in command lists. The design seeds
   *representative* literal specs into the TUI test helper — a hardcoded
   built-in list that duplicates the authoritative table and is free to drift.
   Confirmed structurally forced: `components/tui/deps.edn` has no
   `agent-session` dep, so the test literally cannot reference the canonical
   table. The seed is therefore an unanchored duplicate. Design does not
   acknowledge this or specify drift protection.

2. **Seam at wrong layer (testing-without-mocks).** The defect is that a `nil`
   `query-fn` yields an empty surface. The architecturally-aligned fix is a
   Nullable/Configurable-Response seam (build-init with a nil/stub query-fn
   still yields a representative state via an embedded stub) rather than the
   test post-hoc `(assoc state :builtin-command-specs ...)`. The design's
   test-only constraint patches *around* the production seam, asserting state
   the production path never produces. Flag as fit concern even if the
   test-only scope is retained.

Conclusion: actionable architectural-fit feedback found (see design-steps.md).

## Design-steps follow-up — resolution (ψ)

Both architectural-fit follow-ups resolved by re-scoping the design (not the
test code; this is a design task):

- **Seam layer (item 2).** `query-fn` is the genuine infrastructure boundary
  for backend introspection, and `make-init`/`build-init` already accept and
  validate a callable `query-fn` (`ensure-init-arg-contract!`). The fix is now
  to drive the test `init-state` through that seam with a *stub* `query-fn`
  (Configurable-Response Nullable) returning representative specs, so the real
  `build-init` introspection populates `:builtin-command-specs`. The design now
  forbids the post-hoc `(assoc state :builtin-command-specs ...)` injection of
  the default surface, which asserted state the production path never produces.

- **Single-source drift (item 1).** Confirmed structurally forced:
  `components/tui/deps.edn` has no `agent-session` dep, so the test cannot
  reference the canonical `builtin-command-specs` table. Resolved via path (a):
  design documents the rationale and bounds the risk — the literal specs are
  *test fixture data* standing in for the backend at the `query-fn` seam, not a
  production UI command list. No production projection gains a hardcoded list;
  drift surfaces only as fixture staleness (failing/vacuous assertions), not a
  production command-surface inconsistency. The fixture is intentionally small
  and representative, not an exhaustive table mirror.

No blocking reasons; both design-steps completed. design.md updated (Scope,
Constraints + new drift subsection, Acceptance).

## Design review — ambiguity (ψ)

Reviewed design.md for ambiguities only (¬architecture, ¬correctness) against
support.clj (build-init/refresh query keys), autocomplete.clj (slash-candidates),
make-init contract, and builtin-command-specs-for-resolver shape. Facts verified.

Actionable ambiguities (see design-steps.md):

1. **Stub fixture membership undefined.** "representative … intentionally small"
   specs + acceptance "one or more … at least one expected name (e.g.
   help/status/quit)" do not state which/how many specs the stub must contain.
   "e.g." + "one or more" is undecidable: must all of help/status/quit be
   present, or any one? No inclusion rule.
2. **"broader introspection query keys" obligation ambiguous.** Constraint says
   stub specs must match the resolver shape "(and the broader introspection
   query keys that build-init issues)". build-init issues a 7-key query.
   Unclear whether the stub query-fn must populate all 7 keys or only
   :psi.agent-session/builtin-command-specs.
3. **Empty-surface example conflates two shapes.** Acceptance: "stub query-fn
   returning [], or a nil query-fn". build-init calls query-fn with a key vector
   and treats the result as a map ((:k introspected)). A query-fn "returning []"
   is not the map shape; ambiguous whether it means the whole-query return is []
   or a map with [] under the builtin key.
4. **"existing and (where appropriate) added autocomplete tests" scope undefined.**
   "where appropriate" gives no criterion for which existing tests must be
   updated to assert real candidates vs left unchanged.

Conclusion: actionable ambiguity feedback found.

## Plan/steps ambiguity follow-up — resolution (ψ)

Executed both plan/steps ambiguity follow-ups (design/plan task; no test/
production code touched — slices 1–3 remain unimplemented).

1. **Slice 2 positive branch this-or-that.** Rewrote the steps.md Slice 2
   positive-branch step and plan.md Slice 2 (decision 5 + Slice-order entry) to
   use the default-fixture form `(init-state)` only, per design Test scope
   ("the positive built-in surface assertion uses the default stub specs").
   Deleted the `(init-state {:builtin-command-specs [...]})` / "include `status`
   in its per-case input" / "(or the default fixture)" alternatives. The default
   fixture `sample-builtin-command-specs` already contains help/status/quit.

2. **Slice 3 verify command open choice.** Removed the "`bb test` TUI scope or
   focused `app-input-selector-test`" open choice from the first Slice 3 box;
   stated the focused `app-input-selector-test` as the inner loop. The original
   follow-up assumed the full TUI `bb test` was "already the final Slice 3 box",
   but no such box existed (the last box covered `git diff`/no-production-change).
   Added an explicit gating Slice 3 box running the full TUI component `bb test`
   (design Acceptance: "`bb test` (TUI component) passes") so the inner-loop /
   gating split is well-grounded rather than a dangling reference.

No blocking reasons; both follow-ups completed.

## Design-steps follow-up — ambiguity resolution (ψ)

Resolved all four ambiguity follow-ups by tightening design.md (still a design
task; no test/production code touched). Facts re-verified against
`support.clj` build-init (7-key query, result-as-map with `or` defaults) and
`app_input_selector_test.clj` (current tests).

- **Fixture membership (1).** Decidable rule added: stub fixture must contain at
  minimum `help`/`status`/`quit`; autocomplete test asserts all three
  (`/help`/`/status`/`/quit`). Removed "one or more … e.g." phrasing.
- **Broader query keys (2).** New "Stub `query-fn` contract" section: stub need
  only populate `:psi.agent-session/builtin-command-specs`; build-init defaults
  the other six keys. Dropped the ambiguous "broader query keys" obligation
  from Constraints.
- **Empty-surface shape (3).** Acceptance now states empty = map with `[]` under
  `:psi.agent-session/builtin-command-specs` (or nil query-fn), explicitly not a
  bare `[]` return — aligning with build-init's map-keyed result handling.
- **Test scope (4).** New "Test scope" section names the two built-in
  autocomplete tests that must source from the seam and the subject-based
  criterion that leaves unrelated `init-state` callers unchanged.

No blocking reasons; all four design-steps completed.

## Design review — inconsistency (ψ)

Reviewed design.md for internal inconsistency and design-vs-artifact
inconsistency only (¬architecture, ¬ambiguity, ¬correctness). Facts verified
against support.clj (build-init 7-key query, map-keyed `or` defaults),
app.clj (make-init/ensure-init-arg-contract!), autocomplete.clj
(slash-candidates), builtin_specs.clj (`{:name :description}` resolver shape),
and app_input_selector_test.clj (current init-state + named tests). Resolver
shape, 7-key query, and seam mechanics all match the design.

Actionable inconsistencies (see design-steps.md):

1. **post-hoc `assoc` prohibition: unqualified vs. qualified.** Constraints
   flatly forbid "a post-hoc `(assoc state :builtin-command-specs ...)`"
   (unconditional), but Acceptance narrows it to "no post-hoc `assoc` … to
   inject the default surface", and Test scope endorses
   `autocomplete-slash-dedupes-builtin-template-collision-test` setting
   `:builtin-command-specs` directly. The three statements disagree on whether
   per-case direct `assoc` of non-default specs is permitted.

2. **"source from seam-produced state" vs. "set directly" for the same test.**
   Scope/Test scope require the named built-in autocomplete tests to "source
   candidates from the seam-produced state", yet Test scope simultaneously
   describes the collision test (one of those named tests) as one "which set
   `:builtin-command-specs` directly" — not seam-sourced. The mandate and the
   concession contradict; design does not state whether the collision/empty
   cases must be converted to the seam or may keep direct `assoc`.

Conclusion: actionable inconsistency feedback found.

## Design-steps follow-up — inconsistency resolution (ψ)

Resolved both inconsistency follow-ups with one uniform rule (design task; no
test/production code touched). The two items were the same conflict: whether
per-case (non-default) built-in specs may be set via post-hoc `assoc` or must
go through the stub `query-fn` seam.

Decision: the post-hoc `(assoc state :builtin-command-specs ...)` is
**unconditionally forbidden** for every built-in-surface case (default,
collision, empty). The stub `query-fn` seam is the single mechanism; only the
*input* to the stub varies per case. The `init-state` helper takes a per-case
option (e.g. `:builtin-command-specs`) that it routes into the stub's returned
map, so the real `build-init` introspection path produces every surface
(`resume` for collision, `[]` for empty) — matching testing-without-mocks
Configurable-Response Nullable and removing the assertion of non-producible
state.

Aligned all four touchpoints to this rule:
- Constraints — prohibition is unconditional + per-case-via-seam added.
- Scope — `init-state` parameterizes stub input (not post-hoc assoc).
- Acceptance — no post-hoc assoc for any case; empty case feeds `[]` to stub.
- Test scope — collision/empty cases converted to seam input; dropped the
  "set `:builtin-command-specs` directly" descriptions.

No blocking reasons; both design-steps completed.

## Plan/steps review — ambiguity (ψ)

Reviewed plan.md + steps.md for ambiguities only (¬architecture, ¬correctness)
against the now-settled design.md and verified production seam (support.clj
`build-init` 7-key query + map-keyed `or` defaults; `refresh-extension-command-names`
vector-guarded slots; app.clj `ensure-init-arg-contract!` nil-or-ifn?). Seam
mechanics and the per-case-via-stub rule match design.

Actionable ambiguities (see steps.md):

1. **Slice 2 positive branch — unresolved this-or-that.** plan.md Slice 2
   ("switch it to the default fixture … or include `status` in its per-case
   input") and steps.md ("`(init-state {:builtin-command-specs [...]})` (or
   plain `(init-state)` using the default fixture)" + "(or the default
   fixture)") leave the implementer a choice with no criterion — violates
   `one_way`. design.md Test scope already decided this: the positive assertion
   "uses the default stub specs". Plan/steps reintroduce a choice the design
   settled; pick the default-fixture form and drop the alternatives.

2. **Slice 3 verify command — `bb test` vs. focused-only.** steps.md Slice 3
   first box offers "`bb test` TUI scope or focused `app-input-selector-test`"
   with no criterion. design.md Acceptance requires `bb test` (TUI component)
   passes; a focused-only run does not satisfy that acceptance. State that the
   focused run is the inner loop and the full TUI `bb test` is the gating
   verification (already covered by the final Slice 3 box), removing the
   open "or".

Conclusion: actionable ambiguity feedback found.

## Plan/steps review — inconsistency (ψ)

Reviewed plan.md + steps.md for inconsistency only (¬architecture, ¬ambiguity,
¬correctness): plan-internal, plan-vs-steps, and plan/steps-vs-design.
Grounded against `support.clj` `build-init` (7-key query, map-keyed `or`
defaults, `:builtin-command-specs` line 237, `:query-fn` line 238), `app.clj`
`ensure-init-arg-contract!` (nil-or-ifn?), and the current test
`app_input_selector_test.clj` (`init-state` lines 43–64 **ends with**
`(assoc state :builtin-command-specs (vec builtin-specs))` where the default is
the full 7-item `sample-builtin-command-specs`; positive test lines 188–197
overrides to `[help reload-models speed]` and asserts `/reload-models`+`/speed`+
`/help`). Seam mechanics + per-case-via-stub rule match design.

Actionable inconsistencies (see steps.md):

1. **I1 — "regression intent" fixture vs. strengthened positive assertion.**
   plan.md Decision 4 keeps the extra specs "(reload-models, speed, …)" with
   the justification that "they cover the **previously-missing-command
   regression intent**". But Decision 5 + Slice 2 rewrite the positive branch
   to the default `(init-state)` and strengthen its assertion to only
   `/help`/`/status`/`/quit`, dropping the current `/reload-models`+`/speed`
   assertions. After the rewrite **no** step asserts any previously-missing
   command, so Decision 4's stated regression-intent justification is realized
   by nothing. Reconcile: either retain a previously-missing name in the
   positive assertion (e.g. add `/reload-models`), or drop Decision 4's
   "regression intent" justification (design Acceptance/Test scope only require
   help/status/quit).

2. **I2 — "produced test state empty" vs. helper post-hoc assocs the default.**
   design.md Problem (line 31) states "The produced test state has
   `:builtin-command-specs` empty" and plan.md Risk R2 says unrelated tests
   were "(previously empty unless they assoc'd)". But the current helper (and
   plan.md "Verified mechanics") ends with
   `(assoc state :builtin-command-specs (vec builtin-specs))`, defaulting to the
   full `sample-builtin-command-specs` — so `(init-state)` already yields a
   **non-empty** built-in surface today; unrelated tests are not "previously
   empty". These statements contradict the verified current code (and each
   other within plan.md: Verified-mechanics-assoc vs R2-previously-empty).
   Reconcile: fix R2's premise (unrelated tests already receive the full
   default surface; the change moves *how* it is produced from post-hoc assoc
   to the seam, not *whether* it is present) and qualify design Problem line 31
   as "empty via the `build-init` introspection path (before the helper's
   post-hoc assoc patches it)".

Conclusion: actionable inconsistency feedback found.

## Plan/steps inconsistency follow-up — resolution (ψ)

Executed both plan/steps inconsistency follow-ups (I1, I2). Still a design/plan
task; no test/production code touched — slices 1–3 remain unimplemented.

- **I1 — regression intent vs. strengthened assertion.** Chose the
  design-aligned option (drop, not add): rewrote plan.md Decision 4 to remove
  the "previously-missing-command regression intent" justification. design.md
  Acceptance + Test scope require the positive assertion to cover only
  `/help`/`/status`/`/quit`, and Decision 5 + Slice 2 already settle on that
  surface, so no step asserts a previously-missing command. The extra fixture
  specs (reload-models, speed, …) are now documented as benign, unasserted
  fixture data — keeping or trimming them is immaterial to acceptance. This
  avoids re-widening the asserted surface beyond what design settled.

- **I2 — "produced test state empty" vs. helper post-hoc assocs the default.**
  Verified against the live helper (`app_input_selector_test.clj` lines 43–64):
  it ends with `(assoc state :builtin-command-specs (vec builtin-specs))`,
  defaulting to the full `sample-builtin-command-specs`, so `(init-state)` is
  already non-empty today. Corrected plan.md Risk R2's premise: unrelated tests
  already receive the full default surface; the rewire moves *how* the default
  surface is produced (post-hoc `assoc` → stub `query-fn` seam), not *whether*
  it is present, so the candidate set unrelated tests see is unchanged.

  **Deferred sub-part (design.md half of I2).** I2 also calls for qualifying
  design.md Problem line 31 ("The produced test state has
  `:builtin-command-specs` empty") as "empty via the `build-init` introspection
  path, before the helper's post-hoc assoc patches it". design.md is read-only
  in this follow-up pass, so that qualification is **not** applied here. The
  contradiction is real (line 31 reads as describing the final produced state,
  which is non-empty; it actually describes the pre-assoc `build-init` output
  with a nil `query-fn`). Tracked here as a pending design edit to apply when
  design.md is next writable: qualify Problem line 31 to scope "empty" to the
  `build-init` introspection path before the helper's post-hoc assoc.

## Implementation pass (ψ, 2026-06-02)

Executed slices 1–3; test-only change, single file
`components/tui/test/psi/tui/app_input_selector_test.clj`.

- **Slice 1.** Added a stub `query-fn` inside `init-state` returning
  `{:psi.agent-session/builtin-command-specs (vec builtin-specs)}` (only that
  key; `build-init` defaults the other six). Passed it as `make-init`'s first
  arg (was `nil`). Removed the trailing
  `(assoc state :builtin-command-specs (vec builtin-specs))` — the real
  `build-init` introspection path now produces the slot. `:builtin-command-specs`
  opt is still resolved from `opts` (default `sample-builtin-command-specs`) and
  routed into the stub; `:ui-state*` / `:ui-read-fn` handling unchanged.
- **Slice 2.** Positive built-in branch now uses plain `(init-state)` (default
  fixture) and asserts all three of `/help`, `/status`, `/quit`. Empty branch
  uses `(init-state {:builtin-command-specs []})`. Both collision branches use
  `(assoc (init-state {:builtin-command-specs [{:name "resume" …}]}) …)` —
  built-in specs via the seam, the unrelated `:prompt-templates` /
  `:extension-command-names` slots remain post-hoc assocs (permitted). No
  forbidden `(assoc … :builtin-command-specs …)` remains (grep confirms only
  comment mentions + the seam-input inner calls).
- **Slice 3.** Focused `app-input-selector-test`: 15 tests, 40 assertions, 0
  failures. `clj-paren-repair` + `clj-kondo`: clean (0/0). Full unit suite
  (`clojure -M:test --focus unit`): RC=0 (TUI component lives in `:unit`),
  satisfying design Acceptance. No production file changed; test-only, no
  changelog entry.

## Resolved deferred follow-up (I2 design half)

design.md Problem bullet (was "The produced test state has
`:builtin-command-specs` empty") now qualified: the `build-init` introspection
path leaves the slot empty and the current helper masks it with a post-hoc
assoc. Applied this pass since design.md is writable during implementation.

## Implementation review — quality (ψ, 2026-06-02)

Reviewed the implemented change (commit 59c3b25b8) per task-implementation-review:
design fit, architecture fit, new-pattern/abstraction/perf flags. Grounded
against `support.clj` (`build-init` 7-key query, map-keyed `or` defaults,
`:builtin-command-specs` line 237, `:query-fn` line 238),
`autocomplete.clj` (`builtins` derived from `(:builtin-command-specs state)`,
line 58–59), `builtin_specs.clj` (`builtin-command-specs-for-resolver` →
`{:name :description}` vector), and the resolver
(`resolvers/extensions.clj:160`).

Verified:
- **Matches design.** Stub `query-fn` lives in `init-state`, returns
  `{:psi.agent-session/builtin-command-specs (vec builtin-specs)}` (only that
  key); passed as `make-init` first arg; trailing post-hoc
  `(assoc … :builtin-command-specs …)` removed. Per-case specs flow through the
  `:builtin-command-specs` opt → stub. Positive branch is plain `(init-state)`
  asserting all three `/help`/`/status`/`/quit`; empty via
  `(init-state {:builtin-command-specs []})`; collision branches via
  `(assoc (init-state {:builtin-command-specs [{:name "resume" …}]}) …)` where
  the outer assoc sets only the unrelated `:prompt-templates` /
  `:extension-command-names` slots (permitted). Grep confirms no forbidden
  `(assoc … :builtin-command-specs …)` for any case.
- **Architecture fit.** Drives the genuine infrastructure seam
  (Configurable-Response Nullable, testing-without-mocks), not a mock. Test-only
  diff (`git diff --name-only 59c3b25b8~1` lists only the test file + munera
  artifacts); no production behaviour change. Fixture shape `{:name :description}`
  matches the resolver exactly, so the test reflects production.
- **Drift.** `components/tui/deps.edn` has no `agent-session` dep (verified), so
  the literal fixture is structurally forced and bounded as design documents.
- **No new patterns / abstractions / perf concerns.**

Verification: focused `app-input-selector-test` 15 tests, 40 assertions, 0
failures; `clj-kondo` errors 0, warnings 0.

Conclusion: no new actionable implementation-quality feedback. (The lone open
item is the I2 design-half qualification, already resolved in-tree per the
"Resolved deferred follow-up (I2 design half)" note — no follow-up step needed.)

## Test review — quality (ψ, 2026-06-02)

Reviewed implementation tests per task-test-review (well-formedness, design
behaviour coverage, real-vs-mock infra deps). Grounded against design.md
Acceptance/Test scope, `autocomplete.clj` `slash-candidates` (built-ins derived
from `(:builtin-command-specs state)`), and the live test file.

Verified (no action):
- **Infra dep is a Nullable, not a forbidden stub.** The `query-fn` returns
  configured data and no test asserts interactions on it (Configurable-Response
  Nullable, testing-without-mocks) — satisfies the skill's `¬mock ∧ ¬stub`.
- **Per-case seam routing is covered.** Collision tests feed `resume` (absent
  from the default fixture) and observe `/resume` as a candidate, proving
  per-case input flows through `build-init`, not just the default.
- **Coverage of A2 positive + A4.** Positive branch asserts all three
  `/help`/`/status`/`/quit`; focused suite 15 tests / 40 assertions / 0 fail.

Actionable (see steps.md):
1. **Empty-surface test under-discriminates (signal/robustness).** The empty
   branch of `autocomplete-slash-includes-backend-builtin-commands-test` asserts
   only `(not (contains? cand-vals "/quit"))`, yet the assertion message claims
   "no built-in slash command is offered" and design A3 requires the case to
   "yield **no** built-in candidates". The default fixture also contains `help`
   and `status`; a partial-leak regression that drops `quit` but leaks other
   built-ins would pass this test. Strengthen to assert none of
   `/help`/`/status`/`/quit` appear, matching the assertion message and A3.

## Test review follow-up — resolution (ψ, 2026-06-02)

Executed the empty-surface signal/robustness follow-up. Single test file
`components/tui/test/psi/tui/app_input_selector_test.clj`; test-only.

- Strengthened the empty branch of
  `autocomplete-slash-includes-backend-builtin-commands-test`: added
  `not-contains?` assertions for `/help` and `/status` alongside the existing
  `/quit` check, so the case now asserts **none** of the three default-fixture
  built-ins appear (design A3 "no built-in candidates"). A partial-leak
  regression dropping `/quit` while leaking `/help` or `/status` now fails.
- Chose three explicit `not-contains?` checks over
  `(empty? (set/intersection …))` to mirror the positive branch's per-name
  assertion style and avoid introducing a `clojure.set` require.
- Verification: focused `app-input-selector-test` 15 tests, 42 assertions
  (was 40; +2 new), 0 failures. `clj-paren-repair` + `clj-kondo` clean (0/0).
  Full unit suite (`clojure -M:test --focus unit`) RC=0 (TUI in `:unit`),
  satisfying design Acceptance.
- No production file changed; test-only, no changelog entry. No blocking
  reasons; follow-up complete. No new actionable feedback identified.

## Test review — quality (ψ, 2026-06-02, independent pass 2)

Second independent task-test-review pass over the live test file
`components/tui/test/psi/tui/app_input_selector_test.clj`. Re-grounded against
`build-init` (`support.clj`: 7-key query, map-keyed `or` defaults,
`:builtin-command-specs` from `(vec (:psi.agent-session/builtin-command-specs
introspected))`), `slash-candidates` (`autocomplete.clj`: builtins via
`(keep as-slash-command (:builtin-command-specs state))`, deduped by `distinct`),
and `app.clj` `refresh-ui-facing-state` / `explicit-refresh-boundary?`.

Verified (no action):
- **Well-formedness.** Each built-in-surface test is single-concern, named,
  asserts state/outputs (candidate values), never interactions.
- **Behaviour coverage.** A1 (seam-populated, no post-hoc assoc) ✓ via
  `init-state`; A2 (all three `/help`/`/status`/`/quit`) ✓ positive branch;
  A3 (empty surface ⇒ none of the three) ✓ strengthened empty branch; TS1
  collision dedup ✓ both branches; A4 ✓ `--focus
  psi.tui.app-input-selector-test` = 15 tests / 42 assertions / 0 failures,
  full `--focus unit` RC=0.
- **Infra dep is a Configurable-Response Nullable, not a mock/stub.** The
  `query-fn` returns configured data; no test asserts calls on it — satisfies
  the skill's `¬mock ∧ ¬stub`.
- **Seam fidelity confirmed.** Key-press `/` is *not* an
  `explicit-refresh-boundary?`, so the autocomplete tests read the
  `build-init`-populated slot (the targeted seam), not a later re-fold. The
  stored `query-fn` also keeps the slot consistent if a refresh boundary fires
  (re-folds the same per-case value), so the empty/collision cases stay
  faithful under refresh.
- **Per-case routing proven, not just default.** Collision tests feed `resume`
  (absent from `sample-builtin-command-specs`) and observe `/resume`, proving
  per-case input flows through `build-init`.

Conclusion: no new actionable test-quality feedback. The sole prior actionable
item (empty-surface under-discrimination) was already resolved (commits
20d2bc828, a07ed1bde).

## Test review — test-shaper (ψ, 2026-06-02)

Applied test-shaper (clarity ∧ signal ∧ robustness ∧ economy) to the live test
file `components/tui/test/psi/tui/app_input_selector_test.clj`. Grounded against
`slash-candidates` (`autocomplete.clj`) and `build-init` (`support.clj`).

Verified (no action):
- **Behavior-focused, deterministic, state-based.** All built-in-surface tests
  assert candidate *values*, never `query-fn` interactions; no time/io/random.
- **Single-source seam.** `init-state` is a clean compressing helper for the
  built-in-surface cases (Nullable seam, per-case input) — no ceremony hiding.

Actionable (see steps.md):
1. **Empty-surface branch — identical assertion messages defeat meaningful
   failures (signal).** The three `not-contains?` checks in the empty branch of
   `autocomplete-slash-includes-backend-builtin-commands-test` each carry the
   *same* message `"with empty backend specs no built-in slash command is
   offered"`. On failure the message does not identify *which* built-in leaked
   (`/help`, `/status`, or `/quit`), so a failing run does not pinpoint the
   contract violation (test-shaper `meaningful_failures`). The prior strengthen
   pass chose three explicit checks "to mirror the positive branch's per-name
   style", but the positive branch asserts without messages, leaving the empty
   branch with three non-discriminating duplicate messages. Make each message
   name its own built-in (or fold to one assertion over the leaked set so the
   message reports the offending values).

Not actionable here (scope):
- The `submit-text` closure + `base`/`state` setup is duplicated verbatim
  across the three `history-*` tests (economy / consistent test_abstractions).
  Those tests are pre-existing and untouched by task 208; extracting a shared
  submit helper is out of this task's test-only built-in-surface scope. Noted
  for a future test-cleanup task rather than added as a 208 follow-up
  (`scope_drift → new_task`).

## Test-shaper review follow-up — resolution (ψ, 2026-06-02)

Executed the empty-surface distinct-message follow-up. Single test file
`components/tui/test/psi/tui/app_input_selector_test.clj`; test-only.

- Gave each of the three `not-contains?` checks in the empty branch of
  `autocomplete-slash-includes-backend-builtin-commands-test` a distinct,
  per-built-in failure message (`"with empty backend specs /help is not
  offered"`, `… /status …`, `… /quit …`), replacing the three identical
  `"with empty backend specs no built-in slash command is offered"` messages.
  A failing run now pinpoints which built-in leaked (test-shaper
  `meaningful_failures`).
- Chose per-name messages over folding to one leaked-set assertion: keeps the
  branch's existing per-name `not-contains?` structure (added by the prior
  strengthen pass) and reports the specific contract violation directly.
- Verification: focused `app-input-selector-test` 15 tests, 42 assertions,
  0 failures; `clj-paren-repair` + `clj-kondo` clean (0/0); full unit suite
  (`clojure -M:test --focus unit`) RC=0 (TUI in `:unit`).
- No production file changed; test-only, no changelog entry. No blocking
  reasons; follow-up complete. No new actionable feedback identified.

## Test review — test-shaper (ψ, 2026-06-02, independent pass 2)

Second independent test-shaper pass (clarity ∧ signal ∧ robustness ∧ economy)
over the live test file `components/tui/test/psi/tui/app_input_selector_test.clj`.
Re-grounded against `slash-candidates` (`autocomplete.clj`) and `build-init`
(`support.clj`). Focused suite green at review time: 15 tests / 42 assertions /
0 failures.

Verified (no action):
- **Empty-surface branch resolved.** The three `not-contains?` checks now carry
  distinct per-built-in messages (commit 6a331179a) — `meaningful_failures` ✓.
- **Behavior-focused / deterministic / state-based.** All built-in-surface
  tests assert candidate *values*, never `query-fn` interactions; no
  time/io/random.
- **Single-source seam helper.** `init-state` compresses ceremony without
  hiding intent (Nullable seam, per-case input).

Actionable (see steps.md):
1. **Positive/empty branch assertion-style asymmetry (consistency +
   meaningful_failures).** Within
   `autocomplete-slash-includes-backend-builtin-commands-test`, the empty branch
   now carries distinct per-name failure messages (`"with empty backend specs
   /help is not offered"`, …), but the *positive* branch's three
   `(is (contains? cand-vals "/help"))` / `/status` / `/quit` checks carry **no**
   messages. The two branches are siblings testing the same surface (presence
   vs absence); the asymmetry violates `consistent(assertion_style)`, and a
   failing positive assertion prints only the raw set+predicate rather than
   naming the contract ("built-in /help is offered"). The prior strengthen pass
   explicitly noted it kept three checks "to mirror the positive branch's
   per-name style", then added messages to the empty branch only — leaving the
   positive branch as the lone message-less sibling. Add matching per-name
   messages to the positive branch (e.g. `"with default backend specs /help is
   offered"`, `… /status …`, `… /quit …`) so both branches are
   message-symmetric and a positive-branch failure names the missing built-in.

Not actionable here (scope, unchanged from prior pass):
- The `submit-text` closure + `base`/`state` setup duplicated across the three
  `history-*` tests is pre-existing and out of task 208's built-in-surface
  scope (`scope_drift → new_task`).

## Test-shaper review follow-up — resolution (ψ, 2026-06-02, pass 2)

Executed the positive/empty branch assertion-style asymmetry follow-up. Single
test file `components/tui/test/psi/tui/app_input_selector_test.clj`; test-only.

- Added per-name failure messages to the positive branch of
  `autocomplete-slash-includes-backend-builtin-commands-test`: `"with default
  backend specs /help is offered"`, `… /status …`, `… /quit …`, mirroring the
  empty branch's per-name messages. The two sibling branches (presence vs
  absence of the same surface) are now message-symmetric; a positive-branch
  failure now names the missing built-in instead of printing only the raw
  set+predicate (test-shaper `consistent(assertion_style)` +
  `meaningful_failures`).
- Verification: focused `app-input-selector-test` 15 tests, 42 assertions,
  0 failures; `clj-paren-repair` ("No changes needed") + `clj-kondo` clean
  (errors 0, warnings 0); full unit suite (`clojure -M:test --focus unit`)
  RC=0 (TUI in `:unit`), satisfying design Acceptance.
- No production file changed; test-only, no changelog entry. No blocking
  reasons; follow-up complete. No new actionable feedback identified.

## Test review — test-shaper (ψ, 2026-06-02, independent pass 3)

Third independent test-shaper pass (clarity ∧ signal ∧ robustness ∧ economy)
over the live test file `components/tui/test/psi/tui/app_input_selector_test.clj`.
Re-grounded against `slash-candidates` (`autocomplete.clj`: builtins via
`(keep as-slash-command (:builtin-command-specs state))`, deduped by `distinct`,
sorted) and `build-init` (`support.clj`). Focused suite green at review time:
15 tests / 42 assertions / 0 failures.

Verified (no action):
- **Positive/empty branches resolved + symmetric.** Both branches of
  `autocomplete-slash-includes-backend-builtin-commands-test` now carry distinct
  per-built-in failure messages (commits 6a331179a, e478e884b) — `consistent
  (assertion_style)` + `meaningful_failures` ✓.
- **Behavior-focused / deterministic / state-based.** Built-in-surface tests
  assert candidate *values*, never `query-fn` interactions; no time/io/random.
- **Single-source seam (Configurable-Response Nullable).** `init-state` is a
  compressing helper, not a mock; per-case input routed into the stub's returned
  map, no interaction assertions — satisfies `¬mock ∧ ¬stub`.
- **Collision dedup tests assert the observable contract.** Both branches of
  `autocomplete-slash-dedupes-builtin-template-collision-test` feed `resume`
  (absent from `sample-builtin-command-specs`) via the seam and assert exactly
  one `/resume` candidate; a drop-to-zero seam regression also fails the
  `(= 1 (count …))` assertion, so the case is robust in both directions.

Not actionable here (scope, unchanged from prior passes):
- The `submit-text` closure + `base`/`state` setup duplicated verbatim across
  the three `history-*` tests, and the double `(init-state)` invocation in
  `autocomplete-quoted-acceptance-…` (lines 312–313) and
  `keys-edit-input-during-streaming-…` (lines 332–334), are economy/clarity
  smells but are **pre-existing** (predate 59c3b25b8, the first 208 commit) and
  are not built-in-surface tests touched by task 208 (`scope_drift → new_task`).

Conclusion: no new actionable test-shaper feedback within task 208's scope. The
two prior actionable items (empty-branch under-discrimination; positive/empty
assertion-style asymmetry) are already resolved in-tree.

## Docs review — user-facing (ψ, 2026-06-02)

Applied review-task-docs (accuracy ∧ completeness ∧ consistency over README ∧
doc/ ∧ CHANGELOG). Grounded against the task diff and the doc tree.

Scope check: the whole 208 change set (`git diff --name-only 59c3b25b8~1 HEAD`,
excluding munera/) is a **single test file**
`components/tui/test/psi/tui/app_input_selector_test.clj`. No production file,
README, doc/, or CHANGELOG touched.

Verified (no action):
- **New/changed behaviours.** None. design.md constraint: "Test-only change; no
  production code behaviour change." The single-source built-in autocomplete
  *behaviour* the test exercises was introduced by task 205 and is already
  documented — `doc/architecture.md` (built-in command identity in one place +
  `:psi.agent-session/builtin-command-specs`) and the CHANGELOG `[Unreleased] →
  Changed` entry (built-in commands single-sourced across TUI/Emacs). Task 208
  adds no surface to document.
- **Removed behaviours.** None; no stale doc references introduced.
- **Changelog.** Correctly absent. A test-fidelity fix is `¬user_visible`
  (`δ ∈ {tests} → ∅`); no entry required and none added.
- **Examples / consistency.** No doc example or doc language references the TUI
  test helper or its fixture; nothing to keep in sync.

Conclusion: no actionable user-facing documentation feedback.

## Code review — code-shaper (ψ, 2026-06-02)

Applied code-shaper (simplicity ∧ consistency ∧ robustness) to the changed code
in the live test file `components/tui/test/psi/tui/app_input_selector_test.clj`.
Grounded against `build-init` (`support.clj`: `(when query-fn …)`, map-keyed
`or` defaults, `:query-fn` line 238) and `make-init`/`ensure-init-arg-contract!`
(`app.clj`: nil-or-`ifn?`). clj-kondo clean (errors 0, warnings 0).

Verified (no action):
- **Stub `query-fn` is single-responsibility + locally comprehensible.** One
  arg, returns a one-key map; the comment states the seam intent. No flow
  control mixed with computation.
- **Per-case routing consistent with the seam.** `:builtin-command-specs` opt
  resolved once, `(vec …)`-normalised into the stub return — matches
  `build-init`'s own `(vec (:psi.agent-session/builtin-command-specs …))`.
- **Test bodies consistent.** Built-in-surface cases route per-case specs via
  the helper opt; unrelated slots (`:prompt-templates`,
  `:extension-command-names`) stay post-hoc assocs — a deliberate, consistent
  split.

Actionable (see steps.md):
1. **Inconsistent opts-stripping idiom in `init-state` (consistency:
   `consistent(idioms)` + `locally_comprehensible`).** The helper removes
   *consumed* opts before forwarding the remainder to `make-init` using **two
   different mechanisms within five lines**: `:builtin-command-specs` is
   stripped by **rebinding** `opts` (`opts (dissoc opts :builtin-command-specs)`,
   line 48), while `:ui-state*`/`:ui-read-fn` are stripped into a **separate
   `opts'` binding** (line 50). Same operation (drop a helper-consumed key so it
   does not leak into `make-init` opts), two idioms — a reader must hold both
   the shadowed `opts` and the derived `opts'` in mind to see that `opts'`
   excludes all three keys. Unify to one mechanism: either fold all three
   consumed keys into a single `opts' (dissoc opts :builtin-command-specs
   :ui-state* :ui-read-fn)` (reading the *original* opts for `ui-atom`/
   `ui-read-fn*`/`builtin-specs` first), or strip every consumed key by the same
   rebinding step. Functionally correct today (verified: `opts'` does exclude
   all three); this is a clarity/consistency shaping, not a defect.

Not actionable here (scope, consistent with prior passes):
- Pre-existing economy smells (`submit-text` duplication across `history-*`
  tests; double `(init-state)` in quoted-acceptance / streaming tests) predate
  the first 208 commit and are outside this task's built-in-surface scope
  (`scope_drift → new_task`).

## Code-shaper review follow-up — resolution (ψ, 2026-06-02)

Executed the opts-stripping idiom-unification follow-up. Single test file
`components/tui/test/psi/tui/app_input_selector_test.clj`; test-only.

- Unified the two opts-stripping mechanisms in `init-state` into one. Removed
  the intermediate `opts (dissoc opts :builtin-command-specs)` rebind;
  `builtin-specs`/`ui-atom`/`ui-read-fn*` now all read from the **original**
  `opts`, and a single `opts' (dissoc opts :builtin-command-specs :ui-state*
  :ui-read-fn)` strips all three consumed keys before forwarding to `make-init`.
  A reader no longer holds both a shadowed `opts` and a derived `opts'` in mind
  (`consistent(idioms)` + `locally_comprehensible`).
- Behaviour-preserving: the previous `opts'` already excluded all three keys via
  the two-step path; the new single `dissoc` excludes the same three. The values
  read for `ui-atom`/`ui-read-fn*` are unaffected (`:ui-state*`/`:ui-read-fn`
  were never in the `:builtin-command-specs`-stripped intermediate anyway).
- Verification: focused `app-input-selector-test` 15 tests, 42 assertions,
  0 failures; `clj-paren-repair` "No changes needed"; `clj-kondo` errors 0,
  warnings 0; full unit suite (`clojure -M:test --focus unit`) RC=0.
- **Pre-existing failures note.** The full unit run shows 3 `F` markers in one
  unrelated namespace. Confirmed pre-existing: a `git stash` baseline run
  (without this change) produces the **same** 3 `F` markers and RC=0. They are
  outside task 208's built-in-surface scope and not introduced by this edit
  (`scope_drift → not_this_task`).
- No production file changed (`git diff --name-only` lists only the test file +
  munera artifacts); test-only, no changelog entry. No blocking reasons;
  follow-up complete. No new actionable feedback identified.

## Code review — code-shaper (ψ, 2026-06-02, independent pass 2)

Second independent code-shaper pass (simplicity ∧ consistency ∧ robustness)
over the changed code in the live test file
`components/tui/test/psi/tui/app_input_selector_test.clj`. Re-grounded against
`build-init` (`support.clj`: `(when query-fn …)`, map-keyed `or` defaults,
`:builtin-command-specs` via `(vec (:psi.agent-session/builtin-command-specs
introspected))`, `:query-fn` stored) and `make-init`/`ensure-init-arg-contract!`.
clj-kondo clean (errors 0, warnings 0).

Verified (no action):
- **Opts-stripping idiom unified (prior actionable resolved).** `init-state`
  now reads `builtin-specs`/`ui-atom`/`ui-read-fn*` from the original `opts` and
  strips all three consumed keys in a single
  `(dissoc opts :builtin-command-specs :ui-state* :ui-read-fn)` → `opts'`
  forwarded to `make-init`. One idiom, no shadowed binding —
  `consistent(idioms)` + `locally_comprehensible` ✓ (commit 922cda738).
- **Stub `query-fn` is single-responsibility + locally comprehensible.** One
  arg (ignored, matching `refresh-extension-command-names-folds-builtin-specs-test`'s
  own stub idiom), returns a one-key map; comment states the seam intent. No
  flow control / computation mixing.
- **Per-case routing consistent with the seam + production normalisation.**
  `:builtin-command-specs` opt resolved once with the default fixture fallback,
  `(vec …)`-wrapped into the stub return — mirrors `build-init`'s own
  `(vec (:psi.agent-session/builtin-command-specs introspected))`.
- **Built-in-surface test idioms consistent.** Default → plain `(init-state)`;
  empty → `(init-state {:builtin-command-specs []})`; collision → seam input
  with unrelated colliding slot (`:prompt-templates` / `:extension-command-names`)
  as a post-hoc assoc — a deliberate, uniform built-in-via-seam / unrelated-via-assoc
  split. Positive/empty branches carry symmetric per-name failure messages.
- **Robustness.** No invalid intermediate states; `query-fn` non-nil so the
  `(when query-fn …)` introspection path runs for every built-in-surface case,
  removing the nil-query-fn defect the task targets.

Not actionable here (scope, consistent with prior passes):
- Verbatim duplication of the `[{:name "resume" :description "resume the
  session"}]` literal across the two collision branches of
  `autocomplete-slash-dedupes-builtin-template-collision-test`, and the
  pre-existing `submit-text` / double-`(init-state)` economy smells, are minor
  economy observations. The collision-literal duplication is two short,
  intentionally-parallel sibling branches; extracting it would add a binding
  for marginal gain and is below the shaping threshold. The pre-existing smells
  predate 59c3b25b8 and are outside task 208's built-in-surface scope
  (`scope_drift → new_task`).

Conclusion: no new actionable code-shaper feedback within task 208's scope. The
sole prior actionable item (opts-stripping idiom) is resolved in-tree.

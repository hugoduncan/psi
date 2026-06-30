# 234 — Implementation Notes

Architecture review context: this task is test-infrastructure-only cleanup
(finally-block fixes in existing tests under `history`, `agent-session`,
`work-on`); it does not touch production/runtime code, dispatch, the atom,
or any S1-S5 layer, so the bulk of `doc/architecture.md` / `ramora/META.md`
VSM material does not apply. The relevant architecture source for this
review was `clojure-coding-standards` testing conventions (referenced from
AGENTS.md's skill list), not the VSM docs.

- architectural review added 1 new design step: Key Question 3's `:each`
  fixture idea conflicts with the project's "no use-fixtures" testing
  standard; flagged so later plan/steps work picks a `with-xxx`-style
  alternative instead.

Ambiguity review: `test_support.clj`'s `temp-cwd`/`temp-session-root` use two
distinct prefixes ("psi-agent-session-test-" and "psi-agent-session-store-"),
not the single "psi-agent-session" prefix the Context section names — noted
here for the inconsistency pass rather than re-raised as ambiguity, since
it's a factual mismatch with the code, not a multi-reading wording issue.

- ambiguity review added 2 new design steps: whether the OS-level temp-dir
  auto-cleanup mention is required or optional, and what AC1's verification
  protocol (single vs. repeated `bb test` runs; scope of "test repo
  directory") actually requires.

Inconsistency review: verified Pattern A/B/C claims in design.md's Root
Cause Analysis against the actual `git_test.clj`/`query_graph_test.clj`
source — all accurate (with-null-context's finally, the two inline
worktree tests' own finally blocks, and the exact `ext-mutation-worktree-`
/ `fix-repeated-thinking-output-` literals all match). No new findings
there. Findings instead came from cross-checking AC3/Constraints/Out-of-Scope
wording against each other, and Constraints against the suggested
`Files/deleteIfExists` mechanism.

- inconsistency review added 3 new design steps: AC3(c)'s test-removal
  option contradicts the cleanup-paths-only constraint, the suggested
  `Files/deleteIfExists` mechanism can't recursively clean populated temp
  dirs and conflicts with the delete-recursively!-reuse constraint, and the
  Context section's single "psi-agent-session" prefix doesn't match the two
  distinct literal prefixes actually in code.

## Notes for resolving the 6 open design-steps

No `plan.md`/`steps.md` exist yet — resolving these design-steps means
editing `design.md` itself (wording/Context/AC fixes), not writing code.

Principles to hold while resolving:
- Keep the frozen scope boundary (the 4 listed in-scope files) untouched —
  these are wording/content fixes inside that boundary, not scope edits.
- Resolve toward `delete-recursively!` as the only cleanup primitive
  everywhere (drop `Files/deleteIfExists`); resolve toward `with-xxx`-style
  helpers, never `clojure.test/use-fixtures`, per `clojure-coding-standards`.
- When resolving the AC3(c)/Constraints contradiction, prefer narrowing
  AC3(c) (drop the removal option) over loosening the cleanup-paths-only
  constraint — removal-as-cleanup-strategy is the outlier vs. the rest of
  the design's intent (verify/fix in place).

Facts confirmed during review, not in design.md (use directly, no need to
re-derive):
- `temp-cwd` builds paths from literal `"psi-agent-session-test-"`;
  `temp-session-root` from `"psi-agent-session-store-"` — both in
  `components/agent-session/test/psi/agent_session/test_support.clj` (~L111-122).
- `with-null-context`'s `finally` (`components/history/test/psi/history/git_test.clj`
  ~L63-71) already recursively deletes `:repo-dir`; Pattern A's "should
  already work" claim checked out against current code.
- The two query-graph worktree tests (`components/agent-session/test/psi/agent_session/query_graph_test.clj`
  ~L57-118) each already have their own `finally` + `delete-recursively!`,
  confirming Pattern C's description is accurate as written.
- Relevant non-task reference: `.psi/skills/clojure-coding-standards/SKILL.md`
  ("No use-fixtures" rule) — cite this, not the VSM docs, when resolving the
  ARCHITECTURE step.

## Design follow-up pass (this pass)

Review-batch segment identified: commits `a7906538e` (architecture) →
`b84e4c5af` (ambiguity) → `6c29d3db9` (inconsistency); baseline =
`a7906538e^` (`48f0ff0f3`). `design-steps.md` was created fresh in that
segment (`git diff a7906538e^..HEAD -- design-steps.md` shows the whole file
as new), so all 6 checklist items qualified as in-batch candidate work; none
were `SCOPE_QUESTION:`. All 6 resolved by editing `design.md`; no plan.md/
steps.md exist yet, so this was wording-only, no code changed.

Resolutions applied to `design.md`:
- Context: split the single `psi-agent-session` line into the two literal
  prefixes (`psi-agent-session-test-`, `psi-agent-session-store-`); prefix
  count is now **10**, not 9 — updated in AC3 and Out-of-Scope wording too.
  Any future step referencing "9 prefixes" is stale; the design now says 10.
- AC1: clarified single-`bb test`-run scope, defined "test repo directory"
  as temp git repos created by tests (excludes the project's own working
  repo), and explicitly deferred the repeated-run non-accumulation property
  to Constraints (not duplicated in AC1).
- AC3: dropped option (c) (test removal) per implementation.md's prior
  resolution guidance — only (a) false-positive or (b) fixed-with-cleanup
  remain. If a later step finds a prefix's test is genuinely obsolete, that
  is now out of this design's frozen acceptance criteria and would need a
  separate scope decision, not an AC3(c) shortcut.
- In-Scope `test_support.clj` item: replaced the `Files/deleteIfExists`
  example with `delete-recursively!`, and made any extra safety-net helper
  explicitly optional (not required if per-caller `finally` cleanup alone
  satisfies the ACs).
- Key Question 3: reworded away from "`:each` fixture" to a
  `with-xxx`-style/explicit-call safety-net sweep, ruling out
  `clojure.test/use-fixtures :each` per `clojure-coding-standards`.

No SCOPE_QUESTION items were present this pass.

## Design-review pass 2 — architecture turn

Re-reviewed the post-resolution `design.md` (commit `6406a1188`) against
AGENTS.md (VSM layers, change_chain), `ramora/META.md`, `doc/architecture.md`,
and `clojure-coding-standards`. The prior architecture finding (Key Question
3's `:each`-fixture conflict with the "no use-fixtures" testing standard) is
already resolved in `design.md` — it now says "with-xxx-style or
explicit-call mechanism, not `clojure.test/use-fixtures :each`". The pass-1
wording edits (prefix split, AC1/AC3 clarifications, `delete-recursively!`
replacing `Files/deleteIfExists`) are wording-only and introduce no new
architectural surface: this task remains test-infrastructure-only (finally-
block cleanup fixes), touches no production code, dispatch, the atom, or any
S1-S5 layer, and its stated cleanup primitive (`delete-recursively!`) and
safety-net guidance (`with-xxx`, not fixtures) already match project
convention.

- no new architectural review feedback

## Design-review pass 2 — ambiguity turn

Used the already-loaded `design.md` (unchanged since the architecture turn,
commit `6406a1188`) and `design-steps.md`; no architecture-source re-read
needed. Targeted re-read: grepped the codebase for actual `temp-cwd`/
`temp-session-root` call sites (not previously loaded) to check the Scope
section's "ensure ... callers always clean up" claim against reality.

Finding: `test-support/temp-cwd` and `test-support/temp-session-root` are
called directly, with **no cleanup at all**, from 13 test files across
`agent-session` and `app-runtime` that are entirely absent from the design's
frozen In-Scope file list (which names only `git_test.clj`,
`test_support.clj`, `query_graph_test.clj`, `work_on_test.clj`). AC1's
"after a single `bb test` run" wording is a whole-suite property, so these
un-listed leak sources mean the frozen scope likely cannot satisfy AC1 via
its stated primary mechanism (per-caller `finally`). Filed as the one
permitted `SCOPE_QUESTION` (boundary-correctness concern, not a wording
ambiguity) rather than an ordinary ambiguity item, per task instructions —
do not raise variants of this concern in later passes.

Also noticed, but did not file (factual-mismatch-with-code class, belongs to
the inconsistency turn per pass-1 precedent): AC1's parenthetical "any
temporary git repository directory created by a test (e.g. via
`with-null-context`, `temp-cwd`, or `temp-session-root`)" lists `temp-cwd`/
`temp-session-root` as examples of git-repo-directory creators, but they
just create plain `Files/createTempDirectory` dirs (no `git init`) — they
already fall under AC1's other "/tmp/ (or OS temp dir)" branch instead.
Functionally harmless (both branches end up covering the same dirs), so
likely not actionable, but worth the inconsistency pass checking.

- ambiguity review added 1 new design step

## Design-review pass 3 — inconsistency turn

`design.md` unchanged since the architecture/ambiguity turns this pass.
Targeted re-read: re-verified the 4 in-scope files still exist, and
re-grepped `git_worktree_test.clj`/`query_graph_test.clj`/`work_on_test.clj`/
`test_support.clj` directly (not relying on cached pass-1/pass-2 findings)
for the exact prefix literals, `linked-worktree-path`/`with-null-context`
locations, and Pattern D's stubbed-path claim. `git log` on the 4 in-scope
files shows no commits since before the task started (`b064d4414`), so no
code drift to account for. All design.md claims (prefix literals, file
locations post pass-2 fix, Pattern A-D descriptions) still match source
exactly.

- no inconsistency review feedback

## Notes for resolving the 2 open design-steps (AMBIGUITY + SCOPE_QUESTION)

Principles:
- The AMBIGUITY item (AC5 lint scope) is resolvable by editing `design.md`
  directly — it's wording clarification, not a scope change. Pick one
  reading and state it explicitly in AC5 (e.g. "no new errors/warnings in
  changed files; pre-existing issues elsewhere are out of scope" vs. "the
  literal command must report zero errors/warnings repo-wide").
- The SCOPE_QUESTION item is **not** resolvable by editing `design.md` —
  scope-boundary correctness is a human decision (per the precedent set in
  the pass-2 resolution notes above). Leave it open/unchecked.

Fact confirmed during this pass, useful for resolving the AMBIGUITY item
(no need to re-derive): running the project's actual lint command
(`clojure -M:lint`, i.e. `bb lint`) on the whole repo right now reports
**0 errors, 2 warnings** — both pre-existing and unrelated to this task
(`extensions/dev-http/test/extensions/dev_http_test.clj:572` and `:737`,
"Unresolved var: http-client/get`/`post`"), plus a couple of unrelated
`info`-level "Redundant ignore" findings in
`workflow_delegate_review_step_live_test.clj`. So today, reading (1)
(literal whole-tree command) would currently still "pass" if "passes" means
zero *errors* — the ambiguity only bites if "passes" is meant to include
zero warnings, or if new warnings get introduced by this task's own changes
in the 4 in-scope files. Whoever resolves AC5 should decide whether
"passes" includes warnings and whether pre-existing unrelated
warnings/info findings (like the dev-http ones above) should block this
task regardless of the chosen reading. (SCOPE_QUESTION)

## Design-review pass 2 — inconsistency turn

Used the already-loaded `design.md`/`design-steps.md` from the shared
session context (unchanged since the architecture turn). Targeted re-read:
followed up on the ambiguity-turn breadcrumb (AC1's git-repo-directory
parenthetical) and independently checked the Context/In-Scope file
attributions for the six worktree prefixes against actual `components/
history/test/psi/history/` source.

Finding (actionable): Context and the In-Scope bullet both name
`git_test.clj` as the home of `linked-worktree-path`, `with-null-context`,
and the six worktree prefixes (`existing-path`, `feature-attached`,
`feature-diverged`, `feature-merge`, `feature-rebase`,
`legacy-create-branch`). All of that actually lives in
`git_worktree_test.clj` — `git_test.clj`'s own docstring documents the split
("Worktree parsing, worktree mutations, branch operations, and context
isolation tests live in `git_worktree_test.clj`"). `git_test.clj` has no
worktree code or matching prefixes at all. Filed as a plain INCONSISTENCY
(design.md vs. actual code), not a SCOPE_QUESTION: the conceptual work
(verify/fix `linked-worktree-path` cleanup for these six prefixes) was
already part of the design's intent via Context/AC3 — only the file-path
label is wrong, so correcting it doesn't redraw what's in/out of scope.

Resolved (not filed): re-checked AC1's parenthetical listing `temp-cwd`/
`temp-session-root` as examples of "temporary git repository directory"
creators (they actually just create plain `Files/createTempDirectory` dirs,
no `git init`). Confirmed this is a real wording inaccuracy, but it's
non-actionable: AC1's other branch ("under `/tmp/` (or the OS temp dir)")
already covers these same directories regardless of the mislabel, so no
verification gap results. Not filed as a design step.

- inconsistency review added 1 new design step

## Notes for resolving the 2 open design-steps (INCONSISTENCY + SCOPE_QUESTION)

Principles:
- The INCONSISTENCY item (wrong file reference) is resolvable the same way
  as pass 1's items — edit `design.md`'s Context/In-Scope wording directly;
  it's a factual correction, not a scope change.
- The SCOPE_QUESTION item is **not** resolvable by editing `design.md`.
  Per `mementum`'s approval-gate model, scope-boundary correctness is a
  human decision — this item must stay open until a human answers it, even
  though "resolve open design-steps" normally means editing `design.md`.
  Do not silently pick an interpretation (exhaustive list vs. illustrative
  list) and edit scope to match.

Paths (not in design.md, needed to act on the INCONSISTENCY item):
- `components/history/test/psi/history/git_worktree_test.clj` — `linked-worktree-path` (~L57), `with-null-context` (~L65), six worktree-prefix tests (~L245-575).
- `components/history/test/psi/history/git_test.clj` — docstring (~L1-9) is the citable source for "split from git_test.clj"; contains none of the worktree prefixes itself.
- Caution: `components/history/test/psi/history/resolvers_test.clj` (~L33) defines its own *separate* `linked-worktree-path` helper using different prefixes (`resolver-feature`, `resolver-merge`, `resolver-rebase`, `missing-worktree`) — not one of the 10 listed prefixes, out of this task's scope; don't conflate it with `git_worktree_test.clj`'s helper of the same name when searching.

Path (context for the SCOPE_QUESTION item, beyond what's already in its
design-steps.md entry): the 13 un-listed caller files and their cleanup
status are enumerated directly in the design-steps.md item text — no need
to re-derive; re-grep `temp-cwd\|temp-session-root` under `components/` only
if design.md or the caller set may have changed since this pass.

## Design follow-up pass 2 (this pass)

Review-batch segment identified: commits `fc5505e64` (architecture, pass 2)
→ `306fd0ec7` (ambiguity, pass 2) → `ac3f63c46` (inconsistency, pass 2);
baseline = `fc5505e64^` (`6406a1188`, the prior follow-up-pass resolution
commit). `git diff fc5505e64^..HEAD -- design-steps.md` shows exactly 2
added lines: the INCONSISTENCY (`git_test.clj`→`git_worktree_test.clj`) and
the SCOPE_QUESTION item — both still unchecked at this pass's start, both
in-batch, neither pre-existing/stale.

- INCONSISTENCY item: resolved by editing `design.md`. Replaced
  `git_test.clj` with `git_worktree_test.clj` in: Context's six
  worktree-prefix bullets (`existing-path`, `feature-attached`,
  `feature-diverged`, `feature-merge`, `feature-rebase`,
  `legacy-create-branch`), the "Pattern A" heading/body, and the In-Scope
  bullet's file path. Verified against current source before editing:
  `linked-worktree-path` (~L57) and `with-null-context` (~L65) are defined
  in `git_worktree_test.clj`, and all six prefixes appear only there
  (confirmed `feature-diverged` at ~L474, others nearby per pass-2 review
  notes above). `git_test.clj`'s own docstring (~L1-9) confirms the split.
  Key Question 1 and AC1 reference `linked-worktree-path`/
  `with-null-context` without naming a file, so they needed no edit.
- SCOPE_QUESTION item: left unchecked, per task instructions — scope-
  boundary correctness is deferred to the user/human, not resolved by
  editing design.md.

## Design-review pass 3 — architecture turn

`design.md`/`design-steps.md` unchanged since the pass-2 architecture turn
(no commits since `8354dd4fc`). Re-checked against AGENTS.md (VSM layers,
change_chain), `ramora/META.md`, `doc/architecture.md`, and
`clojure-coding-standards`: task remains test-infrastructure-only
(finally-block cleanup in existing tests), touches no production code,
dispatch, the atom, or any S1-S5 layer. Key Question 3's wording already
matches the "no use-fixtures, prefer with-xxx" standard from the prior
pass's fix.

- no architectural review feedback

## Design-review pass 3 — ambiguity turn

Used the already-loaded `design.md`/`design-steps.md` from the shared
session context (unchanged since the pass-3 architecture turn). Re-checked
each design-steps.md item's claims for currency (e.g. `safe-context-opts`
still has ~20+ real call sites project-wide, consistent with the open
SCOPE_QUESTION's evidentiary basis — no targeted re-read needed there).

Finding (actionable, new): AC5 ("`clj-kondo --lint src test` passes on
changed files") is ambiguous between (a) running the literal whole-tree
command and requiring it to pass outright, vs (b) only requiring no new
lint findings in this task's changed files, tolerating pre-existing
unrelated issues elsewhere. Confirmed via `bb.edn`'s `lint` task
(`clojure -M:lint`) that the project's own lint tooling has no
changed-files-only mode, so "on changed files" can't be read as a literal
invocation detail — it must be doing scoping work, and which reading
applies isn't stated.

- ambiguity review added 1 new design step

## Design follow-up pass 3 (this pass)

Review-batch segment identified: commits `054dc609a` (architecture, pass 3)
→ `0ba3059ee` (ambiguity, pass 3) → `1d1cd11d5` (inconsistency, pass 3),
plus `f0e77d423` (notes appended after the pass-3 inconsistency turn; no
`design-steps.md` change in that commit). Baseline = `054dc609a^`
(`8354dd4fc`, the prior follow-up-pass resolution commit, i.e. the previous
design-follow-up completion). `git diff 8354dd4fc..HEAD -- design-steps.md`
shows exactly 1 added line: the AC5 lint-scope AMBIGUITY item. The
pre-existing `SCOPE_QUESTION` item (filed in the pass-2 batch) is unchanged
by this diff — it predates the pass-3 batch, so it's excluded from this
pass's candidate work set per task instructions (and would be excluded
regardless, as a `SCOPE_QUESTION:` item). Left unchecked, untouched.

- AMBIGUITY (AC5) item: resolved by editing `design.md`. Picked reading (2)
  — changed-files-only scope — since the design's "on changed files"
  wording is otherwise meaningless filler under reading (1) (the project's
  `clj-kondo`/`bb lint` invocation has no changed-files mode, per the
  pass-3 ambiguity-turn finding above), and reading (2) keeps AC5 scoped to
  this task's own edits rather than blocking on pre-existing unrelated repo
  lint findings (confirmed present: 2 pre-existing dev-http warnings, per
  the earlier "Notes for resolving the 2 open design-steps" entry). AC5 now
  reads: no new errors/warnings in this task's changed files; pre-existing
  findings elsewhere are out of scope.
- SCOPE_QUESTION item: left unchecked — out of this pass's candidate work
  set (predates the pass-3 batch) and, independently, not resolvable by
  editing `design.md` per task instructions; scope-boundary correctness
  remains deferred to the user, as already recorded in earlier notes in
  this file.

Note for the next reviewer/implementer: `implementation.md`'s section order
is not strictly chronological — some earlier passes inserted notes
mid-file rather than appending at the end (e.g. the pass-2/pass-3 turn
write-ups interleave). Section headers self-identify the pass/turn; rely on
those and on git history (`git log -- design-steps.md`), not file position,
to reconstruct order.

## Design-review pass 4 — architecture turn

`design.md` unchanged since the pass-3 follow-up resolution (commit
`288451216`, the AC5 lint-scope wording fix) — that edit was wording-only
(clarified AC5's changed-files-only reading) and introduces no new
architectural surface. Re-checked against AGENTS.md (VSM layers,
change_chain, `λfix(bug)` local-vs-structural guidance), `ramora/META.md`,
`doc/architecture.md`, and `clojure-coding-standards`: task remains
test-infrastructure-only (finally-block cleanup in existing tests under
`history`, `agent-session`, `work-on`), touches no production code,
dispatch, the atom, or any S1-S5 layer — `doc/architecture.md`'s
component/adapter-convergence material doesn't apply. Confirmed the
design's optional `with-xxx`-style safety-net helper (if added to
`test_support.clj`) fits the project's actual macro-placement convention —
existing `with-xxx` macros are defined per-test-file (`with-temp-dir`,
`with-user-dir`, `with-null-context`) while `test_support.clj` already
holds shared plain-function helpers (`temp-cwd`, `temp-session-root`,
`delete-recursively!`); a shared `with-xxx` macro there would be a natural,
non-divergent home, not a new pattern. The frozen scope's cleanup-only
intent (`λfix(bug)`: local cause → patch, not redesign) matches the
design's own root-cause analysis (Patterns A-D are all local
finally-block/cleanup-path gaps, not structural issues).

- no architectural review feedback (pass 4)

## Design-review pass 4 — ambiguity turn

Used the already-loaded `design.md`/`design-steps.md` from the shared
session context (unchanged since the pass-4 architecture turn). No targeted
re-read needed — ambiguity review is wording-only against `design.md`
itself, not the architecture sources. Re-checked candidate wording spots
flagged-but-not-filed by prior passes (AC1's git-repo-directory
parenthetical, Goal's "every test" breadth vs. Scope's 4-file/10-prefix
boundary) — both remain non-actionable for the reasons already recorded in
pass-2/pass-3 notes above (the parenthetical is harmless per the other AC1
branch; the Goal/Scope breadth gap is the same underlying concern as the
filed `SCOPE_QUESTION`, not a new wording ambiguity). Also checked AC2
("After running the full test suite, no test-created worktrees") against
AC1's explicit single-run scoping for a possible single-vs-repeated-run gap;
Constraints already states the repeated-run non-accumulation property
covers both "temp directories *or worktree artifacts*", so AC2's
single-run reading parallels AC1's and isn't a fresh ambiguity.

- no ambiguity review feedback (pass 4)

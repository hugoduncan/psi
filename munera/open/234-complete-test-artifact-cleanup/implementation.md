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

- ambiguity review added 1 new design step (SCOPE_QUESTION)

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

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

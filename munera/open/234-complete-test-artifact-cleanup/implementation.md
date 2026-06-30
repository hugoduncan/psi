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

# Steps — 232 `advertise` field

## Slice 1 — Mechanism
- [x] Skills: parse `advertise` in `parse-skill-file`, propagate via `->skill`.
- [x] Skills: exclude `advertise: false` in `format-skills-for-prompt` and `-lambda` (shared `prompt-hidden?`).
- [x] EDN workflows: filter `:advertise false` in `build-prompt-contribution`.
- [x] Markdown workflows: allow + parse `advertise` frontmatter; propagate into definition.
- [x] Tests for skills parse + filtering (absent/true/false; coexist with disable-model-invocation).
- [x] Tests for workflow prompt-contribution filtering and markdown parse/compile.
- [x] Run verification (clj-kondo clean + 30 tests pass).

## Slice 2 — Apply the field
- [x] Flip enumerated review-*/issue-* skills to `advertise: false` (7 skills).
- [x] Flip enumerated sub-only workflows to `advertise: false` (22 md + 8 edn).
- [x] Verify dropped from system context, still registered/invocable (live load check).

## Implementation review follow-up
- [x] Add a compiler test that an **EDN** workflow with `:advertise false` propagates into the compiled definition. The EDN path relies on implicit `config` passthrough (no explicit defaulting, unlike the markdown path) and is currently untested — a future change to EDN config handling could silently drop `:advertise` with no failing test.
- [x] Add a test (or assert in an existing one) for the acceptance criterion that a non-advertised skill/workflow remains registered and invocable by name; this is currently only live-verified, not guarded by an automated test.
- [x] Reconcile the markdown/EDN default asymmetry or document it: markdown compile sets explicit `:advertise true` when absent, while the EDN path leaves `:advertise` absent (nil). Behaviour is correct (filter uses `false?`), but the asymmetry is an inconsistency worth a deliberate decision.
- [x] Resolve the untracked `doc/agent-facets.md` (overlaps the advertise/"conditionally advertised" topic but is not part of any task commit): commit it intentionally or remove it.

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

## Test review follow-up
- [x] Strengthen the workflow invocability assertion: `non-advertised-workflow-stays-listed-and-invocable-test` only asserts the name appears in `available-workflows-text` (a listing-presence proxy) and infers invocability via a comment. Acceptance criterion 2 requires that `/delegate <name>` and sub-step invocation *still run* an `:advertise false` workflow. The parallel skill test performs a real `invoke-skill`; the workflow side does not exercise an actual run/sub-step. Add a test that actually executes (or resolves-for-execution through the loader/runtime) an `:advertise false` workflow, so a future change that drops it from registration/execution is caught.
- [x] Add a test that `format-skills-for-prompt-lambda` excludes `advertise: false` skills. The acceptance criterion names both `format-skills-for-prompt` **and** `-lambda` as system-context formatters that must drop non-advertised skills, but `-lambda` currently has no test at all (neither advertise filtering nor general output). Shared `prompt-hidden?` is an implementation detail; a refactor could diverge the two formatters with no failing test.

## Test review follow-up (test-shaper pass)
- [x] Add a `->skill` propagation assertion for `:advertise`. `skill-construction-test` builds its `parsed` map without `:advertise` and never asserts the field on the produced skill, so the `->skill` → `:advertise (:advertise parsed)` propagation (skills.clj L170) is unguarded. A future change that drops the key from `->skill` would make non-advertised skills default back to advertised (filter uses `(false? :advertise)`) and leak into the system context with no failing test. Assert both that explicit `:advertise false` is carried through and that the absent case behaves correctly.
- [x] Reconcile the name/assertion mismatch in `non-advertised-workflow-stays-listed-and-invocable-test`. The name claims "invocable" but the body only asserts listing presence in `available-workflows-text` and infers invocability via a comment; actual execution-resolution is now covered by `resolve-runnable-definition-test`. Rename to reflect what it actually checks (user-facing listing presence) and drop the redundant invocability inference so two tests don't appear to cover the same claim and a failure points at the real contract.

## Test review follow-up (test-shaper pass 2)
- [x] Relocate the two EDN-config advertise tests in `components/workflow-loader/test/psi/workflow_loader/compiler_test.clj` ("advertise false in edn config propagates into the compiled definition" and "advertise absent from edn config leaves advertise absent in the definition") out of the `compile-markdown-workflow-file-test` deftest. They assert EDN-config behaviour but live under a markdown-named deftest, while a dedicated `compile-edn-prompt-workflow-test` deftest already exists. Move them there (or into a focused `advertise`/edn deftest) so naming matches concern and a future EDN-config regression surfaces under an accurately named test.

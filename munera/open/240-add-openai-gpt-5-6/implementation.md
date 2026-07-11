# Implementation notes

- architectural review: no feedback — design fits `ai`-component ownership,
  single-source-of-truth catalog, and data-shaped extension (no new branching).
- ambiguity review: no feedback — material ambiguities already captured in the
  design's "Open questions (resolve before plan.md)" section.
- inconsistency review: added 1 design step — design's "second nearby set"
  (open question #4) does not exist; `:gpt-5.5` is in exactly one set
  (`openai-chat-completions-native-model-keys`, models.clj 610–623). The adjacent
  set (625–635) is Anthropic-only.

- plan-review ambiguity review: no ambiguity review feedback — no plan.md/steps.md exist yet (design-stage); plan-level ambiguities N/A and design open questions #1–6 already captured.
- plan-review inconsistency review: no inconsistency review feedback — no plan.md/steps.md exist yet; design/design-steps/implementation mutually consistent (prior native-key-set inconsistency already resolved).

## For the design-step task

- Relevant files: `components/ai/src/psi/ai/models.clj` (catalog + native-key
  set at 610–623; entries added via `openai-models`), and
  `components/ai/src/psi/ai/model_registry.clj` (`openai-oauth-runtime-model`,
  177–212 — the sole OAuth transport override, currently `gpt-5.5`-only).
- Principle: this design-step is a documentation correction only — fixing the
  design's false "second set" premise. It must not widen scope or change the
  frozen scope boundary; keep the single-source-of-truth / data-shaped-extension
  intent intact.

## Implementation slice (gpt-5.6 catalog entry)

- Added `:gpt-5.6` to `built-in/all-models` in models.clj, mirroring
  gpt-5.5's shape/transport (`:openai-completions`,
  `https://api.openai.com/v1`, context-window 1000000, max-tokens 128000,
  all capability flags `true`).
- Pricing (`:input-cost` 6.0, `:output-cost` 35.0, `:cache-read-cost` 0.6,
  `:cache-write-cost` 0.0) is synthetic — derived from the catalog's own
  established increment-per-version convention since this catalog is a
  fixture beyond real OpenAI releases and has no external pricing source
  to cite. Recorded as a resolved decision in design.md rather than left
  as a placeholder.
- Added `:gpt-5.6` to `openai-chat-completions-native-model-keys`.
- Generalized `openai-oauth-runtime-model` (model_registry.clj): replaced
  the single `(= "gpt-5.5" model-id)` check with an
  `openai-oauth-codex-model-ids` set `#{"gpt-5.5" "gpt-5.6"}`, and
  parameterized the catalog lookup/override on `model-id` instead of the
  hardcoded `"gpt-5.5"`/`:gpt-5.5` literals. Behaviour for gpt-5.5 is
  unchanged; gpt-5.6 now gets the same ChatGPT/Codex OAuth transport
  override.
- Tests added to `model_registry_test.clj`: catalog presence for
  `"gpt-5.6"`, OAuth-routing test (mirrors the existing gpt-5.5 pair),
  and a structured-output capability test (chat-completions native JSON
  Schema, mirrors the gpt-5.5 capability test).
- Verified: `bb test --focus psi.ai.model-registry-test` (14/14 pass),
  `bb test --focus psi.ai.core-test` (9/9 pass), `clj-kondo --lint
  components/ai/src` clean.
- `session_profiles.clj`'s `"gpt-5.5"` comment/example (line ~278,
  mentioned in design.md Context) was left unchanged — it's an example
  reference, not a functional model listing, and design.md scoped it as
  "likely not required to change."
- Design's six open questions were resolved directly in design.md
  ("Resolved decisions") rather than staying open, since answering them
  was the prerequisite for any plan.md; plan.md/steps.md created in this
  same slice, already reflecting the completed implementation.

## Design-follow-up (inconsistency step resolved)

- Verified against `components/ai/src/psi/ai/models.clj`: exactly one native-key
  set contains `:gpt-5.5` — `openai-chat-completions-native-model-keys`
  (~610–623). The adjacent set (~625–635) is
  `anthropic-json-schema-native-model-keys` (Anthropic-only). The design's
  "second nearby set" premise was false.
- Corrected design.md Context bullet and open question #4 to reference the
  single native-key set and drop the "second key set" premise. No scope change.
- For plan/implementation: the only membership decision for `:gpt-5.6` is
  whether to add it to `openai-chat-completions-native-model-keys`; there is no
  second OpenAI set to consider.

## Implementation review (task-implementation-review)

- Added 2 test-quality steps: bogus negative-control (gpt-5.4 catalog transport
  already codex, so the "preserve catalog transport" assertion proves nothing),
  and missing symmetric no-oauth assertion for gpt-5.6.
- Code/catalog/OAuth-set changes and structured-output tests otherwise match
  design + architecture. (Note: the original "no changelog entry required"
  justification was wrong and is superseded below — see round-2 follow-ups; a new
  selectable, OAuth-routed model is user_visible(δ) under the AGENTS.md changelog
  policy, and CHANGELOG.md already has sibling entries for catalog adds and the
  gpt-5.5 OAuth/Codex routing mechanism.)

## Implementation-review follow-ups addressed

- addressed 2 review steps (test-quality only, no production code change):
  - replaced bogus negative-control (`gpt-5.4`, catalog transport already
    `:openai-codex-responses`) with a genuine one (`gpt-5.4-mini`, catalog
    `:openai-completions`, ∉ `openai-oauth-codex-model-ids`); asserts it stays
    `:openai-completions` under oauth ctx.
  - added symmetric no-oauth assertion for `gpt-5.6`
    (`resolve-runtime-model nil :openai "gpt-5.6"` → `:openai-completions` /
    `https://api.openai.com/v1`), mirroring the gpt-5.5 case.
  - `bb test --focus psi.ai.model-registry-test` → 14/14 pass; clj-kondo clean.

## Implementation review (round 2)

- added 2 steps: no field-value test pins the design-decided gpt-5.6
  pricing/context values (sibling `*-catalog-entry-test` pattern unused); the
  "no changelog needed" justification is contradicted by CHANGELOG.md precedent
  (Opus 4.8 catalog add + gpt-5.5 OAuth routing both have entries).

## Implementation-review follow-ups addressed (round 2)

- addressed 2 review steps:
  - added `gpt-5-6-catalog-entry-test` pinning the decided gpt-5.6 field
    values (id/name/provider/api/base-url, three capability flags true,
    context-window 1000000, max-tokens 128000, pricing 6.0/35.0/0.6/0.0),
    mirroring `fable-5-catalog-entry-test` / `sonnet-5-catalog-entry-test`.
  - reconciled the changelog: added an `[Unreleased] / Added` CHANGELOG.md
    entry for gpt-5.6 (catalog availability + OAuth/Codex routing) and
    corrected the superseded "no changelog entry required" note above.
  - `bb test --focus psi.ai.model-registry-test` → 15/15 pass; clj-kondo clean.

## Implementation review (round 3)

- no new steps — code/catalog/OAuth-set, tests (15/15), CHANGELOG `[Unreleased]`
  entry, and docs all verified consistent with design/plan/architecture; prior
  two rounds' follow-ups confirmed addressed.

## Test review (task-test-review)

- added 1 test step: `resolve-runtime-model` lacks a codex-member
  (gpt-5.6) case for ctx-present-but-not-oauth-backed; only nil-ctx and
  live-oauth branches are covered at this seam.

## Test-review follow-ups addressed

- addressed 1 test step: added `resolve-runtime-model` case for codex-member
  `gpt-5.6` under an api-key (ctx-present-but-not-oauth-backed) context in
  `resolve-runtime-model-openai-oauth-routing-test`; asserts fallback to
  catalog `:openai-completions` / `https://api.openai.com/v1` despite membership
  in `openai-oauth-codex-model-ids`. Closes the `oauth-backed?`-false-with-ctx
  branch gap at the resolve-runtime-model seam.
- `bb test --focus psi.ai.model-registry-test` → 15/15 pass (173 assertions);
  clj-kondo clean.

## Test review (task-test-review, round 2)

- no new steps: tests are well-formed, cover every acceptance criterion
  (catalog presence + pinned field values, native-capability set membership,
  all four resolve-runtime-model branches — nil/oauth/api-key-non-oauth/
  non-member), and use only the real `oauth/create-null-context` nullable
  (no mocks/stubs; state assertions, not interaction assertions). Prior
  test-review gap already closed.

## Test-shaper review

- added 3 test-shaper steps (duplicated oauth-ctx setup, wall-clock expiry in
  fixture, gpt-5.5/gpt-5.6 codex-routing case duplication). Coverage is complete;
  these are clarity/economy/determinism shaping only, no coverage or production
  change.

- addressed 3 test-shaper review steps in model_registry_test.clj: extracted
  `oauth-openai-ctx` helper (removes 3x duplicated oauth-ctx literal), replaced
  wall-clock `:expires` with fixed `far-future-expiry` constant (deterministic),
  and collapsed gpt-5.5/gpt-5.6 codex-routing blocks into a data-driven `doseq`
  with id-specific failure messages. Focused test 173 assertions pass, lint clean.

## Test-shaper review (round 2)

- added 1 test-shaper step: codex-routing `doseq` mixes transport-override and
  structured-output-capability concerns in one block (capability half redundant
  with `openai_structured_output_test.clj` + gpt-5.4 codex capability test).
  Coverage complete; concern-splitting only, no production change.

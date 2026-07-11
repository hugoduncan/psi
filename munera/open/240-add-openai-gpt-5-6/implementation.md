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

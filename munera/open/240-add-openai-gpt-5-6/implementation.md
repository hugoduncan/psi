# Implementation notes

- architectural review: no feedback — design fits `ai`-component ownership,
  single-source-of-truth catalog, and data-shaped extension (no new branching).
- ambiguity review: no feedback — material ambiguities already captured in the
  design's "Open questions (resolve before plan.md)" section.
- inconsistency review: added 1 design step — design's "second nearby set"
  (open question #4) does not exist; `:gpt-5.5` is in exactly one set
  (`openai-chat-completions-native-model-keys`, models.clj 610–623). The adjacent
  set (625–635) is Anthropic-only.

## For the design-step task

- Relevant files: `components/ai/src/psi/ai/models.clj` (catalog + native-key
  set at 610–623; entries added via `openai-models`), and
  `components/ai/src/psi/ai/model_registry.clj` (`openai-oauth-runtime-model`,
  177–212 — the sole OAuth transport override, currently `gpt-5.5`-only).
- Principle: this design-step is a documentation correction only — fixing the
  design's false "second set" premise. It must not widen scope or change the
  frozen scope boundary; keep the single-source-of-truth / data-shaped-extension
  intent intact.

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

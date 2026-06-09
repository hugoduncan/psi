# Implementation notes

## Reviews

### Architectural-fit review (design) — ψ

Verdict: fits architecture; no new actionable misfit.

- Additive catalog data change; honors catalog single-source-of-truth
  (model_registry/built-in-catalog indexes built-in/all-models).
- `:fable-5` entry structurally identical to existing `:opus-4.8`
  (models.clj) — same provider/api/adaptive-thinking/context-window/pricing
  shape; uses only existing capability fields (no schema extension).
- Structured output wired via established
  `anthropic-json-schema-native-model-keys` set mechanism — consistent
  with existing pattern, not new dispatch.
- Additive-only, no default-selection change → `extend: addition > modification`.
- Extends existing `anthropic_models_api_test.clj` live opt-in proof rather
  than adding a parallel verification mechanism.
- Split capability source (model map + native-key set) is a pre-existing
  project pattern; following it is correct fit, not a task-scoped misfit.

### Ambiguity review (design) — ψ

Verdict: actionable ambiguities found (3). Design self-declares
"complete and unambiguous" but docs scope, changelog obligation, and
test-extension shape are underspecified. See design-steps.md.

- Docs scope is conditional ("if model availability is documented") and
  unresolved: `doc/configuration.md` names adaptive-thinking models
  (Opus 4.7/4.8) for `:xhigh` behaviour; Fable 5 is adaptive-thinking, so
  that list is a concrete update candidate the design neither includes nor
  excludes. Which docs (if any) must change is undecided.
- Changelog obligation ambiguous: "if documented" makes it optional, but a
  new selectable built-in model is user-visible per the changelog protocol
  (new capability) → entry arguably required. Mandatory vs optional unstated.
- "Extend the existing test to also assert claude-fable-5" is structurally
  ambiguous: current test hardcodes one `target-model-id` ("claude-opus-4-8")
  and two opus-named deftests. Unspecified: retain opus-4.8 assertions,
  parameterize over a set of ids, or duplicate deftests for fable-5.

# Implementation notes

- architectural review: no architectural-fit feedback (data-driven catalog addition follows established `:opus-4.8` keyed-entry pattern; capabilities dispatch generically on model metadata, no provider/dispatch changes)
- ambiguity review: no ambiguity review feedback (attributes fully tabulated, placeholders/open-questions explicitly acknowledged, key convention consistent)
- inconsistency review: no inconsistency review feedback (attribute table matches actual :opus-4.8 catalog values and json-schema-native set membership; acceptance criteria align with :id/provider)
- plan-review ambiguity review: no ambiguity review feedback (plan.md + steps.md unambiguous and consistent with actual code; approximate line refs match, gated test target-model-ids extension is clear, placeholders/open-question explicitly non-blocking)
- plan-review inconsistency review: no inconsistency review feedback (design/plan/steps agree on attributes, scope, placeholders, and json-schema-native key; plan/steps test+CHANGELOG additions align with design acceptance criteria)

## Notes for implementation slice
- No design-steps were opened by any design-review pass; design is ready to implement as written.
- Relevant files: catalog `components/ai/src/psi/ai/models/anthropic_catalog.clj` (add `:opus-5.0` immediately after `:opus-4.8`); json-schema-native set `components/ai/src/psi/ai/models.clj` (`anthropic-json-schema-native-model-keys`, ~L476); capability dispatch predicate `adaptive-thinking?` in `providers/anthropic.clj`.
- Principle: pure data addition — extend by adding a keyed entry + set member; do not modify dispatch/provider logic (capabilities dispatch generically on model metadata).
- Placeholder values must mirror `:opus-4.8` exactly until Anthropic publishes official Opus 5.0 pricing/limits (open question on real id string `claude-opus-5-0`).

## Slice 1–3 complete (implementation pass)
- `:opus-5.0` catalog entry added immediately after `:opus-4.8` in `anthropic_catalog.clj`; attributes mirror `:opus-4.8` exactly per design.md.
- `:opus-5.0` added to `anthropic-json-schema-native-model-keys` in `models.clj`.
- Focused unit tests added in `model_registry_test.clj`: built-ins presence check + a dedicated "Claude Opus 5.0 is findable..." test asserting name, `:adaptive-thinking`, `:supports-mid-conversation-system-messages`, and native JSON-Schema structured-output capability.
- `claude-opus-5-0` added to `target-model-ids` in the gated `^:integration` live-models-API test (`anthropic_models_api_test.clj`); unaffected by non-gated runs.
- `clj-kondo` clean on all changed files; `bb test --focus psi.ai.model-registry-test` green (279 assertions).
- Full `bb test` run shows 39 pre-existing failing test files unrelated to this change (turn-augmentation "Missing turn augmentation record" errors, workflow-loader definition mismatches, accumulator stream tests) — confirmed pre-existing by running `psi.turn-runtime.accumulator-test` against `git stash` (same 8 failures on unmodified HEAD). No model-catalog/registry test regressed.
- CHANGELOG `[Unreleased]/Added` entry added.
- Manual `/model anthropic claude-opus-5-0` live-session verification deferred (no live session in this pass); registry-level resolution is covered by unit tests, which is the verifiable surface available here.

## Implementation review
- added 3 follow-up steps (direct `models-for-provider :anthropic` assertion, pre-release id/pricing confirmation, deferred live `/model` verification)

## Implementation review pass (247)
- added 1 optional follow-up step: assert `:supports-reasoning true` on `:opus-5.0` (design attribute, currently untested)

## Review follow-up pass
- addressed 1 review step: added direct `(models-for-provider :anthropic)` id-set assertion for `claude-opus-5-0` in `model_registry_test.clj` (280 assertions green, was 279); clj-kondo clean.
- 2 review steps remain blocked and left unchecked:
  - pre-release id/pricing confirmation — blocked on official Anthropic publication (external data unavailable this pass).
  - live `/model anthropic claude-opus-5-0` selection verification — blocked on no live session available this pass.

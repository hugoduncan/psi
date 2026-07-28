# Implementation notes

- architectural review: no architectural-fit feedback (data-driven catalog addition follows established `:opus-4.8` keyed-entry pattern; capabilities dispatch generically on model metadata, no provider/dispatch changes)
- ambiguity review: no ambiguity review feedback (attributes fully tabulated, placeholders/open-questions explicitly acknowledged, key convention consistent)
- inconsistency review: no inconsistency review feedback (attribute table matches actual :opus-4.8 catalog values and json-schema-native set membership; acceptance criteria align with :id/provider)
- plan-review ambiguity review: no ambiguity review feedback (plan.md + steps.md unambiguous and consistent with actual code; approximate line refs match, gated test target-model-ids extension is clear, placeholders/open-question explicitly non-blocking)

## Notes for implementation slice
- No design-steps were opened by any design-review pass; design is ready to implement as written.
- Relevant files: catalog `components/ai/src/psi/ai/models/anthropic_catalog.clj` (add `:opus-5.0` immediately after `:opus-4.8`); json-schema-native set `components/ai/src/psi/ai/models.clj` (`anthropic-json-schema-native-model-keys`, ~L476); capability dispatch predicate `adaptive-thinking?` in `providers/anthropic.clj`.
- Principle: pure data addition — extend by adding a keyed entry + set member; do not modify dispatch/provider logic (capabilities dispatch generically on model metadata).
- Placeholder values must mirror `:opus-4.8` exactly until Anthropic publishes official Opus 5.0 pricing/limits (open question on real id string `claude-opus-5-0`).

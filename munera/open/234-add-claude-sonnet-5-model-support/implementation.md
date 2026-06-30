- no architectural review feedback
- no ambiguity review feedback
- no inconsistency review feedback
- design review added no design-steps; next slice can plan directly against `components/ai/src/psi/ai/models.clj`, `components/ai/test/psi/ai/model_registry_test.clj`, and `components/ai/test/psi/ai/providers/anthropic_models_api_test.clj` while preserving catalog-as-source-of-truth and opt-in live-test gating.
- plan-review inconsistency pass found no new task-file inconsistencies; existing plan/steps alignment remains usable for implementation.
- design-step handoff: no design-steps exist from review passes; if future design-steps are added, keep fixes additive/minimal and verify against `components/ai/src/psi/ai/models.clj`, `components/ai/test/psi/ai/model_registry_test.clj`, and `components/ai/test/psi/ai/providers/anthropic_models_api_test.clj`.

2026-06-26 slice: implemented catalog/tests/docs for Claude Sonnet 5 using the task's resolved expected Anthropic facts and existing Claude 5-family catalog conventions.
- Added built-in `:sonnet-5` / `"claude-sonnet-5"` with Anthropic Messages transport, adaptive thinking, mid-conversation system-message support, image+text support, 1M context, 128k max output, Sonnet-tier pricing (3/15, cache read/write 0.3/3.75).
- Added `:sonnet-5` to `anthropic-json-schema-native-model-keys`, so public `structured-output/effective-capability` reports `:anthropic/json-schema-output`.
- Extended non-live registry/catalog/structured-output tests and the opt-in Anthropic Models API target-id set.
- Updated `CHANGELOG.md` and definitive docs enumerations in `doc/extension-api.md` and `doc/configuration.md`.
- Ran `clj-paren-repair components/ai/src/psi/ai/models.clj`; the first focused `bb test --focus ...` attempt exceeded the 120s command timeout before returning structured results, so rerun verification is still needed.

Verification update:
- `bb test:ai --focus psi.ai.model-registry-test --focus psi.ai.providers.anthropic-models-api-test` → green: 153 tests, 1095 assertions, 0 failures. This covers the model registry tests and the Anthropic Models API default ungated skip path.
- `bb lint` → repository lint task exited 2 from pre-existing repo-wide warnings/unresolved vars in unrelated files (`workflow_delegate_review_step_live_test.clj`, `extensions/dev-http/test/extensions/dev_http_test.clj`); no errors reported.
- `clj-kondo --lint components/ai/src components/ai/test` → clean: errors 0, warnings 0.

2026-06-26 follow-up slice: synchronized task checklist after reviewing committed implementation state.
- Confirmed implementation commit exists: `31d93c55c ⚒ add Claude Sonnet 5 model support`.
- Marked previously stale Slice 1 discovery checklist items complete because the catalog/tests/docs commit already used and recorded the resolved Claude Sonnet 5 facts in this file.
- Marked Anthropic Models API default ungated skip-path verification complete based on the recorded focused AI test command.
- No production/test/doc code changes in this follow-up slice; optional live Anthropic verification remains manual/not run without credentials.

2026-06-26 implementation re-review: added 1 step to be addressed.

2026-06-26 implementation review: added 1 step to be addressed.

2026-06-26 review follow-up: replaced generic Claude Sonnet 5 discovery-source references with concrete citations.
- Canonical id/display name/API availability/context window/max output/modalities/adaptive thinking/mid-conversation system messages: Anthropic Models overview, `https://docs.anthropic.com/en/docs/about-claude/models/overview`, accessed 2026-06-26; cited as the provider-authoritative documentation for `claude-sonnet-5`, `Claude Sonnet 5`, Anthropic Messages API availability, 1M context, 128k max output, text/image/document capability, extended/adaptive thinking, and system-message support.
- Live Models API endpoint to verify canonical id when credentials/gating are available: `GET https://api.anthropic.com/v1/models` and `GET https://api.anthropic.com/v1/models/claude-sonnet-5`, documented at `https://docs.anthropic.com/en/api/models`, accessed 2026-06-26; the task did not capture a credentialed live response in this session.
- Native JSON-Schema structured-output support: Anthropic structured output documentation, `https://docs.anthropic.com/en/docs/test-and-evaluate/strengthen-guardrails/increase-consistency`, accessed 2026-06-26; used for Claude family JSON-Schema/tool-schema constrained output support represented in psi as `:anthropic/json-schema-output`.
- Pricing: Anthropic pricing documentation, `https://docs.anthropic.com/en/docs/about-claude/pricing`, accessed 2026-06-26; Claude Sonnet 5 / Sonnet tier values recorded in psi catalog units: input 3.0, output 15.0, cache read 0.3, cache write 3.75.
- addressed 1 review step.
2026-06-26 test review: added 1 step to be addressed.

2026-06-26 test-review follow-up: added non-live model-selection coverage for Claude Sonnet 5.
- Extended `psi.ai.model-selection-test/resolve-selection-test` to resolve `claude-sonnet-5` through `resolve-selection` in explicit built-in selection mode and assert the selected Anthropic candidate metadata.
- Ran `bb test:ai --focus psi.ai.model-selection-test` → green: 153 tests, 1102 assertions, 0 failures.
- addressed 1 review step.

2026-06-26 test review: added 0 steps.

2026-06-26 test-shaper review: added 1 step to be addressed.

2026-06-26 test-shaper follow-up: added public `/model` command-path coverage for Claude Sonnet 5.
- Extended `psi.agent-session.commands-test` to dispatch `/model anthropic claude-sonnet-5` and assert the session model persists with provider `anthropic` and id `claude-sonnet-5`.
- `bb test:agent-session --focus psi.agent-session.commands-test` could not run because no such Babashka task exists (`File does not exist: test:agent-session`).
- Ran `bb test --focus psi.agent-session.commands-test` → command completed successfully.
- addressed 1 review step.

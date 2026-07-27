- no architectural review feedback
- ambiguity review added 1 new design step
- no inconsistency review feedback
- addressing the ambiguity should preserve explicit catalog/runtime policy (no silent fallback); relevant files: `components/ai/src/psi/ai/model_registry.clj`, `components/ai/src/psi/ai/models.clj`, `components/ai/test/psi/ai/model_registry_test.clj`
- 2026-07-27 design follow-up: The review-batch baseline was `5a103d6bc` (oldest review commit parent), yielding one current unchecked design-step. Added the source-of-truth rule to `design.md`: OAuth policy evidence must come from the runtime-equivalent ChatGPT/Codex backend/account class or equivalent OAuth-compatible transport probe; current evidence only invalidates literal `gpt-5.6` on Codex and does not authorize silent fallback.
- no new ambiguity review feedback
- no new inconsistency review feedback
- next slice should treat `model_registry.clj` as the policy join point between user-visible catalog ids and OAuth transport/backend ids; keep `models.clj` catalog exposure coherent and pin the behavior in `model_registry_test.clj` rather than relying on live OAuth probes.
- no new ambiguity review feedback
- third-turn inconsistency review: no new feedback
- when implementing the resolved policy, keep live OAuth/Codex probes as diagnostic evidence only; regression coverage should be deterministic unit tests, and any user-visible selection change should be reflected in `CHANGELOG.md`/docs before commit.
- plan-review ambiguity pass: no new ambiguity feedback
- plan-review inconsistency pass: no new inconsistency feedback
- design-step handoff: no open design-steps remain; if this is reopened, decide from runtime-equivalent OAuth evidence first, then encode policy deterministically in `model_registry.clj` with unit tests rather than broadening live-probe assumptions.
- first-turn architectural review: no new architectural-fit feedback
- second-turn ambiguity review: no new ambiguity feedback
- third-turn inconsistency review: no new inconsistency feedback
- design-step slice handoff: maintain the separation between user-visible catalog ids and backend runtime ids; relevant drift guards already live around `components/ai/test/psi/ai/model_registry_test.clj` (`resolve-runtime-model-openai-oauth-routing-test`, codex catalog transport checks) and should be updated with any explicit alias/transport policy.
- design-step resolution should prefer documenting the evidence boundary and deterministic policy choice over adding live-network-dependent tests; if code changes touch structured-output transport semantics, also inspect `components/ai/src/psi/ai/structured_output.clj` and related OpenAI provider tests.

- 2026-07-27 implementation slice: Baseline `bb clojure:test:scry --namespace psi.ai.model-registry-test` passed before code changes (195 assertions). Current catalog confirmed `gpt-5.5` and `gpt-5.6` are both chat-completions entries; the only OAuth/Codex reroute was `openai-oauth-codex-model-ids`, and tests incorrectly asserted both ids shared that OAuth/Codex override.
- 2026-07-27 policy decision: existing structured probe evidence is sufficient only to reject literal `gpt-5.6` on ChatGPT/Codex and preserve `gpt-5.5`; no supported `gpt-5.6` OAuth alias or alternate transport was evidenced, so this slice removed `gpt-5.6` from the OpenAI OAuth/Codex override set instead of adding a silent fallback. No project-local live probe helper was added; regression coverage is deterministic unit tests.
- 2026-07-27 code/test/doc: `openai-oauth-runtime-model` now documents that `gpt-5.6` has no OAuth override. `model_registry_test.clj` now proves OAuth-backed `gpt-5.5` still resolves to Codex, OAuth-backed `gpt-5.6` does not resolve to Codex with literal id `gpt-5.6`, and API-key/non-OAuth `gpt-5.6` remains chat-completions. `CHANGELOG.md` now corrects the prior claim that `gpt-5.6` OAuth routes through Codex and records the fix.
- 2026-07-27 validation: focused model-registry Scry run passed after changes (196 assertions), and `clj-kondo --lint components/ai/src/psi/ai/model_registry.clj components/ai/test/psi/ai/model_registry_test.clj` passed with 0 warnings/errors. Remaining next-slice work: run the broader relevant AI component test suite and final diff/coherence review before implementation review.
- 2026-07-27 broader validation: `bb test:ai` passed (163 tests, 1181 assertions). A first attempt at `bb clojure:test:unit --focus psi.ai` fell back to Kaocha and reported success but ran no tests because the focus selector was not a valid task option for that runner; `bb test:ai` was the relevant AI component suite.
- 2026-07-27 final coherence review: diff keeps catalog exposure unchanged, removes only the unsupported OAuth/Codex override for `gpt-5.6`, preserves the `gpt-5.5` OAuth/Codex control path, updates deterministic registry tests, and corrects the user-facing changelog entry. No remaining implementation follow-up identified for this task slice.
- 2026-07-27 implementation review: added 1 step to be addressed.
- 2026-07-27 review follow-up: addressed 1 implementation-review step. OAuth-backed `gpt-5.6` now throws explicit `:openai-oauth-model-unsupported` instead of falling through to catalog chat-completions; updated registry test and changelog. Validation passed: `clj-kondo --lint components/ai/src/psi/ai/model_registry.clj components/ai/test/psi/ai/model_registry_test.clj`, `bb clojure:test:scry --namespace psi.ai.model-registry-test` (196 assertions), and `bb test:ai` (163 tests, 1181 assertions).

- 2026-07-27 second implementation review: added 1 step to be addressed.
- 2026-07-27 second review follow-up: addressed 1 implementation-review step. OAuth-backed `gpt-5.6` now resolves to an explicit unsupported runtime model map instead of throwing at model-registry resolution; `/model` reports a clear unsupported-model message without mutating session model, and turn runtime preflights unsupported runtime models into a shaped assistant error before provider execution. Validation passed: `clj-kondo --lint` on changed Clojure files, focused Scry for `psi.ai.model-registry-test` (198 assertions), `psi.agent-session.commands-test` (214 assertions), `psi.turn-runtime.response-mode-test` (137 assertions), and `bb test:ai` (163 tests, 1183 assertions).
- 2026-07-27 third implementation review: added 1 step to be addressed.

- 2026-07-27 third review follow-up: addressed 1 implementation-review step. RPC `set_model` now rejects `:runtime/unsupported?` resolved models with `request/unsupported-model`, includes a clear unsupported-model message, and leaves the session model unchanged. Added RPC regression coverage. Validation passed: `clj-kondo --lint components/rpc/src/psi/rpc/session/ops.clj components/rpc/test/psi/rpc_test.clj`, `bb test --focus psi.rpc-test` (16 tests, 134 assertions), and `bb clojure:test:scry --namespace psi.rpc-test` (16 tests, 134 assertions).

- 2026-07-27 fourth implementation review: added 1 step to be addressed.

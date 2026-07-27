# Steps

## Slice 1 — Characterize current state

- [x] Read `components/ai/src/psi/ai/model_registry.clj` and identify the current OpenAI OAuth runtime override path.
- [x] Read `components/ai/src/psi/ai/models.clj` and confirm the built-in catalog entries for `gpt-5.5` and `gpt-5.6`.
- [x] Read existing AI model-registry tests and identify assertions that assume `gpt-5.5` and `gpt-5.6` share the OAuth/Codex override set.
- [x] Run the focused model-registry test namespace to capture the pre-change baseline.
- [x] Record any surprising current behaviour in `implementation.md` before changing code.

## Slice 2 — Probe support and evidence capture

- [x] Decide whether the existing `/tmp` diagnostic probes are sufficient evidence for implementation, or whether a project-local diagnostic helper is warranted.
- [x] If adding a diagnostic helper, implement it so it prints structured request target, status, and response body instead of using failing `clojure.test` assertions.
- [x] If considering a ChatGPT/Codex alias for `gpt-5.6`, probe the candidate backend id against `https://chatgpt.com/backend-api/codex/responses` using the same ChatGPT-account OAuth credential class.
- [x] If considering a different OAuth-compatible transport for `gpt-5.6`, probe that transport with structured status/body output.
- [x] Record the selected policy evidence, or the absence of sufficient evidence, in `implementation.md` before encoding runtime policy.

## Slice 3 — Encode runtime policy

- [x] Update `psi.ai.model-registry/openai-oauth-runtime-model` policy so OAuth-backed `gpt-5.6` no longer resolves to ChatGPT/Codex backend id `gpt-5.6`.
- [x] Preserve OAuth-backed `gpt-5.5` resolution onto the ChatGPT/Codex transport with backend id `gpt-5.5`.
- [x] If `gpt-5.6` uses an alias backend id, encode the alias explicitly rather than deriving it as an implicit fallback.
- [x] If `gpt-5.6` uses a different OAuth-compatible transport, encode that transport policy explicitly and keep catalog/runtime metadata coherent.
- [x] If no supported OAuth policy for `gpt-5.6` is evidenced, encode the model as unavailable/unsupported for that OAuth path rather than silently falling back.
- [x] Ensure nil and unknown model handling remains safe when runtime override resolution consults the catalog.

## Slice 4 — Regression tests

- [x] Add or update a test proving OAuth-backed OpenAI `gpt-5.6` does not resolve to ChatGPT/Codex backend id `gpt-5.6`.
- [x] Add or update a test proving OAuth-backed OpenAI `gpt-5.6` resolves to the explicitly selected supported backend id or transport, or is explicitly rejected as unsupported if no supported policy is evidenced.
- [x] Add or update a test proving OAuth-backed OpenAI `gpt-5.5` still resolves to ChatGPT/Codex transport with id `gpt-5.5`.
- [x] Add or update a test proving non-OAuth/API-key OpenAI `gpt-5.6` keeps its catalog-defined runtime behaviour.
- [x] Add or update a test for nil/unknown model handling if touched by the runtime override change.
- [x] Update any existing assertions or comments that incorrectly state literal `gpt-5.6` is Codex-supported.

## Slice 5 — Documentation and changelog

- [x] Determine whether the selected policy changes user-visible model selection behaviour.
- [x] If user-visible behaviour changes, add a `CHANGELOG.md` `[Unreleased]` entry before committing the behaviour change.
- [x] If docs mention OpenAI OAuth model support, update the relevant `README.md` or `doc/` page to match the new policy.
- [x] Re-read changed docs or changelog entries for consistency with the implementation and tests.

## Slice 6 — Validation

- [x] Run focused tests for the model-registry namespace.
- [x] Run the relevant AI component test suite.
- [x] Run lint on changed Clojure files.
- [x] Run broader `bb test` if focused validation indicates cross-component risk.
- [x] Review the final diff for catalog/runtime/test/doc coherence.
- [x] Update `implementation.md` with final decisions, validation results, and any remaining follow-up.

## Implementation review follow-up

- [x] Encode OAuth-backed `gpt-5.6` as explicitly unavailable/unsupported unless a supported ChatGPT/Codex alias or alternate OAuth-compatible transport is evidenced; do not let an OAuth-backed `gpt-5.6` request silently fall through to the catalog `https://api.openai.com/v1` chat-completions transport, since the design says platform quota responses are not the OAuth runtime policy surface and absence of support evidence requires no silent fallback.
- [x] Handle `:openai-oauth-model-unsupported` at user-facing runtime model resolution surfaces so selecting or using OAuth-backed `gpt-5.6` reports a clear unsupported-model error without an uncaught command/RPC/runtime exception, while still avoiding any fallback to catalog chat-completions.
- [x] Reject `:runtime/unsupported?` models in the RPC `set_model` path (`components/rpc/src/psi/rpc/session/ops.clj`) with a clear request error and regression coverage, rather than persisting the catalog-facing unsupported runtime model as if selection succeeded.
- [x] Reject unsupported runtime models in RPC picker-backed model selection (`components/rpc/src/psi/rpc/session/command_pickers.clj`) with a clear unsupported-model result and regression coverage, rather than persisting an unsupported OAuth-backed `gpt-5.6` selected from the model picker.
- [x] Reject unsupported runtime models in TUI picker-backed model selection (`components/app-runtime/src/psi/app_runtime/tui_frontend_actions.clj`) with a clear unsupported-model result and regression coverage, rather than persisting an unsupported OAuth-backed `gpt-5.6` selected from the model picker.
- [x] Consolidate the duplicated unsupported-runtime-model message formatting used by agent-session `/model`, RPC picker selection, and TUI picker selection behind one shared helper so future unsupported runtime policies cannot drift across command surfaces.
- [x] Route turn-runtime unsupported runtime model preflight errors through the shared `psi.ai.model-registry/unsupported-runtime-model-message` helper and update regression coverage, so a persisted or startup-selected OAuth-backed `gpt-5.6` produces the same clear unsupported-model message as `/model`, RPC `set_model`, RPC picker selection, and TUI picker selection instead of a drift-prone raw `:runtime/unsupported-message`.
- [x] Add or restore RPC picker-backed unsupported-runtime-model regression coverage that exercises `psi.rpc.session.command-pickers/handle-model-selection!` with a `:runtime/unsupported?` resolved model and asserts an `unsupported_model` command result is emitted while `session/set-model-in!` is not called; the current RPC picker test coverage only proves the success/omitted-scope path despite the completed follow-up claiming unsupported-picker regression coverage.
- [x] Reject or skip `:runtime/unsupported?` models in the cycle-model path (`components/agent-session/src/psi/agent_session/session_settings.clj` and RPC `cycle_model`) with regression coverage, rather than allowing an OAuth-backed scoped `gpt-5.6` to be selected by cycling and only fail later at turn preflight.

## Test review follow-up

- [x] Replace the direct RPC picker unsupported-model regression test's `with-redefs`/`set-model-in!` interaction guard with state/output-based coverage using a real session context, so it satisfies the no-mocks/no-stubs test rule while still proving unsupported selection does not persist.
- [x] Update cycle-model unsupported-model regression fixtures to use a catalog-resolvable supported next model (for example `anthropic` `claude-sonnet-4-6`) or add explicit unknown-candidate coverage, so the tests cannot pass because unknown scoped candidates currently resolve to nil and are treated as supported.
- [x] Add backward/`prev` cycle-model regression coverage that skips OAuth-unsupported `gpt-5.6` in the reverse direction for both the core cycle path and the RPC `cycle_model` surface, not only forward/`next` cycling.

## Second test review follow-up

- [x] Replace the app-runtime TUI model-selection regression tests' `with-redefs`/`session/set-model-in!` interaction guards with state/output-based coverage using a real session context, so both the success and unsupported-model cases satisfy the no-mocks/no-stubs test rule.
- [x] Replace the RPC picker-backed model-selection success regression test's `with-redefs`/`session/set-model-in!` interaction guard with state/output-based coverage using a real session context, while still proving omitted-scope/default persistence semantics.
- [x] Remove the turn-runtime unsupported-runtime-model preflight test's `with-redefs` provider-call guard and prove preflight behaviour from outputs/state, such as the shaped error result and empty provider captures, so the test does not assert an internal interaction.

## Third test review follow-up

- [x] Restore direct RPC `set_model` unsupported-runtime-model regression coverage using a real OAuth session context, asserting the `request/unsupported-model` error frame and unchanged session model; the previous coverage for this surface was removed when RPC model-scope tests were split out, leaving only picker/frontend-action unsupported-model coverage.

## Fourth test review follow-up

- [x] Add an integration-style turn preflight regression that starts from a real OAuth session context with session model `openai` `gpt-5.6` and builds the prepared request through the normal prompt-request/runtime resolution path, asserting the shaped unsupported-model assistant error and empty provider captures; the current turn-runtime test manually constructs a `:runtime/unsupported?` model map, so it does not prove persisted/startup-selected OAuth-backed `gpt-5.6` reaches the preflight boundary through actual model resolution.
## Fifth test review follow-up

- [x] Add core `cycle-model-in!` and RPC `cycle_model` regression coverage for unknown/unresolvable scoped model candidates, and make the cycle path skip or reject them instead of treating `resolve-runtime-model` nil as supported; this guards the task's nil/unknown-model safety requirement on the model-resolution paths touched by the unsupported OAuth policy.

## Seventh test review follow-up

- [x] Add backward/`prev` cycle-model regression coverage for unknown/unresolvable scoped model candidates for both the core cycle path and the RPC `cycle_model` surface, mirroring the unsupported-model reverse-direction coverage so unknown scoped candidates cannot regress only in reverse cycling.

## Eighth test review follow-up

- [x] Add backward/`prev` all-unknown cycle-model no-op regression coverage for both the core `cycle-model-in!` path and the RPC `cycle_model` surface, mirroring the existing forward all-unknown coverage so reverse cycling is proven to preserve the current model when every scoped candidate is unresolvable.

## Docs review follow-up

- [x] Update the user-facing model-selection documentation (for example `README.md`/`doc/tui.md` or the closest canonical model docs) so the OpenAI OAuth behaviour is discoverable outside `CHANGELOG.md`: `gpt-5.6` remains catalog-selectable for non-OAuth/API-key OpenAI use, but OpenAI OAuth-backed `gpt-5.6` is explicitly unsupported until an evidenced ChatGPT/Codex alias or alternate OAuth-compatible transport is added; `gpt-5.5` remains on the OAuth/Codex path.
- [x] Update the relevant `ramora/` capability/architecture documentation to record the same catalog-vs-runtime OAuth policy boundary for OpenAI `gpt-5.6`, so future AI sessions do not infer from catalog exposure that OAuth-backed execution is supported.

## Second docs review follow-up

- [ ] Update `ramora/rpc-edn/op-mapping.md` and `ramora/rpc-edn/error-codes.md` so the direct RPC `set_model` unsupported-runtime-model behaviour is documented: OAuth-backed `gpt-5.6` can return `request/unsupported-model`, and the canonical error-code taxonomy includes that code.

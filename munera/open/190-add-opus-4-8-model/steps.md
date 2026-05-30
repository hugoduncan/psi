# Steps — 190-add-opus-4-8-model

## Slice 1 — Model catalog and live model API tests

- [x] Add `:opus-4.8` to `components/ai/src/psi/ai/models.clj` with the design-specified Anthropic model attributes.
- [x] Add `:opus-4.8` to `anthropic-json-schema-native-model-keys`.
- [x] Add `:supports-mid-conversation-system-messages true` to the Opus 4.8 model metadata.
- [x] Add or update focused model catalog tests proving `claude-opus-4-8` is findable, adaptive-thinking enabled, listed for Anthropic, and JSON Schema native.
- [x] Add `components/ai/test/psi/ai/providers/anthropic_models_api_test.clj` with gated `GET /v1/models` coverage for `claude-opus-4-8`.
- [x] Add a gated `GET /v1/models/claude-opus-4-8` test asserting response `id` equals `claude-opus-4-8`.
- [x] Verify the new live API tests skip gracefully unless both `PSI_LIVE_ANTHROPIC_MODELS_API=1` and `ANTHROPIC_API_KEY` are present.

## Slice 2 — Speed mode stack

- [x] Add `speed-mode-schema` and optional `:speed-mode` to `agent-session-schema`, with initial sessions defaulting to nil.
- [x] Add a `:session/set-speed-mode` dispatch handler that stores the session value and emits project/user config persistence effects for scoped updates.
- [x] Add `set-speed-mode-in!` to session settings.
- [x] Propagate non-nil `:speed-mode` from session data into `:turn/ai-options` in `session->request-options`.
- [x] Add shared-config schema support for persisted `speed-mode`.
- [x] Add a presence-aware `resolved-speed-mode` accessor that distinguishes absent/invalid values from explicit `:normal`.
- [x] Apply resolved persisted speed defaults to newly created root sessions only when the accessor reports presence.
- [x] Implement `/speed` command parsing for no args, `<normal|fast>`, and `<normal|fast> <session|project|user>`.
- [x] Ensure `/speed normal session` clears in-memory state to nil while `/speed normal project|user` stores explicit `:normal` in current session and config.
- [x] Add `:psi.agent-session/speed-mode` resolver that displays nil session state as `:normal`.
- [x] Add footer query/display support for `• fast` when speed mode is `:fast`.
- [x] Add Anthropic request shaping for `speed: "fast"` and the `fast-mode-2026-02-01` beta header only when speed mode is `:fast`.
- [x] Add `[:speed {:optional true} [:enum "fast"]]` to the Anthropic request body schema.
- [x] Add OpenAI chat-completions request shaping for `service_tier: "flex"` only when speed mode is `:fast`.
- [x] Confirm Codex/responses request shaping omits speed mode.
- [x] Add tests for speed command success/error branches and scope handling.
- [x] Add tests for speed session mutation, resolver projection, startup config masks, and cold resume transience.
- [x] Add tests for Anthropic and OpenAI speed request shaping.

## Slice 3 — Effort override and adaptive `:xhigh`

- [x] Add optional `:effort-override` to `agent-session-schema`, with initial sessions defaulting to nil.
- [x] Add a `:session/set-effort-override` dispatch handler that stores the session value and emits project/user config persistence effects for scoped updates.
- [x] Add `set-effort-override-in!` to session settings.
- [x] Propagate non-nil `:effort-override` from session data into `:turn/ai-options` in `session->request-options`.
- [x] Add shared-config schema support for persisted `effort-override`, including explicit nil clears.
- [x] Add a presence-aware `resolved-effort-override` accessor that distinguishes absent/invalid values from explicit nil and explicit effort keywords.
- [x] Apply resolved persisted effort defaults to newly created root sessions only when the accessor reports presence.
- [x] Implement `/effort` command parsing for no args, `<low|medium|high|xhigh|none>`, and `<value> <session|project|user>`.
- [x] Ensure `/effort none session` clears in-memory state to nil while `/effort none project|user` persists explicit nil as a higher-precedence mask.
- [x] Add `:psi.agent-session/effort-override` resolver.
- [x] Update `effective-reasoning-effort` display mapping so `thinking-level :xhigh` displays distinctly as `xhigh`.
- [x] Add footer query/display support for `• effort:<value>` when an override is active and thinking is on.
- [x] Update Anthropic adaptive effort mapping so level-derived and override `:xhigh` send `"highest"`.
- [x] Ensure Anthropic extended-thinking models keep budget-token behavior and silently ignore effort override rather than sending `output_config.effort`.
- [x] Allow `output_config.effort = "highest"` in the Anthropic request schema.
- [x] Update OpenAI chat-completions `reasoning-effort` to accept `:effort-override`, mapping `:xhigh` to provider ceiling `"high"`.
- [x] Update Codex/responses reasoning shaping to call the shared reasoning-effort path instead of reading `thinking-level->effort` directly.
- [x] Ensure all providers omit effort override when `thinking-level` is `:off`.
- [x] Add tests for effort command success/error branches and scope handling.
- [x] Add tests for effort session mutation, resolver projection, startup config masks, and cold resume transience.
- [x] Add tests for Anthropic adaptive `:xhigh`/override shaping, OpenAI chat-completions shaping, and Codex/responses shaping.

## Slice 4 — Mid-conversation system messages

- [x] Add `:system` to `psi.ai.schemas/MessageRole`.
- [x] Add optional `:supports-mid-conversation-system-messages` boolean metadata to the model schema with absent-as-false semantics.
- [x] Add `:mid-system` to `session-entry-kind-schema`.
- [x] Extend journal-to-provider projection to emit `:mid-system` entries as `{:role "system" ...}` provider-style text messages in conversation order.
- [x] Update prepared-turn current-user replacement to preserve a pending `... user, system` tail as `... current-user, system`.
- [x] Add or update AI conversation helpers so system messages are represented as schema-valid keyword-role `:system` messages with normalized text content.
- [x] Extend turn-runtime conversation `append-msg` to normalize projected provider-style system text blocks into canonical AI messages.
- [x] Extend Anthropic message transformation to emit inline `{"role":"system"}` messages for internal `:system` messages.
- [x] Add Anthropic transform placement validation that allows `user → system` even when system is final, and drops/logs invalid beginning, consecutive-system, or after-assistant system messages.
- [x] Add Anthropic request schema support for inline system messages with text-block content.
- [x] Extend OpenAI chat-completions message transformation to map internal `:system` to wire role `"system"`.
- [x] Add a shared model-supports-mid-system predicate using runtime-resolved model data, explicit metadata, and OpenAI chat-completions API-shape inference.
- [x] Add `:psi.agent-session/model-supports-mid-system-messages` resolver using the shared capability predicate.
- [x] Add `:session/inject-mid-system-message` dispatch handler with capability gating and placement validation that ignores non-conversational metadata after the latest user turn.
- [x] Ensure invalid injection placements return `:invalid-placement` reason maps and do not mutate the journal.
- [x] Route successful mid-system injection through the standard journal append persistence path, or emit equivalent journal persistence IO, with coverage proving injected `:mid-system` entries are flushed beyond in-memory state.
- [x] Add `inject-mid-system-message!` to the extension API with text-only and text/options arities.
- [x] Add `psi.extension/inject-mid-system-message` Pathom mutation with optional `:source` and `:ext-path` provenance inference.
- [x] Register the extension mutation in `all-mutations` and `session-scoped-extension-mutation-ops`.
- [x] Translate Pathom mutation results back to the compact extension API result contract.
- [x] Extend compaction `entry->message` to handle `:mid-system` entries.
- [x] Implement compaction preservation for pre-cut active mid-system instructions by coalescing them at a valid next-generation boundary.
- [x] Implement compaction cut-normalization so preserved mid-system instructions are not inserted before already-retained assistant history.
- [x] Merge retained boundary `:mid-system` entries with pre-cut coalesced instructions to avoid adjacent system messages.
- [x] Add tests for model capability flags and OpenAI chat-completions inference, including custom/runtime-loaded model maps.
- [x] Add tests for the EQL capability resolver and dispatch gating agreement.
- [x] Add tests for successful and rejected mid-system injection, including non-conversational metadata after the latest user turn.
- [x] Add tests for journal projection, prepared-turn current-user replacement, and conversation normalization.
- [x] Add tests for Anthropic valid/invalid inline system transform behavior and request schema acceptance.
- [x] Add tests for OpenAI chat-completions system-role transformation.
- [x] Add nullable extension-helper integration coverage for `inject-mid-system-message!`.
- [x] Add compaction tests covering summary-boundary attachment, retained pending-user attachment, cut advancement over completed user/assistant exchanges, and boundary mid-system merge.
- [x] Ensure compacted mid-system preservation survives journal replay/cold resume: pre-cut active `:mid-system` instructions must not be lost by `rebuild-messages-from-journal-entries` after a compaction entry.
- [x] Fix compaction journal replay so mid-system preservation does not drop post-compaction journal entries after the latest replayed assistant turn; add replay coverage with pre-cut `:mid-system`, a compaction entry, and later post-compaction user/assistant history.

## Slice 5 — Integration and coherence

- [x] Update README and `doc/` user-facing command/model documentation for Opus 4.8, `/speed`, `/effort`, and mid-system extension capability.
- [x] Add changelog entries for user-visible model, command, provider-shaping, and extension capability changes.
- [x] Run focused tests for all modified model, command, provider, resolver, extension, prompt-request, and compaction namespaces.
- [x] Run targeted `clj-kondo` on modified source/test paths.
- [x] Run `bb test` and confirm the full suite is green.
- [x] Append implementation notes with key decisions, verification commands, and any provider/API caveats discovered during implementation.

## Test review follow-ups

- [x] Add scoped `/speed` and `/effort` persistence tests proving project/user scoped commands or session mutations write the expected config keys, including `/speed normal project|user` explicit `:normal` masks and `/effort none project|user` explicit nil masks.
- [x] Add effort override request-shaping tests for non-`xhigh` override values: Anthropic adaptive `:effort-override :high` forces `output_config.effort = "high"` over a different thinking level, OpenAI chat-completions `:effort-override :medium` returns/sends `"medium"`, and Codex/responses `:effort-override :medium` sends `{"effort" "medium" ...}` over a different thinking level.

## Docs review follow-ups

- [ ] Align `doc/extension-api.md` mid-system examples with the implemented extension API key: docs currently show `(:inject-mid-system-message! api)`, while the public API map exposes `:inject-mid-system-message` without bang (or add/document a bang alias if that is the intended public surface).
- [x] Correct `doc/configuration.md` speed/effort runtime-settings wording so it does not claim extension mutation surfaces exist for speed/effort unless actual documented mutation names are implemented.

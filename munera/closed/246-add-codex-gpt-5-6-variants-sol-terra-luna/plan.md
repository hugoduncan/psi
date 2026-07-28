# Plan

## Approach

Evidence-gated catalog + routing addition, mirroring the working `gpt-5.5`
OAuth/Codex path exactly.

1. **Probe first (gate).** Run a structured streaming probe against
   `https://chatgpt.com/backend-api/codex/responses` for each of
   `gpt-5.6-sol`, `gpt-5.6-terra`, `gpt-5.6-luna`, using the same request
   shape as the existing Codex transport (`store:false`, `stream:true`,
   `include:["reasoning.encrypted_content"]`, ChatGPT OAuth headers). Record
   status/body per id in `implementation.md`. Only ids that reach execution
   proceed; a rejected id is excluded with the negative evidence recorded.
2. **Catalog.** Add the three variant entries to
   `components/ai/src/psi/ai/models.clj` with the resolved metadata from
   design.md (shared shape; per-variant name + pricing; 272K context window,
   128K max-tokens), and add each key to
   `openai-chat-completions-native-model-keys`.
3. **OAuth/Codex routing.** Add the confirmed ids to
   `openai-oauth-codex-model-ids` in
   `components/ai/src/psi/ai/model_registry.clj`. Do not touch
   `openai-oauth-unsupported-model-ids` (bare `gpt-5.6` stays unsupported).
4. **Tests.** Extend `model_registry_test.clj` (and any surface-level tests
   that enumerate codex-routed models) to cover: each variant resolves under
   OAuth to the Codex transport with its id verbatim; `gpt-5.5` unchanged;
   bare `gpt-5.6` still rejected with the uniform unsupported message.
5. **Docs.** CHANGELOG entry under [Unreleased]/Added; user docs if they list
   selectable models. Additionally reconcile the *prose OAuth-support policy
   statements* that currently assert bare `gpt-5.6` is OAuth-unsupported —
   `doc/tui.md` (~L71–80), `README.md` (~L121–123), `doc/configuration.md`
   (~L109–114, L158), `doc/cli.md` (~L112–115) — so they distinguish the newly
   OAuth/Codex-supported `gpt-5.6-sol/terra/luna` from bare `gpt-5.6` (still
   unsupported). Preserve the true bare-`gpt-5.6` statement and the existing
   catalog-selectable vs OAuth-runtime-supported distinction; add only that the
   three variants are OAuth/Codex-supported. Docs describe policy — do not
   restate per-surface codex literals (those live only in `model_registry.clj`).
6. **Validate.** Focused ai tests, broader test run, lint, coherence review.

## Key decisions

- Ids are sent verbatim; no aliasing between variants or to/from bare
  `gpt-5.6`.
- All model-selection surfaces derive from the shared `model_registry.clj`
  join point — no per-surface codex literals restated.
- 272K flat-rate short-context tier only; long-context tiered pricing not
  modelled (per resolved design).
- Probe scripts are diagnostic (structured status/body, no asserts);
  permanent coverage lives in project tests.

## Risks

- One or more variant ids may be rejected for this ChatGPT account despite
  pi-mono evidence → exclude only the rejected id(s), record evidence.
- OAuth token may need refresh before probing; probe must use the current
  credential class (ChatGPT account OAuth) or evidence is invalid.
- Tests or surfaces may enumerate codex model ids in more than one place;
  find them all (`rg gpt-5.5`) to keep coherence.
- Pricing values are taken from pi-mono generated definitions; if the catalog
  schema validates costs differently, adjust field names, not values.

## Slice order

1. Live streaming probes for all three variant ids; record evidence.
2. Catalog entries + native-model-keys membership.
3. OAuth/Codex route membership for confirmed ids.
4. Regression tests (variants, `gpt-5.5` control, bare `gpt-5.6` negative).
5. Changelog/docs (enumerated model lists + prose OAuth-support statements).
6. Validation (tests, lint, final diff review).

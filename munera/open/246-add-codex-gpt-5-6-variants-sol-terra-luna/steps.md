# Steps

## Slice 1 — Probe evidence (gate)

- [ ] Locate/reuse the task-245 streaming probe approach; confirm current ChatGPT OAuth token is valid (refresh if needed).
- [ ] Probe `gpt-5.6-sol` against `https://chatgpt.com/backend-api/codex/responses` (streaming, structured status/body); record result in implementation.md.
- [ ] Probe `gpt-5.6-terra` the same way; record result in implementation.md.
- [ ] Probe `gpt-5.6-luna` the same way; record result in implementation.md.
- [ ] Decide the confirmed-id set; if any id rejected, record negative evidence and exclude it from later slices.

## Slice 2 — Catalog entries

- [ ] Add `:gpt-5.6-sol` to `components/ai/src/psi/ai/models.clj` (name "GPT-5.6 Sol", input 5.0, output 30.0, cache-read 0.5, cache-write 6.25; shared fields per design: openai/openai-completions, base-url api.openai.com/v1, reasoning+images+text, context-window 272000, max-tokens 128000).
- [ ] Add `:gpt-5.6-terra` (name "GPT-5.6 Terra", 2.5 / 15.0 / 0.25 / 3.125; same shared fields).
- [ ] Add `:gpt-5.6-luna` (name "GPT-5.6 Luna", 1.0 / 6.0 / 0.1 / 1.25; same shared fields).
- [ ] Add all three keys to `openai-chat-completions-native-model-keys`.
- [ ] Reload/lint models.clj; run focused ai tests to confirm catalog validity.

## Slice 3 — OAuth/Codex routing

- [ ] Add confirmed variant ids to `openai-oauth-codex-model-ids` in `components/ai/src/psi/ai/model_registry.clj`.
- [ ] Verify `openai-oauth-unsupported-model-ids` still contains bare `gpt-5.6` and is otherwise untouched.
- [ ] `rg "gpt-5.5"` across components to confirm no other surface restates codex-route literals needing update.

## Slice 4 — Tests

- [ ] Extend `components/ai/test/psi/ai/model_registry_test.clj`: for each variant, OAuth-backed resolution routes to the Codex transport with the id sent verbatim.
- [ ] Add/confirm control test: `gpt-5.5` OAuth/Codex resolution unchanged.
- [ ] Add/confirm negative test: bare `gpt-5.6` under OpenAI OAuth still yields the uniform unsupported-model message.
- [ ] Run `bb test --focus psi.ai.model-registry-test` (and other touched test namespaces).

## Slice 5 — Docs

- [ ] Add CHANGELOG entry under [Unreleased]/Added for the three newly selectable models.
- [ ] Check `doc/`/README for model lists; update if the selectable models are enumerated.
- [ ] Update prose OAuth-support statements that assert bare `gpt-5.6` is OAuth-unsupported so they distinguish the newly OAuth/Codex-supported `gpt-5.6-sol/terra/luna` from bare `gpt-5.6` (still unsupported): `doc/tui.md` (~L71–80), `README.md` (~L121–123), `doc/configuration.md` (~L109–114, L158), `doc/cli.md` (~L112–115). Preserve the true bare-`gpt-5.6` statement and the catalog-selectable vs OAuth-runtime-supported distinction; add only that the three variants are OAuth/Codex-supported; do not restate codex literals (single source of truth = `model_registry.clj`).

## Slice 6 — Validation

- [ ] `bb test` full run.
- [ ] `clj-kondo --lint` on touched files.
- [ ] Final diff review: verbatim ids, no aliasing, shared join point only, task-245 behaviour preserved.
- [ ] Confirm implementation.md contains per-id probe evidence for every id added to the Codex route.

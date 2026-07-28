# Steps

## Slice 1 — Probe evidence (gate)

- [x] Locate/reuse the task-245 streaming probe approach; confirm current ChatGPT OAuth token is valid (refresh if needed).
- [x] Probe `gpt-5.6-sol` against `https://chatgpt.com/backend-api/codex/responses` (streaming, structured status/body); record result in implementation.md.
- [x] Probe `gpt-5.6-terra` the same way; record result in implementation.md.
- [x] Probe `gpt-5.6-luna` the same way; record result in implementation.md.
- [x] Decide the confirmed-id set; if any id rejected, record negative evidence and exclude it from later slices.

## Slice 2 — Catalog entries

- [x] Add `:gpt-5.6-sol` to `components/ai/src/psi/ai/models.clj` (name "GPT-5.6 Sol", input 5.0, output 30.0, cache-read 0.5, cache-write 6.25; shared fields per design: openai/openai-completions, base-url api.openai.com/v1, reasoning+images+text, context-window 272000, max-tokens 128000).
- [x] Add `:gpt-5.6-terra` (name "GPT-5.6 Terra", 2.5 / 15.0 / 0.25 / 3.125; same shared fields).
- [x] Add `:gpt-5.6-luna` (name "GPT-5.6 Luna", 1.0 / 6.0 / 0.1 / 1.25; same shared fields).
- [x] Add all three keys to `openai-chat-completions-native-model-keys`.
- [x] Reload/lint models.clj; run focused ai tests to confirm catalog validity.

## Slice 3 — OAuth/Codex routing

- [x] Add confirmed variant ids to `openai-oauth-codex-model-ids` in `components/ai/src/psi/ai/model_registry.clj`.
- [x] Verify `openai-oauth-unsupported-model-ids` still contains bare `gpt-5.6` and is otherwise untouched.
- [x] `rg "gpt-5.5"` across components to confirm no other surface restates codex-route literals needing update.

## Slice 4 — Tests

- [x] Extend `components/ai/test/psi/ai/model_registry_test.clj`: for each variant, OAuth-backed resolution routes to the Codex transport with the id sent verbatim.
- [x] Add/confirm control test: `gpt-5.5` OAuth/Codex resolution unchanged.
- [x] Add/confirm negative test: bare `gpt-5.6` under OpenAI OAuth still yields the uniform unsupported-model message.
- [x] Run `bb test --focus psi.ai.model-registry-test` (and other touched test namespaces).

## Slice 5 — Docs

- [x] Add CHANGELOG entry under [Unreleased]/Added for the three newly selectable models.
- [x] Check `doc/`/README for model lists; update if the selectable models are enumerated.
- [x] Update prose OAuth-support statements that assert bare `gpt-5.6` is OAuth-unsupported so they distinguish the newly OAuth/Codex-supported `gpt-5.6-sol/terra/luna` from bare `gpt-5.6` (still unsupported): `doc/tui.md` (~L71–80), `README.md` (~L121–123), `doc/configuration.md` (~L109–114, L158), `doc/cli.md` (~L112–115). Preserve the true bare-`gpt-5.6` statement and the catalog-selectable vs OAuth-runtime-supported distinction; add only that the three variants are OAuth/Codex-supported; do not restate codex literals (single source of truth = `model_registry.clj`).

## Slice 6 — Validation

- [x] `bb test` full run.
- [x] `clj-kondo --lint` on touched files.
- [x] Final diff review: verbatim ids, no aliasing, shared join point only, task-245 behaviour preserved.
- [x] Confirm implementation.md contains per-id probe evidence for every id added to the Codex route.

## Implementation-review follow-ups (2)

- [x] `doc/emacs-ui.md` (~L92–99, "Model selection") was missed by the docs-prose reconciliation. Like the four docs the plan enumerated (`doc/tui.md`, `README.md`, `doc/configuration.md`, `doc/cli.md`), it makes an OAuth-support policy statement — bare `gpt-5.6` is rejected/skipped under OAuth, `gpt-5.5` stays on the Codex path — but does **not** distinguish the newly OAuth/Codex-supported `gpt-5.6-sol/terra/luna` variants, which are now catalog-selectable *and* OAuth-supported (so they are selectable via `C-c m m` and NOT skipped by the cycle commands). Update the prose to add the variants (preserve the true bare-`gpt-5.6`-unsupported statement; single source of truth stays `model_registry.clj`; do not restate codex literals). Root cause: the plan/design enumerated a fixed 4-doc list instead of matching all OAuth-support prose via `rg`; consider an `rg 'gpt-5\.6'` sweep across `doc/` to confirm no other prose remains stale.

## Implementation-review follow-ups

- [x] Acceptance criterion says variants must be "selectable across all model-selection surfaces" (`/model`, RPC `set_model`, RPC picker, TUI picker, turn preflight), but tests only assert `resolve-runtime-model` + catalog membership. The pickers/preflight derive from this shared join point (low risk), yet no surface-level test proves a variant is offered/accepted at a picker or preflight boundary. Consider adding one surface-level assertion (e.g. picker enumeration or preflight acceptance of a variant under OAuth) to close the acceptance-criteria coverage gap, or explicitly record in implementation.md that shared-join-point coverage is deemed sufficient and why.
  - Added `components/rpc/test/psi/rpc/session/command_pickers_test.clj`: `/model` picker enumeration surface test asserting all three variants are offered as `["openai" id]` picker items. `bb test --focus psi.rpc.session.command-pickers-test`: 3 assertions / 1 test pass; `clj-kondo` clean.

## Task-test-review follow-ups

- [x] Accept-path (not just offer-path) surface coverage for the variants. The picker-enumeration test proves each variant is *offered*, and `resolve-runtime-model-openai-oauth-gpt-5-6-variants-codex-test` proves each *resolves* to codex verbatim, but no test asserts a *variant* id passing through the selection **accept** boundary (`handle-model-selection!` / RPC `set_model`) under OAuth resolves as supported (codex) rather than being rejected as `unsupported_model`. `components/rpc/test/psi/rpc_model_scope_test.clj` covers the accept/reject machinery only with generic ids (`gpt-5.3-codex` accepted, `gpt-5.6` rejected), so the variant→accept composition is only transitively covered. Either add one focused assertion (e.g. `handle-model-selection!` or `set_model` with `gpt-5.6-sol` under an oauth ctx persists the selection and emits success — mirroring `rpc-picker-model-selection-success-test`), or record in implementation.md why the enumeration + resolution + generic-accept coverage is deemed sufficient for the variants. This is distinct from the prior *offer*-path step above (low severity; largely redundant with existing coverage).
  - Added `model-selection-accepts-gpt-5-6-codex-variants-under-oauth-test` to `components/rpc/test/psi/rpc/session/command_pickers_test.clj`: doseq over all three variants drives each through `handle-model-selection!` + `resolve-model` under an `oauth-openai-ctx`, asserting a model-set **success** `command-result` and persistence of the selection (not the `unsupported_model` reject path bare `gpt-5.6` takes). `bb test --focus psi.rpc.session.command-pickers-test`: 9 assertions / 2 tests pass; `clj-kondo` clean.

## Task-test-review follow-ups (2nd pass)

- [x] Variant OAuth codex resolution only asserts **two of three** codex facets. `resolve-runtime-model-openai-oauth-gpt-5-6-variants-codex-test` asserts `:api :openai-codex-responses` and the codex `:base-url` verbatim, but `with-openai-codex-transport` (the single owner of "how a model becomes codex") shapes **three** facets — `:api`, `:base-url`, **and** the codex native structured-output capability (`with-openai-codex-native-capability`). The design's acceptance criterion is that variants route "exactly as `gpt-5.5` does", and `codex-catalog-transport-matches-shared-constants-test` explicitly warns the structured-output capability is attached by a *second, independent mechanism* that "could drift from the override's composed capability with no test flagging it". The variants are catalog-authored `:openai-completions` and only *become* codex at runtime, so the structured-output-capability facet is precisely the one most likely to silently regress (a variant would fall back to its catalog chat-completions capability). No test proves a variant, once OAuth-codex-resolved, exposes the codex `:openai/responses-text-format-json-schema` native structured-output capability — the way `gpt-5.4` (a codex model) is explicitly tested at ~L232–238. Either extend the variant codex-resolution test to assert `structured-output/effective-capability` on the resolved model matches the codex native mechanism (mirroring the `gpt-5.4` capability test), or record in implementation.md why the two-facet assertion plus the drift-guard test is deemed sufficient for the variants. Low severity — the shared `with-openai-codex-transport` owner is drift-guarded generically; the gap is variant-specific transitive coverage. Distinct from the prior offer/accept-path picker steps (those cover selection surfaces, not the codex structured-output facet).

## Task-test-review follow-ups (3rd pass)

- [x] Turn-preflight surface has variant *negative* coverage but no variant *positive* coverage. The acceptance criteria name **turn preflight** as one of the selection surfaces the variants must be selectable/routable across, and `execute-prepared-request-unsupported-runtime-model-preflights-before-provider-test` (`components/turn-runtime/test/psi/turn_runtime/response_mode_test.clj` ~L235) exercises that boundary — but only for bare `gpt-5.6`, asserting it is *blocked* (`:runtime/unsupported? true`, shaped error, no provider request). No test drives a *variant* (`gpt-5.6-sol/terra/luna`) through the same persisted-model → `prepared-request` (`:resolve-runtime-model? true`) → `execute-prepared-request!` preflight path under OAuth to prove it is *not* preflight-rejected and reaches provider dispatch (codex-resolved, verbatim id). The prior accept-path step covered the `handle-model-selection!`/RPC selection accept boundary, not the turn-preflight boundary; `resolve-runtime-model-openai-oauth-gpt-5-6-variants-codex-test` covers the resolver in isolation, not its composition into preflight. So the variant→preflight-pass composition is the one named surface still only transitively covered, and the *only* preflight assertion for a GPT-5.6 id is the negative one — a regression that started preflight-rejecting the variants (e.g. an over-broad unsupported predicate) would pass every existing test. Either add one focused positive assertion mirroring the existing negative preflight test (persisted OAuth `gpt-5.6-sol`, `:resolve-runtime-model? true`, assert `:prepared-request/model` is codex-resolved with `:runtime/unsupported?` absent/false and the turn is not error-shaped at preflight), or record in implementation.md why the isolated resolver + selection-accept coverage is deemed sufficient for the preflight surface. Low severity — shared join point makes silent divergence unlikely — but it closes the last named-surface positive-path gap and gives the preflight boundary a variant control against its existing negative test. Distinct from the offer/accept picker steps and the structured-output-facet step (those cover selection/resolution, not the turn-preflight execution boundary).

## Docs-review follow-ups

- [x] CHANGELOG `[Unreleased]/Added` variant entry omits the per-model capability/context summary that every sibling model-addition entry in this same CHANGELOG carries. The bare-`gpt-5.6` entry states "reasoning, image + text input, 1M-token context, and native JSON-Schema structured output"; `claude-sonnet-5`/`claude-fable-5` likewise state adaptive-thinking/image+text/1M-token context/structured output; the new variant entry only says the ids are "available … and selectable" and describes routing, giving a reader no sense of the models' capabilities or the 272K context window / per-variant pricing tier the catalog actually encodes. Add a brief capability/context clause consistent with the sibling entries (reasoning, image + text input, 272K-token context, native structured output over the Codex path; note the three variants differ only in pricing — sol > terra > luna), so the changelog is self-describing and consistent with the catalog metadata in `components/ai/src/psi/ai/models.clj` (context-window 272000, per-variant costs). This is style/consistency, not a correctness error.
- [x] CHANGELOG variant entry lists "turn preflight" alongside `/model`, RPC `set_model`, and the pickers as a surface the variants are "selectable via". Turn preflight is a runtime *validation/routing* boundary, not a user-facing model-*selection* surface a user selects "via" — the phrasing conflates the acceptance-criteria surface list (which includes preflight as a routing surface) with user-facing selection entry points. Reword so the user-facing entry points (`/model`, `set_model`, pickers) are what the reader "selects via", and mention preflight (if at all) as part of the runtime routing statement rather than a selection surface. Low severity — accuracy/clarity of user-facing wording only.

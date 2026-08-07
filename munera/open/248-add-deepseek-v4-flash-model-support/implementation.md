# Implementation notes — 248-add-deepseek-v4-flash-model-support

## Slice 1 — schema + tests (2026-08-07)

- Added `[:adaptive-thinking {:optional true} [:maybe boolean?]]` to `ModelDef` in `user_models.clj`. No other schema or code changes needed: `expand-model` already merges all `model-def` keys (except `:name`) into the expanded model map, so the field flows through verbatim.
- `structured-output/normalize-model` operates only on `:capabilities :structured-output` and is a no-op for `:adaptive-thinking` — confirmed by the parse test.
- Added 3 tests in `user_models_test.clj`: `:adaptive-thinking true` accepted and flows through; `:adaptive-thinking false` accepted and flows through; omitted field remains valid and absent/falsy.
- Added 2 tests in `anthropic_test.clj` using a literal custom-provider model map (`deepseek-custom-provider-model`) proving the Anthropic transport emits `thinking.type "adaptive"` + `output_config.effort` (no `budget_tokens`) when `:adaptive-thinking true`, and emits neither when thinking is off.
- All tests green: `psi.ai.user-models-test` (13 tests, 77 assertions), `psi.ai.providers.anthropic-test` (15 tests, 84 assertions).

## Slice 2 — docs + changelog (2026-08-07)

- Added "DeepSeek-compatible example" subsection to `doc/custom-providers.md` with the resolved `models.edn` snippet.
- Documented the new `:adaptive-thinking` field in the Anthropic-compatible example section.
- Added CHANGELOG `[Unreleased]` → `Added` entry.

## Verification (2026-08-07)

- `clj-kondo --lint components/ai/src` clean (0 errors, 0 warnings).
- Targeted test namespaces green.
- No existing built-in Anthropic model or custom-provider behaviour changed.

- Review (2026-08-07): added 5 follow-up steps to be addressed.
- Review 2 (2026-08-07): added 3 further follow-up steps to be addressed.
- Review 3 (2026-08-07): added 2 further follow-up steps to be addressed.
- Review 4 (2026-08-07): added 2 further follow-up steps to be addressed.
- Review 4 (2026-08-07): added 3 steps to be addressed.
## Follow-ups addressed (2026-08-07)

- addressed 4 review steps
- `anthropic_test.clj` custom-provider test now also asserts headers (`x-api-key` from configured key, no `Authorization`, `anthropic-version` present, no `anthropic-beta`) and `:temperature` absent (both thinking on and off) — mirrors the sibling catalog test.
- `anthropic_stream_test.clj` adds a DeepSeek stream-seam test proving `https://api.deepseek.com/anthropic/v1/messages` is derived from `:base-url` (posted URL captured via `http/post` redef, mirroring the MiniMax pattern). Chose the stream-seam approach over extracting a `request-url` fn — zero production code change.
- `doc/custom-providers.md` `:adaptive-thinking` section now documents the temperature trade-off (adaptive models never send `temperature`, even thinking off); DeepSeek example notes call out that this forfeits DeepSeek's fully-supported `temperature` and how to opt back out (`:adaptive-thinking false`).
- Full `bb test` executed: unit + extension suites 2549 tests / 18420 assertions green EXCEPT `psi.agent-session.workflow-delegate-review-step-live-test/delegate-review-task-implementation-completes-with-nullable-local-model-test` (1 failure) — proven pre-existing on base commit 8f0d8258c via stash-revert run; root cause is developer-machine session-profile config (user-config/read-config) referencing `deepseek/deepseek-v4-flash`, which the live test's temp model registry (only `local/test-model`) cannot resolve. Unrelated to this task's scope. `emacs:check` green (343 tests). `clj-kondo` clean on changed test files.
- Blocker for remaining step (live smoke test): `DEEPSEEK_API_KEY` not set in environment; request-shaping only by design, needs a real key.

## Follow-ups review 2 addressed (2026-08-07)

- addressed 3 review steps
- `bb test` RED regression fixed: restored `.psi/project.edn` committed workflow session profiles to the built-in anthropic catalog models (claude-opus-4-8 / claude-sonnet-5 / claude-fable-5), which are always resolvable in CI; the deepseek profile map is kept commented out with a note that it requires the user-global `~/.psi/agent/models.edn` custom provider. The f0c818cc1 "use deepseek for workflows" activation made the committed project config depend on an uncommitted user-local models file, breaking `delegate-review-task-implementation-completes-with-nullable-local-model-test` and the `bb test` AC. Full `bb test` now green: 2550 tests / 19132 assertions, 0 failures (was 1 failure). Targeted live test green (3 tests / 21 assertions).
- Thinking-off caveat documented in `doc/custom-providers.md` DeepSeek example notes: psi never emits an explicit `thinking: {:type "disabled"}` — it omits the field when off, and DeepSeek's endpoint defaults thinking ON, so `/thinking off` on `deepseek-v4-flash` is silently ignored (with or without `:adaptive-thinking`). Chose documentation over adding a `{:type "disabled"}` emission: live verification blocked (no `DEEPSEEK_API_KEY` in env) and the design AC forbids changing `providers/anthropic.clj` request-shaping logic in this task.
- Cache-cost rationale documented in `doc/custom-providers.md` DeepSeek example notes: psi bills `cache_read_input_tokens` at `:cache-read-cost` and `cache_creation_input_tokens` at `:cache-write-cost`; DeepSeek publishes no separate write price so `:cache-write-cost 0.14` mirrors the miss/input rate; Anthropic-style accounting reports the miss portion separately from `input_tokens` (no double-count); Anthropic field-name assumption unverified against a live payload — adjust the example if DeepSeek's usage shape differs.
- Still blocked: optional live smoke test (needs `DEEPSEEK_API_KEY`; not set in env — not attempted with the key embedded in the user-local models.edn without explicit direction).

## Follow-ups review 3 addressed (2026-08-07)

- addressed 2 review steps
- Provider-scoped API-key resolution: `anthropic/resolve-api-key` now falls back to `ANTHROPIC_API_KEY` only for built-in Anthropic models (`:provider` nil or `:anthropic`); custom `:anthropic-messages` providers fail fast with "Missing API key for provider <name>" when their configured key is nil/blank, so an Anthropic key can never be sent to a third-party endpoint. Code + tests authored by a concurrent review-step pass in the working tree (a `getenv` indirection makes the env fallback redef-testable; `anthropic_test.clj` covers the no-leak path and the built-in env-fallback path; the MiniMax missing-auth test now expects the provider-scoped message). Completed the item here by documenting the scoped behavior in the DeepSeek example notes (`doc/custom-providers.md`). `bb test` full suite green with all changes (2550 tests / 19134 assertions); namespace green (15 tests / 92 assertions); clj-kondo clean.
- `doc/custom-providers.md` Adaptive thinking section now explicitly states `:adaptive-thinking` is only meaningful for `:api :anthropic-messages` custom providers (and built-in Anthropic catalog models), and is ignored for OpenAI-compatible custom providers — satisfying the design AC wording.

## Final verification pass (2026-08-07)

- Full `bb test` re-run after the provider-scoped api-key change: 2550 tests /
  19134 assertions, 0 failures. `clj-kondo` clean (0 errors, 0 warnings) on
  changed source/tests.
- Review-1 optional live smoke test remains blocked: `DEEPSEEK_API_KEY` not set
  in environment; request-shaping coverage only by design.

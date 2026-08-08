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
- Review 5 (2026-08-07): added 3 steps to be addressed.
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

## Follow-ups review 4 addressed (2026-08-07)

- addressed 5 review steps (2 duplicate /login-hint items resolved once)
- `resolve-api-key`: returns nil instead of failing when `:no-auth-header` is
  set (keyless local-proxy configs); custom-provider missing-key error no
  longer hints at `/login <provider>` (OAuth login is built-in-only) — it
  names the `models.edn` `:auth {:api-key ...}` remedy. (Code + tests from a
  concurrent review-step pass in the working tree.)
- `build-request`: skips `resolve-api-key` when `:no-auth-header` is set OR
  custom `:headers` provide the auth (headers present, no configured key) and
  strips `x-api-key`/`Authorization` in that case — restores pre-review-3
  behavior for keyless/header-auth custom `:anthropic-messages` providers.
  Added tests: keyless `:no-auth-header`, headers-only auth (no
  `:no-auth-header`), key-plus-headers (both sent, no regression).
- design.md: added "Revision note (implementation reviews)" documenting the
  two review-driven `providers/anthropic.clj` changes (provider-scoped api-key
  resolution; `:no-auth-header` key tolerance) as the only provider-transport
  changes; scope/AC wording updated to match.
- doc/custom-providers.md: provider-scoped key note updated to mention
  `:auth-header? false` keyless exemption; "Local servers and custom headers"
  section documents the keyless `:anthropic-messages` pattern.
- `bb test` full suite green: 2551 tests / 18440 assertions, 0 failures
  (was 2550/19134). Namespace green: 16 tests / 107 assertions. clj-kondo
  clean (0 errors, 0 warnings) on changed source/tests.
- Review-1 optional live smoke test remains blocked: `DEEPSEEK_API_KEY` not
  set in environment (not attempted with the user-local key without explicit
  direction); request-shaping coverage only by design.
- addressed 5 review steps (verified committed resolution a48d288ce end-to-end: full `bb test` green 2551 tests / 0 failures, clj-kondo clean, steps.md items closed; optional live smoke test still blocked on `DEEPSEEK_API_KEY`).

## Follow-ups review 5 addressed (2026-08-07)

- addressed 3 review steps
- `spec/custom-providers.allium` updated: `CustomModelDef`/`ResolvedCustomModel`
  now carry `adaptive_thinking` (carried through in `ParseModelsConfig`); new
  `ResolveRequestApiKey` rule models provider-scoped key resolution (built-in
  Anthropic env fallback; custom-provider fast-fail naming the models.edn
  `:auth` remedy; keyless exemptions via `:no-auth-header` or a recognized
  auth header among custom `:headers`), plus an `ExistsAuthHeader` rule
  (x-api-key / authorization only — incidental headers do not imply keyless);
  `InjectCustomProviderAuth`/`NoAuthHeaderWhenDisabled` updated for keyless
  configs. `anthropic-provider.allium` `ApiKeyResolved` updated to match
  (was unconditional `ANTHROPIC_API_KEY` fallback). allium-check run manually
  (no automated allium checker in repo): spec now matches implementation in
  all three behaviour areas.
- `build-request` headers-implies-auth inference narrowed: custom `:headers`
  only imply keyless auth when a recognized auth header (x-api-key /
  authorization, case-insensitive, new `auth-header?` helper) is among them
  and no `:api-key` is configured; incidental headers (e.g. `X-Client`) with
  a blank key now fast-fail with the clear "Missing API key for provider
  <name>" error instead of silently sending a keyless request. Tests added:
  incidental-headers + blank-key throws (with and without explicit `:api-key
  ""`); `Authorization`-header-only auth (no `:no-auth-header`) builds a
  keyless request. Namespace green (16 tests / 111 assertions); clj-kondo
  clean (0 errors / 0 warnings) on changed source/tests.
- Full `bb test` re-run twice, stable both times: 2551 tests / 19153
  assertions, 0 failures — replaces the inconsistent recorded figures
  (2550/19134 vs 2551/18440); the 18440 figure was an anomalous run /
  transcription error (assertions cannot fall 694 while the namespace grew by
  15 assertions).
- Review-1 optional live smoke test remains blocked: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design.
- Correction to the review-5 count reconciliation (verified independently,
  2026-08-07): the assertion count is run-to-run UNSTABLE, not stable.
  Fresh `bb test` runs of the identical committed code (8a513cb95) gave
  18444, 19153, 19153 assertions (2551 tests, 0 failures each on the default
  seed); pre-commit runs gave 18435/19148/19149. The 18440-vs-19134 delta in
  earlier entries is the same variance — not a transcription error. Root
  cause: pre-existing flaky test `psi.turn-runtime.response-mode-retry-test/
  execute-prepared-request-streaming-retry-discards-failed-partial-output-test`
  (retry attempt count 2 vs 53; FAILS under `--seed 424242`, turning the
  suite red) + kaocha per-run seed randomization. Unrelated to this task's
  changed files. Stable task-relevant deltas: 2550→2551 tests (added
  deftest), namespace 92→111 assertions.
 - Review 6 (2026-08-07): added 3 steps to be addressed.
 - Review 7 (2026-08-07): added 3 steps to be addressed.
 - Review 8 (2026-08-07): added 2 steps to be addressed.

## Follow-ups review 6 addressed (2026-08-07)

- addressed 3 review steps (all actionable review-6 items; review-1 optional
  live smoke test remains BLOCKED)
- Parse-lock test: `user_models_test.clj` gains
  `parse-documented-deepseek-example-test` — parses the exact documented
  DeepSeek `models.edn` example from `doc/custom-providers.md` (version 1,
  deepseek provider, anthropic-messages, `env:DEEPSEEK_API_KEY` auth, all
  resolved model fields incl. pricing/context-window 1000000/max-tokens
  384000/adaptive-thinking true) and asserts every resolved model field plus
  provider-scoped env auth resolution (via `resolve-api-key-spec`,
  `:auth-header? true`). Guards closed ModelDef/AuthConfig schemas against
  docs/code drift.
- Classic-shape custom test: `anthropic_test.clj` gains
  `build-request-classic-thinking-custom-provider-test` — non-catalog
  custom-provider map (`deepseek-custom-provider-model` minus
  `:adaptive-thinking`) + thinking `:medium` emits
  `{:type "enabled" :budget_tokens 8000}`, no `output_config`, no
  `temperature`, `interleaved-thinking` beta present. Locks the
  "no custom-provider behaviour changes" AC for the classic path the docs
  recommend for temperature control on DeepSeek.
- Fast-mode note: `doc/custom-providers.md` DeepSeek example notes now
  document that fast mode is unverified/assumed-unsupported on
  `deepseek-v4-flash` — psi sends `"speed": "fast"` + `fast-mode-2026-02-01`
  beta header; DeepSeek compat table does not list `speed`; Anthropic-
  compatible endpoints typically 400 unknown body fields; blocked on the
  same missing `DEEPSEEK_API_KEY` as the live smoke test.
- Verification: `psi.ai.user-models-test` 14 tests / 97 assertions green
  (was 13/77); `psi.ai.providers.anthropic-test` 17 tests / 115 assertions
  green (was 16/111); clj-kondo clean (0 errors, 0 warnings) on changed
  test files.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design.

## Follow-ups review 10 addressed (2026-08-07)

- addressed 5 review steps (review-10; review-1 optional live smoke test
  remains BLOCKED on missing DEEPSEEK_API_KEY)
- `spec/custom-providers.allium` auth rules are now self-contained (folded
  into the review-9 item-1 style fix): new `RequestOptions` value defines
  the previously-undefined `options` entity (`api_key`/`no_auth_header`/
  `headers`/`thinking_level`); the self-referential `ExistsAuthHeader` rule
  is renamed `AuthHeaderRecognition` (now defining the `ExistsAuthHeader`
  predicate); new `KeylessRequestDefined` rule defines the
  `KeylessRequest(model, options)` predicate (mirrors `build-request`'s
  `no-auth?`); `ResolveRequestApiKey` and `NoAuthHeaderWhenDisabled`
  reference that predicate as a value — `NoAuthHeaderWhenDisabled` no longer
  invokes a rule as a function and its previously-unbound bare `keyless`
  ensure is now `KeylessRequest(model, options)`; `InjectCustomProviderAuth`
  carries `options` in its trigger and cross-references the request-level
  predicate from its auth-config-level keyless determination.
- OpenAI-transport key fallback closed (review-10 item 2): the
  `:openai-completions` transport now has the same provider-scoped key
  resolution the anthropic transport got in review 3 — `chat_completions.clj`
  gains `getenv`/`auth-header?`/`resolve-api-key` (falls back to
  `OPENAI_API_KEY` only for built-in OpenAI models, `:provider` nil or
  `:openai`; custom providers fail fast with a provider-scoped "Missing API
  key" error naming the models.edn `:auth` remedy, no `/login` hint) and
  `build-request` computes the same `no-auth?` keyless logic
  (`:no-auth-header`, or a recognized `x-api-key`/`Authorization` header
  among custom `:headers` with no configured key), omitting the Authorization
  header when keyless. A custom provider's request can never silently send
  the user's `OPENAI_API_KEY` to a third-party endpoint. Completed by this
  pass: `openai_completions_test.clj` gains
  `openai-provider-scoped-api-key-resolution-test` (custom provider with
  redef'd `getenv` → throws rather than sending the key; missing-key error
  names models.edn and never hints at `/login`; built-in model env fallback
  preserved; keyless `:no-auth-header` / recognized-auth-header /
  incidental-headers-fast-fail paths); `doc/custom-providers.md` MiniMax
  example notes + the Anthropic-compatible provider-scoped paragraph now
  document provider-scoped resolution for BOTH transports; `spec/
  openai-provider.allium` gains `OpenAIApiKeyResolved` +
  `KeylessRequestDetermined` mirroring the anthropic spec; design.md revision
  note updated with this third provider-transport change (review-10-driven).
- Tautological env-auth assertion fixed: `user_models.clj` gains a private
  `getenv` indirection used by `resolve-api-key-spec` (behavior-preserving;
  mirrors the anthropic provider's review-3 pattern), and
  `parse-documented-deepseek-example-test` now `with-redefs`
  `user-models/getenv` to a sentinel and asserts the parsed auth `:api-key`
  equals it — genuinely exercising `env:DEEPSEEK_API_KEY` → getenv →
  `:api-key`, with no env-dependency and no `(= X X)` tautology.
- OpenAI adaptive-thinking-is-ignored lock added:
  `openai_completions_test.clj` gains
  `openai-completions-adaptive-thinking-ignored-for-custom-providers-test` —
  `build-request` for a literal custom `:openai-completions` model with and
  without `:adaptive-thinking true` (+ `:thinking-level :high`) yields
  byte-identical bodies, no `output_config`/`thinking` leakage, and the
  unchanged classic `reasoning_effort "high"` shape. (First version used a
  `->` thread ending in bare `true`, which compiled to `(true ...)` — fixed
  to direct `json/parse-string (:body ...) true` calls.)
- Mixed-case `Authorization` capture redaction locked:
  `anthropic_stream_test.clj` gains a capture-path block — keyless custom
  provider with `:headers {"authorization" "local-token"}` → `"Bearer
  ***REDACTED***"` in the `:on-provider-request` payload, locking the
  `redact-authorization` path through the case-insensitive `find-header`
  helper for a non-exact-case header name.
- Verification: full `bb test` green (2554 tests / 19183 assertions /
  0 failures; test count 2553→2554 = the added OpenAI deftest);
  `psi.ai.user-models-test` 14/97, `psi.ai.providers.anthropic-test` 17/115,
  `psi.ai.providers.anthropic-stream-test` 9/85, `psi.ai.providers.
  openai-completions-test` 13/53 green; clj-kondo clean (0 errors, 0
  warnings) on all changed source + test files.
- allium-check performed manually (no automated allium checker in repo):
  both `custom-providers.allium` and `anthropic-provider.allium` now use
  only defined entities/fields/predicates; `KeylessRequest` is defined by a
  rule and referenced as a predicate, never invoked as a function; the
  `options` entity is defined; the rules match
  `providers/anthropic.clj` (build-request `no-auth?`/resolve-api-key),
  `providers/openai/chat_completions.clj` (Authorization fallback), and the
  stream/parse tests.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design. Recorded in
  steps.md as the sole remaining unchecked item.

## Follow-ups review 10 reconciliation (2026-08-07)

- Reconciles the review-10 entry above with the final committed state
  (concurrent passes converged on the same implementation; this pass added
  the OpenAI code change + tests and finalized the specs):
- `spec/custom-providers.allium` item-1 resolution refined after the entry
  above was written: the intermediate `AuthHeaderRecognition` rename is
  replaced by inlining the recognized-auth-header condition as an explicit
  `∃ header ∈ ... . LowerCase(HeaderName(header)) ∈ {"x-api-key",
  "authorization"}` at both use sites (`KeylessRequestDefined`,
  `InjectCustomProviderAuth`) — no self-referential rule remains. New
  `Primitives` section defines `SystemGetenv`/`Environment`, `BlankOrNil`,
  `HeaderName`, `LowerCase`; new `External interface` section +
  `surface CustomProviderApi` declare `BuildPreparedRequest`/
  `RequestUnderConstruction` (provided events) and document
  `LookupProviderAuth` (runtime function, cf. `extract-provider-auth`).
  `spec/anthropic-provider.allium` mirrors this: `ExistsAuthHeader` rule
  deleted (∃ inlined into `KeylessRequestDetermined`), `RedactRequestHeaders`
  uses the documented `HeaderName` primitive, Primitives note added — both
  specs are now checkable (no undefined predicates/entities/functions beyond
  the documented primitives and the pre-existing runtime-interface events).
- OpenAI-transport key resolution (item 2) implemented in this pass:
  `providers/openai/chat_completions.clj` gains `getenv`/`auth-header?`/
  `resolve-api-key` (provider-scoped: built-in `:provider` nil/`:openai`
  fall back to `OPENAI_API_KEY`; custom providers fail fast with the
  models.edn-`:auth` remedy, no `/login` hint) and `build-request` computes
  `no-auth?` (`:no-auth-header`, or a recognized auth header among custom
  `:headers` with no configured key), omitting Authorization when keyless.
  `openai_completions_test.clj` gains
  `openai-provider-scoped-api-key-resolution-test` (custom provider + redef'd
  `getenv` → throws rather than leaking; missing-key error names models.edn,
  no `/login`; built-in env fallback; keyless `:no-auth-header` /
  recognized-auth-header / incidental-headers-fast-fail) and the pre-existing
  `local-openai-completions-thinking-off...` test's two build-request calls
  now pass `:no-auth-header true` (local server pattern; previously relied on
  the silent env fallback). `spec/openai-provider.allium` gains
  `OpenAIApiKeyResolved` + `KeylessRequestDetermined` (with `keyless` on
  `OpenAIStream`, `no_auth_header`/`headers` on `StreamOptions`).
- Final verification (this pass, committed state): full `bb test` green
  (2554 tests / 0 failures; assertion count varies run-to-run per the
  review-5 flake analysis); `psi.ai.providers.openai-completions-test`
  14 tests / 62 assertions (was 13/53 before the added deftest);
  `psi.ai.user-models-test` 14/97, `psi.ai.providers.anthropic-test` 17/115,
  `psi.ai.providers.anthropic-stream-test` 9/85,
  `psi.ai.providers.openai-request-headers-test` 4/25,
  `psi.ai.providers.openai-test` 13/62 green; clj-kondo clean (0 errors, 0
  warnings) on all changed source + test files.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design. Recorded in
  steps.md as the sole remaining unchecked item.

## Follow-ups review 7 addressed (2026-08-07)

- addressed 3 review steps (review-7; review-1 optional live smoke test
  remains BLOCKED on missing DEEPSEEK_API_KEY)
- `thinking.type "adaptive"` caveat documented in DeepSeek example notes
  (doc/custom-providers.md): `output_config.effort` is confirmed supported
  (compat table), but `thinking.type "adaptive"` is NOT among DeepSeek's
  documented honored values (`enabled`/`disabled` only; "adaptive" absent
  from DeepSeek's Anthropic API docs, verified 2026-08-07) — strict endpoint
  may 400, lenient may ignore leaving thinking ON; fall back to
  `:adaptive-thinking false` (classic `type: "enabled"` IS honored). Live
  verification blocked (no key).
- Capture redaction is now case-insensitive: `providers/anthropic.clj`
  `redact-request-headers` uses a new `find-header` helper matching auth
  header names case-insensitively (reuses the same recognition set as
  `auth-header?`) and redacts under the original key casing. New capture-path
  test in `anthropic_stream_test.clj`: keyless custom-provider request with
  `:headers {"X-API-Key" "local-key"}` → `***REDACTED***` in the
  `:on-provider-request` payload (mirrors the lowercase `x-api-key`
  assertion). Previously a mixed-case auth header leaked verbatim into stored
  captures.
- Docs keyless-overbreadth aligned: "Local servers and custom headers" now
  names the recognized-auth-header requirement (`x-api-key`/`Authorization`
  case-insensitive, no configured key) and states incidental headers (e.g.
  `X-Client`) do NOT imply keyless (fast-fail); DeepSeek example api-key note
  aligned to the same exemption.
- Verification: `psi.ai.providers.anthropic-stream-test` 9 tests / 84
  assertions green (was 9/83; +1 new assertion, stable across 3 runs);
  `psi.ai.providers.anthropic-test` 17/115; `psi.ai.user-models-test` 14/97;
  clj-kondo clean (0 errors, 0 warnings) on changed source + tests.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design.

## Review 7 verification pass (2026-08-07)

- addressed 3 review steps (verified end-to-end on committed 8827ae209)
- Found + fixed a real defect in the review-7 redaction change before it
  landed: `find-header`'s parameter was named `name`, shadowing
  `clojure.core/name`, so `(name k)` invoked the string parameter as a
  function → "String cannot be cast to IFn" on EVERY captured request (all
  9 anthropic-stream tests red). Renamed the param to `header-name`;
  stream namespace back to green (9 tests / 84 assertions).
- Full `bb test` re-run: first run 2552 tests / 1 failure —
  `prompt-lifecycle-test/prompt-provider-retry-after-tool-result-does-not-
  rerun-tool-test` (provider-attempts 54 vs 3) — same documented
  timing-sensitive retry-loop flake class as the review-5
  `response-mode-retry-test` finding; passes 3/3 in isolation. Re-run
  green: 2553 tests / 18469 assertions / 0 failures (assertion count
  varies run-to-run per the review-5 analysis). clj-kondo clean.
- Working tree clean at end: all review-6 + review-7 work (incl. this
  verification) committed in 8827ae209.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design.

## Follow-ups review 8 addressed (2026-08-07)

- addressed 2 review steps (review-8; review-1 optional live smoke test
  remains BLOCKED on missing DEEPSEEK_API_KEY)
- Effort-value set documented (doc/custom-providers.md DeepSeek example
  notes): psi's adaptive path emits "low"/"medium"/"high"/"highest" (from
  thinking-level->effort and effort-override->effort in
  providers/anthropic.clj) and never "max"; only "low"/"high" are within
  DeepSeek's documented "low/high/max" set, "medium"/"highest" are
  undocumented (strict endpoint may 400, lenient may map unpredictably),
  "highest" does not correspond to DeepSeek's "max"; recommended
  documented-safe levels: /thinking minimal|low → "low", /thinking high →
  "high". Live verification blocked (no key); chose documentation over an
  effort-value mapping (design AC forbids request-shaping changes).
- Fast-mode 400 non-recoverability documented (doc/custom-providers.md):
  the HTTP-400 compatibility retry strips the fast-mode-2026-02-01 beta
  header (`:without-all-betas` in anthropic/request_support.clj) but leaves
  `"speed": "fast"` in the retried body — a speed-field 400 retries once
  with the same field and hard-fails; users must /fast off. Chose
  documentation over the optional `:speed`-stripping code change (design AC
  forbids transport/request-shaping changes in this task).
- Verification: doc-only change; parse-lock namespace
  psi.ai.user-models-test 14/97 green, psi.ai.providers.anthropic-test
  17/115 green, psi.ai.providers.anthropic-stream-test 9/84 green (the
  documented example code block was untouched — notes bullets only).

- Review 9 (2026-08-07): added 4 steps to be addressed.

## Follow-ups review 9 addressed (2026-08-07)

- addressed 4 review steps (review-9; review-1 optional live smoke test
  remains BLOCKED on missing DEEPSEEK_API_KEY)
- `spec/anthropic-provider.allium` model completed so `ApiKeyResolved` is
  self-contained: `Model` gains `provider: String?` and
  `adaptive_thinking: Boolean = false`; `StreamOptions` gains
  `no_auth_header: Boolean = false`, `headers: Map<String, String>?`,
  `effort_override`, and `on_provider_request`/`on_provider_response`
  (plus a `ProviderCallbackHandle` value); `AnthropicStream` gains
  `keyless: Boolean = false`. `ApiKeyResolved` inlines the built-in/custom
  conditions (`provider == null or provider == "anthropic"` vs otherwise)
  instead of the previously-undefined `BuiltinAnthropic`/`CustomProvider`
  predicates; new `KeylessRequestDetermined` + `ExistsAuthHeader` rules
  define `stream.keyless`, mirroring `build-request`'s `no-auth?`
  computation (`:no-auth-header`, or a recognized auth header among custom
  `:headers` with no configured key; incidental headers do not imply
  keyless).
- Adaptive-thinking + capture-redaction spec coverage added to
  `spec/anthropic-provider.allium`: `ThinkingParamPresentForActiveLevel`
  emits the adaptive shape (`{:type "adaptive" :display "summarized"}`)
  for `adaptive_thinking` models; new `OutputConfigEffortForAdaptiveThinking`
  /`NoOutputConfigForClassicThinking` rules model body
  `output_config.effort` (level-derived or explicit override; never present
  for classic thinking); `TemperatureExcludedForAdaptiveModels` +
  `AnthropicRequestBodyBuilt`'s temperature guard model temperature
  exclusion whenever adaptive (even with thinking off); a new Capture
  Callbacks section (`ProviderRequestCaptureEmittedWithRedaction`,
  `ProviderResponseCaptureEmitted`, `RedactRequestHeaders`) models the
  review-7 case-insensitive `find-header` redaction (auth header names
  matched case-insensitively, redacted value written back under the
  original key casing, non-auth headers pass through).
- `parse-documented-deepseek-example-test` (user_models_test.clj) now
  reads `doc/custom-providers.md` directly instead of embedding a hardcoded
  copy: `repo-root` (walks up from cwd until `doc/custom-providers.md`
  exists) + `deepseek-example-edn` (extracts the ```clojure EDN block under
  the '## DeepSeek-compatible example' heading) helpers feed the exact
  documented example through `parse-models-config`, asserting every
  resolved model field plus provider-scoped env auth resolution. Doc↔schema
  drift now fails the test in both directions (a doc edit that breaks the
  example, or a schema change that rejects it).
- `doc/custom-providers.md` Adaptive thinking section now states
  `:adaptive-thinking true` is a silent no-op without
  `:supports-reasoning true`: `thinking-param` gates on
  `(:supports-reasoning model)`, so adaptive-thinking without
  supports-reasoning sends a plain non-thinking request — no `thinking`
  field, no `output_config.effort`, no schema error or warning. Set both
  flags together.
- Verification: full `bb test` green (2553 tests / 18469 assertions /
  0 failures); `psi.ai.user-models-test` 14/97, `psi.ai.providers.
  anthropic-test` 17/115, `psi.ai.providers.anthropic-stream-test` 9/84
  green; clj-kondo clean (0 errors, 0 warnings) on the changed test file.
- allium-check performed manually (no automated allium checker in repo, per
  the review-5 resolution): `ApiKeyResolved` is now self-contained (no
  undefined predicates/attributes), and the adaptive-thinking,
  temperature-exclusion, keyless, and capture-redaction rules match
  `providers/anthropic.clj` (thinking-param/request-body/build-request/
  resolve-api-key/redact-request-headers) and `anthropic_stream_test.clj`.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design.

- Review 10 (2026-08-07): added 5 steps to be addressed.
- Review 11 (2026-08-07): added 6 steps to be addressed.
- Review 12 (2026-08-07): added 2 steps to be addressed.

## Follow-ups review 11 + 12 addressed (2026-08-07)

- addressed 6 review-11 steps + 7 review-12 steps (review-1 optional live
  smoke test remains BLOCKED on missing DEEPSEEK_API_KEY)
- OpenAI capture redaction made case-insensitive + x-api-key aware:
  `providers/openai/transport.clj` `redact-request-headers` now uses a
  `find-header` helper (mirrors the anthropic transport's review-7 fix),
  redacting `Authorization` (`Bearer ***REDACTED***`), `x-api-key`
  (`***REDACTED***`) and `chatgpt-account-id` (masked) under the original
  key casing. `openai_request_headers_test.clj` gains
  `custom-header-auth-redacted-in-captures-test` (mixed-case `X-API-Key` →
  `***REDACTED***`; lowercase `authorization` → `Bearer ***REDACTED***`).
- OAuth content-sniff gated to built-in Anthropic models:
  `oauth?` in `build-request`/`request-headers` is now
  `(and (builtin-anthropic? model) (oauth-api-key? api-key))` — custom
  `:anthropic-messages` providers with an `sk-ant-oat…` key always use
  `x-api-key` auth and never receive the Claude Code headers/system prompt.
  `resolve-api-key` reuses `builtin-anthropic?`. New
  `build-request-oauth-gated-on-builtin-models-test`; spec
  `OAuthDetectedFromApiKey` gated; DeepSeek docs notes updated.
- `spec/openai-provider.allium` now self-contained: Primitives section
  (Environment/BlankOrNil/IsBlank/HeaderName/LowerCase, shared vocabulary
  with custom-providers.allium) + `RedactRequestHeaders` rule defining the
  previously-undefined reference (case-insensitive auth-header redaction
  mirroring the transport fix). Manual allium-check (no automated checker).
- `:effort-override` no-op without a thinking level documented (Adaptive
  thinking section + DeepSeek notes: effort applies only with `/thinking`
  on) and locked with a build-request test block (no `:thinking`, no
  `:output_config`).
- Configured-key + recognized-auth-header interplay locked on both
  transports (`configured-key-plus-recognized-auth-header-interplay-test`):
  anthropic sends both `x-api-key` (configured) + custom `X-API-Key`
  (duplicate on the wire); openai custom `Authorization` replaces the
  resolved bearer key. Docs "Local servers and custom headers" now state the
  merge behavior and advise one auth mechanism per provider.
- `deepseek-example-edn` (user_models_test.clj) now targets the specific EDN
  block: scans ```clojure blocks after the heading and picks the first
  starting with `{:version`, throwing a clear error if none matches —
  incidental code blocks can no longer move the parse-lock target.
- Review-12 CHANGELOG entry added (`[Unreleased]` → `Changed`: provider-scoped
  API-key resolution for both transports + keyless exemptions; OAuth
  content-sniff gating; case-insensitive capture redaction).
- Review-12 env-var suggestion fix: both transports' custom-provider
  missing-key errors now normalize kebab-case provider keys
  (`-` → `_`, e.g. `:my-anthropic-proxy` → `env:MY_ANTHROPIC_PROXY_API_KEY`);
  tests added on both transports.
- Verification: full `bb test` green (2559 tests / 19212 assertions /
  0 failures); `psi.ai.providers.anthropic-test` 19/130,
  `psi.ai.providers.anthropic-stream-test` 9/85,
  `psi.ai.providers.openai-completions-test` 15/65,
  `psi.ai.providers.openai-request-headers-test` 5/27,
  `psi.ai.user-models-test` 14/97 green; clj-kondo clean (0 errors, 0
  warnings) on all changed source + test files.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design.

## Follow-ups review 11 + 12 addressed (2026-08-07)

- addressed 7 review steps (review-11: 1; review-12: 6; review-1 optional
  live smoke test remains BLOCKED on missing DEEPSEEK_API_KEY)
- This pass completed the two remaining code/doc items whose working-tree
  changes were still pending, and verified the rest end-to-end:
  - Env-var suggestion normalization (review-12 item): both
    `anthropic/resolve-api-key` and `openai/chat-completions/resolve-api-key`
    now `(str/replace "-" "_")` the provider key before `str/upper-case` in
    the suggested env var name — `:my-anthropic-proxy` →
    `env:MY_ANTHROPIC_PROXY_API_KEY` (bash identifiers cannot contain
    hyphens), not `MY-ANTHROPIC-PROXY_API_KEY`. Tests added on both
    transports asserting the underscore suggestion and absence of the
    hyphenated form for kebab-case provider keys.
  - CHANGELOG (review-12 item): `[Unreleased]` → `Changed` now carries three
    bullets — provider-scoped API-key resolution for both transports
    (fail-fast instead of silent `ANTHROPIC_API_KEY`/`OPENAI_API_KEY`
    fallback; built-ins keep the fallback; keyless exemptions named),
    custom-provider OAuth content-sniffing closed (`sk-ant-oat` keys on
    custom providers always use `x-api-key`; Claude Code headers/system
    prompt never sent to third parties), and case-insensitive auth-header
    capture redaction on both transports.
- Verified (working-tree changes from the concurrent review-11/12 pass):
  OpenAI transport capture redaction now case-insensitive + `x-api-key`
  (`providers/openai/transport.clj` `find-header` helper) with capture-path
  tests (`custom-header-auth-redacted-in-captures-test`); `oauth?` gated on
  built-in Anthropic models via new `builtin-anthropic?` helper in
  anthropic.clj (`build-request-oauth-gated-on-builtin-models-test`);
  `spec/openai-provider.allium` gains Primitives section +
  `RedactRequestHeaders` rule; `spec/anthropic-provider.allium`
  `OAuthDetectedFromApiKey` requires the built-in-provider condition;
  effort-override-without-thinking no-op documented + tested
  (no `output_config`); configured-key + recognized-auth-header interplay
  tested on both transports + documented ("don't mix them");
  `deepseek-example-edn` now targets the `{:version` EDN block (incidental
  code blocks cannot move the parse-lock target).
- Verification: full `bb test` green (2559 tests / 19216 assertions /
  0 failures; assertion count varies run-to-run per the review-5 flake
  analysis); `psi.ai.providers.anthropic-test` 19/132,
  `psi.ai.providers.openai-completions-test` 15/67,
  `psi.ai.providers.openai-request-headers-test` 5/27,
  `psi.ai.providers.anthropic-stream-test` 9/85,
  `psi.ai.user-models-test` 14/97 green; clj-kondo clean (0 errors,
  0 warnings) on all changed source + test files.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design.
- Review 13 (2026-08-07): added 3 steps to be addressed.
- Review 13 (2026-08-07): added 2 further steps to be addressed.

## Follow-ups review 13 addressed (2026-08-07)

- addressed 3 review steps (review-13; review-1 optional live smoke test
  remains BLOCKED on missing DEEPSEEK_API_KEY)
- Adaptive-shape 400-fallback locked + documented: `anthropic_stream_test.clj`
  gains `stream-anthropic-retries-adaptive-shape-without-thinking-on-400-test`
  — literal deepseek-v4-flash custom model (`:adaptive-thinking true`) +
  `:thinking-level :high`; first post 400, retry 200. Asserts first body has
  `thinking.type "adaptive"` + `output_config.effort "high"`, retried body
  strips BOTH `:thinking` and `:output_config`, response capture records
  `:retry-fallback-steps [:without-thinking]`, stream completes with no
  `:error` (400 absorbed → thinking silently ON at default effort on
  DeepSeek; the non-streaming execute path hard-fails — asymmetry noted).
  `doc/custom-providers.md` DeepSeek notes gain an "HTTP-400 compatibility
  retry and the adaptive shape" bullet documenting the degradation and the
  `:adaptive-thinking false` fail-fast alternative.
- `spec/anthropic-provider.allium` gains a self-contained "HTTP-400
  Compatibility Retry" section: `FallbackStepsSelectedFor400` (cumulative
  step selection: prompt-caching beta / thinking request / any-beta-not-Bearer),
  `FallbackRetriedOnceOrErrorSurfaced` (retry once; retry ≥400 → error,
  <400 → stream continues; no steps → error without retry), and per-step
  transform rules — `:without-thinking` strips `:thinking` + `:output_config`
  (the review-13 adaptive interaction), `:without-all-betas` clears betas +
  strips `:output_format` but RETAINS `:speed` (the review-8 fast-mode note),
  `:without-prompt-caching` strips cache directives. Section documents its
  rule-defined vocabulary (review-9/10 self-containedness pattern); manual
  allium-check (no automated checker in repo).
- OpenAI `redact-authorization` aligned with the anthropic transport:
  `openai/transport.clj` now strips `^Bearer\s+` before counting
  (delegating to `redact-secret`, moved above it) so `(len=N)` measures the
  secret only, not the 7-char prefix. `openai_request_headers_test.clj`
  gains `redact-authorization-length-excludes-bearer-prefix-test` — capture
  of a keyless custom-provider `"authorization" (str "Bearer " token)` with
  a 30-char token asserts `"Bearer ***REDACTED*** (len=30)"`.
- Verification: full `bb test` green (2561 tests / 19226 assertions /
  0 failures; +2 tests = the two new deftests; assertion count varies
  run-to-run per the review-5 flake analysis);
  `psi.ai.providers.anthropic-stream-test` 10/94 (was 9/85),
  `psi.ai.providers.openai-request-headers-test` 6/28 (was 5/27),
  `psi.ai.providers.anthropic-test` 19/132, `psi.ai.providers.
  openai-completions-test` 15/67, `psi.ai.user-models-test` 14/97 green
  (doc parse-lock unaffected by the new notes bullet); clj-kondo clean (0
  errors, 0 warnings) on all changed source + test files.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design.

## Follow-ups review 13 addressed (codex + openai spec, 2026-08-07)

- addressed 2 further review steps (concurrent review-pass working-tree
  changes verified end-to-end; review-1 optional live smoke test remains
  BLOCKED on missing DEEPSEEK_API_KEY)
- Codex custom-provider key fallback closed: `codex_responses/
  build-codex-request` now resolves the key provider-scoped (new
  `getenv`/`auth-header?`/`resolve-api-key` helpers) — built-in
  `:provider` nil/`:openai` keep the `OPENAI_API_KEY` env fallback; custom
  `:openai-codex-responses` providers fail fast with the provider-scoped
  "Missing API key" error naming the models.edn `:auth` remedy (no `/login`
  hint; kebab-case env-var suggestion normalized `-` → `_`), matching the
  review-3/10 treatment of the other two transports. Keyless exemptions
  apply (`:no-auth-header`, or a recognized auth header among custom
  `:headers` with no configured key; incidental headers fast-fail) and
  keyless requests omit BOTH `Authorization` and `chatgpt-account-id` — the
  account-id requirement is waived for keyless configs (was an unconditional
  `extract-chatgpt-account-id` throw). `openai_test.clj` gains
  `codex-provider-scoped-api-key-resolution-test` (no-leak redef'd-getenv,
  models.edn remedy/no-/login, kebab-case suggestion, built-in fallback,
  `:no-auth-header` keyless, recognized-auth-header keyless, incidental-
  headers fast-fail). CHANGELOG `Changed` entry + doc/custom-providers.md
  now name all three transports; design.md revision note added.
- `spec/openai-provider.allium` nil-provider condition fixed:
  `OpenAIApiKeyResolved` now uses `(provider == null or provider ==
  "openai")` / `(provider != null and provider != "openai")`, mirroring the
  anthropic spec and `chat-completions` `resolve-api-key`'s `(or (nil?
  provider) (= :openai provider))`. Also updated for codex:
  `OpenAIProviderDispatchesByModelApi` dispatches on `model.api` (completions
  vs codex-responses) instead of the built-in-only `provider = "openai"`
  assumption, and `CodexRequestRequiresApiKey`/`CodexRequiresChatGptAccountId`
  gain the keyless exemption. Manual allium-check (no automated checker in
  repo).
- Verification: full `bb test` green (2561 tests / 0 failures; assertion
  count varies run-to-run per the review-5 flake analysis);
  `psi.ai.providers.openai-test` 14/75 (was 13/62; +1 codex deftest),
  `psi.ai.providers.openai-completions-test` 15/67,
  `psi.ai.providers.openai-request-headers-test` 6/28,
  `psi.ai.providers.anthropic-stream-test` 10/94,
  `psi.ai.providers.anthropic-test` 19/132,
  `psi.ai.user-models-test` 14/97 green; clj-kondo clean (0 errors, 0
  warnings) on all changed source + test files.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design.

## Follow-ups review 13 — final verification pass (2026-08-07)

- addressed 5 review steps (all review-13 items; verified end-to-end on the
  converged working tree — review-1 optional live smoke test remains BLOCKED
  on missing DEEPSEEK_API_KEY)
- Full `bb test` re-run on the complete converged tree: 2562 tests / 19239
  assertions / 0 failures. Count reconciliation: the review-13 entry above
  records 2561 tests — that run predated the codex deftest landing; with all
  three review-13 deftests present the suite is 2559 (committed) + 3 =
  2562 (adaptive-shape 400-retry, openai redactor Bearer-prefix length,
  codex-provider-scoped key resolution). Assertion count varies run-to-run
  per the review-5 flake analysis (19239 in the observed band).
- Targeted namespaces green on the final tree: `psi.ai.providers.openai-test`
  14/75, `psi.ai.providers.openai-completions-test` 15/67,
  `psi.ai.providers.openai-request-headers-test` 6/28,
  `psi.ai.providers.anthropic-stream-test` 10/94,
  `psi.ai.providers.anthropic-test` 19/132, `psi.ai.user-models-test` 14/97.
  clj-kondo clean (0 errors, 0 warnings) on all changed source + test files.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design (steps.md item
  left unchecked).
- Review 14 (2026-08-07): added 2 steps to be addressed.

- Review 14 (2026-08-07): added 4 steps to be addressed.

## Follow-ups review 14 addressed (2026-08-07)

- addressed 6 review steps (all review-14 items; review-1 optional live smoke
  test remains BLOCKED on missing DEEPSEEK_API_KEY)
- **Shared provider request-support namespace (review-14 item 6):** new
  `components/ai/src/psi/ai/providers/request_support.clj`
  (`psi.ai.providers.request-support`) now owns the provider-scoped key
  resolution, keyless-auth detection, auth-header recognition and
  capture-redaction primitives that were triplicated across the three
  transports — `getenv`/`auth-header?`/`no-auth?`/`builtin?`/`resolve-api-key`
  (parameterized by `{:builtin-provider :env-var :builtin-missing-msg}`) and
  `find-header`/`redact-secret`/`redact-authorization`/
  `mask-chatgpt-account-id`/`redact-headers`. All three transports
  (`anthropic.clj`, `openai/chat_completions.clj`,
  `openai/codex_responses.clj`) call the shared `no-auth?` + `resolve-api-key`
  with a transport config map; `anthropic.clj`'s 400-fallback alias renamed
  to `anthropic-request-support` to free the `request-support` alias;
  `openai/transport.clj` redaction delegates to shared `redact-headers`.
  Behavior-preserving (verified by the full transport test namespaces +
  clj-kondo clean). Test getenv redefs moved to
  `psi.ai.providers.request-support/getenv` across the three provider test
  files.
- **Custom-provider origin tagging (review-14 item 3):** `expand-model`
  (user_models.clj) now tags every custom models.edn model `:custom? true`,
  and `builtin?`/`builtin-anthropic?` require the tag in addition to the
  provider name — a custom provider literally named "anthropic"/"openai" can
  no longer fall back to `ANTHROPIC_API_KEY`/`OPENAI_API_KEY` or receive
  Claude Code OAuth treatment. Tests: `user_models_test.clj`
  `custom-provider-models-tagged-custom-test` (every custom model tagged;
  providers named "anthropic"/"openai" parse with `:custom? true`);
  `anthropic_test.clj` `custom-provider-named-anthropic-not-builtin-test`
  (unset key + redef'd getenv → throws "Missing API key for provider
  anthropic"; sk-ant-oat key → x-api-key auth, no OAuth headers/system
  prompt); `openai_completions_test.clj`
  `custom-provider-named-openai-not-builtin-test` (unset key + redef'd getenv
  → throws, no OPENAI_API_KEY fallback). Specs updated to model the tag:
  `ResolvedCustomModel.custom = true` + `not model.custom` built-in
  conditions in all three allium files (custom-providers, anthropic-provider,
  openai-provider; `Model.custom: Boolean = false` added to the two provider
  specs).
- **Exact-case auth-header interplay (review-14 item 5):** added the
  exact-case variants to the interplay deftests — `anthropic_test.clj`
  `configured-key-plus-recognized-auth-header-interplay-test` gains
  exact-case `x-api-key` custom header → REPLACES the configured key (equal
  string-key merge); `openai_completions_test.clj` gains lowercase
  `authorization` custom header → DUPLICATES beside the base `Authorization`
  (different casing = distinct keys). `doc/custom-providers.md` "Local
  servers and custom headers" merge sentence tightened to name the
  case-dependence (mixed-case X-API-Key duplicates beside lowercase
  x-api-key on anthropic; exact-case x-api-key replaces it; exact-case
  Authorization replaces on openai transports; lowercase authorization
  duplicates beside it).
- **Codex interplay lock + doc naming (review-14 item 2):**
  `openai_test.clj` `codex-configured-key-plus-recognized-auth-header-interplay-test`
  (added by a concurrent review pass in the shared tree; verified here) locks
  the codex transport's identical merge behavior — custom `Authorization`
  replaces the resolved codex bearer key (chatgpt-account-id still derived
  from the configured key), custom `X-API-Key` coexists with the bearer
  header. `doc/custom-providers.md` merge sentence now names all three
  transports (`:anthropic-messages`, `:openai-completions`,
  `:openai-codex-responses`).
- **design.md revision note completed (review-14 item 4):** added the missing
  provider-transport bullets — OAuth content-sniff gating (review 11),
  case-insensitive capture redaction (reviews 7/11/13), custom-provider
  origin tagging (`:custom?`, review 14), and the shared request-support
  namespace (review 14, pure refactor) — and updated the AC exception wording
  to name all of them.
- **Scheduler-lifecycle full-suite flake verified pre-existing + inventoried
  (review-14 item 1):** `psi.agent-session.scheduler-lifecycle-test/
  scheduled-deliver-runs-canonical-prompt-lifecycle-test` (documented
  ~1-in-8 full-suite flake in its own comment: session phase `:streaming`
  instead of `:idle`, no assistant message, no lifecycle entries) is
  byte-identical between the task base commit (71d4821bf, the first task-248
  commit) and HEAD, and the whole `components/agent-session/` directory has
  zero diff across the task's commit range — the flake is pre-existing and
  unrelated to this task's changed files. Passes in isolation (4 tests / 26
  assertions; the "only pending schedules can fire" dispatch warning fires
  even on passing runs). Added to the flake inventory alongside the two
  retry-loop flakes (`response-mode-retry-test`,
  `prompt-provider-retry-after-tool-result...`): full-suite `bb test` is not
  deterministically green on any single run, independent of this task.
- Verification: full `bb test` green (2566 tests / 19264 assertions /
  0 failures; +4 tests vs the committed 2562 = the three new review-14
  deftests + the concurrent codex interplay deftest; assertion count varies
  run-to-run per the review-5 flake analysis).
  `psi.ai.providers.anthropic-test` 20/140,
  `psi.ai.providers.openai-completions-test` 16/70,
  `psi.ai.providers.openai-test` 15/81 (incl. the concurrent codex interplay
  test and the custom-codex-provider-named-"openai" block),
  `psi.ai.providers.anthropic-stream-test` 10/94,
  `psi.ai.providers.openai-request-headers-test` 6/28,
  `psi.ai.user-models-test` 15/105 green (14→15 tests = the new
  custom-tagging deftest; 97→105 assertions); clj-kondo clean (0 errors, 0
  warnings) on all changed source + test files.
- `schemas/Model` (schemas.clj) gained `[:custom? {:optional true}
  [:maybe boolean?]]` — the canonical (closed-map) Model schema must accept
  the new origin tag, or custom-model parse tests that validate against it
  fail (caught by `psi.ai.textual-tool-calls-test/
  textual-tool-call-capability-schema-test` on the first full-suite run;
  fixed and re-run green).
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design (steps.md item
  left unchecked).

## Review 14 finalization pass (2026-08-07)

- Review-14 items verified end-to-end on the committed tree (34f3cc404); this
  pass adds the empirical flake verification (review-14 item 1) and one
  verification catch.
- **Scheduler-lifecycle flake — empirical verification (review-14 item 1):**
  a full-suite run on the committed tree with the review-recorded failing
  seed (`--seed 1741154775`) reproduced the flake exactly — 2561 passed /
  1 failed (`scheduled-deliver-runs-canonical-prompt-lifecycle-test`, 5
  failed assertions: session phase `:streaming` instead of `:idle`, no
  assistant message, no `:scheduler/deliver` /
  `:session/prompt-record-response` / `:session/prompt-finish` entries). A
  full-suite run at the task base commit (3c286a46e, parent of the first
  task-248 commit) with the SAME seed passed (2548 tests / 0 failures) —
  consistent with a probabilistic ~1-in-8 timing race (a single run has
  ~88% chance of not reproducing, and the base test set differs so the
  kaocha shuffle differs). `components/agent-session/` is byte-identical
  across the task (zero diff base→HEAD; the test file was last touched by
  task #201, ab526eee8), so the race is definitionally pre-existing and
  independent of this task's changed files. A further full-suite run
  (random seed 941216726) reproduced it again — seed-independent. Added to
  the flake inventory alongside the two retry-loop flakes and the committed
  byte-identity entry.
- **anthropic_test.clj file-length breach fixed (verification catch):** the
  extensions commit-checks suite
  (`file-length-check-enforces-real-legacy-ratchets-test`) failed
  deterministically on the committed tree —
  `components/ai/test/psi/ai/providers/anthropic_test.clj` had grown to 943
  lines (limit 800) through the accumulated review-driven test additions
  (already 858 at the review-13 address commit, 16e5fdc24). Split the
  cohesive anthropic-provider auth cluster into a new
  `components/ai/test/psi/ai/providers/anthropic_auth_test.clj` (389 lines):
  `build-request-no-auth-header-custom-provider-test`,
  `configured-key-plus-recognized-auth-header-interplay-test`,
  `build-request-oauth-injects-claude-code-system-test`,
  `build-request-oauth-gated-on-builtin-models-test`,
  `custom-provider-named-anthropic-not-builtin-test`, plus the private
  `claude-code-system` helper. `anthropic_test.clj` is now 579 lines.
  Behavior-preserving: 20 tests / 140 assertions across the two namespaces
  (unchanged total), cljfmt + clj-kondo clean.
- Verification: extensions suite green (364 passed / 0 failed — the
  file-length check passes again); full unit suite green on the split tree
  (2566 tests / 19264 assertions / 0 failures); cljfmt + clj-kondo clean on
  all changed files.
- Review 15 (2026-08-07): added 4 steps to be addressed.

## Follow-ups review 15 addressed (2026-08-07)

- addressed 4 review steps (review-15; review-1 optional live smoke test
  remains BLOCKED on missing DEEPSEEK_API_KEY)
- Codex adaptive-thinking no-op lock (review-15 item 1):
  `openai_test.clj` gains
  `codex-adaptive-thinking-ignored-for-custom-providers-test` — a custom
  `:openai-codex-responses` model (`:custom? true`, mirroring expand-model's
  origin tag) built with and without `:adaptive-thinking true` (+
  `:thinking-level :high`) via `build-codex-request` yields byte-identical
  bodies, no `:output_config`/adaptive leakage, and the unchanged classic
  `reasoning {:effort "high" :summary "auto"}` shape — mirroring the
  review-10 completions lock for the codex transport added to the docs claim
  in review 13.
- Fixture origin-tag drift closed (review-15 item 2): `:custom? true` added
  to `deepseek-custom-provider-model` (anthropic_test.clj) and the literal
  model map in `stream-anthropic-retries-adaptive-shape-without-thinking-
  on-400-test` (anthropic_stream_test.clj) so both match the review-14
  expand-model shape (every custom models.edn model is origin-tagged).
- getenv indirection deduplicated (review-15 item 3): `user_models.clj`'s
  private `getenv` removed — `resolve-api-key-spec`'s `env:` lookup now
  delegates to the shared `request-support/getenv`; the config-parse layer
  keeps no separate indirection. `parse-documented-deepseek-example-test`
  now redefs `psi.ai.providers.request-support/getenv` to the sentinel.
  Env-lookup testability lives in one place (user_models_test.clj gains the
  request-support require).
- File-length gate (review-15 item 4): resolved by the committed split
  (b9571cfac) — `anthropic_auth_test.clj` (389 lines) + `anthropic_test.clj`
  (579 lines); `bb commit-check:file-lengths` and the extensions
  commit-checks suite pass on the committed tree (364 passed / 0 failed).
- Verification: full unit suite green (2566 tests / 19264 assertions /
  0 failures); extensions suite green (364 passed / 0 failed); affected
  namespaces green on a focused run (77 tests / 493 assertions across
  user-models, anthropic, anthropic-auth, anthropic-stream, openai and
  openai-completions; +1 test = the new codex adaptive-thinking deftest);
  cljfmt + clj-kondo clean (0 errors, 0 warnings) on all changed files.

## Follow-ups review 15 — final verification pass (2026-08-07)

- addressed 4 review steps (verified end-to-end on the converged tree;
  review-1 optional live smoke test remains BLOCKED on missing
  DEEPSEEK_API_KEY)
- Full `bb test` re-run on the converged tree: 2567 tests / 18558 assertions
  / 0 failures. Count reconciliation: 2567 = 2566 (committed b9571cfac) + 1
  (the new `codex-adaptive-thinking-ignored-for-custom-providers-test`
  deftest — the review-15 entry above records the pre-deftest 2566);
  assertion count varies run-to-run per the review-5 flake analysis (18558
  in the observed band).
- Affected namespaces green on the final tree: 77 tests / 493 assertions —
  user-models 15/105, anthropic 15/98 + anthropic-auth 5/42 (the review-14
  split), anthropic-stream 10/94, openai 16/84 (incl. the codex
  adaptive-thinking no-op lock), openai-completions 16/70.
- `bb commit-check:file-lengths` green (anthropic_test.clj 576 lines /
  anthropic_auth_test.clj 386 lines, both under the 800-line gate);
  clj-kondo clean (0 errors, 0 warnings) on all changed source + test files.

- Review 16 (2026-08-07): added 2 steps to be addressed.
- addressed 2 review-16 steps (steps.md `^- [x]` checklist artifacts fixed on
  all 6 lines; openai/codex api-key config deduplicated into shared
  `request-support/openai-api-key-config`). Review-1 optional live smoke test
  remains BLOCKED: `DEEPSEEK_API_KEY` not set in env. Targeted namespaces
  green (openai-completions 16/70, openai-codex 8/27, openai 16/84,
  openai-request-headers 6/28, openai-codex-retry 1/5); clj-kondo clean.

- Review 17 (2026-08-07): added 3 steps to be addressed.

## Follow-ups review 17 addressed (2026-08-07)

- addressed 3 review steps (review-17; review-1 optional live smoke test
  remains BLOCKED on missing DEEPSEEK_API_KEY)
- Stale cross-transport comment fixed (review-17 item 1):
  `providers/anthropic.clj` `build-request`'s keyless-logic comment no longer
  claims the OpenAI transport "only exempts on explicit :no-auth-header" —
  inaccurate since review 10 (both `:openai-completions` and
  `:openai-codex-responses` use the shared `request-support/no-auth?`, which
  also exempts a recognized auth header among custom `:headers`). The comment
  now states the keyless logic is shared with the OpenAI transports via
  `request-support/no-auth?` and describes the actual keyless conditions.
  Comment-only change; no behavior delta.
- Fixture origin-tag drift closed (review-17 item 2): `:custom? true` added
  to every remaining custom-provider fixture in the touched anthropic test
  files — `anthropic_stream_test.clj`
  (`stream-anthropic-captures-provider-request-and-response-test`: MiniMax,
  DeepSeek, and both `:provider :local-proxy` fixtures) and
  `anthropic_test.clj` (the "custom provider never falls back to
  ANTHROPIC_API_KEY" deepseek fixture, the `:my-anthropic-proxy` kebab-case
  env-suggestion fixture, plus the two MiniMax missing-auth fixtures in the
  same file), and — same drift class in a touched file — every custom
  fixture in `anthropic_auth_test.clj` (the review-15 split-out file:
  local-proxy keyless/interplay fixtures, deepseek incidental-headers and
  sk-ant-oat fixtures). All are non-built-in provider names, so the tag is
  behavior-neutral; fixtures now match the review-14 expand-model shape
  (every custom models.edn model is origin-tagged). The built-in
  `:provider :anthropic` fixtures in `build-request-oauth-*` tests
  (`models/get-model :sonnet-4.6`) are NOT tagged — they represent catalog
  models. Boundary note: the openai test files
  (`openai_completions_test.clj` / `openai_test.clj` /
  `openai_request_headers_test.clj`) were NOT in this item's named scope;
  their synthetic transport-mechanics fixtures (`:custom-chat`, `:local`,
  `:local3`, `:custom-codex`, `:my-codex-proxy`, …) remain untagged per
  those files' established pattern (tag only where semantically relevant —
  the custom-provider-named-"openai"/adaptive-thinking no-op tests carry the
  tag). If reviewers want the openai literal fixtures tagged too, that is a
  separate follow-up.
- Model-dispatch full-suite flake inventoried (review-17 item 3):
  `psi.agent-session.model-dispatch-test/model-thinking-dispatch-test`
  observed failing on a full-suite run at review time (seed 1846209693;
  2566 passed / 1 failed: dispatch log showed `:scheduler/drain-queue`
  instead of `:session/set-system-prompt`, 2 failed assertions). Verified
  pre-existing: passes in isolation (12 tests / 153 assertions green,
  re-verified 2026-08-07); `components/agent-session/` has zero diff across
  the task commit range (3c286a46e → HEAD, `git diff --stat` empty); the
  test file was last touched by 5c910d5d4 ("Add Opus 4.8 model support"),
  pre-task. Same scheduler-timing race class as the documented
  `scheduler-lifecycle-test` flake. Added to the flake inventory — full-suite
  `bb test` is not deterministically green on any single run, independent of
  this task: known races are now (1) `response-mode-retry-test`, (2)
  `prompt-provider-retry-after-tool-result...`, (3)
  `scheduler-lifecycle-test/scheduled-deliver-runs-canonical-prompt-
  lifecycle-test`, (4) `model-dispatch-test/model-thinking-dispatch-test`.
- Verification: `psi.ai.providers.anthropic-test` 15/98,
  `psi.ai.providers.anthropic-auth-test` 5/42,
  `psi.ai.providers.anthropic-stream-test` 10/94 green (unchanged counts —
  the `:custom?` fixture tags are behavior-neutral);
  clj-kondo clean (0 errors, 0 warnings) on all changed source + test files.
- Review 18 (2026-08-08): added 3 steps to be addressed.

## Follow-ups review 18 addressed (2026-08-08)

- addressed 3 review steps (all review-18 items; review-1 optional live smoke
  test remains BLOCKED on missing DEEPSEEK_API_KEY)
- Openai fixture origin-tag drift closed (review-18 item 1): `:custom? true`
  added to every synthetic custom-provider fixture in `openai_test.clj`
  (`:custom-codex`, `:my-codex-proxy`, `:local-codex`) and
  `openai_completions_test.clj` (`:custom-chat`, `:my-openai-proxy`,
  `:local-chat`, `:local3`), plus the bare `:local` fixtures in both files
  (same drift class, named in the review-17 boundary note) — 21 fixtures,
  all behavior-neutral (`builtin?` requires `:custom?` false, so the tag only
  changes built-in classification; none of these names are built-in).
  Already-tagged `"openai"`-named custom fixtures and built-in
  `:provider :openai` catalog fixtures untouched. Boundary: the custom
  `:local` fixtures in `openai_request_headers_test.clj` remain untagged —
  outside this item's named scope (mirrors the review-17 boundary for the
  openai files; available for a future follow-up if reviewers want them).
- project.edn deepseek-activation regression resolved (review-18 item 2 —
  decision: revert the activation): verified the delegate-review live test
  fails DETERMINISTICALLY on this machine too, not just CI — "unknown model
  deepseek/deepseek-v4-flash" (the live test snapshots the committed
  session profiles against a temp model registry containing only
  `local/test-model`, so the deepseek profiles are unresolvable everywhere,
  user-global models.edn notwithstanding — the review-2 note's "developer
  machine can resolve" hypothesis is incorrect for this test). Option (a)
  (treat b26f84f25 as intentional user-local override excluded from the AC)
  was therefore rejected: it would leave the task unable to demonstrate its
  AC ("`bb test` green") on any machine. Restored `.psi/project.edn` to the
  review-2-established committed default (ef4db8c0e state): built-in
  anthropic catalog profiles active, deepseek + openai maps kept commented
  with the existing explanatory note — the human's local deepseek workflow
  preference remains a one-line local flip and the file's own comment
  documents this convention (b26f84f25's only change was project.edn; the
  restore is byte-identical to ef4db8c0e's file). Delegate-review live test
  green again (3 tests / 21 assertions).
- Codex `chatgpt-account-id` interplay locked + documented (review-18 item
  3): `codex-configured-key-plus-recognized-auth-header-interplay-test`
  gains two blocks — configured key + custom `chatgpt-account-id` →
  custom replaces the derived value (configured key still sent as bearer
  Authorization); keyless (`:no-auth-header true`) + custom
  `chatgpt-account-id` → passes through unmodified (supplies an account id
  for a keyless request, no Authorization). doc/custom-providers.md "Local
  servers and custom headers" merge paragraph names the
  `:openai-codex-responses` override. No production code change — the
  behavior is inherent in `build-codex-request`'s `(merge base-hdrs
  custom)` (design AC forbids transport changes).
- NEW flake observed + inventoried (5th entry): a full-suite run (seed
  1292130533; 2566 passed / 1 failed) failed
  `workflow_judge_cancellation_test.clj/judge-turn-dispatch-cancel-cannot-
  land-between-final-read-and-prompt-submit-test` ("the judge prompt entry
  is ordered before the D31 cancel checkpoint, not after it"). Verified
  pre-existing: passes in isolation (8 tests / 34 assertions green);
  `components/agent-session/` has zero diff across the task commit range
  (3c286a46e → HEAD); the test file was last touched by 04861433f
  ("Fix tool-result duplication and workflow cancellation"), an ancestor of
  the task base — same scheduler-timing race class as the four documented
  flakes. Added to the flake inventory: known races are now (1)
  `response-mode-retry-test`, (2)
  `prompt-provider-retry-after-tool-result...`, (3)
  `scheduler-lifecycle-test/scheduled-deliver-runs-canonical-prompt-
  lifecycle-test`, (4) `model-dispatch-test/model-thinking-dispatch-test`,
  (5) `workflow_judge_cancellation_test/judge-turn-dispatch-cancel-cannot-
  land-between-final-read-and-prompt-submit-test`.
- Verification (state being closed): full `bb test` green — 2567 tests /
  19271 assertions / 0 failures (two prior runs each hit one documented/
  inventoried flake: judge-turn-dispatch-cancel..., then scheduler-lifecycle
  — both pass in isolation, pre-existing). `psi.ai.providers.openai-test`
  16/88 (was 16/84; +4 assertions from the two new interplay blocks),
  `psi.ai.providers.openai-completions-test` 16/70 (unchanged — tags
  behavior-neutral); delegate-review live test 3/21 green. clj-kondo clean
  (0 errors, 0 warnings) on all changed test files; `bb fmt:check` clean;
  `bb commit-check:file-lengths` clean.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment (verified again 2026-08-08); request-shaping coverage
  only by design (steps.md item left unchecked).

- Review 19 (2026-08-08): added 2 steps to be addressed.

## Follow-ups review 19 addressed (2026-08-08)

- addressed 2 review steps (all review-19 items; review-1 optional live smoke
  test remains BLOCKED on missing DEEPSEEK_API_KEY)
- Dual-casing auth-header capture leak fixed (review-19 item 1):
  `request-support/redact-headers` redacted only the FIRST case-insensitive
  match per auth-header name, so a wire request carrying both casings of the
  same auth header (base `x-api-key` + custom `X-API-Key`, or `Authorization`
  + `authorization`) leaked the duplicate VERBATIM into the
  `:on-provider-request` capture — contradicting the CHANGELOG "never persist
  verbatim" claim. New `find-headers` returns ALL case-insensitive matches
  (`find-header` delegates to it), and `redact-headers` applies the redactor
  to every match under its original key casing. Capture-path tests added on
  both transports (`anthropic_stream_test.clj` dual x-api-key/X-API-Key →
  both `***REDACTED***`; `openai_request_headers_test.clj` dual
  Authorization/authorization → both `Bearer ***REDACTED***`); each verified
  to FAIL against the old single-match implementation. Specs updated
  (`RedactRequestHeaders` in both provider allium files; the ∀-header ensure
  already models all-matches).
- `:without-all-betas` 400-fallback restored for keyless custom-header Bearer
  auth (review-19 item 2): `oauth-auth-request?` (anthropic/error.clj)
  classified ANY `Authorization: Bearer` header as OAuth, so a keyless custom
  provider's custom-header Bearer request (documented "Local servers and
  custom headers" pattern) kept ALL beta headers on a beta-related 400 retry
  and hard-failed. Narrowed to the transport's own OAuth signature
  (`Authorization: Bearer` AND `user-agent: claude-cli/…` AND `x-app: cli` —
  the exact headers `request-headers` sets only for genuine built-in OAuth
  requests), so keyless custom-header Bearer requests now get
  `:without-all-betas` (betas stripped, custom Authorization preserved).
  New `stream-anthropic-retries-without-all-betas-on-400-for-keyless-bearer-test`
  locks the fallback selection end-to-end (fast-mode beta cleared on retry,
  `:retry-fallback-steps [:without-all-betas]`, stream completes) + direct
  `oauth-auth-request?` predicate assertions; verified to FAIL against the
  old any-Bearer predicate. `spec/anthropic-provider.allium` 400-retry
  section updated (`BearerAuthRequest` → `OAuthAuthRequest` with the full
  signature); `doc/custom-providers.md` fast-mode note states the beta
  stripping applies to keyless custom-header Bearer requests (only genuine
  built-in OAuth keeps betas; DeepSeek never is one). The error diagnostics
  no longer label a keyless Bearer request `auth=oauth`.
- Verification (state being closed): full `bb test` green — 2568 tests /
  19285 assertions / 0 failures (+1 deftest vs 2567); extensions suite green
  (364 tests / 1566 assertions / 0 failed);
  `psi.ai.providers.anthropic-stream-test` 11/106 (was 10/94; +1 deftest +
  dual-casing capture block), `psi.ai.providers.openai-request-headers-test`
  6/30 (was 6/28; +2 assertions); affected namespaces green (67 tests / 403
  assertions across anthropic, anthropic-auth, openai-completions, openai,
  user-models); clj-kondo clean (0 errors, 0 warnings); `bb fmt:check` clean;
  `bb commit-check:file-lengths` clean.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment (verified again 2026-08-08); request-shaping coverage
  only by design (steps.md item left unchecked).
- Review 20 (2026-08-08): added 2 steps to be addressed.

## Follow-ups review 20 addressed (2026-08-08)

- addressed 2 review steps (review-20; review-1 optional live smoke test
  remains BLOCKED on missing DEEPSEEK_API_KEY)
- `:custom?` origin tag added to the last untagged custom-provider fixtures:
  all five `:provider :local` fixtures in `openai_request_headers_test.clj`
  (two identity-capture fixtures, three redaction-capture fixtures) now carry
  `:custom? true`, closing the review-18-deferred boundary in the touched
  test files. Behavior-neutral (`:local` never collides with built-in names;
  `builtin?` requires the tag absent). `psi.ai.providers.openai-request-
  headers-test` green (6 tests / 30 assertions); clj-kondo clean; file-length
  gate passes (294 lines < 800).
- Trailing-slash `:base-url` inconsistency documented (docs option, in scope;
  transport normalization deliberately not done — design AC forbids transport
  changes): `doc/custom-providers.md` `:base-url` bullet now states the API
  root must have no trailing slash (psi concatenates the protocol path suffix
  verbatim — `/v1/messages`, `/chat/completions`, `/codex/responses`; only
  codex normalizes a trailing slash away, so a trailing `/` silently yields a
  double-slash URL), and the DeepSeek example notes gain a matching bullet
  with the concrete `https://api.deepseek.com/anthropic/` → `//v1/messages`
  example. Doc-parse-lock test unaffected (EDN block untouched; notes prose
  only): `psi.ai.user-models-test` green (15 tests / 105 assertions).
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design (steps.md item
  left unchecked).

## Review 21 (2026-08-08)

- Review 21: added 3 steps to be addressed.

## Follow-ups review 21 addressed (2026-08-08)

- addressed 3 review steps (review-21; review-1 optional live smoke test
  remains BLOCKED on missing DEEPSEEK_API_KEY)
- DeepSeek example locality/tier misclassification fixed (docs + parse-lock +
  fixtures): `doc/custom-providers.md` DeepSeek example model map now sets
  `:locality :cloud` / `:latency-tier :low` / `:cost-tier :low` explicitly;
  "What a provider definition contains" now documents the three fields and
  their `model-defaults` (`:locality :local` / `:latency-tier :low` /
  `:cost-tier :zero`) plus the local-helper-selection consequence
  (context-manager requires `:latency-tier :low` + `:cost-tier #{:zero
  :low}`, strong `:locality :local`, non-local guard — a cloud model with
  defaulted locality can be selected for/charged as a "local" helper);
  DeepSeek notes gain the explicit-values bullet.
  `parse-documented-deepseek-example-test` (user_models_test.clj) now
  asserts `:locality :cloud` / `:latency-tier :low` / `:cost-tier :low` on
  the parsed doc example (guard locks; +3 assertions). Fixtures aligned:
  `deepseek-custom-provider-model` (anthropic_test.clj) and the literal
  deepseek fixture in `anthropic_stream_test.clj`'s adaptive-shape 400-retry
  test (same drift class; behavior-neutral — none of the transport paths
  read locality/tier).
- `spec/openai-provider.allium` keyless request construction modeled
  (spec-only): new `KeylessCompletionsRequestBuilt` /
  `KeylessCodexRequestBuilt` rules (`requires: stream.keyless`; body
  identical to the authenticated build, guidance documents omitted
  `Authorization` and — codex — omitted `chatgpt-account-id` with the
  custom-header-supplied account-id pass-through), and the authenticated
  `CompletionsRequestBuilt`/`CodexRequestBuilt` rules gained an explicit
  `requires: not stream.keyless` (self-documenting partition; keyless → nil
  key per `OpenAIApiKeyResolved` already implied it). Manual allium-check
  (no automated checker in repo): all referenced entities/attributes
  defined in the spec.
- `chatgpt-account-id` capture masking locked: `openai_codex_test.clj` gains
  `codex-chatgpt-account-id-capture-masked-test` — keyless
  `:openai-codex-responses` stream request with custom
  `:headers {"chatgpt-account-id" "acc_1234567890" "ChatGPT-Account-Id"
  "acc_0987654321"}` asserts BOTH casings masked to first-6-chars + "..."
  in the `:on-provider-request` payload (review-19 dual-casing semantics via
  shared `find-headers`-based `redact-headers`) and no `Authorization` on a
  keyless request. Placed in `openai_codex_test.clj` (existing codex
  transport test home) rather than `openai_test.clj`: adding to
  `openai_test.clj` pushed it to 830 lines, failing the repo
  `commit-check:file-lengths` gate (committed 775); the move keeps
  `openai_test.clj` at its committed 775 and the gate green
  (`openai_codex_test.clj` 267 lines). Discovered + recorded: the seven
  codex deftests still in `openai_test.clj`
  (`codex-requires-chatgpt-token`, `codex-reasoning-*`,
  `codex-tool-call-id-roundtrip`, `codex-function-call-done`) are
  byte-identical duplicates of `openai_codex_test.clj` copies — pre-existing
  (openai_codex_test.clj predates this task, commit 008b1e094), outside
  review-21 scope; flagged here for a future dedup.
- Verification (state being closed): full `bb test` green — 2569 tests /
  19291 assertions / 0 failures (+1 deftest vs 2568 = the new codex
  capture-masking test; +6 assertions = 3 parse-lock locality/tier + 3
  capture-mask; assertion count varies run-to-run per the review-5 flake
  analysis); `psi.ai.user-models-test` 15/108 (was 15/105),
  `psi.ai.providers.anthropic-test` 15/98, `psi.ai.providers.anthropic-auth-
  test` 5/42, `psi.ai.providers.anthropic-stream-test` 11/106,
  `psi.ai.providers.openai-test` 16/88 (unchanged),
  `psi.ai.providers.openai-codex-test` 9/30 (was 8/27) green; clj-kondo
  clean (0 errors, 0 warnings) on all changed source/test files; `bb
  fmt:check` clean; `bb commit-check:file-lengths` clean.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment; request-shaping coverage only by design (steps.md item
  left unchecked).

- Review 22 (2026-08-08): added 4 steps to be addressed.

## Follow-ups review 22 addressed (2026-08-08)

- addressed 4 review steps (all review-22 items; review-1 optional live smoke
  test remains BLOCKED on missing DEEPSEEK_API_KEY)
- 400-fallback OAuth decision threaded from build-request (review-22 item 1):
  `build-request` attaches the transport's COMPUTED `oauth?` boolean
  (built-in Anthropic model + OAuth-shaped key, review 11) to the request map
  as `::oauth?`; `handle-400-response!` passes `:oauth-auth-request? (fn [req]
  (boolean (::oauth? req)))` in the beta-config, replacing the header
  content-sniff for the `:without-all-betas` selection. A keyless custom
  `:anthropic-messages` provider whose custom `:headers` reproduce all three
  Claude Code CLI markers (Authorization Bearer + user-agent: claude-cli/… +
  x-app: cli) is no longer classified OAuth — on a beta-related 400 it gets
  `:without-all-betas` (betas stripped, custom headers preserved) instead of
  retaining every beta, repeating the 400 and hard-failing (review-19
  regression class). New
  `stream-anthropic-400-fallback-uses-transport-oauth-decision-test`
  (keyless custom provider, three markers + `:speed-mode :fast` → 400 →
  `[:without-all-betas]`, stream completes) — verified to FAIL against the
  old content-sniffing predicate (6 assertions) and pass with the fix. The
  content-sniffing `oauth-auth-request?` (error.clj) is kept for error
  diagnostics only. `spec/anthropic-provider.allium` HTTP-400 section updated
  (fallback decision = `stream.oauth`, not header content-sniff); the spec's
  stale "OpenAI transport only exempts on explicit :no-auth-header" comment
  in `KeylessRequestDetermined` also fixed.
- `resolve-api-key` keyless early-return unified with `no-auth?` (review-22
  item 2): `request-support/resolve-api-key` now computes its keyless
  early-return via the shared `no-auth?` predicate instead of testing only
  `(:no-auth-header options)` — the keyless contract lives in one predicate
  and the public function is safe for direct callers (headers-auth keyless →
  nil; `:no-auth-header` keyless → nil; blank-key non-keyless → throws).
  Behavior-preserving for the three transports (they gate on `no-auth?`
  first anyway). New shared-namespace test file
  `components/ai/test/psi/ai/providers/request_support_test.clj`
  (`resolve-api-key-keyless-contract-test` + `no-auth?-predicate-test`).
- `:supports-mid-conversation-system-messages` documented + schema-gated
  (review-22 item 3): the closed `ModelDef` schema in `user_models.clj` gains
  the optional boolean field (previously models.edn custom providers could
  not declare the capability at all — the canonical `Model` schema already
  had it); it flows through `expand-model`'s verbatim merge. `doc/
  custom-providers.md` "What a provider definition contains" documents the
  field (gates `:session/inject-mid-system-message`; default false for
  `:anthropic-messages` custom providers; `:openai`/`:openai-completions`
  inferred) and the DeepSeek example notes state the example does NOT enable
  it (DeepSeek compat lists `system` fully supported but per-turn switching
  unverified — set it only after live verification; deliberately not added to
  the example EDN). New `supports-mid-conversation-system-messages-field-test`
  (user_models_test.clj); `spec/custom-providers.allium`
  `CustomModelDef`/`ResolvedCustomModel`/`ParseModelsConfig` carry the field;
  CHANGELOG `[Unreleased]` → `Added` entry added.
- Custom `anthropic-beta` header interplay documented + tested (review-22
  item 4; docs option chosen — making `:without-all-betas` strip only
  transport-known betas would be a transport change the design AC forbids):
  `doc/custom-providers.md` "Local servers and custom headers" documents that
  a custom `"anthropic-beta"` header REPLACES the transport-generated betas
  on the wire (transport-gated features e.g. fast mode stop working) and that
  `:without-all-betas` wipes the custom beta too on a beta-related 400 (the
  retry may then 400 for a different reason, masking the original error).
  Tests: `build-request-custom-anthropic-beta-header-replaces-transport-betas-test`
  (anthropic_test.clj — custom beta wins the merge over fast-mode +
  interleaved-thinking; without it the transport betas are sent) and
  `stream-anthropic-custom-anthropic-beta-header-stripped-by-without-all-betas-test`
  (anthropic_stream_test.clj — custom beta on first request, 400 →
  `[:without-all-betas]`, retried request has no `anthropic-beta` at all,
  configured `x-api-key` preserved, stream completes). CHANGELOG `[Unreleased]`
  → `Fixed` entry added for the 400-fallback OAuth fix.
- File-length gate: the two new stream deftests pushed
  `anthropic_stream_test.clj` to 878 lines (> 800 gate). Split the cohesive
  HTTP-400 compatibility-retry cluster (6 deftests, incl. the two new ones)
  into a new `components/ai/test/psi/ai/providers/anthropic_retry_test.clj`
  (421 lines); `anthropic_stream_test.clj` is now 479 lines and no longer
  requires `anthropic.error` (the `run-stream` SSE-parser helper moved back
  with the SSE-parser tests it serves). `bb commit-check:file-lengths` green.
- Verification (state being closed): full `bb test` unit suite green — 2575
  tests / 19337 assertions / 0 failures (+6 deftests vs the committed 2569:
  2 request-support, 1 user-models mid-system, 2 anthropic-stream/retry, 1
  anthropic build-request; assertion count varies run-to-run per the review-5
  flake analysis — no flake observed on this run); extensions suite green
  (364 passed / 0 failed / 1566 assertions; the summary's "1 unknown" is the
  pre-existing `:integration`-meta skip in the extensions suite, unrelated to
  this task); `psi.ai.user-models-test` 16/114 (was 15/108),
  `psi.ai.providers.anthropic-test` 16/104 (was 15/98),
  `psi.ai.providers.anthropic-stream-test` 7/65 + `psi.ai.providers.anthropic-retry-test`
  6/58 (was 11/106 pre-split; counts unchanged across the split),
  `psi.ai.providers.request-support-test` 2/18 (new),
  `psi.ai.providers.openai-test` 16/88, `psi.ai.providers.openai-completions-test`
  16/70, `psi.ai.providers.openai-codex-test` 9/30, `psi.ai.providers.
  openai-request-headers-test` 6/30 green; clj-kondo clean (0 errors, 0
  warnings) on all changed source + test files; `bb fmt:check` clean;
  `bb commit-check:file-lengths` clean.
- Review-1 optional live smoke test remains BLOCKED: `DEEPSEEK_API_KEY` not
  set in environment (verified again 2026-08-08); request-shaping coverage
  only by design (steps.md item left unchecked).
- Review 23 (2026-08-08): added 3 steps to be addressed.

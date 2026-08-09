# Steps

## Slice 1 — schema + tests

- [x] Add `[:adaptive-thinking {:optional true} [:maybe boolean?]]` to
      `ModelDef` in `components/ai/src/psi/ai/user_models.clj`.
- [x] `user_models_test.clj`: test that a model def with
      `:adaptive-thinking true` parses without error and the expanded model
      map has `:adaptive-thinking true`.
- [x] `user_models_test.clj`: test that omitting `:adaptive-thinking`
      remains valid (no error) and the expanded model map has it
      absent/falsy (unchanged current behaviour).
- [x] `anthropic_test.clj`: add a request-shaping test using a raw
      DeepSeek-shaped custom-provider model map (`:provider :deepseek`,
      `:api :anthropic-messages`, `:adaptive-thinking true`,
      `:supports-reasoning true`, plus the other resolved fields) that
      asserts `output_config.effort` and `thinking.type "adaptive"` appear
      (no `budget_tokens`), mirroring `build-request-adaptive-thinking-test`
      but proving it works off a non-catalog model map.
- [x] Run `bb clojure:test:scry --namespace psi.ai.user-models-test` and
      `bb clojure:test:scry --namespace psi.ai.providers.anthropic-test`;
      confirm green.

## Slice 2 — docs + changelog

- [x] `doc/custom-providers.md`: add a "DeepSeek-compatible example"
      subsection (after the existing "Anthropic-compatible example"
      section) with the resolved `models.edn` snippet
      (`https://api.deepseek.com/anthropic`, `:anthropic-messages`,
      `deepseek-v4-flash`, `env:DEEPSEEK_API_KEY`, `:adaptive-thinking
      true`, resolved pricing/context-window/max-tokens).
- [x] `doc/custom-providers.md`: document the new `:adaptive-thinking`
      field — what it does, that it only applies to `:api
      :anthropic-messages` custom providers, and when to set it (provider
      supports Anthropic's adaptive `output_config.effort` request shape).
- [x] CHANGELOG `[Unreleased]` → `Added`: entry announcing the DeepSeek
      custom-provider example and the new `:adaptive-thinking` custom-model
      field.

## Verification

- [x] `bb clojure:test:scry --namespace psi.ai.user-models-test` green
      (13 tests, 77 assertions).
- [x] `bb clojure:test:scry --namespace psi.ai.providers.anthropic-test` green
      (15 tests, 84 assertions).
- [x] `clj-kondo --lint components/ai/src` clean (0 errors, 0 warnings).
- [x] Re-read `doc/custom-providers.md` end to end — existing examples
      unaffected, DeepSeek subsection follows established pattern.
- [x] `bb commit-check:file-lengths` passes on the committed tree —
      `anthropic_test.clj` split into `anthropic_auth_test.clj` (review 15;
      both files under the 800-line gate).
- [x] Committed.

## Follow-ups (implementation review, 2026-08-07)

- [x] `anthropic_test.clj` `build-request-adaptive-thinking-custom-provider-test`:
      assert headers and temperature for the DeepSeek-shaped map, not just
      body — `x-api-key` from the configured key (no `Authorization`/OAuth
      path), `anthropic-version` present, no forced `anthropic-beta`
      (no `interleaved-thinking`), and `:temperature` absent (adaptive
      models never send it, even thinking off — mirror the sibling
      `build-request-adaptive-thinking-test` assertions). Design AC requires
      "headers, URL, body"; body alone is currently proven.
- [x] Cover the base-url-derived request URL: `build-request` does not build
      the URL (it is `(str (:base-url model) "/v1/messages")` in
      `stream-anthropic`/`execute-anthropic`), so no test proves
      `https://api.deepseek.com/anthropic/v1/messages` is derived. Either
      assert at the stream/execute seam or extract a small `request-url` fn
      and test it.
- [x] `doc/custom-providers.md` `:adaptive-thinking` section: note that
      adaptive-thinking models never send `temperature` (even with thinking
      off), so `:adaptive-thinking true` forfeits temperature control —
      DeepSeek's compat table lists temperature as fully supported, so this
      trade-off belongs in the DeepSeek example notes so users can choose.
- [x] Full `bb test` (unit + extension suites) to satisfy the design AC
      "`bb test` green" — implementation verified only the two targeted
      namespaces plus `clj-kondo`; full-suite run not demonstrated.
- [ ] Optional manual smoke test before close: configure DeepSeek in a real
      `models.edn`, select `deepseek-v4-flash`, run one live turn to confirm
      DeepSeek accepts the request (x-api-key auth, `/v1/messages` path,
      adaptive `output_config.effort`). Automated tests are request-shaping
      only by design; needs a `DEEPSEEK_API_KEY`. BLOCKED: `DEEPSEEK_API_KEY`
      not set in env (recorded in implementation.md).

## Follow-ups (implementation review 2, 2026-08-07)

- [x] Full `bb test` is currently RED — `delegate-review-task-
      implementation-completes-with-nullable-local-model-test` fails because
      committed `.psi/project.edn` (f0c818cc1) points every workflow session
      profile at `deepseek/deepseek-v4-flash`, which is resolvable only via
      the user-local `~/.psi/agent/models.edn` (never committed, absent in
      test/CI) → "unknown model deepseek/deepseek-v4-flash" → all profiles
      invalid → delegation fails. Design AC "`bb test` green" is violated.
      Verified: passes at 3c286a46e (base, anthropic catalog profiles);
      fails identically at f0c818cc1 (whose only change is the project.edn
      deepseek wiring) — so the af7e05b46 note claiming this failure is
      "pre-existing (fails on base commit too)" is inaccurate; the
      regression is introduced by the committed deepseek workflow config.
      Fix options: point committed profiles back at catalog models (or make
      them conditional on the custom provider existing), add a committed
      test fixture `models.edn`, or make the live workflow test tolerate
      unresolvable profiles.
      → Resolved: restored `.psi/project.edn` committed profiles to the
      built-in anthropic catalog models (always resolvable in CI); kept the
      deepseek profile map commented out with a note that it requires the
      user-global custom provider. Full `bb test` green (2550 tests / 19132
      assertions, 0 failures); targeted live test green (3 tests / 21
      assertions).
- [x] `:adaptive-thinking true` forfeits thinking-off control on DeepSeek:
      psi's adaptive path omits the `thinking` param entirely when thinking
      is off (`thinking-param` never emits `{:type "disabled"}`), and
      DeepSeek's Anthropic endpoint defaults to thinking ON — so `/thinking
      off` on `deepseek-v4-flash` silently leaves thinking enabled (the
      design's "on/off control works" claim holds only for the on side).
      Verify live; then either document the caveat in the DeepSeek example
      or add an explicit `{:type "disabled"}` emission for adaptive models
      that need it, with a test.
      → Resolved: documented the caveat in the DeepSeek example notes
      (omission ≠ disabled on DeepSeek; endpoint defaults thinking ON; holds
      with or without `:adaptive-thinking`; explicit `{:type "disabled"}`
      is not emitted today). Chose documentation over the code change
      because live verification is blocked (no `DEEPSEEK_API_KEY` in env)
      and the design AC explicitly forbids changing
      `providers/anthropic.clj` request-shaping logic in this task.
- [x] Cache-cost accounting is unverified: `:cache-read-cost 0.0028` and
      `:cache-write-cost 0.14` assume DeepSeek's usage JSON matches psi's
      `cache_read_input_tokens`/`cache_creation_input_tokens` mapping, but
      no live probe has captured a real usage payload. Confirm the usage
      shape (and that `cache-write-cost` mirroring the miss rate does not
      double-count) or adjust the example costs; consider documenting the
      mirroring rationale in the docs example notes.
      → Resolved: documented in the DeepSeek example notes — psi bills
      cache usage from Anthropic-shaped
      `usage.cache_read_input_tokens`/`usage.cache_creation_input_tokens`;
      DeepSeek publishes no separate write price so `:cache-write-cost 0.14`
      mirrors the miss/input rate (Anthropic-style accounting reports the
      miss portion separately from `input_tokens`, so no double-count);
      the Anthropic field-name assumption is unverified against a live
      payload — adjust the example if DeepSeek's usage shape differs.

## Follow-ups (implementation review 3, 2026-08-07)

- [x] API-key resolution for custom `:anthropic-messages` providers:
      `anthropic/resolve-api-key` falls back to the `ANTHROPIC_API_KEY` env
      var when `:api-key` is nil and errors with an Anthropic-specific
      message ("Set ANTHROPIC_API_KEY or login via /login anthropic"). With
      the new DeepSeek docs example (`env:DEEPSEEK_API_KEY`), an unset
      `DEEPSEEK_API_KEY` (with `ANTHROPIC_API_KEY` set, common for psi
      users) would silently send the user's Anthropic key to
      `https://api.deepseek.com/anthropic/v1/messages` — cross-provider
      credential disclosure and a misleading error. Decide: (a) error when
      a custom provider's configured key resolves nil instead of falling
      back to the Anthropic env var (provider-scoped resolution), and/or
      (b) document the fallback in `doc/custom-providers.md` + add a test
      proving the fallback does not leak the Anthropic key to a non-
      anthropic provider.
      → Resolved: provider-scoped resolution implemented (in this change
      set): `resolve-api-key` now only falls back to `ANTHROPIC_API_KEY`
      for built-in Anthropic models (`:provider` nil or `:anthropic`);
      custom providers fail fast with a provider-scoped "Missing API key
      for provider <name>" error. Tests in `anthropic_test.clj` prove a
      custom provider never leaks the Anthropic key (redef'd `getenv` →
      deepseek request still throws) and built-in models still use the env
      fallback. Also documented the scoped behavior in the DeepSeek example
      notes (`doc/custom-providers.md`). `bb test` full suite green (2550
      tests / 19134 assertions); namespace green (15 tests / 92
      assertions).
- [x] `doc/custom-providers.md` `:adaptive-thinking` section: explicitly
      state the field is only meaningful for `:api :anthropic-messages`
      custom providers (and built-in Anthropic catalog models) — design
      acceptance criteria call this out; the current text only implies it
      via section placement ("Anthropic-compatible models may declare...").
      → Resolved: `doc/custom-providers.md` Adaptive thinking section now
      states the field is only meaningful for `:api :anthropic-messages`
      custom providers (and built-in Anthropic catalog models) and is
      ignored for OpenAI-compatible custom providers.

## Follow-ups (implementation review 4, 2026-08-07)

- [x] Keyless `:auth-header? false`/`:no-auth-header` custom
      `:anthropic-messages` providers now hard-fail: `anthropic/resolve-api-key`
      throws "Missing API key for provider <name>" for any blank key before
      `:no-auth-header` stripping can apply, even for local-proxy configs that
      legitimately send no auth (cf. workflow-step-session-config test with
      `:auth {:auth-header? false}` and no key). Regression vs. prior
      behavior: with `ANTHROPIC_API_KEY` set these configs previously
      succeeded (env key resolved, then stripped by `:no-auth-header`); the
      OpenAI `chat_completions` transport still handles this case (no throw,
      header simply omitted). This is a behavior change to existing
      custom-provider configs the design AC rules out. Fix: skip the key
      requirement when `:no-auth-header` is set, and add a test proving a
      keyless custom provider with `:no-auth-header true` builds a request
      with no `x-api-key`/`Authorization`.
      → Resolved: `anthropic/resolve-api-key` returns nil instead of failing
      when `:no-auth-header` is set; `build-request` skips key resolution
      whenever `:no-auth-header` is set OR custom `:headers` provide the auth
      (headers present with no configured key) and strips
      `x-api-key`/`Authorization` in that case. Tests prove a keyless
      `:no-auth-header` provider, a headers-only-auth provider (no
      `:no-auth-header`), and a configured-key-plus-headers provider all
      build correct requests.
- [x] New custom-provider "Missing API key" error suggests "or login via
      /login <provider>", but OAuth login only exists for built-in
      `:anthropic`/`:openai` providers — `/login deepseek` is not a real
      flow. Drop the login hint for custom providers or gate it on OAuth
      provider registration.
      → Resolved: custom-provider missing-key error no longer hints at
      `/login` — it names the `models.edn` `:auth {:api-key ...}` remedy only
      (OAuth login exists only for built-in providers). Test asserts no
      `/login` text in the custom-provider error.
- [x] design.md drift: it still claims "no core provider/transport code
      changes" and AC "no existing custom-provider behaviour changes" /
      "no code change to `providers/anthropic.clj`'s request-shaping logic
      itself, only the schema gate in `user_models.clj`", but the merged
      review-3 resolution modified `providers/anthropic.clj`
      (`resolve-api-key` + `getenv`) and changed custom-provider
      key-fallback behavior. Update design.md scope/AC (or add a revision
      note) so the design reflects the provider-scoped api-key resolution.
      → Resolved: design.md now has a "Revision note (implementation
      reviews)" section documenting the two review-driven
      `providers/anthropic.clj` changes (provider-scoped api-key resolution;
      `:no-auth-header` key tolerance) as the only provider-transport changes,
      and the scope/AC wording was updated to match.

## Follow-ups (implementation review 4, 2026-08-07)

- [x] `build-request`'s unconditional `resolve-api-key` call now throws for
      custom `:anthropic-messages` providers that intentionally send no auth:
      both `:auth-header? false` (the docs' own "Local servers and custom
      headers" pattern, `{:api-key "env:..." :auth-header? false :headers ...}`)
      and custom-`:headers`-only auth reach `build-request` with no `:api-key`
      in options (`provider-auth/provider-api-key` returns nil whenever
      `:auth-header?` is false), and `resolve-api-key` throws before the
      `:no-auth-header` header-stripping in `build-request` can apply. This is
      a regression from the review-3 provider-scoped key change: pre-change,
      the `ANTHROPIC_API_KEY` env fallback let such requests through (header
      then stripped). It is also inconsistent with the OpenAI transport, which
      skips the Authorization header when `:no-auth-header` is set without
      requiring a key. Verified: `build-request` with `{:no-auth-header true}`
      and with `{:headers {"X-API-Key" ...}}` (no api-key) both throw "Missing
      API key for provider <name>". Fix direction: skip key resolution (or
      tolerate a nil key) when `:no-auth-header` is set in options, and/or
      when custom `:headers` provide auth; add a test proving a no-auth /
      header-auth custom `:anthropic-messages` request builds without a key.
      → Resolved: `build-request` now computes `no-auth?` =
      `:no-auth-header` OR (custom `:headers` present with no configured
      `:api-key`) and skips `resolve-api-key` in that case, stripping
      `x-api-key`/`Authorization` from the base headers; `resolve-api-key`
      also returns nil for `:no-auth-header`. Tests cover keyless
      `:no-auth-header`, headers-only auth (no `:no-auth-header`), and
      key-plus-headers (both sent). `bb test` full suite green (2551 tests /
      18440 assertions); namespace green (16 tests / 107 assertions);
      clj-kondo clean.
- [x] `resolve-api-key`'s custom-provider error suggests "or login via /login
      <provider>", but `/login` only supports OAuth providers (anthropic,
      openai); `/login deepseek` fails with "Unknown OAuth provider". Drop the
      `/login` hint from the custom-provider branch (or restrict it to
      OAuth-capable providers) — the correct remedy is configuring the
      provider's `:auth {:api-key ...}` in models.edn.
      → Resolved: `/login` hint dropped from the custom-provider missing-key
      error; error names the `models.edn` `:auth` remedy. Test asserts the
      error mentions `models.edn` and contains no `/login`.

## Follow-ups (implementation review 5, 2026-08-07)

- [x] `spec/custom-providers.allium` (the formal spec `user_models.clj`'s ns
      docstring points to) was not updated for this task's behaviour changes:
      `CustomModelDef`/`ResolvedCustomModel` carry no `adaptive_thinking` field,
      and the auth rules do not reflect the provider-scoped api-key resolution
      (custom `:anthropic-messages` providers never fall back to
      `ANTHROPIC_API_KEY`) or the keyless `:no-auth-header`/headers-auth
      behavior introduced by reviews 3/4. AGENTS.md change chain requires
      `update(spec, δ|behaviour)` — add the field (and `ResolvedCustomModel`
      carry-through) plus the provider-scoped key-resolution/keyless rules,
      then run the allium-check workflow.
      → Resolved: `CustomModelDef` and `ResolvedCustomModel` now carry
      `adaptive_thinking` (carried through in `ParseModelsConfig`); new
      `ResolveRequestApiKey` rule models provider-scoped resolution (built-in
      Anthropic env fallback, custom-provider fast-fail naming the models.edn
      `:auth` remedy, keyless exemptions via `:no-auth-header` or a recognized
      auth header among custom `:headers`), with an `ExistsAuthHeader` rule
      (x-api-key / authorization only — incidental headers do not imply
      keyless); `InjectCustomProviderAuth`/`NoAuthHeaderWhenDisabled` updated
      for keyless configs. `anthropic-provider.allium`'s `ApiKeyResolved` rule
      updated to match (was unconditional `ANTHROPIC_API_KEY` fallback).
      allium-check performed manually (no automated allium checker in repo):
      spec now matches the implementation in all three behaviour areas.
- [x] `build-request`'s headers-implies-auth inference is too broad: with a
      blank configured key, ANY custom `:headers` makes `no-auth?` true, so a
      provider with incidental headers (e.g. `X-Client`) and an unset
      `env:DEEPSEEK_API_KEY` silently sends a keyless request (provider-side
      401) instead of fast-failing with the clear "Missing API key for
      provider <name>" error. The OpenAI transport only exempts on explicit
      `:no-auth-header`, so the two transports now disagree. Refine: treat
      custom headers as auth only when `:no-auth-header`/`:auth-header? false`
      is set OR a recognized auth header (`x-api-key`/`authorization`) is
      among them; add a test for the incidental-headers + blank-key case.
      → Resolved: `build-request` now only treats custom `:headers` as
      keyless-auth when a recognized auth header (`x-api-key` /
      `authorization`, case-insensitive, via new `auth-header?` helper) is
      among them and no `:api-key` is configured; incidental headers with a
      blank key now fast-fail with "Missing API key for provider <name>".
      Tests added: incidental `X-Client` + blank key throws (both with
      explicit `:api-key ""` and no key), and `Authorization`-header-only auth
      (no `:no-auth-header`) builds a keyless request. Namespace green (16
      tests / 111 assertions); clj-kondo clean.
- [x] Reconcile the full-suite verification counts in implementation.md:
      "Final verification pass" records 2550 tests / 19134 assertions while
      "Follow-ups review 4 addressed" records 2551 tests / 18440 assertions
      ("was 2550/19134") — the test count rose but the assertion count fell
      by 694, which is either a transcription error or unstable suite
      counts. Re-run `bb test` and record exact, consistent numbers.
      → Resolved (corrected): the assertion count is NOT stable run-to-run —
      fresh runs of identical code give 18435, 18444, 19148, 19149, 19153
      assertions (all 2551 tests / 0 failures on the default seed). The
      18440-vs-19134 delta is the same variance, not a transcription error.
      Root cause: a pre-existing flaky, timing-sensitive test
      (`psi.turn-runtime.response-mode-retry-test/execute-prepared-request-
      streaming-retry-discards-failed-partial-output-test`) whose retry-loop
      attempt count varies (2 vs 53 observed) — with `--seed 424242` it
      FAILS, turning the whole suite red, entirely outside this task's
      changed files; kaocha's per-run seed randomization then makes totals
      vary. The only stable, task-relevant deltas: test count 2550→2551
      (the added `build-request-no-auth-header-custom-provider-test`
      deftest) and namespace growth 92→111 assertions (added tests). Exact
      record: latest fresh `bb test` = 2551 tests / 19153 assertions /
      0 failures.

## Follow-ups (implementation review 6, 2026-08-07)

- [x] Parse-lock the exact documented DeepSeek `models.edn` example:
      `user_models_test.clj` covers only minimal deepseek model defs (id +
      supports-reasoning + adaptive-thinking), never the full documented
      example (pricing/context-window 1000000/max-tokens 384000 +
      `:adaptive-thinking true` + `:auth {:api-key "env:DEEPSEEK_API_KEY"}`)
      from `doc/custom-providers.md`. A parse test of the exact example
      would guard the closed `ModelDef`/`AuthConfig` schemas against
      docs/code drift (e.g. a future field typo silently making the
      documented example invalid).
      → Resolved: added
      `parse-documented-deepseek-example-test` in `user_models_test.clj`
      parsing the exact documented example and asserting every resolved
      field (id/name/provider/api/base-url, reasoning/adaptive-thinking/
      images/text flags, context-window 1000000, max-tokens 384000, all four
      costs) plus provider-scoped env auth resolution
      (`env:DEEPSEEK_API_KEY` via `resolve-api-key-spec`,
      `:auth-header? true`).
- [x] No request-shaping test proves the classic extended-thinking shape for
      a NON-catalog custom-provider model map: all `budget_tokens` shape
      tests use built-in catalog models (`build-request-with-thinking-test`,
      cache-control test), while custom-provider maps are only tested for
      missing-auth and the adaptive shape. The docs advise DeepSeek users
      who need temperature to fall back to `:adaptive-thinking false`/omitted
      ("relies on the classic extended-thinking shape DeepSeek accepts") —
      prove that path: a `build-request` test with a custom map
      (e.g. `deepseek-custom-provider-model` minus `:adaptive-thinking`) +
      thinking `:medium` asserting `{:type "enabled" :budget_tokens 8000}`
      and no `output_config`. Locks the AC "no custom-provider behaviour
      changes" for the classic path.
      → Resolved: added
      `build-request-classic-thinking-custom-provider-test` in
      `anthropic_test.clj` — `(dissoc deepseek-custom-provider-model
      :adaptive-thinking)` + `:medium` asserts
      `{:type "enabled" :budget_tokens 8000}`, no `output_config`, no
      `temperature`, and the `interleaved-thinking` beta header.
- [x] `speed` fast-mode is unverified against DeepSeek: psi sends
      `"speed": "fast"` in the request body when fast mode is on
      (`request-body` `:speed-mode`), but DeepSeek's Anthropic compat table
      does not list `speed` and Anthropic-compatible endpoints typically
      reject unknown body fields (400). The DeepSeek example notes enumerate
      unsupported features (images, structured output, temperature trade-off,
      thinking-off) but not this. Verify against a live turn or add a note
      documenting that fast mode is unsupported/unverified on
      `deepseek-v4-flash` (alongside the existing blocked live-smoke-test
      caveat).
      → Resolved: documented in the DeepSeek example notes
      (`doc/custom-providers.md`) — fast mode sends `"speed": "fast"` +
      `fast-mode-2026-02-01` beta header, not listed in DeepSeek's compat
      table, typical Anthropic-compatible 400 on unknown body fields,
      unverified live (blocked on missing `DEEPSEEK_API_KEY`); assume
      unsupported until verified.

## Follow-ups (implementation review 7, 2026-08-07)

- [x] DeepSeek `thinking.type "adaptive"` is unverified against DeepSeek's
      documented honored values: the compat table (per design.md) lists
      `thinking.type` honored as `"enabled"`/`"disabled"` only, but psi's
      adaptive request shape sends `thinking: {:type "adaptive" :display
      "summarized"}` (paired with `output_config.effort`). The DeepSeek
      example and the "output_config.effort reachable via adaptive shape"
      claim therefore rest on an unverified type value — if DeepSeek rejects
      or ignores `"adaptive"`, the documented example fails or silently
      degrades (thinking then defaults ON per DeepSeek behavior, compounding
      the already-documented thinking-off caveat). Verify against DeepSeek
      docs/live whether `type: "adaptive"` is accepted (the existing
      smoke-test step checks "adaptive output_config.effort" but not the
      `thinking.type` value), then either document the caveat in the DeepSeek
      example notes or adjust the example (e.g. note that only
      `output_config.effort` is confirmed supported and `thinking.type
      "adaptive"` behavior is unknown).
      → Resolved (documented, per the item's doc option; live verification
      blocked on missing `DEEPSEEK_API_KEY`): DeepSeek example notes now
      state `output_config.effort` is confirmed supported while
      `thinking.type "adaptive"` is NOT among DeepSeek's documented honored
      values (`type: "enabled"/"disabled"` only; "adaptive" appears nowhere
      in DeepSeek's Anthropic API docs, verified 2026-08-07); a strict
      endpoint may 400, a lenient one may ignore it leaving thinking ON;
      fall back to `:adaptive-thinking false` whose classic `type: "enabled"`
      IS a documented honored value.
- [x] Provider-request capture redaction is case-sensitive while auth-header
      recognition is not: `redact-request-headers` redacts only exact
      `"Authorization"`/`"x-api-key"` keys, but `build-request`'s
      `auth-header?` treats auth headers case-insensitively and the
      headers-auth keyless pattern (e.g. `:headers {"X-API-Key" "local-key"}`
      — the exact map `anthropic_test.clj` proves keyless) would persist the
      secret unredacted. turn-runtime appends `:on-provider-request` captures
      to the session capture store, so a capitalized/mixed-case auth header
      (X-API-Key, X-Api-Key, lower-case authorization) leaks verbatim into
      stored captures. Make redaction case-insensitive (reuse `auth-header?`
      or normalize header names) and add a capture-path test asserting a
      custom `X-API-Key` header is `***REDACTED***` in the
      `:on-provider-request` payload, mirroring the existing lowercase
      `x-api-key` assertion in `anthropic_stream_test.clj`.
      → Resolved: `redact-request-headers` in `providers/anthropic.clj` is
      now case-insensitive via a new `find-header` helper (matches header
      names case-insensitively, redacts under the original key casing);
      `anthropic_stream_test.clj` gains a capture-path test proving a keyless
      custom-provider request with `:headers {"X-API-Key" "local-key"}` is
      redacted to `***REDACTED***` in the `:on-provider-request` payload.
- [x] `doc/custom-providers.md` "Local servers and custom headers" overstates
      the keyless inference: "or auth carried entirely by custom `:headers`"
      implies any custom headers make a request keyless, but `build-request`
      only infers keyless from custom headers when a RECOGNIZED auth header
      name (`x-api-key`/`authorization`, case-insensitive) is among them and
      no key is configured — a provider with only incidental headers (e.g.
      `X-Client`, the section's own example) and an unset key fast-fails
      with "Missing API key for provider <name>". Tighten the sentence to
      name the recognized-auth-header requirement, and align the DeepSeek
      example note's "fails fast ... unless `:auth-header? false`" wording,
      which omits the same recognized-auth-header exemption.
      → Resolved: "Local servers and custom headers" now states the keyless
      inference requires `:auth-header? false` OR a recognized auth header
      (`x-api-key`/`Authorization`, case-insensitive) among custom `:headers`
      with no configured key, and that incidental headers (e.g. `X-Client`)
      do NOT imply keyless — they fast-fail with the provider-scoped
      "Missing API key" error. DeepSeek example api-key note aligned to name
      the same recognized-auth-header exemption.

## Follow-ups (implementation review 8, 2026-08-07)

- [x] Adaptive `output_config.effort` values "medium" and "highest" are
      outside DeepSeek's documented effort set: the DeepSeek example notes
      quote the Thinking Mode guide's Anthropic-format effort control as
      `{"output_config": {"effort": "low/high/max"}}`, but psi sends "medium"
      (`/thinking medium` → `thinking-level->effort`) and "highest"
      (`/thinking xhigh`; also `effort-override :xhigh`) and never emits
      DeepSeek's "max". "low" (minimal/low) and "high" overlap the documented
      set, but "medium"/"highest" are undocumented — a strict endpoint may
      400, a lenient one may map them unpredictably. Verify against a live
      turn (blocked: no `DEEPSEEK_API_KEY`) and/or document in the DeepSeek
      example notes which `/thinking` levels are documented-safe, plus the
      "highest"-vs-"max" mismatch; or add a DeepSeek effort-value mapping.
      → Resolved (documented, per the item's doc option; live verification
      blocked on missing `DEEPSEEK_API_KEY`): DeepSeek example notes now
      state psi's adaptive path emits effort `"low"` (`/thinking minimal` or
      `/thinking low`), `"medium"` (`/thinking medium`), `"high"` (`/thinking
      high`) and `"highest"` (`/thinking xhigh`, and `effort-override
      :xhigh`) and never `"max"`; only `"low"`/`"high"` are within DeepSeek's
      documented `"low/high/max"` set, `"medium"`/`"highest"` are
      undocumented (strict endpoint may 400, lenient may map unpredictably),
      and `"highest"` does not correspond to DeepSeek's `"max"`; until
      live-verified, prefer `/thinking minimal`/`/thinking low` or
      `/thinking high` for documented-safe effort values. Chose documentation
      over a DeepSeek effort-value mapping because live verification is
      blocked (no `DEEPSEEK_API_KEY` in env) and the design AC forbids
      changing `providers/anthropic.clj` request-shaping logic in this task.
- [x] A fast-mode 400 on DeepSeek is not auto-recoverable by psi's
      compatibility retry: `fallback-request-for-400`'s `:without-all-betas`
      step strips the `fast-mode-2026-02-01` beta header but leaves
      `"speed": "fast"` in the retried body, so a DeepSeek 400 on the
      unverified `speed` field (the review-6 fast-mode note's exact concern)
      retries once and hard-fails instead of degrading. The review-6
      fast-mode note does not mention this — extend it to state users must
      turn fast mode off (`/fast off`) because the auto-retry cannot recover
      it; optionally (separate change) add `:speed` stripping to the
      400-fallback transforms.
      → Resolved (documented, per the item's doc option; live verification
      blocked on missing `DEEPSEEK_API_KEY`): review-6 fast-mode note in
      `doc/custom-providers.md` extended — psi's HTTP-400 compatibility retry
      strips the `fast-mode-2026-02-01` beta header (`:without-all-betas`
      step in `anthropic/request_support.clj`) but leaves `"speed": "fast"`
      in the retried body, so a `speed`-field 400 retries once with the same
      field and hard-fails; turn fast mode off (`/fast off`) — do not rely on
      the auto-retry to degrade gracefully. Chose documentation over the
      optional separate `:speed`-stripping code change because the design AC
      forbids changing provider request-shaping/transport logic in this
      task.

## Follow-ups (implementation review 9, 2026-08-07)

- [x] `spec/anthropic-provider.allium` `ApiKeyResolved` (updated in the
      review-5 resolution) references predicates/attributes the spec never
      defines: `BuiltinAnthropic(stream.model)`, `CustomProvider(stream.model)`
      and `stream.keyless` — the `AnthropicStream` entity has no `keyless`
      field, `StreamOptions` lacks `no_auth_header`/`headers`, and `Model`
      lacks `provider` (which the two predicates need). The rule is
      uncheckable as written, so the review-5 "spec now matches the
      implementation" claim holds only for `custom-providers.allium`.
      Complete the model: add `keyless: Boolean = false` to
      `AnthropicStream`, `no_auth_header` and `headers` to `StreamOptions`,
      `provider`/`adaptive_thinking` to `Model`, and define the
      `BuiltinAnthropic`/`CustomProvider` predicates (or inline the
      provider-nil/"anthropic" conditions) so `ApiKeyResolved` is
      self-contained.
      → Resolved: `spec/anthropic-provider.allium` model completed — `Model`
      gains `provider: String?` and `adaptive_thinking: Boolean = false`;
      `StreamOptions` gains `no_auth_header: Boolean = false`,
      `headers: Map<String, String>?`, `effort_override`, and
      `on_provider_request`/`on_provider_response`; `AnthropicStream` gains
      `keyless: Boolean = false`. `ApiKeyResolved` now inlines the
      built-in/custom conditions (`provider == null or provider ==
      "anthropic"` vs otherwise) instead of the undefined
      `BuiltinAnthropic`/`CustomProvider` predicates; new
      `KeylessRequestDetermined` + `ExistsAuthHeader` rules define
      `stream.keyless` (mirroring `build-request`'s `no-auth?`: explicit
      `:no-auth-header`, or a recognized auth header among custom `:headers`
      with no configured key). The rule is now self-contained.
- [x] `spec/anthropic-provider.allium` does not model two behaviors this
      task now documents/changes for the Anthropic transport: (a) adaptive
      thinking — `ThinkingParamPresentForActiveLevel` unconditionally maps
      active levels to `{:type "enabled" :budget_tokens ...}`, but
      `thinking-param` emits `{:type "adaptive" :display "summarized"}`
      (paired with body `output_config.effort`, which is never modeled) for
      `:adaptive-thinking` models — the exact shape this task's DeepSeek
      example and `:adaptive-thinking` field teach; and
      `AnthropicRequestBodyBuilt`/`TemperatureExcludedWhenThinkingActive`
      require `temperature` whenever thinking is null, but adaptive models
      omit it even with thinking off (the trade-off
      `doc/custom-providers.md` documents); (b) capture redaction —
      `openai-provider.allium` models
      `ProviderRequestCaptureEmittedWithRedaction`/`RedactRequestHeaders`,
      but anthropic-provider.allium has no capture/redaction rules, so the
      review-7 case-insensitive `find-header` redaction change has no spec
      counterpart. Add adaptive-thinking rules (thinking param shape,
      `output_config.effort`, temperature exclusion whenever adaptive
      regardless of thinking) and a capture-redaction rule to
      anthropic-provider.allium.
      → Resolved: `ThinkingParamPresentForActiveLevel` now emits the adaptive
      shape (`{:type "adaptive" :display "summarized"}`) for
      `adaptive_thinking` models; new `OutputConfigEffortForAdaptiveThinking`
      /`NoOutputConfigForClassicThinking` rules model body
      `output_config.effort` (level-derived or override, never for classic
      thinking); `TemperatureExcludedForAdaptiveModels` +
      `AnthropicRequestBodyBuilt`'s temperature guard model temperature
      exclusion whenever adaptive (even thinking off); new Capture Callbacks
      section (`ProviderRequestCaptureEmittedWithRedaction`,
      `ProviderResponseCaptureEmitted`, `RedactRequestHeaders`) models the
      case-insensitive auth-header redaction from review 7 (original key
      casing preserved).
- [x] `parse-documented-deepseek-example-test` (user_models_test.clj,
      review-6 resolution) embeds a hardcoded copy of the documented
      `models.edn` example instead of reading `doc/custom-providers.md`, so
      its stated purpose — "guards the closed ModelDef/AuthConfig schemas
      against docs/code drift" — is only half met: a change to the documented
      example (typo, new field, pricing edit) never fails the test; it locks
      a snapshot. Extract/parse the EDN block from the doc file itself (read
      `doc/custom-providers.md`, take the ```clojure block under the
      "DeepSeek-compatible example" heading) or assert the embedded map
      equals the doc's block, so doc↔schema drift is actually caught.
      → Resolved: `parse-documented-deepseek-example-test` now reads
      `doc/custom-providers.md` directly — repo-root walk-up +
      `deepseek-example-edn` helpers extract the ```clojure EDN block under
      the '## DeepSeek-compatible example' heading and parse it through
      `parse-models-config`, asserting every resolved field. The hardcoded
      copy is gone; a doc edit that breaks the example (or a schema change
      that rejects it) now fails the test.
- [x] `doc/custom-providers.md` "Adaptive thinking" section never states
      that `:adaptive-thinking true` is a silent no-op without
      `:supports-reasoning true`: `thinking-param` gates on
      `(:supports-reasoning model)`, so a custom provider declaring
      adaptive-thinking without supports-reasoning gets no `thinking` field
      and no `output_config.effort` at all (plain non-thinking requests) —
      no schema error, no warning. Document the dependency in the
      `:adaptive-thinking` field section (and/or the DeepSeek example notes)
      so both flags are known to be required together.
      → Resolved: `doc/custom-providers.md` Adaptive thinking section now
      states `:adaptive-thinking true` is a silent no-op without
      `:supports-reasoning true` (no `thinking` field, no
      `output_config.effort`, no schema error or warning) — set both flags
      together when you want the adaptive shape.

## Follow-ups (implementation review 10, 2026-08-07)

- [x] `spec/custom-providers.allium`'s review-5 rules carry the same
      undefined-reference problem review 9 flagged for
      `anthropic-provider.allium`'s `ApiKeyResolved` — the review-5 "spec now
      matches the implementation" claim holds for `custom-providers.allium`
      only in the same uncheckable sense. `ResolveRequestApiKey`,
      `ExistsAuthHeader`, `InjectCustomProviderAuth` and
      `NoAuthHeaderWhenDisabled` reference predicates/entities/functions never
      defined anywhere in the spec suite: `BuildPreparedRequest`,
      `LookupProviderAuth`, an `options` entity with
      `no_auth_header`/`headers`/`api_key` fields, `SystemGetenv`,
      `BlankOrNil`, `HeaderNamed`, `LowerCase`. `ExistsAuthHeader` is a rule
      whose own `ensures` uses `ExistsAuthHeader(headers)` as a predicate
      (self-referential), and `NoAuthHeaderWhenDisabled` invokes
      `ResolveRequestApiKey(model, options).keyless` as a value expression (a
      rule, not a function). Fold this into the review-9 item-1 fix so both
      specs are left checkable.
      → Resolved: `spec/custom-providers.allium` auth rules are now
      self-contained — new `RequestOptions` value defines the `options`
      entity (`api_key`/`no_auth_header`/`headers`/`thinking_level`); a
      `KeylessRequestDefined` rule defines the `KeylessRequest(model,
      options)` predicate (mirrors `build-request`'s `no-auth?`), which
      `ResolveRequestApiKey` and `NoAuthHeaderWhenDisabled` reference as a
      value — `NoAuthHeaderWhenDisabled` no longer invokes a rule as a
      function, and its previously-unbound bare `keyless` ensure is now
      `KeylessRequest(model, options)`. The self-referential
      `ExistsAuthHeader` rule is gone — the recognized-auth-header condition
      (`x-api-key`/`authorization`, case-insensitive) is inlined as an
      explicit `∃ header ∈ ... . LowerCase(HeaderName(header)) ∈ {...}`
      where it is needed (`KeylessRequestDefined`,
      `InjectCustomProviderAuth`). A `Primitives` section defines the helper
      vocabulary (`SystemGetenv`/`Environment`, `BlankOrNil`, `HeaderName`,
      `LowerCase`) and an `External interface` section plus
      `surface CustomProviderApi` declare `BuildPreparedRequest`,
      `RequestUnderConstruction` (provided events) and document
      `LookupProviderAuth` (runtime function reading the provider's `:auth`
      config, cf. `extract-provider-auth`). `spec/anthropic-provider.allium`
      received the same treatment (inlined ∃ in `KeylessRequestDetermined`,
      `HeaderName` in `RedactRequestHeaders`, Primitives note) so both specs
      are checkable.
- [x] OpenAI `chat_completions` transport still leaks the user's OpenAI key to
      custom `:openai-completions` providers: `build-request`'s Authorization
      header is `(or (:api-key options) (System/getenv "OPENAI_API_KEY"))`
      for every provider, so a custom OpenAI-compatible provider with an
      unset/blank configured key silently sends the global `OPENAI_API_KEY`
      to the third-party endpoint — the exact cross-provider credential
      disclosure class review 3 eliminated for the anthropic transport
      (provider-scoped `resolve-api-key`). The new `doc/custom-providers.md`
      "API-key resolution is provider-scoped" text is anthropic-only, so it
      does not contradict, but the asymmetry is undocumented and untested:
      either make custom `:openai-completions` key resolution provider-scoped
      like the anthropic transport, or explicitly document that the OpenAI
      transport still falls back to `OPENAI_API_KEY`; add a no-leak test
      mirroring the anthropic `anthropic_test.clj` one (custom provider with
      redef'd `getenv`/env → throws or refuses rather than sending the key).
      → Resolved: custom `:openai-completions` key resolution is now
      provider-scoped like the anthropic transport — `openai/chat_completions`
      gains `resolve-api-key` (falls back to `OPENAI_API_KEY` only for
      built-in OpenAI models, `:provider` nil or `:openai`; custom providers
      fail fast with a provider-scoped "Missing API key" error naming the
      models.edn `:auth` remedy, no `/login` hint), `auth-header?`/
      `getenv` helpers, and `build-request` computes the same `no-auth?`
      keyless logic (`:no-auth-header`, or a recognized
      `x-api-key`/`Authorization` header among custom `:headers` with no
      configured key) and omits the Authorization header when keyless — a
      custom provider's request can never silently send the user's
      `OPENAI_API_KEY` to a third-party endpoint. Tests added
      (`openai-provider-scoped-api-key-resolution-test` in
      `openai_completions_test.clj`): custom provider with redef'd `getenv` →
      throws rather than sending the key; missing-key error points at
      models.edn and never hints at `/login`; built-in model still uses the
      env fallback; keyless `:no-auth-header` / recognized-auth-header /
      incidental-headers-fast-fail paths. `doc/custom-providers.md` updated
      (MiniMax example notes now document the provider-scoped behavior, and
      the Anthropic-compatible provider-scoped paragraph cross-references
      both transports). `spec/openai-provider.allium` gains
      `OpenAIApiKeyResolved` + `KeylessRequestDetermined` mirroring the
      anthropic spec; design.md revision note updated with this third
      provider-transport change.
- [x] `parse-documented-deepseek-example-test`'s env-auth assertion is
      tautological: `(= (user-models/resolve-api-key-spec
      "env:DEEPSEEK_API_KEY") (:api-key auth))` — `extract-provider-auth`
      already resolved `:api-key` via the same `resolve-api-key-spec` call, so
      this is `(= X X)` and passes whether `DEEPSEEK_API_KEY` is set, unset,
      or env resolution is broken. It proves nothing about provider-scoped env
      auth. Assert against the actual env value (e.g.
      `(System/getenv "DEEPSEEK_API_KEY")`, with the env-dependency
      documented) or redef the env lookup to a known sentinel so the
      resolution path is genuinely exercised. (Distinct from the review-9
      item-3 doc-copy concern — this remains a tautology even if the test
      reads the doc file.)
      → Resolved: `user_models.clj` gains a private `getenv` indirection used
      by `resolve-api-key-spec` (behavior-preserving; mirrors the anthropic
      provider's review-3 testability pattern), and the test now
      `with-redefs` `user-models/getenv` to a sentinel
      (`"sk-deepseek-sentinel"`) and asserts the parsed auth `:api-key`
      equals it — genuinely exercising `env:DEEPSEEK_API_KEY` → getenv →
      `:api-key`. No longer tautological, no env-dependency.
- [x] No test locks the docs claim that `:adaptive-thinking` "is ignored for
      OpenAI-compatible custom providers": `expand-model` carries the field
      into every custom model map (`:openai-completions` included), and the
      OpenAI transport never reads it — but no request-shaping test proves an
      `:openai-completions` custom model with `:adaptive-thinking true`
      produces an unchanged OpenAI body (no `output_config`/effort/adaptive
      leakage). Add one mirroring the anthropic classic-shape locking tests.
      → Resolved: `openai_completions_test.clj` gains
      `openai-completions-adaptive-thinking-ignored-for-custom-providers-test`
      — builds `build-request` for a literal custom `:openai-completions`
      model map with and without `:adaptive-thinking true` (+ `:thinking-level
      :high`) and asserts the bodies are byte-identical, with no
      `output_config`/`thinking` leakage and the unchanged classic
      `reasoning_effort "high"` shape.
- [x] Mixed-case `Authorization` capture redaction is untested: the review-7
      case-insensitive redaction tests cover lowercase `x-api-key` (existing)
      and mixed-case `X-API-Key` (new), but not a mixed-case
      `Authorization`/`authorization` header — the `redact-authorization`
      path through the same `find-header` helper has no capture-path lock
      (existing tests use exact-case `"Authorization"` only). Add a capture
      assertion in `anthropic_stream_test.clj` for a mixed-case
      `Authorization` header.
      → Resolved: `anthropic_stream_test.clj` gains a capture-path testing
      block — keyless custom provider with `:headers {"authorization"
      "local-token"}` → `"Bearer ***REDACTED***"` in the
      `:on-provider-request` payload, locking the `redact-authorization` path
      through the case-insensitive `find-header` helper for a non-exact-case
      header name.

## Follow-ups (implementation review 11, 2026-08-07)

- [x] OpenAI transport capture redaction is case-sensitive and does not redact
      `x-api-key`: `transport/redact-request-headers` (`providers/openai/
      transport.clj`) redacts only exact-case `"Authorization"` and
      `"chatgpt-account-id"`, so the review-10 keyless custom-header auth
      patterns on `:openai-completions` (`:headers {"X-API-Key" "local-key"}`
      or mixed-case `authorization`) leak the secret verbatim into the
      `:on-provider-request` capture payload — the exact leak review 7 fixed
      for the anthropic transport via the case-insensitive `find-header`
      helper. Make the openai redaction case-insensitive and redact
      `x-api-key` (mirror `auth-header?` recognition / the anthropic
      `redact-request-headers`), and add capture-path tests in the openai
      stream tests mirroring `anthropic_stream_test.clj` (mixed-case
      `X-API-Key` → `***REDACTED***`, lowercase `authorization` → `Bearer
      ***REDACTED***`).
      → Resolved: `transport/redact-request-headers` (`providers/openai/
      transport.clj`) is now case-insensitive via a new `find-header` helper
      (matches header names case-insensitively, redacts under the original key
      casing) and redacts `x-api-key` (`***REDACTED***`), `Authorization`
      (`Bearer ***REDACTED***`) and `chatgpt-account-id` (masked) — mirroring
      the anthropic transport's review-7 fix. `openai_request_headers_test.clj`
      gains `custom-header-auth-redacted-in-captures-test` proving keyless
      custom-provider requests with `:headers {"X-API-Key" "local-key"}` →
      `***REDACTED***` and `:headers {"authorization" "local-token"}` →
      `Bearer ***REDACTED***` in the `:on-provider-request` payload. Full
      `bb test` green (2559 tests / 19212 assertions / 0 failures).

## Follow-ups (implementation review 12, 2026-08-07)

- [x] CHANGELOG has no entry for this task's user-visible custom-provider
      behavior changes: `[Unreleased]` documents only the DeepSeek example +
      `:adaptive-thinking` field (Added). The review-driven provider-scoped
      API-key resolution (custom `:anthropic-messages` and
      `:openai-completions` providers no longer silently fall back to
      `ANTHROPIC_API_KEY`/`OPENAI_API_KEY` — they fail fast with a
      provider-scoped missing-key error), the keyless exemptions
      (`:no-auth-header`/`:auth-header? false`, recognized auth header among
      custom `:headers`), the OAuth content-sniff gating to built-in
      Anthropic models (review 11), and the case-insensitive auth-header
      capture redaction are all user-visible `δ` per AGENTS.md changelog
      policy (a custom-provider user relying on the old env-var fallback now
      gets a hard error instead of silent forwarding). Add a `[Unreleased]`
      → `Changed` (or `Fixed`) entry summarizing these provider-transport
      behavior changes.
      → Resolved: CHANGELOG `[Unreleased]` → `Changed` now carries three
      entries: (1) provider-scoped API-key resolution for both
      `:anthropic-messages` and `:openai-completions` transports (custom
      providers with no configured key fail fast with a provider-scoped
      "Missing API key" error instead of leaking `ANTHROPIC_API_KEY`/
      `OPENAI_API_KEY` to a third-party endpoint; built-ins keep the env-var
      fallback; keyless exemptions via `:auth-header? false`/`:no-auth-header`
      or a recognized auth header among custom `:headers`); (2) custom-provider
      OAuth content-sniffing closed (a custom `:anthropic-messages` provider
      whose key resembles `sk-ant-oat…` always uses `x-api-key` auth — the
      Claude Code CLI headers/system prompt are never sent to a third-party
      endpoint); (3) provider request captures redact auth headers
      case-insensitively on both transports (`x-api-key`, mixed-case
      `Authorization`, `chatgpt-account-id`).
- [x] Custom-provider missing-key error suggests an env var name derived from
      the raw provider key with hyphens preserved — both transports:
      `anthropic/resolve-api-key` and
      `openai/chat-completions/resolve-api-key` build
      `"env:" (str/upper-case (name provider)) "_API_KEY"`, so a kebab-case
      provider key (e.g. the docs' own `my-anthropic-proxy` example) yields
      `env:MY-ANTHROPIC-PROXY_API_KEY` — not a usable shell env var name
      (bash identifiers cannot contain `-`) and inconsistent with the docs'
      underscore convention (`MY_PROXY_API_KEY`, `MINIMAX_API_KEY`).
      Normalize `-` → `_` when deriving the suggested name (both transports)
      and add a test asserting the suggestion for a kebab-case provider key.
      → Resolved: both `anthropic/resolve-api-key` and
      `openai/chat-completions/resolve-api-key` now normalize the suggested
      env var name with `(str/replace "-" "_")` before `str/upper-case`
      (e.g. `:my-anthropic-proxy` → `env:MY_ANTHROPIC_PROXY_API_KEY`).
      Tests added on both transports asserting the underscore suggestion for
      a kebab-case provider key and that no hyphenated suggestion is emitted.
- [x] `oauth-api-key?` content-sniffs the resolved key with no provider gate:
      a custom `:anthropic-messages` provider whose configured key contains
      `sk-ant-oat` is treated as an OAuth request — `Authorization: Bearer` +
      `user-agent: claude-cli/…` + `x-app: cli` headers, the
      `claude-code-*`/`oauth-*`/`prompt-caching-scope-*` beta headers, AND
      the `claude-code-system-prompt` ("You are Claude Code…") prepended as
      the first system block — all sent to the third-party endpoint. Reviews
      3/4/10 hardened env-var fallback but left this content-based OAuth
      detection unguarded; it is undocumented and untested for custom
      providers. Fix: gate `oauth?` on built-in Anthropic models (`:provider`
      nil or `:anthropic`) so custom providers always use `x-api-key` auth,
      and add a test proving a custom provider with an `sk-ant-oat…` key does
      NOT get OAuth headers or the Claude-Code system prompt.
      → Resolved (same resolution as review-11 item 2): `oauth?` is now gated
      on a new `builtin-anthropic?` helper (`:provider` nil or `:anthropic`)
      in both `build-request` (drives `request-body`'s Claude-Code
      system-prompt prepend and `beta-header`) and `request-headers` (drives
      `x-api-key` vs OAuth header shape); `resolve-api-key` reuses the same
      helper. `anthropic_test.clj` gains
      `build-request-oauth-gated-on-builtin-models-test` — a custom
      `:anthropic-messages` provider with `sk-ant-oat…` key gets `x-api-key`
      auth, no `Authorization`/`user-agent`/`x-app`, no OAuth betas, and no
      Claude Code system prompt; built-in models still get OAuth treatment.
      `spec/anthropic-provider.allium` `OAuthDetectedFromApiKey` now requires
      the built-in-provider condition; `doc/custom-providers.md` DeepSeek
      notes document the provider-scoped OAuth sniffing.
- [x] `spec/openai-provider.allium` has the same undefined-reference class
      reviews 9/10 just fixed in custom-providers.allium +
      anthropic-provider.allium: this task's new `OpenAIApiKeyResolved`/
      `KeylessRequestDetermined` rules use `Environment`, `IsBlank`,
      `LowerCase`, `HeaderName` with no Primitives/shared-vocabulary section
      (anthropic-provider.allium carries the shared-vocabulary note;
      openai-provider.allium has none), and
      `ProviderRequestCaptureEmittedWithRedaction` still references an
      undefined `RedactRequestHeaders`. Fold into the review-9/10 treatment:
      add the shared-vocabulary/Primitives note (or reference
      custom-providers.allium) and define/import `RedactRequestHeaders` so
      openai-provider.allium is checkable too.
      → Resolved (same resolution as review-11 item 3): `spec/
      openai-provider.allium` gains a Primitives section (shared vocabulary
      with custom-providers.allium: `Environment`, `BlankOrNil`/`IsBlank`,
      `HeaderName`, `LowerCase`) and a `RedactRequestHeaders` rule defining
      the previously-undefined reference (case-insensitive `authorization` →
      Bearer-redacted, `x-api-key` → secret-redacted, `chatgpt-account-id` →
      masked; other headers pass through) mirroring the review-11 transport
      fix. The spec is now self-contained for the new rules (manual
      allium-check, per the established pattern — no automated checker in
      repo).
- [x] `doc/custom-providers.md` teaches `output_config.effort` "derived from
      /thinking//effort" and (review-8 note) `effort-override :xhigh`, but
      `:effort-override` alone is a silent no-op: `request-body`'s effort is
      gated on `(and thinking adaptive?)` and `thinking-param` requires an
      active `:thinking-level` (session default `:off`), so `/effort` on a
      `:adaptive-thinking true` model with `/thinking` unset/off emits neither
      `thinking` nor `output_config` — while DeepSeek defaults thinking ON
      server-side, so the user's effort setting is silently dropped.
      Document in the Adaptive-thinking section (and/or DeepSeek notes) that
      effort applies only when a thinking level is active (`/thinking` on),
      and add a `build-request` test proving `:effort-override` without a
      thinking level emits no `output_config`.
      → Resolved (same resolution as review-11 item 4): `doc/
      custom-providers.md` Adaptive thinking section now states effort applies
      only when a thinking level is active (`/thinking` on) — `:effort-override`
      / `/effort` with thinking unset/off emits neither `thinking` nor
      `output_config.effort` (silent no-op); DeepSeek example notes add the
      same caveat with the DeepSeek thinking-ON-default interaction.
      `anthropic_test.clj` `build-request-adaptive-thinking-custom-provider-test`
      gains an `:effort-override :xhigh`-only (no thinking level) block
      asserting no `:thinking` and no `:output_config`.
- [x] Custom `:headers` carrying a recognized auth header name silently
      replace/duplicate the configured `:api-key` — untested and
      undocumented for both transports: anthropic `build-request` merges
      custom headers over the base headers, so `:headers {"X-API-Key"
      "other"}` with a configured key sends BOTH the lowercase `x-api-key`
      (configured) and `X-API-Key` (custom) on the wire (server picks by
      case-insensitive header merge); openai `build-request`'s
      `(merge … (:headers options))` lets a custom `Authorization` header
      silently replace the resolved bearer key. Add a test for the
      configured-key + recognized-auth-header interplay on both transports
      and a docs sentence (a recognized auth header among custom `:headers`
      overrides the configured `:api-key`; don't mix them) — the keyless
      inference is documented, the override case is not.
      → Resolved (same resolution as review-11 item 5): `anthropic_test.clj`
      gains `configured-key-plus-recognized-auth-header-interplay-test` —
      configured key + `:headers {"X-API-Key" "other"}` sends both `x-api-key`
      (configured) and `X-API-Key` (custom); configured key + `Authorization`
      custom header sends both. `openai_completions_test.clj` gains the same
      deftest — custom `Authorization` REPLACES the resolved bearer key;
      custom `X-API-Key` coexists with the configured bearer key.
      `doc/custom-providers.md` "Local servers and custom headers" now states
      the merge behavior (duplicate on anthropic, replace on openai) and
      advises picking one auth mechanism per provider.
- [x] `deepseek-example-edn` (user_models_test.clj) picks the FIRST ```clojure
      block after the "## DeepSeek-compatible example" heading; if the
      section's prose gains a code block before the models.edn example (e.g. a
      curl or request-shape sample), the parse-lock silently locks the wrong
      block and the docs↔schema drift guard (review 6/9) is defeated. Make the
      extraction target the specific EDN block (e.g. require the block to
      start with `{:version`), so incidental ```clojure blocks in the section
      cannot move the parse-lock target.
      → Resolved (same resolution as review-11 item 6): `deepseek-example-edn`
      now scans every ```clojure block after the heading and picks the first
      whose first content line starts with `{:version` (the models.edn root
      map); if none matches it throws a clear "no ```clojure EDN block
      starting with {:version ...}" error instead of silently locking an
      incidental block. The docs↔schema drift guard (review 6/9) now survives
      a curl/request-shape sample added to the section prose.

## Follow-ups (implementation review 13, 2026-08-07)

- [x] HTTP-400 compatibility retry silently absorbs a DeepSeek 400 on the
      unverified adaptive `thinking.type "adaptive"` shape: for an adaptive
      request, `fallback-request-steps-for-400` yields `[:without-thinking]`,
      which strips BOTH `:thinking` and `:output_config` from the retried
      body (verified 2026-08-07 against `request_support.clj`). On DeepSeek
      an omitted `thinking` field leaves thinking ON (server default), so a
      strict endpoint that rejects `type: "adaptive"` (the review-7 caveat's
      "may reject it (400)") does NOT hard-fail on the streaming path — the
      retry succeeds with thinking silently ON at default effort and the
      user's effort setting dropped. The non-streaming `execute-anthropic`
      path has NO 400 fallback, so the same request hard-fails there —
      streaming/non-streaming asymmetry. No test locks the fallback on an
      adaptive-shape request (`stream-anthropic-retries-without-thinking-on-
      400-test` uses a built-in OAuth extended-thinking request,
      `type: "enabled"`). Document in the DeepSeek example notes (streaming
      retry strips the adaptive shape and degrades to thinking-ON; non-
      streaming hard-fails) and/or add a fallback test with an adaptive-shape
      request asserting `:without-thinking` strips `:thinking` +
      `:output_config`.
      → Resolved (both options): `anthropic_stream_test.clj` gains
      `stream-anthropic-retries-adaptive-shape-without-thinking-on-400-test`
      — a literal deepseek-v4-flash custom model map (`:adaptive-thinking
      true`) + `:thinking-level :high` → first post 400, retry 200; asserts
      the first body carries `thinking.type "adaptive"` +
      `output_config.effort "high"`, the retried body strips BOTH `:thinking`
      and `:output_config`, the response capture records
      `:retry-fallback-steps [:without-thinking]`, and the stream completes
      with no `:error` (the 400 is absorbed, thinking silently ON on
      DeepSeek). `doc/custom-providers.md` DeepSeek example notes gain an
      "HTTP-400 compatibility retry and the adaptive shape" bullet: streaming
      retry strips the adaptive shape and degrades to thinking-ON at default
      effort (effort silently dropped); the non-streaming `execute` path has
      no 400 fallback and hard-fails on the same request; to fail fast
      instead of silently degrading use `:adaptive-thinking false` (classic
      `type: "enabled"` is a documented honored value). Live verification
      remains blocked (no `DEEPSEEK_API_KEY`).
- [x] `spec/anthropic-provider.allium` has no rules for the HTTP-400
      compatibility retry (`fallback-request-for-400` / `handle-400-response!`
      / `:without-thinking` / `:without-all-betas` / `:without-prompt-
      caching`), even though the review-8 fast-mode note and the review-13
      adaptive-shape finding (item 1 above) document retry behavior for this
      task's DeepSeek example, and the review-5/9 "spec now matches the
      implementation" claims cover adaptive/temperature/keyless/redaction but
      not the retry path. Add rules modeling the fallback step selection and
      the retried request/body (thinking + output_config stripped;
      all-betas + output_format stripped but `:speed` retained) so the
      documented DeepSeek retry behavior has a spec counterpart.
      → Resolved: new "HTTP-400 Compatibility Retry" section in
      `spec/anthropic-provider.allium` — `FallbackStepsSelectedFor400`
      (cumulative step selection: prompt-caching beta → `:without-prompt-
      caching`; interleaved-thinking beta or `:thinking` body key →
      `:without-thinking`; any beta + no Bearer Authorization →
      `:without-all-betas`), `FallbackRetriedOnceOrErrorSurfaced` (retry
      exactly once; retry ≥400 → error, <400 → stream continues; no steps →
      error without retry; execute path has no fallback), and one rule per
      transform: `WithoutThinkingStepStripsThinkingAndOutputConfig`
      (strips `:thinking` + `:output_config`, removes the
      interleaved-thinking beta — the review-13 adaptive-shape interaction),
      `WithoutAllBetasStepClearsBetasAndOutputFormat` (clears beta header,
      strips `:output_format`, RETAINS `:speed` — the review-8 fast-mode
      note), `WithoutPromptCachingStepStripsCacheDirectives`. Section
      documents its rule-defined vocabulary (Http400Observed,
      FallbackStepApplied, RetriedRequest, etc.) per the review-9/10
      self-containedness pattern; no undefined entity attributes referenced.
      Manual allium-check (no automated checker in repo).
- [x] `openai/transport.clj` `redact-authorization` does not strip a leading
      `Bearer ` before computing the redacted length suffix, unlike the
      anthropic transport's `redact-authorization` (which strips `^Bearer\s+`
      first): an openai capture of `"Bearer abc…"` records
      `"Bearer ***REDACTED*** (len=N)"` with N counting the whole value
      including the 7-char `Bearer ` prefix, while the anthropic transport
      records N excluding it. The review-11 redaction mirror is therefore
      inconsistent between transports in the length metadata (the secret
      itself is redacted either way). Align the openai redactor with the
      anthropic one (strip the prefix before `count`).
      → Resolved: `openai/transport.clj` `redact-authorization` now strips
      `^Bearer\s+` before delegating to the shared `redact-secret` (moved
      above it), mirroring the anthropic transport exactly —
      `"Bearer ***REDACTED***"` with `(len=N)` measuring the secret only,
      excluding the prefix. `openai_request_headers_test.clj` gains
      `redact-authorization-length-excludes-bearer-prefix-test` — a keyless
      custom-provider capture of `"authorization" (str "Bearer " token)` with
      a 30-char token asserts `"Bearer ***REDACTED*** (len=30)"` in the
      `:on-provider-request` payload.
- [x] `:openai-codex-responses` custom providers still fall back to the global
      `OPENAI_API_KEY` env var — the third transport in the custom `ModelDef`
      `ApiProtocol` enum never received the provider-scoped key resolution
      reviews 3/10 gave `:anthropic-messages`/`:openai-completions`.
      `build-codex-request` (providers/openai/codex_responses.clj) resolves
      `(or (:api-key options) (System/getenv "OPENAI_API_KEY"))`
      unconditionally, then `extract-chatgpt-account-id` throws "OpenAI Codex
      requires ChatGPT OAuth access token" for any non-OAuth key. A custom
      `models.edn` provider with `:api :openai-codex-responses` and no
      configured key therefore either hard-fails confusingly (regular `sk-`
      env key, request never sent) or silently sends the user's OpenAI
      credential to the third-party `:base-url` (OAuth-shaped env key;
      `resolve-codex-url` honors custom base-urls) — the exact cross-provider
      disclosure class this task closed on the other two transports, left
      open here. `:auth-header? false`/`:no-auth-header` keyless codex
      configs don't work either (the account-id check still requires a key),
      unlike the keyless exemptions now documented for both scoped
      transports. The review-12 CHANGELOG `Changed` entry and
      doc/custom-providers.md claim provider-scoped resolution for "both
      transports" — codex is an undocumented exception reachable from the
      same custom-provider schema. Note `spec/openai-provider.allium`
      `OpenAIProviderDispatchesByModelApi` already `requires
      stream.model.provider = "openai"` for codex routing — the spec assumes
      codex is built-in-only while the code permits custom codex providers.
      Fix direction: (a) provider-scope codex key resolution mirroring review
      10 (built-in `:provider :openai` keeps the env fallback; custom codex
      providers fail fast with a provider-scoped missing-key error, no
      `/login` hint; keyless `:no-auth-header`/headers-auth exemptions), (b)
      restrict `:openai-codex-responses` to built-in models (drop it from the
      custom `ModelDef` `ApiProtocol` enum — per design.md's revision note
      the transport is hard-coupled to the ChatGPT/Codex backend), or (c)
      explicitly document the exception. Add a no-leak test mirroring
      `openai-provider-scoped-api-key-resolution-test`.
      → Resolved (option (a), concurrent review-pass working-tree changes
      verified end-to-end here): `codex_responses/build-codex-request` now
      resolves the key provider-scoped via new `getenv`/`auth-header?`/
      `resolve-api-key` helpers — built-in `:provider` nil/`:openai` keep the
      `OPENAI_API_KEY` env fallback; custom codex providers fail fast with
      the provider-scoped "Missing API key for provider <name>" error naming
      the models.edn `:auth` remedy (no `/login` hint; kebab-case provider
      keys normalized `-` → `_` in the suggested env var name). The same
      `no-auth?` keyless computation as the other two transports applies
      (`:no-auth-header`, or a recognized `x-api-key`/`Authorization` header
      among custom `:headers` with no configured key; incidental headers
      fast-fail), and keyless requests omit BOTH `Authorization` and
      `chatgpt-account-id` — the account-id requirement is waived for
      keyless configs (was an unconditional throw). `openai_test.clj` gains
      `codex-provider-scoped-api-key-resolution-test` (no-leak with redef'd
      `getenv`; models.edn remedy, no `/login`; kebab-case env suggestion;
      built-in env fallback; `:no-auth-header` keyless; recognized-auth-header
      keyless; incidental-headers fast-fail). CHANGELOG `Changed` entry +
      doc/custom-providers.md now name all three transports;
      `spec/openai-provider.allium` updated (`OpenAIApiKeyResolved` covers
      codex, `CodexRequestRequiresApiKey`/`CodexRequiresChatGptAccountId`
      gain the keyless exemption, and `OpenAIProviderDispatchesByModelApi`
      dispatches on `model.api` instead of the built-in-only `provider =
      "openai"` assumption); design.md revision note added.
- [x] `spec/openai-provider.allium` `OpenAIApiKeyResolved` built-in condition
      omits the nil-provider case: the rule uses `stream.model.provider =
      "openai"` / `!= "openai"`, but the implementation
      (`openai/chat-completions` `resolve-api-key`) treats `:provider` nil as
      built-in — `(or (nil? provider) (= :openai provider))` — with the
      `OPENAI_API_KEY` env fallback. `spec/anthropic-provider.allium`
      `ApiKeyResolved` models the nil case explicitly (`provider == null or
      provider == "anthropic"`); the openai rule should mirror it (`provider
      == null or provider == "openai"` for the env-fallback ensure, and the
      converse for the custom-provider fast-fail ensure) so a nil-provider
      model on the openai transport is modeled as built-in, matching the
      code.
      → Resolved: `spec/openai-provider.allium` `OpenAIApiKeyResolved` now
      mirrors the anthropic spec's nil-provider modeling —
      `(stream.model.provider == null or stream.model.provider == "openai")`
      for the env-fallback ensure and `(stream.model.provider != null and
      stream.model.provider != "openai")` (with `not stream.keyless`) for the
      custom-provider fast-fail ensure — matching
      `openai/chat-completions` `resolve-api-key`'s `(or (nil? provider)
      (= :openai provider))` built-in condition. Manual allium-check (no
      automated checker in repo).

## Follow-ups (implementation review 14, 2026-08-07)

- [x] Full-`bb test` flake inventory is incomplete: a fresh full-suite run
      (seed 1741154775) failed `psi.agent-session.scheduler-lifecycle-test/
      scheduled-deliver-runs-canonical-prompt-lifecycle-test` (8 pass / 5
      fail: session phase `:streaming` instead of `:idle`, no assistant
      message, and no `:scheduler/deliver` / `:session/prompt-record-response`
      / `:session/prompt-finish` entries). It passes in isolation (4 tests /
      26 assertions). The `:scheduler/fired only pending schedules can fire`
      dispatch warning fires even on passing isolated runs — a race between
      the schedule statechart and the session turn lifecycle. The test file
      is in the agent-session component, untouched by this task's diff, and
      its own comment documents a ~1-in-8 full-suite flake class (mitigated
      but not eliminated by the session-id event-log filter). implementation.md
      records only the two retry-loop flakes (`response-mode-retry-test`,
      `prompt-provider-retry-after-tool-result...`) — this third,
      timing-sensitive flake is undocumented, so the design AC "`bb test`
      green" is not reliably reproducible on any given run.
      → Verify it is pre-existing on the task base commit (mirroring the
      review-2/review-5 approach), then add it to the implementation.md
      flake inventory (and/or harden the test — e.g. settle the lifecycle or
      assert phase after the deliver effect completes rather than immediately
      after `:scheduler/fired` returns).
      → Resolved (verified pre-existing + inventoried; chose not to harden
      the test — it lives in the agent-session component this task must not
      change, and the design AC forbids touching request-shaping/transport
      code): `scheduler_lifecycle_test.clj` is byte-identical between the
      task base commit (71d4821bf, the first task-248 commit) and HEAD, and
      the whole `components/agent-session/` directory has zero diff across
      the task's commit range — the flake predates this task. Passes in
      isolation (4 tests / 26 assertions; the "only pending schedules can
      fire" warning fires even on passing runs). Added as the third entry in
      the implementation.md flake inventory (alongside the two retry-loop
      flakes) — full-suite `bb test` is not deterministically green on any
      single run, independent of this task.
- [x] Configured-key + recognized-auth-header interplay is untested and
      unnamed for the `:openai-codex-responses` transport:
      `configured-key-plus-recognized-auth-header-interplay-test` (review 11)
      locks the behavior for `:anthropic-messages` and `:openai-completions`
      only (custom `Authorization` replaces the resolved key; custom
      `X-API-Key` coexists with the configured bearer key), and
      doc/custom-providers.md "Local servers and custom headers" names only
      "the openai transport" in its merge sentence. `build-codex-request`
      performs the identical `(merge base-hdrs custom)` (codex_responses.clj),
      so a custom `Authorization` header on a `:openai-codex-responses`
      provider silently replaces the resolved codex key too — same
      behavior, no test lock and no explicit doc naming.
      → Add a codex block to the interplay deftest (or a dedicated codex
      test) mirroring the review-11 assertions, and name
      `:openai-codex-responses` in the doc's merge sentence alongside the
      other two transports.
      → Resolved: `openai_test.clj` gains
      `codex-configured-key-plus-recognized-auth-header-interplay-test`
      (added by a concurrent review pass in the shared tree; verified
      end-to-end here) — custom `Authorization` replaces the resolved codex
      bearer key (chatgpt-account-id still derived from the configured key),
      custom `X-API-Key` coexists with the configured bearer key.
      `doc/custom-providers.md` "Local servers and custom headers" merge
      sentence now names all three transports (`:anthropic-messages`,
      `:openai-completions`, `:openai-codex-responses`).

## Follow-ups (implementation review 14, 2026-08-07)

- [x] Built-in detection is by provider NAME, so a custom models.edn provider
      literally named `"anthropic"` or `"openai"` is classified as built-in
      and defeats the provider-scoped guarantees reviews 3/10/13 claim to
      close. `builtin-anthropic?` (providers/anthropic.clj) and the inline
      `(or (nil? provider) (= :openai provider))` in openai/chat_completions
      .clj + codex_responses.clj match on the `:provider` keyword, and
      `expand-model` (user_models.clj) sets `:provider` from the models.edn
      provider key with no custom/built-in source marker. Verified
      (2026-08-07): a models.edn `{"anthropic" {:base-url
      "https://third-party.example" ...}}` provider with a non-colliding
      model id yields `:provider :anthropic`, so an unset configured key
      silently falls back to `ANTHROPIC_API_KEY` (sent to the third-party
      endpoint) and an `sk-ant-oat…` key triggers the Claude Code OAuth
      headers/system prompt — the exact cross-provider credential-disclosure
      class reviews 3/10/13 eliminated for every OTHER custom provider name.
      Fix direction: tag custom-provider models at parse time (e.g.
      `:custom? true` in `expand-model`) and gate built-in treatment on that
      flag, or validate/reject `anthropic`/`openai` as custom provider names;
      add a test proving a custom provider named "anthropic"/"openai" with an
      unset key does NOT fall back to the env var and does NOT get OAuth
      treatment. Note the specs model the built-in condition the same way
      (`provider == null or provider == "anthropic"` in all three allium
      files) — they inherit the same gap.
      → Resolved (origin-tag approach): `expand-model` now tags every custom
      models.edn model `:custom? true`, and the shared `builtin?` helper
      (providers/request_support.clj, used by all three transports via
      `builtin-anthropic?` / `resolve-api-key`) requires the tag in addition
      to the provider name — a custom provider literally named
      "anthropic"/"openai" never falls back to
      `ANTHROPIC_API_KEY`/`OPENAI_API_KEY` and never gets Claude Code OAuth
      treatment. `schemas/Model` gained the optional `:custom?` field (the
      closed canonical schema must accept the tag). Tests:
      `user_models_test.clj` `custom-provider-models-tagged-custom-test`
      (every custom model tagged; providers named "anthropic"/"openai" parse
      with `:custom? true`), `anthropic_test.clj`
      `custom-provider-named-anthropic-not-builtin-test` (unset key → throws
      "Missing API key for provider anthropic" with redef'd getenv; sk-ant-oat
      key → x-api-key auth, no OAuth headers/system prompt),
      `openai_completions_test.clj`
      `custom-provider-named-openai-not-builtin-test` (unset key → throws, no
      OPENAI_API_KEY fallback). All three allium specs model the tag
      (`ResolvedCustomModel.custom = true`, `Model.custom: Boolean = false`,
      `not model.custom` built-in conditions).
- [x] design.md "Revision note (implementation reviews)" is incomplete: it
      claims its four bullets are "the *only* provider-transport changes",
      but the review-11 OAuth content-sniff gating (`oauth?` now
      `(and (builtin-anthropic? model) (oauth-api-key? api-key))` in
      `build-request`/`request-headers` — a custom-provider header behavior
      change) and the review-7/11/13 case-insensitive capture redaction
      (`find-header` in both transports, `redact-authorization` Bearer-prefix
      alignment) are provider-transport changes not listed; the AC clause
      "no existing custom-provider behaviour changes except the review-driven
      provider-scoped API-key resolution and `:no-auth-header` key tolerance"
      is therefore inaccurate (the OAuth gating IS a custom-provider behavior
      change, and the redaction changes the capture payload). Extend the
      revision note bullets + the AC exception wording to name them.
      → Resolved: design.md "Revision note (implementation reviews)" now
      lists the OAuth content-sniff gating (review 11), the case-insensitive
      capture redaction (reviews 7/11/13), the `:custom?` origin tag
      (review 14) and the shared request-support namespace (review 14, pure
      refactor) as provider-transport changes; the AC exception wording now
      names all of them.
- [x] The "don't mix" doc guidance is case-dependent and the exact-case
      variants are untested on both transports: doc/custom-providers.md
      "Local servers and custom headers" says the custom auth header
      "duplicates the configured key (X-API-Key beside the lowercase
      x-api-key on the anthropic transport) or silently replaces it (a
      custom Authorization header on the openai transport)", but on the
      anthropic transport an EXACT-case `x-api-key` custom header REPLACES
      the configured key (`(merge base-hdrs custom)` on equal string keys —
      the configured credential is silently dropped), and on the openai
      transport a lowercase `authorization` custom header DUPLICATES beside
      the base `Authorization` instead of replacing. The review-11/12
      interplay tests lock only mixed-case `X-API-Key` and exact-case
      `Authorization`. Add exact-case (`x-api-key` on anthropic,
      `authorization` on openai) interplay assertions and tighten the doc
      sentence to name the case-dependence. (Distinct from the review-14
      codex-interplay item, which is about the codex transport not being
      covered at all.)
      → Resolved: exact-case interplay assertions added —
      `anthropic_test.clj` `configured-key-plus-recognized-auth-header-interplay-test`
      gains an exact-case `x-api-key` custom-header block (REPLACES the
      configured key — equal string-key merge), `openai_completions_test.clj`
      gains a lowercase `authorization` custom-header block (DUPLICATES
      beside the base `Authorization` — different casing, distinct keys).
      `doc/custom-providers.md` "Local servers and custom headers" merge
      sentence tightened to name the case-dependence explicitly.
- [x] Provider-scoped key resolution is triplicated across the three
      transports: `getenv`/`auth-header?`/`resolve-api-key` and the `no-auth?`
      computation are near-identical private copies in providers/anthropic
      .clj, providers/openai/chat_completions.clj and providers/openai/
      codex_responses.clj (introduced by reviews 3/4/10/13), and
      `find-header`/`redact-secret`/`redact-authorization` are duplicated in
      anthropic.clj + providers/openai/transport.clj. The copies already
      drifted — reviews 9/10/13 repeatedly reconciled spec/behavior
      mismatches between them, and each allium spec had to model the same
      rules separately. Extract a shared provider-request-support helper
      namespace (parameterized by the built-in provider keyword + env var
      name) and have all three transports use it, so future fixes land once.
      → Resolved: new `components/ai/src/psi/ai/providers/request_support.clj`
      (`psi.ai.providers.request-support`) owns the shared primitives —
      `getenv`/`auth-header?`/`no-auth?`/`builtin?`/`resolve-api-key`
      (parameterized by `{:builtin-provider :env-var :builtin-missing-msg}`)
      and `find-header`/`redact-secret`/`redact-authorization`/
      `mask-chatgpt-account-id`/`redact-headers`. All three transports
      (`anthropic.clj`, `openai/chat_completions.clj`,
      `openai/codex_responses.clj`) call the shared `no-auth?` +
      `resolve-api-key` with a transport config map; `anthropic.clj`'s
      400-fallback alias renamed to `anthropic-request-support` to free the
      `request-support` alias; `openai/transport.clj` redaction delegates to
      shared `redact-headers`. Behavior-preserving — full `bb test` green,
      clj-kondo clean.

## Follow-ups (implementation review 15, 2026-08-07)

- [x] `:adaptive-thinking` "ignored for `:openai-codex-responses`" is
      untested: doc/custom-providers.md says the field "is ignored for
      OpenAI-compatible (`:openai-completions` / `:openai-codex-responses`)
      custom providers" and `expand-model` carries it into every custom model
      map, but the only no-op lock is
      `openai-completions-adaptive-thinking-ignored-for-custom-providers-test`
      (review 10, completions only). The codex transport never reads
      `:adaptive-thinking` (`openai/reasoning.clj` `reasoning-effort` maps
      `:thinking-level` → classic `reasoning_effort`), so the claim holds —
      but no codex test proves it. Add a `build-codex-request` deftest
      mirroring the completions lock: a custom `:openai-codex-responses`
      model with and without `:adaptive-thinking true` (+ `:thinking-level
      :high`) yields byte-identical bodies, no `output_config`/adaptive
      leakage, classic `reasoning_effort "high"` unchanged. Codex was added
      to the docs claim in review 13 without the lock that review 10 closed
      for completions.
      → Resolved: `openai_test.clj` gains
      `codex-adaptive-thinking-ignored-for-custom-providers-test` — a custom
      `:openai-codex-responses` model (`:custom? true`, mirroring
      expand-model's origin tag) built with and without
      `:adaptive-thinking true` (+ `:thinking-level :high`) via
      `build-codex-request` yields byte-identical bodies, no
      `:output_config`/adaptive leakage, and the unchanged classic
      `reasoning {:effort "high" :summary "auto"}` shape. Mirrors the
      review-10 completions lock.
- [x] Custom-provider test fixtures no longer match the expand-model shape
      they claim to represent: `deepseek-custom-provider-model`
      (anthropic_test.clj) and the literal model map in
      `stream-anthropic-retries-adaptive-shape-without-thinking-on-400-test`
      (anthropic_stream_test.clj) are documented as shaped "the way
      `psi.ai.user-models/expand-model` produces" the map, but review 14's
      `expand-model` now tags every custom models.edn model `:custom? true`
      — both fixtures omit the tag. Behavior-neutral today (`:provider
      :deepseek` is never classified built-in regardless), but the fixtures'
      stated purpose (proving the transport path for an expand-model-shaped
      map) has silently drifted; a future `builtin?`/`:custom?` semantics
      change would not be caught. Add `:custom? true` to both fixtures (one
      line each) and re-run `psi.ai.providers.anthropic-test` +
      `psi.ai.providers.anthropic-stream-test`.
      → Resolved: `:custom? true` added to both fixtures —
      `deepseek-custom-provider-model` (anthropic_test.clj) and the literal
      model map in
      `stream-anthropic-retries-adaptive-shape-without-thinking-on-400-test`
      (anthropic_stream_test.clj) — so they match the review-14
      expand-model shape (every custom models.edn model is origin-tagged).
      `psi.ai.providers.anthropic-test` + `psi.ai.providers.anthropic-auth-test`
      + `psi.ai.providers.anthropic-stream-test` green.
- [x] `getenv` indirection duplicated across layers: `user_models.clj`'s
      private `getenv` (review 10) and `request_support/getenv` (review 14)
      are the identical 3-line `System/getenv` testability wrapper, and
      `parse-documented-deepseek-example-test` redefs `user-models/getenv`
      while the transport tests redef `request-support/getenv`. The review-14
      dedup ("future fixes land once") covered the three transports but left
      the config-parse layer's copy in place. Consider delegating
      `user_models/resolve-api-key-spec`'s `env:` lookup to the shared
      `request-support/getenv` (update the parse-lock test's redef target
      accordingly) so env-lookup testability lives in one place, or document
      why the config-parse layer intentionally keeps its own indirection.
      → Resolved: `user_models.clj` no longer defines its own private
      `getenv` — `resolve-api-key-spec`'s `env:` lookup now delegates to the
      shared `request-support/getenv` (the config-parse layer keeps no
      separate indirection); `parse-documented-deepseek-example-test` now
      redefs `psi.ai.providers.request-support/getenv` to the sentinel.
      Env-lookup testability lives in one place.
- [x] Committed tree fails the repo file-length commit gate:
      HEAD's `components/ai/test/psi/ai/providers/anthropic_test.clj` is 943
      lines (> the 800-line `commit-check:file-lengths` limit; no legacy
      ratchet covers it — bb.edn lists only extension files), so
      `bb commit-check:file-lengths` and the extensions commit-checks suite
      (`file-length-check-enforces-real-legacy-ratchets-test`) fail
      deterministically on the committed state. A fix exists in the working
      tree (verified 2026-08-07): the cohesive anthropic auth cluster was
      split into `anthropic_auth_test.clj` (389 lines) leaving
      `anthropic_test.clj` at 575 lines — behavior-preserving (20 tests /
      140 assertions across the two namespaces, unchanged) — but the split
      is UNCOMMITTED. Commit the split (or an equivalent reduction) before
      close so the committed task state satisfies the repo gate; add the
      file-length gate to the verification checklist for this task.
      → Resolved: the split is committed — `anthropic_auth_test.clj` (389
      lines) extracted the cohesive anthropic auth cluster, leaving
      `anthropic_test.clj` at 579 lines; `bb commit-check:file-lengths` and
      the extensions commit-checks suite
      (`file-length-check-enforces-real-legacy-ratchets-test`) pass on the
      committed tree (364 passed / 0 failed). File-length gate recorded in
      implementation.md verification.

## Follow-ups (implementation review 16, 2026-08-07)

- [x] Fix the 6 `^- [x]` markdown checklist artifacts in the two
      "Follow-ups (implementation review 14, 2026-08-07)" sections: each
      item's checkbox marker carries a stray `^` prefix (`^- [x]` instead of
      `- [x]`), breaking the checklist-marker convention and hiding the items
      from `grep "^- \["` (found 2026-08-07; lines ~1071, 1104, 1132, 1175,
      1194, 1219). Replace `^- [x]` → `- [x]` on those six lines.
      → Resolved: `^- [x]` → `- [x]` on all six lines (1071, 1104, 1132,
      1175, 1194, 1219); `grep "^- \["` now finds them.
- [x] Deduplicate the byte-identical `openai-api-key-config` /
      `codex-api-key-config` maps (`components/ai/src/psi/ai/providers/
      openai/chat_completions.clj` and `openai/codex_responses.clj`): the
      review-14 `request-support` namespace deduplicated the key-resolution
      *logic* across the three transports but left two identical per-transport
      config maps (`{:builtin-provider :openai :env-var "OPENAI_API_KEY"
      :builtin-missing-msg "Missing OpenAI API key..."}`) in sibling
      namespaces. If the env-var name or built-in missing-key message ever
      changes, the two copies can drift — the exact cross-transport drift
      class reviews 9/10/13 kept reconciling. Define the openai config once
      (shared constant in `request_support.clj`, or one openai ns required by
      the other) and reference it from both transports.
      → Resolved: `request-support/openai-api-key-config` defined once in
      `request_support.clj`; both `chat_completions.clj` and
      `codex_responses.clj` reference it directly (private per-transport
      copies removed). Env-var name and built-in missing-key message can no
      longer drift. clj-kondo clean; openai-completions (16/70),
      openai-codex (8/27), openai (16/84), openai-request-headers (6/28),
      openai-codex-retry (1/5) namespaces green.

## Follow-ups (implementation review 17, 2026-08-07)

- [x] Stale cross-transport comment in `providers/anthropic.clj`
      `build-request` (lines ~257-258): the keyless-logic comment claims the
      behavior is "consistent with the OpenAI transport, which only exempts
      on explicit :no-auth-header" — inaccurate since review 10: the
      `:openai-completions` transport (and `:openai-codex-responses` since
      review 13) uses the shared `request-support/no-auth?`, which also
      exempts a recognized auth header (`x-api-key`/`Authorization`,
      case-insensitive) among custom `:headers`. A reader would conclude the
      OpenAI transport still hard-requires a key unless `:no-auth-header` —
      the exact behavior review 10 closed. Fix: drop the stale parenthetical
      (or say both transports share `request-support/no-auth?` semantics);
      optionally collapse the duplicated 6-line keyless explanation to a
      one-line pointer to `request-support/no-auth?` (the same
      triplicated-comment class review 14 removed from the code).
      → Resolved: the comment now states the keyless logic is shared with
      the OpenAI transports via `request-support/no-auth?` (explicit
      `:no-auth-header` OR a recognized auth header among custom `:headers`
      with no configured key; incidental headers do NOT imply keyless) —
      the stale "only exempts on explicit :no-auth-header" claim is gone.
      Comment-only change; no behavior delta.
- [x] Remaining custom-provider test fixtures omit the `:custom?` origin tag
      (review-15 item 2 fixed only `deepseek-custom-provider-model` and the
      400-retry fixture): the same expand-model-shape drift remains in other
      custom-provider fixtures in the touched test files —
      `anthropic_stream_test.clj` `stream-anthropic-captures-provider-
      request-and-response-test` (MiniMax `:provider :minimax`, DeepSeek
      `:provider :deepseek`, and the two `:provider :local-proxy` fixtures)
      and `anthropic_test.clj` (the "custom provider never falls back to
      ANTHROPIC_API_KEY env var" deepseek fixture and the
      `:my-anthropic-proxy` kebab-case env-var-suggestion fixture).
      Behavior-neutral today (none of these provider names are built-in
      names), but a future `builtin?`/`:custom?` semantics change would not
      be caught — the same gap review 15 closed for the named two. Fix: add
      `:custom? true` to these fixtures (or extract a shared custom-provider
      fixture helper that always carries the tag), or explicitly document
      why literal transport fixtures are exempt from the origin tag.
      → Resolved: `:custom? true` added to every remaining custom-provider
      fixture in the touched anthropic test files —
      `anthropic_stream_test.clj` (MiniMax, DeepSeek, both `:local-proxy`
      fixtures) and `anthropic_test.clj` (deepseek no-leak fixture,
      `:my-anthropic-proxy` fixture, plus the two MiniMax missing-auth
      fixtures in the same file) — and, same drift class in the touched
      review-15 split-out file, all custom fixtures in
      `anthropic_auth_test.clj` (local-proxy keyless/interplay fixtures,
      deepseek incidental-headers and sk-ant-oat fixtures). Behavior-neutral
      (all non-built-in provider names); fixtures now match the review-14
      expand-model shape. Built-in `:provider :anthropic` fixtures
      (`models/get-model :sonnet-4.6`) correctly left untagged. Openai test
      files were outside this item's named scope (boundary noted in
      implementation.md).
- [x] `psi.agent-session.model-dispatch-test/model-thinking-dispatch-test`
      full-suite flake is not in the flake inventory: a full `bb test` run
      at review time (seed 1846209693; 2566 passed / 1 failed) failed this
      test — the dispatch log showed `:scheduler/drain-queue` instead of
      `:session/set-system-prompt` in `set-system-prompt-in! routes through
      dispatch log` (2 failed assertions). Verified pre-existing: passes in
      isolation (12 tests / 153 assertions green); `components/agent-session/`
      has zero diff across the task commit range (base 3c286a46e → HEAD) and
      the test file was last touched by 5c910d5d4 (pre-task) — the same
      scheduler-timing race class as the documented
      `scheduler-lifecycle-test` flake, but not named in the
      implementation.md flake inventory (which lists response-mode-retry,
      prompt-provider-retry, scheduler-lifecycle). Fix: add this test to the
      inventory entry (or note the observed run in the verification section)
      so the "full-suite green" claim names all known races.
      → Resolved: `model-thinking-dispatch-test` added to the
      implementation.md flake inventory — observed run (seed 1846209693,
      2566 passed / 1 failed, `:scheduler/drain-queue` instead of
      `:session/set-system-prompt`), passes in isolation (12 tests / 153
      assertions, re-verified 2026-08-07), `components/agent-session/` zero
      diff across the task commit range (3c286a46e → HEAD), test file last
      touched pre-task (5c910d5d4) — same scheduler-timing race class as
      `scheduler-lifecycle-test`. The inventory now names all four known
      races (response-mode-retry, prompt-provider-retry, scheduler-lifecycle,
      model-dispatch).

## Follow-ups (implementation review 18, 2026-08-08)

- [x] Openai test files' synthetic custom-provider fixtures omit the
      `:custom?` origin tag (review-17 deferred boundary, confirmed still
      present): every custom models.edn model is stamped `:custom? true` by
      `expand-model` (review 14), but the literal custom-provider fixtures in
      `openai_test.clj` (`:custom-codex`, `:my-codex-proxy`, `:local-codex`)
      and `openai_completions_test.clj` (`:custom-chat`, `:my-openai-proxy`,
      `:local-chat`, `:local3`) omit it. Behavior-neutral today (none of the
      names collide with built-in `:openai`/`:anthropic`, so `builtin?`
      classifies them custom either way), but it is the exact drift class
      reviews 15/17 closed on the anthropic test files — a future
      `builtin?`/`:custom?` semantics change (e.g. keying built-in-ness on
      the tag alone) would silently change these tests' behavior. Fix: add
      `:custom? true` to these fixtures (or extract a shared custom-provider
      fixture helper that always carries the tag), mirroring
      `anthropic_test.clj` / `anthropic_auth_test.clj` /
      `anthropic_stream_test.clj`.
      → Resolved: `:custom? true` added to every synthetic custom-provider
      fixture in both named files — `openai_test.clj` (`:custom-codex`,
      `:my-codex-proxy`, `:local-codex`) and `openai_completions_test.clj`
      (`:custom-chat`, `:my-openai-proxy`, `:local-chat`, `:local3`), plus
      the bare `:local` fixtures in both files (same drift class, named in
      the review-17 boundary note) — 21 fixtures tagged, all behavior-neutral
      (non-built-in provider names; `builtin?` requires `:custom?` false, so
      the tag only changes built-in classification). The already-tagged
      custom-provider-named-`"openai"` fixtures and built-in
      `:provider :openai` catalog fixtures were left untouched.
      `psi.ai.providers.openai-test` 16/88 and
      `psi.ai.providers.openai-completions-test` 16/70 green; clj-kondo +
      cljfmt clean. Note: `openai_request_headers_test.clj`'s custom `:local`
      fixtures remain untagged (outside this item's named scope; boundary
      recorded in implementation.md).
- [x] Design AC "`bb test` green" does not hold on current HEAD: post-task
      human commit b26f84f25 ("update workflows to use deepseek") re-enabled
      the `.psi/project.edn` deepseek workflow session-profiles that review 2
      reverted (the f0c818cc1 regression class) — the delegate-review live
      test fails when `deepseek/deepseek-v4-flash` is unresolvable (CI /
      fresh checkout), so a full-suite run on HEAD is red for the documented
      reason. The task's recorded full-suite green (ef4db8c0e, 2567 tests)
      predates that commit. Before close, decide: treat b26f84f25 as an
      intentional user-local override excluded from the task AC, or revert /
      conditionalize the profiles so the committed tree stays green; either
      way re-run `bb test` on the state being closed and record the result.
      → Resolved (option b — revert the activation; decision 2026-08-08):
      the failure is NOT CI-only — verified the delegate-review live test
      fails deterministically on this machine too ("unknown model
      deepseek/deepseek-v4-flash": the live test snapshots the committed
      session profiles against a temp model registry containing only
      `local/test-model`, so the deepseek profiles are unresolvable
      everywhere, user-global models.edn notwithstanding). Option (a) would
      leave the task unable to demonstrate its AC on any machine, so
      `.psi/project.edn` was restored to the review-2-established committed
      default (ef4db8c0e state): built-in anthropic catalog profiles active,
      deepseek + openai maps kept commented with the existing explanatory
      note — the human's local deepseek workflow preference remains a
      one-line local flip, and the file's own comment documents this
      convention. Delegate-review live test green again (3 tests / 21
      assertions). Full `bb test` on the state being closed: 2567 tests /
      19271 assertions / 0 failures (one run hit the documented
      scheduler-lifecycle flake, one run hit a newly-observed
      `workflow_judge_cancellation_test.clj` timing flake — both pass in
      isolation and are pre-existing; see implementation.md flake
      inventory).
- [x] Custom `chatgpt-account-id` header interplay on the
      `:openai-codex-responses` transport is untested and undocumented:
      `build-codex-request` derives `chatgpt-account-id` from the resolved
      key (omitted for keyless requests) but `(merge base-hdrs custom)` lets
      a custom `chatgpt-account-id` header silently replace the derived
      value (configured-key case) or supply one (keyless case). The
      review-11/14 interplay locks
      (`codex-configured-key-plus-recognized-auth-header-interplay-test`)
      cover only `Authorization`/`X-API-Key`, and doc/custom-providers.md's
      "don't mix" guidance names only auth headers. Add a codex interplay
      assertion (custom account-id header with a configured key → replaces
      the derived value; keyless + custom account-id → passes through)
      and/or a doc sentence naming the override.
      → Resolved (both options): `openai_test.clj`
      `codex-configured-key-plus-recognized-auth-header-interplay-test` gains
      two testing blocks — (1) configured key + custom `chatgpt-account-id`
      header → the custom value REPLACES the derived account id (configured
      key's account id not sent; configured key still sent as the bearer
      Authorization); (2) keyless (`:no-auth-header true`) + custom
      `chatgpt-account-id` header → passes through unmodified (supplies an
      account id for a keyless request, no Authorization). `doc/
      custom-providers.md` "Local servers and custom headers" merge
      paragraph now names the `:openai-codex-responses` `chatgpt-account-id`
      override (replaces the derived value; supplies one for keyless
      requests; don't mix a configured `:api-key` with a custom
      `chatgpt-account-id` header either). No production code change — the
      behavior is inherent in `build-codex-request`'s `(merge base-hdrs
      custom)` (design AC forbids transport changes). Namespace green (16
      tests / 88 assertions; +4 assertions from the two blocks).

## Follow-ups (implementation review 19, 2026-08-08)

- [x] Capture redaction leaks a differently-cased duplicate of an auth
      header: `request-support/redact-headers` redacts only the FIRST
      case-insensitive match per auth-header name (`find-header` returns the
      first `[k v]` whose lower-cased name matches; `assoc` replaces only that
      key). When a request carries BOTH casings of the same auth header —
      base `"x-api-key"` (configured key) + custom `"X-API-Key"`, or
      `"Authorization"` + `"authorization"` — the second one persists
      VERBATIM in the `:on-provider-request` capture. Verified end-to-end
      through `anthropic/build-request` + `capture-request!`: wire headers
      `{x-api-key configured-key, X-API-Key secret-custom-key}` capture as
      `{x-api-key ***REDACTED***, X-API-Key secret-custom-key}`. This is the
      exact "don't mix" scenario the review-11/14 interplay tests exercise on
      the wire (configured key + mixed-case custom header), and it contradicts
      the CHANGELOG claim that "secrets carried in custom :headers never
      persist verbatim in :on-provider-request session captures". Applies to
      all three transports (shared `redact-headers`). Fix: redact ALL
      case-insensitive matches per auth-header name (not just the first), and
      add a capture-path test on the anthropic transport (mirrored on the
      openai transport) with dual-casing auth headers asserting no verbatim
      secret in the capture.
      → Resolved: `request-support/redact-headers` now redacts EVERY
      case-insensitive match per auth-header name — new `find-headers`
      helper returns all matches, `find-header` delegates to it (first), and
      `redact-headers` applies the redactor to each match under its original
      key casing. A wire request carrying both casings of an auth header
      (base `x-api-key` + custom `X-API-Key`, or `Authorization` +
      `authorization`) now captures ALL matches redacted — no verbatim
      secret persists, satisfying the CHANGELOG claim. Capture-path tests
      added on both transports: `anthropic_stream_test.clj` (dual
      x-api-key/X-API-Key → both `***REDACTED***`) and
      `openai_request_headers_test.clj` (dual Authorization/authorization →
      both `Bearer ***REDACTED***`). Both new tests verified to FAIL against
      the old single-match implementation. Specs updated
      (`RedactRequestHeaders` in anthropic-provider.allium and
      openai-provider.allium note the all-matches semantics; the ∀-header
      ensure already models it).
- [x] 400-fallback `:without-all-betas` step is skipped for keyless
      custom-header Bearer auth: `fallback-request-steps-for-400` gates
      `:without-all-betas` on `(not (oauth-auth-request? request))`, and
      `anthropic/error.clj` `oauth-auth-request?` classifies ANY request
      carrying an `Authorization: Bearer ...` header as an OAuth request —
      including a keyless custom provider whose auth comes from a custom
      `Authorization: Bearer` header (the documented "Local servers and
      custom headers" keyless pattern). On a beta-related 400 such a request
      keeps ALL beta headers on the retry (e.g. `fast-mode-2026-02-01`), so
      the retry repeats the same 400 and hard-fails — the review-8 fast-mode
      note's "beta stripped, speed retained" degradation is worse here (not
      even the beta is stripped). Verified: `fallback-request-for-400` for a
      prompt-caching-beta request with a custom Bearer header selects only
      `[:without-prompt-caching]`, omitting `:without-all-betas`. Fix: narrow
      `oauth-auth-request?` (or the gate) to actual built-in OAuth requests —
      e.g. require the request's Authorization to have come from the
      transport's own OAuth resolution (built-in model + OAuth-shaped key)
      rather than any Bearer header — or document the limitation; add a
      fallback-selection test for the keyless custom-header-Bearer case.
      → Resolved: `anthropic/error.clj` `oauth-auth-request?` narrowed to the
      transport's own OAuth signature — `Authorization: Bearer` AND
      `user-agent: claude-cli/…` AND `x-app: cli`, the exact headers
      `request-headers` sets only for genuine built-in OAuth requests
      (built-in Anthropic model + OAuth-shaped key, review 11). A keyless
      custom provider's custom `Authorization: Bearer` header is no longer
      classified OAuth, so a beta-related 400 on such a request now selects
      `:without-all-betas` (ALL beta headers stripped on the retry; the
      custom Authorization header preserved) instead of keeping every beta
      and hard-failing. New
      `stream-anthropic-retries-without-all-betas-on-400-for-keyless-bearer-test`
      locks the fallback selection end-to-end (fast-mode beta on the first
      request, cleared on the retry, `:retry-fallback-steps
      [:without-all-betas]`, stream completes) plus direct
      `oauth-auth-request?` predicate assertions (genuine OAuth with all
      three markers still true; keyless custom Bearer false). Verified to
      FAIL against the old any-Bearer predicate (7 assertions). The error
      diagnostics also no longer label a keyless Bearer request `auth=oauth`.
      `spec/anthropic-provider.allium` 400-retry section updated
      (`BearerAuthRequest` → `OAuthAuthRequest` with the full signature);
      `doc/custom-providers.md` fast-mode note now states the beta stripping
      applies to keyless custom-header Bearer requests too (only genuine
      built-in Anthropic OAuth requests keep their betas — DeepSeek never
      is one).

## Follow-ups (implementation review 20, 2026-08-08)

- [x] `openai_request_headers_test.clj`'s custom `:provider :local` fixtures
      still omit the `:custom?` origin tag — the exact drift class reviews
      15/17/18 closed in every other touched test file (all anthropic test
      fixtures, openai_test.clj, openai_completions_test.clj tagged), and the
      review-18 resolution explicitly deferred this file ("boundary recorded
      in implementation.md") without opening a follow-up. Verified 2026-08-08:
      all five `:local` fixtures in the file (lines ~52/106/138/176/218)
      lack `:custom? true`; behavior-neutral today (`:local` never collides
      with built-in names, `builtin?` classifies it custom either way), but a
      future `builtin?`/`:custom?` semantics change (e.g. keying
      built-in-ness on the tag alone) would silently alter these tests — the
      same gap the task closed everywhere else. Fix: add `:custom? true` to
      these fixtures (or extract a shared custom-provider fixture helper that
      always carries the tag) and re-run
      `psi.ai.providers.openai-request-headers-test`.
      → Resolved: `:custom? true` added to all five `:provider :local`
      fixtures in `openai_request_headers_test.clj` (the two identity-capture
      fixtures, the three redaction-capture fixtures) — the last untagged
      custom-provider fixtures in the touched test files. Behavior-neutral
      (`:local` is never a built-in name; `builtin?` requires `:custom?`
      false). `psi.ai.providers.openai-request-headers-test` green (6 tests /
      30 assertions); clj-kondo clean (0 errors, 0 warnings); file-length
      gate passes (294 lines < 800).
- [x] Trailing-slash `:base-url` handling is inconsistent across the three
      transports: `:openai-codex-responses` normalizes (`resolve-codex-url`
      strips a trailing `/+$`), but `:anthropic-messages`
      (`(str (:base-url model) "/v1/messages")`) and `:openai-completions`
      (`(str (:base-url model) "/chat/completions")`) concatenate
      unnormalized — a custom-provider `:base-url` ending in `/` (e.g.
      `https://api.deepseek.com/anthropic/`) silently produces a double-slash
      URL (`//v1/messages`). The DeepSeek example and the custom-provider
      docs teach `:base-url` as "the API root" with no trailing-slash
      guidance, and the review-14 three-transport consistency standard
      (shared `request-support`) does not cover URL construction. Fix (docs
      option, within this task's scope): add a "no trailing slash" note to
      the `:base-url` description in `doc/custom-providers.md` and the
      DeepSeek example notes; or normalize the join in shared
      `request-support` as a separate transport change (design AC forbids
      transport changes in this task).
      → Resolved (docs option, per the item's in-scope option; transport
      normalization deliberately not done — design AC forbids transport
      changes): `doc/custom-providers.md` `:base-url` bullet now states the
      API root must have no trailing slash — psi concatenates the protocol
      path suffix verbatim (`/v1/messages`, `/chat/completions`,
      `/codex/responses`; only the codex transport normalizes a trailing
      slash away), so a trailing `/` silently yields a double-slash URL.
      DeepSeek example notes gain a matching bullet using the concrete
      `https://api.deepseek.com/anthropic/` → `//v1/messages` example.
      Doc-parse-lock test still green (the ```clojure EDN block is
      untouched; notes prose only): `psi.ai.user-models-test` green
      (15 tests / 105 assertions).

## Follow-ups (implementation review 21, 2026-08-08)

- [x] The documented DeepSeek example misclassifies a cloud model as local/free:
      `doc/custom-providers.md`'s `deepseek-v4-flash` model map sets none of
      `:locality`, `:latency-tier`, `:cost-tier`, so `expand-model`'s
      `model-defaults` apply — `:locality :local`, `:latency-tier :low`,
      `:cost-tier :zero`. The entity-resolution and tooling-friction helper
      sessions (context-manager, pre-turn blocking path) select their "local"
      helper via required `:latency-tier :low` + `:cost-tier #{:zero :low}`,
      strong `:locality :local`, and the `default-select-model` guard
      `(= :local (:facts :locality))` — all of which a DeepSeek model with
      the defaulted fields PASSES, so a user who follows the documented
      example can get cloud DeepSeek calls (with conversation excerpts) on
      the local-only helper path, plus zero-cost pricing estimates. Fix:
      add `:locality :cloud` (and explicit `:latency-tier`/`:cost-tier`) to
      the DeepSeek example model map; note the locality/tier defaults for
      custom models in `doc/custom-providers.md` (the fields are currently
      undocumented there); extend `parse-documented-deepseek-example-test`
      (user_models_test.clj) to assert `:locality :cloud` so the guard locks;
      and align `deepseek-custom-provider-model` (anthropic_test.clj) with
      the example. (The pre-existing MiniMax example has the same
      latency/cost omission but predates this task; scope this item to the
      DeepSeek example this task documents.)
      → Resolved (all four sub-items): `doc/custom-providers.md` DeepSeek
      example model map now sets `:locality :cloud` `:latency-tier :low`
      `:cost-tier :low` explicitly; the "What a provider definition
      contains" section now documents the three fields and their
      `model-defaults` (`:locality :local`, `:latency-tier :low`,
      `:cost-tier :zero`) plus the local-helper-selection implication
      (context-manager requires `:latency-tier :low` + `:cost-tier
      #{:zero :low}` with a strong `:locality :local` preference and a
      non-local guard, so a cloud model with defaulted locality can be
      selected for (and charged as) a "local" helper); the DeepSeek notes
      gain a bullet naming the explicit values and the same consequence.
      `parse-documented-deepseek-example-test` now asserts
      `:locality :cloud`/`:latency-tier :low`/`:cost-tier :low` on the
      parsed example (the guard locks); `deepseek-custom-provider-model`
      (anthropic_test.clj) and the literal deepseek fixture in
      `anthropic_stream_test.clj`'s adaptive-shape 400-retry test (same
      drift class) aligned with the example. Verification:
      `psi.ai.user-models-test` 15/108 (was 15/105; +3 locality/tier
      assertions), anthropic namespaces green; clj-kondo clean; full `bb
      test` green.
- [x] `spec/openai-provider.allium` does not model the keyless request
      construction path: `CompletionsRequestBuilt` requires
      `not IsBlank(stream.resolved_api_key)` and `CodexRequestBuilt`
      requires `not IsBlank(stream.resolved_api_key)` AND
      `not IsBlank(stream.chatgpt_account_id)` — these are the ONLY rules
      producing `ProviderRequestBuilt` for the openai transport. A keyless
      `:openai-completions`/`:openai-codex-responses` request
      (`:no-auth-header` or a recognized auth header among custom `:headers`
      with no configured key — the documented "Local servers and custom
      headers" pattern, tested by this task's keyless blocks and review 13's
      keyless codex tests) therefore has no request-construction rule; the
      keyless flags are modeled (`KeylessRequestDetermined`,
      `OpenAIApiKeyResolved` → nil key) but the resulting keyless request
      build (no `Authorization`; codex also omits `chatgpt-account-id`) is
      unmodeled. The anthropic spec covers this (`ApiKeyResolved` ensures
      keyless → nil key, and `AnthropicRequestBodyBuilt` has no key
      `requires`); the openai spec should mirror it — e.g. gate the keyless
      build separately (`KeylessCompletionsRequestBuilt` /
      `KeylessCodexRequestBuilt`) or relax the `requires` clauses and model
      the omitted auth headers. Manual allium-check (no automated checker in
      repo) per the established pattern.
      → Resolved (gate-the-keyless-build-separately option, mirroring the
      anthropic spec): `spec/openai-provider.allium` gains
      `KeylessCompletionsRequestBuilt` and `KeylessCodexRequestBuilt`
      (`requires: stream.keyless`; body identical to the authenticated
      build, guidance documents the omitted `Authorization` and — for codex
      — omitted `chatgpt-account-id`, with the custom-header-supplied
      account-id pass-through noted), and the authenticated
      `CompletionsRequestBuilt`/`CodexRequestBuilt` rules now carry an
      explicit `requires: not stream.keyless` so the keyless/authenticated
      build paths are cleanly partitioned (keyless → nil key per
      `OpenAIApiKeyResolved`, so the old `requires` already implied it; the
      gate is self-documenting). No code change — spec-only. Manual
      allium-check (no automated checker in repo): all referenced entities/
      attributes (`stream.keyless`, `request_api`, `request_url`,
      `resolved_api_key`, `chatgpt_account_id`, `ProviderRequestBuilt`,
      `CompletionsRequestBody`, `CodexRequestBody`,
      `RequestBodyDoesNotContain`) are defined in the spec.
- [x] `chatgpt-account-id` capture masking has no test lock: the shared
      `request-support/mask-chatgpt-account-id` (first 6 chars + "...",
      review 11) is wired into `openai/transport.clj`
      `redact-request-headers`, but no capture-path test asserts the masked
      output — `codex-request-and-reply-capture-callbacks-test`
      (openai_test.clj) asserts only `Authorization` redaction, and
      `custom-header-auth-redacted-in-captures-test`
      (openai_request_headers_test.clj) covers `X-API-Key`/`authorization`
      only. Add a codex capture-path assertion that a wire
      `chatgpt-account-id` header (and a mixed-case duplicate, mirroring the
      review-19 dual-casing locks) is masked to its first-6-chars form in
      the `:on-provider-request` payload, so the mask cannot silently regress
      to verbatim.
      → Resolved: `openai_codex_test.clj` gains
      `codex-chatgpt-account-id-capture-masked-test` — a keyless
      `:openai-codex-responses` stream request (`:no-auth-header true`,
      custom `:headers {"chatgpt-account-id" "acc_1234567890"
      "ChatGPT-Account-Id" "acc_0987654321"}` passing through per the
      review-18 keyless pass-through) asserts the `:on-provider-request`
      payload masks BOTH casings to first-6-chars + "..."
      (`"acc_12..."`/`"acc_09..."` under the original key casing — review-19
      dual-casing semantics via the shared `find-headers`-based
      `redact-headers`) and that the keyless request sends no
      `Authorization`. The test lives in `openai_codex_test.clj` (the
      existing codex transport test home) rather than `openai_test.clj`:
      adding it to `openai_test.clj` pushed that file to 830 lines, failing
      the repo `commit-check:file-lengths` gate (committed state 775);
      moving it to the codex file keeps `openai_test.clj` at its committed
      775 and the gate green (`openai_codex_test.clj` 267 lines). Note:
      `openai_test.clj` still carries seven codex deftests
      (`codex-requires-chatgpt-token`, `codex-reasoning-*`,
      `codex-tool-call-id-roundtrip`, `codex-function-call-done`) that are
      byte-identical to copies in `openai_codex_test.clj` — pre-existing
      duplication (openai_codex_test.clj predates this task, commit
      008b1e094) outside review-21's scope; recorded here for a future
      dedup. Verification: `psi.ai.providers.openai-codex-test` 9/30 (was
      8/27; +1 deftest +3 assertions), `psi.ai.providers.openai-test` 16/88
      (unchanged); clj-kondo clean; full `bb test` green.

## Follow-ups (implementation review 22, 2026-08-08)

- [x] `oauth-auth-request?` (anthropic/error.clj) content-sniffs the three-marker
      OAuth signature from the MERGED request headers, so the documented
      keyless custom-header pattern can reproduce it: a keyless custom
      `:anthropic-messages` provider whose custom `:headers` carry
      `Authorization: Bearer …` PLUS `user-agent: claude-cli/…` AND `x-app:
      cli` (all three markers — the "Local servers and custom headers"
      pattern permits arbitrary headers, and a Claude Code-compatible
      gateway could set exactly these) is classified as a genuine OAuth
      request. Consequence: on a beta-related HTTP 400,
      `fallback-request-steps-for-400` skips `:without-all-betas`, so ALL
      betas (e.g. `fast-mode-2026-02-01`) are retained on the retry → the
      retry repeats the same 400 and hard-fails — the review-19 regression
      class, still reachable through the full marker set. This also
      contradicts doc/custom-providers.md's fast-mode note claim that "only
      genuine built-in Anthropic OAuth requests keep their betas" — the code
      cannot distinguish a spoofed marker set from a genuine one (genuine
      OAuth is a transport decision: `builtin-anthropic?` + `oauth-api-key?`
      in `build-request`, review 11). Fix direction: thread the transport's
      actual `oauth?` boolean from `build-request`/`stream-anthropic` into
      `handle-400-response!`'s beta-config (replace the content-sniffing
      predicate with the computed decision) and add a stream test with a
      keyless custom-provider request carrying all three markers +
      `:speed-mode :fast` asserting `:without-all-betas` is selected (betas
      stripped on the retry) — fails against the current content-sniffing
      predicate. Keep `oauth-auth-request?` for error diagnostics only, or
      align the doc sentence to name the marker-set limitation.
      → Resolved (thread-the-computed-decision option): `build-request` now
      attaches the transport's COMPUTED `oauth?` boolean (built-in Anthropic
      model + OAuth-shaped key, review 11) to the request map as `::oauth?`,
      and `handle-400-response!` passes `:oauth-auth-request? (fn [req]
      (boolean (::oauth? req)))` in the beta-config — replacing the header
      content-sniff for the `:without-all-betas` selection. A keyless custom
      `:anthropic-messages` provider whose custom `:headers` reproduce all
      three Claude Code CLI markers (Authorization Bearer + user-agent:
      claude-cli/… + x-app: cli) is no longer classified OAuth: on a
      beta-related 400 it now selects `:without-all-betas` (all betas
      stripped on the retry, custom headers preserved) instead of retaining
      every beta, repeating the 400 and hard-failing. New stream test
      `stream-anthropic-400-fallback-uses-transport-oauth-decision-test`
      (keyless custom provider, three markers + `:speed-mode :fast` → 400 →
      retry with `[:without-all-betas]`, stream completes) — verified to FAIL
      against the old content-sniffing predicate (6 assertions). The
      content-sniffing `oauth-auth-request?` (error.clj) is kept for error
      diagnostics only (`auth=oauth` hint in 400 messages); the
      doc/custom-providers.md fast-mode note's "only genuine built-in
      Anthropic OAuth requests keep their betas" claim is now exact.
      `spec/anthropic-provider.allium` HTTP-400 section updated (the
      fallback decision is `stream.oauth`, not a header content-sniff).
- [x] `request-support/resolve-api-key`'s keyless early-return tests only
      `(:no-auth-header options)`, not the shared `no-auth?` predicate — the
      two keyless definitions can drift, and the public function throws for
      a headers-auth keyless config when called directly: `(resolve-api-key
      model {:headers {"X-API-Key" "k"}} config)` (no `:no-auth-header`,
      blank key) throws "Missing API key" even though `no-auth?` is true for
      the same options. Every real caller (`build-request` in all three
      transports) gates on `no-auth?` first, so the `:no-auth-header` branch
      is currently dead code whose docstring documents a contract the
      callers never exercise. Unify: `(when-not (no-auth? options) …)` inside
      `resolve-api-key` so the function itself is safe for direct callers and
      the keyless logic lives in one predicate; add a direct-call test
      (headers-auth keyless → nil; `:no-auth-header` keyless → nil;
      blank-key no-auth → throws) locking the unified contract.
      → Resolved: `request-support/resolve-api-key` now computes its keyless
      early-return with the shared `no-auth?` predicate (`(when-not (no-auth?
      options) …)`) — the keyless contract lives in one predicate, and the
      function is safe for direct callers (headers-auth keyless and
      `:no-auth-header` keyless both return nil; blank-key non-keyless still
      throws the provider-scoped "Missing API key" error). Behavior-
      preserving for all real callers (the three transports gate on
      `no-auth?` first anyway). New direct-call contract tests in a new
      shared-namespace test file
      `components/ai/test/psi/ai/providers/request_support_test.clj`
      (`resolve-api-key-keyless-contract-test` + `no-auth?-predicate-test`):
      keyless → nil for both exemption classes, incidental-headers → throws,
      built-in env fallback preserved, configured key passes through.
      `spec/custom-providers.allium` / both provider specs already model the
      keyless contract via `stream.keyless` (no spec delta needed).
- [x] `doc/custom-providers.md` never documents
      `:supports-mid-conversation-system-messages`, and the DeepSeek example
      omits it: the field exists in the canonical `Model` schema and gates a
      real session capability (`session-supports-mid-system-messages?` in
      agent-session `model_capabilities.clj`; `:session/inject-mid-system-
      message` returns `:capability-not-supported` for a
      `:anthropic-messages` custom provider without the flag — only
      `:openai`/`:openai-completions` is inferred). A DeepSeek user
      following the documented example cannot use mid-conversation system
      messages even though Anthropic's Messages API supports per-turn
      `system` changes and DeepSeek's compat table lists `system` as fully
      supported (mid-conversation switching unverified). Fix: document the
      field in "What a provider definition contains" (what it gates, default
      false for `:anthropic-messages` custom providers, the OpenAI
      chat-completions inference) and add a note to the DeepSeek example
      (set it only after verifying the endpoint honours per-turn `system`
      changes), matching the review-21 locality/tier treatment.
      → Resolved (schema gate + docs + test; NOT added to the example EDN —
      the capability is unverified live, and the note says to set it only
      after verifying): the closed `ModelDef` schema in `user_models.clj`
      gains `[:supports-mid-conversation-system-messages {:optional true}
      [:maybe boolean?]]` (the canonical `Model` schema already had it, but
      models.edn custom providers could not declare it at all); the field
      flows through `expand-model`'s verbatim merge. `doc/custom-providers.md`
      "What a provider definition contains" now documents the field (what it
      gates — `:session/inject-mid-system-message` →
      `:capability-not-supported` without it; default false for
      `:anthropic-messages` custom providers; the `:openai`/
      `:openai-completions` inference) and the DeepSeek example notes gain a
      bullet stating the example does NOT enable it (DeepSeek compat lists
      `system` fully supported but per-turn switching unverified — set it
      only after live verification). New `supports-mid-conversation-system-
      messages-field-test` in `user_models_test.clj` (true/false accepted and
      flow through; omitted stays valid and absent). `spec/custom-providers
      .allium` `CustomModelDef`/`ResolvedCustomModel`/`ParseModelsConfig`
      carry the new field. CHANGELOG `[Unreleased]` → `Added` entry added.
- [x] Custom `anthropic-beta` header interplay with the 400-fallback is
      undocumented and untested: `build-request` merges custom `:headers`
      over the base headers, so a custom `"anthropic-beta"` header REPLACES
      the transport-generated beta header on the first request (the
      transport's own betas — prompt-caching, interleaved-thinking,
      fast-mode — are silently dropped from the wire), and on a beta-related
      400 `:without-all-betas` (`clear-beta-header`) wipes the user's custom
      beta too — the retry may then 400 for a DIFFERENT reason (missing
      provider-required beta) and hard-fail, masking the original error. The
      "don't mix" guidance covers auth headers only, not betas. Fix (docs
      and/or code): document that a custom `anthropic-beta` header replaces
      the transport betas and is also stripped by `:without-all-betas`, and/
      or make `:without-all-betas` strip only the transport-known betas
      (preserving custom-header beta values); add a build-request +
      400-fallback test for a custom `anthropic-beta` header.
      → Resolved (docs + tests; chose documentation over the code option —
      making `:without-all-betas` strip only transport-known betas would be
      a transport behavior change the design AC forbids): `doc/
      custom-providers.md` "Local servers and custom headers" now documents
      both consequences — a custom `"anthropic-beta"` header replaces the
      transport-generated betas on the wire (features gated by those betas
      e.g. fast mode stop working), and `:without-all-betas` wipes the custom
      beta too on a beta-related 400 (the retry may then 400 for a different
      reason, masking the original error). Tests lock both:
      `build-request-custom-anthropic-beta-header-replaces-transport-betas-test`
      (anthropic_test.clj — `:speed-mode :fast` + `:thinking-level :medium`
      with a custom `"anthropic-beta"` header → wire carries ONLY the custom
      value; without it the transport betas are sent) and
      `stream-anthropic-custom-anthropic-beta-header-stripped-by-without-all-
      betas-test` (anthropic_stream_test.clj — custom beta on the first
      request, 400 → `:without-all-betas` → retried request has no
      `anthropic-beta` at all, `[:without-all-betas]` recorded, configured
      `x-api-key` preserved, stream completes).

## Follow-ups (implementation review 23, 2026-08-08)

- [x] `spec/custom-providers.allium`'s `CustomModelDef`/`ResolvedCustomModel`/
      `ParseModelsConfig` drift against the closed `ModelDef` schema in
      `user_models.clj`: the spec value models id/name/supports-reasoning/
      supports-images/supports-text/adaptive-thinking/
      supports-mid-conversation-system-messages/context-window/max-tokens/
      input/output/cache costs, but the actual schema also accepts
      `:parallel-tool-calls`, `:locality`, `:latency-tier`, `:cost-tier` and
      `:capabilities` — and the task's own DeepSeek example (review 21) now
      sets `:locality :cloud`/`:latency-tier :low`/`:cost-tier :low` (locked
      by `parse-documented-deepseek-example-test`), so the exact documented
      example EDN would NOT validate against the spec's `CustomModelDef`.
      `ResolvedCustomModel` claims "carries all fields of a built-in model
      plus origin metadata" but omits them, and `ParseModelsConfig` doesn't
      map them. Fix: add `parallel_tool_calls: Boolean?`,
      `locality: local | cloud = local`, `latency_tier: low | medium | high
      = low`, `cost_tier: zero | low | medium | high = zero` and a
      `capabilities` field (optional, matching the schema's
      `ModelCapabilities`) to `CustomModelDef`; carry them in
      `ResolvedCustomModel`; map them in `ParseModelsConfig` (pass-through
      like the other model-def fields). Manual allium-check per the
      established pattern (no automated checker in repo).
      → Resolved: `CustomModelDef` gains `parallel_tool_calls: Boolean?`,
      `locality: local | cloud = local`, `latency_tier: low | medium | high
      = low`, `cost_tier: zero | low | medium | high = zero` and
      `capabilities: ModelCapabilities?` (new `ModelCapabilities` /
      `StructuredOutputCapability` / `TextualToolCallFormat` values mirror
      schemas/ModelCapabilities); `ResolvedCustomModel` carries all five;
      `ParseModelsConfig` maps them pass-through. The documented DeepSeek
      example (locality :cloud / latency-tier :low / cost-tier :low) now
      validates against the spec's `CustomModelDef`. Manual allium-check:
      every referenced field/type is defined in the spec.
- [x] `spec/anthropic-provider.allium` models the HTTP-400 fallback's beta
      TRANSFORMS (`WithoutPromptCachingStep`, `WithoutThinkingStep`,
      `WithoutAllBetasStepClearsBetasAndOutputFormat`, incl. the review-22
      `stream.oauth` decision and the `"speed"`-retention rule) but never
      models the FIRST-request beta/body construction: `StreamOptions` has
      no `speed_mode`, and `AnthropicRequestBodyBuilt` doesn't ensure the
      `"speed": "fast"` body field or the assembled `anthropic-beta` header
      (`beta-header`/`request-headers` in providers/anthropic.clj: oauth →
      claude-code/oauth/context-management/prompt-caching-scope,
      extended-thinking → interleaved-thinking, prompt-caching →
      prompt-caching, `:fast` → fast-mode-2026-02-01, structured-output →
      json-schema beta). The task documents fast mode on DeepSeek (review-8
      note: `"speed": "fast"` + fast-mode beta are sent; a speed-field 400
      retries with the field retained and hard-fails) and review 22 touched
      the retry half of the same behavior, but the first-request half is
      unmodeled. Fix: add `speed_mode: fast | normal | null` to
      `StreamOptions`; a rule ensuring `RequestBodyContains("speed",
      "fast")` plus the fast-mode beta header when `speed_mode = fast` and
      neither when not; optionally a beta-header assembly rule covering the
      oauth/prompt-caching/interleaved-thinking betas the retry rules
      already reference by name. The openai spec has the same class of gap:
      `:speed-mode :fast` → `service_tier: "flex"` on `:openai-completions`
      (locked by `speed-mode-fast-adds-service-tier-flex-test`) is absent
      from `spec/openai-provider.allium`'s `StreamOptions`/
      `CompletionsRequestBuilt` — close both or split into separate steps.
      Manual allium-check per the established pattern.
      → Resolved (both specs): `spec/anthropic-provider.allium`
      `StreamOptions` gains `speed_mode: fast | normal | null`; new
      `FastModeBodyAndBetaHeaderForSpeedMode` /
      `NoFastModeFieldsWhenSpeedModeNotFast` rules ensure the `"speed":
      "fast"` body field + `fast-mode-2026-02-01` beta appear iff
      `speed_mode = fast`; new `BetaHeaderAssembledForRequest` rule models
      the full first-request beta assembly (oauth →
      claude-code/oauth/context-management/prompt-caching-scope, classic
      extended thinking → interleaved-thinking, prompt-caching →
      prompt-caching, fast → fast-mode-2026-02-01, structured-output →
      structured-outputs-2025-11-13; adaptive thinking never adds
      interleaved-thinking), with `AnthropicBetaHeader(stream)` defined as
      rule vocabulary. `spec/openai-provider.allium` `StreamOptions` gains
      `speed_mode: fast | normal | null` and new
      `FastModeServiceTierMappedForCompletions` rule ensures
      `service_tier: "flex"` iff `speed_mode = fast` on
      `:openai-completions` (codex never emits it). Manual allium-check:
      all referenced entities/attributes defined.
- [x] `spec/custom-providers.allium`'s `RequestOptions` value models only
      `api_key`/`no_auth_header`/`headers`/`thinking_level`, while the real
      request-options map (`prompt_request.clj` `session->request-options`
      + transport options) also carries `:temperature`, `:speed-mode`,
      `:effort-override`, `:logprobs-enabled`/`:top-logprobs` and
      `:structured-output` — the anthropic spec's `StreamOptions` models
      `temperature`/`effort_override` and (per the item above) should model
      `speed_mode`, so the custom-providers spec's request-options model is
      narrower than its sibling spec's for the same concept. Fix: extend
      `RequestOptions` with `temperature: Number?`,
      `speed_mode: fast | normal | null`,
      `effort_override: low | medium | high | xhigh | null` (mirroring
      `StreamOptions`); logprobs/structured-output can stay out of scope if
      the spec only models what its rules reference. Manual allium-check
      per the established pattern.
      → Resolved: `RequestOptions` extended with `temperature: Number?`,
      `speed_mode: fast | normal | null` and
      `effort_override: low | medium | high | xhigh | null` — mirroring the
      anthropic spec's `StreamOptions` for the same concept
      (`session->request-options` in prompt_request.clj carries all three).
      `:logprobs-enabled`/`:top-logprobs` and `:structured-output` left out
      of scope per the item (no rule in this spec references them). Manual
      allium-check: no undefined fields/types introduced.

## Follow-ups (implementation review 24, 2026-08-08)

- [x] `openai_completions_logprobs_test.clj`'s literal custom-provider
      fixture (`:provider :local3`, line ~107,
      `completion-response-with-logprobs-and-missing-model-pricing-test`)
      lacks the `:custom? true` origin tag — the last untagged custom
      fixture in a task-touched test file. Reviews 15/17/18/20 closed this
      drift class in every other touched file (review 18 tagged the two
      `:local3` fixtures in `openai_completions_test.clj`; this logprobs
      file was outside that scope, though the task touched it in review 10
      when adding `:api-key` to its build-request calls). Behavior-neutral
      today (`:local3` is never a built-in name, and the fixture only feeds
      `completion-response->assistant-message`, which never reads
      `:custom?`), but a future `builtin?`/`:custom?` semantics change
      (e.g. keying built-in-ness on the tag alone) would silently alter the
      fixture's classification — the same gap the task closed everywhere
      else. Fix: add `:custom? true` (one line) and re-run
      `psi.ai.providers.openai-completions-logprobs-test`.
      → Resolved: `:custom? true` added to the `:local3` fixture (the model
      map in `completion-response-with-logprobs-and-missing-model-pricing-test`),
      matching the review-14 expand-model shape and closing the last
      untagged custom fixture in a task-touched test file. Behavior-neutral
      (`:local3` is never a built-in name; the fixture feeds only
      `completion-response->assistant-message`). Namespace green (10 tests /
      27 assertions, unchanged counts — the tag is behavior-neutral).
- [x] Direct shared-namespace unit tests for the remaining
      `request-support` primitives: `request_support_test.clj` (review 22)
      locks `resolve-api-key`/`no-auth?`/`auth-header?` directly, but
      `builtin?` (the review-14 origin-tag gate — the predicate that
      decides env-var fallback / OAuth treatment, and the most
      security-relevant helper in the namespace) and the capture-redaction
      helpers (`find-headers`/`find-header`/`redact-headers`/
      `redact-secret`/`redact-authorization`/`mask-chatgpt-account-id`,
      reviews 7/11/13/19) are only exercised indirectly through transport
      capture tests. Since the shared namespace is the "future fixes land
      once" home (review 14), add direct tests there (e.g. `builtin?` with
      `:custom?` true/false/absent + provider nil/builtin/other;
      `redact-headers` dual-casing all-matches semantics and
      `redact-authorization` Bearer-prefix length) so a shared-namespace
      regression fails without needing the transport files.
      → Resolved: `request_support_test.clj` gains 7 direct deftests —
      `builtin?-origin-tag-gate-test` (`:custom?` true/false/absent ×
      provider nil/builtin/other; a custom provider literally named
      "anthropic" is NOT built-in), `find-headers-case-insensitive-all-matches-test`
      (every case-insensitive match under its original casing; keyword keys),
      `find-header-first-match-test`, `redact-secret-test` (length suffix
      only for values > 20 chars; non-strings → nil),
      `redact-authorization-test` (Bearer prefix stripped before counting —
      review-13 len semantics), `mask-chatgpt-account-id-test` (first-6-chars
      masking), `redact-headers-all-matches-dual-casing-test` (dual-casing
      x-api-key/Authorization/chatgpt-account-id → every match redacted, no
      verbatim secret, original key casing preserved, non-auth headers pass
      through). A shared-namespace regression now fails without needing the
      transport files. Namespace green (9 tests / 57 assertions, was 2/18).

## Follow-ups (implementation review 25, 2026-08-08)

- [x] `supports-mid-system-messages?` (components/agent-session/src/psi/
      agent_session/model_capabilities.clj) infers the mid-conversation
      system-message capability from provider NAME + api —
      `(and (= :openai (:provider model)) (= :openai-completions (:api model)))`
      — without the review-14 `:custom?` origin-tag guard. Review 22 added
      the explicit `:supports-mid-conversation-system-messages` ModelDef
      field and documented "only :openai/:openai-completions models get the
      capability inferred" (meaning built-in catalog models; custom providers
      must declare it). But a custom models.edn provider literally named
      "openai" (api :openai-completions) is tagged `:custom? true` by
      `expand-model` and still receives the built-in-only inference BY NAME —
      the exact provider-name-collision class review 14 closed for env-key
      fallback and OAuth treatment, and a contradiction of the design's
      review-14 claim ("a custom provider literally named 'anthropic'/'openai'
      can no longer be classified built-in … never receives built-in-only
      treatment"). Verified live (2026-08-08): `supports-mid-system-messages?`
      on `{:provider :openai :api :openai-completions :custom? true}` → true,
      while `{:provider :deepseek :api :openai-completions :custom? true}` →
      false. Consequence: a custom provider named "openai" silently gets
      `:session/inject-mid-system-message` enabled without declaring the
      field, while every other custom provider must declare it explicitly.
      Fix: add `(not (:custom? model))` to the inference branch
      (behavior-preserving — built-in catalog models never carry the tag),
      and add a test locking a custom provider named "openai" (`:custom? true`)
      does NOT get the inference — fits beside
      `mid-system-capability-dispatch-test` in model_dispatch_test.clj or as
      a direct model-capabilities unit test.
      → Resolved: `(not (:custom? model))` added to the inference branch in
      `model_capabilities.clj` (behavior-preserving — no built-in catalog
      model carries the tag; only `expand-model`-tagged custom models do).
      New `mid-system-capability-custom-origin-test` deftest in
      `model_dispatch_test.clj` locks it directly on the pure predicate:
      custom "openai" (`:custom? true`) → false, custom "deepseek"
      (`:custom? true`) → false, untagged built-in OpenAI shape → true,
      and explicit `:supports-mid-conversation-system-messages` still wins
      for custom models. Namespace green (13 tests / 158 assertions).
- [x] `:custom?` is undocumented in doc/custom-providers.md: `expand-model`
      tags every custom models.edn model `:custom? true` (review 14 — the
      origin tag that gates built-in classification, env-key fallback and
      OAuth treatment), but the docs never mention it, and the closed
      `ModelDef` schema in user_models.clj REJECTS a user-declared `:custom?`
      key with a generic "Invalid models.edn schema" error (no hint that it
      is an internal/reserved tag). A user who sees `:custom? true` in an
      introspected model map (resolvers/EQL) or copies a model map into
      models.edn gets a confusing validation failure. Fix: add a note in
      "What a provider definition contains" that `:custom?` is an internal
      origin tag set by psi for custom models (never declare it in models.edn
      — the closed ModelDef schema rejects it); optionally mirror the note in
      spec/custom-providers.allium, where `ResolvedCustomModel.custom = true`
      is currently the only place the tag is described and nothing says it is
      not settable from models.edn.
      → Resolved: "What a provider definition contains" gains a
      "Note on `:custom?`" paragraph — internal origin tag set by psi at
      parse time, never declared in models.edn (closed ModelDef schema
      rejects it), gates built-in classification (env-key fallback, OAuth
      headers, mid-conversation system-message inference), and is expected in
      introspected model maps for custom providers. `spec/custom-providers.allium`
      `ResolvedCustomModel.custom` comment mirrored (set by expand-model,
      never declared in models.edn).
- [x] `parse-documented-deepseek-example-test` (user_models_test.clj) asserts
      every resolved field of the documented DeepSeek example — id, name,
      provider, api, base-url, supports-reasoning, adaptive-thinking,
      supports-images, supports-text, context-window, max-tokens, all four
      cost fields, locality/latency-tier/cost-tier — but NOT the review-14
      origin tag `:custom? true`, the task's central security property (it
      gates built-in classification: env-key fallback and OAuth treatment).
      The general `custom-provider-models-tagged-custom-test` covers
      expand-model tagging, but the doc-parse-lock's stated purpose ("doc↔
      schema drift fails the test in both directions") is the natural home to
      also pin the origin tag on the exact shipped example — a future
      expand-model change that stops tagging custom models (e.g. a
      merge-order regression moving `:custom? true` before the model-def
      merge) would then fail the doc lock too. Fix: add
      `(is (true? (:custom? model)))` to the parse-lock's first testing
      block.
      → Resolved: `(is (true? (:custom? model)))` added to the parse-lock's
      first testing block (with a review-25 comment naming the merge-order
      regression it guards). Namespace green (16 tests / 115 assertions).

## Follow-ups (implementation review 26, 2026-08-08)

- [x] `env:VAR` custom-provider API keys are snapshotted at models.edn parse
      time, not re-read per request: `extract-provider-auth` (user_models.clj)
      resolves `"env:DEEPSEEK_API_KEY"` via `resolve-api-key-spec` once when
      the model registry loads, and the resolved value (or nil) is stored in
      `registry-state :auth` and served per request by
      `provider-auth/provider-api-key` → `session->request-options` → the
      transports' `resolve-api-key` (which only ever sees the
      already-resolved `:api-key options`). The built-in anthropic/openai env
      fallback IS request-time (`request-support/getenv` inside
      `resolve-api-key`), so custom `env:` keys are the odd case. Consequence
      for the task's headline example: a user who exports `DEEPSEEK_API_KEY`
      AFTER psi has already loaded models.edn (psi running, or the var set in
      a different shell than the one that started psi) keeps getting the
      review-3/12 "Missing API key for provider deepseek" error — and that
      error tells them to configure models.edn `:auth {:api-key ...}`, which
      they already did, so it is actively misleading (the var WAS set; the
      registry just snapshotted nil at load). Verified live (2026-08-08):
      `parse-models-config` on the documented `env:DEEPSEEK_API_KEY` auth with
      `getenv` returning a sentinel yields the sentinel in `:auth`, while
      with `getenv` → nil the same config yields nil — the resolution happens
      entirely inside parse, and nothing re-resolves at request time.
      Fix options: (a) resolve `env:` specs at request time — store the raw
      spec (e.g. "env:DEEPSEEK_API_KEY") in the registry auth and have the
      transports' shared `request-support/resolve-api-key` re-resolve through
      `getenv` per request (making custom providers match the built-in env
      fallback's live semantics); or (b) minimally, document in
      doc/custom-providers.md next to "Then export your key" that `env:` keys
      are read when models.edn loads and env changes require a models reload
      (`refresh!`/restart), and consider having the custom-provider
      missing-key error hint "env:VAR was unset at models load time" when the
      configured spec is an `env:` string — so the error stops looking like a
      config mistake. Option (a) is the behavior fix; (b) is the
      doc/UX-only fallback.
      → Resolved (option (a), the behavior fix): the registry now stores the
      RAW `:api-key` spec (literal or "env:VAR") — `extract-provider-auth`
      no longer resolves at parse — and the shared
      `request-support/resolve-api-key` re-resolves `env:` keys through
      `getenv` per request (new `request-support/resolve-key-spec` helper;
      `user_models/resolve-api-key-spec` delegates to it, so env-lookup
      testability lives in one place). Exporting `DEEPSEEK_API_KEY` after psi
      loaded models.edn now works without a reload, matching the built-in env
      fallback's live semantics. The custom-provider missing-key error now
      names the unset variable ("environment variable DEEPSEEK_API_KEY is
      unset — env: keys are re-read per request") when the configured spec is
      an `env:` string, instead of pointing back at the models.edn `:auth`
      block the user already configured. Tests: `request_support_test.clj`
      gains `resolve-key-spec-test` + `resolve-api-key-request-time-env-
      resolution-test` (live re-read, unset-var error naming the variable,
      literal pass-through, built-in fallback preserved);
      `parse-documented-deepseek-example-test` updated to assert the raw spec
      is stored and resolves through the shared request-time lookup; all
      three allium specs model request-time `ResolveApiKey`; docs updated
      (env: keys re-read per request — no reload needed); CHANGELOG `Changed`
      entry added; design.md revision note updated. Full `bb test` green
      (2586 tests / 18697 assertions / 0 failures); clj-kondo + file-lengths
      clean.
- [x] `supports-mid-system-messages?` (components/agent-session/src/psi/
      agent_session/model_capabilities.clj) re-implements built-in
      classification inline — `(and (= :openai (:provider model))
      (= :openai-completions (:api model)) (not (:custom? model)))` — instead
      of reusing the shared `request-support/builtin?` predicate that all
      three provider transports have used since review 14 ("so future fixes
      land once"). The review-25 fix added the `:custom?` origin-tag guard to
      this inline copy, but the origin-tag built-in-classification semantics
      now live in TWO places: `request-support/builtin?` (ai component,
      `(not (:custom? model))` + provider nil/builtin-name) and this
      agent-session branch. The next origin-tag/builtin-classification change
      (new built-in provider keyword, different tag semantics, a
      `:custom?`-adjacent field) can silently drift one of them — the exact
      triplication failure review 14/16 consolidated inside the ai component,
      which never reached this agent-session predicate. Note the api
      constraint must be preserved: the inference is intentionally
      chat-completions-only (codex-routed built-ins like gpt-5.5/
      gpt-5.6-* under OAuth must NOT get the inference from this branch).
      Fix: extract a shared "built-in openai chat-completions" predicate
      (e.g. `(and (request-support/builtin? model :openai)
      (= :openai-completions (:api model)))`) in `psi.ai.providers.
      request-support` (or reuse `builtin?` directly with the api constraint
      at the call site), and have `supports-mid-system-messages?` call it —
      agent-session's model-capabilities already depends on the ai component
      (`psi.ai.model-registry`), so the require is available. Add/extend a
      direct model-capabilities test locking the custom-provider-named-openai
      (`:custom? true`) exclusion through the shared predicate.
      → Resolved: new `request-support/builtin-openai-chat-completions?`
      predicate owns the built-in-openai-chat-completions classification
      (`builtin?` origin-tag gate + api :openai-completions constraint);
      `model_capabilities.clj` `supports-mid-system-messages?` calls it
      instead of the inline copy. The api constraint is preserved (codex-
      routed built-ins, api :openai-codex-responses, never match). Tests:
      `request_support_test.clj` `builtin-openai-chat-completions?-test`
      (built-in shape → true, nil provider → true, custom "openai"
      `:custom? true` → false, custom "deepseek" → false, codex api → false,
      explicit `:custom? false` → true); `model_dispatch_test.clj`
      `mid-system-capability-custom-origin-test` extended to assert the
      exclusion through the shared predicate directly. Behavior-preserving
      (no built-in catalog model carries the tag; the nil-provider case now
      matches the transports' `builtin?` semantics). Full `bb test` green
      (2586 tests / 18697 assertions / 0 failures); clj-kondo + file-lengths
      clean.

## Follow-ups (implementation review 27, 2026-08-08)

- [x] `doc/custom-providers.md` mid-conversation system-message inference
      claim is stale after the review-25/26 built-in gating: "What a provider
      definition contains" says "only OpenAI chat-completions models
      (`:openai`/`:openai-completions`) get the capability inferred from the
      runtime API shape", and the DeepSeek example note repeats it
      ("only `:openai`/`:openai-completions` models get the capability
      inferred") — but `supports-mid-system-messages?` now gates the
      inference on the `:custom?` origin tag via
      `request-support/builtin-openai-chat-completions?` (review 26), so a
      custom models.edn provider named "openai" (api :openai-completions,
      tagged `:custom? true`) does NOT get the inference and must declare
      `:supports-mid-conversation-system-messages` explicitly. The same
      section's "Note on `:custom?`" already states the inference is
      built-in-only — the two paragraphs contradict each other, and the
      review-26 resolution updated the code + design.md but not these doc
      claims. Fix: qualify the inference as built-in-only in both places
      (custom OpenAI-compatible providers must declare the field; a custom
      provider named "openai" is tagged `:custom? true` and does not get it).
      → Resolved: both doc claims now qualify the inference as built-in-only.
      "What a provider definition contains" states the inference applies only
      to built-in OpenAI chat-completions catalog models (not tagged
      `:custom?`), that a custom models.edn provider named "openai" is tagged
      `:custom? true` and does not get it, and that every custom
      OpenAI-compatible provider must declare the field explicitly; the
      DeepSeek example note says the same. Both paragraphs now agree with the
      adjacent "Note on `:custom?`" and with
      `request-support/builtin-openai-chat-completions?`. Parse-lock
      (`psi.ai.user-models-test`, reads the doc's EDN block) green — 16
      tests / 116 assertions.
- [x] CHANGELOG `[Unreleased]` carries the same stale inference claim AND
      has no entry for the review-25/26 behavior change: the review-22
      `Added` entry for `:supports-mid-conversation-system-messages` says
      "(only `:openai`/`:openai-completions` models get the capability
      inferred)" — written before the origin-tag gating. The gating itself is
      a user-visible custom-provider behavior change per AGENTS.md changelog
      policy (a custom models.edn provider literally named "openai" with api
      :openai-completions previously received the inferred mid-conversation
      system-message capability by name; it now requires the explicit field,
      and `:session/inject-mid-system-message` returns
      `:capability-not-supported` otherwise — the same provider-name-
      collision class the task's `:custom?` work closed for env-key fallback
      and OAuth). Fix: correct the `Added` entry's parenthetical to
      "built-in" and add a `Changed` entry documenting the built-in gating
      of the inference.
      → Resolved: the `Added` entry's parenthetical now reads "the capability
      inference is built-in-only — only built-in `:openai`/`:openai-
      completions` catalog models get it inferred from the runtime API
      shape"; a new `[Unreleased]` → `Changed` entry documents the built-in
      gating of the inference (custom provider named "openai", tagged
      `:custom? true`, previously received the inferred capability by name —
      now must declare the field explicitly, else
      `:session/inject-mid-system-message` returns
      `:capability-not-supported`).
- [x] `resolve-key-spec` (request_support.clj) recognizes only the lowercase
      `"env:"` prefix: `"ENV:DEEPSEEK_API_KEY"` or `"Env:..."` (or `" env:..."`)
      falls through to the literal branch and is sent as the API key verbatim
      — a silently bogus key (provider-side 401) instead of the clear
      "environment variable ... is unset" error review 26 added for env:
      specs, and no schema or parse-time warning. The docs and the
      missing-key error suggestion use lowercase `env:` consistently, so this
      is a documentation/UX gap, not a leak (the literal text is not a
      secret). Fix (docs option, in scope): add a one-line note to
      doc/custom-providers.md's `:api-key` bullet ("the `env:` prefix is
      case-sensitive — lowercase `env:` only") and/or the
      `resolve-key-spec` docstring; or handle the prefix case-insensitively
      with a test in request_support_test.clj.
      → Resolved (docs option, in scope): `doc/custom-providers.md` `:api-key`
      bullet now notes the `env:` prefix is case-sensitive — lowercase `env:`
      only, so `ENV:VAR`/`Env:VAR` is sent as a literal key and fails
      provider-side; `request_support.clj` `resolve-key-spec` docstring now
      states the same (only exact lowercase `env:` triggers env lookup; other
      casings fall through to the literal branch, provider-side 401, never an
      env lookup). Chose documentation over case-insensitive handling to keep
      behavior byte-stable (the design AC forbids changing provider
      request-shaping/key-resolution logic in this task); the docs and the
      missing-key error suggestion already emit lowercase `env:` only.
- [x] Pre-existing codex deftest duplication in `openai_test.clj` (flagged
      in review 21's resolution as "outside review-21 scope; flagged here for
      a future dedup", never opened as a follow-up): the seven codex deftests
      `codex-requires-chatgpt-token-test`,
      `codex-reasoning-text-delta-maps-to-thinking-delta-test`,
      `codex-reasoning-map-delta-normalized-to-string-test`,
      `codex-reasoning-output-item-done-emits-thinking-boundary-test`,
      `codex-thinking-level-maps-to-reasoning-effort-test`,
      `codex-tool-call-id-roundtrip-test` and
      `codex-function-call-done-includes-final-arguments-test` are
      byte-identical to copies in `openai_codex_test.clj` (pre-dates this
      task, commit 008b1e094). It is task-relevant now because
      `openai_test.clj` sits at the committed 775-line mark (the exact reason
      review 21 moved new codex tests to `openai_codex_test.clj`), so the
      duplicates consume the remaining file-length headroom and any future
      openai-transport test addition needs the same workaround. Fix: delete
      the seven duplicates from `openai_test.clj` (or make them delegate to
      the codex file) and re-verify `psi.ai.providers.openai-test` +
      `psi.ai.providers.openai-codex-test` + `bb commit-check:file-lengths`.
      → Resolved: the seven byte-identical deftests are deleted from
      `openai_test.clj` (verified byte-identical against
      `openai_codex_test.clj`, the canonical copies remain there; the only
      delta was a trailing blank line in one copy). `openai_test.clj` drops
      775 → 607 lines, well under the 800-line commit gate. `jwt-with-account-id`
      / `stream-body` helpers remain used by surviving tests (no orphans).
      Verification: `psi.ai.providers.openai-test` 9 tests / 65 assertions
      green (was 16/88 — exactly the 7 duplicates removed);
      `psi.ai.providers.openai-codex-test` 9 tests / 30 assertions green;
      `bb commit-check:file-lengths` passes (exit 0); clj-kondo clean on the
      changed test file.

## Follow-ups (implementation review 28, 2026-08-08)

- [x] Committed `.psi/project.edn` at HEAD re-activates the deepseek
      workflow session-profiles → `bb test` is RED again for the documented
      review-18 reason. Review 18 (2026-08-08) found the identical
      regression when post-task human commit b26f84f25 re-enabled the
      deepseek profiles and RESOLVED it by restoring the committed default
      to built-in anthropic catalog profiles (deepseek + openai maps
      commented, explanatory note kept; the delegate-review live test fails
      deterministically everywhere — "unknown model
      deepseek/deepseek-v4-flash", because the live test snapshots the
      committed session profiles against a temp model registry containing
      only `local/test-model`, so the deepseek profiles are unresolvable on
      every machine, user-global models.edn notwithstanding). The SAME
      regression has now re-occurred at HEAD: commit c90ae4043 ("update
      workflows to use deepseek", 2026-08-08 20:04, sitting on top of the
      review-27 address commit c9a783040) re-activated the deepseek map, and
      the current committed tree fails
      `delegate-review-task-implementation-completes-with-nullable-local-model-test`
      (verified deterministic, `.scry-results` EDN: 3 failed assertions,
      `:execution-error {:reason :invalid-session-profile ... :message
      "unknown model deepseek/deepseek-v4-flash"}`; full suite 2578 passed /
      1 failed). This violates the design AC "`bb test` green" on the state
      being closed. Fix per the review-18 decision (option b — revert the
      activation; option (a) "treat as intentional user-local override
      excluded from the AC" was already rejected because the failure is not
      CI-only): revert c90ae4043's `.psi/project.edn` activation so the
      committed default stays on built-in catalog models (deepseek map
      commented, one-line local flip preserved), then re-run the full suite
      on the state being closed and record the result. Consider a
      commit-check (or a lock in the delegate-review live test itself) that
      fails when the committed session profiles reference a non-catalog
      model, so this regression class cannot silently return a third time.
      → Resolved (revert, per the review-18 decision): `.psi/project.edn`
      restored byte-identical to the review-18 committed default
      (ef4db8c0e) — built-in anthropic catalog profiles active, deepseek +
      openai maps commented, the explanatory "keep the committed default on
      catalog models so `bb test` stays green" note kept (the human's
      deepseek preference remains a one-line local flip). c90ae4043's only
      delta was the activation swap (verified: `diff` shows exactly the
      anthropic map commented + deepseek map uncommented), so the restore is
      the exact prior committed state. The delegate-review live test is the
      lock for this regression class — it snapshots the committed session
      profiles against a temp registry containing only `local/test-model`,
      so ANY committed profile referencing a non-catalog model fails it
      deterministically on every machine (how reviews 2/18/28 all caught
      this); it runs in the AC-gated `bb test` suite, so a separate
      commit-check would be redundant with the existing gate and was not
      added (kept the change minimal). Verification (state being closed):
      `delegate-review-task-implementation-completes-with-nullable-local-model-test`
      green again (3 tests / 21 assertions, matching the review-18 state);
      full `bb test` green — 2579 tests / 19383 assertions / 0 failures
      (two prior full-suite runs each hit one documented pre-existing flake
      — `prompt-provider-retry-after-tool-result-does-not-rerun-tool-test`
      then `scheduled-deliver-runs-canonical-prompt-lifecycle-test`; both
      pass in isolation, both files have zero diff across the task range;
      final run clean); extensions suite green (364 passed / 0 failed /
      1566 assertions — "1 unknown" is the pre-existing `:integration`-meta
      skip); clj-kondo clean (0 errors, 0 warnings) on all changed files
      (the 2 dev-http warnings are pre-existing at HEAD in an untouched
      file); `bb commit-check:file-lengths` clean; cljfmt clean.
- [x] `model_capabilities.clj` `supports-mid-system-messages?` docstring
      retains a stale, self-contradictory claim from before the review-25/26
      built-in gating: "OpenAI chat-completions support is also inferred
      from the runtime API shape so custom/runtime-loaded OpenAI chat models
      do not need to carry psi-specific metadata — but only for built-in
      catalog models: the inference is gated on the review-14 `:custom?`
      origin tag ..." The first clause ("custom ... models do not need to
      carry psi-specific metadata") directly contradicts the built-in-only
      gating it documents in the second clause and the actual code
      (`request-support/builtin-openai-chat-completions?`): a custom
      models.edn OpenAI-compatible provider is tagged `:custom? true` and
      does NOT get the inference — it must declare
      `:supports-mid-conversation-system-messages` explicitly. This is the
      same stale-claim class review 27 fixed in doc/custom-providers.md and
      CHANGELOG, but the code docstring was missed. Fix: reword the first
      clause (e.g. "so built-in OpenAI chat-completions models do not need
      to carry psi-specific metadata; custom models.edn providers must
      declare the field explicitly"), keeping the api constraint note
      (codex-routed built-ins must not match).
      → Resolved: `supports-mid-system-messages?` docstring reworded — the
      first clause now reads "built-in OpenAI chat-completions catalog
      models do not need to carry psi-specific metadata" (the
      custom/runtime-loaded claim is gone), the second clause keeps the
      review-14 `:custom?` origin-tag gating description (custom models.edn
      provider named "openai", tagged `:custom? true`, cannot receive the
      built-in-only inference by name and must declare the field
      explicitly), and the api constraint is now explicit: "The inference
      is chat-completions-only: codex-routed built-ins (api
      :openai-codex-responses) never match this branch." Docstring-only
      change (no behavior delta); `psi.agent-session.model-dispatch-test`
      green (13 tests / 161 assertions).
- [x] `user_models/resolve-api-key-spec` is production-dead since review 26
      and its shared-helper docstring claims a delegation that no longer
      happens. `extract-provider-auth` now stores the RAW `:api-key` spec
      (review 26), so nothing in production calls
      `user_models/resolve-api-key-spec` — only
      `user_models_test.clj`'s `resolve-api-key-spec-test` references it
      (verified by repo-wide grep). Meanwhile `request_support.clj`
      `resolve-key-spec`'s docstring still states "The config-parse layer
      (`user_models/resolve-api-key-spec`) delegates here so env-lookup
      testability lives in one place" — stale, since the config-parse layer
      no longer resolves at parse time. Fix (either): (a) delete the dead
      wrapper and point `resolve-api-key-spec-test` at
      `request-support/resolve-key-spec` (the shared helper's own tests
      already cover the same cases), or (b) keep it as a deliberate
      test-facing/public delegation but correct both docstrings to say it is
      retained for API/test stability only, not a parse-time resolution
      path. Avoid leaving a public function whose only callers are tests and
      whose docstrings describe a parse-time contract that no longer exists.
      → Resolved (option (a) — delete the dead wrapper):
      `user_models/resolve-api-key-spec` is deleted from `user_models.clj`
      (production-dead since review 26; the only reference was the test),
      together with its now-unused `psi.ai.providers.request-support` require
      (verified: the namespace's remaining mention is a docstring reference,
      not code). `user_models_test.clj`'s `resolve-api-key-spec-test` is
      renamed `resolve-key-spec-test` and targets
      `request-support/resolve-key-spec` directly (same five testing blocks,
      unchanged coverage; `request_support_test.clj`'s resolve-key-spec-test
      remains the canonical coverage). Both stale docstrings corrected:
      `request_support.clj` `resolve-key-spec`'s docstring no longer claims
      the config-parse layer delegates (it now states the wrapper was deleted
      as production-dead and this shared helper is the single env-resolution
      home); `user_models.clj` `extract-provider-auth`'s docstring
      historical note no longer names the deleted function; the two
      `resolve-api-key-spec`-vs-itself / delegation comments in
      request_support_test.clj and user_models_test.clj updated to describe
      the review-28 deletion. Repo-wide grep confirms no remaining code
      references. Verification: `psi.ai.user-models-test` green (16 tests /
      116 assertions), `psi.ai.providers.request-support-test` green (12
      tests / 77 assertions), clj-kondo clean (0 errors, 0 warnings) on all
      changed files, cljfmt clean.

## Follow-ups (implementation review 29, 2026-08-08)

- [x] `model_selection/catalog-view` `:configured?` no longer reflects key
      resolvability after the review-26 raw-spec storage change.
      `extract-provider-auth` (user_models.clj) now stores the RAW `:api-key`
      spec (literal or "env:VAR") in the registry, and
      `model_selection.clj`'s `catalog-view` reads `(:api-key auth)` directly
      for the resolver-facing `:reference {:configured?}` flag. A custom
      provider whose `env:` var is unset at request time therefore reports
      `:configured? true` in the catalog (model pickers), while every request
      fails with the missing-key error naming the variable. Before review 26
      the parse-time resolution stored nil when the var was unset, so
      `:configured?` was false — the review-26 change silently flipped the
      flag's semantics from "a key will resolve" to "a key spec was declared",
      without updating `catalog-view`, its docstring, or the CHANGELOG.
      Verified live: with `:auth {:api-key "env:PSI_UNSET_TEST_VAR_XYZ"}`
      (var unset), `catalog-view` → `find-candidate :deepseek
      "deepseek-v4-flash"` returns `:configured? true` (registry api-key is
      the raw "env:..." string). The existing
      `catalog-view-unconfigured-provider-test` only covers the no-auth-at-all
      case; no test pins the env:-spec behavior either way. Fix (either):
      (a) resolve the spec in `catalog-view` via
      `request-support/resolve-key-spec` so `:configured?` again reflects
      request-time resolvability (restores pre-review-26 semantics), or
      (b) accept the semantic change (declared config ≠ resolvable key) and
      document it in the `:configured?` reference docstring + CHANGELOG and
      lock it with a test. Avoid leaving the flag silently meaning something
      different than it did before review 26.
      → Resolved (option (a) — restore request-time resolvability):
      `catalog-view` now resolves the configured `:api-key` spec through the
      shared `request-support/resolve-key-spec` before computing
      `:configured?`, so the flag again means "a key will resolve at request
      time": an unset `env:` var reads as not configured (matching the
      per-request missing-key error the transports raise), a set var reads as
      configured, and keyless configs (`:auth-header? false` / custom
      `:headers`) count as configured without a key (unchanged). `catalog-view`
      docstring now documents the request-time-resolvability semantics.
      New `catalog-view-env-api-key-resolvability-test` in
      `model_selection_test.clj` locks all three: unset `env:` var →
      `:configured? false`, set var (redef'd `request-support/getenv`
      sentinel) → `:configured? true`, keyless `:auth-header? false` →
      `:configured? true`. CHANGELOG `Changed` entry for the review-26 env:
      re-read change extended with the catalog `:configured?` implication
      (pre-review-26 semantics restored). No external consumers of
      `catalog-view`/`:configured?` outside model_selection.clj + its test
      (repo grep). Green: `psi.ai.model-selection-test` 13/117 (was 12/113;
      +1 deftest +4 assertions), `psi.ai.providers.request-support-test`
      12/77; clj-kondo clean (0 errors, 0 warnings).
- [x] `runtime/resolve-api-key-in` (and `prompt_request.clj`'s `resolve-api-key`)
      docstrings do not document the review-26 raw-spec contract.
      `provider-auth/provider-api-key` returns the registry's RAW `:api-key`
      spec verbatim for custom providers (literal or "env:VAR"), so
      `resolve-api-key-in` — public, used by the RPC prompt/command paths —
      and `prompt_request/resolve-api-key` can return a raw `"env:VAR"`
      string, not a concrete key; it becomes concrete only when the transport
      re-resolves it per request via `request-support/resolve-key-spec` (the
      `:runtime-opts :api-key` / `:runtime-api-key` session-data flow does
      this). `provider_auth/core.clj`'s `provider-api-key` docstring was
      updated for this in review 26 ("Callers that need a concrete key must
      route through that shared helper"), but the agent-session call sites
      still claim to "resolve the API key" with no note that the value may be
      a raw spec — the same stale-contract class review 28 fixed in
      `request_support.clj`. Fix: extend both docstrings to state the
      raw-spec contract (return value may be `"env:VAR"` for custom
      providers; concrete resolution happens per request in the transports
      via `request-support/resolve-key-spec`; `:runtime-api-key` session data
      stores the raw spec). Docstring-only; no behavior change.
      → Resolved: both docstrings extended with the raw-spec contract —
      `runtime.clj` `resolve-api-key-in` and `prompt_request.clj`
      `resolve-api-key` now state the return value may be a literal key or an
      `"env:VAR"` string for custom providers (registry stores the RAW spec,
      review 26; `:runtime-api-key` session data stores the raw spec too),
      that it becomes concrete only when the transport re-resolves it per
      request via `request-support/resolve-key-spec`, and that callers
      needing a concrete key must route through that shared helper —
      mirroring the `provider_auth/core.clj` `provider-api-key` docstring
      language from review 26. Docstring-only; no behavior change. Green:
      `psi.agent-session.runtime-test` 6/42, `psi.agent-session.prompt-request-test`
      20/59; clj-kondo clean.

## Follow-ups (implementation review 30, 2026-08-08)

- [x] `model_selection/catalog-view` `:configured?` counts ANY custom
      `:headers` as making a provider configured, contradicting the review-29
      request-time-resolvability semantics it just documented: the flag
      computes `(or (nil? auth) (some? (resolve-key-spec (:api-key auth)))
      (seq (:headers auth)) (false? (:auth-header? auth)))`, so a custom
      provider with only INCIDENTAL headers (e.g. `:headers {"X-Client"
      "psi"}`, no `:api-key`, `:auth-header?` default true) reports
      `:configured? true` in the model picker — but `request-support/no-auth?`
      treats incidental headers as NOT keyless (review 5), so every request
      fast-fails with "Missing API key for provider <name>". Verified
      end-to-end through `session->request-options` →
      `provider-request-options` → `no-auth?` → `resolve-api-key` (throws);
      `doc/custom-providers.md` "Local servers and custom headers" states the
      same fast-fail for incidental headers, so the picker flag disagrees
      with the docs' own claim for this exact case, and
      `catalog-view-env-api-key-resolvability-test` (review 29) locks only
      the unset/set-env and `:auth-header? false` cases — not the
      incidental-headers case. Fix: align the headers clause with `no-auth?`
      (a recognized auth header — `x-api-key`/`authorization`,
      case-insensitive — among custom `:headers` with no resolvable key, or
      `:auth-header? false`, or a resolvable key), e.g. reuse
      `request-support/no-auth?` on the auth map (mapping `:auth-header?
      false` → `:no-auth-header`), and add an incidental-headers block to
      `catalog-view-env-api-key-resolvability-test` asserting
      `:configured? false`.
      → Resolved: `catalog-view` `:configured?` now reuses
      `request-support/no-auth?` on the registry auth map (`:auth-header?
      false` → `:no-auth-header`), so keyless counts as configured only when
      the shared predicate would treat the request as keyless — a recognized
      auth header (`x-api-key`/`authorization`, case-insensitive) among
      custom `:headers` with no resolvable key, or `:auth-header? false`;
      incidental custom headers (e.g. `X-Client`) no longer imply
      configured, matching the per-request fast-fail they cause (review 5
      semantics). Docstring updated. Tests: added incidental-headers
      (`:configured? false`) and recognized-auth-header (`:configured? true`)
      blocks to `catalog-view-env-api-key-resolvability-test`. Green:
      `psi.ai.model-selection-test` 13/119 (+2 assertions), clj-kondo clean
      (0 errors, 0 new warnings).
- [x] Empty `env:` variable name is schema-valid and produces a misleading
      blank-var error: `ModelDef`'s `:api-key` is `[:maybe string?]`, so
      `:auth {:api-key "env:"}` parses and is stored raw; per request
      `request-support/resolve-key-spec` does `(getenv (subs "env:" 4))` →
      `(getenv "")` → nil, and `resolve-api-key`'s unset-var branch names the
      empty substring — "Missing API key for provider deepseek: environment
      variable  is unset" (double space, no variable name) — instead of a
      config error. Not covered by `resolve-key-spec-test` /
      `resolve-api-key-request-time-env-resolution-test` (they test
      `"env:DEEPSEEK_API_KEY"` only). Fix (either): reject/blank-normalize a
      non-empty var name after the `env:` prefix (schema or
      `extract-provider-auth`), or handle it in `resolve-key-spec`/the error
      branch (e.g. treat `"env:"` with a blank var name as a config error
      naming the literal spec, never `getenv ""`); add a test locking the
      chosen behavior.
      → Resolved: handled in `request-support` (the shared env-resolution
      home, review 28) so all resolution paths — models.edn, RPC-passed raw
      specs, direct `resolve-api-key` callers — are covered, not just
      models.edn parse time. `resolve-key-spec` now returns nil for an
      `env:` spec with a blank variable name (never `getenv ""` — a set env
      cannot rescue `"env:"`, the spec itself is invalid); `resolve-api-key`'s
      error branch gained a blank-var-name case that throws a config error
      naming the literal spec (`api-key spec "env:" names an empty
      environment variable (use "env:VAR_NAME")`) instead of the misleading
      "environment variable  is unset" (double space, no variable name).
      Tests: `resolve-key-spec-test` locks `"env:"`/`"env: "` → nil with a
      `getenv` guard proving it is never called with `""`;
      `resolve-api-key-request-time-env-resolution-test` locks the
      config-error message and asserts the blank-var unset message is not
      emitted. Green: `psi.ai.providers.request-support-test` 12/84 (+7
      assertions), clj-kondo clean (0 errors, 0 new warnings).

## Follow-ups (implementation review 31, 2026-08-08)

- [x] `catalog-view` `:configured?` docstring (model_selection.clj, review-29/30 wording) and the CHANGELOG `Changed` entry overclaim "request-time key resolvability": for BUILT-IN providers the flag is `(or (nil? auth) ...)` → always `true` — `get-auth` returns nil for built-ins (no registry `:auth` entry), so a built-in Anthropic/OpenAI model reports `:configured? true` in the model picker even when `ANTHROPIC_API_KEY`/`OPENAI_API_KEY` is unset AND no OAuth login exists, while every request fails with "Missing Anthropic API key. Set ANTHROPIC_API_KEY or login via /login anthropic." Verified: `catalog-view-built-ins-test` locks built-ins → `true` with `registry/init! {}` and no env key. The request-time-resolvability semantics are real for custom providers only; the docstring/CHANGELOG never carve out built-ins. Fix (docs option, in scope): state that built-in providers always report configured (psi cannot know env-var/OAuth availability from the registry alone — catalog-view has no oauth ctx), or reflect env resolvability for built-ins (e.g. resolve `ANTHROPIC_API_KEY`/`OPENAI_API_KEY` via `request-support/getenv` when auth is nil and the provider is a built-in name) with a test pinning the unset-env built-in case.
      → Resolved (docs option, in scope): `catalog-view` `:configured?` docstring
      now carves out built-ins — the request-time-resolvability semantics apply
      to CUSTOM providers only: built-in catalog models always report
      `:configured? true` because `get-auth` returns nil for built-ins (no
      registry `:auth` entry) and `catalog-view` has no OAuth context (psi
      cannot know from the registry alone whether `ANTHROPIC_API_KEY`/
      `OPENAI_API_KEY` is set or an OAuth login exists); the per-request
      missing-key error remains the authoritative signal for built-ins. The
      CHANGELOG `Changed` entry for the review-26 env: re-read change is
      extended with the same built-in carve-out ("These `:configured?`
      semantics apply to custom providers only ..."). Docstring/docs-only; no
      behavior change (`catalog-view-built-ins-test` still locks built-ins →
      `:configured? true` with `registry/init! {}` and no env key, now
      documented rather than contradicted). Green: `psi.ai.model-selection-test`
      13/119, `psi.ai.user-models-test` 16/116 (doc parse-lock), clj-kondo +
      cljfmt clean, file-lengths pass.
- [x] DeepSeek temperature-control recommendation in `doc/custom-providers.md` is incomplete: the note says "If you need temperature control, set `:adaptive-thinking false` (or omit it) and rely on the classic extended-thinking shape DeepSeek accepts" — but `request-body` sends `:temperature` only when `(and (not thinking) (not adaptive?))` (providers/anthropic.clj), so with `:adaptive-thinking false` AND `/thinking` on (the classic extended-thinking shape the note points at), `thinking` is `{:type "enabled" :budget_tokens N}` and temperature is STILL omitted (extended thinking is incompatible with temperature on the Anthropic transport). Temperature is sent only with `:adaptive-thinking false` AND thinking OFF — and thinking-off is signaled by OMITTING the `thinking` field, which DeepSeek's endpoint treats as thinking ON (the existing thinking-off caveat), so whether DeepSeek accepts `temperature` alongside its server-side thinking default is exactly the unverified case. A user following the note with `/thinking` on gets no temperature; with `/thinking` off they get temperature only if DeepSeek accepts it with thinking effectively ON. Fix (docs option, in scope): qualify the recommendation — temperature requires BOTH `:adaptive-thinking false` AND thinking off; the classic extended-thinking shape (thinking on) also omits temperature; and on DeepSeek the omission-based thinking-off signal defaults to ON server-side, so temperature+thinking-ON acceptance is unverified (same block as the live smoke test).
      → Resolved: the DeepSeek temperature note now qualifies the
      recommendation — `:temperature` is sent only when BOTH `:adaptive-thinking`
      is off AND thinking is off; the classic extended-thinking shape
      (`:adaptive-thinking false` + `/thinking` on, what the older note
      recommended) ALSO omits `temperature` (extended thinking is incompatible
      with temperature on the Anthropic transport); and on DeepSeek thinking-off
      is signaled by omitting the `thinking` field, which the endpoint treats as
      thinking ON (server default), so temperature+thinking-ON acceptance is
      exactly the unverified case (blocked: no `DEEPSEEK_API_KEY` in env, same
      block as the live smoke test). Docs-only; no code change.
- [x] `:adaptive-thinking true` without `:supports-reasoning true` also silently forfeits temperature — undocumented: `adaptive?` is `(boolean (:adaptive-thinking model))`, independent of `:supports-reasoning`, and the `request-body` temperature gate is `(and (not thinking) (not adaptive?))`; `thinking-param` is nil when `:supports-reasoning` is false, so the misconfigured model sends neither `thinking` nor `output_config.effort` (the documented review-9 no-op) AND never sends `temperature` — the "Adaptive thinking" docs say "set both flags together" but never mention the temperature consequence of the misconfiguration (a user who sets `:adaptive-thinking true` without `:supports-reasoning true` loses temperature control silently, no schema error or warning). Fix (docs option, in scope): extend the review-9 no-op note in the "Adaptive thinking" section (and/or DeepSeek notes) to state the misconfiguration also disables temperature (the adaptive temperature exclusion applies whenever `:adaptive-thinking` is set, even when the thinking param is a no-op).
      → Resolved: the review-9 no-op note in the "Adaptive thinking" section
      now states the misconfiguration also forfeits temperature — the adaptive
      temperature exclusion applies whenever `:adaptive-thinking` is set,
      independent of `:supports-reasoning` (temperature gate
      `(and (not thinking) (not adaptive?))`), so a model with
      `:adaptive-thinking true` and no `:supports-reasoning true` loses
      temperature silently alongside the thinking no-op. Docs-only; no code
      change.

## Follow-ups (implementation review 32, 2026-08-08)

- [x] `doc/custom-providers.md` `:api-key` bullet ("Local servers and custom
      headers") documents the `env:` prefix case-sensitivity (review 27) and
      per-request re-read semantics (reviews 26/29), but never mentions the
      review-30 empty-variable config error — the canonical user-facing env:
      guidance omits the exact behavior that changed: an `:api-key "env:"`
      (blank variable name) is schema-valid and stored raw, resolves to nil
      (never `getenv ""` — a set env cannot rescue the invalid spec), reads as
      NOT configured in the model picker (`catalog-view` `:configured?` via
      `request-support/resolve-key-spec` → nil), and fails every request with
      the config error "api-key spec \"env:\" names an empty environment
      variable (use \"env:VAR_NAME\")" instead of the misleading "environment
      variable  is unset" (blank name). The behavior is in the CHANGELOG and
      `request_support_test` (`resolve-key-spec-test` locks "env:"/"env: " →
      nil with a getenv guard; `resolve-api-key-request-time-env-resolution-test`
      locks the config-error message) but not in the docs where users hit it.
      Fix (docs option, in scope): add a sentence to the `:api-key` bullet —
      a blank variable name after `env:` is a config error naming the literal
      spec, never an environment lookup of the empty string (use
      `"env:VAR_NAME"`); optionally also add a blank-var block to
      `catalog-view-env-api-key-resolvability-test` locking `:configured?`
      false for an `"env:"` spec (currently only covered indirectly via
      request_support_test).
      → Resolved: `doc/custom-providers.md` `:api-key` bullet ("Local servers
      and custom headers") now states a blank variable name after the `env:`
      prefix — `"env:"` or `"env: "` — is a config error naming the literal
      spec ("api-key spec \"env:\" names an empty environment variable (use
      \"env:VAR_NAME\")"), never an environment lookup of the empty string,
      so always use `"env:VAR_NAME"` with a real variable name. Also added
      the optional blank-var block to
      `catalog-view-env-api-key-resolvability-test`
      (`model_selection_test.clj`): a custom provider with `:api-key "env:"`
      reports `:configured?` false (resolve-key-spec → nil; a set env cannot
      rescue the invalid spec), locking the picker behavior directly instead
      of only indirectly via request_support_test. Green:
      `psi.ai.model-selection-test` 13/120 (+1 assertion), clj-kondo + cljfmt
      clean. Docs + test only; no behavior change.

## Follow-ups (implementation review 33, 2026-08-08)

- [x] The docs' reserved-`:custom?`-tag claim is untested: "Note on `:custom?`"
      in doc/custom-providers.md (added review 25) states "the closed
      model-definition schema rejects a user-supplied `:custom?` key with a
      generic 'Invalid models.edn schema' error", but no test locks the
      rejection side of the origin-tag guarantee. `custom-provider-models-
      tagged-custom-test` (user_models_test.clj) locks that expand-model
      TAGS custom models `:custom? true`, and
      `parse-documented-deepseek-example-test` locks the shipped example
      carries the tag, but nothing asserts a user cannot SUPPLY the tag —
      verified manually: a models.edn model map with `:custom? true` (or
      `:custom? false`) fails `parse-models-config` with "Invalid models.edn
      schema" (`:malli.core/extra-key` on the closed ModelDef), which is the
      security property that makes the origin-tag scheme trustworthy (a user
      cannot spoof built-in classification — env-key fallback, OAuth
      headers, mid-system inference — from models.edn). Fix (test only, in
      scope): add a block to `custom-provider-models-tagged-custom-test` (or
      a sibling deftest) parsing a config with a user-supplied `:custom?`
      key (both `true` and `false`) and asserting `:error` matches "Invalid
      models.edn schema" and `:models` is empty — locking the documented
      reserved-tag claim in both directions.
      → Resolved: new `custom-model-cannot-supply-reserved-custom-tag-test`
      deftest (user_models_test.clj, sibling of
      `custom-provider-models-tagged-custom-test`) locks the rejection side
      of the reserved-tag guarantee in both directions — a models.edn model
      map with a user-supplied `:custom? true` (or `:custom? false`) fails
      `parse-models-config` with `:error` matching "Invalid models.edn
      schema" (the closed ModelDef's `:malli.core/extra-key`) and `:models`
      empty, so a user cannot spoof built-in classification (env-key
      fallback, OAuth headers, mid-system inference) from models.edn. Green:
      `psi.ai.user-models-test` 17/120 (was 16/116; +1 deftest +4
      assertions), clj-kondo + cljfmt clean, file-length gate passes (523
      lines < 800). Test only; no behavior change.
- [x] The MiniMax cloud example contradicts the review-21 locality guidance:
      "OpenAI-compatible example: MiniMax" (doc/custom-providers.md) — the
      doc's flagship hosted/cloud custom-provider example
      (`https://api.minimax.chat/v1`) sets `:latency-tier :medium` /
      `:cost-tier :medium` but omits `:locality`, so it falls through to
      `model-defaults` `:locality :local` — exactly the "cloud model with
      defaulted locality" case the review-21 locality guidance (added to
      "What a provider definition contains" and the DeepSeek example notes)
      warns can be selected for (and charged as) a "local" helper. The
      review-21 resolution explicitly scoped this out ("the pre-existing
      MiniMax example has the same latency/cost omission but predates this
      task"), but that scope note predates the guidance now shipping in the
      same doc — a user copying the first example in the doc reproduces the
      documented misconfiguration. Real risk is lower than DeepSeek's was
      (MiniMax's `:medium`/`:medium` tiers already exclude it from the
      strict local-helper constraint set `:latency-tier :low` +
      `:cost-tier #{:zero :low}`), so the primary fix is consistency with
      the documented guidance. Fix (docs option, in scope): add
      `:locality :cloud` to the MiniMax example model map (no parse-lock
      impact — `parse-documented-deepseek-example-test` reads only the
      DeepSeek section).
      → Resolved: `:locality :cloud` added to the MiniMax example model map
      (doc/custom-providers.md) so the doc's flagship hosted example no
      longer falls through to the `:local` default — consistent with the
      review-21 locality guidance ("What a provider definition contains" +
      DeepSeek example notes). No parse-lock impact
      (`parse-documented-deepseek-example-test` reads only the DeepSeek
      section; `psi.ai.user-models-test` 17/120 green). Docs only; no
      behavior change.

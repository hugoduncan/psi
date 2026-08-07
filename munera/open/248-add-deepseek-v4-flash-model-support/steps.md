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

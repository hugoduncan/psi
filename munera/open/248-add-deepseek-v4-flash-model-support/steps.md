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

- [ ] HTTP-400 compatibility retry silently absorbs a DeepSeek 400 on the
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
- [ ] `spec/anthropic-provider.allium` has no rules for the HTTP-400
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
- [ ] `openai/transport.clj` `redact-authorization` does not strip a leading
      `Bearer ` before computing the redacted length suffix, unlike the
      anthropic transport's `redact-authorization` (which strips `^Bearer\s+`
      first): an openai capture of `"Bearer abc…"` records
      `"Bearer ***REDACTED*** (len=N)"` with N counting the whole value
      including the 7-char `Bearer ` prefix, while the anthropic transport
      records N excluding it. The review-11 redaction mirror is therefore
      inconsistent between transports in the length metadata (the secret
      itself is redacted either way). Align the openai redactor with the
      anthropic one (strip the prefix before `count`).
- [ ] `:openai-codex-responses` custom providers still fall back to the global
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
- [ ] `spec/openai-provider.allium` `OpenAIApiKeyResolved` built-in condition
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

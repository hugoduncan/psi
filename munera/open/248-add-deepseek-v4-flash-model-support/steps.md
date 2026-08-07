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
      only by design; needs a `DEEPSEEK_API_KEY`.

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

- [ ] `spec/custom-providers.allium` (the formal spec `user_models.clj`'s ns
      docstring points to) was not updated for this task's behaviour changes:
      `CustomModelDef`/`ResolvedCustomModel` carry no `adaptive_thinking`
      field, and the auth rules do not reflect the provider-scoped api-key
      resolution (custom `:anthropic-messages` providers never fall back to
      `ANTHROPIC_API_KEY`) or the keyless `:no-auth-header`/headers-auth
      behavior introduced by reviews 3/4. AGENTS.md change chain requires
      `update(spec, δ|behaviour)` — add the field (and `ResolvedCustomModel`
      carry-through) plus the provider-scoped key-resolution/keyless rules,
      then run the allium-check workflow.
- [ ] `build-request`'s headers-implies-auth inference is too broad: with a
      blank configured key, ANY custom `:headers` makes `no-auth?` true, so a
      provider with incidental headers (e.g. `X-Client`) and an unset
      `env:DEEPSEEK_API_KEY` silently sends a keyless request (provider-side
      401) instead of fast-failing with the clear "Missing API key for
      provider <name>" error. The OpenAI transport only exempts on explicit
      `:no-auth-header`, so the two transports now disagree. Refine: treat
      custom headers as auth only when `:no-auth-header`/`:auth-header? false`
      is set OR a recognized auth header (`x-api-key`/`authorization`) is
      among them; add a test for the incidental-headers + blank-key case.
- [ ] Reconcile the full-suite verification counts in implementation.md:
      "Final verification pass" records 2550 tests / 19134 assertions while
      "Follow-ups review 4 addressed" records 2551 tests / 18440 assertions
      ("was 2550/19134") — the test count rose but the assertion count fell
      by 694, which is either a transcription error or unstable suite
      counts. Re-run `bb test` and record exact, consistent numbers.

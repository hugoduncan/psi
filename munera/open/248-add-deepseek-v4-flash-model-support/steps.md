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
- [x] Optional manual smoke test before close: configure DeepSeek in a real
      `models.edn`, select `deepseek-v4-flash`, run one live turn to confirm
      DeepSeek accepts the request (x-api-key auth, `/v1/messages` path,
      adaptive `output_config.effort`). Automated tests are request-shaping
      only by design; needs a `DEEPSEEK_API_KEY`. BLOCKED: `DEEPSEEK_API_KEY`
      not set in env (recorded in implementation.md).
      → RESOLVED (live, 2026-08-09 — the env block lifted: `DEEPSEEK_API_KEY`
      is now set in the environment): executed the smoke test through psi's
      own request builder (`anthropic/build-request`, non-streaming) with the
      committed `.psi/models.edn` deepseek config (`:adaptive-thinking true`)
      + `:thinking-level :high` (the committed `:reviewing-implementation`
      profile level) and POSTed to
      `https://api.deepseek.com/anthropic/v1/messages` with the env key.
      Result: **HTTP 200** — body
      `{"model":"deepseek-v4-flash","max_tokens":384000,"messages":[...],"system":"sys","thinking":{"type":"adaptive","display":"summarized"},"output_config":{"effort":"high"}}`
      was accepted; response contained a `thinking` content block (thinking
      ran) + the text reply. Confirms x-api-key auth, `/v1/messages` path,
      adaptive `output_config.effort`, AND that DeepSeek accepts
      `thinking.type "adaptive"` (the review-7 unverified caveat — a 200 with
      a thinking block, not a 400; the 2026-08-07 strict-endpoint
      speculation is superseded for the tested shape). Usage JSON carried
      Anthropic-shaped `cache_read_input_tokens`/`cache_creation_input_tokens`
      (both 0 in the no-cache turn) — the review-2 cache-cost field-name
      assumption is verified live, no adjustment needed. Docs updated:
      `doc/custom-providers.md` DeepSeek notes (adaptive-shape, effort,
      HTTP-400-retry, cache-cost bullets) and the `.psi/project.edn`
      review-39 NOTE now record the live verification; unverified items that
      remain (fast mode, `temperature` with thinking-on default,
      `thinking.type "disabled"` explicit signal, `"medium"`/`"highest"`
      effort values, mid-conversation system messages) were not exercised by
      this single turn and keep their caveats.

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

## Follow-ups (implementation review 34, 2026-08-08)

- [x] The "Anthropic-compatible example" (`proxy-sonnet`, doc/custom-providers.md)
      still has the defaulted-locality problem review 33 fixed for MiniMax:
      the model map (`:base-url "https://example.com/anthropic"`, no
      `:locality`/`:latency-tier`/`:cost-tier`) falls through to
      `model-defaults` `:locality :local` — a hosted (https) Anthropic
      proxy copied from the doc's second example reproduces the exact
      "cloud model with defaulted locality" misconfiguration the review-21
      locality guidance ("What a provider definition contains" + DeepSeek
      example notes) warns about (local-helper eligibility). Review 33
      scoped its fix to MiniMax only, and this example is the direct
      template the DeepSeek section points at ("it configures like any
      other Anthropic-compatible provider"). Unlike MiniMax
      (`api.minimax.chat`, unambiguously cloud), `example.com` is a
      placeholder, so the fix is consistency with the shipped guidance:
      add `:locality :cloud` + explicit `:latency-tier`/`:cost-tier` to the
      example model map (matching the DeepSeek example shape), or add a
      pointer note directing users to set the locality/tier fields per
      "What a provider definition contains". Docs only, in scope; no
      parse-lock impact (`parse-documented-deepseek-example-test` reads
      only the DeepSeek section).
      → Resolved: `:locality :cloud` + explicit `:latency-tier :low` /
      `:cost-tier :low` added to the `proxy-sonnet` model map (matching the
      DeepSeek example shape), plus a pointer note stating custom models
      default to `:locality :local` when omitted (local-helper eligibility),
      that `example.com` is a placeholder, and to pick tier values
      describing the proxy's actual latency/pricing. Docs only; no
      parse-lock impact (`psi.ai.user-models-test` 17/120 green).
- [x] The "Local servers and custom headers" flagship example
      (`{:auth {:api-key "env:LOCAL_LLM_KEY" :auth-header? false
      :headers {"X-Client" "psi"}}}`) configures an api-key that is NEVER
      resolved or sent: with `:auth-header? false`,
      `provider-auth/provider-api-key` skips the registry key (the
      `:auth-header?` gate returns nil), `provider-request-options` sets
      `:no-auth-header`, and all three transports build a keyless request
      (`no-auth?` → true; an explicit runtime-opts `:api-key` is ignored
      too). The example therefore contradicts the section's own text
      ("with `:auth-header? false`, psi does not require an API key and
      sends no `x-api-key`/`Authorization` header") and its "Pick one auth
      mechanism per provider" guidance — a user copying it exports
      `LOCAL_LLM_KEY` expecting it to authenticate while the request is
      silently keyless (and `catalog-view` `:configured?` still reports
      true via the resolvable spec, masking the dead key). Fix (docs
      option, in scope): drop the `:api-key` from the example, or add a
      sentence stating the configured key is never sent when
      `:auth-header? false` (omit it; use it only with the default
      auth-header path or custom `:headers` auth).
      → Resolved: `:api-key "env:LOCAL_LLM_KEY"` dropped from the flagship
      example (now `{:auth {:auth-header? false :headers {"X-Client"
      "psi"}}}`) and a new paragraph states psi never resolves/sends a
      configured `:api-key` with `:auth-header? false` (dead key that still
      reads `:configured? true` while requests are keyless) — use `:api-key`
      only with the default auth-header path or custom `:headers` auth.
      Docs only; no parse-lock impact.

## Follow-ups (implementation review 35, 2026-08-08)

- [x] Stale `:runtime-api-key` is reused across a mid-session provider
      switch, sending the previous provider's key to the new provider's
      endpoint — the cross-provider credential disclosure class this task
      closed for the env-var fallback (reviews 3/10/13) and OAuth
      content-sniffing (review 11), but via session-data. `prompt_request/
      resolve-api-key` gives `(:runtime-api-key session-data)` priority 2,
      ABOVE the current provider's own `provider-auth/provider-api-key`
      (priority 3), and `:runtime-api-key` is stored per-session, unscoped,
      at `turn/handlers.clj` prompt-prepare from the previous turn's
      `:ai-options :api-key` (for a custom provider that is the RAW spec or
      literal, e.g. `"env:MINIMAX_API_KEY"`; for an OAuth built-in it is the
      OAuth token). Nothing clears or scopes it on `:session/set-model` or
      `:session/apply-session-profile` (the handler only assocs `:model` +
      `:thinking-level`), so after `/model` switches from provider A to
      provider B the next turn injects A's key/spec into B's request
      options, the transport treats it as configured (provider-scoped
      `resolve-api-key` only guards the env fallback, not an injected
      `:api-key` option), and A's live key is sent to B's `:base-url`
      (e.g. `https://api.deepseek.com/anthropic/v1/messages`). Verified by
      code trace: `session->request-options` → `resolve-api-key`
      (prompt_request.clj) → `:runtime-api-key` wins over
      `provider-api-key`; `request-support/resolve-api-key` re-resolves the
      stale `env:` spec via `getenv` per request. No test covers the
      model-switch path (`prompt_request_test.clj` tests only first-turn
      resolution). Fix: clear `:runtime-api-key` when the session
      model/provider changes (in the `:session/set-model` and
      `:session/apply-session-profile` handlers), or scope the stored value
      per provider and only reuse it when the provider matches (preserving
      the same-provider OAuth stability intent); add a `prompt_request_test`
      locking that a provider switch never reuses the prior provider's key
      (e.g. session-data `:model {:provider "deepseek" ...}` + stale
      `:runtime-api-key "env:MINIMAX_API_KEY"` resolves the deepseek
      registry auth, not the stale spec).
      → Resolved (provider scoping chosen — the review's second option; it
      is robust against ALL model-change paths, not just the two dispatch
      handlers): `prompt_request/resolve-api-key` now reuses
      `:runtime-api-key` only when its recorded `:runtime-api-key-provider`
      matches the session's current model provider (normalized); an
      unscoped stored key (no recorded provider) is never reused.
      `turn/handlers.clj` prompt-prepare records `:runtime-api-key-provider`
      (the session model's provider at prepare time) alongside
      `:runtime-api-key`. New `provider-switch-never-reuses-stale-runtime-
      api-key-test` (prompt_request_test.clj) locks: cross-provider stale
      key (recorded minimax + deepseek model) resolves the deepseek
      registry auth not the stale spec; unscoped legacy key never reused;
      same-provider stored key still reused (OAuth stability). CHANGELOG
      [Unreleased] → Fixed entry; design.md revision note updated.
- [x] Only the DeepSeek doc example is parse-locked; the other documented
      `models.edn` examples can silently drift. `parse-documented-deepseek-
      example-test` (user_models_test.clj, review 6) guards only the
      DeepSeek section of doc/custom-providers.md. Reviews 33/34 found real
      defects in the other two example model maps (MiniMax locality;
      proxy-sonnet locality/tiers) by MANUAL review, and each fix was
      docs-only with "no parse-lock impact" — meaning the closed
      ModelDef/AuthConfig schemas can silently reject or mis-parse the
      doc's other copy-paste examples with no test catching it (the same
      docs/code drift class review 6 built the DeepSeek parse-lock for).
      Fix (test only): generalize the doc-EDN extraction to parse every
      `models.edn` example block in doc/custom-providers.md (MiniMax,
      proxy-sonnet, DeepSeek, and the "Local servers and custom headers"
      `:auth` snippet where applicable) through `parse-models-config` and
      assert zero errors, so future doc edits cannot break the shipped
      examples.
      → Resolved (test only): `user_models_test.clj` doc extraction
      generalized — `doc-clojure-blocks` parses every ```clojure block in
      doc/custom-providers.md with its nearest '## ' heading;
      `models-edn-example-blocks` collects every full models.edn root map
      (`{:version ... :providers ...}`; MiniMax, proxy-sonnet, DeepSeek);
      `deepseek-example-edn` now selects by model id
      (`deepseek-v4-flash`). New `all-documented-models-edn-examples-parse-
      test` parses EVERY full models.edn block through `parse-models-config`
      asserting zero errors + ≥1 model (≥3 blocks enforced); new
      `local-servers-auth-snippet-parses-test` wraps the doc's exact
      `{:auth {:auth-header? false :headers {"X-Client" "psi"}}}` snippet
      in a minimal provider def and asserts the closed AuthConfig accepts it
      (auth-header? false, headers carried, no api-key). The DeepSeek
      per-field parse-lock test is unchanged (now reads via the generalized
      extraction).
- [x] The documented `speed`-field HTTP-400 non-recovery is untested.
      doc/custom-providers.md claims the `:without-all-betas` compatibility
      retry "leaves `"speed": "fast"` in the retried body" so a 400 caused
      by the unverified `speed` field retries once with the same field and
      hard-fails. The retry tests cover beta stripping
      (`stream-anthropic-retries-without-all-betas-on-400-for-keyless-
      bearer-test`, which uses `:speed-mode :fast` but asserts only that
      the fast-mode beta is gone from the retry HEADERS) — none asserts the
      retried BODY still carries `:speed "fast"` (the documented
      non-recovery). Fix (test only): extend the keyless-bearer retry test
      (or add a sibling) to assert the retried request body retains
      `:speed "fast"` while the beta header is stripped, locking the
      documented "fast-mode 400 is not auto-recoverable" degradation.
      → Resolved (test only): `anthropic_retry_test.clj`'s keyless-bearer
      retry test now parses both request bodies and asserts `:speed "fast"`
      on the FIRST request AND on the RETRIED body (while the fast-mode
      beta header is stripped) — locking the documented non-recovery: the
      `:without-all-betas` transform removes beta headers only, so a
      speed-field 400 retries once with the same field and hard-fails.

## Follow-ups (implementation review 36, 2026-08-08)

- [x] The review-35 provider scoping of the session-stored `:runtime-api-key`
      ignores the `:custom?` origin tag — the exact provider-name collision
      class this task's reviews 14/25/26/27 closed at the transport layer is
      still open in the session-data layer. `session-runtime-api-key`
      (prompt_request.clj) compares ONLY normalized provider ids, and
      `turn/handlers.clj` prompt-prepare records `:runtime-api-key-provider`
      from `(get-in session-data [:model :provider])` — but the persistable
      session model map is `{:provider (name provider) :id ... :reasoning ...}`
      with NO `:custom?` marker, so a custom models.edn provider literally
      named "anthropic" (or "openai") has the SAME session provider string as
      the built-in. A session that ran a built-in Anthropic OAuth turn
      (stored key = the live `sk-ant-oat…` token, provider "anthropic") and
      then `/model`-switches to the custom "anthropic" provider reuses the
      OAuth token (provider match), and the transport — `builtin?` false on
      the `:custom? true` model — sends it as plain `x-api-key` to the custom
      provider's third-party `:base-url`: the cross-provider credential
      disclosure review 35 claimed to close ("a mid-session /model or
      session-profile provider switch can no longer inject the prior
      provider's raw key spec/literal key/OAuth token into the new provider's
      endpoint"), still reachable via the origin collision. The reverse
      direction (custom "anthropic" raw spec stored → switch to built-in
      claude) leaks the custom key into api.anthropic.com the same way.
      Verified: `session->request-options` on
      `{:model {:provider "anthropic" :id "my-custom-model"} :runtime-api-key
      "sk-ant-oat-…" :runtime-api-key-provider "anthropic"}` returns the
      OAuth token. No test covers a custom provider named "anthropic"/
      "openai" in the provider-switch test (fixtures are deepseek/minimax
      only). Fix: include origin in the reuse check — resolve the session's
      runtime model (or record `:runtime-api-key-custom?` at prepare) and
      require BOTH provider and built-in/custom origin to match before
      reusing the stored key; add a `prompt_request_test` block with a
      custom-provider-named-"anthropic" fixture proving the stored built-in
      OAuth token is NOT reused for the custom origin (and vice versa).
      → Resolved: origin scoping implemented — `turn/handlers.clj`
      prompt-prepare now records `:runtime-api-key-custom?` (the session
      model's built-in/custom origin at prepare time, via the new public
      `prompt-request/session-model-custom?` helper which resolves the
      persistable `{:provider :id}` session model through the model
      registry's `:custom?` origin tag), and `session-runtime-api-key`
      requires BOTH the normalized provider AND the origin to match before
      reusing the stored key. `prompt_request_test.clj` gains blocks with a
      custom-provider-named-"anthropic" fixture: (1) stored built-in-origin
      OAuth token + custom "anthropic" model → the custom provider's own
      registry auth resolves, never the token; (2) the discriminating
      keyless case — custom "anthropic" model with NO resolvable auth
      (redef'd `provider-api-key` → nil) + stored built-in-origin token →
      `:api-key` nil (verified to FAIL against the pre-review-36
      provider-only check); (3) reverse direction — built-in claude +
      stored custom-origin raw spec → the built-in's own current resolution
      (OAuth token) wins.
- [x] The same-provider stored `:runtime-api-key` is a self-perpetuating
      fixed point that pins a stale auth spec after a models.edn `:auth`
      change: `session-runtime-api-key` (priority 2) returns the stored RAW
      spec (e.g. `"env:DEEPSEEK_API_KEY"` or a literal) whenever the provider
      matches, and `prompt-prepare-request-handler` re-records that same
      stored value each prepare — so priority 3 (`provider-auth/provider-api-key`,
      the CURRENT registry spec) is never consulted again for that session.
      A user who edits the provider's `:auth {:api-key ...}` in models.edn
      (e.g. rotates the env-var NAME, or switches env: → literal) and runs
      `/reload-models` sees NO effect in the existing session: requests keep
      resolving the old var name (which may now be unset → hard "environment
      variable OLD_VAR is unset" failure), contradicting doc/custom-providers.md's
      "env: keys are re-read on every request … no reload needed" and the
      `/reload-models` reload contract. The review-35 same-provider test
      actually LOCKS IN the wrong precedence for custom providers: the
      "minimax-runtime-key" stored literal wins over the registry's current
      "minimax-registry-key". Fix: reuse the stored key only when it still
      equals what the current resolution would return for that provider
      (OAuth stability is preserved — `provider-api-key` re-resolves the same
      token from the OAuth store; the custom-provider raw spec only differs
      from the registry when the config changed), or restrict the stored-key
      flow to OAuth/built-in credentials; add a `prompt_request_test` locking
      that a registry `:auth` change (re-init the registry with a different
      spec between turns) wins over the stale stored key, and update the
      review-35 same-provider block to use an OAuth-shaped fixture so the
      OAuth-stability intent stays covered under the corrected semantics.
      → Resolved: staleness fix implemented — `resolve-api-key` now computes
      the current `provider-auth/provider-api-key` resolution and reuses the
      stored key only when it is NOT contradicted by it: a different fresh
      resolution (models.edn `:auth` change, OAuth refresh) wins over the
      stale stored spec; a nil current resolution (e.g. an RPC/extension-
      threaded key that lives only in runtime-opts / session-data, not in
      provider-auth — the tested `rpc-openai-codex-prompt-emits-tool-events-
      with-final-args-test` continuation flow) lets the stored key keep
      same-provider same-origin turns working. New
      `registry-auth-change-wins-over-stale-stored-key-test` locks the
      precedence: stored `env:DEEPSEEK_OLD_VAR` reused while the registry
      holds it, then re-init with `env:DEEPSEEK_NEW_VAR` → the new spec wins
      (verified to FAIL against the pre-review-36 unconditional reuse). The
      review-35 same-provider block now uses an OAuth-shaped fixture
      (redef'd `provider-api-key` returning a token equal to the stored key)
      so the OAuth-stability intent is covered under the corrected
      semantics. CHANGELOG `Fixed` entry + design.md revision note extended
      with both review-36 refinements.

## Follow-ups (implementation review 37, 2026-08-08)

- [x] CHANGELOG `[Unreleased]` → `Changed` redaction entry still claims the
      case-insensitive capture redaction applies "on both transports"
      (line 30: "Provider request captures now redact auth headers
      case-insensitively on both transports (`x-api-key`, mixed-case
      `Authorization`, `chatgpt-account-id`)"), but the shared
      `request-support/redact-headers` is wired into both transport
      redaction entry points — `anthropic.clj`'s `redact-request-headers`
      AND `openai/transport.clj`'s `redact-request-headers` — and the
      `:openai-codex-responses` path captures through
      `transport/capture-request!`, so codex captures are redacted too
      (locked by `codex-chatgpt-account-id-capture-masked-test` and
      `codex-request-and-reply-capture-callbacks-test` in
      `openai_codex_test.clj`). Review 13's "name all three transports"
      update fixed the sibling provider-scoped key-resolution entry but
      missed this review-12 bullet, so a user reading the CHANGELOG would
      conclude `:openai-codex-responses` captures are NOT redacted — false.
      Fix: "on both transports" → "on all three transports" (or name
      `:anthropic-messages`, `:openai-completions`, `:openai-codex-responses`),
      matching the sibling entry.
      → Resolved: CHANGELOG `[Unreleased]` → `Changed` redaction entry now
      reads "on all three transports" and names them
      (`:anthropic-messages`, `:openai-completions`, `:openai-codex-responses`),
      matching the sibling provider-scoped key-resolution entry. Verified the
      codex path captures through `transport/capture-request!`
      (codex_responses.clj), whose `redact-request-headers` delegates to the
      shared `request-support/redact-headers` — the `:openai-codex-responses`
      captures are redacted, and the CHANGELOG no longer misleads.

## Follow-ups (implementation review 38, 2026-08-09)

- [x] `.psi/project.edn` deepseek workflow session-profile activation is back
      at HEAD — the FOURTH recurrence of the regression reviews 2/18/28
      caught and reverted. Commit d1b28eb93 ("update workflows to use
      deepseek", 2026-08-09) re-activated the deepseek map (anthropic map
      commented, deepseek map uncommented — the only project.edn delta vs
      the review-37 state 67ec93bcb, verified by diff) and added a committed
      `.psi/models.edn`. Verified 2026-08-09: the delegate-review live test
      fails deterministically on HEAD (18 passed / 3 failed) — all seven
      workflow profiles are invalid (`:reason :unknown-model`, "unknown
      model deepseek/deepseek-v4-flash"), `:reviewing-implementation`
      fails with `:invalid-session-profile`, so the design AC "`bb test`
      green" is violated on the state being closed. The new committed
      `.psi/models.edn` does NOT fix it: the live test inits the model
      registry with a temp file containing only `local/test-model`
      (`model-registry/init! {:user-models-path models-path}`) and never
      triggers `:model-registry/reload` / `load-project-models!`, so the
      deepseek provider is unresolvable in that test regardless of the
      committed project models.edn. Also: the review-28 resolution
      explicitly declined a durable pre-commit guard ("the delegate-review
      live test IS the lock ... a separate commit-check would be redundant
      with the existing gate and was not added"), and this recurrence
      proves that decision wrong — the lock fires only AFTER the activation
      is committed (b26f84f25, c90ae4043, d1b28eb93), and the file's own
      comment ("requires ... a user-global models.edn — not committed") is
      now stale since `.psi/models.edn` IS committed. Fix options: (a)
      revert `.psi/project.edn` to the catalog-model committed default
      (reviews 18/28 choice; the human's deepseek preference stays a
      one-line local flip, and the stale "not committed" comment should be
      updated to match the now-committed models.edn); (b) keep the deepseek
      activation and instead make the delegate-review live test resolve the
      deepseek provider (e.g. also load the committed project
      `.psi/models.edn` in its temp-registry init) — this is now viable
      because `.psi/models.edn` is committed, and it changes the
      review-18/28 premise ("unresolvable everywhere") that drove the
      revert; (c) add a durable pre-commit guard (bb commit-check or a
      test) that fails when committed session profiles reference a model not
      resolvable from committed model sources (catalog + committed
      `.psi/models.edn`) so this regression class cannot return a fifth
      time. Whichever option, re-run `bb test` on the state being closed
      and record the result.
      → Resolved (option b — keep the activation, fix the lock; decision
      2026-08-09): the deepseek profiles remain the committed default — the
      human re-activated them three times (b26f84f25, c90ae4043, d1b28eb93),
      so reverting again (option a) would invite a fifth recurrence. Option
      (b) is viable exactly because d1b28eb93 committed `.psi/models.edn`:
      the delegate-review live test now mirrors the production bootstrap
      (app-runtime/psi-tool/dispatch-effects all load `<cwd>/.psi/models.edn`)
      and passes `:project-models-path` pointing at the committed file in
      its temp-registry init — the deepseek profiles resolve against
      committed model sources, so the test is green with the activation in
      place, and the test becomes a durable lock for the regression class (a
      committed profile referencing a model absent from committed model
      sources fails deterministically at test time, not after commit — the
      review-28 "lock fires only after commit" gap). The stale
      "user-global models.edn — not committed" comment in `.psi/project.edn`
      was rewritten to match the now-committed models.edn (only the runtime
      DEEPSEEK_API_KEY env var is user-local). Verification: delegate-review
      live test green (3 tests / 21 assertions, was 18 passed / 3 failed);
      full `bb test` green — 2586 tests / 19428 assertions / 0 failures;
      extensions suite green (364 passed / 0 failed / 1566 assertions, "1
      unknown" is the pre-existing `:integration`-meta skip); clj-kondo
      clean (0 errors, 0 warnings) on the changed test files.
- [x] Committed `.psi/models.edn` deepseek model map omits the locality/tier
      fields the task's own documented example mandates (review 21): the
      committed `deepseek-v4-flash` map (`.psi/models.edn`, added
      d1b28eb93) has no `:locality`/`:latency-tier`/`:cost-tier`, so
      `expand-model` `model-defaults` apply — `:locality :local` /
      `:latency-tier :low` / `:cost-tier :zero` — the exact "cloud model
      with defaulted locality" misconfiguration reviews 21/33/34 fixed in
      doc/custom-providers.md (the doc's DeepSeek example sets
      `:locality :cloud` / `:latency-tier :low` / `:cost-tier :low`
      explicitly, and its note explains that a cloud model with defaulted
      locality can be selected for local-helper duty — context-manager
      requires `:latency-tier :low` + `:cost-tier #{:zero :low}` with a
      strong `:locality :local` preference — and charged as a "local"
      helper, receiving conversation excerpts on the local-only path).
      The committed file is covered by no test: the doc parse-locks
      (`parse-documented-deepseek-example-test`,
      `all-documented-models-edn-examples-parse-test`) read
      doc/custom-providers.md only, so the committed file can silently
      drift from the shipped example. Fix: align the committed
      `.psi/models.edn` deepseek model map with the documented example (add
      the three fields), and optionally add the committed file to the
      parse-lock coverage (or a dedicated test) so it cannot drift again.
      → Resolved: the committed `.psi/models.edn` deepseek model map now
      carries `:locality :cloud` / `:latency-tier :low` / `:cost-tier :low`
      exactly as the documented example mandates — no more fall-through to
      `:locality :local` (the review-21/33/34 cloud-with-defaulted-locality
      misconfiguration). Parse-lock coverage added:
      `committed-project-models-edn-matches-documented-deepseek-example-test`
      in `user_models_test.clj` reads the committed file from the repo root
      (walk-up helper) and asserts (a) it is schema-valid, (b) its deepseek
      model carries the three locality/tier fields, and (c) the resolved
      model EQUALS the documented example's resolved deepseek model
      (full-map equality — committed-file ↔ doc drift fails in both
      directions, the same lock class as the review-6/9 doc parse-locks).
      `psi.ai.user-models-test` green (20 tests / 138 assertions).

## Follow-ups (implementation review 39, 2026-08-09)

- [x] doc/custom-providers.md "Switch to the configured model" section lists
      only the MiniMax (`/model minimax MiniMax-M1`) and Anthropic-compatible-
      proxy (`/model my-anthropic-proxy proxy-sonnet`) in-session selection
      commands — the new DeepSeek section (added by this task) is a full
      first-class copy-paste example the same shape as the other two, but a
      user who configures DeepSeek per that section finds no selection
      command for it anywhere in the doc's selection section (the natural
      lookup point), which ends with "or, for the Anthropic-compatible
      example" naming only the placeholder proxy. Docs-only fix: add a
      DeepSeek selection line (`/model deepseek deepseek-v4-flash`) alongside
      the minimax and proxy-sonnet examples (or a third "or, for the
      DeepSeek example" variant) so every documented example has its
      in-session selection command.
      → Resolved: `doc/custom-providers.md` "Switch to the configured model"
      section now lists a third variant — "or, for the DeepSeek example:
      `/model deepseek deepseek-v4-flash`" — so every documented example
      (MiniMax, Anthropic-compatible proxy, DeepSeek) has its in-session
      selection command at the natural lookup point. Doc-only change; the
      DeepSeek model parse-lock (`deepseek-example-edn`) is unaffected (the
      new block is ```text, not ```clojure).
- [x] delegate-review live test's review-38 "durable lock" is CWD-dependent —
      workflow_delegate_review_step_live_test.clj resolves the committed
      project models path via `(str (System/getProperty "user.dir")
      "/.psi/models.edn")`, and the session-profiles read (shared-config) is
      likewise `<cwd>/.psi/project.edn`; from a component-local cwd both
      silently miss (`load-models-file` returns an empty result for a missing
      file, shared-config returns nil profiles), so the test runs GREEN
      without exercising the deepseek lock — the durable lock review 38
      claimed (a committed profile referencing a model absent from committed
      sources fails at test time) silently vanishes instead of failing loud.
      user_models_test.clj's committed-file lock already solved this exact
      problem with a `repo-root` walk-up helper ("Tests run from the repo
      root via bb, but this also tolerates a component-local cwd") that also
      asserts the file exists with a clear failure. Fix: resolve the
      committed `.psi/models.edn` (and assert `.psi/project.edn` exists, or
      fail loud on its absence) the same way, so the lock cannot degrade
      silently.
      → Resolved: `workflow_delegate_review_step_live_test.clj` gains
      `repo-root` (walk-up until `doc/custom-providers.md` exists, mirroring
      user_models_test.clj) and `committed-project-models-path` (resolves
      `.psi/models.edn` from the repo root, throws a clear error if the
      committed `.psi/project.edn` or `.psi/models.edn` is absent — fail
      loud, never silent). The live test binds `project-models-path` from
      the helper instead of `user.dir`. Verified: test green from the repo
      root (3 tests / 21 assertions) and the walk-up helper resolves the
      committed files from a component-local cwd (`user.dir` =
      components/agent-session → repo root, both files found).
- [x] the committed deepseek default activates the UNVERIFIED adaptive wire
      shape repo-wide — `.psi/project.edn`'s seven workflow profiles plus the
      committed `.psi/models.edn` (`:adaptive-thinking true`) send
      `thinking.type "adaptive"` + `output_config.effort` on every delegated
      workflow turn, but DeepSeek's Thinking Mode guide documents only
      `type: "enabled"/"disabled"` and "adaptive" appears nowhere in its
      Anthropic API docs (doc/custom-providers.md's own DeepSeek notes say
      the value is unverified and a strict endpoint may 400); on such a 400
      the streaming path silently retries `:without-thinking` (effort
      dropped, thinking ON server-default) and the non-streaming path
      hard-fails — while the `.psi/project.edn` deepseek comment documents
      only the env-var requirement and says nothing about the unverified
      shape or the silent degradation. Fix (docs/decision): add a note to the
      `.psi/project.edn` deepseek comment stating the adaptive shape is
      unverified until a live DEEPSEEK_API_KEY turn (the review-1 smoke test)
      confirms it, that a 400 silently degrades to thinking-ON at default
      effort on the streaming path and hard-fails on the non-streaming path,
      and the documented fallback is `:adaptive-thinking false` (classic
      `type: "enabled"`, a documented honored value); note the committed-file
      ↔ doc-example equality parse-lock
      (`committed-project-models-edn-matches-documented-deepseek-example-test`)
      means the committed `.psi/models.edn` and the doc example must move
      together if the fallback is chosen.
      → Resolved: `.psi/project.edn` deepseek comment now carries a NOTE
      (review 39) stating the adaptive wire shape is unverified until a live
      DEEPSEEK_API_KEY turn (the review-1 smoke test) confirms it, that a
      strict endpoint's 400 silently retries `:without-thinking` on the
      streaming path (effort dropped, thinking ON server-default) and
      hard-fails on the non-streaming path, that the documented fallback is
      `:adaptive-thinking false` (classic `type: "enabled"`, a documented
      honored value), and that the committed-file ↔ doc-example equality
      parse-lock
      (`committed-project-models-edn-matches-documented-deepseek-example-test`)
      means `.psi/models.edn` and the doc example must move together if the
      fallback is chosen. Comment-only change; `.psi/project.edn` still
      parses (7 profiles, deepseek default) and the delegate-review live
      test (which snapshots these profiles) stays green.

## Follow-ups (implementation review 40, 2026-08-09)

- [x] `bb test` is RED at HEAD — `workflow_definitions_test/review-step-test`
      fails (verified 2026-08-09: 14 tests / 1 failed), so the design AC
      "`bb test` green" is violated on the state being closed. The external
      concurrent commit 5e5e5b1f0 "update review skills" (in this task's
      commit range 3c286a46e..HEAD) added `code-shaper` + `test-shaper` to
      the review-step follow-up step's skills (`.psi/workflows/review-step.edn`
      + `.psi/workflows/review-follow-up-steps.md`) but did not update the
      test's `actor-skills` expectation: `workflow_definitions_test.clj`
      (~line 423) asserts `(= actor-skills (:skills follow-up-step))` with
      the 3-skill vector, so the committed state fails. Review 39 documented
      the failure ("caused ENTIRELY by the external commit … not touched
      here") but scoped it out — it remains the only red test in the suite
      and blocks the AC at closure. Fix: update the test expectation —
      the follow-up step now carries 5 skills (`code-shaper` +
      `test-shaper` added), keep the review step's 3-skill assertion —
      then re-run `bb test` (full suite, and the focused namespace) and
      record the result on the state being closed.
      → Resolved: `workflow_definitions_test.clj` `review-step-test` now
      asserts the review step's 3-skill vector unchanged and the follow-up
      step's 5-skill vector (`(conj actor-skills "code-shaper"
      "test-shaper")`, matching the external commit's ordering). Focused
      namespace green (15 tests / 276 assertions, 0 failures). Full `bb test`
      green on the state being closed: 2586 tests / 19431 assertions /
      0 failures (recorded in implementation.md; assertion count varies
      run-to-run per the documented review-5 flake analysis).
- [x] The delegate-review live test's claimed CWD-independence (review 39)
      is incomplete — verified 2026-08-09: with `user.dir` =
      components/agent-session the test FAILS with "Error: Unknown workflow
      'review-task-implementation'. Use action=list to see available
      workflows." `workflow-test-support/workflow-extensions-cwd` (=
      `(System/getProperty "user.dir")`) drives BOTH the session worktree
      (`create-tui-context+session` → the run's session-profile snapshot
      reads `<cwd>/.psi/project.edn` via shared-config, a strict cwd path
      with no walk-up) AND `load-all-workflow-definitions!` (`<cwd>/.psi/
      workflows`); only `committed-project-models-path` was made
      repo-root-based (review 39 verified the walk-up helper, not the test
      itself). So from a component-local cwd the test neither exercises the
      deepseek durable lock nor fails loud about it — it fails with an
      unrelated "Unknown workflow" error (the review-39 "runs GREEN without
      exercising the deepseek lock" characterization is inaccurate; it fails
      RED for the wrong reason). Fix: resolve the workflow-definitions load
      path AND the session worktree (or assert the run's session-profile
      snapshot actually contains the deepseek `:reviewing-implementation`
      profile so nil profiles fail loud) from the same repo-root walk-up,
      then verify the live test from both the repo root and a
      component-local cwd.
      → Resolved (both options): `workflow-test-support` gains a public
      `repo-root` walk-up helper and `workflow-extensions-cwd` is now
      `(str (repo-root))` (repo root from any cwd — identical to user.dir in
      normal bb runs from the repo root). Fixed a latent dead-opt bug in
      `create-tui-context+session`: it passed a top-level `:worktree-path`
      opt that `create-context*` ignores (the session worktree came from
      `resolved-cwd` = user.dir); it now passes `:cwd workflow-extensions-cwd`
      (the opt `create-context*` actually destructures → session-defaults
      `:worktree-path`), so the session worktree AND `load-all-workflow-
      definitions!` AND the delegate's `runtime-state/loaded-definitions`
      all resolve from the repo root. The live test additionally asserts the
      run's session-profile snapshot contains the deepseek
      `:reviewing-implementation` profile (`:valid? true`, resolved model
      deepseek/deepseek-v4-flash) — nil profiles fail loud instead of
      running with an unrelated error. The live test's private `repo-root`
      helper now delegates to the shared one. Verified BOTH ways:
      `bb clojure:test:scry --namespace psi.agent-session.workflow-delegate-
      review-step-live-test` green from the repo root (3 tests / 24
      assertions) and green with `user.dir` = components/agent-session
      (java -Duser.dir=<abs component path>, absolute classpath — 3 tests /
      24 assertions; the walk-up helper + worktree fix make the two runs
      equivalent). `workflow-async-path-test` (6/29) and
      `workflow-tui-repro-test` (2/11), the other `create-tui-context+session`
      users, green from the repo root; clj-kondo clean (0 errors, 0
      warnings).

## Follow-ups (implementation review 41, 2026-08-09)

- [x] `doc/custom-providers.md` DeepSeek notes are internally inconsistent
      about the live verification block: the temperature bullet (line ~390)
      still says the temperature-with-thinking-ON acceptance case is
      "(blocked: no `DEEPSEEK_API_KEY` in env, same block as the live smoke
      test)" and the fast-mode bullet (line ~497) still says fast mode is
      "Not verified against a live turn — blocked on the same missing
      `DEEPSEEK_API_KEY` as the optional live smoke test" — but review 40
      RESOLVED that block (DEEPSEEK_API_KEY now set in env; a live turn was
      made 2026-08-09), and the same section's adaptive-shape bullet (line
      ~404, "now unblocked") and cache-cost bullet ("verified live
      2026-08-09") were updated to record the live verification. The block
      was lifted, so these two bullets' stated reason for being unverified
      is false: temperature-with-thinking-ON remains unverified because the
      smoke test did not exercise it (it used the adaptive shape at effort
      high, and psi never sends temperature for adaptive-thinking models
      anyway), and fast mode because it was not exercised — not because the
      env var is missing. Fix (doc-only): reword both bullets to say the
      case was "not exercised in the review-1 live smoke test (2026-08-09,
      which covered the adaptive thinking shape at effort high + cache field
      names)" instead of "blocked: no `DEEPSEEK_API_KEY` in env" / "blocked
      on the same missing `DEEPSEEK_API_KEY` as the optional live smoke
      test"; the caveats themselves (assume unverified / assume unsupported
      until verified) stay.
      → Resolved: both bullets reworded. Temperature bullet: the
      temperature-with-thinking-ON case is now "the unverified case (not
      exercised in the review-1 live smoke test 2026-08-09, which covered
      the adaptive thinking shape at effort high + cache field names; the
      smoke test did not exercise temperature — psi never sends it for
      adaptive-thinking models anyway)" — the "blocked: no DEEPSEEK_API_KEY
      in env, same block as the live smoke test" phrasing is gone; the
      "verify against a live turn" caveat stays. Fast-mode bullet: "Not
      exercised in the review-1 live smoke test (2026-08-09, which covered
      the adaptive thinking shape at effort high + cache field names; fast
      mode was not tested)" — the "blocked on the same missing
      DEEPSEEK_API_KEY as the optional live smoke test" phrasing is gone;
      "assume fast mode is unsupported on DeepSeek until verified" stays.
      Doc-only; no parse-lock impact (the ```clojure EDN block is
      untouched) — `psi.ai.user-models-test` green (20 tests / 138
      assertions).
- [x] `workflow_test_support.clj`'s public `repo-root` walk-up helper
      (added review 40, `(loop [dir (.getCanonicalFile (java.io.File. "."))]
      ... doc/custom-providers.md ...)`) duplicates `user_models_test.clj`'s
      private `repo-root` helper — the "established pattern" review 39
      explicitly referenced when fixing the delegate-review live test's
      CWD-dependence. Two copies of the same walk-up now exist in two
      components (components/ai/test vs components/agent-session/test);
      review 40's resolution created the second copy instead of extracting a
      shared helper, and only the live test's private `repo-root` delegates
      to the shared one. Fix (either): extract `repo-root` to a shared
      test-support namespace on the unit test classpath (e.g. a new
      `psi.test-support` helper reachable from both components; bases/main/
      test is already on the unit classpath) and have both test files use
      it; or, if cross-component test-code sharing is intentionally not
      done, document that decision in workflow-test-support's docstring so a
      future reader does not add a third copy.
      → Resolved (option a — shared helper; cross-component test-code
      sharing IS already established via bases/main/test/psi/test_support/,
      e.g. `psi.test-support.workflow-test-fixtures` used by both
      agent-session and workflow-runtime tests, so the documented
      "intentionally not done" option was not applicable): new
      `bases/main/test/psi/test_support/repo_root.clj`
      (`psi.test-support.repo-root`) owns the single `repo-root` walk-up;
      `workflow_test_support.clj` and `user_models_test.clj` both require it
      (their local copies deleted — `workflow_test_support.clj`'s docstring
      on `workflow-extensions-cwd` now documents the shared helper), and
      `workflow_delegate_review_step_live_test.clj`'s private `repo-root`
      delegates to the shared namespace directly. One place for future
      fixes; no third copy can be added without review. Verified:
      `psi.ai.user-models-test` green (20/138),
      `psi.agent-session.workflow-delegate-review-step-live-test` green
      (3 tests / 24 assertions) from BOTH the repo root and a
      component-local cwd (`user.dir` = components/agent-session, absolute
      classpath — the walk-up resolves the committed files either way),
      `workflow-async-path-test` (6/29) and `workflow-tui-repro-test`
      (2/11) green; clj-kondo clean (0 errors, 0 warnings) on all changed
      files; `bb commit-check:file-lengths` passes (exit 0).

## Follow-ups (implementation review 42, 2026-08-09)

- [x] `provider-auth/provider-api-key` and `provider-auth/provider-request-options`
      resolve registry auth purely by provider NAME (`model-registry/get-auth`),
      and `prompt_request/session->request-options` + `resolve-api-key` consume
      them without consulting the session model's `:custom?` origin tag (review
      14) — so the provider-name-collision class reviews 14/25/26/27/36 closed
      at the transport layer (`builtin?`/`builtin-anthropic?` on the resolved
      model map), capability inference (`builtin-openai-chat-completions?`) and
      session-data (`session-runtime-api-key` origin gate) is STILL OPEN at the
      session request-options layer: when a custom models.edn provider is
      literally named "anthropic"/"openai", `parse-providers` keys the registry
      `:auth` entry by that provider name, and a session running the BUILT-IN
      same-named model inherits the custom provider's auth config. Verified
      end-to-end (models.edn with an "anthropic" provider carrying `:headers
      {"x-api-key" "THIRD-PARTY-KEY"}` → a built-in `claude-opus-4-8` session's
      `session->request-options` carries the custom headers and
      `request-support/no-auth?` is TRUE → the transport resolves no key (the
      built-in's own `ANTHROPIC_API_KEY`/OAuth is never resolved) and merges the
      custom `x-api-key` onto the wire to api.anthropic.com; variant with
      `:api-key "env:MY_THIRD_PARTY_KEY"` → the built-in's request options carry
      the custom provider's spec and the transport resolves/sends that
      third-party key to api.anthropic.com, or fails with the custom provider's
      "environment variable MY_THIRD_PARTY_KEY is unset" error instead of
      falling back to `ANTHROPIC_API_KEY`). No test covers this layer
      (`built-in-provider-no-auth-injection-test` only covers a clean registry
      with no `:anthropic` auth entry). Fix: gate registry-auth options/key
      resolution on the session model's `:custom?` origin — apply
      `provider-request-options`/`provider-api-key` only for custom models (e.g.
      thread the resolved model's `:custom?` into provider-auth, or branch in
      `session->request-options`/`resolve-api-key` on `session-model-custom?` so
      built-in models resolve only env/OAuth) — plus regression tests for both
      the headers/`:no-auth-header` variant and the api-key variant.
      → Resolved (thread-the-origin option): `provider-auth/provider-api-key` +
      `provider-request-options` now take the resolved model's `:custom?` origin
      tag (2-arity/1-arity default nil = built-in): registry auth is consulted
      only when `custom?` is true, and OAuth only when `custom?` is false — so a
      built-in same-named model resolves only env/OAuth (never the custom
      provider's registry headers/`:no-auth-header`/api-key spec), and a custom
      provider named "anthropic"/"openai" never receives the built-in same-named
      OAuth credential (the reverse direction of the same class). Callers
      updated: `prompt_request/resolve-api-key` + `session->request-options`
      pass `(session-model-custom? session-data)`; `runtime/resolve-api-key-in`
      passes `(:custom? ai-model)`. Regression tests added: core_test.clj
      (built-in + OAuth → OAuth, never registry; built-in + no OAuth → nil;
      custom → registry; custom never receives same-named OAuth credential;
      provider-request-options built-in → nil / custom → options),
      runtime_test.clj (built-in anthropic with a custom "anthropic" registry
      entry + OAuth credential → OAuth wins; custom minimax with a same-named
      OAuth credential → registry auth wins, never the OAuth credential; custom
      fixtures tagged `:custom? true` per the review-14 expand-model shape),
      prompt_request_test.clj
      `built-in-session-never-inherits-custom-same-named-provider-auth-test`
      (both variants: `:headers {"x-api-key" ...}` custom "anthropic" provider →
      built-in claude session carries no headers/`:no-auth-header`/api-key;
      `:api-key "env:MY_THIRD_PARTY_KEY"` variant → built-in carries no custom
      spec; positive control: the custom same-named model still gets its own
      registry headers). `spec/custom-providers.allium`
      `InjectCustomProviderAuth` now `requires: model.custom` (the origin gate;
      built-in same-named models never receive registry auth injection).
      CHANGELOG `[Unreleased]` → `Fixed` entry added; design.md revision note
      updated. Full `bb test` green (2587 tests / 19445 assertions / 0 failures
      — assertion count varies run-to-run per the review-5 flake analysis; one
      run hit the documented timing-sensitive retry-loop flake
      `prompt-execution-result-retryable-error-enters-retrying-and-schedules-
      retry-test`, 53 vs 2 attempts, passes in isolation — pre-existing,
      unrelated to this change); clj-kondo clean (0 errors, 0 warnings);
      cljfmt clean; file-lengths pass.
- [x] `prompt_request/session-model-custom?` docstring misstates the persistable
      session model shape: it says the map "carries only `{:provider (name
      provider) :id :reasoning}`" — but the canonical persistable shape
      (`model-registry/persistable-model`, used by `/model`/RPC/TUI selection)
      is `{:provider (name provider) :id model-id :reasoning bool}`: `:id` holds
      the model-id string and `:reasoning` is a separate boolean key, not the
      `:id` value. Fix the docstring so a reader does not believe the session
      model's `:id` is a `:reasoning` keyword.
      → Resolved: `session-model-custom?` docstring now states the persistable
      shape as `{:provider (name provider) :id model-id :reasoning bool}`
      (persistable-model: `:id` holds the model-id string and `:reasoning` is a
      separate boolean key), so a reader cannot mistake the session model's
      `:id` for a `:reasoning` keyword. Docstring-only; no behavior change.

## Follow-ups (implementation review 43, 2026-08-09)

- [x] `stream-anthropic` silently drops Anthropic SSE `error` events
      (`{"type":"error","error":{...}}` — Anthropic's documented mid-stream
      error shape, e.g. `overloaded_error`/rate-limit during a stream). The
      stream loop's `(case (:type event-data) ...)` handles only
      message_start/content_block_start/content_block_delta/content_block_stop/
      message_delta/message_stop; an `error` event falls to the default `nil`
      and is consumed as a no-op — the line IS captured via
      `capture-response!` (so `:on-provider-response` sees it) but no `:error`
      event reaches the consumer and no terminal `:done` is emitted, so the
      turn hangs until the 20-minute default `llm-stream-idle-timeout-ms`
      (1200000, turn-runtime/stream.clj) and fails with a misleading timeout
      instead of the provider's actual error. The `:openai-codex-responses`
      transport already handles `"error"` SSE events explicitly
      (handle-codex-event! → emit-codex-error!), so an in-repo precedent
      exists; `:openai-completions` has the same silent-drop class (an error
      chunk with no `:choices` no-ops in process-chat-sse-line!). Relevant to
      this task's newly shipped DeepSeek provider: the docs (review-1 live
      smoke test 2026-08-09) verified the happy path but no failure mode
      beyond HTTP-level errors; a mid-stream DeepSeek error today hangs the
      turn. Fix: add an `"error"` case branch in stream-anthropic that emits
      an `:error` event (mapping the event's error body through
      anthropic-error, with http-status when present) and terminates the
      stream (respect the existing `done?` guard), plus a stream test feeding
      an SSE error event and asserting the `:error` event + no hang; decide
      whether the same class warrants a chat-completions fix (same pattern).
      → Resolved: `stream-anthropic`'s stream loop now has an `"error"` case
      branch — the event's error body is mapped through
      `anthropic-error/error-from-response-data` (message from parsed-body
      `[:error :message]`, http-status from `[:error :http_status]` when
      present, fallback "Anthropic stream error", raw event preserved as
      `:body`/`:body-text`; the line was already captured via
      `capture-response!`, so no double capture) and the `:error` event is
      emitted, terminating the stream under the existing `done?` guard (a
      trailing `message_stop` cannot emit a second terminal event).
      Chat-completions decision: FIXED too — `process-chat-sse-line!` now
      detects a parsed chunk carrying `:error` (no `:choices`) and emits an
      `:error` event + terminates via new `emit-chat-error!` (numeric
      http-status from `:status`/`[:error :status]`/`[:error :http_status]`
      when present, message via the openai transport's
      `error-from-response-data`), mirroring the codex transport's in-repo
      precedent. Tests: `anthropic_stream_test.clj`
      `stream-anthropic-surfaces-sse-error-event-test` (with `http_status 529`
      → "Overloaded (status 529)" + raw body + no `:done`; without status →
      message only, no status suffix, no `:done`);
      `openai_completions_test.clj`
      `completions-sse-error-event-emits-error-and-terminates-test`
      (server_error chunk → `:error` event, no `:done`). Specs updated
      (`spec/anthropic-provider.allium` `SseErrorEventEmitsErrorAndTerminates`;
      `spec/openai-provider.allium`
      `CompletionsSseErrorChunkEmitsErrorAndTerminates`); CHANGELOG `Fixed`
      entry. Full `bb test` green (2590 tests / 19462 assertions / 0
      failures); clj-kondo + cljfmt + file-lengths clean.
- [x] `content-block-stop-event` maps every non-tool block stop to `:text-end`
      — including `thinking` content blocks, which `stream-anthropic` emits
      (DeepSeek returned a `thinking` block in the live smoke test
      2026-08-09, and `content-block-start-event`/`content-block-delta-event`
      correctly emit `:thinking-start`/`:thinking-delta`/
      `:thinking-signature-delta`). The accumulator (turn-runtime/
      accumulator.clj) has a dedicated `:on-thinking-end` handler
      (note-last-provider-event! `:thinking-end` + end-content-block!), but
      it is dead code for the anthropic path: a thinking block's stop arrives
      labeled `:text-end`, so the last-provider-event diagnostic marker
      mislabels DeepSeek thinking-block stops as text. Fix: make
      `content-block-stop-event` emit `:thinking-end` for `"thinking"`
      blocks (keep `:toolcall-end` for tool_use, `:text-end` for text), and
      add a stream-test assertion that a thinking block's stop emits
      `:thinking-end` (no existing anthropic test asserts this).
      → Resolved: `content-block-stop-event` now emits `:thinking-end` for
      `"thinking"` blocks (keeps `:toolcall-end` for tool_use and `:text-end`
      for text), so the accumulator's dedicated `:on-thinking-end` handler
      (`note-last-provider-event!` `:thinking-end` + `end-content-block!`)
      runs for anthropic-path thinking-block stops instead of the mislabeled
      `:text-end`. Test: `anthropic_stream_test.clj`
      `thinking-block-stop-emits-thinking-end-test` — a thinking block's stop
      emits `:thinking-end` at its content-index, is NOT mislabeled
      `:text-end`, and a following text block's stop still emits `:text-end`.
      Spec updated (`spec/anthropic-provider.allium`
      `ContentBlockStopEmitsTypedEndEvent`; the `StreamEvent` `event_type`
      enum now also carries `thinking_start`/`thinking_signature_delta`/
      `thinking_end` — the thinking-start and signature-delta events were
      already emitted by `content-block-start-event`/
      `content-block-delta-event` but missing from the enum;
      `SignatureFragmentObserved` was replaced by the emitted
      `thinking_signature_delta` StreamEvent to match the implementation).
      CHANGELOG `Fixed` entry. Full `bb test` green (2590 tests / 19462
      assertions / 0 failures); clj-kondo + cljfmt + file-lengths clean.

## Follow-ups (implementation review 44, 2026-08-09)

- [x] `stream-anthropic`'s review-43 terminal-event guard is incomplete: the
      `done?` guard was added to the `"error"` and `"message_stop"` branches,
      but the `"message_delta"` branch's terminal emission is unguarded — a
      mid-stream Anthropic SSE `error` event followed by a trailing
      `message_delta` carrying `delta.stop_reason` emits a SECOND terminal
      `:done` after the `:error` (verified live via the stream loop:
      events = `[:start :error :done]` for error → message_delta
      `{:delta {:stop_reason "end_turn"}}`), contradicting the review-43
      fix's claim ("terminating the stream under the existing done? guard — a
      trailing message_stop cannot emit a second terminal event"; the guard
      covers only message_stop). The unguarded branch also fires the
      structured-output-result emissions (`maybe-emit-json-schema-output-result!` /
      `maybe-emit-prompted-json-result!`) after the `:error`, and
      `update-output-usage!` runs on a post-error message_delta. Same
      guard-completeness class in the sibling transports: the catch blocks of
      `stream-anthropic` and `stream-openai` emit a second `:error` with no
      `done?` check if the stream read throws after a mid-stream error has
      already terminated the stream (the codex transport's `emit-codex-error!`
      is properly `done?`-guarded — the in-repo model to mirror). Downstream,
      the consumer sends `:turn/error` then `:turn/done` (or a second
      `:turn/error`), so a post-error trailing event can mask or re-enter the
      terminal state. Fix: guard the `:done` emission in the `message_delta`
      branch with `when-not @done?` (keep usage accumulation and the
      structured-result emissions inside the guard with the `:done`), guard
      both stream catch blocks' `:error` emission on `done?` (emit nothing
      when already terminated), and add stream tests: error →
      message_delta-with-stop_reason → exactly one terminal event (the
      `:error`, no `:done`), and error → stream-read exception → no second
      `:error`. Mirror the invariant in `spec/anthropic-provider.allium` and
      `spec/openai-provider.allium` (a "once done, no further terminal event
      is emitted" rule — the review-43 rules set `stream.done = true` but no
      rule forbids a subsequent terminal emission; the `MessageDelta` spec
      rule models only usage), and tighten the CHANGELOG `Fixed` entry's
      "terminates" wording if it implies the guard is complete.
      → Resolved: `stream-anthropic`'s `message_delta` branch is now wrapped
      in `when-not @done?` — usage accumulation, the structured-output-result
      emissions AND the terminal `:done` all sit inside the guard, so a
      post-error trailing `message_delta` is a full no-op (verified: events
      are now exactly `[:start :error]` for error → message_delta stop_reason
      end_turn). Both stream catch blocks' `:error` emission is guarded on
      `done?` (`stream-anthropic` and `stream-openai`; mirror the codex
      transport's `emit-codex-error!`), so a stream-read exception thrown
      after a mid-stream error has terminated the stream emits nothing.
      Tests (all verified to FAIL against the old code): `anthropic_stream_test.clj`
      `stream-anthropic-error-then-message-delta-single-terminal-event-test`
      (error → message_delta-with-stop_reason → exactly one terminal event,
      events = `[:start :error]`, no `:done`) and
      `stream-anthropic-error-then-read-exception-no-second-error-test`
      (post-error stream-read exception swallowed — exactly one `:error`);
      `openai_test.clj`
      `completions-sse-error-then-read-exception-no-second-error-test`
      (error chunk → post-error stream-read exception → no second `:error`).
      Specs: `spec/anthropic-provider.allium` gains
      `OnceDoneNoFurtherTerminalEvent` (no further `done | error` Emit once
      `stream.done`), `MessageDeltaAccumulatesOutputUsage` now `requires: not
      stream.done`, and a new `MessageDeltaStopReasonEmitsDone` rule models
      the guarded terminal `:done`; `spec/openai-provider.allium` mirrors the
      `OnceDoneNoFurtherTerminalEvent` invariant and the
      `CompletionsSseErrorChunkEmitsErrorAndTerminates` guidance records the
      catch-block guard. CHANGELOG `Fixed` entry's "terminates" wording
      tightened to the exact guarantee: exactly one terminal event per
      stream, with the `message_delta`/`message_stop`/`[DONE]`-trailing and
      post-error-exception cases named. Verification: full `bb test` green
      (2593 tests / 0 failures; 2590 → 2593 = the three new deftests;
      assertion count varies run-to-run per the review-5 flake analysis —
      19467 then 18759 on the final tree); `psi.ai.providers.anthropic-stream-test` 11/81,
      `psi.ai.providers.openai-test` 10/67 (was 9/65 on committed HEAD),
      `psi.ai.providers.openai-completions-test` 17/75 (unchanged from
      committed HEAD — the openai terminal-guard test lives in
      `openai_test.clj` to keep the completions file under the 800-line
      file-length gate), `psi.ai.providers.anthropic-test` 16/103,
      `psi.ai.providers.anthropic-auth-test` 5/42 green; clj-kondo clean (0
      errors, 0 warnings); `bb commit-check:file-lengths` passes (all
      touched test files under 800 lines).
- [x] design.md's "Revision note (implementation reviews)" is stale: its
      opening claim — "They are the *only* provider-transport changes in this
      task" — and the acceptance-criterion wording ("No change to
      `providers/anthropic.clj`'s request-shaping logic itself ... only the
      schema gate in `user_models.clj` plus the review-driven API-key
      resolution changes documented in the revision note") enumerate the
      review-driven provider-transport changes, but the review-43 changes are
      provider-transport changes NOT in the enumeration: mid-stream SSE
      `error`-event surfacing on the `:anthropic-messages` AND
      `:openai-completions` transports (stream-anthropic's `"error"` case
      branch + `emit-chat-error!`/`process-chat-sse-line!` in
      chat_completions.clj) and the `content-block-stop-event` `:thinking-end`
      labeling for `"thinking"` blocks (providers/anthropic.clj streaming
      behavior change). A reader of design.md would conclude those changes do
      not exist or were out of scope; every prior provider-transport review
      (3/10/13/14/26/35/36/42) added its bullet(s) to the revision note.
      Fix: add review-43 bullets to the revision-note enumeration (or qualify
      the "only" claim to name the stream/error-surfacing + thinking-end
      changes), keeping the design artifact coherent with the implemented
      behavior per the change chain.
      → Resolved: design.md "Revision note (implementation reviews)" now
      carries the review-43/44 bullets — "Mid-stream SSE error-event
      surfacing + terminal-event guard (reviews 43/44)" (the `"error"` SSE
      case branch on `:anthropic-messages` + `emit-chat-error!`/
      `process-chat-sse-line!` on `:openai-completions`; review 44's
      `done?`-guard completion: `message_delta` terminal `:done` + usage +
      structured-result emissions guarded, both stream catch blocks'
      `:error` emission guarded — exactly one terminal event per stream,
      mirroring codex's `emit-codex-error!`) and "Thinking-block stop
      labeling (review 43)" (`content-block-stop-event` emits `:thinking-end`
      for `"thinking"` stops, not the mislabeled `:text-end`). The AC
      exception wording now names both ("mid-stream SSE error-event surfacing
      + the terminal-event guard on both transports, and `:thinking-end`
      labeling for thinking-block stops") and the "only the schema gate plus
      the review-driven API-key resolution changes" phrase now reads "plus
      the review-driven provider-transport changes documented in the revision
      note".

## Follow-ups (implementation review 45, 2026-08-09)

- [x] design.md's "Revision note (implementation reviews)" enumeration and the
      AC exception wording are still incomplete for the review-22 provider
      changes — review 44 added the review-43/44 bullets, but review-22's
      provider-transport changes were never enumerated, so the note's opening
      claim ("They are the *only* provider-transport changes in this task")
      and the AC "no custom-provider behaviour changes except the
      review-driven changes documented in the revision note" list remain
      false in the same way review 44 found for 43/44. Omitted: (a) the
      HTTP-400 compatibility retry's OAuth decision — review 22 replaced the
      three-marker header content-sniff (`oauth-auth-request?`) in
      `handle-400-response!`'s beta-config with the transport's COMPUTED
      `oauth?` boolean threaded from `build-request` as `::oauth?`, so a
      keyless custom provider whose custom `:headers` reproduce the Claude
      Code CLI marker set now selects `:without-all-betas` on a beta-related
      400 instead of retaining every beta, repeating the 400 and hard-failing
      — a provider-transport BEHAVIOR change with its own CHANGELOG `Fixed`
      entry, absent from the revision note; (b) the shared `no-auth?`
      keyless-predicate unification in `request-support/resolve-api-key`
      (review 22 moved the keyless early-return onto the shared predicate —
      the same "pure refactor — no behavior change" class as the review-14
      request-support bullet that IS enumerated); and (c) the review-22
      `[:supports-mid-conversation-system-messages {:optional true}
      [:maybe boolean?]]` ModelDef schema field — the design's In-scope list
      and the AC schema bullet mention only `:adaptive-thinking`, yet the
      field was added to the closed `ModelDef` schema (with a CHANGELOG
      `Added` entry) and flows through `expand-model`. Every other
      review-driven change got its bullet; fix: add review-22 bullets to the
      revision-note enumeration (or qualify the "only" claim) and name the
      schema field in the design's schema scope / AC, keeping the design
      artifact coherent with the implemented behavior per the change chain.
      → Resolved: design.md "Revision note (implementation reviews)" now
      carries two review-22 bullets — "HTTP-400 compatibility retry OAuth
      decision (review 22)" (the computed `::oauth?` boolean threaded from
      `build-request` into `handle-400-response!`'s beta-config replaces the
      three-marker header content-sniff for `:without-all-betas` selection; a
      keyless custom provider reproducing the Claude Code CLI marker set now
      gets its betas stripped on a beta-related 400 instead of retaining
      every beta and hard-failing; content-sniffing `oauth-auth-request?`
      remains for error diagnostics only; a custom-provider behavior change
      with its own CHANGELOG `Fixed` entry) and "Shared keyless-predicate
      unification (review 22)" (`resolve-api-key`'s keyless early-return now
      uses the shared `no-auth?` predicate — pure refactor, no behavior
      change) — the "only provider-transport changes" claim now holds. The
      In-scope list gains a "Mid-conversation system-message capability
      field (review 22, pulled into scope)" bullet naming the
      `[:supports-mid-conversation-system-messages {:optional true}
      [:maybe boolean?]]` `ModelDef` schema field (canonical `Model` schema
      already had it; models.edn custom providers could not declare it),
      and the AC schema bullet now names both fields ("accepts
      `:adaptive-thinking true/false` (and the review-22
      `:supports-mid-conversation-system-messages true/false` field)").
      The AC exception wording names the HTTP-400-compatibility-retry OAuth
      decision alongside the other review-driven custom-provider behavior
      changes.
- [x] The delegate-review live test's durable lock covers only the
      `:reviewing-implementation` profile: the snapshot assertions check that
      ONE profile is present, valid, and resolves to
      deepseek/deepseek-v4-flash. The other six committed `.psi/project.edn`
      deepseek profiles (`:designing :fixing-design :planning :fixing-plan
      :implementing :fixing-implementation`) are unlocked — a SINGLE-profile
      regression (one profile removed, retargeted at a nonexistent/typo'd
      model-id or provider, an invalid `:thinking-level`, or re-pointed at
      the commented anthropic/openai map) passes `bb test` green and fails
      only at delegated-workflow runtime. The review-2/18/28/38 regression
      class (all seven profiles invalid) is caught only because
      `:reviewing-implementation` is one of the seven, so the review-38
      "the delegate-review live test IS the lock" decision is only a partial
      lock for that class. `profile-snapshot` already exposes
      `:valid-profile-names`/`:invalid-profile-names` across ALL profiles, so
      the fix is cheap: extend the live-test snapshot assertions to assert
      all seven committed profiles are present, valid, and resolve to the
      committed deepseek model — or add a dedicated `.psi/project.edn`
      parse-lock test mirroring
      `committed-project-models-edn-matches-documented-deepseek-example-test`
      in user_models_test.clj (assert the committed project.edn's seven
      session-profiles all resolve against the committed `.psi/models.edn`
      deepseek model), so a partial profile regression fails loud instead of
      surfacing at workflow runtime.
      → Resolved (live-test extension, the item's first option):
      `delegate-review-task-implementation-completes-with-nullable-local-model-test`
      now doseqs ALL SEVEN committed `.psi/project.edn` session profiles
      (`:designing :fixing-design :planning :fixing-plan :implementing
      :reviewing-implementation :fixing-implementation`) from the
      `session-profiles/profile-snapshot`, asserting each is present in the
      snapshot, `:valid?` (covers the invalid-`:thinking-level` and
      unknown-model regression sub-classes — `resolve-profile` marks both
      invalid), and resolves to `{:provider "deepseek"
      :id "deepseek-v4-flash"}` (covers retargeting at a nonexistent/typo'd
      model or provider and re-pointing at the commented anthropic/openai
      map). A single-profile regression now fails the live test
      deterministically instead of surfacing only at delegated-workflow
      runtime. Namespace green (3 tests / 42 assertions — the snapshot block
      grew from 3 to 21 assertions); clj-kondo clean (0 errors, 0 warnings);
      file-length gate fine (263 lines &lt; 800).

## Follow-ups (implementation review 46, 2026-08-09)

- [x] The review-43/44 mid-stream-error terminal-event guard is incomplete:
      only the TERMINAL emissions (anthropic `message_delta`/`message_stop`
      `:done`, both transports' stream catch blocks' `:error`,
      `emit-chat-error!`'s own emission) are guarded on `done?` — the
      NON-terminal branches still emit events after the stream has already
      terminated with an `:error`, on all three transports (verified
      empirically against the committed code with redef'd `http/post`):
      - `stream-anthropic`: a trailing `content_block_stop` after the SSE
        `error` event still emits `:text-end` (events = `[:start :text-start
        :text-delta :error :text-end]`); trailing `content_block_delta` /
        `content_block_start` similarly emit `:text-delta`/`:text-start`
        post-error, and a trailing structured-tool `content_block_stop`
        fires `maybe-emit-structured-result!` after the error.
      - `stream-openai` (chat completions): a trailing `:choices` chunk
        after the error chunk still emits `:text-delta` via
        `emit-chat-chunk!` (events = `[:start :error :text-delta]`); a
        trailing usage/finish chunk drives `finish-chat-chunk!`'s unguarded
        `force-start-pending-chat-tools!`/`emit-chat-tool-ends!`/
        `emit-structured-output-result!` (only `emit-chat-completion-finish!`
        is done?-guarded), and `flush-pending-chat-finish!` on a trailing
        `[DONE]` with a pending finish reason emits
        `:structured-output-result` post-error.
      - `stream-openai-codex`: a trailing `response.output_text.delta` after
        `response.failed`/`error` still emits `:text-delta` (events =
        `[:start :error :text-delta]`) — `handle-codex-event!` has no `done?`
        check at its top (only `emit-codex-error!`/`emit-codex-done!`
        self-guard).
      This contradicts the review-44 claim ("a trailing SSE event after the
      error ... suppressed by the done? guard") and the CHANGELOG `Fixed`
      wording, which overstate the guard's coverage (they hold only for the
      enumerated terminal shapes). Downstream, `make-provider-event-consumer`
      dispatches the post-error events: `:text-end`/`:text-start`/
      `:thinking-*`/`:logprob-delta`/`:structured-output-result` call the
      accumulator actions DIRECTLY (bypassing the statechart, which is in
      `:error` with no transitions), mutating turn-data after `handle-error!`
      has already finalized the result (e.g. `end-content-block!` closing a
      block after finalization, `note-last-provider-event!` overwriting the
      `:error` marker with `:text-end`). Fix: guard ALL non-terminal branches
      on `done?` (or short-circuit the line loop once `done?` is set) on all
      three transports, so a post-error trailing event is a full no-op; add
      stream tests (anthropic error → trailing `content_block_stop` → no
      `:text-end` after `:error`; openai error → trailing `:choices` chunk →
      no `:text-delta` after `:error`; codex error → trailing
      `response.output_text.delta` → no `:text-delta` after `:error`);
      mirror the invariant in `spec/anthropic-provider.allium` +
      `spec/openai-provider.allium` (`OnceDoneNoFurtherTerminalEvent` covers
      only terminal events — extend to "no further event at all once done" or
      add a rule), and tighten the CHANGELOG wording to the exact guarantee.
      → RESOLVED (2026-08-09): the whole SSE dispatch is now short-circuited
      on `done?` on all three transports — `stream-anthropic`'s event `case`
      is wrapped in `when-not @done?` (and `done?` is set on the `message_stop`
      terminal too), `process-chat-sse-line!` and `handle-codex-event!` each
      short-circuit at their top — so a post-error trailing event is a full
      no-op on every branch, terminal or not. Three new stream tests lock the
      behavior (`stream-anthropic-error-then-content-block-stop-no-text-end-test`,
      `completions-sse-error-then-trailing-choices-chunk-no-text-delta-test`,
      `codex-error-then-trailing-output-text-delta-no-text-delta-test`), all
      three verified to FAIL against the pre-fix code and PASS with the fix.
      Specs extended: `OnceDoneNoFurtherTerminalEvent` → `OnceDoneNoFurtherEvent`
      ("no further `Emit(StreamEvent)` once `stream.done`") in both
      `spec/anthropic-provider.allium` and `spec/openai-provider.allium`, with
      `requires: not stream.done` added to the non-terminal dispatch rules
      (`MessageStartEmitsStartEvent`,
      `AnthropicThinkingStreamingIncludesSignatureMaterial`,
      `ContentBlockStopEmitsTypedEndEvent`,
      `CompletionsChunksNormalizeToExecutorEvents`,
      `CodexEventsNormalizeToExecutorEvents`). CHANGELOG `Fixed` wording
      tightened to the exact guarantee ("once the stream has terminated, no
      further event of any kind is emitted"); design.md revision note +
      AC updated. Full `bb test` green (2596 tests / 19492 assertions /
      0 failures); clj-kondo clean (0 errors, 0 warnings); cljfmt clean;
      `bb commit-check:file-lengths` passes (all touched files under 800
      lines).

## Follow-ups (implementation review 47, 2026-08-09)

- [x] `stream-anthropic`'s `message_stop` terminal `:done` carries no
      `:usage`: the `message_delta`-with-`stop_reason` branch emits
      `:done` WITH `(usage-with-cost model usage-acc)`, but the
      `message_stop` branch emits a bare `{:type :done :reason :stop}` — so
      when a stream terminates via `message_stop` WITHOUT a preceding
      `message_delta` carrying `stop_reason`, the turn's `handle-done!`
      (`(map? usage)` false) records ZERO usage/cost even though `usage-acc`
      already holds the input + cache tokens accumulated from
      `message_start` (the review-44/46 guards made this the ONLY terminal
      in that flow; they addressed event ordering, not usage content).
      Reachable on any Anthropic-compatible endpoint that omits
      `message_delta` (or sends it without `stop_reason`/`usage`) —
      including the newly shipped DeepSeek provider whose STREAMING path is
      unverified: the review-1 live smoke test (2026-08-09) exercised only
      the non-streaming `execute` path, whose usage comes from the response
      body; the streaming usage path is `message_start`/`message_delta` SSE
      events only, so a streamed DeepSeek turn ending via `message_stop`
      without `message_delta` would silently record no cost despite the
      documented cache-cost fields. Fix: attach
      `:usage (usage-with-cost model usage-acc)` to the `message_stop`
      `:done` (at minimum the accumulated input/cache tokens; output stays
      0), add a stream test (message_start with input/cache usage → content
      blocks → message_stop, no message_delta → exactly one `:done`
      carrying the accumulated input usage + cost map), and mirror the
      terminal in `spec/anthropic-provider.allium` if it models the
      `message_stop` `:done` shape.
      → Resolved: `stream-anthropic`'s `message_stop` terminal `:done` now
      carries `:usage (usage-with-cost model usage-acc)` like the
      `message_delta`-with-`stop_reason` terminal — a stream ending via
      `message_stop` without a `message_delta`-with-`stop_reason` records
      the accumulated input/cache tokens + cost instead of zero (output
      stays 0 — nothing accumulated it in that flow). New test:
      `stream-anthropic-message-stop-done-carries-usage-test`
      (message_start with input/cache usage → content blocks → message_stop,
      no message_delta → exactly one `:done` carrying the accumulated input
      usage + cost map; output-tokens 0; total = 130) — verified FAIL
      against the pre-fix code (bare `{:type :done :reason :stop}`), PASS
      with the fix. Spec: `MessageStopEmitsDoneWithUsage` rule added to
      `spec/anthropic-provider.allium` (models the `message_stop` `:done`
      with `FinalizedUsage(stream.usage_acc)`, `requires: not stream.done`).
      CHANGELOG `Fixed` entry; design.md revision note + AC updated.
- [x] Anthropic mid-stream SSE `"error"` branch status extraction is
      incomplete AND unvalidated vs the sibling transports: the branch reads
      http-status from `[:error :http_status]`/`:http_status` only, while
      the openai `emit-chat-error!` reads
      `:status`/`[:error :status]`/`[:error :http_status]` and validates
      `(and (number? s) (>= s 400) s)`, and codex's
      `codex-error-http-status` reads eight locations incl.
      `[:error :status]` (review-43's resolution text described the openai
      locations but never flagged the anthropic branch's narrower read).
      An Anthropic-compatible endpoint (DeepSeek's mid-stream error shapes
      are unverified; the review-1 smoke test covered only the happy path)
      emitting `{"type":"error","error":{"status":529,"message":"..."}}` —
      or a generic message plus a `status` key, or a string status — loses
      its status: the `:error` event carries no numeric `:http-status`, so
      downstream `retry-error?`/`provider-error-kind`
      (numeric `#{429 500 502 503 529}` membership + message patterns) falls
      to `:unknown` → a transient mid-stream 5xx/overload error is NOT
      auto-retried — the same class review 23 fixed for the OpenAI
      transports. Fix: mirror `emit-chat-error!`'s status extraction on the
      anthropic branch (check `:status`/`[:error :status]`/
      `[:error :http_status]`, keep only numeric `>= 400`), add a stream
      test with an error event carrying `status` (not `http_status`)
      asserting the `:error` event's `:http-status`, and update the
      `SseErrorEventEmitsErrorAndTerminates` guidance in
      `spec/anthropic-provider.allium` (which currently documents
      http-status from `[:error :http_status]` only).
      → Resolved: the `"error"` SSE branch's http-status extraction now
      mirrors `emit-chat-error!`/`codex-error-http-status` — checked at
      `:status` / `[:error :status]` / `[:error :http_status]` (keeping the
      existing top-level `:http_status` read too), numeric `>= 400` only —
      so a status-carrying error event (e.g.
      `{"error":{"status":529,...}}`, a generic message plus a `status`
      key) keeps its numeric `:http-status` and downstream
      `retry-error?`/`provider-error-kind` classify a transient mid-stream
      5xx/overload as retryable instead of `:unknown`; a non-numeric
      (string) status is dropped. New test:
      `stream-anthropic-sse-error-status-key-test` — error event carrying
      `status 529` (not `http_status`) → `:error` carries `:http-status 529`
      + "Overloaded (status 529)" message; string status `"529"` → dropped,
      no numeric `:http-status` — verified FAIL against the pre-fix code,
      PASS with the fix. `SseErrorEventEmitsErrorAndTerminates` guidance in
      `spec/anthropic-provider.allium` now documents the review-47
      extraction (numeric `>= 400`, the status-carrying/lost-status
      consequence). CHANGELOG `Fixed` entry; design.md revision note + AC
      updated.

## Follow-ups (implementation review 48, 2026-08-09)

- [x] `stream-anthropic` and `stream-openai` (chat completions) have no
      EOF-level terminal flush — the codex transport does, so a stream that
      ends without an in-band terminal event hangs the turn until
      `llm-stream-idle-timeout-ms` on the two non-codex transports. Verified
      against the committed code (2026-08-09):
      - `stream-openai-codex` (codex_responses.clj) is the in-repo
        precedent: after the SSE `doseq` it runs
        `(when-not @(:done? stream-state) (emit-codex-start! …)
        (emit-codex-done! … {:response {:status "completed"}} …))`, so a
        truncated/non-conforming stream always gets a terminal `:done`.
      - `stream-anthropic` (`consume-stream-response!`) and `stream-openai`
        (`stream-openai`'s `doseq` of `process-chat-sse-line!`) end with
        NOTHING after the loop. Anthropic: a stream that EOFs without
        `message_stop` / `message_delta`-with-`stop_reason` / `"error"`
        emits no terminal event — the review-43 hang class, still reachable
        via the EOF path rather than a mid-stream error, and directly
        task-relevant: review 47 established DeepSeek's streaming path is
        UNVERIFIED (the review-1 smoke test exercised only the non-streaming
        `execute` path), so a DeepSeek stream that ends without
        `message_stop` currently hangs 20 minutes instead of terminating.
        OpenAI: a final chunk carrying `finish_reason` but no trailing
        `[DONE]` sets `pending-finish-reason` and never flushes it
        (`flush-pending-chat-finish!` runs ONLY on a `[DONE]` line), and a
        `[DONE]` with no prior `finish_reason` chunk no-ops
        (`flush-pending-chat-finish!` guards on the pending reason) — both
        leave the turn with no `:done`/`:error` until the idle timeout.
        Fix: mirror the codex EOF flush on both transports (after the
        `doseq`, `when-not @done?` → emit the terminal: anthropic `:done`
        with the review-47 `usage-with-cost usage-acc` shape; openai `:done`
        with the pending finish reason or `:stop`, and accumulated usage if
        any was seen); add stream tests: anthropic error-free stream ending
        at EOF without `message_stop` → exactly one `:done`; openai
        finish_reason-chunk-then-EOF (no `[DONE]`) → exactly one `:done`;
        openai `[DONE]` without `finish_reason` → exactly one `:done`.
        Mirror the guarantee in `spec/anthropic-provider.allium` +
        `spec/openai-provider.allium` (`OnceDoneNoFurtherEvent` covers
        post-done no-ops; nothing models the EOF-without-terminal case the
        codex transport's flush already closes).
      → Resolved: both non-codex transports now flush the terminal `:done`
      at EOF, mirroring the codex transport's post-doseq `(when-not @done?
      ...)`. Anthropic: `consume-stream-response!` runs
      `(when-not @done? (emit-terminal-done!) nil)` after the SSE `doseq`
      (the shared `emit-terminal-done!` helper — the `message_stop` branch
      and the flush emit the identical `:stop` terminal with the review-47
      `usage-with-cost` shape). OpenAI: `stream-openai` runs the EOF flush
      after its `doseq` — `flush-pending-chat-finish!` (pending
      finish_reason, else the second `when-not @done?` block emits `:stop`
      with `:start`-once + structured-output result + the last-seen usage),
      all no-ops when an in-band terminal already fired. Tests (new
      `anthropic_stream_termination_test.clj` +
      `openai_completions_stream_test.clj`): anthropic error-free stream
      ending at EOF without `message_stop` → exactly one `:done` (with the
      accumulated input/cache usage); openai
      finish_reason-chunk-then-EOF (no `[DONE]`) → exactly one `:done`
      with the pending reason; openai `[DONE]` without `finish_reason` →
      exactly one `:done` (`:stop`). Specs: new
      `AnthropicTransportCloseEmitsDoneIfNotTerminal` +
      `OpenAITransportCloseEmitsDoneIfNotTerminal` rules with a new
      `ProviderTransportClosed(stream)` primitive model the EOF-without-
      terminal flush on both transports (the previously-missing half the
      codex flush already closed).
- [x] OpenAI chat-completions terminal `:done` carries NO usage when the
      provider omits the `usage` chunk — the exact review-47 zero-usage
      class fixed on the anthropic `message_stop` terminal, still open on
      the sibling `:openai-completions` transport (and on codex's synthetic
      EOF `:done`, which passes `{:response {:status "completed"}}` with no
      usage). Verified against the committed code (2026-08-09):
      `finish-chat-chunk!`'s `(:finish_reason choice)` branch only records
      `pending-finish-reason`, and `flush-pending-chat-finish!` (on the
      trailing `[DONE]`) calls `emit-chat-completion-finish!` with
      `usage nil` → `(cond-> {:type :done :reason reason} usage …)` emits a
      `:done` with no `:usage` key → `handle-done!` (`(map? usage)` false)
      records ZERO usage/cost. Reachable on any OpenAI-compatible custom
      endpoint that ignores `stream_options.include_usage` (the body always
      sets it, but local proxies / third-party endpoints commonly omit the
      usage chunk) — the same class the task's review-47 CHANGELOG `Fixed`
      entry claims to close ("a streamed turn … records the accumulated
      tokens … instead of silently recording zero usage and cost"), which
      names only the anthropic `message_stop` path. The existing
      `completions-trailing-usage-after-finish-reason-is-preserved-test`
      covers finish_reason-before-usage (usage chunk still arrives); no
      test covers finish_reason + `[DONE]` with NO usage chunk. Fix: have
      the openai transport accumulate the last-seen `:usage` (or the
      review-47-style accumulated shape) and attach it to the flushed
      `:done` when a usage chunk was seen, or document the zero-usage
      consequence for usage-omitting endpoints; add a stream test
      (finish_reason chunk → `[DONE]`, no usage chunk → `:done` carries no
      `:usage`, matching `handle-done!`'s zero-usage semantics) and update
      the CHANGELOG `Fixed` wording to name the openai omission path.
      → Resolved: the openai transport now accumulates the last-seen usage
      chunk (`:last-usage` stream-state atom, set in `finish-chat-chunk!`'s
      `:usage` branch) and `flush-pending-chat-finish!` / the EOF flush
      attach it to the flushed terminal `:done` when one was seen — a
      trailing-`[DONE]`/EOF `:done` no longer hardcodes `usage nil` when a
      usage chunk had been accumulated. A usage-omitting endpoint (ignores
      `stream_options.include_usage` — common for local proxies / third-
      party OpenAI-compatible endpoints) still gets a `:done` with no
      `:usage` key → zero usage/cost recorded, now documented as the
      consequence for usage-omitting endpoints. New test
      `completions-done-without-usage-chunk-carries-no-usage-test`
      (finish_reason chunk → `[DONE]`, no usage chunk → `:done` carries no
      `:usage`, exactly one `:done`) + `completions-finish-reason-then-eof-
      emits-done-test` (usage-omitting finish_reason-then-EOF flow);
      CHANGELOG `Fixed` wording extended to name the openai omission path
      (the review-47 zero-usage entry now covers both the anthropic
      `message_stop` path and the `:openai-completions` usage-chunk-omitted
      path).
- [x] `"redacted_thinking"` content blocks are still mislabeled as text in
      the review-43-typed block events: `content-block-start-event` falls
      to the default `:text-start` and `content-block-stop-event` to
      `:text-end` for a `"redacted_thinking"` block (Anthropic's first
      thinking block in extended-thinking streams), so the accumulator
      creates a phantom empty text block and the last-provider-event
      diagnostic marker mislabels a thinking-family block stop as text —
      the same mislabel class review 43 fixed for `"thinking"` (which now
      emits `:thinking-start`/`:thinking-end`). Not reachable on the newly
      shipped DeepSeek provider (its compat table explicitly does NOT
      support redacted-thinking blocks — the review-43 fix's own
      motivation was DeepSeek's thinking blocks), so this is a built-in
      Anthropic-path completion of the review-43 typing change, not a
      DeepSeek-path gap. Fix (either): skip `"redacted_thinking"` blocks in
      `content-block-start-event`/`content-block-stop-event` (no phantom
      text block, no mislabeled marker), or map them to
      `:thinking-start`/`:thinking-end` (with the block's `:data`);
      add a stream test asserting a `"redacted_thinking"` block's
      start/stop emits no `:text-start`/`:text-end` mislabel, and extend
      the `ContentBlockStopEmitsTypedEndEvent` guidance in
      `spec/anthropic-provider.allium` accordingly.
      → Resolved (skip option): `content-block-start-event` and
      `content-block-stop-event` (extracted to the new
      `psi.ai.providers.anthropic.stream-events` namespace) now return nil
      for `"redacted_thinking"` blocks, and `consume-event!` (nil-guarded)
      is used at both call sites — a redacted_thinking block's start/stop
      emit NO event (no phantom text block, no mislabeled `:text-end`, no
      unbalanced `:thinking-end` for a skipped start), and
      `content-block-delta-event` skips the `redacted_thinking_delta` (no
      `:text` in the delta). New `redacted-thinking-block-not-mislabeled-
      as-text-test` (anthropic_stream_termination_test.clj) asserts no
      `:text-start`/`:text-end`/`:thinking-start`/`:thinking-end`/
      `:text-delta` and exactly one `:done`. Spec:
      `ContentBlockStopEmitsTypedEndEvent` gains the
      `"redacted_thinking"` → `Emit(nothing)` branch + guidance (start and
      delta skipped symmetrically); CHANGELOG `Fixed` entry added.

## Follow-ups (implementation review 49, 2026-08-09)

- [x] `stream-anthropic`'s terminal `:done` emission resets `done?` AFTER
      the structured-output-result emissions and the `:done` consumption —
      the ONLY terminal path across the three transports that does this.
      The `message_delta`-with-`stop_reason` branch resets `done?` FIRST
      (`(reset! done? true)` then the maybe-emit-* emissions + `:done`
      consume), and every OpenAI-transport terminal emitter resets first
      too (`emit-chat-completion-finish!`/`emit-chat-error!` in
      chat_completions.clj, `emit-codex-done!`/`emit-codex-error!` in
      codex_responses.clj all set `done?` before consuming). The
      `message_stop` branch runs
      maybe-emit-json-schema-output-result! /
      maybe-emit-prompted-json-result! and the `:done` consume-fn FIRST and
      only then `(reset! done? true)` — so a downstream exception during the
      message_stop terminal processing (a structured-output emission or the
      `:done` consume-fn, e.g. a statechart dispatch failure inside
      make-provider-event-consumer's `:done` → `:turn/done` send) propagates
      to the outer `(catch Exception e (when-not @done? ...))` with `done?`
      still false and emits a SECOND `:error` terminal — the exact
      double-terminal class reviews 43/44/46 eliminated on every other
      terminal path, contradicting the spec's `OnceDoneNoFurtherEvent`
      invariant ("no further Emit(StreamEvent) once stream.done") and the
      CHANGELOG's "exactly one terminal event per stream" wording. The
      review-48 `emit-terminal-done!` extraction (the EOF-level flush) keeps
      this ordering (emissions → consume → `(reset! done? true)`), so the
      fix must land in the shared helper too, not just the branch. Fix:
      move `(reset! done? true)` to the TOP of the terminal emission — in
      the `message_stop` branch AND the review-48 `emit-terminal-done!`
      helper, before the structured-output emissions and the `:done`
      consume — mirroring the `message_delta` branch; add a stream test
      whose consume-fn throws on the `:done` event (message_stop terminal
      path) asserting exactly one terminal event is emitted and no second
      `:error` reaches the consumer; note the ordering in the
      `MessageStopEmitsDoneWithUsage` guidance in
      `spec/anthropic-provider.allium`.
      → Resolved: `emit-terminal-done!` (the shared helper used by BOTH the
      `message_stop` branch and the review-48 EOF-level flush) now resets
      `done?` FIRST — `(reset! done? true)` at the top, before the
      structured-output-result emissions and the `:done` consume — mirroring
      the `message_delta`-with-`stop_reason` branch and every
      OpenAI-transport terminal emitter. A downstream exception during the
      terminal processing (here: the `:done` consume-fn throws, e.g. a
      statechart dispatch failure inside make-provider-event-consumer's
      `:done` → `:turn/done` send) propagates to the outer catch with
      `done?` already true, so the `(when-not @done? ...)` guard swallows it
      — no second `:error` terminal. New test
      `stream-anthropic-message-stop-done-consumer-exception-no-second-error-test`
      (anthropic_stream_termination_test.clj): consume-fn throws on the
      `:done` (asserts the throw actually happened), exactly one `:done`
      reaches the consumer, no `:error` at all, sequence
      `[:start :text-start :text-delta :text-end :done]` — verified to FAIL
      against the old emissions→consume→reset ordering (second `:error`
      emitted), PASS with the fix. Spec: `MessageStopEmitsDoneWithUsage`
      guidance notes the done?-first ordering (and that the review-48
      `AnthropicTransportCloseEmitsDoneIfNotTerminal` shares the helper);
      CHANGELOG `Fixed` entry added.

## Follow-ups (implementation review 50, 2026-08-09)

- [x] `stream-anthropic`'s terminal emitters never emit a `:start` event
      when the stream never received `message_start` — the only
      three-transport asymmetry left in the review-48 EOF-level flush.
      `:start` is emitted only inside the `message_start` case branch, and
      `stream-anthropic` has no `started?` tracking; `emit-terminal-done!`
      (shared by the `message_stop` branch and the EOF flush) and the
      `"error"` SSE branch emit `:done`/`:error` with no preceding `:start`.
      The sibling transports both emit `:start` first when not started:
      `emit-chat-completion-finish!` (`stream-openai`) and the codex EOF
      flush's `emit-codex-start!` both use a `compare-and-set!` on a
      `stream-started?` atom. Reachable on any 200 response whose body EOFs
      before `message_start` (empty body, truncated body, or a malformed
      stream starting with `message_stop`/`"error"`): the anthropic path
      then emits `[:done]`/`[:error]` while openai/codex emit
      `[:start :done]`/`[:start :error]`. Benign today (the consumer's
      `:start` handler is a no-op and the turn statechart is already past
      `:idle` via the turn-level `:turn/start` sent by
      `create-live-turn-context`, so the final assistant message is
      identical on all three transports) — but it is a genuine
      cross-transport inconsistency in the exact class this task has
      repeatedly treated as actionable ("mirror the codex transport",
      reviews 13/37/48), and `stream-anthropic-eof-flush-emits-done-test`
      (review 48) covers only the message_start-present path. Fix: add a
      `started?` atom to `stream-anthropic` (set in the `message_start`
      branch) and emit `:start` in `emit-terminal-done!`/the `"error"`
      branch when not started, mirroring `emit-chat-completion-finish!`/
      `emit-codex-start!`; or explicitly document why the anthropic
      transport intentionally omits it. Add a stream test for a stream that
      EOFs with no `message_start` (empty body) asserting the emitted event
      sequence (and the same for the `"error"`-without-`message_start`
      case).
      → Resolved (started?-tracking option, mirroring the siblings):
      `stream-anthropic` now tracks `started?` (atom alongside `done?`, set
      via a `compare-and-set!` in a new `emit-start!` helper) and emits
      `:start` once before the terminal when the stream never received
      `message_start` — `emit-terminal-done!` (shared by the `message_stop`
      branch and the review-48 EOF flush) and the `"error"` SSE branch both
      call `emit-start!` after the `done?` reset, mirroring
      `emit-chat-completion-finish!`'s ordering — so a 200 whose body EOFs
      before `message_start` (empty/truncated body, or a malformed stream
      starting with `message_stop`/`"error"`) now yields
      `[:start :done]`/`[:start :error]` like the openai/codex siblings
      instead of `[:done]`/`[:error]`: the last three-transport asymmetry in
      the review-48 EOF-level flush. Two new stream tests
      (`stream-anthropic-eof-flush-no-message-start-emits-start-then-done-test`
      — empty-body AND message_stop-first blocks — and
      `stream-anthropic-error-without-message-start-emits-start-then-error-test`)
      assert the emitted sequences; both verified to FAIL against the
      pre-fix code (events were `[:done]`/`[:error]`) and PASS with the fix.
      `MessageStartEmitsStartEvent` + the terminal/error rule guidance in
      `spec/anthropic-provider.allium` updated; CHANGELOG `Fixed` entry;
      design.md revision note + AC updated.
- [x] The review-48 `"redacted_thinking"` skip is explicit in
      `content-block-start-event`/`content-block-stop-event` (dedicated
      `"redacted_thinking"` branches returning nil, guarded by
      `consume-event!`) but IMPLICIT in `content-block-delta-event`:
      `stream_events.clj`'s `content-block-delta-event` has no
      `"redacted_thinking"` branch — the type falls through to the default
      text branch and returns nil only because `redacted_thinking_delta`
      currently carries no `:text` key (it carries `:data`). The skip
      therefore depends on the delta's current shape: if Anthropic ever
      sends a `redacted_thinking_delta` that includes a `:text` key (or
      renames the payload field), the block would emit a `:text-delta` with
      no `:text-start` — a phantom text delta for a block whose start/stop
      are skipped (unbalanced block events; the accumulator's
      `note-content-delta!` would open a block at an unbegun index while
      start/stop stay nil). The implementation.md review-48 entry and the
      `ContentBlockStopEmitsTypedEndEvent` spec guidance ("redacted_thinking
      _delta events are skipped by content-block-delta-event (no :text in
      the delta)") document the skip as a fact, not as the implicit
      fall-through it is. Fix: add an explicit `"redacted_thinking"` branch
      to `content-block-delta-event` returning nil (mirroring the start/stop
      branches), making the skip symmetric and shape-independent; optionally
      extend `redacted-thinking-block-not-mislabeled-as-text-test` with a
      `redacted_thinking_delta` carrying a `:text` key to prove the
      explicit skip.
      → Resolved: `content-block-delta-event` (`stream_events.clj`) now has
      an explicit `"redacted_thinking"` branch returning nil, mirroring the
      start/stop branches — the skip is symmetric and shape-independent
      (previously the type fell through to the default text branch and
      returned nil only because `redacted_thinking_delta` carries no
      `:text`; a future delta with a `:text` key would have emitted a
      phantom `:text-delta` for a block whose start/stop are skipped,
      opening an unbalanced block in the accumulator's `note-content-delta!`
      at an unbegun index). `redacted-thinking-block-not-mislabeled-as-text-test`
      extended with a `redacted_thinking_delta` carrying a `:text` key
      (`"should-not-leak"`) proving no `:text-delta` — verified to FAIL
      against the pre-fix fall-through (the `:text` key leaked a
      `:text-delta`, failing the existing "no :text-delta" assertion) and
      PASS with the explicit branch. `ContentBlockStopEmitsTypedEndEvent`
      guidance in `spec/anthropic-provider.allium` updated (the delta skip
      is now the explicit branch, not the shape dependency);
      `stream_events.clj` docstring updated.

## Follow-ups (implementation review 51, 2026-08-09)

- [x] Turn statechart has NO terminal transitions from its initial `:idle`
      state — `components/turn-statechart/src/psi/turn_statechart/chart.clj`'s
      `:idle` accepts only `:turn/start`; `:turn/error` and `:turn/done` are
      silently DROPPED there (verified live 2026-08-09 via
      `sp/process-event!`: `enabled transitions => #{}`, phase stays
      `:idle`, `done-p` never delivered). Not reachable through the current
      live-turn path — `create-live-turn-context` (turn-runtime/core.clj,
      used by every prompt execution path via
      `execute-prepared-request!`) sends the turn-level `:turn/start`
      before the provider stream starts, so the statechart is past `:idle`
      when provider events arrive (the review-50 item-1 note relies on
      this) — but it is a latent structural gap in the component whose core
      invariant the task's review-43/44/46/48 CHANGELOG entries claim
      ("exactly one terminal event per turn"): any direct
      `create-turn-context` consumer (tests, embeddings, a future turn path
      that skips the turn-level `:start`) that feeds a provider
      `:error`/`:done` as the first event gets a silent drop, `done-p`
      never delivered, and only the 20-minute `llm-stream-idle-timeout-ms`
      ends the turn — and the timeout branch's own
      `(turn-sc/send-event! :turn/error ...)` is ALSO dropped from `:idle`
      (harmless today because the timeout branch returns the result map
      directly, but the statechart never records the terminal phase). Fix
      (cheap, defense-in-depth): add `:turn/error` → `:error` and
      `:turn/done` → `:done` transitions to the `:idle` state (mirroring
      the `:text-accumulating`/`:tool-accumulating` transitions) so
      terminal events are accepted from ANY state; add a statechart unit
      test sending `:turn/error`/`:turn/done` from the initial state and
      asserting the terminal phase + `done-p` delivery.
      → Resolved: `chart.clj`'s `:idle` state now carries `:turn/done` →
      `:done` and `:turn/error` → `:error` transitions (with the same
      `:on-done`/`:on-error` dispatch scripts as the accumulating states),
      so terminal events are accepted from ANY state and the terminal phase
      is always recorded. New `terminal-from-idle-test` in
      `core_test.clj` sends `:turn/done`/`:turn/error` from the INITIAL
      state and asserts the terminal phase (`:done`/`:error`) AND `done-p`
      delivery (deref'd with a timeout) — both previously failed (silent
      drop, phase stayed `:idle`, `done-p` never delivered). Full
      turn-statechart suite green (15 tests / 75 assertions, was 13/69).
      Design AC + CHANGELOG `Fixed` entry updated.
- [x] `emit-chat-error!` (openai chat-completions) http-status extraction
      omits the top-level `:http_status` location the review-47-aligned
      anthropic `"error"` branch reads: `emit-chat-error!` checks
      `(:status chunk)` / `[:error :status]` / `[:error :http_status]`
      only, while `stream-anthropic`'s `"error"` branch checks those three
      PLUS top-level `(:http_status event-data)` (the review-47 claim that
      the anthropic branch "mirrors emit-chat-error!" is a one-directional
      alignment — the anthropic branch is a superset). An OpenAI-compatible
      endpoint emitting a mid-stream error chunk with the status under a
      TOP-LEVEL `http_status` key (`{"http_status": 529, "error": {...}}`)
      loses its status on `:openai-completions`: the `:error` event carries
      no numeric `:http-status`, downstream `retry-error?` /
      `provider-error-kind` classify a transient 5xx/overload as `:unknown`
      and it is not auto-retried — the exact review-47/23 class the
      anthropic branch now handles. Fix: add `(:http_status chunk)` to the
      `some` locations in `emit-chat-error!` and a test with a top-level
      `http_status` on an error chunk asserting the `:error` event's
      `:http-status`.
      → Resolved: `emit-chat-error!`'s `some` locations now include
      `(:http_status chunk)` (the fourth location the anthropic "error"
      branch reads), so a mid-stream error chunk with the status under a
      top-level `http_status` key keeps its numeric `:http-status` and
      downstream `retry-error?`/`provider-error-kind` classify a transient
      5xx/overload as retryable instead of `:unknown`. New
      `completions-sse-error-top-level-http-status-kept-test` (moved to
      `openai_completions_stream_test.clj` for the 800-line file-length
      gate; `openai_completions_test.clj` was 817 lines with it in place)
      streams `{"http_status": 529, "error": {...}}` and asserts the
      `:error` event's `:http-status` is 529 (verified FAIL pre-fix:
      `:http-status` nil, `(status ...)` suffix absent — classified
      `:unknown`; PASS with the fix). `CompletionsSseErrorChunkEmitsErrorAndTerminates`
      guidance in `spec/openai-provider.allium` updated; CHANGELOG `Fixed`
      entry.
- [x] `stream-openai-codex`'s HTTP-error path drops the response headers /
      body from the surfaced `:error` event — the only transport that does:
      `(let [{:keys [error-message http-status]} (transport/response->error
      response)] (emit-codex-error! ... error-message http-status))`
      destructures AWAY `:headers`, `:body-text` and `:body` even though
      `emit-codex-error!`'s 4-arity accepts `headers` (used by the
      SSE `response.failed`/`error` branches), while the anthropic and
      openai HTTP-error paths surface the full error map
      (`(emit-error! ... (anthropic-error/response->error response request))`
      / `(transport/emit-error! ... (transport/response->error response))`
      include `:headers`). A codex HTTP error (e.g. a 401/429/500 from the
      ChatGPT backend or a custom codex endpoint) therefore surfaces an
      `:error` with no `request-id`-style headers for diagnostics, while
      the same status on the sibling transports keeps them — the
      cross-transport error-surface inconsistency in the exact class this
      task's reviews 13/43/47 aligned ("mirror the sibling transports").
      Fix: pass the full error map (headers/body-text) through to
      `emit-codex-error!` on the HTTP-error branch (the 4-arity already
      supports it), and add a stream test asserting a codex HTTP-error
      response's headers appear on the `:error` event.
      → Resolved: the HTTP-error branch now destructures
      `{:keys [error-message http-status headers]}` from
      `transport/response->error` and calls `emit-codex-error!`'s 4-arity
      with `headers` — the full error map (headers/body-text/body) flows
      through, so a codex HTTP error (401/429/500 from the ChatGPT backend
      or a custom codex endpoint) keeps its `request-id`-style headers on
      the `:error` event for diagnostics, mirroring the sibling transports
      (and the codex SSE `response.failed`/`error` branches). New
      `codex-http-error-surfaces-response-headers-test` in
      `openai_codex_test.clj` streams a 429 with
      `{"x-request-id" "req_oai_429" "retry-after" "5"}` and asserts both
      headers appear on the `:error` event (verified FAIL pre-fix: no
      `:headers` key, and the error message lacked the `[request-id ...]`
      suffix; PASS with the fix — message now includes
      `[request-id req_oai_429]`). `StreamErrorsNormalized` +
      `CodexStreamFailurePreservesRetryMetadata` guidance and the
      `StreamEvent.headers` field in `spec/openai-provider.allium` updated;
      CHANGELOG `Fixed` entry.

## Follow-ups (implementation review 52, 2026-08-09)

- [x] The review-50 `:start`-before-terminal fix is incomplete on the ERROR
      paths of the two OpenAI transports: `emit-chat-error!`
      (`:openai-completions`) and `emit-codex-error!`
      (`:openai-codex-responses`) emit `[:error]` with NO preceding `:start`
      when the stream errors before producing any output event, while the
      review-50-fixed anthropic `"error"` branch emits `[:start :error]`
      (verified against the committed code — neither error emitter calls
      `emit-stream-start!`/`emit-codex-start!`; the review-50 resolution's
      "mirroring the openai/codex siblings" claim is false for the error
      path: the siblings' error emitters never emitted `:start`). The
      existing error tests don't catch it because both start with a
      content/role chunk that triggers `:start` via the non-error path
      (`completions-sse-error-event-emits-error-and-terminates-test` starts
      with `{:choices [{:delta {:role "assistant"}}]}`, and
      `codex-error-then-trailing-output-text-delta-no-text-delta-test`
      starts with `response.output_text.delta`) — neither exercises an
      error-FIRST stream. Within the anthropic transport the same gap
      remains on the `message_delta`-with-`stop_reason` terminal: that
      branch emits `[:done]` without `:start` (review 50 tested
      `message_stop`-first and empty-body, not `message_delta`-first), so
      `message_delta`-first yields `[:done]` while `message_stop`-first
      yields `[:start :done]`. Fix: emit `:start` once in
      `emit-chat-error!`/`emit-codex-error!` (compare-and-set on the
      existing `stream-started?`/`started?` atoms) and in the anthropic
      `message_delta` terminal branch (or route it through a shared
      start-first terminal helper), plus stream tests for error-first
      streams on all three transports (and a `message_delta`-first block on
      the anthropic test) asserting the emitted sequences — the same
      three-transport consistency class review 50 treated as actionable.
      → Resolved: `emit-chat-error!` (`:openai-completions`) and
      `emit-codex-error!` (`:openai-codex-responses`) now emit `:start`
      first (compare-and-set on `stream-started?`/`started?`) when the
      stream never emitted it — for codex this also covers the HTTP-error
      and exception paths that share the emitter; the anthropic
      `message_delta`-with-`stop_reason` terminal branch emits `:start`
      first when the stream never received `message_start`. Tests:
      `completions-sse-error-first-stream-emits-start-then-error-test`,
      `codex-error-first-stream-emits-start-then-error-test`,
      `stream-anthropic-message-delta-first-emits-start-then-done-test`,
      and the existing codex HTTP-error / account-id / capture tests updated
      to `[:start :error]`. CHANGELOG + both provider specs + design.md
      revision note updated.
- [x] The review-51 codex HTTP-error headers fix left the sibling CATCH
      block dropping the same data: `stream-openai-codex`'s
      `(catch Exception e (let [{:keys [error-message http-status]}
      (transport/exception->error e)] (emit-codex-error! ...)))` still
      destructures away `:headers`/`:body-text`/`:body` and calls the
      3-arity (headers nil), even though `transport/exception->error`
      returns them when the exception's ex-data carries them and
      `emit-codex-error!`'s 4-arity accepts headers — the exact class
      review 51 just fixed on the HTTP-error branch. Reachability is lower
      (non-HTTP stream exceptions rarely carry response headers), but the
      fix is the same one-line destructure change; pass `headers` through
      (and consider `:body-text` via the 4-arity's error map) for
      consistency with the review-51-fixed branch.
      → Resolved: `stream-openai-codex`'s catch block now destructures
      `:headers` from `transport/exception->error` and passes them to
      `emit-codex-error!`'s 4-arity — an exception whose ex-data carries
      response headers keeps them on the `:error` event for diagnostics.
      New test `codex-catch-block-surfaces-exception-headers-test`
      (redef'd `parse-sse-line` throwing with `:status`/`:headers` in
      ex-data → `:error` carries `x-request-id`, status, and the
      request-id-suffixed message).
- [x] Codex double-captures mid-stream SSE errors while the other two
      transports capture once: `handle-codex-event!` captures the raw
      `response.failed`/`error` event at its top, then `emit-codex-error!`
      captures the CONSTRUCTED `:error` event again — two
      `:on-provider-response` callbacks per codex mid-stream error. The
      anthropic `"error"` branch and openai `emit-chat-error!` capture only
      the raw SSE line (the constructed `:error` with normalized
      `:http-status`/`:headers` is never in the capture payload). The
      capture payloads therefore differ per transport for the same error
      class (codex: raw + normalized; anthropic/openai: raw only) — the
      capture-consistency class reviews 7/11/13/19/37 treated as
      actionable. Decide: drop the raw-event capture from the codex
      mid-stream-error path (keep the constructed capture, matching the
      transports' HTTP-error-path behavior), or align the other transports
      to also capture the constructed error; add a capture-count/payload
      assertion to the codex SSE error tests.
      → Resolved: `handle-codex-event!` skips the raw capture for the error
      event types (`response.failed`/`error`) — only the CONSTRUCTED
      `:error` (with normalized `:http-status`/`:headers`) is captured via
      `emit-codex-error!`, matching the codex HTTP-error path; non-error
      lines are still captured raw. Chose "drop the raw capture" (the
      transports' HTTP-error-path behavior) over aligning the other
      transports to the constructed capture — the codex transport now
      captures exactly one `:on-provider-response` per mid-stream error,
      like anthropic/openai capture one raw line. New test
      `codex-mid-stream-error-captured-once-test` (2 captures: raw
      non-error line + constructed error; the raw `response.failed` line
      never captured).

## Follow-ups (implementation review 53, 2026-08-09)

- [x] Full-`bb test` is RED on the state being closed with a NEW
      un-inventoried flake instance: a full randomized-suite run (seed
      281542343, 2026-08-09) failed
      `psi.turn-runtime.response-mode-test/
      execute-prepared-request-streaming-error-event-provider-headers-drive-
      retry-test` — `(= 2 @attempts*)` actual 3 (the final
      result/retry-metadata assertions all passed, so the retry pipeline
      converged, but the stream attempt ran three times instead of two).
      The test passes 5/5 in isolation, and `components/turn-runtime/` has
      ZERO diff across the whole task commit range (71d4821bf^..HEAD) —
      the same pre-existing attempt-count race class as the inventoried
      `psi.turn-runtime.response-mode-retry-test/
      execute-prepared-request-streaming-retry-discards-failed-partial-
      output-test` flake (2 vs 53), but a NEW instance in a DIFFERENT
      namespace (`response_mode_test.clj`, not `response_mode_retry_test.
      clj`) that the flake inventory does not name — the inventory entries
      (reviews 5/14/17) record only the sibling test and
      `prompt-provider-retry-after-tool-result...`, so the design AC
      "`bb test` green" is violated on this state exactly like the
      inventoried instances. Fix: add this test to the flake inventory
      with the seed + isolated-pass + no-diff evidence (the review-14/17
      pattern), or harden the test (e.g. make the retry scheduling
      deterministic / assert the attempt count only after the pipeline
      fully settles) so a full-suite run can be green.
      → Resolved: added as the 6th flake-inventory entry in
      implementation.md with the full evidence per the review-14/17
      pattern — seed 281542343, isolated pass re-verified 2026-08-09
      (1 test / 6 assertions green via
      `bb clojure:test:scry --namespace psi.turn-runtime.response-mode-test
      --var ...execute-prepared-request-streaming-error-event-provider-
      headers-drive-retry-test`), and `git diff 71d4821bf^..HEAD --stat --
      components/turn-runtime/` empty (zero diff across the whole task
      commit range — the component was never touched by this task). Chose
      the inventory option (the established pattern for all five prior
      entries, all inventory-only) over hardening the test: the test file
      lives in `components/turn-runtime/` with zero diff across the task
      and the race is definitionally pre-existing, so a test-timing change
      would be out-of-scope churn on a task that has repeatedly treated
      the inventory as the resolution for this exact class.
- [x] The anthropic + openai chat-completions CATCH blocks remain
      `:start`-less on a pre-output stream-read exception — the last gap
      in the review-50/52 `:start`-before-terminal class: `stream-anthropic`'s
      `(catch Exception e (when-not @done? ... (consume-fn err)))` and
      `stream-openai`'s `(catch Exception e (when-not @done?
      (transport/emit-error! ...)))` emit `[:error]` with NO preceding
      `:start` when the exception fires before any output event (e.g. a
      connection reset on the first read — verified empirically:
      `[:error]` on both transports), while every IN-BAND terminal/error
      emitter now emits `[:start ...]` (the review-50 anthropic "error"
      branch, the review-52 in-progress `emit-chat-error!`/
      `emit-codex-error!` fixes, and the codex catch block, which gets
      `:start` for free by routing through `emit-codex-error!`'s new
      `emit-codex-start!`). Review 52 item 1 scoped the fix to the named
      in-band emitters + the anthropic `message_delta` branch, so the
      anthropic/openai catch blocks are not covered. Fix: emit `:start`
      once (compare-and-set on `started?`/`stream-started?`) in both catch
      blocks before the `:error` (or route them through the `:start`-aware
      emitters), + stream tests throwing on the first stream read
      asserting `[:start :error]` on both transports — the same
      three-transport consistency class reviews 50/52 treated as
      actionable.
      → Resolved: both catch blocks now emit `:start` once before the
      `:error` — `stream-anthropic`'s catch calls the shared top-level
      `emit-start!` helper (moved out of the letfn, which the catch is
      outside of; 4 existing call sites updated to the two-arg form) and
      `stream-openai`'s catch calls `emit-stream-start!`
      (stream-started? compare-and-set) before `transport/emit-error!` — so
      a first-read exception yields `[:start :error]` on both transports,
      closing the last `:start`-before-terminal gap (the codex catch
      already gets `:start` via `emit-codex-error!`'s review-52
      `emit-codex-start!`). New tests:
      `stream-anthropic-first-read-exception-emits-start-then-error-test` +
      `completions-first-read-exception-emits-start-then-error-test`
      (redef'd `http/post` throwing before any response);
      `stream-anthropic-error-includes-status-and-request-id-test` updated
      (it throws from `http/post` — the exact first-read scenario — and now
      expects `[:start :error]`). CHANGELOG `Fixed` entry + both provider
      specs + design.md revision note/AC updated.

## Follow-ups (implementation review 54, 2026-08-09)

- [x] `stream-anthropic`'s CONTENT-BLOCK branches never emit `:start` — the
      non-terminal half of the review-50 `:start`-before-first-event class
      (reviews 50/52/53 fixed the terminal/error/catch emitters only, and
      review 53's closing note claimed "the last :start-before-terminal
      gap" — scoped to terminals). Verified empirically against the
      committed tree: a malformed/non-conforming stream whose FIRST event
      is `content_block_start` (no `message_start`) emits
      `[:text-start :text-delta :text-end :start :done]` — the first
      content event has NO preceding `:start`, and `:start` appears only at
      the terminal, AFTER the content events. Both sibling transports emit
      `:start` before the first content event: `:openai-completions`
      (`emit-started-event!`/`emit-stream-start!` on any role/content/
      tool emission) and `:openai-codex-responses`
      (`emit-codex-start!`/`emit-codex-started-event!` on output_item.
      added/output_text.delta). The `content_block_delta`/`content_block_stop`
      branches have the same gap (a delta-first or stop-first stream emits
      `[:text-delta ...]`/`[:text-end ...]` with no `:start`). Benign for
      the consumer (`:start` is a no-op handler; the statechart is past
      `:idle` via the turn-level `:turn/start`) but the same benign-yet-real
      three-transport inconsistency class reviews 48/50/52/53 repeatedly
      treated as actionable — reachable on any non-conforming
      Anthropic-compatible endpoint, and DeepSeek's streaming path remains
      unverified. Fix: emit `:start` once (`started?` compare-and-set,
      mirroring `emit-terminal-done!`) before the first content-block event
      when the stream never received `message_start`, + a
      `content_block_start`-first stream test asserting `[:start :text-start
      ...]` (FAIL pre-fix / PASS post-fix, per the established pattern).
      → Resolved: the content-block branches (content_block_start/delta/stop) now emit :start once (shared request-support/emit-start! compare-and-set — a no-op when message_start already fired) before the first content-block event when the stream never received message_start, mirroring the openai/codex siblings' emit-started-event!/emit-codex-started-event!. New test `stream-anthropic-content-block-start-first-emits-start-test` asserts a content_block_start-first stream emits [:start :text-start :text-delta :text-end :done] (FAIL pre-fix: :start appeared only at the terminal, AFTER the content events).
- [x] `stream-anthropic`'s `content_block_delta`/`content_block_stop` for an
      UNKNOWN index (no prior `content_block_start` — a stream that omits
      start events, reuses indices, or reorders deltas/stops ahead of
      starts) emit unbalanced `:text-delta`/`:text-end`: `(:type block-info)`
      is nil for a missing index, which falls through `content-block-delta-event`/
      `content-block-stop-event`'s default TEXT branch. Verified
      empirically: a `content_block_delta` at index 5 with no prior start
      yields `[:start :text-delta :done]` — a phantom `:text-delta` for a
      block that never had a `:text-start`, which the turn accumulator's
      `note-content-delta!` opens at an unbegun index — the exact
      "unbalanced block events" harm the review-48 redacted_thinking fix
      eliminated for skipped TYPES, still open for MISSING indices. Sibling
      transports do not emit unbalanced events: `:openai-completions`
      balances (tool-call path creates an entry and emits
      `:toolcall-start` before the delta via `ensure-chat-tool-entry!`/
      `start-chat-tool-if-ready!`), and `:openai-codex-responses` SKIPS an
      unresolved index (`response.function_call_arguments.delta` guards on
      `(number? idx)`). Fix: nil-guard the delta/stop branches on
      `block-info` (skip events for unknown indices, mirroring the codex
      skip — `consume-event!` already nil-guards) or balance them with a
      synthetic start; + a stream test with a delta/stop at an index whose
      start was never received.
      → Resolved: the content_block_delta/content_block_stop branches are now nil-guarded on block-info (skip events for an index whose content_block_start was never received, mirroring the codex sibling's skip of an unresolved index and the review-48 redacted_thinking skip) — no phantom :text-delta/:text-end for a block that never had a :text-start (the turn accumulator's note-content-delta! no longer opens a block at an unbegun index). New test `stream-anthropic-unknown-index-content-block-skipped-test` asserts a delta/stop-first stream emits [:start :done] with no unbalanced text events (FAIL pre-fix: [:start :text-delta :done]).
- [x] The `:start`-once emitter is now triplicated across the three
      transports — `stream-anthropic`'s `emit-start!` (review 50), the
      `:openai-completions` `emit-stream-start!`, and the
      `:openai-codex-responses` `emit-codex-start!` are byte-identical
      `(when (compare-and-set! started false true) (consume-fn {:type
      :start}))` one-liners modulo atom/param naming, introduced separately
      across reviews 50/52/53. This is the exact triplication class review
      14 extracted into the shared `providers/request_support.clj` (key
      resolution, `no-auth?`, capture redaction) and review 16 (the openai
      api-key config), with the documented rationale that "the copies
      repeatedly drifted — reviews 9/10/13 reconciled spec/behavior
      mismatches between them". The three `:start` emitters are the newest
      instance: a future `:start`-semantics change (e.g. carrying a payload,
      or a different once-guard) would have to land in three places with
      drift risk. Fix: extract a shared
      `request-support/emit-start! [consume-fn started-atom]` and use it in
      all three transports (pure refactor, no behavior change — all three
      call sites are already identical), or document the intentional
      per-transport duplication in request_support.clj's ns docstring.

      → Resolved: the three byte-identical :start-once emitters (anthropic emit-start!, chat-completions emit-stream-start!, codex emit-codex-start!) now delegate to a shared `request-support/emit-start! [consume-fn started?]` (compare-and-set once-guard), extracted into the review-14 request_support.clj namespace the triplication class exists to prevent; the per-transport private wrappers keep the transport-local names at the call sites. New `emit-start-once-test` in request_support_test.clj locks the once-guard contract directly (fires exactly once across call sites; a pre-set started? atom suppresses the emission entirely).
## Follow-ups (implementation review 55, 2026-08-09)

- [x] The review-48 EOF-level terminal flush leaves content blocks / tool
      calls OPEN at the terminal on two of the three transports — the
      accumulator receives `:done` with an unclosed block index (no
      phantom-or-unbalanced-block invariant, reviews 43/48/50), via the EOF
      path. `stream-anthropic`'s `emit-terminal-done!` and `stream-openai`
      chat-completions' EOF flush emit the terminal `:done` but never close
      blocks started and never stopped: a tool_use block whose
      `content_block_stop` never arrived, or a thinking/text block started
      but not stopped (stream truncated / non-conforming endpoint), leaves
      the turn accumulator with an OPEN block index when `handle-done!`
      finalizes — no `:toolcall-end`/`:thinking-end`/`:text-end` precedes
      the `:done`. Verified empirically (probe streams, FAIL pre-fix):
      anthropic `message_start` + `content_block_start` (tool_use) + EOF
      (no stop, no message_stop) → `[:start :toolcall-start :done]` (no
      `:toolcall-end`); anthropic `message_start` + `content_block_start`
      (thinking) + EOF → `[:start :thinking-start :done]` (no
      `:thinking-end`); openai chat-completions a `tool_calls` delta chunk +
      EOF (no finish_reason, no `[DONE]`) →
      `[:start :toolcall-start :toolcall-delta :done]` (no
      `:toolcall-end`). The codex transport BALANCES open tool calls at its
      EOF flush — `emit-codex-done!` doseqs `:toolcall-end` over
      `open-tool-indexes` (and chat-completions' own
      `finish-chat-chunk!` usage/finish_reason branches call
      `force-start-pending-chat-tools!` + `emit-chat-tool-ends!` — but the
      EOF-flush path does NOT), so the review-48 EOF flush is the last
      unbalanced-block class on this task's transports. Fix: track open
      block/tool indexes on both transports (anthropic: mirror codex's
      `open-tool-indexes` — conj on `content_block_start`, disj on
      `content_block_stop`, emit `:toolcall-end`/`:thinking-end`/`:text-end`
      for open indexes in `emit-terminal-done!` before the `:done`; openai:
      call `force-start-pending-chat-tools!` + `emit-chat-tool-ends!` in
      the EOF flush before the `:done`, reusing the existing helpers), +
      EOF-mid-tool / EOF-mid-thinking stream tests on both transports
      asserting the balancing events precede the `:done` (FAIL pre-fix /
      PASS post-fix per the established pattern). Reachable on any
      Anthropic-compatible endpoint that truncates a stream mid-block —
      DeepSeek's streaming path remains unverified (see next item).
      → Resolved: both transports now balance open blocks at the EOF terminal. Anthropic: stream-anthropic tracks OPEN content-block indices (open-blocks map, conj on content_block_start only when the start event was consumed, dissoc on content_block_stop) and emit-terminal-done! emits the matching :toolcall-end/:thinking-end/:text-end for each open index (sorted by index, shaped via the shared content-block-stop-event helper) before the :done — a truncated stream can no longer finalize the turn accumulator with an OPEN block index (the no-phantom-or-unbalanced-block invariant via the EOF path, mirroring codex's open-tool-indexes doseq). OpenAI chat-completions: the EOF flush now calls force-start-pending-chat-tools! + emit-chat-tool-ends! before the terminal :done, reusing the exact helpers the finish_chunk branches call — a tool_calls-delta-then-EOF stream closes its open tool call (a not-yet-started fragment is force-started so it is balanced). Tests (FAIL pre-fix / PASS post-fix, verified): `stream-anthropic-eof-balances-open-tool-block-test`, `stream-anthropic-eof-balances-open-thinking-block-test`, `stream-anthropic-eof-balances-open-text-block-test`, `stream-anthropic-eof-balances-multiple-open-blocks-in-index-order-test`, `completions-eof-balances-open-tool-call-test`, `completions-eof-balances-not-yet-started-tool-call-test`.
- [x] DeepSeek's STREAMING path is still unverified live — the task's
      longest-standing unverified item, and no step has ever asked for the
      verification even though the review-1 block was LIFTED (review 40:
      `DEEPSEEK_API_KEY` now set in env; the review-1 smoke test built
      psi's exact non-streaming request and POSTed it, HTTP 200 with a
      `thinking` content block — the streaming path was never exercised).
      Reviews 43-54 (and this review's EOF-balancing item) repeatedly
      hardened the streaming transports citing "DeepSeek's streaming path
      is unverified" as the reachability justification — EOF-level terminal
      flush (48), `:start`-before-terminal/first-event (50/52/53/54),
      mid-stream SSE error surfacing (43), usage on the `message_stop`
      terminal (47), and now open-block balancing at EOF — none verified
      against the actual endpoint. Fix (optional, like the review-1 smoke
      test): run a live STREAMING turn through `stream-anthropic` with the
      committed deepseek config (`.psi/models.edn` deepseek provider,
      `:adaptive-thinking true`, env key) and record: the actual SSE event
      sequence (does DeepSeek send `message_start`/`message_stop`, or
      deviate — content-block-first / truncated streams, which directly
      determines whether the malformed-stream hardening is reachable), the
      adaptive `output_config.effort` wire shape, and the usage payload
      (cache_read/cache_creation field names, review 2). Reconcile the
      observed sequence with the review-43-55 hardening assumptions; if
      DeepSeek conforms, note it; if it deviates, add the observed
      non-conformance to the docs' DeepSeek notes and to the relevant
      stream tests.

      → Resolved (live, 2026-08-09): executed a real STREAMING turn through `stream-anthropic` with the committed .psi/models.edn deepseek config (:adaptive-thinking true, /thinking high, env key). DeepSeek CONFORMS to the Anthropic stream shape — SSE sequence message_start (1) → content_block_start (2: thinking + text) → content_block_delta (20) → content_block_stop (2) → message_delta (usage) → message_stop (1); every block balanced (no truncated/open-block or missing-message_start stream), so the review-43-55 malformed-stream hardening is NOT triggered by DeepSeek's actual streaming path (it remains defensive for non-conforming endpoints only). Provider event sequence [:start :thinking-start :thinking-delta x17 :thinking-signature-delta :thinking-end :text-start :text-delta x2 :text-end :done :end_turn] with usage-with-cost (input 90, output 20, cache 0/0, total cost 1.82e-5). The adaptive wire shape (thinking.type "adaptive" + output_config.effort "high") was accepted with a thinking content block, and the usage payload carried the Anthropic-shaped cache_read_input_tokens/cache_creation_input_tokens fields (review-2 field-name assumption confirmed on the streaming path too; both 0 in the no-cache turn). One observed deviation: DeepSeek emits an extra mid-stream `ping` SSE event (not in Anthropic's event set), ignored harmlessly (no case branch → no-op, no error/hang) — documented in doc/custom-providers.md DeepSeek notes + locked by `stream-anthropic-ignores-deepseek-ping-events-test`.
## Follow-ups (implementation review 56, 2026-08-09)

- [x] The review-55 open-block balancing covers only the `:done` terminals
      (`message_stop` + the EOF flush, via the shared `emit-terminal-done!`)
      — the anthropic transport's OTHER finalization paths still leave the
      turn accumulator with OPEN content-block indices at the terminal:
      (a) the mid-stream `"error"` SSE branch emits `:error` with no
      balancing — a stream that started a thinking/tool_use block and then
      receives the review-43 mid-stream error (e.g. `overloaded_error`)
      yields `[:start :thinking-start :thinking-delta :error]` with the
      block still `:status :open` in turn-data's `:content-blocks`
      (exposed via the `:psi.turn/content-blocks` telemetry resolver) —
      the exact no-phantom-or-unbalanced-block invariant review 55 asserted
      "via the EOF path", still open via the error path; (b) the
      `message_delta`-with-`stop_reason` terminal emits its INLINE `:done`
      (reviews 44/52 restructured it separately from `emit-terminal-done!`)
      WITHOUT the open-blocks balancing the shared helper does — the two
      `:done` branches of the same transport now disagree (`message_stop`
      finalizes balanced, `message_delta`-with-`stop_reason` does not), so
      a non-conforming stream that sends `message_delta`-with-`stop_reason`
      while blocks are open finalizes with open blocks; (c) the catch block
      and the HTTP-error path route through `capture/emit-error!` with no
      balancing. Fix: emit the matching `:toolcall-end`/`:thinking-end`/
      `:text-end` for `@open-blocks` (the review-55 balancing, sorted by
      index, shaped via `content-block-stop-event`) before the `:error` in
      the `"error"` branch and before the `:done` in the `message_delta`
      terminal (or route the `message_delta` terminal through
      `emit-terminal-done!`), + stream tests (error-after-thinking-start /
      error-after-tool-start / message_delta-with-stop_reason-with-open-
      blocks) asserting the balancing events precede the terminal (FAIL
      pre-fix / PASS post-fix). Also fix the overclaiming wording: the
      CHANGELOG Fixed entry ("all three transports now emit no unbalanced
      or open block events at the terminal") and the review-55 steps.md
      resolution ("never finalizes with an OPEN block index") are true
      only for the `:done`/EOF paths.
      → Resolved: the review-55 balancing is extended to EVERY remaining
      terminal path via the shared `balance-open-blocks!` helper (extracted
      from `emit-terminal-done!`'s inline doseq): the mid-stream `"error"`
      branch balances `@open-blocks` before the `:error`, the
      `message_delta`-with-`stop_reason` terminal balances before its
      inline `:done` (keeping the real `stop_reason`, not routing through
      `emit-terminal-done!`'s hardcoded `:stop`), and the catch block
      balances before the `:error` — the HTTP-error path needs no balancing
      (it fires before any SSE line has been consumed, so `@open-blocks`
      is always empty; documented at the call site). The two `:done`
      branches and every `:error` branch now balance identically. New
      tests (FAIL pre-fix / PASS post-fix, verified):
      `stream-anthropic-error-after-thinking-start-balances-open-block-test`,
      `stream-anthropic-error-after-tool-start-balances-open-block-test`,
      `stream-anthropic-message-delta-stop-reason-with-open-blocks-balances-test`,
      `stream-anthropic-catch-balances-open-block-before-error-test`
      (anthropic_stream_termination_test.clj; the catch test redefs
      `http/post` to throw after a `content_block_start` was consumed).
      Overclaiming wording fixed: CHANGELOG Fixed entry now reads "no
      unbalanced or open block events at any terminal — `:done` or
      `:error`", `TerminalEmitsEndEventsForOpenBlocks` spec rule's `when`
      now includes the `"error"` event and the `message_delta`-with-
      `stop_reason` terminal (guidance updated), and the design.md
      revision note/AC scoped the invariant to every terminal.
- [x] The review-55 open-tool balancing on the OpenAI transports covers
      only the `:done` paths — the chat-completions `finish_chunk`/usage
      branches and the EOF flush call `force-start-pending-chat-tools!` +
      `emit-chat-tool-ends!`, and codex's `emit-codex-done!` doseqs
      `open-tool-indexes` — but every ERROR path emits the terminal with
      open tool calls: (a) `emit-chat-error!` (`:openai-completions` —
      mid-stream error chunk) and the `transport/emit-error!` HTTP-error /
      catch paths do NOT call `force-start-pending-chat-tools!`/
      `emit-chat-tool-ends!`, so a tool_calls-delta-then-error-chunk stream
      yields `[:start :toolcall-start :toolcall-delta :error]` with the
      tool call open at `handle-error!`; (b) `emit-codex-error!` (all codex
      error paths — `response.failed`/`error` SSE events, the HTTP-error
      branch, the catch block) destructures only `done?`/`started?` and
      never balances `open-tool-indexes` (only `emit-codex-done!` does), so
      a function_call-output_item-then-`response.failed` stream finalizes
      with an open tool call. Fix: balance open tool calls before the
      `:error` on the error emitters (reuse the existing
      `force-start-pending-chat-tools!`/`emit-chat-tool-ends!` helpers /
      `emit-codex-done!`'s doseq), + error-after-tool-start stream tests on
      both transports asserting the `:toolcall-end` precedes the `:error`
      (FAIL pre-fix / PASS post-fix), and extend the same CHANGELOG
      "no unbalanced or open block events at the terminal" wording (the
      claim is currently scoped to the `:done` paths only).
      → Resolved: the error emitters now balance open tool calls before
      the `:error` — `emit-chat-error!` and the `stream-openai` catch
      block call `force-start-pending-chat-tools!` +
      `emit-chat-tool-ends!` (the exact helpers the finish_chunk branches
      and the EOF flush use), and `emit-codex-error!` (shared by EVERY
      codex error path) now destructures `open-tool-indexes` +
      `tool-args-by-index` and doseqs `:toolcall-end` over the open
      indexes before the `:error` (mirroring `emit-codex-done!`'s doseq;
      also resets the two atoms). The HTTP-error paths need no balancing
      (no SSE line has been consumed before they fire, so no tool call is
      open; documented at the call sites). New tests (FAIL pre-fix / PASS
      post-fix, verified):
      `completions-error-after-tool-start-balances-open-tool-call-test`
      + `completions-catch-balances-open-tool-call-before-error-test`
      (openai_completions_stream_test.clj) and
      `codex-error-after-tool-start-balances-open-tool-call-test`
      (openai_codex_test.clj — `response.failed` after a
      function_call output item). CHANGELOG wording extended ("no
      unbalanced or open block events at any terminal — `:done` or
      `:error`"); new `ErrorPathEmitsToolCallEndsForOpenToolCalls` +
      `CodexErrorEmitsToolCallEndsForOpenToolCalls` spec rules in
      openai-provider.allium; design.md revision note/AC updated.

## Follow-ups (implementation review 57, 2026-08-09)

- [x] The non-streaming `execute-anthropic` response mapping drops
      `tool_use` blocks entirely — `response->assistant-message` builds the
      assistant message `:content` via `text-content-blocks`, which keeps
      only `"text"` blocks, so a non-streaming response whose `:content`
      contains a `tool_use` block (Anthropic's tool-call shape, fully
      supported by DeepSeek per its compat table) yields an assistant
      message with NO `:tool-call` block while `:stop-reason :tool_use` is
      preserved (probe-verified: response `{:content [{:type "tool_use"
      :id "toolu_01" :name "get_weather" :input {}} {:type "text" :text
      "Let me check"}] :stop_reason "tool_use"}` → assistant `{:content
      [{:type :text :text "Let me check"}] :stop-reason :tool_use}` — the
      tool call is silently lost). The turn-runtime's
      `classify-assistant-message`/`extract-tool-calls` then finds no
      `:tool-call` block, classifies the turn `:turn.outcome/stop` instead
      of `:turn.outcome/tool-use`, and the tool call NEVER executes — a
      silent functional loss (no error, no retry), reachable on the newly
      shipped DeepSeek provider (and every `:anthropic-messages` custom
      provider) via `response-mode :non-streaming` sessions with tools
      enabled. This is inconsistent with BOTH the `:openai-completions`
      sibling — `completion-message->content` maps `:tool_calls` into
      `:tool-call` blocks (tool calls survive on the non-streaming openai
      execute path) — and the anthropic transport's own STREAMING path
      (the accumulator's `tool-content-blocks` keeps tool calls in the
      final content); the non-streaming execute path is the only path that
      drops them. The reviews 43-56 hardened the streaming transports
      citing "reachable on the newly shipped DeepSeek provider" — the
      non-streaming execute response mapping was never reviewed for tool
      handling (the only execute-path reviews were the 400-fallback
      asymmetry, review 45, and the usage-mapping notes, reviews 47/48;
      no test covers `execute-anthropic` with a `tool_use` response — all
      `:execute` anthropic tests are structured-output/usage/400-only).
      Fix: map `tool_use` blocks to `:tool-call` content blocks in
      `response->assistant-message` (mirroring `completion-message->content`'s
      `tool-call-block` shape — id/name/arguments — and/or the streaming
      accumulator's `tool-content-blocks`), + a non-streaming execute test
      with a `tool_use` response asserting the `:tool-call` block survives
      with its id/name/arguments (FAIL pre-fix / PASS post-fix per the
      established pattern). (The same `text-content-blocks` mapping also
      drops `thinking` blocks on the non-streaming execute path while the
      streaming path keeps them in the final content — informational, but
      the same mapping should preserve or document them.)
      → Resolved: `text-content-blocks` is replaced by
      `non-streaming-content-blocks` (anthropic.clj), which maps the
      response `:content` blocks in WIRE ORDER — `"tool_use"` →
      `{:type :tool-call :id :name :arguments}` with `:input` JSON-encoded
      (a string, so downstream `tool-args/parse-args`, which
      json/parse-strings `:arguments`, parses it — mirroring the streaming
      accumulator's string `:arguments` and the `:openai-completions`
      sibling's `tool-call-block`), `"thinking"` → `{:type :thinking
      :text :signature}` (mirroring `thinking-blocks-in-order`; the
      informational note is closed by preserving rather than documenting),
      `"text"` → `{:type :text :text}`. A non-streaming `tool_use`
      response now yields a `:tool-call` block, so
      `classify-assistant-message`/`extract-tool-calls` record
      `:turn.outcome/tool-use` and the tool call executes (the
      `:stop-reason :tool_use` was already preserved). New tests (FAIL
      pre-fix / PASS post-fix, verified against the old text-only
      mapping): `execute-anthropic-preserves-tool-use-blocks-test`
      (tool_use + text, asserts `[{:type :tool-call :id "toolu_01" :name
      "get_weather" :arguments "{\"location\":\"Paris\"}"} {:type :text
      :text "Let me check"}]` in order + `:stop-reason :tool_use`) and
      `execute-anthropic-preserves-thinking-blocks-test` (thinking +
      text, asserts the `:thinking` block with text/signature survives) in
      anthropic_test.clj. Spec: a "Non-streaming execute-anthropic
      response mapping (review 57)" section comment added to
      spec/anthropic-provider.allium (the execute path is not
      rule-modeled — same convention as the review-45 400-fallback
      asymmetry note); CHANGELOG `Fixed` entry added; design.md revision
      note/AC updated.

## Follow-ups (implementation review 58, 2026-08-09)

- [x] Make Codex terminal tool-call balancing deterministic for multiple open
      calls. `emit-codex-done!` and the review-56 `emit-codex-error!` iterate
      `@open-tool-indexes` directly, but that value is a set and its traversal
      order is not a sequencing contract. Consequently the emitted
      `:toolcall-end` event order can differ from content-index order, unlike
      Anthropic's `balance-open-blocks!` (`sort (keys ...)`) and OpenAI chat
      completions' `emit-chat-tool-ends!` (`sort-by key ...`). This conflicts
      with psi's deterministic/replayable event-stream invariant and leaves
      the new error path without a multi-tool ordering lock. Iterate sorted
      indices in both Codex terminal emitters (prefer one shared balancing
      helper so the done/error paths cannot drift), and add done + error
      stream tests with at least two simultaneously open tool calls whose
      insertion order differs from content-index order, asserting ordered
      `:toolcall-end` events before the terminal.
      → Resolved: added shared `balance-open-codex-tools!`; both done/error
      terminals now sort open content indices before emitting
      `:toolcall-end`. Done + `response.failed` tests open indices 2 then 100
      (whose persistent-set traversal is 100 then 2) and assert ends 2, 100
      before the terminal. Namespace green (16 tests / 54 assertions);
      clj-kondo clean.

## Follow-ups (implementation review 59, 2026-08-09)

- [x] Propagate review 58's deterministic Codex terminal-balancing behavior
      into the authoritative design/spec artifacts. The implementation and
      tests now require open tool calls to close in ascending content-index
      order on both `:done` and `:error`, but
      `spec/openai-provider.allium`'s balancing rules only say "for each
      index" (no ordering contract), and design.md's review-driven transport
      inventory/acceptance exception stops at review 57. Add the ordered
      emission invariant to the Codex done/error rules (or a shared Codex
      balancing rule) and record review 58 in design.md so future changes
      cannot satisfy the spec while reintroducing nondeterministic set
      traversal. Re-run the Allium coherence check after updating them.
      → Resolved: `spec/openai-provider.allium` now has one shared Codex
      terminal-balancing rule for done/error paths: sort open tool-call
      content indices ascending, emit each `:toolcall-end` in that sequence,
      then emit the terminal. `SortAscending` is documented as a primitive.
      `design.md` records review 58 in both the review-driven transport
      inventory and acceptance exception. Manual Allium coherence check
      passes (the repository has no automated Allium checker): referenced
      entities/events are defined, the old unordered Codex error rule is
      removed, and spec/design/tests/code agree.

## Follow-ups (test review 61, 2026-08-09)

- [ ] Replace the task-added provider stream tests' global `with-redefs` of
      `clj-http.client/post` with an injectable nullable HTTP boundary. The
      new deterministic Codex balancing tests in `openai_codex_test.clj`
      (and the task's sibling stream tests) currently install canned
      `http/post` functions, so infrastructure is stubbed rather than
      nullable and the tests violate the task-test-review invariant
      `injectable ∧ nullable ∧ ¬mock ∧ ¬stub`. Add/configure a production
      nullable HTTP adapter that returns scripted SSE/HTTP responses and
      records requests through its public API, then drive the provider with
      that adapter and keep assertions on emitted provider events/request
      state. Preserve the discriminating `2`/`100` fixture: the pre-fix set
      traversal is `[100 2]`, so both `:done` and `:error` tests must still
      fail without ascending-index balancing.

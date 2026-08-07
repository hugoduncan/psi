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

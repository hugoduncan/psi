# Steps

- [x] Add `:gpt-5.6` catalog entry to `built-in/all-models` in
      `components/ai/src/psi/ai/models.clj` (mirrors gpt-5.5 shape/transport).
- [x] Add `:gpt-5.6` to `openai-chat-completions-native-model-keys`.
- [x] Generalize `openai-oauth-runtime-model` in
      `components/ai/src/psi/ai/model_registry.clj` to
      `openai-oauth-codex-model-ids` set `#{"gpt-5.5" "gpt-5.6"}`.
- [x] Add/extend tests in
      `components/ai/test/psi/ai/model_registry_test.clj`:
      catalog presence, OAuth routing, structured-output capability.
- [x] Run `bb test --focus psi.ai.model-registry-test` and
      `bb test --focus psi.ai.core-test` — pass.
- [x] `clj-kondo --lint components/ai/src` — clean.

## Implementation-review follow-ups

- [x] Fix bogus negative-control in `resolve-runtime-model-openai-oauth-routing-test`
      ("other openai models preserve catalog transport under oauth",
      `model_registry_test.clj` ~113–121): it uses `"gpt-5.4"`, whose *catalog*
      entry already has `:api :openai-codex-responses` /
      `:base-url "https://chatgpt.com/backend-api"` (models.clj 546–560). Because
      `gpt-5.4` ∉ `openai-oauth-codex-model-ids`, the override returns nil and
      `resolve-runtime-model` falls back to `find-model`, which returns codex
      transport anyway — so the assertion passes regardless of whether the OAuth
      override is applied and proves nothing about non-member behaviour. Use a
      genuine negative control whose catalog transport is `:openai-completions`
      (e.g. `gpt-5.4-mini` or `gpt-5`) and assert it stays `:openai-completions`
      under oauth ctx.
- [x] Add the missing symmetric "no oauth context" assertion for `gpt-5.6`
      (mirroring the existing `gpt-5.5` "remains chat-completions without oauth
      context" case, `model_registry_test.clj` ~80–84): assert
      `(resolve-runtime-model nil :openai "gpt-5.6")` yields `:openai-completions`
      / `https://api.openai.com/v1`. This closes the "selectable via the same path
      as gpt-5.5" AC and proves gpt-5.6 differs from an already-codex catalog entry.

## Implementation-review follow-ups (round 2)

- [x] Add a dedicated `gpt-5.6` catalog-entry field-value test (mirror the
      existing `fable-5-catalog-entry-test` / `sonnet-5-catalog-entry-test`
      pattern in `model_registry_test.clj` ~203–242). The AC "`:gpt-5.6` present
      in `built-in/all-models` with complete, sourced field values" and
      design.md Resolved-decision #1 elevate the specific pricing/context/max
      values (`:input-cost` 6.0, `:output-cost` 35.0, `:cache-read-cost` 0.6,
      `:cache-write-cost` 0.0, `:context-window` 1000000, `:max-tokens` 128000,
      `:api :openai-completions`, `:base-url "https://api.openai.com/v1"`, all
      three capability flags `true`) to resolved decisions, but no test pins any
      of them — the current gpt-5.6 tests assert only presence, OAuth routing,
      and structured-output capability. Silent drift in the decided pricing/
      context values would pass unnoticed despite the sibling test pattern
      existing in the same file for exactly this purpose.
- [x] Reconcile the changelog decision. implementation.md claims "no changelog
      entry required (consistent with prior synthetic-fixture gpt-5.4/gpt-5.5
      additions, which also added none)", but CHANGELOG.md contradicts this: it
      has entries for a catalog addition ("Claude Opus 4.8 ... is now available
      in the Anthropic model catalog") and for the exact OAuth-routing mechanism
      gpt-5.6 now joins ("OpenAI OAuth-backed `gpt-5.5` sessions now route
      through the ChatGPT/Codex transport"). A new user-visible, selectable
      model is `user_visible(δ)` under the AGENTS.md changelog policy. Either add
      an `[Unreleased] / Added` entry for gpt-5.6 (catalog availability + OAuth/
      Codex routing) or correct the implementation.md justification to cite an
      accurate reason for omission.

## Test-review follow-ups

- [x] Add a `resolve-runtime-model` test for a codex-set member (`gpt-5.6`)
      under a **ctx that is present but not OAuth-backed** (e.g. an api-key
      `create-null-context`, mirroring `core_test.clj`'s `api-key-ctx` /
      `empty-ctx` in `oauth-backed-test`). The current
      `resolve-runtime-model-openai-oauth-routing-test`
      (`model_registry_test.clj` 77–128) exercises only two branches for
      gpt-5.6: `nil` ctx (skips the `(and ctx (= :openai ...))` guard entirely,
      `model_registry.clj` ~216) and a ctx with a live OAuth credential. The
      `oauth-backed?`-false-with-ctx-present branch — where the override is
      guarded off and gpt-5.6 must fall back to catalog `:openai-completions`
      despite being in `openai-oauth-codex-model-ids` — is untested here. The
      existing `gpt-5.4-mini` negative control proves *non-membership* fallback,
      not *non-oauth-credential* fallback for a member; the `oauth-backed?`-false
      cases are covered only at the provider-auth unit level, not at the
      `resolve-runtime-model` seam gpt-5.6 actually routes through.

## Test-shaper follow-ups

- [x] Compress duplicated OAuth-context setup in
      `resolve-runtime-model-openai-oauth-routing-test`
      (`model_registry_test.clj` ~91–94, ~105–108, ~122–125): the
      `{:oauth-ctx (oauth/create-null-context {:credentials {:openai {:type :oauth
      :access "tok" :refresh "ref" :expires ...}}})}` literal is copy-pasted
      verbatim across three `testing` blocks. Extract a single helper (e.g.
      `oauth-ctx` / `(oauth-openai-ctx)`) so the arrange step is
      minimal-incidental-setup and the behavioural difference between blocks
      (model-id + expected transport) is the only thing that varies. Compresses
      ceremony without hiding intent (test-shaper: `minimal_incidental_setup`,
      `consistent(fixtures)`, `helpers_that_compress(ceremony)`).
- [x] Remove wall-clock time from the OAuth fixture
      (`model_registry_test.clj` ~94/108/125): `:expires (+
      (System/currentTimeMillis) 60000)` derives expiry from real time in setup,
      which is uncontrolled time in tests. Use a fixed/large constant expiry (or
      a clearly-labelled far-future literal) so the fixture is deterministic and
      time-independent (test-shaper: `deterministic → control(time)`). Verify
      `oauth-backed?` only requires a non-expired credential, not a specific
      value.
- [x] Reduce case-duplication between the two codex-routing `testing` blocks for
      `gpt-5.5` and `gpt-5.6` (`model_registry_test.clj` ~89–115): they assert
      the identical transport/capability contract and differ only by model-id
      and expected `:id`. Consider a small data-driven form (`doseq` over
      `["gpt-5.5" "gpt-5.6"]`) or a shared assertion helper so the shared
      contract is stated once and each id is a representative case, not a full
      copy (test-shaper: `economical`, `representative_cases_over_case_explosion`,
      `one_test_per_distinct_behavior`). Keep failure messages id-specific so
      `meaningful_failures` is preserved.

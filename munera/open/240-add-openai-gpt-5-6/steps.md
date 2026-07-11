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

- [ ] Add a dedicated `gpt-5.6` catalog-entry field-value test (mirror the
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
- [ ] Reconcile the changelog decision. implementation.md claims "no changelog
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

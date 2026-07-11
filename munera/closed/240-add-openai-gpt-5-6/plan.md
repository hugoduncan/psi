# Plan — Add OpenAI GPT-5.6

## Approach

Mirror the existing gpt-5.5 catalog entry pattern; no new mechanism
needed, purely data + a small generalization of the single-model OAuth
override check to a set (to keep it open for a second model without
branching).

## Decisions

- Reuse `:openai-completions` transport / `https://api.openai.com/v1`
  base URL, same as gpt-5.5.
- Costs follow the catalog's established increment-per-version
  convention (see design.md "Resolved decisions" #1) since this is a
  synthetic fixture catalog with no external pricing source.
- Add `:gpt-5.6` to `openai-chat-completions-native-model-keys`.
- Generalize `openai-oauth-runtime-model`'s single `"gpt-5.5"` string
  check into an `openai-oauth-codex-model-ids` set `#{"gpt-5.5"
  "gpt-5.6"}`, keeping the override open-for-extension without
  per-model branching.

## Risks

- Synthetic pricing values are not externally verifiable; mitigated by
  following the catalog's own established convention rather than
  inventing an unrelated scale.

## Test strategy

- Extend `components/ai/test/psi/ai/model_registry_test.clj`:
  - catalog presence (`find-model :openai "gpt-5.6"`)
  - OAuth routing (no-oauth vs. oauth-present, mirroring gpt-5.5 test)
  - structured-output capability classification (chat-completions
    native JSON Schema)

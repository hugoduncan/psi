# 223 — Steps

## Slice 1 — Catalog entry

- [ ] Add the `:fable-5` entry to `anthropic-models` in
      `components/ai/src/psi/ai/models.clj`, immediately after `:opus-4.8`, using
      the design's "Final catalog entry" verbatim (id `"claude-fable-5"`, name
      `"Claude Fable 5"`, `:provider :anthropic`, `:api :anthropic-messages`,
      `:base-url "https://api.anthropic.com"`, `:supports-reasoning true`,
      `:adaptive-thinking true`,
      `:supports-mid-conversation-system-messages true`, `:supports-images true`,
      `:supports-text true`, `:context-window 1000000`, `:max-tokens 128000`,
      `:input-cost 10.0`, `:output-cost 50.0`, `:cache-read-cost 1.0`,
      `:cache-write-cost 12.5`).
- [ ] Add `:fable-5` to the `anthropic-json-schema-native-model-keys` set in
      `models.clj`.
- [ ] `clj-paren-repair components/ai/src/psi/ai/models.clj` after the edits.
- [ ] Add/extend a non-live test asserting `:fable-5` resolves via
      `model-registry/find-model` and appears in `all-models`.
- [ ] Add/extend a non-live test asserting Fable 5 reports native JSON-Schema
      structured-output capability (`built-in-structured-output-capability`
      path).
- [ ] Run the focused model/registry test namespace(s) green.

## Slice 2 — Live verification test

- [ ] In `components/ai/test/psi/ai/providers/anthropic_models_api_test.clj`,
      replace `(def ^:private target-model-id "claude-opus-4-8")` with
      `(def ^:private target-model-ids #{"claude-opus-4-8" "claude-fable-5"})`.
- [ ] Rewrite the list-includes deftest to assert
      `(every? (set ids) target-model-ids)` against the `/v1/models` listing;
      rename it to `live-anthropic-models-list-includes-targets-test`.
- [ ] Rewrite the retrieve deftest to `doseq`/`testing` over `target-model-ids`,
      asserting a 200 and an id round-trip per id; rename it to
      `live-anthropic-models-retrieve-targets-test`.
- [ ] Keep `^:integration` metadata and the `with-live-models-api` opt-in gating
      on both deftests.
- [ ] `clj-paren-repair` the test file; confirm it compiles (the opt-in skip
      path runs without the env flags).
- [ ] (Optional, manual) Run the live test with
      `PSI_LIVE_ANTHROPIC_MODELS_API=1` + `ANTHROPIC_API_KEY` to confirm
      `claude-fable-5` is present and retrievable.

## Slice 3 — Docs + changelog

- [ ] Edit the mid-conversation system-message support enumeration in
      `doc/extension-api.md` (~line 217) to read "Support is true for Claude
      Opus 4.8 and Claude Fable 5 and for OpenAI chat-completions models …".
- [ ] Add a CHANGELOG `[Unreleased]` → `Added` entry using the design's draft:
      "Added Claude Fable 5 (`claude-fable-5`) as a selectable built-in Anthropic
      model: adaptive-thinking, image + text input, 1M-token context, native
      JSON-Schema structured output, and mid-conversation system-message
      support. Select with `/model anthropic claude-fable-5`."
- [ ] Do **not** edit `doc/configuration.md`, `doc/tui.md`, or `README.md`
      (illustrative examples, intentionally out of scope).

## Slice 4 — Verify + finalize

- [ ] Run `bb test` (non-live suite) and confirm green.
- [ ] Run `clj-kondo --lint components/ai/src components/ai/test` and confirm
      clean.
- [ ] Coherence check: catalog entry, native-keys set, live-test ids, changelog,
      and doc enumeration all reference Fable 5 consistently.
- [ ] Commit the change (`⚒` vocabulary) with a message referencing task 223.

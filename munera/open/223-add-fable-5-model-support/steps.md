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
- [ ] Extend the existing `init-built-ins-only-test` deftest in
      `components/ai/test/psi/ai/model_registry_test.clj` (do **not** add a new
      deftest) to assert Fable 5 resolution and catalog membership: add
      `(is (some? (registry/find-model :anthropic "claude-fable-5")))` (string
      id resolution) and `(is (contains? built-in/all-models :fable-5))`
      (keyword `:fable-5` key in the `psi.ai.models/all-models` source map,
      aliased `built-in` in this ns). Note: `registry/find-model` keys on the
      string id `"claude-fable-5"`; `built-in/all-models` keys on the keyword
      `:fable-5` — assert both forms accordingly.
- [ ] Extend the existing `built-in-structured-output-capabilities-test`
      deftest in the same namespace (do **not** add a new deftest, do **not**
      reference the private `built-in-structured-output-capability` fn) with a
      new `testing` block for Fable 5 that mirrors the existing Claude Opus 4.8
      block: bind `model (registry/find-model :anthropic "claude-fable-5")` and
      `capability (structured-output/effective-capability model)`, then assert
      the public capability surface — `(is (= true (:supported? capability)))`,
      `(is (= :anthropic/json-schema-output (:native-mechanism capability)))`,
      and `(is (contains? (set (:strategies capability)) :provider-native))`.
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

## Plan/steps ambiguity review follow-ups (ψ)

- [x] Pin the non-live test target in Slice 1 steps 4–5: name the namespace
      (`components/ai/test/psi/ai/model_registry_test.clj`) and state whether to
      extend the existing `init-built-ins-only-test` (find-model resolution) and
      `built-in-structured-output-capabilities-test` deftests or add new ones,
      so "Add/extend … namespace(s)" is no longer an open choice.
      → Resolved: Slice 1 steps 4–5 now name the ns and direct extending the two
      existing deftests (no new deftests).
- [x] Replace the private `built-in-structured-output-capability` reference in
      Slice 1 step 5 with the public assertion path used by the existing test:
      `structured-output/effective-capability` on the `find-model` result,
      asserting `:supported? true`, `:native-mechanism
      :anthropic/json-schema-output`, and `:provider-native` ∈ `:strategies`.
      → Resolved: Slice 1 step 5 now uses `structured-output/effective-capability`
      with the three public assertions, mirroring the Opus 4.8 block; private fn
      reference removed.
- [x] Disambiguate "appears in `all-models`" in Slice 1 step 4: specify which
      var (`psi.ai.models/all-models` keyed `:fable-5`, vs `registry/all-models`
      keyed `"claude-fable-5"`) and the key form to assert against.
      → Resolved: Slice 1 step 4 now asserts `built-in/all-models`
      (`psi.ai.models/all-models`) keyed by keyword `:fable-5`, plus
      `registry/find-model` keyed by string id `"claude-fable-5"`.

## Slice 4 — Verify + finalize

- [ ] Run `bb test` (non-live suite) and confirm green.
- [ ] Run `clj-kondo --lint components/ai/src components/ai/test` and confirm
      clean.
- [ ] Coherence check: catalog entry, native-keys set, live-test ids, changelog,
      and doc enumeration all reference Fable 5 consistently.
- [ ] Commit the change (`⚒` vocabulary) with a message referencing task 223.

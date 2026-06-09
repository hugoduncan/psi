# 223 — Steps

## Slice 1 — Catalog entry

- [x] Add the `:fable-5` entry to `anthropic-models` in
      `components/ai/src/psi/ai/models.clj`, immediately after `:opus-4.8`, using
      the design's "Final catalog entry" verbatim (id `"claude-fable-5"`, name
      `"Claude Fable 5"`, `:provider :anthropic`, `:api :anthropic-messages`,
      `:base-url "https://api.anthropic.com"`, `:supports-reasoning true`,
      `:adaptive-thinking true`,
      `:supports-mid-conversation-system-messages true`, `:supports-images true`,
      `:supports-text true`, `:context-window 1000000`, `:max-tokens 128000`,
      `:input-cost 10.0`, `:output-cost 50.0`, `:cache-read-cost 1.0`,
      `:cache-write-cost 12.5`).
- [x] Add `:fable-5` to the `anthropic-json-schema-native-model-keys` set in
      `models.clj`.
- [x] `clj-paren-repair components/ai/src/psi/ai/models.clj` after the edits.
- [x] Extend the existing `init-built-ins-only-test` deftest in
      `components/ai/test/psi/ai/model_registry_test.clj` (do **not** add a new
      deftest) to assert Fable 5 resolution and catalog membership: add
      `(is (some? (registry/find-model :anthropic "claude-fable-5")))` (string
      id resolution) and `(is (contains? built-in/all-models :fable-5))`
      (keyword `:fable-5` key in the `psi.ai.models/all-models` source map,
      aliased `built-in` in this ns). Note: `registry/find-model` keys on the
      string id `"claude-fable-5"`; `built-in/all-models` keys on the keyword
      `:fable-5` — assert both forms accordingly.
- [x] Extend the existing `built-in-structured-output-capabilities-test`
      deftest in the same namespace (do **not** add a new deftest, do **not**
      reference the private `built-in-structured-output-capability` fn) with a
      new `testing` block for Fable 5 that is a **full mirror** of the existing
      Claude Opus 4.8 block (model_registry_test.clj:144-151) — i.e. it asserts
      the catalog-metadata fields **and** the structured-output capability
      surface, not only the three structured-output assertions. Bind
      `model (registry/find-model :anthropic "claude-fable-5")` and
      `capability (structured-output/effective-capability model)`, then assert,
      in this order:
      - `(is (some? model))`
      - `(is (= "Claude Fable 5" (:name model)))`
      - `(is (= true (:adaptive-thinking model)))`
      - `(is (= true (:supports-mid-conversation-system-messages model)))`
      - `(is (= true (:supported? capability)))`
      - `(is (= :anthropic/json-schema-output (:native-mechanism capability)))`
      - `(is (contains? (set (:strategies capability)) :provider-native))`

      The catalog-metadata assertions (`:name`, `:adaptive-thinking`,
      `:supports-mid-conversation-system-messages`) are **required**, not
      optional: no other step asserts these Fable 5 field values (Slice 1 step 4
      only asserts `find-model` presence + `built-in/all-models` membership), so
      this full mirror is what makes the acceptance criterion "Fable 5 appears …
      with … capabilities … matching the agreed spec" covered by the non-live
      suite.
- [x] Run the focused model/registry test namespace(s) green.

## Slice 2 — Live verification test

- [x] In `components/ai/test/psi/ai/providers/anthropic_models_api_test.clj`,
      replace `(def ^:private target-model-id "claude-opus-4-8")` with
      `(def ^:private target-model-ids #{"claude-opus-4-8" "claude-fable-5"})`.
- [x] Rewrite the list-includes deftest to assert
      `(every? (set ids) target-model-ids)` against the `/v1/models` listing;
      rename it to `live-anthropic-models-list-includes-targets-test`.
- [x] Rewrite the retrieve deftest to `doseq`/`testing` over `target-model-ids`,
      asserting a 200 and an id round-trip per id; rename it to
      `live-anthropic-models-retrieve-targets-test`.
- [x] Keep `^:integration` metadata and the `with-live-models-api` opt-in gating
      on both deftests.
- [x] `clj-paren-repair` the test file; confirm it compiles (the opt-in skip
      path runs without the env flags).
- [ ] (Optional, manual) Run the live test with
      `PSI_LIVE_ANTHROPIC_MODELS_API=1` + `ANTHROPIC_API_KEY` to confirm
      `claude-fable-5` is present and retrievable.

## Slice 3 — Docs + changelog

- [x] Edit the mid-conversation system-message support enumeration in
      `doc/extension-api.md` (~line 217) to read "Support is true for Claude
      Opus 4.8 and Claude Fable 5 and for OpenAI chat-completions models …".
- [x] Add a CHANGELOG `[Unreleased]` → `Added` entry using the design's draft:
      "Added Claude Fable 5 (`claude-fable-5`) as a selectable built-in Anthropic
      model: adaptive-thinking, image + text input, 1M-token context, native
      JSON-Schema structured output, and mid-conversation system-message
      support. Select with `/model anthropic claude-fable-5`."
- [x] Do **not** edit `doc/configuration.md`, `doc/tui.md`, or `README.md`
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

## Plan/steps inconsistency review follow-ups (ψ)

- [x] Reconcile plan.md with the non-live test decisions in steps Slice 1.
      plan.md's "Key decisions (all inherited from design, no new decisions
      required)" header and its Approach/Key-decisions deliverable list omit the
      non-live `model_registry_test.clj` work (extend `init-built-ins-only-test`
      + `built-in-structured-output-capabilities-test`, public
      `structured-output/effective-capability` assertions,
      `:fable-5`/`"claude-fable-5"` key forms) — a test structure decided during
      the plan/steps ambiguity-review pass, not present in design.md (which
      resolved only the live-test shape). Either add the non-live test work as
      an explicit plan decision/deliverable, or drop the "all inherited from
      design, no new decisions required" claim so plan and steps agree on
      decision provenance.
      → Resolved: plan.md now (1) reframes the Key-decisions header to scope
      "inherited from design" to the catalog/live-test/docs decisions and
      attribute the non-live test structure to the plan/steps ambiguity-review
      pass, and (2) adds an explicit deliverable bullet for the non-live
      `model_registry_test.clj` work (extend the two existing deftests; public
      `structured-output/effective-capability` assertions; `:fable-5` /
      `"claude-fable-5"` key forms). Plan and steps now agree on decision
      provenance.

## Plan/steps ambiguity review follow-ups (3rd pass, ψ)

- [x] Pin the assertion set for the Fable 5 `built-in-structured-output-capabilities-test`
      block in Slice 1 step 5: "mirrors the existing Claude Opus 4.8 block" but
      then enumerates only the three structured-output assertions, while the
      Opus 4.8 block (model_registry_test.clj:144-151) also asserts
      `(some? model)`, `(= "Claude Fable 5" (:name model))`,
      `(= true (:adaptive-thinking model))`, and
      `(= true (:supports-mid-conversation-system-messages model))`. Decide
      explicitly whether the Fable 5 block includes those catalog-metadata
      assertions (full mirror) or only the three structured-output assertions,
      and update the step so it is no longer an open choice. Note no other step
      asserts Fable 5's name/adaptive-thinking/mid-system-message field values
      (Slice 1 step 4 only asserts `find-model` presence + `built-in/all-models`
      membership), so this choice determines whether acceptance criterion
      "capabilities … matching the agreed spec" is covered by the non-live suite.
      → Resolved: chose **full mirror**. Slice 1 step 5 now directs a Fable 5
      `testing` block that asserts the catalog-metadata fields (`:name`,
      `:adaptive-thinking`, `:supports-mid-conversation-system-messages`) **and**
      the structured-output surface, exactly mirroring the Opus 4.8 block
      (model_registry_test.clj:144-151), with all seven assertions enumerated.
      Rationale: this is the only step that asserts Fable 5's catalog-metadata
      field values, so the full mirror is required to cover the acceptance
      criterion "capabilities … matching the agreed spec" in the non-live suite.

## Slice 4 — Verify + finalize

- [x] Run `bb test` (non-live suite) and confirm green.
      → `bb test:ai` 145 tests / 970 assertions / 0 failures;
      `bb clojure:test:unit` exit 0.
- [x] Run `clj-kondo --lint components/ai/src components/ai/test` and confirm
      clean. → 0 errors, 0 warnings.
- [x] Coherence check: catalog entry, native-keys set, live-test ids, changelog,
      and doc enumeration all reference Fable 5 consistently.
- [x] Commit the change (`⚒` vocabulary) with a message referencing task 223.
      → Committed in slices 1–4.

## Test review follow-ups (ψ)

- [x] Close the pricing/numeric-capability coverage gap: no non-live test
      asserts Fable 5's `:input-cost 10.0`, `:output-cost 50.0`,
      `:cache-read-cost 1.0`, `:cache-write-cost 12.5`, `:context-window 1000000`,
      or `:max-tokens 128000` (nor `:supports-images`/`:supports-text`/
      `:supports-reasoning`), yet the acceptance criterion requires "provider,
      api, capabilities, and **pricing** matching the agreed spec". Extend the
      Fable 5 block in `built-in-structured-output-capabilities-test`
      (model_registry_test.clj:153-161) — or add a focused Fable 5 catalog-value
      assertion — to assert the pricing fields and the `:context-window` /
      `:max-tokens` values (and the boolean capability flags) against the
      design's "Final catalog entry", so a transcription error is caught by the
      non-live suite. Re-run `bb test:ai` + clj-kondo.
      → Resolved: added a focused `testing` block "Claude Fable 5 catalog entry
      carries the agreed pricing and capability values" to
      `built-in-structured-output-capabilities-test`
      (model_registry_test.clj) asserting `:supports-reasoning`,
      `:supports-images`, `:supports-text` (all true), `:context-window 1000000`,
      `:max-tokens 128000`, `:input-cost 10.0`, `:output-cost 50.0`,
      `:cache-read-cost 1.0`, `:cache-write-cost 12.5` against the design's
      "Final catalog entry". A transcription error in any pricing/numeric field
      is now caught by the non-live suite. `bb test:ai` 145 tests / 980
      assertions / 0 failures; clj-kondo 0/0 on the test file.

## Test review follow-ups (test-shaper, 3rd pass, ψ)

- [x] Extract the Fable 5 catalog-value/pricing assertions out of
      `built-in-structured-output-capabilities-test` (model_registry_test.clj:167-178)
      into a dedicated Fable 5 catalog-entry deftest (e.g.
      `fable-5-catalog-entry-test`), so the structured-output deftest keeps a
      single concern (structured-output surface) and a pricing/numeric
      transcription failure reports under a truthfully-named test. test-shaper
      `single_concern` + `consistent(naming)` + `behavior_focused`. Optionally
      move the Fable 5 "findable" block's catalog-metadata assertions
      (`:name`/`:adaptive-thinking`/`:supports-mid-conversation-system-messages`,
      lines 156-165) into the same catalog-entry deftest, slimming the
      structured-output block to mirror the gpt-5.5/sonnet blocks and removing
      the duplicated `find-model` + `(some? model)` across the two Fable 5
      blocks. Re-run `bb test:ai` + clj-kondo.
      → Resolved: added a dedicated `fable-5-catalog-entry-test` deftest holding
      all catalog-value assertions (metadata `:name`/`:adaptive-thinking`/
      `:supports-mid-conversation-system-messages`, boolean flags, numeric
      `:context-window`/`:max-tokens`, and the four pricing fields). Took the
      optional path: slimmed the Fable 5 structured-output block in
      `built-in-structured-output-capabilities-test` to the three
      structured-output assertions only (mirroring the gpt-5.5/sonnet blocks),
      removing the duplicated `find-model` + `(some? model)`. The Opus 4.8 block
      is untouched (pre-existing convention). `bb test:ai` 146 tests / 979
      assertions / 0 failures; clj-kondo 0/0; clj-paren-repair clean.

## Test review follow-ups (test-shaper, 4th pass, ψ)

- [x] Re-shape `live-anthropic-models-list-includes-targets-test`
      (anthropic_models_api_test.clj:47-52) for meaningful failures and
      structural consistency with the retrieve deftest. The current
      `(is (every? ids target-model-ids))` reports only `actual: false` on
      failure and cannot name the missing id (test-shaper `meaningful_failures`),
      and diverges from the sibling
      `live-anthropic-models-retrieve-targets-test`'s per-id
      `doseq`/`testing` shape (test-shaper `consistent(structure)`). Assert the
      `200` status once, then `(doseq [model-id target-model-ids] (testing
      model-id (is (contains? ids model-id))))` so a missing target id names
      itself and both live deftests share one iteration shape. Run
      clj-paren-repair; confirm the env-gated skip path still compiles without
      `PSI_LIVE_ANTHROPIC_MODELS_API`/`ANTHROPIC_API_KEY`.
      → Resolved: replaced `(is (every? ids target-model-ids))` with the `200`
      status assertion once followed by
      `(doseq [model-id target-model-ids] (testing model-id (is (contains? ids
      model-id))))`. A missing target id now names itself on failure, and both
      live deftests share the per-id `doseq`/`testing` iteration shape. The
      env-gated skip path still compiles (loaded clean under `bb test:ai`);
      clj-paren-repair no changes; clj-kondo 0/0; `bb test:ai` 146 tests / 979
      assertions / 0 failures.

## Docs review follow-ups (review-task-docs, ψ)

- [x] Improve readability of the edited mid-conversation enumeration in
      `doc/extension-api.md:217`. The current "Support is true for Claude Opus
      4.8 and Claude Fable 5 and for OpenAI chat-completions models" doubles
      "and … and for" for a two-item list. Add a comma before the second
      clause: "… for Claude Opus 4.8 and Claude Fable 5, and for OpenAI
      chat-completions models" (accuracy unaffected; clarity nit only).
      → Resolved: added the comma before the second clause in
      `doc/extension-api.md:217`; line now reads "Support is true for Claude
      Opus 4.8 and Claude Fable 5, and for OpenAI chat-completions models".
      Pure prose clarity edit; accuracy unchanged, no code/test impact.

## Plan/steps inconsistency review follow-ups (6th pass, ψ)

- [ ] Reconcile the stale "no new deftests / full mirror" non-live-test
      decision in plan.md and steps.md Slice 1 step 5 with the test-shaper
      3rd-pass follow-up and the implemented code. plan.md still says the
      non-live proof is done by "extending the existing deftests (no new
      deftests)" and that `built-in-structured-output-capabilities-test` gets a
      Fable 5 block that is a "full mirror" of the Opus 4.8 block (catalog
      metadata + structured-output surface, all seven assertions); steps.md
      Slice 1 step 5 (`[x]`) enumerates the same seven-assertion full mirror and
      "do not add a new deftest". The later test-shaper 3rd-pass follow-up
      (`[x]`) reversed both: it added a dedicated `fable-5-catalog-entry-test`
      deftest and slimmed the structured-output Fable 5 block to the three
      structured-output assertions only — matching the implemented code
      (`fable-5-catalog-entry-test` at model_registry_test.clj:163; slim
      3-assertion block at line 156). Update plan.md's Approach/Key-decisions
      ("no new deftests", "full mirror … all seven assertions") and steps.md
      Slice 1 step 5 to reflect the final structure (slim structured-output
      block + dedicated catalog-entry deftest), or add an explicit note that
      the test-shaper 3rd-pass follow-up supersedes the original Slice 1 step 5
      decision, so plan, steps, and code agree on the non-live test structure.

# Steps

## Code-shaper follow-ups (round 4)

- [x] Fix the tautological `:api` assertion in
      `codex-catalog-transport-matches-shared-constants-test`
      (`model_registry_test.clj` ~273–295). The drift-guard test filters codex
      entries with the predicate `(= structured-output/openai-codex-api (:api
      model))`, then asserts `(= structured-output/openai-codex-api (:api model))`
      on each surviving entry. Every entry that passes the filter satisfies that
      assertion *by construction* — the `:api` check is provably always-true and
      can never catch drift, so the test's stated "`:api`/`:base-url` equal the
      shared constants" invariant is only half-enforced (only `:base-url` carries
      signal). The intent (round-3: "asserts its `:api`/`:base-url` equal the
      shared constants") is undermined: a codex entry whose `:api` diverged from
      the constant would simply be excluded by the filter and silently skipped
      rather than flagged. Select the drift-guard population by a codex identity
      *independent of* `:api` — e.g. iterate all entries and select those whose
      `:base-url` equals `openai-codex-base-url` (then assert `:api` equals
      `openai-codex-api`), or select by a stable catalog key/marker — so the
      `:api` assertion tests a real invariant instead of the filter predicate.
      Guard against the empty-population masking risk either way (the current
      `(seq codex-entries)` check must key off whichever independent selector is
      used) (code-shaper: `robust → enforceable(invariants)`,
      `single_responsibility`, `orthogonal`; test-shaper: `meaningful_failures`).

## Code-shaper follow-ups (round 3)

- [x] Reconcile the codex transport literals in the catalog with the shared
      `structured-output` constants that now own them (`models.clj` — the 9
      `:openai-codex-responses` entries; the 8 that restate
      `:base-url "https://chatgpt.com/backend-api"`, e.g. `:gpt-5.4`
      ~546–551, `:gpt-5.1-codex` ~402, `:gpt-5.2-codex` ~482, `:gpt-5.3-codex`
      ~514). Task 240's round-2 code-shaper fix introduced a single owner for
      the "how a model becomes codex" rule — `openai-codex-api` /
      `openai-codex-base-url` constants and `with-openai-codex-transport` in
      `structured_output.clj` — and made `openai-oauth-runtime-model` compose it
      instead of restating the literals. But the codex catalog entries still
      restate the same `:api :openai-codex-responses` and
      `:base-url "https://chatgpt.com/backend-api"` string literals inline, so the
      codex transport identity is now defined in two places by two mechanisms
      (shared constants vs. inline catalog literals) that can drift silently: a
      change to `openai-codex-base-url` would not propagate to the catalog
      entries, and nothing enforces that the catalog's inline literals equal the
      constants the override composes. This is the same duplication the round-2
      item fixed on the override side, left in place on the catalog side
      (round-2 note scoped catalog literals out explicitly, but the shared owner
      it created makes reconciliation now reachable). Prefer having the codex
      catalog entries reference the shared constants (`:api
      structured-output/openai-codex-api`, `:base-url
      structured-output/openai-codex-base-url`) — or an equivalent
      catalog-authoring construct — so the codex transport strings have a single
      source and the catalog cannot drift from the runtime override. If the
      data-literal catalog form is intentionally kept, add a test/invariant that
      asserts every `:openai-codex-responses` entry's `:api`/`:base-url` equal the
      shared constants so drift is at least caught. Task-240 scope caveat: this
      touches pre-existing sibling codex entries, not just gpt-5.6, so confirm the
      broadened blast radius is acceptable (or split into a dedicated catalog
      task) before applying (code-shaper: `consistent(idioms)`,
      `single_responsibility`, `orthogonal`, `robust → enforceable(invariants)`).

## Code-shaper follow-ups (round 2)

- [x] Remove the duplicated codex-transport shaping in `openai-oauth-runtime-model`
      (`model_registry.clj` ~195–200). The override re-derives the ChatGPT/Codex
      transport triple imperatively — `(assoc :api :openai-codex-responses :base-url
      "https://chatgpt.com/backend-api")` plus
      `structured-output/with-openai-codex-native-capability` — but this exact triple
      is *already* the declarative shape a codex catalog entry produces: every
      `:openai-codex-responses` entry in `models.clj` carries the same `:api` +
      `:base-url` literals (e.g. `:gpt-5.4`, models.clj 546–551), and
      `built-in-structured-output-capability`'s `:openai-codex-responses` branch
      (models.clj ~660) auto-attaches the *same* `openai-codex-native-capability`
      that `with-openai-codex-native-capability` sets. So the codex transport/
      capability shape is now defined in two places via two different mechanisms
      (declarative catalog annotation vs. imperative override `assoc`), which drift
      independently: a change to the codex base-url or codex capability in the
      catalog path would silently not apply to OAuth-overridden models. Prefer
      shaping the codex transport once — e.g. derive the override target from the
      canonical codex catalog entry / a shared codex-transport constructor — so the
      "how a model becomes codex" rule has a single owner and the override composes
      it rather than re-stating its literals (code-shaper: `consistent(idioms)`,
      `orthogonal`, `single_responsibility`, `robust` → `enforceable(invariants)`).

## Code-shaper follow-ups

- [x] Resolve the dual-lookup shape inconsistency in `openai-oauth-runtime-model`
      (`model_registry.clj` ~192–199). The base entry is looked up as
      `(or (find-model :openai model-id) (get built-in/all-models (keyword
      model-id)))`. The two branches yield **different data shapes**: `find-model`
      returns the catalog-normalized entry (structured-output materialized via
      `built-in-catalog` → `structured-output/normalize-model`), while the raw
      `(get built-in/all-models (keyword model-id))` fallback returns the
      un-normalized built-in map. For every id in `openai-oauth-codex-model-ids`
      the catalog entry always exists, so the fallback branch is currently dead;
      if it were ever reached (id present in `all-models` but absent from the
      merged catalog) the override would emit a differently-shaped, un-normalized
      model map. Prefer a single lookup source (`find-model` only), or if a
      built-in fallback is genuinely needed, normalize it the same way the catalog
      does so both branches produce one shape (code-shaper:
      `consistent(data_shapes)`, `robust`, `single_responsibility`).
- [x] Remove the `(keyword model-id)` catalog-keying assumption from
      `openai-oauth-runtime-model` (`model_registry.clj` ~197). The fallback
      reconstructs the built-in map key by `(keyword model-id)`, duplicating the
      indexing convention that `built-in-catalog`/`find-model` already own and
      coupling the OAuth override to the raw map's keying scheme. This is the same
      leak the design's single-source-of-truth intent argues against. Dropping the
      raw-map fallback (previous step) removes this coupling; if a fallback must
      remain, route it through the catalog-indexing path rather than
      re-deriving the key here (code-shaper: `locally_comprehensible`,
      `orthogonal`, `single_responsibility`).

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

## Test-shaper follow-ups (round 4)

- [x] Collapse the duplicated no-oauth member-pair blocks in
      `resolve-runtime-model-openai-oauth-routing-test`
      (`model_registry_test.clj` ~95–105). The two `testing` blocks "openai
      gpt-5.5 remains chat-completions without oauth context" and "openai gpt-5.6
      remains chat-completions without oauth context" are verbatim copies
      differing only by model-id (`nil` ctx → `:openai-completions` /
      `https://api.openai.com/v1`) — the identical `case_explosion` that the
      adjacent codex-routing pair was already collapsed into a `doseq` over
      `["gpt-5.5" "gpt-5.6"]` to fix (round-2 follow-up). Leaving the no-oauth
      pair as full copies makes the *same test* internally inconsistent: one
      member-pair contract is data-driven, the sibling member-pair contract is
      duplicated. Data-drive the no-oauth pair the same way (`doseq` over the
      same id list, id-specific failure messages) so the shared contract is
      stated once and each id is a representative case, and the two member-pair
      contracts in this test use one consistent shape (test-shaper: `economical`,
      `representative_cases_over_case_explosion`, `consistent(structure)`,
      `one_test_per_distinct_behavior`; keep `meaningful_failures` via
      id-specific messages).

## Test-shaper follow-ups (round 3)

- [x] Make the api-key ctx fixture consistent with the extracted oauth helper in
      `resolve-runtime-model-openai-oauth-routing-test`
      (`model_registry_test.clj` ~118–128). The oauth branches build their ctx via
      the `oauth-openai-ctx` helper (extracted in round 1 to kill the duplicated
      oauth-ctx literal), but the "ctx present but not oauth-backed" branch
      reintroduces the raw `{:oauth-ctx (oauth/create-null-context {:credentials
      {:openai {:type :api-key :key "sk-1"}}})}` literal inline. Within a single
      test, the fixture-construction style is now inconsistent (helper vs. raw
      literal) and the incidental `create-null-context` ceremony reappears.
      Extract a sibling helper (e.g. `api-key-openai-ctx`) or generalize a single
      `openai-ctx` builder parameterized on the credential map, so the only thing
      varying between the oauth and api-key branches is the credential type — the
      actual behavioural distinction the test exists to prove (test-shaper:
      `consistent(fixtures)`, `minimal_incidental_setup`,
      `helpers_that_compress(ceremony)`, `behavior_focused`).

## Test-shaper follow-ups (round 2)

- [x] Split the concern-mixing in the codex-routing `doseq`
      (`model_registry_test.clj` ~99–116). The single `testing` block asserts two
      distinct contracts at once: the OAuth **transport override** (`:api`
      `:openai-codex-responses` / `:base-url "https://chatgpt.com/backend-api"`)
      and the **structured-output capability** shaping (`:strategies`
      `[:provider-native :prompted-json]`, `:native-mechanism`
      `:openai/responses-text-format-json-schema`). This violates
      `single_concern` and weakens `meaningful_failures` (a capability-shaping
      regression fails under a test named for transport routing). The capability
      half is also redundant: the codex structured-output contract is already
      covered in `openai_structured_output_test.clj` (~160/256/304) and, for a
      codex catalog model, in `built-in-structured-output-capabilities-test`'s
      "OpenAI Codex Responses models" block (`gpt-5.4`). Reduce this `doseq` to
      the transport-override contract only (the behaviour that this seam owns and
      that the OAuth override actually changes), so the routing test states one
      concern and capability regressions surface under the capability tests
      (test-shaper: `single_concern`, `behavior_focused`, `meaningful_failures`,
      `economical`).

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

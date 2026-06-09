# Implementation notes

## Reviews

### Architectural-fit review (design) — ψ

Verdict: fits architecture; no new actionable misfit.

- Additive catalog data change; honors catalog single-source-of-truth
  (model_registry/built-in-catalog indexes built-in/all-models).
- `:fable-5` entry structurally identical to existing `:opus-4.8`
  (models.clj) — same provider/api/adaptive-thinking/context-window/pricing
  shape; uses only existing capability fields (no schema extension).
- Structured output wired via established
  `anthropic-json-schema-native-model-keys` set mechanism — consistent
  with existing pattern, not new dispatch.
- Additive-only, no default-selection change → `extend: addition > modification`.
- Extends existing `anthropic_models_api_test.clj` live opt-in proof rather
  than adding a parallel verification mechanism.
- Split capability source (model map + native-key set) is a pre-existing
  project pattern; following it is correct fit, not a task-scoped misfit.

### Architectural-fit review (design, 2nd pass) — ψ

Verdict: fits architecture; no new actionable misfit. Re-verified against
current code:

- `:fable-5` entry structurally identical to `:opus-4.8` (models.clj:171-187):
  same provider/api/adaptive-thinking/context/pricing shape; only existing
  capability fields — no catalog schema extension (single-source-of-truth held).
- Structured output via established `anthropic-json-schema-native-model-keys`
  set + `built-in-structured-output-capability` case dispatch
  (models.clj:589-613) — canonical mechanism, no new dispatch path.
- Additive-only, no default-selection change → `extend: addition > modification`.
- Live proof extends existing `anthropic_models_api_test.clj` (parameterize
  ids over a set) rather than adding a parallel verification mechanism.

No new design-steps items.

### Ambiguity review (design) — ψ

Verdict: actionable ambiguities found (3). Design self-declares
"complete and unambiguous" but docs scope, changelog obligation, and
test-extension shape are underspecified. See design-steps.md.

- Docs scope is conditional ("if model availability is documented") and
  unresolved: `doc/configuration.md` names adaptive-thinking models
  (Opus 4.7/4.8) for `:xhigh` behaviour; Fable 5 is adaptive-thinking, so
  that list is a concrete update candidate the design neither includes nor
  excludes. Which docs (if any) must change is undecided.
- Changelog obligation ambiguous: "if documented" makes it optional, but a
  new selectable built-in model is user-visible per the changelog protocol
  (new capability) → entry arguably required. Mandatory vs optional unstated.
- "Extend the existing test to also assert claude-fable-5" is structurally
  ambiguous: current test hardcodes one `target-model-id` ("claude-opus-4-8")
  and two opus-named deftests. Unspecified: retain opus-4.8 assertions,
  parameterize over a set of ids, or duplicate deftests for fable-5.

### Inconsistency review (design) — ψ

Verdict: actionable inconsistencies found (2). See design-steps.md.

- Dangling cross-reference: Scope "Out of scope" (l.49) says "unless Fable 5
  requires a genuinely new protocol — see open questions", but design.md has
  no "Open questions" section, and "Resolved facts" already fixes
  `:api :anthropic-messages` (existing protocol family). The reference is
  stale and contradicts the design's own resolved facts.
- Unmapped capability fact: "Resolved facts" (l.79) lists `pdf_input` supported
  alongside capabilities that each map to a catalog field
  (image_input→:supports-images, text→:supports-text, context-window,
  max-tokens), but pdf has no `→ :field` mapping, is absent from the "Final
  catalog entry", and is unmentioned in acceptance criteria. Confirmed no pdf
  field exists anywhere in `components/ai/src` — so it is a no-op fact dropped
  without rationale. Intent ("correct capabilities") leaves its scope unstated.

### Inconsistency review follow-up — resolved (design) — ψ

Both inconsistency follow-up steps resolved in design.md; design-steps marked
done.

- Dangling cross-reference → "Out of scope" bullet rephrased to drop the
  non-existent "Open questions" reference; now states resolved facts fix
  `:api :anthropic-messages`, so no new-protocol work is in scope.
- `pdf_input` fact → "Resolved facts → Capabilities" now states pdf is
  provider-supported but has no catalog representation (verified: zero
  pdf/document refs in `components/ai/src`), so it is intentionally omitted
  from the catalog entry and acceptance criteria.

### Ambiguity review follow-up — resolved (design) — ψ

All three ambiguity follow-up steps resolved in design.md "Resolved
ambiguities (from review, 2026-06)"; design-steps marked done.

- Docs scope → no prose doc/README changes. Confirmed by grep: docs name models
  only as illustrative examples (`configuration.md` "such as Opus 4.7/4.8",
  `tui.md`/`extension-api.md` single-model worked examples); no catalog
  inventory exists in docs (`models.clj` is SoT). Additive task keeps examples
  as-is.
- Changelog → mandatory `[Unreleased] → Added` entry; draft text recorded in
  design. CHANGELOG.md itself not edited here (implementation work, belongs to
  builder/steps.md, not design follow-up).
- Test shape → parameterize asserted ids over a set `target-model-ids`
  (retain `claude-opus-4-8`, add `claude-fable-5`), `every?` in list-includes
  test, `doseq`/`testing` per id in retrieve test, rename deftests to drop
  opus-specific names. No parallel deftests.

### Inconsistency review (design, 2nd pass) — ψ

Verdict: actionable inconsistency found (1). See design-steps.md.

- Docs-scope rationale vs referenced artifact: design "Resolved ambiguities →
  Docs scope" (and the resolved ambiguity follow-up) characterizes
  `doc/extension-api.md` as a "single-model worked example … not catalog
  inventories", justifying "no prose doc changes required". But the referenced
  text (`doc/extension-api.md:217-220`) is a capability-coverage statement, not
  a worked example: "Support is true for Claude Opus 4.8 and for OpenAI
  chat-completions models … older Anthropic models are reported unsupported."
  The design sets `:supports-mid-conversation-system-messages true` for Fable 5,
  and `model_capabilities/supports-mid-system-messages?` returns true on
  explicit support, so Fable 5 *will* report supported. Adding Fable 5 makes
  that enumeration incomplete/misleading (a reader may infer the newer Fable 5
  is unsupported). Inconsistent with the design's "all model mentions are
  illustrative" premise for that doc and undermines the no-doc-change decision
  for `extension-api.md` specifically.
- Re-verified prior resolved inconsistencies (open-questions cross-ref, pdf
  fact) remain resolved; catalog entry/structured-output/cache-ratio claims
  match models.clj; test-shape claims match current `anthropic_models_api_test`.

### Architectural-fit review (design, 3rd pass) — ψ

Verdict: fits architecture; no new actionable misfit. Re-verified against
code + META.md:
- META.md:32-33 "psi has a model catalog of built-in and user-defined AI
  models; built-in models are compiled into the AI component" — Fable 5 as a
  compiled Anthropic built-in entry is exactly this; honors catalog SoT.
- Proposed `:fable-5` structurally identical to `:opus-4.8` (models.clj:171-187):
  same provider/api/adaptive-thinking/context/max-tokens; only existing
  capability fields, no catalog schema extension.
- Structured output via `anthropic-json-schema-native-model-keys` set
  (models.clj:589-596) + `case`-on-`:api` dispatch (models.clj:598-613);
  adding `:fable-5` to the set is the canonical mechanism — no new dispatch.
- Pure additive data, no default-selection change → `extend: addition >
  modification`; flows through `all-models`/`built-in-catalog` automatically.
- Live proof extends existing opt-in `anthropic_models_api_test.clj` rather
  than a parallel verification mechanism.

No new design-steps items.

### Ambiguity review (design, 3rd pass) — ψ

Verdict: no new actionable ambiguity. Re-verified design against current code.

- Prior 3 ambiguities (docs scope, changelog obligation, test-extension shape)
  remain resolved in design "Resolved ambiguities"; design-steps all `[x]`.
- Target `:fable-5` entry values map unambiguously to existing fields, near-
  identical to `:opus-4.8` (models.clj:171-187); pricing/cache ratios explicit
  (cache-read 1.0=10×0.1, cache-write 12.5=10×1.25) — no underspecified field.
- Structured-output wiring exact: add `:fable-5` to
  `anthropic-json-schema-native-model-keys` (models.clj:589-596); dispatch via
  `built-in-structured-output-capability` `:anthropic-messages` + set membership
  (models.clj:597-613). Unambiguous.
- Test-extension shape matches current `anthropic_models_api_test.clj`
  (single `target-model-id "claude-opus-4-8"`, two opus-named deftests,
  list + `/{id}` retrieve); parameterize-over-set resolution is concrete and
  fully mappable.
- Entry placement within the (unordered) `anthropic-models` map and the
  deftest-rename "e.g." names are cosmetic, not design-level decisions → not
  actionable (consistent with prior pass).

No new design-steps items.

### Inconsistency review (design, 3rd pass) — ψ

Verdict: actionable inconsistency found (1, previously identified but unaddressed).
See design-steps.md.

- The 2nd-pass inconsistency review (above) flagged the `doc/extension-api.md`
  mischaracterization and said "See design-steps.md", but no follow-up step was
  ever added and design.md remains unchanged → the inconsistency is still live.
  Re-confirmed: design "Resolved ambiguities → Docs scope" calls
  `doc/extension-api.md` a "single-model worked example … not catalog inventory"
  to justify no-doc-change, but the referenced text
  (`doc/extension-api.md:217-220`) is a capability-support *enumeration*:
  "Support is true for Claude Opus 4.8 and for OpenAI chat-completions models …
  older Anthropic models are reported unsupported." Fable 5 sets
  `:supports-mid-conversation-system-messages true` and will report supported,
  so the enumeration becomes incomplete/misleading after the addition. The
  characterization does not match the referenced artifact, and the no-doc-change
  decision for `extension-api.md` rests on that inaccurate framing. Now carried
  into design-steps.md as a follow-up.
- Re-verified other design claims hold: `:fable-5` final entry vs `:opus-4.8`
  (models.clj:171-187) — only id/name/pricing differ; cache ratios consistent
  (read 1.0=10×0.1, write 12.5=10×1.25). Structured-output set + dispatch
  (models.clj:589-613) matches. Test-shape claims match current
  `anthropic_models_api_test.clj` (single `target-model-id "claude-opus-4-8"`,
  two opus-named deftests). Prior resolved inconsistencies (open-questions
  cross-ref, pdf fact) remain resolved.

### Inconsistency review follow-up (3rd pass) — resolved (design) — ψ

The 3rd-pass `doc/extension-api.md` docs-scope inconsistency is resolved in
design.md; design-steps marked done.

- Verified in code: `model-capabilities/supports-mid-system-messages?`
  (components/agent-session/.../model_capabilities.clj:8-27) returns true on
  explicit `(true? :supports-mid-conversation-system-messages)`. Fable 5
  declares that flag true (final catalog entry), so it *will* report supported.
- Corrected the design mischaracterization: design.md "Resolved ambiguities →
  Docs scope" now separates the illustrative "such as" examples
  (`configuration.md`, `tui.md`, unchanged) from the *definitive* capability
  enumeration at `doc/extension-api.md:215-220` ("Support is true for Claude
  Opus 4.8 and for OpenAI chat-completions models … older Anthropic models are
  reported unsupported"), which is not illustrative.
- Decision recorded: one targeted `doc/extension-api.md` update — add Claude
  Fable 5 to the mid-conversation system-message support enumeration — is
  **required** to keep the factual enumeration accurate; all other prose docs
  unchanged. Threaded into Scope and acceptance criteria. The actual doc edit
  belongs to builder/steps.md (implementation), not this design follow-up.

### Architectural-fit review (design, 4th pass) — ψ

Verdict: fits architecture; no new actionable misfit. Independently
re-verified design claims against current code:
- `:fable-5` entry structurally identical to `:opus-4.8` (models.clj:171-187):
  only id/name/pricing differ; uses only existing capability fields — no
  catalog schema extension (single-source-of-truth held, per META.md
  model-catalog model).
- Structured output: `anthropic-json-schema-native-model-keys` set
  (models.clj:589-596) + `built-in-structured-output-capability` `case`-on-`:api`
  dispatch (models.clj:598-613); adding `:fable-5` to the set is the canonical
  mechanism — no new dispatch.
- Pure additive data, no default-selection change → `extend: addition >
  modification`; flows through `all-models` (models.clj:630) /
  `built-in-catalog` (model_registry.clj:22-29) automatically.
- Live proof extends existing opt-in `anthropic_models_api_test.clj` rather
  than a parallel verification mechanism.
- One targeted `doc/extension-api.md` enumeration update is a docs-accuracy
  obligation already threaded into scope, not an architectural misfit.

No new design-steps items.

### Ambiguity review (design, 2nd pass) — ψ

Verdict: no new actionable ambiguity. Re-verified design against current code.

- Prior 3 ambiguities (docs scope, changelog obligation, test-extension shape)
  remain resolved in design "Resolved ambiguities"; design-steps all `[x]`.
- Catalog placement unambiguous: provider `:anthropic` + "near-identical to
  `:opus-4.8`" fixes the entry in `anthropic-models`; `all-models` (models.clj:630)
  and `built-in-catalog` (model_registry) derive it automatically — no extra
  registration gap.
- `find-model`/cycling resolvability needs no explicit ordering list:
  `next-model` (session_state/model.clj) cycles registry-derived candidates;
  additive entry flows through.
- Structured-output wiring exact: `built-in-structured-output-capability`
  (models.clj:598-613) dispatches on `:api :anthropic-messages` + set
  membership; adding `:fable-5` to `anthropic-json-schema-native-model-keys`
  (models.clj:589) is unambiguous.
- Cache ratios consistent: `cache-read 1.0`=10×0.1, `cache-write 12.5`=10×1.25,
  matching established per-entry ratio.
- Only soft spot — deftest rename "e.g." names — is cosmetic implementation
  detail, not a design-level decision affecting built behaviour → not actionable.

No new design-steps items.

### Ambiguity review (design, 3rd pass) — ψ

Verdict: no new actionable ambiguity. Independently re-verified design vs code.

- All 3 prior ambiguities (docs scope, changelog obligation, test-extension
  shape) remain resolved in design "Resolved ambiguities"; design-steps `[x]`.
- Final `:fable-5` entry maps field-for-field to `:opus-4.8`
  (models.clj:171-187) — only id/name/pricing differ; every field
  (`:supports-text` included) already exists. Cache ratios explicit and
  consistent (read 1.0=10×0.1, write 12.5=10×1.25). No underspecified field.
- `:cost-tier` is auto-derived by `annotate-model` (models.clj) → not a design
  decision; additive entry flows through `all-models`/`built-in-catalog`.
- Structured-output wiring exact: add `:fable-5` to
  `anthropic-json-schema-native-model-keys` (models.clj:589-596); dispatch via
  `built-in-structured-output-capability` `:anthropic-messages` + set membership
  (models.clj:597-613).
- Test shape matches current `anthropic_models_api_test.clj` (single
  `target-model-id "claude-opus-4-8"`, two opus-named deftests at lines 43/51);
  parameterize-over-set resolution is concrete and fully mappable.
- Deftest rename "e.g." names + map placement are cosmetic, not actionable
  (consistent with prior passes).

No new design-steps items.

### Inconsistency review (design, 4th pass) — ψ

Verdict: no new actionable inconsistency. Independently re-verified design.md
against the referenced artifacts:
- Final `:fable-5` entry vs `:opus-4.8` (models.clj:171-187): only id/name/
  pricing differ; every field exists; cache ratios consistent (read 1.0=10×0.1,
  write 12.5=10×1.25). "structurally near-identical" claim holds.
- Structured-output: `anthropic-json-schema-native-model-keys` (models.clj:589-596)
  + `:anthropic-messages` set-membership dispatch (models.clj:597-613) match
  the design's wiring claim.
- Test-shape claims match current `anthropic_models_api_test.clj` (single
  `target-model-id "claude-opus-4-8"`, two opus-named deftests, list+retrieve).
- `doc/extension-api.md` enumeration text (lines 217-220) matches the quoted
  enumeration; `supports-mid-system-messages?` (model_capabilities.clj:8) returns
  true on explicit support — the required one-line update is coherent.
- `doc/configuration.md` (adaptive "such as" list, l.237-238) and `doc/tui.md`
  (`/model` worked example, l.68-70) are genuinely illustrative, supporting the
  no-change decision; `/model anthropic claude-fable-5` changelog draft matches
  the `/model <provider> <model-id>` syntax (tui.md:58).
- Prior resolved inconsistencies (open-questions cross-ref, pdf fact,
  extension-api.md mischaracterization) remain resolved.
- Below-threshold nits only: keyword `:fable-5` vs `:opus-4.8` dot form, and the
  `doc/extension-api.md:215-220` citation being a superset of the actual 217-220
  enumeration (overlaps, not contradictory) — cosmetic, consistent with prior
  passes treating naming/placement/line-precision as non-actionable.

No new design-steps items.

### Ambiguity review (plan + steps) — ψ

Verdict: actionable ambiguities found (3) in the non-live test specification
(Slice 1 steps 4–6). Live-test shape, catalog entry, structured-output wiring,
docs edit, and changelog are concrete and unambiguous. See steps.md.

- Non-live test target file + add-vs-extend unspecified: Slice 1 says
  "Add/extend a non-live test …" / "Run the focused model/registry test
  namespace(s) green" without naming the namespace or whether to add new
  deftests or extend existing ones. A canonical home already exists:
  `components/ai/test/psi/ai/model_registry_test.clj` has
  `init-built-ins-only-test` (asserts `find-model :anthropic "claude-opus-4-8"`)
  and `built-in-structured-output-capabilities-test` ("Claude Opus 4.8 is
  findable …" block). Builder could reasonably add a new file/deftest or extend
  these — materially different outcomes; design resolved only the *live* test
  shape, not the non-live ones.
- Structured-output assertion path names a private fn: step 5 says assert via
  "(`built-in-structured-output-capability` path)", but that fn is `defn-`
  private (`models.clj:598`). The existing public assertion path is
  `structured-output/effective-capability` on a `find-model` result
  (`:supported? true`, `:native-mechanism :anthropic/json-schema-output`,
  `:provider-native` strategy — model_registry_test.clj:144-151). The step
  should name the public surface + expected values, not an internal path.
- "appears in `all-models`" is namespace-ambiguous: two vars exist —
  `psi.ai.models/all-models` (keyed `:fable-5`) and `registry/all-models`
  (keyed `"claude-fable-5"`). Unqualified `all-models` + differing key forms
  (the plan's own Risks flags the `:fable-5` keyword form) leaves the assertion
  target/key form undecided.

### Inconsistency review (plan + steps) — ψ

Verdict: no new actionable inconsistency. Plan and steps are mutually
consistent and agree with design.md and verified code/docs:

- Slice mapping aligns: plan "Slice order" 1–4 ↔ steps Slice 1–4
  (catalog+native-keys+resolution/structured-output proof / live-test
  parameterize+rename / docs+changelog / verify+finalize).
- Catalog values consistent everywhere: design "Final catalog entry" =
  steps Slice 1 step 1 = plan pricing `10.0/50.0/1.0/12.5`, 1M context,
  128k max-tokens; verified `:opus-4.8` (models.clj:171-187) carries every
  field including `:supports-text`, so the "near-identical/verbatim" claim
  holds. `:fable-5` keyword (no dot) used consistently in both the entry and
  `anthropic-json-schema-native-model-keys` (models.clj:589-596).
- Non-live test target consistent post-resolution: steps name
  `model_registry_test.clj` and extend `init-built-ins-only-test`
  (find-model + `built-in/all-models` keyword membership) and
  `built-in-structured-output-capabilities-test` (public
  `structured-output/effective-capability` assertions mirroring the Opus 4.8
  block) — verified both deftests exist (lines 47, 103) and the ns requires
  `[psi.ai.models :as built-in]`.
- Live-test shape consistent with design: parameterize
  `target-model-ids #{"claude-opus-4-8" "claude-fable-5"}`, `every?` in
  list-includes, `doseq`/`testing` in retrieve, rename to
  `…-targets-test`, retain `^:integration` + `with-live-models-api` —
  matches current `anthropic_models_api_test.clj` (single `target-model-id`,
  two opus-named deftests).
- Docs/changelog consistent: steps Slice 3 edits the
  `doc/extension-api.md` enumeration at the verified line (217:
  "Support is true for Claude Opus 4.8 and for OpenAI chat-completions
  models …") to add Fable 5, and adds the design's exact `[Unreleased] →
  Added` draft; CHANGELOG `[Unreleased]` exists (line 7). The illustrative
  `configuration.md`/`tui.md` examples are excluded in both plan and steps.
- Below-threshold nit only (non-actionable, consistent with prior passes):
  Slice 1's final step retains plural "namespace(s)" though the resolution
  pinned a single namespace — cosmetic wording residue, not a contradiction.

No new steps.md follow-ups.

### Plan/steps ambiguity review follow-ups — resolved (steps) — ψ

The three plan/steps ambiguity follow-ups (added by the plan/steps ambiguity
review pass) are resolved by disambiguating Slice 1 steps 4–5 in steps.md; no
production code/tests/docs changed (the underlying Slice 1 implementation steps
remain unchecked and predate this review pass, so they were intentionally not
executed here).

- Test target pinned: Slice 1 steps 4–5 now name
  `components/ai/test/psi/ai/model_registry_test.clj` and direct **extending**
  the existing `init-built-ins-only-test` and
  `built-in-structured-output-capabilities-test` deftests (no new deftests).
- Private-fn reference removed: step 5 now specifies the public path
  `structured-output/effective-capability` on the `find-model` result with the
  three public assertions (`:supported? true`,
  `:native-mechanism :anthropic/json-schema-output`,
  `:provider-native` ∈ `(set (:strategies …))`), mirroring the existing Opus 4.8
  block (model_registry_test.clj:144-151). Verified `built-in-structured-output-capability`
  is `defn-` private at models.clj:598.
- `all-models` disambiguated: step 4 asserts `built-in/all-models`
  (`psi.ai.models/all-models`) keyed by keyword `:fable-5`, and
  `registry/find-model :anthropic "claude-fable-5"` (string id). Verified the
  test ns already requires `[psi.ai.models :as built-in]` and uses
  `built-in/all-models` keyed by keyword in `all-models-by-key-test`.

### Ambiguity review (plan + steps, 2nd pass) — ψ

Verdict: no new actionable ambiguity. Independently re-verified plan/steps
against the live referenced artifacts:
- Prior 3 plan/steps ambiguities (non-live test target, private-fn assertion
  path, `all-models` namespace/key form) remain resolved in steps Slice 1
  steps 4–5; follow-ups all `[x]`.
- Confirmed in code: `init-built-ins-only-test` (model_registry_test.clj:47)
  and `built-in-structured-output-capabilities-test` (line 103, Opus 4.8 block
  144-151) exist; ns aliases `built-in` (psi.ai.models) and `structured-output`
  present (lines 5-6). Steps map cleanly to these.
- Live-test rewrite unambiguous against current
  `anthropic_models_api_test.clj`: single `target-model-id "claude-opus-4-8"`
  (line 7), `ids` bound as `(set (map :id …))` (the `(every? (set ids) …)`
  step is well-defined), two opus-named deftests at lines 41/49 to rename.
- Docs edit target verified: `doc/extension-api.md:217` reads "Support is true
  for Claude Opus 4.8 and for OpenAI chat-completions models …" — Slice 3 edit
  is concrete.
- Below-threshold nits only (non-actionable, consistent with prior passes):
  Slice 1 step 6 retains plural "namespace(s)" after the single-ns resolution;
  Slice 2 step 2 specifies only the `every?` assertion without restating the
  existing `200` status check (behaviour-preserving, obvious); Slice 4 "non-live
  suite" is the env-gated skip path, not a separate selector. Cosmetic, not
  contradictions.

No new steps.md follow-ups.

### Inconsistency review (plan + steps, 3rd pass) — ψ

Verdict: actionable inconsistency found (1). See steps.md.

- Plan vs steps decision-provenance mismatch: plan.md heads its decision list
  "Key decisions (all inherited from design, no new decisions required)" and its
  Approach/Key-decisions deliverable enumeration lists only catalog entry +
  native-keys + live-test parameterization + changelog + one doc edit. But steps
  Slice 1 makes the **non-live** `model_registry_test.clj` work central
  (extend `init-built-ins-only-test` + `built-in-structured-output-capabilities-test`,
  public `structured-output/effective-capability` assertion path,
  `:fable-5`/`"claude-fable-5"` key forms). That non-live test *structure* was
  decided during the plan/steps ambiguity-review pass — it is **not** in
  design.md, which resolved only the *live* test shape (confirmed: design names
  only `anthropic_models_api_test.clj`; implementation.md's own ambiguity review
  states "design resolved only the *live* test shape, not the non-live ones").
  So plan's "no new decisions required / all inherited from design" claim and
  its deliverable list are stale relative to steps. Now carried into steps.md as
  a follow-up.
- Re-verified other plan/steps claims hold against code/docs: catalog values
  (`10.0/50.0/1.0/12.5`, 1M, 128k) match design; `doc/extension-api.md:217`
  enumeration text matches; renamed live deftests target the two opus-named
  deftests at lines 43/51; CHANGELOG `[Unreleased] → Added` exists.

### Plan/steps inconsistency review follow-up — resolved (plan) — ψ

The plan/steps inconsistency follow-up (plan decision-provenance vs non-live
test steps) is resolved in plan.md; steps marked done. No production
code/tests/docs changed — the underlying Slice 1–4 implementation steps remain
unchecked and predate this review pass, so they were intentionally not executed.

- Reframed the Key-decisions header: "inherited from design" now scoped to the
  catalog/live-test/docs decisions; the non-live test *structure* is explicitly
  attributed to the plan/steps ambiguity-review pass (design.md resolved only
  the live-test shape).
- Added an explicit plan deliverable for the non-live `model_registry_test.clj`
  work: extend `init-built-ins-only-test` (`find-model` string id +
  `built-in/all-models` keyword `:fable-5`) and
  `built-in-structured-output-capabilities-test` (public
  `structured-output/effective-capability` assertions mirroring the Opus 4.8
  block). Plan and steps now agree on decision provenance.

### Ambiguity review (plan + steps, 3rd pass) — ψ

Verdict: actionable ambiguity found (1). See steps.md.

- "Mirror the Opus 4.8 block" vs three-assertion enumeration conflict
  (Slice 1 step 5): steps direct a Fable 5 `testing` block that "mirrors the
  existing Claude Opus 4.8 block" but then enumerate only the three
  structured-output assertions (`:supported? true`, `:native-mechanism
  :anthropic/json-schema-output`, `:provider-native` ∈ `:strategies`). The
  actual Opus 4.8 block (model_registry_test.clj:144-151) additionally asserts
  `(some? model)`, `(= "Claude Opus 4.8" (:name model))`,
  `(= true (:adaptive-thinking model))`, and
  `(= true (:supports-mid-conversation-system-messages model))`. "Mirror" plus
  the narrower three-assertion list leaves it undecided whether the Fable 5
  block also asserts the catalog-metadata fields. This is material coverage:
  no other step asserts Fable 5's `:name "Claude Fable 5"`, `:adaptive-thinking`,
  or `:supports-mid-conversation-system-messages` values (Slice 1 step 4 only
  asserts `find-model` presence + `built-in/all-models` keyword membership), so
  the acceptance criterion "Fable 5 appears … with … capabilities … matching
  the agreed spec" would be unverified by the non-live suite under the
  three-assertion reading.
- Re-verified prior plan/steps ambiguity resolutions still hold against code:
  `init-built-ins-only-test` (line 47) and
  `built-in-structured-output-capabilities-test` (line 103, Opus block 144-151)
  exist; live test still has single `target-model-id "claude-opus-4-8"` and two
  opus-named deftests (lines 43/51); `doc/extension-api.md` enumeration present.

### Plan/steps ambiguity review follow-up (3rd pass) — resolved (steps) — ψ

The 3rd-pass plan/steps ambiguity follow-up ("mirror Opus 4.8 block" vs the
narrower three-assertion enumeration in Slice 1 step 5) is resolved by
disambiguating Slice 1 step 5 in steps.md. No production code/tests/docs
changed — the underlying Slice 1–4 implementation steps remain unchecked and
predate this review pass, so they were intentionally not executed here.

- Decision: **full mirror**. Slice 1 step 5 now directs a Fable 5 `testing`
  block that asserts the catalog-metadata fields (`(some? model)`,
  `(= "Claude Fable 5" (:name model))`, `(= true (:adaptive-thinking model))`,
  `(= true (:supports-mid-conversation-system-messages model))`) **and** the
  structured-output capability surface (`:supported? true`, `:native-mechanism
  :anthropic/json-schema-output`, `:provider-native` ∈ `:strategies`) — all
  seven assertions enumerated in order, mirroring the Opus 4.8 block
  (model_registry_test.clj:144-151).
- Rationale: Slice 1 step 4 only asserts Fable 5's `find-model` presence +
  `built-in/all-models` keyword membership; no step asserts the
  name/adaptive-thinking/mid-system-message field values. The full mirror is
  therefore required for the non-live suite to cover the acceptance criterion
  "Fable 5 appears … with … capabilities … matching the agreed spec." Choosing
  the narrower three-assertion reading would have left that criterion
  unverified outside the opt-in live suite.

### Inconsistency review (plan + steps, 4th pass) — ψ

Verdict: no new actionable inconsistency. Independently re-verified plan/steps
against design.md and the live referenced artifacts:
- Slice mapping aligned: plan "Slice order" 1–4 ↔ steps Slice 1–4.
- Catalog values consistent (design "Final catalog entry" = steps Slice 1 step 1
  = plan `10.0/50.0/1.0/12.5`, 1M, 128k); `:opus-4.8` entry (models.clj:171) and
  `:fable-5` ∈ `anthropic-json-schema-native-model-keys` (models.clj:596) verified.
- Non-live test target + decision-provenance reconciled (prior 3rd-pass
  follow-up resolved): plan now scopes "inherited from design" to
  catalog/live-test/docs and attributes the non-live test structure to the
  ambiguity-review pass; steps agree.
- Full-mirror assertion set agrees between plan (Key decisions) and steps
  (Slice 1 step 5): same seven assertions in the same order; verified against
  the actual Opus 4.8 block (model_registry_test.clj:143-152).
- Live-test parameterization/rename consistent with design and current
  `anthropic_models_api_test.clj` (single `target-model-id "claude-opus-4-8"`
  line 8; opus-named deftests lines 43/51; `with-live-models-api` + `^:integration`).
- Docs/changelog consistent: Slice 3 edits verified `doc/extension-api.md:217`
  enumeration and adds design's exact `[Unreleased] → Added` draft; illustrative
  `configuration.md`/`tui.md` excluded in both plan and steps.
- Below-threshold nits only (non-actionable, consistent with prior passes):
  the `model_registry_test.clj:144-151` citation is one line short of the actual
  144-152 block, and Slice 1's final step retains plural "namespace(s)" after the
  single-ns resolution. Cosmetic, not contradictions.

No new steps.md follow-ups.

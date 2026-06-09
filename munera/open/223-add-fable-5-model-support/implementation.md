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

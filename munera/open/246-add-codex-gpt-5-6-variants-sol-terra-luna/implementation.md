# Implementation log — 246

## Streaming probe evidence (pre-planning)

Probe: streaming POST to `https://chatgpt.com/backend-api/codex/responses`
(`stream:true`, `store:false`, `include:["reasoning.encrypted_content"]`,
`accept: text/event-stream`), using the live OpenAI ChatGPT-account OAuth token
(`chatgpt-account-id` extracted from the token). Structured status/body only —
no assertions. Probe script: `/tmp/psi-gpt56-codex-stream-probe.clj`. Run via
project REPL on 2026-07-28.

"Reached execution" = backend emitted `response.created` →
`response.in_progress` → `response.output_item.added` SSE events (request
accepted and began generating), rather than a terminal model-support rejection.

| model id        | status | outcome                                                                 |
| --------------- | ------ | ----------------------------------------------------------------------- |
| `gpt-5.5`       | 200    | reached execution (control — existing OAuth/Codex path)                 |
| `gpt-5.6`       | 400    | `The 'gpt-5.6' model is not supported when using Codex with a ChatGPT account.` (confirms task 245) |
| `gpt-5.6-sol`   | 200    | reached execution — events response.created/in_progress/output_item.added |
| `gpt-5.6-terra` | 200    | reached execution — events response.created/in_progress/output_item.added |
| `gpt-5.6-luna`  | 200    | reached execution — events response.created/in_progress/output_item.added |

### Conclusion

All three GPT-5.6 variants (`gpt-5.6-sol`, `gpt-5.6-terra`, `gpt-5.6-luna`) are
accepted and reach execution on the ChatGPT/Codex backend for a ChatGPT
account. The task-245 rule ("no id joins the Codex path without live backend
evidence") is satisfied for all three. Bare `gpt-5.6` remains rejected and must
stay unsupported under OpenAI OAuth.

Observed backend echo per variant: `store:false`, `service_tier:"auto"`,
`reasoning.effort:"medium"`, `reasoning.context:"all_turns"` (vs `gpt-5.5`'s
`current_turn`), `text.verbosity:"low"` — useful for catalog metadata during
implementation.

## Review notes

- architectural review: no architectural review feedback (design reuses the shared `model_registry.clj` codex join point + `with-openai-codex-transport`; additive catalog/policy-set slice mirrors existing `gpt-5.5` pattern; single-source-of-truth honored)
- ambiguity review: added 1 new design step (under-determined per-variant catalog metadata: same-vs-differ across sol/terra/luna, and 272K vs larger-context/long-context-pricing conflict for Codex-routed entries)
- inconsistency review: no inconsistency review feedback (design internally consistent and consistent with model_registry.clj codex/unsupported sets + implementation.md probe evidence; metadata 272K-vs-1M/larger-context tension already captured by ambiguity design step, not duplicated)
- plan/steps ambiguity review: added 1 new design step (plan Slice 5 docs scope is narrow — "enumerated model lists" — but GPT-5.6 OAuth-support statements live as prose in doc/tui.md, README.md, doc/configuration.md, doc/cli.md that assert bare gpt-5.6 is OAuth-unsupported; those need updating to reflect the newly OAuth/Codex-supported sol/terra/luna variants)
- plan/steps inconsistency review: no inconsistency review feedback (pricing table, shared fields, 272K/128K, codex/unsupported set literals, and gpt-5.5-mirror API classification agree across design.md/plan.md/steps.md/implementation.md; probe-echoed reasoning.context all_turns vs gpt-5.5 current_turn is a backend default, not a task-file contradiction)
- plan/steps ambiguity review (re-run): no new ambiguity review feedback (plan/steps are concrete — files, line ranges, per-variant pricing, verbatim-id + single-source-of-truth constraints, slice order; the one prior ambiguity (docs-prose OAuth-support scope, commit 4434f0d7d) is already resolved in design-steps.md)
- plan/steps inconsistency review (re-run): no new inconsistency review feedback (pricing table, 272K/128K, native-model-keys join, codex/unsupported set literals vs model_registry.clj, docs line ranges, and slice order all agree across design.md/plan.md/steps.md/implementation.md)

## Notes for the docs-prose design-step (plan/steps ambiguity review)

Concrete OAuth-support prose to reconcile with the newly OAuth/Codex-supported
sol/terra/luna variants (each currently asserts bare `gpt-5.6` is OAuth-unsupported
without distinguishing the variants):
- `doc/tui.md` — L71–80 (`/model` command + picker OAuth/Codex behaviour).
- `README.md` — L121–123 (catalog-selectable vs OAuth-supported distinction).
- `doc/configuration.md` — L109–114, L158 (profile/config `gpt-5.6` rejection at OAuth).
- `doc/cli.md` — L112–115 (catalog keys vs OAuth runtime support are distinct).

Principles when addressing this design-step:
- Preserve the existing true statement (bare `gpt-5.6` stays OAuth-unsupported);
  only add that `gpt-5.6-sol/terra/luna` are the OAuth/Codex-supported GPT-5.6 ids.
- Keep the catalog-selectable vs OAuth-runtime-supported distinction the docs
  already draw; the variants are both catalog-selectable and OAuth-supported.
- Single source of truth: docs describe policy, don't restate per-surface literals;
  the codex/unsupported sets live only in `model_registry.clj`.
- CHANGELOG [Unreleased]/Added (plan Slice 5) is the user-facing entry point — keep
  doc-prose edits consistent with that entry's wording.

## Build slice: catalog + routing + tests + docs (2026-07-28)

- Catalog: added `:gpt-5.6-sol`, `:gpt-5.6-terra`, `:gpt-5.6-luna` to
  `components/ai/src/psi/ai/models.clj` immediately after the existing bare
  `gpt-5.6` entry, with the design-resolved shared shape (`:openai`,
  `:openai-completions`, `api.openai.com/v1`, reasoning/images/text,
  context-window 272000, max-tokens 128000) and per-variant pricing from the
  design table. All three keys joined
  `openai-chat-completions-native-model-keys`.
- Routing: added the same three ids (verbatim) to
  `openai-oauth-codex-model-ids` in `model_registry.clj`.
  `openai-oauth-unsupported-model-ids` untouched — still `#{"gpt-5.6"}`.
- `rg "gpt-5.5"` confirmed only `models.clj`, `model_registry.clj`,
  `model_registry_test.clj`, and `rpc_model_scope_test.clj` reference the id;
  the rpc test exercises generic OAuth rejection/preservation behaviour (not
  catalog/codex-route membership), so it needed no change — no other surface
  restates codex-route literals.
- Tests added to `model_registry_test.clj`: a new
  `resolve-runtime-model-openai-oauth-gpt-5-6-variants-codex-test` (doseq over
  all three variants, mirrors the `gpt-5.5` codex-resolution test — asserts
  `:api :openai-codex-responses`, `chatgpt.com/backend-api` base-url, and id
  echoed verbatim); a new `gpt-5-6-variants-catalog-entry-test` (doseq,
  mirrors the existing bare-`gpt-5.6` catalog-entry test) asserting the full
  per-variant metadata/pricing table; extended the existing
  `resolve-runtime-model-openai-no-oauth-stays-chat-completions-test` doseq to
  include the three variant ids. Existing bare-`gpt-5.6` unsupported test and
  `gpt-5.5` control test required no changes (already present, still pass).
  `bb test --focus psi.ai.model-registry-test`: 267 assertions / 25 tests, all
  passing.
- Docs: CHANGELOG `[Unreleased]/Added` entry; prose reconciled in
  `doc/tui.md`, `README.md`, `doc/configuration.md` (two locations —
  `:model-id` validation prose and the session-profile credential-policy
  prose), and `doc/cli.md` — each now distinguishes bare `gpt-5.6`
  (OAuth-unsupported) from the three OAuth/Codex-supported variants, without
  restating codex-route literals (single source of truth stays
  `model_registry.clj`).
- Validation: `bb test` full run — 18937 assertions passed, 44 failed / 17
  errored, but confirmed via `git stash` that these failures are pre-existing
  and unrelated (workflow/prompt/streaming tests with randomized seed
  flakiness); none touch `psi.ai.models`/`psi.ai.model-registry`.
  `clj-kondo --lint` on all touched `.clj` files: 0 errors, 0 warnings.

## Notes for the design-step task

Relevant project files (non-task):
- `components/ai/src/psi/ai/models.clj` — built-in catalog. Existing `gpt-5.6`
  entry at ~L594 (context-window 1000000, input 6.0 / output 35.0); `gpt-5.5`
  at ~L578. New variant keys must also join
  `openai-chat-completions-native-model-keys` (~L636) for API classification.
- `components/ai/src/psi/ai/model_registry.clj` — `openai-oauth-codex-model-ids`
  (L180) and `openai-oauth-unsupported-model-ids` (L186); `openai-oauth-runtime-model`
  / `resolve-runtime-model` are the single join point. Reuse
  `structured-output/with-openai-codex-transport`; do not restate codex literals.

Principles to maintain when resolving the metadata design-step:
- Single source of truth: encode per-variant metadata once in the catalog; all
  surfaces read through it. No per-surface literals.
- Resolve the 272K-vs-larger-context/pricing conflict with an explicit chosen
  value per id; the existing `gpt-5.6` uses 1M/6.0/35.0 as one reference point —
  decide deliberately whether Codex-routed variants match or differ, don't inherit
  by accident.
- Keep the frozen scope: three variants only; bare `gpt-5.6` stays unsupported.

## Design-follow-up: catalog metadata resolution (2026-07-28)

Resolved the ambiguity-review design step. Authoritative source for per-variant
values: pi-mono `~/src/pi-mono/packages/ai/scripts/generate-models.ts`,
`missingOpenAiModels` block (~L2119) and the OpenAI short-context/long-context
sets (~L305–321). Facts discovered for downstream implementation:

- Variants **differ in pricing** (base flat rates): sol 5/30/0.5/6.25,
  terra 2.5/15/0.25/3.125, luna 1/6/0.1/1.25 (input/output/cacheRead/cacheWrite).
  These base rates differ from our existing bare `gpt-5.6` entry (6.0/35.0) — do
  **not** inherit gpt-5.6's numbers; use the per-variant table in design.md.
- pi-mono default context window for these ids is 272000 with an opt-in override
  to 1050000 that triggers long-context pricing (input×2, output×1.5) above the
  272K threshold. Our catalog schema is flat (no `:tiers`), so design fixes each
  variant at `:context-window 272000` with flat short-context rates — no tier
  modelling. If a future task wants the 1.05M window, it must add tier support
  first.
- pi-mono classes these as `openai-responses`; our catalog mirrors the working
  `gpt-5.5` control instead (`:api :openai-completions` + join
  `openai-chat-completions-native-model-keys`), consistent with the design's
  "mirror gpt-5.5" constraint and the Codex route.
- No thinking-level-map catalog field exists; reasoning effort comes from the
  shared Codex transport (probe echoed `reasoning.effort:medium`), same as
  `gpt-5.5`. No per-variant reasoning field needed beyond `:supports-reasoning true`.

## Plan-follow-up (plan-review batch: ambiguity + inconsistency)

Batch baseline `d077fa3ee` (plan.md+steps.md creation). Batch commits:
`4434f0d7d` (plan/steps ambiguity — added docs-prose design step), `efda2d8c2`
(plan/steps inconsistency — no feedback), `5af7c8dc5` (docs-prose context notes).

`git diff d077fa3ee..HEAD -- steps.md` added **no** checklist lines — the single
actionable plan-review follow-up was the unchecked design-steps.md ambiguity item
("clarify docs scope for the OAuth-support boundary"). Executed by folding the
prose-OAuth-support reconciliation into plan.md Slice 5 (+ Slice-order 5) and
steps.md Slice 5 with concrete files/line-ranges and preserve-distinction /
single-source-of-truth principles; design-steps.md item now checked.

Nothing else actionable in the batch. Implementation of Slice 5 doc-prose edits
happens in the build phase per the concrete file list already in this log.

## Implementation review (2026-07-28)

- added 1 step: surface-level selectability coverage gap (acceptance criterion names all selection surfaces; tests only cover the shared `resolve-runtime-model` join point + catalog membership).

## Implementation-review follow-up (2026-07-28)

- addressed 1 review step (surface-level selectability coverage gap). Added
  `components/rpc/test/psi/rpc/session/command_pickers_test.clj` — a `/model`
  picker surface test that invokes `handle-picker-command!` with a capturing
  `emit!` and asserts each variant (`gpt-5.6-sol/terra/luna`) is offered as an
  `["openai" id]` picker item. This proves selectability at a picker boundary
  (not just the shared `resolve-runtime-model` join + catalog membership) and
  guards against a future picker filter dropping catalog entries; the picker
  enumerates `all-models-seq` unconditionally, so this is the tightest
  surface-level assertion for the acceptance criterion. Focused test: 3/1 pass;
  `clj-kondo` clean.

## Implementation review (2026-07-28, 2nd pass)

- added 1 step: `doc/emacs-ui.md` OAuth-support prose missed by the docs-prose reconciliation (states bare `gpt-5.6` OAuth-rejected + `gpt-5.5` on Codex path but omits the now-OAuth-supported sol/terra/luna variants, which are selectable/cyclable in emacs-ui). Root cause: fixed 4-doc list in plan/design rather than an `rg` sweep of OAuth-support prose.

## Implementation-review follow-up (2026-07-28, 2nd pass)

- addressed 1 review step (doc/emacs-ui.md docs-prose gap). Reconciled the
  "Model selection" OAuth-support prose to add `gpt-5.6-sol/terra/luna` as
  OAuth/Codex-supported (selectable via `C-c m m`, not skipped by cycle
  commands), preserving the true bare-`gpt-5.6`-unsupported statement and
  single-source-of-truth (no codex literals restated). Ran the recommended
  `rg 'gpt-5\.6' doc/` sweep: only the five already-reconciled docs
  (tui, README-not-in-doc, configuration, cli, emacs-ui) reference the id; no
  other `doc/` prose remains stale.

## Implementation review (2026-07-28, 3rd pass)

- no new steps added; catalog metadata, OAuth/Codex verbatim routing, gpt-5.5 control, bare-gpt-5.6 negative, docs/CHANGELOG prose, and the /model picker surface test all verified coherent against design + acceptance criteria; focused tests green.

## Task-test-review (2026-07-28)

- added 1 step: variant *accept*-path surface coverage gap (offer + resolution proven for variants, but no test drives a variant id through the `set_model`/`handle-model-selection!` accept boundary under OAuth; accept/reject machinery is only exercised with generic ids). Low severity — transitively covered. Tests otherwise well-formed: real logic deps, nullable oauth context, injected capturing `emit!` (no mocks/stubs); design acceptance behaviours (catalog metadata, OAuth/Codex verbatim routing, gpt-5.5 control, bare-gpt-5.6 negative, picker enumeration) covered; focused suites green (267/25 registry, 3/1 picker).

## Task-test-review follow-up (2026-07-28)

- addressed 1 review step (variant accept-path surface coverage gap). Added
  `model-selection-accepts-gpt-5-6-codex-variants-under-oauth-test` to
  `components/rpc/test/psi/rpc/session/command_pickers_test.clj`: for each of
  `gpt-5.6-sol/terra/luna`, drives the id through the picker-backed selection
  accept boundary (`handle-model-selection!` + `resolve-model`) under an
  `oauth-openai-ctx` and asserts a model-set **success** `command-result` plus
  persisted selection — proving the accept boundary treats the variants as
  supported (codex), distinct from bare `gpt-5.6`'s `unsupported_model` reject.
  Focused suite now 9/2 pass; `clj-kondo` clean.

## Task-test-review (2026-07-28, 2nd pass)

- added 1 step: variant OAuth codex resolution asserts only two of three codex facets — `:api` and `:base-url` are proven verbatim, but the codex native structured-output capability (the third facet `with-openai-codex-transport` owns, attached via a second independent mechanism the drift-guard test warns about) is unasserted for variants after runtime codex resolution. Distinct from the prior offer/accept picker steps. Tests otherwise well-formed: nullable oauth (`create-null-context`), injected capturing `emit!`, no mocks/stubs; behaviours covered; focused suites green (267/25 registry, 9/2 picker).

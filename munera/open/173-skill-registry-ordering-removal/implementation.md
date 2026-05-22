# Implementation

Task created to resolve whether `skill-registry` really requires preserved registration order, and to remove that ordering from the contract if it is only accidental.

Audit notes from refinement:

- `:session/register-skill` uses only `:changed?` to decide whether to emit `:runtime/refresh-system-prompt`; it does not depend on insertion order
- prompt/discovery consumers read `all-skills`, `skill-names`, or `find-skill`, but the observed need is deterministic listing and exact-name lookup rather than "show skills in the order they were registered"
- workflow child-session shaping resolves explicit skill names through exact-name lookup and does not consume registry order
- current direct proof of insertion-order semantics lives mainly in `skill-registry` unit tests and task `164` audit text, not in a caller that meaningfully branches on registration sequence

Refined likely direction:

- preserve duplicate-ignore and `:added?` / `:changed?`
- drop insertion order as registry semantics if the remaining audit confirms no real caller dependence
- replace it with canonical name-sorted read surfaces so higher prompt/discovery callers stay deterministic without each re-sorting independently

## 2026-05-22 ambiguity review

Found actionable ambiguities: the task did not define `design-steps.md` despite requesting follow-ups there; "canonical `:name` order" lacks comparator/case/locale precision; the artifacts do not say whether sorted order is a read-projection contract or stored `:skills` / `register-skill` result contract; prompt/display/introspection surfaces are named broadly but raw-vector consumers make the affected surface set unclear; task `164` update scope is underspecified.


## 2026-05-22 ambiguity review follow-up

Found one additional actionable ambiguity: the design allows outcome A (keep registration order if a real dependency exists), but `plan.md` / `steps.md` are written only for the removal path and do not say what implementation, tests, or task `164` update should happen if the audit proves insertion order is required.


## 2026-05-22 ambiguity follow-up execution

Completed all newly added ambiguity follow-up items in `design-steps.md`. The task artifacts now explicitly separate `design-steps.md` as the ambiguity follow-up surface from `steps.md` as the implementation checklist, define canonical skill-name ordering as case-sensitive locale-independent JVM string order, clarify that registry outputs/results and user/model-visible projections must be canonical while arbitrary incoming session vectors are not trusted ordering contracts, enumerate prompt/display/introspection surfaces to audit, define task `164` update scope for both removal and keep-order branches, and add the explicit keep-order branch to `plan.md` and `steps.md`.

## 2026-05-22 inconsistency review

Found one actionable inconsistency: `design.md` keeps outcome C open (registry-layer ordering non-semantic with presentation/prompt layers sorting), but `plan.md`, `steps.md`, and the task `164` update scope only define the canonical name-sorted removal branch or the keep-order branch.

## 2026-05-22 inconsistency follow-up execution

Completed the newly added `design-steps.md` inconsistency follow-up. Outcome C remains viable in `design.md` and now has matching execution guidance: branch C means registry-layer order-insensitive membership/count/exact lookup, with deterministic canonical sorting owned only by higher prompt/display/introspection surfaces. Updated `plan.md`, `steps.md`, and the task `164` update scope guidance to distinguish branch B canonical registry listing, branch C presentation-owned sorting, and the keep-order branch.

## 2026-05-22 ambiguity review

No new actionable ambiguity feedback. Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, task `164` audit excerpts, and representative skill registry / resolver / prompt / TUI surfaces; the current artifacts now define the decision branches, follow-up surfaces, ordering contract candidates, affected surfaces, and task `164` update expectations clearly enough for implementation.

## 2026-05-22 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md`; all ambiguity follow-up steps remain complete, so no design, plan, or implementation checklist changes were needed.

## 2026-05-22 inconsistency review

Found one actionable inconsistency: `design.md` says `design-steps.md` is the actionable surface for ambiguity-review follow-up items only, but this task's prior inconsistency review already used `design-steps.md` for inconsistency follow-up and the current requested review protocol also requires inconsistency follow-ups there.

## 2026-05-22 inconsistency follow-up execution

Completed the newly added `design-steps.md` inconsistency follow-up. Updated `design.md` so `design-steps.md` is the design-review follow-up surface for both ambiguity-review and inconsistency-review items, and clarified that `steps.md` remains reserved for later implementation execution rather than design-review follow-up execution.

## 2026-05-22 ambiguity review

No new actionable ambiguity feedback. Rechecked `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, task `164` audit references, and representative registry/resolver/prompt/TUI code paths; the branch choices, canonical ordering candidate, branch C alternative, keep-order fallback, affected surfaces, and task `164` update expectations remain clear enough for implementation.

## 2026-05-22 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md` after the preloaded no-feedback ambiguity review. All design follow-up steps remain complete, so no `design.md`, `plan.md`, or `steps.md` changes were needed.

## 2026-05-22 inconsistency review

No new actionable inconsistency feedback. Rechecked `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, task `164` audit/update guidance, and representative skill registry/resolver/prompt code paths; the artifacts now consistently distinguish branch B canonical registry listing, branch C presentation-owned sorting, and the keep-order fallback, with `design-steps.md` as the shared design-review follow-up surface.

## 2026-05-22 inconsistency follow-up execution

No newly added unchecked inconsistency follow-up items were present in `design-steps.md` after the preloaded no-feedback inconsistency review. All design follow-up steps remain complete, so no `design.md`, `plan.md`, or `steps.md` changes were needed.

## 2026-05-22 ambiguity review

No new actionable ambiguity feedback. Re-read `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, task `164` skill-registry audit text, and representative registry/resolver/prompt/TUI/workflow skill-order call sites; the artifacts still specify the branch decision criteria, selected-ordering proof expectations, affected ordered surfaces, design-review follow-up surface, and task `164` update scope clearly enough for implementation.

## 2026-05-22 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md` after the preloaded no-feedback ambiguity review. All design follow-up steps remain complete, so no `design.md`, `plan.md`, or `steps.md` changes were needed.

## 2026-05-22 inconsistency review

Found one actionable inconsistency: `design.md` lists `psi.agent-session.prompt_request` among prompt construction / ordered skill-list surfaces that may need canonical ordering, but the referenced code path only performs exact `/skill:name` input expansion via lookup and does not render or project an ordered skill list. This conflicts with the task's distinction between ordered listing surfaces and exact-name resolution surfaces.

## 2026-05-22 inconsistency follow-up execution

Completed the newly added `design-steps.md` inconsistency follow-up. Reclassified `psi.agent-session.prompt_request` in `design.md`: prompt lifecycle / `system_prompt` paths remain ordered skill-list surfaces when they render skills, while `prompt_request` is only an exact `/skill:name` lookup-expansion surface and does not own or consume canonical skill-list ordering.

## 2026-05-22 ambiguity review

No new actionable ambiguity feedback. Re-read `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, task `164` skill-registry audit text, and representative registry/resolver/session-resource/prompt/TUI/app-runtime/workflow call sites after the latest `prompt_request` reclassification; the artifacts still define the branch decision criteria, canonical ordering candidate, branch C presentation-owned alternative, keep-order fallback, affected surfaces, proof expectations, and task `164` update scope clearly enough for implementation.

## 2026-05-22 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md` after the preloaded no-feedback ambiguity review. All design follow-up steps remain complete, so no `design.md`, `plan.md`, or `steps.md` changes were needed.

## 2026-05-22 inconsistency review

No new actionable inconsistency feedback. Re-read `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, task `164` skill-registry audit/update guidance, and representative registry/resolver/session-resource/prompt/TUI/app-runtime/workflow skill-order call sites; the task artifacts remain consistent about branch B canonical registry listing, branch C presentation-owned sorting, the keep-order fallback, exact-lookup-only surfaces, proof expectations, and task `164` update scope.

## 2026-05-22 inconsistency follow-up execution

No newly added unchecked inconsistency follow-up items were present in `design-steps.md` after the preloaded no-feedback inconsistency review. All design follow-up steps remain complete, so no `design.md`, `plan.md`, or `steps.md` changes were needed.

## 2026-05-22 ambiguity review

Found one new actionable ambiguity: `design.md` says workflow child-session requested skill order may remain caller-specified selection order, while branch B/C require canonical ordering for prompt/display/model-visible surfaces and `system_prompt/filter-skills` currently preserves parent/session order when applying `:skill-names` subset filtering. The task does not yet say whether prompt-component / workflow subset output should be caller selection order, canonical skill-name order, or inherited registry/session order, nor whether that surface is included in the branch B/C changes.

## 2026-05-22 ambiguity follow-up execution

Completed the newly added prompt-component / workflow `:skill-names` ambiguity follow-up. Clarified that `:skill-names` is an allowlist rather than an ordering directive: caller-declared order is only input metadata, inherited parent/session vector order must not become model-visible ordering, and rendered/projected selected skill subsets should use canonical skill-name order under branch B or branch C. Updated `plan.md` so both branch B and branch C explicitly include prompt-component / workflow filtered skill subsets in their ordering proof scope.

## 2026-05-22 inconsistency review

Found one new actionable inconsistency: the artifacts reclassify workflow child-session skill resolution as exact-name lookup only and clarify prompt-component / workflow `:skill-names` allowlist ordering, but workflow step `:session :skills` still declares a selected skill list whose resolved order is carried into child-session `:skills` and model-visible prompt construction. The task artifacts do not say whether that workflow `:skills` selection order is intentionally caller-declared, canonicalized under branch B/C, or exact-lookup-only metadata.

## 2026-05-22 inconsistency follow-up execution

Completed the newly added workflow step `:session :skills` ordering follow-up. Clarified that workflow step skill selection is an allowlist/exact-name resolution input, not an ordering directive: resolution may use caller order internally, but any model-visible child prompt rendering of the selected subset must canonicalize by skill `:name` under branch B or branch C. Updated `design.md`, `plan.md`, and `steps.md`; marked the `design-steps.md` item complete.


## 2026-05-22 implementation pass

Selected branch B. The audit found no true registration-order dependency: callers require exact lookup, duplicate no-op/change reporting, and deterministic prompt/discovery/model-visible ordering. Updated `skill-registry` so `all-skills`, `skill-names`, and `register-skill` result `:skills` are canonical by exact skill `:name` string order while `find-skill` remains exact lookup over supplied collections and duplicate registration remains first-write-wins/no-change.

Canonicalized higher visible surfaces that could otherwise expose raw session/vector order: prompt skill formatting, prompt skill summaries, visible/hidden skill helpers, `:psi.agent-session/skills`, prompt-component `:skill-names` filtering, and workflow step `:session :skills` resolution. Added focused tests for registry ordering, prompt ordering, session resource ordering, prompt-component selected subset ordering, and workflow-selected skill ordering. Updated task `164` to mark insertion-order preservation as superseded by canonical deterministic skill-name ordering while preserving duplicate-ignore and `:added?` / `:changed?`.

Verification: `clojure -M:test --focus psi.skill-registry.registry-test --focus psi.prompt-assets.skills-test --focus psi.agent-session.child-session-state-test --focus psi.agent-session.resolvers-test --focus psi.workflow-step-session-config.core-test` passed (70 tests, 351 assertions). Follow-up regression verification `clojure -M:test --focus psi.agent-session.config-compaction-test --focus psi.agent-session.workflow-execution-test --focus psi.prompt-assets.system-prompt-test` passed (27 tests, 239 assertions).

## 2026-05-22 implementation review

Found one actionable implementation gap: `psi.prompt-assets.skills/skills-by-source` and the `:psi.skill/by-source` discovery resolver still group raw session skill vectors without canonicalizing each source group, even though the task lists source groupings as an affected discovery surface and arbitrary session `:skills` order is not a trusted presentation contract. Existing tests only count grouped skills, so this ordered introspection surface is unproved.

## 2026-05-22 implementation review follow-up execution

Completed the newly added `steps.md` follow-up. `skills-by-source` now groups skills after applying `skill-registry/all-skills`, so each source-grouped vector is canonical by exact skill `:name` rather than raw session vector order. Added focused helper proof and expanded `:psi.skill/by-source` EQL proof with interleaved unsorted user/project skills. Verification: `clojure -M:test --focus psi.prompt-assets.skills-test` passed (22 tests, 120 assertions).

## 2026-05-22 test review

Found one actionable test gap: TUI skill display/autocomplete is an affected ordered surface, but current proof stops at the session resolver / prompt-assets helpers and does not exercise the TUI projection path that consumes `:psi.agent-session/skills` into app state and renders/suggests skill names. A regression could reintroduce raw session-vector order before the banner/autocomplete without focused coverage.

## 2026-05-22 test review follow-up execution

Completed the newly added `steps.md` follow-up. TUI skill banner rendering now sorts visible skills by exact `:name` before display, and slash autocomplete sorts skill candidates by exact skill `:name` before building `/skill:name` entries, so neither surface can expose raw session `:skills` vector order. Added focused TUI projection and input-selector tests with unsorted session skills. Verification: `clojure -M:test --focus psi.prompt-assets.skills-test --focus psi.agent-session.resolvers-test --focus psi.tui.app-input-selector-test --focus psi.tui.app-projection-test` passed (62 tests, 299 assertions); `clj-kondo --lint components/tui/src components/tui/test` passed.

## 2026-05-22 test-shaper review

Found one actionable test-quality gap: branch B requires `register-skill` result `:skills` to be canonical at the registry boundary even when a duplicate registration is ignored, but the current duplicate test starts from a singleton existing vector and only proves add-path canonicalization. A regression could return an unsorted pre-existing vector on the duplicate/no-change path while preserving `:added?` / `:changed?` and passing the current focused registry tests.

## 2026-05-22 test-shaper follow-up execution

Completed the newly added registry proof. The duplicate/no-change test now starts from an unsorted two-skill vector, verifies `register-skill` returns canonical `:skills` even when the duplicate is ignored, and still proves first-write-wins plus `:added? false` / `:changed? false`. Verification: `clojure -M:test --focus psi.skill-registry.registry-test` passed (3 tests, 24 assertions).

## 2026-05-22 follow-up execution

No newly added unchecked actionable `steps.md` items were present after the preloaded review result. All implementation follow-up steps are already complete, so no code, test, design, or plan changes were needed.

## 2026-05-22 code-shaper review

Found one actionable robustness/consistency gap: `skill-registry/register-skill` now canonicalizes duplicate/no-change result `:skills`, but the `:session/register-skill` handler only writes session `:skills` when `:changed?` is true. If a session already contains an unsorted externally supplied/pre-existing skill vector, duplicate registration returns canonical data but the dispatch boundary drops it, leaving session state non-canonical despite the branch B registry-boundary contract.

## 2026-05-22 implementation review

No new actionable implementation feedback. Re-read the task artifacts, `skill-registry` implementation/tests, and `:session/register-skill` handler path. The only implementation issue observed is the already-recorded unchecked follow-up: duplicate/no-change registration canonicalizes at the registry boundary but the session handler still skips writing canonical `:skills` when `:changed?` is false. No additional code/design/test gap was found, so `steps.md` was not changed.

## 2026-05-22 code-shaper follow-up execution

Completed the newly added `:session/register-skill` follow-up. The session handler now writes canonicalized registry `:skills` whenever the registry result differs from the current session vector, even on duplicate/no-change registrations, while `:runtime/refresh-system-prompt` remains gated only by `:changed?`. Added focused dispatch proof that an unsorted pre-existing session skill vector is canonicalized by duplicate registration without prompt refresh. Verification: `clojure -M:test --focus psi.agent-session.config-compaction-test --focus psi.skill-registry.registry-test` passed (11 tests, 109 assertions).

## 2026-05-22 test review

Found one actionable test gap: `/skills` and the `/help` embedded Skills section are user-visible ordered skill-list surfaces, but current command tests only cover the no-skills case. They rely on `:psi.agent-session/skills` being canonical, yet lack focused command-level proof that unsorted session `:skills` render `/skill:*` entries in canonical skill-name order and do not regress to raw vector order.

## 2026-05-22 task-test-review follow-up execution

Completed the newly added command-surface proof. Added focused `/skills` and `/help` command tests that seed raw unsorted session `:skills` and assert `/skill:*` entries render in canonical skill-name order rather than raw vector order. Verification: `clojure -M:test --focus psi.agent-session.commands-test` passed (50 tests, 192 assertions); `clj-kondo --lint components/agent-session/test/psi/agent_session/commands_test.clj` passed.

## 2026-05-22 test-shaper review

Found one actionable test-quality gap: `register-skill` add-path coverage only adds into an empty or already-canonical collection. Branch B requires registry result `:skills` to be canonical even when registering a new skill into an unsorted pre-existing vector, so a regression could append/sort incorrectly on the add path while duplicate/no-change and helper tests still pass.

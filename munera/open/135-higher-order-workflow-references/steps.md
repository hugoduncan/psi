Slice 1 — Model and ownership alignment
- [x] Review current workflow grammar, IR compiler, source-resolution, and delegate runtime owners for static-target assumptions
- [x] Identify and record the canonical owner(s) for workflow-reference value semantics
- [x] Choose and record the preferred candidate external shape for workflow references: `{:type :workflow-ref :name "..."}`
- [x] Choose and record the preferred grammar shape for dynamic `:target`: `workflow-name | workflow-target-source-spec`
- [x] Choose and record the preferred IR-facing `delegate-spec` target union: static workflow-name string or source-spec-like map
- [x] Record the preferred layered validation split: authored target-shape validation in grammar/compiler, workflow-ref value validation in runtime source resolution, final availability validation in canonical delegate lookup/enforcement
- [x] Confirm the final exact IR-facing shape during implementation
- [x] Record the static delegate behaviors that must remain unchanged
- [x] Record that dynamic `:target` reuses the existing `source-spec` shape exactly rather than introducing a parallel target-source mini-language, including bare `:from` and optional `:path`/`:projection`
- [x] Record that if `:projection` is used in dynamic `:target`, the final resolved value must still be a valid workflow-reference value
- [x] Record that workflow references are expected to travel through structured data outputs rather than free-form yielded text
- [x] Record that workflow references are first-cut ordinary structured data values, not a new global output type system

Slice 2 — Grammar / IR / compiler extension
- [x] Extend delegate target modeling to support static workflow names and dynamic workflow-reference sources
- [x] Add explicit workflow-reference value semantics to the canonical workflow model
- [x] Update target-authored validation and IR compilation for the higher-order target path
- [x] Ensure malformed higher-order target shapes fail clearly
- [x] Preserve current static delegate compilation behavior

Slice 3 — Runtime resolution
- [x] Extend canonical workflow source/delegate resolution to accept workflow-reference targets
- [x] Reuse canonical workflow lookup/enforcement for dynamic targets rather than inventing a parallel path
- [ ] Fail explicitly with distinct semantics for authored-shape failure, runtime-type failure, lookup failure, and availability failure
- [ ] Prove that a previously selected workflow reference whose target was removed before delegation fails as a lookup failure at delegation time
- [x] Preserve existing delegated yield/handoff behavior after target resolution
- [x] Verify deterministic/replay-friendly target resolution semantics

Slice 4 — Proof
- [x] Add focused proof that static delegate targets still compile and run as before
- [x] Add focused proof that valid dynamic workflow-reference targets compile and resolve successfully
- [x] Add focused proof that malformed higher-order target shapes fail explicitly
- [ ] Add focused proof that unknown or unavailable referenced workflows fail explicitly
- [x] Add at least one end-to-end higher-order workflow example proving selected workflow reference → later delegate execution
- [x] Verify downstream delegated yield/handoff behavior remains unchanged in the end-to-end proof

Slice 5 — Docs and coherence
- [x] Update workflow docs with the workflow-reference concept and dynamic delegation guidance
- [x] Document what higher-order workflow references are not, especially no runtime workflow generation
- [x] Add design-level worked examples for success, invalid plain-string dynamic target, and unavailable workflow target
- [x] Add a minimal worked example showing when to use static delegate targets vs workflow references in user-facing docs
- [x] Re-read task artifacts for coherence across design, plan, steps, and implementation notes

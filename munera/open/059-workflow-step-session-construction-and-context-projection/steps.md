# 059 — Steps

## Phase 1 — Explicit source selection
- [ ] Inventory current workflow-file defaults and current child-session shaping path
- [ ] Choose the first incremental authoring surface for explicit source selection
- [ ] Implement explicit source selection for workflow input, workflow original, and named prior step accepted result
- [ ] Preserve current default behavior when the new surface is absent
- [ ] Add validation for unknown step references and invalid source forms
- [ ] Add compiler/loader tests for branch-safe non-adjacent source selection

## Phase 2 — Minimal projections
- [ ] Choose the minimal projection vocabulary for the first landing (`:text`, `:full`, `:path [...]` or equivalent)
- [ ] Implement projection compilation/validation
- [ ] Add tests for field/path projection and backward compatibility

## Phase 3 — Step-level session shaping
- [ ] Define authoring for per-step session overrides
- [ ] Route step-level system prompt / tools / skills / model / thinking overrides through step prep
- [ ] Add focused tests proving per-step overrides take effect without regressing defaults

## Phase 4 — Reference message/transcript projection
- [ ] Define authoring for reference/preloaded context under step session shaping
- [ ] Implement at least one constrained message/transcript projection form
- [ ] Add execution tests proving projected reference context reaches the child session

## Phase 5 — Examples and convergence
- [ ] Update workflow docs/examples to explain the session-first authoring model
- [ ] Revisit modular GitHub workflows to use the new surface where it materially improves clarity
- [ ] Decide the long-term role of prompt-binding convenience relative to session construction
- [ ] Run focused workflow tests
- [ ] Run isolated workflow suite if applicable
- [ ] Run full unit suite

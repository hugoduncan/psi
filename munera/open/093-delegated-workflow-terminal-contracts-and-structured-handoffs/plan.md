Approach:
- treat this as the follow-on to task `092`, not a re-opening of all workflow design
- preserve `092`'s delegated yielded-text model and add the minimum broader contract layer needed for realistic multi-phase orchestration
- prefer one dual-plane delegated model over a proliferation of delegated-result surfaces:
  - yielded text for human-readable chaining
  - structured handoff for downstream orchestration
- drive the work from the realistic `gh-bug-triage-modular` migration target if the first-cut contract supports it cleanly
- keep the new contract explicit at the workflow boundary so callers consume declared exports rather than opportunistic callee internals

Likely steps:
1. inventory the concrete multi-phase data items `gh-bug-triage-modular` and its child workflows must pass structurally
2. choose the minimum workflow-level terminal export contract shape
3. choose the canonical caller-side consumption surface for delegated structured handoffs
4. decide the first-cut standard contract keys and fallback behavior for undeclared structured exports
5. converge IR/runtime/compiler/source-resolution behavior on that contract boundary
6. add focused proof for both delegated yielded-text and delegated structured-handoff downstream reads
7. migrate the child workflows' terminal contracts as needed to expose the required handoff data
8. migrate `gh-bug-triage-modular` to target-authored delegate syntax if the resulting first cut is honest and narrow
9. if direct migration still proves broader than intended, replace it with a narrower realistic multi-phase delegate-heavy example and record why
10. add focused automated proof that the checked-in example runs through the authoritative canonical workflow execution path intended to remain after compatibility retirement
11. update workflow docs so the dual-plane delegated contract is taught explicitly
12. verify the task materially reduces a blocker for `090`

Key design constraints:
- preserve delegated `:yield :text` as the canonical simple path from task `092`
- introduce at most one standard structured handoff contract surface for the first cut if possible
- do not make transcript projection the sole machine-facing contract
- do not expose arbitrary callee diagnostics/internal envelopes as the normal authoring contract
- prefer declared exported surfaces over freeform markdown parsing

Proof target:
- one realistic checked-in target-authored multi-phase delegate-heavy workflow proves both:
  - delegated yielded-text chaining
  - delegated structured handoff consumption
through the canonical execution path

Risks:
- workflow-level terminal export declaration may drift into overgeneral schema work if not kept narrow
- `gh-bug-triage-modular` may reveal multiple distinct contract needs whose unification requires careful constraint
- docs may overclaim if the first implementation only proves one standard handoff key; keep the taught model tight to executable reality

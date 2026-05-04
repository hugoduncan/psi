Approach:
- Treat this as a prompt semantics convergence task spanning workflow execution, child-session state derivation, prompt assembly, tests, and Allium spec integration.
- Preserve the canonical system-prompt builder as the source of mode-aware prompt structure.
- Shift workflow child-session behavior from raw-string prompt pass-through by default to structured prompt rebuilding by default, while keeping any already-existing explicit filtering semantics crisp.
- Treat workflow-authored prompt text as a compositional instruction layer by default; do not infer full replacement merely from workflow-local prompt text.
- Use the provider-visible prepared request system prompt as the authoritative proof surface, and keep stored/introspectable prompt layers coherent with it.
- Keep scope tight: tools/skills must be coherent in both state and prompt rendering; extensions/workflows are only in scope to the extent they already participate in prompt-visible composition.
- Update spec, tests, and implementation together so prompt behavior, proof, and design stay aligned.

Likely steps:
1. trace the workflow step -> step-config -> create-child -> child session prompt derivation path and identify the minimal convergence seam
2. confirm how child prompt mode is inherited or overridden today and preserve that resolution model while making it visible in prompt text
3. confirm whether an explicit prompt-replacement mechanism already exists; if not, do not invent one in this task
4. reshape child prompt derivation so nil `:prompt-component-selection` means full/default composition, not rebuild bypass
5. thread workflow capability declarations into both actual child-session availability and prompt-visible capability rendering for tools/skills
6. preserve coherent prompt-visible treatment for extensions/workflows where those surfaces already exist, without broadening into new runtime capability-gating machinery
7. decide and encode the canonical place for workflow-authored prompt text in assembled prompt layers
8. add focused proof for lambda-mode/full-composition/filtered-composition behavior in workflow child sessions, with provider-visible prepared prompt as the decisive observable
9. add/refine Allium spec rules and connect them into the existing prompt/session/lambda/workflow spec graph
10. run focused tests plus broader prompt/workflow/spec verification as needed

Suggested proof shape:
- extend existing workflow child-session and workflow execution tests rather than inventing a separate test surface
- assert child `:base-system-prompt`, `prompt-request/effective-system-prompt`, and the prepared provider-visible prompt all agree on the intended assembled semantics
- assert capability narrowing appears in both child session state and prompt-rendered sections
- assert explicit prompt-component-selection still filters predictably
- assert workflow-authored prompt text is present as a composed layer rather than becoming the sole prompt by default
- assert lambda-mode workflow children visibly include lambda-mode psi-authored prompt structure by default

Risks:
- current tests may encode old pass-through semantics and need careful reshaping
- child-session derivation is shared enough that an over-broad change could affect non-workflow child sessions
- spec integration may be done superficially unless file connections and coverage boundaries are made explicit
- extension/workflow scope may accidentally expand into runtime capability redesign if not kept tight
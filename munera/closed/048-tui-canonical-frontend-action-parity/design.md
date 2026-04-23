Goal: give the TUI parity with Emacs for shared backend-owned frontend selection/control flows used in normal operation, while keeping the shared action semantics canonical and avoiding adapter-local semantic drift.

Intent:
- make the TUI handle shared frontend actions through the canonical backend action semantics rather than relying only on command-local flows
- close the clearest current parity gap identified in `047-tui-feature-parity-with-emacs-ui`
- preserve one shared control-plane model across adapters
- be explicit about the transport distinction:
  - Emacs proves parity through the RPC event/op round-trip (`ui/frontend-action-requested` ↔ `frontend_action_result`)
  - TUI may satisfy the same canonical semantics through its local app-runtime/UI-dispatch path rather than literally using the RPC wire op internally

Context:
- app-runtime exposes canonical frontend actions and result normalization for at least:
  - `select-session`
  - `select-resume-session`
  - `select-model`
  - `select-thinking-level`
- the canonical picker shapes already exist in shared code:
  - `model-picker-action`
  - `thinking-picker-action`
  - context-session / resume-session actions
- RPC already handles `frontend_action_result` canonically in `psi.rpc.session.frontend-actions`:
  - `select-session` routes to switch/fork navigation handling
  - `select-resume-session` routes to resume navigation handling
  - `select-model` routes to model selection handling
  - `select-thinking-level` routes to thinking-level selection handling
  - cancelled and failed statuses already normalize to canonical user-visible messages
- Emacs has focused proof that it handles `ui/frontend-action-requested`, prompts the user, and submits canonical `frontend_action_result` payloads with:
  - preserved `:ui/action`
  - canonical `:action-name`
  - canonical submitted/cancelled status
  - selected value payloads
- TUI already has meaningful selector/dialog support:
  - `/tree` uses a session-selector path over canonical context-session items
  - `/resume` uses a selector workflow
  - TUI has a generic backend-driven `:active-dialog` rendering/input surface for `:confirm`, `:select`, and `:input`
  - TUI can already resolve/cancel dialogs through canonical UI dispatch events
- What remains unclear or missing is the full shared frontend-action parity in the TUI, especially for model and thinking-level selection and for an explicit canonical request/result round-trip analogous to Emacs.

Current evidence and likely gap shape:
- there is strong evidence that the backend side of canonical frontend-action handling is already complete enough for TUI adoption
- there is strong evidence that the TUI already has enough generic selection/dialog machinery to present these actions
- there is not yet focused proof that the TUI honors the same canonical action contract for backend-requested actions that Emacs honors
- there is not yet focused proof for TUI handling of backend-requested:
  - `select-model`
  - `select-thinking-level`
- existing TUI `/tree` and `/resume` flows may still be partially command-local rather than fully converged on the shared frontend-action semantics

Problem:
- today the TUI appears to have workflow-specific local selector behavior, but parity with the canonical shared frontend-action semantics is missing or unproven
- this means adapter behavior can drift even when the backend already owns the canonical action semantics
- the clearest concrete user-visible gap is that TUI may not fully support backend-requested `select-model` and `select-thinking-level` flows with the same semantics as Emacs
- the clearest architectural gap is that existing TUI selectors may not yet be explicitly proven to converge on the same canonical action lifecycle as Emacs, even if the transport path differs

Preferred implementation direction:
- treat the canonical frontend-action contract as the source of truth
- adapt the TUI’s existing selector/dialog machinery to consume shared picker actions and produce the same canonical action result semantics that RPC `frontend_action_result` carries
- do not build a second TUI-only model-selection or thinking-selection path
- where `/tree` and `/resume` already work, either:
  - prove that they already route through the canonical action path, or
  - refactor them so the TUI uses one shared frontend-action handling path for both command-triggered and backend-triggered selection workflows

Scope:
In scope:
- inventory and close TUI gaps for canonical frontend actions used in normal operation
- support backend-requested frontend actions for at least:
  - `select-session`
  - `select-resume-session`
  - `select-model`
  - `select-thinking-level`
- ensure the TUI can present those actions through its canonical rendering/input affordances
- ensure the TUI produces canonical action results equivalent in semantics to RPC `frontend_action_result`, including:
  - request id when the action originated from a request/response flow
  - action name
  - preserved `:ui/action`
  - submitted/cancelled status
  - selected value where applicable
- make explicit how TUI action presentation maps to current affordances:
  - select-style actions may use the existing selector/dialog rendering path
  - submit/cancel behavior must still produce canonical action-result semantics rather than only local state changes
- add focused proof for success and cancel behavior across the supported actions

Minimum concepts to preserve:
- canonical frontend action object: `:ui/action`
- canonical action names: `select-session`, `select-resume-session`, `select-model`, `select-thinking-level`
- canonical status values: submitted, cancelled, failed
- canonical action-result semantics used by shared backend handling
- canonical `:ui/action` round-trip preservation
- canonical selected value shapes:
  - `select-session` → action map such as switch/fork payload
  - `select-resume-session` → session path string
  - `select-model` → `{:provider ... :id ...}`
  - `select-thinking-level` → thinking-level keyword/string as shaped by shared action/result normalization

Transport clarification:
- Emacs evidence is RPC-framed, because Emacs is an RPC adapter
- TUI acceptance in this task is semantic rather than transport-specific:
  - if the TUI reaches backend handling through local UI dispatch/effect paths, that is acceptable
  - if the TUI literally goes through the same RPC-style op/result surface internally, that is also acceptable
- what must match is the canonical action name, status, preserved `:ui/action`, and selected value semantics

Out of scope:
- redesigning the shared frontend-action schema
- inventing TUI-only action names or payload shapes
- broad dialog-framework redesign beyond what is needed to support the canonical action flows cleanly
- exhaustive parity for every possible future frontend action not already part of the normal shared control plane

Design constraints:
- backend remains authoritative for action semantics and payload shape
- TUI must consume canonical action names and canonical `:ui/action` payloads directly
- do not reintroduce legacy action-name or payload fallback variants
- where TUI already has command-local flows, prefer converging them on the canonical action path rather than maintaining duplicate semantics
- keep the implementation adapter-local to TUI presentation/input unless a real shared-contract gap is discovered
- prefer one TUI action-handling path that can serve both command-triggered and backend-triggered selection workflows

Refinement decisions:
- this task should be treated primarily as a TUI adapter-convergence task, not a backend redesign task
- assume the backend action contract is already sufficient unless implementation exposes a specific missing contract
- the first implementation slice should target generic select-action handling because it likely unlocks:
  - `select-model`
  - `select-thinking-level`
  - possible convergence of `select-session` and `select-resume-session`
- command-local `/tree` and `/resume` behavior should be evaluated against a stronger standard than “still works”:
  - preferred end state is one shared TUI handling path for equivalent selection semantics
- if full convergence of `/tree` and `/resume` would make the slice too large, it is acceptable to:
  - implement canonical backend-requested action handling first
  - add one proof that an existing selection workflow matches the shared action semantics
  - leave deeper internal convergence as a narrowly-scoped follow-on inside this task only if still necessary

Possible implementation shapes considered:
1. Thin adapter mapping over existing dialog machinery
- map canonical select actions onto the existing TUI `:active-dialog` / selector affordances
- on submit/cancel, produce canonical action-result data through the current TUI dispatch/effect path
- likely smallest change if current TUI state already receives enough action metadata

2. Selector unification path
- refactor `/tree`, `/resume`, and backend-requested select actions to share one TUI selection controller/model
- stronger architectural convergence
- potentially larger slice because existing command-local behavior must be preserved during consolidation

3. Hybrid slice
- first implement generic backend-requested select-action handling for `select-model` and `select-thinking-level`
- add one proof of semantic alignment for `select-session` or `select-resume-session`
- defer deeper selector-internal unification unless it is clearly needed

Preferred shape:
- start with the hybrid slice
- rationale:
  - it closes the clearest parity gap first
  - it gives concrete proof of semantic convergence without forcing the whole selector stack to move at once
  - it keeps the task small while preserving the preferred long-term shape of one shared TUI handling path

Concrete proof inventory for this task:
- proof should exist at the TUI-facing boundary for backend-requested action handling, not only at app-runtime or RPC layers
- preferred focused proofs:
  - receiving a backend-requested `select-model` action shows a selectable TUI affordance and submit produces canonical action-result semantics
  - receiving a backend-requested `select-model` action and cancelling produces canonical cancelled semantics
  - receiving a backend-requested `select-thinking-level` action shows a selectable TUI affordance and submit produces canonical action-result semantics
  - receiving a backend-requested `select-thinking-level` action and cancelling produces canonical cancelled semantics
  - one focused semantic-convergence proof for `select-session` or `select-resume-session` showing the TUI preserves `:ui/action` and returns canonical value/status
- optional but valuable proof:
  - unsupported or malformed action input fails in a clear, bounded way without corrupting TUI state

Explicit ambiguity removals:
- “handles backend-requested frontend actions” means:
  - when the backend asks the adapter to present one of the canonical actions, the TUI presents a usable selection affordance and returns a canonical result semantic outcome on submit/cancel
- “canonical result semantics” means:
  - preserved action identity (`action-name` / `:ui/action`)
  - canonical status (`submitted`, `cancelled`, optionally `failed` when applicable)
  - canonical selected value shape for the action family
- this task does not require proving that the TUI literally emits the RPC string op `frontend_action_result` unless that is in fact how the TUI adapter boundary is implemented
- this task does require proving that whichever TUI boundary is used reaches the same backend behavior as the canonical action-result contract expects

Implementation-ready acceptance checklist:

Required behavioral outcomes:
- [ ] when the backend requests `select-model`, the TUI presents a usable select affordance using adapter-appropriate rendering
- [ ] when the backend requests `select-model` and the user submits a choice, the TUI returns canonical action-result semantics with:
  - [ ] action name `select-model`
  - [ ] preserved `:ui/action`
  - [ ] status `submitted`
  - [ ] selected value shaped as `{:provider ... :id ...}`
- [ ] when the backend requests `select-model` and the user cancels, the TUI returns canonical action-result semantics with:
  - [ ] action name `select-model`
  - [ ] preserved `:ui/action`
  - [ ] status `cancelled`
- [ ] when the backend requests `select-thinking-level`, the TUI presents a usable select affordance using adapter-appropriate rendering
- [ ] when the backend requests `select-thinking-level` and the user submits a choice, the TUI returns canonical action-result semantics with:
  - [ ] action name `select-thinking-level`
  - [ ] preserved `:ui/action`
  - [ ] status `submitted`
  - [ ] selected value matching the shared thinking-level value shape
- [ ] when the backend requests `select-thinking-level` and the user cancels, the TUI returns canonical action-result semantics with:
  - [ ] action name `select-thinking-level`
  - [ ] preserved `:ui/action`
  - [ ] status `cancelled`
- [ ] at least one existing session-oriented selection workflow (`select-session` or `select-resume-session`) is proven to align with the same canonical action semantics
- [ ] no new legacy compatibility branches are added to TUI or backend contracts

Required proof outcomes:
- [ ] focused TUI-facing proof exists for `select-model` submit
- [ ] focused TUI-facing proof exists for `select-model` cancel
- [ ] focused TUI-facing proof exists for `select-thinking-level` submit
- [ ] focused TUI-facing proof exists for `select-thinking-level` cancel
- [ ] focused TUI-facing proof exists for one semantic-convergence case covering `select-session` or `select-resume-session`
- [ ] proof exercises the TUI boundary where selection input is rendered/handled, not only shared backend normalization code

Non-blocking but desirable proof:
- [ ] malformed or unsupported frontend action input fails in a clear, bounded way without corrupting TUI state

Suggested first implementation slice:
1. establish the TUI action-handling seam
- identify the narrowest adapter-local place where backend-requested select actions can be received and mapped onto current TUI affordances
- prefer a seam that can reuse existing `:active-dialog` rendering and `handle-dialog-key` input behavior

2. implement generic select-action mapping for backend-requested actions
- support canonical select-action payloads for:
  - `select-model`
  - `select-thinking-level`
- map each request onto existing TUI select presentation without inventing a second rendering model
- ensure submit/cancel paths produce canonical action-result semantics through the actual TUI boundary used by this adapter

3. add focused proof for the first slice
- prove `select-model` submit/cancel
- prove `select-thinking-level` submit/cancel
- keep these tests close to the TUI adapter boundary so they validate rendering/input/result behavior together

4. add one semantic-convergence proof for an existing selection workflow
- choose the smaller of `select-session` or `select-resume-session`
- prove that the TUI preserves canonical `:ui/action`, status, and selected value semantics for that workflow
- do not force full internal selector unification in the first slice unless the implementation naturally benefits from it

5. only then decide whether deeper selector unification is necessary
- if the first slice leaves obvious duplicate TUI logic for semantically equivalent select actions, follow up inside this task
- otherwise stop at proven semantic parity and leave broader cleanup out of the slice

Suggested plan gate for moving beyond design:
- do not write `plan.md` until the implementation seam is identified unambiguously:
  - where the TUI receives backend-requested actions
  - how it emits canonical action-result semantics
  - which existing session-oriented flow is the chosen semantic-convergence proof case

Why this task is small and clear:
- it targets one shared workflow cluster: backend-owned frontend actions
- the gap is now sharper: TUI request/result parity, especially for generic select actions
- the first slice is explicit and bounded: generic select actions first, one convergence proof second
- the architecture direction is explicit: shared semantics in backend, adapter-specific presentation only

# Plan — Session profiles for workflow step configuration

## Approach

Implement session profiles as a session-owned configuration/resolution feature that feeds both live slash commands and deterministic workflow step session config.

Key decisions from `design.md`:

- Profile definitions live only in existing config files under `:agent-session :session-profiles`.
- Profile definition resolution uses a profile-specific deep merge across `user < project-shared < project-local`; unrelated `:agent-session` config resolution stays unchanged.
- Supported profile fields are exactly `:model-provider`, `:model-id`, `:thinking-level`, `:speed-mode`, and `:effort-override`.
- Profile application is atomic: invalid/unknown/reserved profiles apply nothing.
- `:clear` is reserved and unavailable as a selectable profile name.
- Live application materializes concrete settings into session state; selected-profile metadata is session-local, non-journaled, and non-inherited.
- Workflows snapshot effective profile resolution on workflow-run state and never re-read mutable config after invocation.
- Workflow precedence is `explicit step setting > resolved profile setting > inherited workflow-run default > existing fallback`.
- Delegate inherited defaults stay narrow and concrete; profile-derived speed/effort present in the delegating effective config outrank parent snapshot speed/effort, otherwise task-207 fallback remains.

Implementation should add a small profile domain layer (resolver/validator/materializer/formatter) and then wire it through existing owned surfaces: config reads, backend built-in command specs and slash dispatch, EQL resolvers, workflow loader/compiler, workflow-run snapshot creation, step config resolution, and docs/tests.


## Plan/steps follow-up decisions (PA1–PA5)

- **PA1 — delegate canonical IR path:** compact authored `:session-profile` on a `:delegate` step compiles to the delegate-local canonical path `[:delegate :session :session-profile]`. Supported direct delegate overrides compile beside it at `[:delegate :session :model]` and `[:delegate :session :thinking-level]`. The target IR compiler writes this path, `workflow-runtime.core/normalize-effective-definition` stores it unchanged, `workflow-step-session-config/resolve-step-session-config` must read delegate session config from `[:delegate :session]` rather than the session-step-only `[:session]` lookup, and the injected `:resolve-inherited-defaults-fn` used by `workflow-runtime.statechart-runtime.delegate/delegate-step-runtime-result` must consume that same resolver path before projecting child `:inherited-defaults`.
- **PA2 — nil effort semantics:** a profile map that explicitly contains `:effort-override nil` is a valid concrete effort-clear setting. Presence, not truthiness, is authoritative: it counts as a concrete supported setting, is included in live selected-profile metadata and workflow profile snapshots, applies through the same session-scoped `/effort none` mutation semantics, and outranks parent/delegate inherited effort when profile-derived. Implementations and tests must use `contains?` or an explicit derived-field presence marker for `:effort-override`; absent `:effort-override` means no profile effort setting.
- **PA3 — command token normalization:** `/session-profile <token>` accepts either bare keyword names such as `planning`/`fast-summary` or EDN-style keyword tokens such as `:planning`/`:fast-summary`, normalizing both to the corresponding unqualified keyword. Tokens must be a single nonblank token; no multi-token names or EDN maps/vectors are accepted. The raw bare token `clear` is parsed before profile-name normalization as the clear action. The token `:clear` normalizes to the reserved profile name and fails as unavailable/reserved without changing concrete settings or metadata.
- **PA4 — model plus thinking application:** live profile application resolves and validates the whole profile first, then applies concrete fields atomically with model-before-thinking semantics. If the profile supplies a model, the existing session-model mutation semantics run first, including clamping the current thinking level to the new model and appending the normal model journal entry. If the profile also supplies `:thinking-level`, the requested profile thinking level is then applied against the resulting active model through the existing thinking-level mutation semantics, so non-reasoning models clamp the final value to `:off` and the thinking journal entry records the clamped value. A profile that supplies only thinking clamps against the current model. Speed/effort changes are transient and non-journaled.
- **PA5 — snapshot capture boundary:** effective session profiles are read from mutable config only at impure top-level workflow invocation boundaries, such as the Pathom `psi.workflow/create-run` mutation and the psi-tool workflow `create-run` action. Those boundaries compute a self-contained `:session-profile-snapshot` and pass it into the pure `workflow-runtime.core/create-run`, which only stores the supplied snapshot and never reads config. Delegated runs never read config; `delegate-step-runtime-result` copies or derives the child run's `:session-profile-snapshot` from the parent workflow run snapshot while separately passing narrow concrete `:inherited-defaults`. Step resolution and resume consume only the stored run snapshot.

## Plan/steps follow-up decisions (PI1–PI4)

- **PI1 — delegate IR/schema path:** the PA1 canonical delegate path means an optional `:session` map inside the canonical delegate spec: `[:delegate :session]`. That map may contain only `:session-profile`, `:model`, and `:thinking-level` for this task. Implementation must update the workflow IR Malli schemas, any runtime stored-step/effective-definition model schemas that validate canonical steps, and focused validation tests so target compilation, `workflow-runtime.core/normalize-effective-definition`, and `validate-workflow-ir` all preserve `[:delegate :session]` rather than rejecting or dropping it.
- **PI2 — nil effort presence through workflow boundaries:** explicit profile-derived `:effort-override nil` must remain distinguishable from absence across every workflow map boundary. Profile resolved settings, `:session-profile-snapshot`, `resolve-step-session-config` output, `effective-config->snapshot`, delegate `:inherited-defaults`, and child-session creation must use `contains?`/field-presence metadata rather than truthiness or `some?` for effort. A profile-derived nil effort clears inherited effort for both ordinary session children and delegated children.
- **PI3 — command normalization work item:** implementation must include a small command-parser/normalizer seam for `/session-profile` arguments and tests proving: bare `planning` and EDN-style `:planning` select the same profile; raw bare `clear` is the clear action; `:clear` is rejected as reserved; multi-token input and EDN map/vector/list tokens fail without state changes.
- **PI4 — snapshot-capture wiring:** the impure top-level create-run boundaries are responsible for computing profile snapshots: the Pathom `psi.workflow/create-run` mutation and psi-tool workflow `create-run` action compute and pass `:session-profile-snapshot`; pure `workflow-runtime.core/create-run` only stores a supplied snapshot and never reads config. Delegate runtime must pass a copied/derived child `:session-profile-snapshot` from the parent run snapshot alongside the narrow concrete `:inherited-defaults`; tests must cover all three boundaries.

## Risks

- **Config merge drift:** broadening existing `:agent-session` merge behavior would violate the design. Keep profile deep merge isolated to `:session-profiles`.
- **Model identity validation:** profile `:model-provider`/`:model-id` must resolve through the existing model registry/selection path; string/provider keyword shape mismatches are likely edge cases.
- **Atomic mutation:** applying model, thinking, speed, effort, and selected-profile metadata must not leave partial state if a profile is invalid. Resolve and validate fully before dispatching state changes.
- **Journaling semantics:** model/thinking should retain existing journal behavior; speed/effort and selected-profile metadata must remain transient.
- **Workflow determinism:** any step-time config re-read breaks snapshot/replay semantics. Tests must prove mid-run config edits do not affect later steps or delegated/resumed runs.
- **Delegate speed/effort projection:** task-207 intentionally sourced speed/effort from the parent snapshot; this task must extend that only when profile-derived fields are present in effective config, preserving fallback otherwise.
- **Grammar scope creep:** direct authored workflow `:speed-mode`/`:effort-override`, nested `{:session {:session-profile ...}}`, invoke steps, and judge profiles are out of scope.
- **User-facing command exposure:** `/session-profile` and `/session-profiles` must be added via backend single-source built-in command specs, not adapter-local command lists.

## Slice order

1. **Profile domain and config resolution** — add effective profile collection, profile-specific deep merge, supported-field filtering, validation, model materialization, readable diagnostics, and focused tests.
2. **Live session command surface and observability** — expose profile reads through resolver/read helpers, add selected-profile metadata state handling, implement `/session-profiles`, `/session-profile`, `/session-profile <name>`, and `/session-profile clear`, and add command/resolver tests.
3. **Workflow authoring grammar and canonical IR** — accept compact top-level `:session-profile` on supported `:session`/`:delegate` steps and markdown frontmatter, store it in canonical session-config surfaces, reject unsupported placements, and lock loader/compiler tests.
4. **Workflow snapshot and step resolution semantics** — snapshot effective profile definitions/diagnostics at workflow invocation, copy/derive snapshots for delegated runs, resolve profile settings in step config with the required precedence, fail invalid profiles before child-session/attempt creation, and preserve deterministic replay/resume behavior.
5. **Delegate inherited-defaults projection** — project profile-derived concrete model/thinking/speed/effort into delegated child `:inherited-defaults` while keeping profile names/maps out and preserving task-207 fallback for absent speed/effort fields.
6. **Docs and changelog** — update configuration and workflow docs plus `[Unreleased]` changelog entries for config shape, commands, workflow key, and snapshot semantics.
7. **Verification and coherence** — run focused profile/command/workflow-loader/workflow-runtime tests, targeted lint, and any relevant broader unit/Scry suites; re-read changed artifacts for design/plan/test/code/docs consistency.

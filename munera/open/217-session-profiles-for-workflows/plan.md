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

# Session profiles for workflow step configuration

## Intent

Add named **session profiles** so users can define reusable model/session-setting bundles in the existing user or project config files, select one interactively for the current session, and let workflows request a named profile for specific steps.

The motivating use case is semantic workflow roles such as `:planning`, `:coding`, and `:review`, where a user might prefer Opus with high thinking for planning and GPT-5.5 for coding, without hardcoding those choices into reusable workflow definitions.

## Problem

Workflow steps can currently set concrete session options such as `:model`, `:thinking-level`, `:speed-mode`, and `:effort-override`, and workflow runs snapshot inherited defaults at invocation time. That supports fixed workflow-authored choices, but it does not let users map workflow roles to their own preferred model/session settings.

A workflow author needs to say “this step wants the planning profile” while each user/project decides what `:planning` means. The profile mechanism must use the existing config system rather than introducing another config location.

## Terms

- **Session profile**: a named config bundle that may contain any subset of model/session settings relevant to model invocation.
- **Profile name**: an open keyword chosen by users/workflows, for example `:planning`, `:coding`, `:review`, `:triage`, or `:fast-summary`.
- **Profile selection**: resolving a named profile to concrete session settings and applying them to a live session or workflow step.

The feature deliberately avoids the generic name “task” because Munera tasks and workflow steps already use that word.

## Config shape

Session profiles live under the existing `:agent-session` map in the existing config files:

- user config: `~/.psi/agent/config.edn`
- project shared config: `<cwd>/.psi/project.edn`
- project local config: `<cwd>/.psi/project.local.edn`

No new config file or config root is introduced.

Example:

```edn
{:version 1
 :agent-session
 {:session-profiles
  {:planning {:model-provider "anthropic"
              :model-id "claude-opus-4-8"
              :thinking-level :high
              :effort-override :high
              :speed-mode :normal}
   :coding   {:model-provider "openai"
              :model-id "gpt-5.5"
              :thinking-level :medium
              :speed-mode :fast}
   :review   {:model-provider "anthropic"
              :model-id "claude-opus-4-8"
              :thinking-level :high}}}}
```

Profile maps support the same persisted model identity shape currently used by existing config: `:model-provider` plus `:model-id`. At runtime, profile resolution materializes that pair to the normal session `:model` shape.

Profile maps may include at least:

- `:model-provider`
- `:model-id`
- `:thinking-level`
- `:speed-mode`
- `:effort-override`

Unknown profile-map keys are preserved only if existing config handling already preserves unknown keys; they must not affect session request construction unless explicitly supported by this task.

## Config precedence and merge rules

Session profiles follow the existing config precedence:

```text
session runtime override > project-local > project-shared > user > system
```

For persisted profile definitions, project local overrides project shared, and both override user config. Profile maps merge recursively using the same deep-merge semantics as other `:agent-session` config maps, so a project can override one field of a user-defined profile without redefining every field.

Example:

```edn
;; user
{:agent-session {:session-profiles {:coding {:model-provider "openai"
                                             :model-id "gpt-5.5"
                                             :thinking-level :medium
                                             :speed-mode :normal}}}}

;; project.local
{:agent-session {:session-profiles {:coding {:speed-mode :fast}}}}
```

The effective `:coding` profile keeps the user model/thinking fields and uses project-local `:speed-mode :fast`.

## Interactive command surface

Add slash-command support for selecting and inspecting session profiles.

Required commands:

```text
/session-profiles
/session-profile
/session-profile <profile-name>
/session-profile clear
```

Behavior:

- `/session-profiles` lists the effective configured profile names and their resolved user-visible settings.
- `/session-profile` shows the currently selected/applied profile metadata for the session, if any, and the session’s current concrete model/thinking/speed/effort settings.
- `/session-profile <profile-name>` resolves the named effective profile and applies its concrete settings to the current session.
- `/session-profile clear` clears only the “selected profile” metadata on the current session; it does not revert concrete model/thinking/speed/effort values that were already applied.
- Unknown profile names fail with an actionable message listing available profile names.

Selecting a profile materializes concrete values into existing session state rather than leaving the session dynamically bound to a mutable profile name. Later config edits do not silently change already-applied session settings.

The command should be discoverable in the built-in slash-command surface and available to existing UIs that consume that surface.

## Workflow grammar

Add a workflow step key named `:session-profile`.

Compact form:

```edn
{:name "plan"
 :type :session
 :session-profile :planning
 :tools ["read" "bash"]
 :contributions [...]}
```

Structured form may be supported if useful for defaults or future extension, but the compact keyword form is required.

For delegate steps, `:session-profile` applies to the delegating step’s effective config and therefore to the inherited-defaults snapshot captured for the delegated sub-workflow. The callee workflow still retains its own explicit overrides.

## Workflow resolution semantics

When resolving a workflow step session config:

1. Resolve the step’s `:session-profile` name against the workflow run’s snapshotted effective profile definitions.
2. Merge the resolved profile settings into the step effective config.
3. Apply explicit step keys as the highest-precedence workflow-authored overrides.
4. Fall back to the workflow run’s inherited defaults snapshot when neither the step nor the profile supplies a value.

Required precedence for a workflow step:

```text
explicit step setting > resolved :session-profile setting > inherited workflow-run default > existing fallback
```

For example:

```edn
{:type :session
 :session-profile :coding
 :thinking-level :high}
```

uses the `:coding` profile for model/speed/effort, but forces `:thinking-level :high` for that step.

Existing direct step keys (`:model`, `:thinking-level`, `:speed-mode`, `:effort-override`) remain valid and keep their current meaning. Existing workflows with no `:session-profile` keep current behavior.

## Snapshot and replay requirement

Workflow runs must not re-read mutable profile config for later steps after the run starts.

At top-level workflow invocation, snapshot the effective profile definitions needed for workflow resolution, or snapshot an equivalent resolved profile map, into canonical workflow-run state. Nested delegated workflow runs receive profile behavior through the existing inherited-defaults/delegation snapshot mechanism, extended as needed so profile-derived model/thinking/speed/effort settings are deterministic.

Consequences:

- Editing user or project config after a workflow starts does not affect that run’s later steps.
- Resuming a blocked run reuses its original profile snapshot.
- A fresh workflow invocation uses the current effective config.

## Introspection and observability

Profile resolution should be observable enough to answer “why did this session/step use this model?”

At minimum, expose or record:

- selected profile name, when a live session selected one via `/session-profile`
- profile name requested by a workflow step, when present
- concrete resolved settings applied from the profile
- clear distinction between profile-derived values and explicit step overrides when feasible

The exact EQL attribute names are left to implementation, but they must use existing resolver/mutation patterns rather than raw state access.

## Documentation

Update user-facing docs to cover:

- `:session-profiles` under `:agent-session` in `doc/configuration.md`
- user/project config examples, including the fact that config goes in existing user/project config files
- `/session-profile` and `/session-profiles` commands
- workflow `:session-profile` examples in workflow authoring docs
- snapshot semantics for workflow runs

Add a CHANGELOG `[Unreleased]` entry because this is user-visible behavior.

## Out of scope

- No new config file, config root, or profile registry outside existing config files.
- No generic `:task` workflow key or user-facing command terminology.
- No requirement to implement a rich TUI picker beyond the slash-command/listing surface, unless existing UI conventions make that straightforward.
- No removal or migration of existing direct workflow step keys.
- No change to provider model registry format except whatever is necessary to resolve profile `:model-provider`/`:model-id` pairs through existing model resolution.

## Acceptance criteria

1. Effective config can define named session profiles under `:agent-session :session-profiles` in user and project config files.
2. Project-local/project-shared/user precedence and deep merge semantics apply to profile definitions consistently with existing config behavior.
3. `/session-profiles` lists effective profile names and readable settings.
4. `/session-profile <name>` resolves and applies the selected profile to the current session’s concrete model/thinking/speed/effort state.
5. `/session-profile clear` clears selected-profile metadata without reverting concrete session settings.
6. Unknown profile selection fails with a helpful message and available names.
7. Workflow steps can specify `:session-profile :profile-name` and receive profile-derived model/thinking/speed/effort settings.
8. Explicit workflow step settings override profile-derived settings.
9. Workflow profile resolution is snapshotted for deterministic run behavior; mid-run config edits do not affect later steps or resumed runs.
10. Existing workflows without `:session-profile` behave unchanged.
11. Docs and changelog describe the config shape, command surface, workflow key, and snapshot semantics.

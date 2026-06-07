# Session profiles for workflow step configuration

## Intent

Add named **session profiles** so users can define reusable model/session-setting bundles in the existing user or project config files, select one interactively for the current session, and let workflows request a named profile for specific steps.

The motivating use case is semantic workflow roles such as `:planning`, `:coding`, and `:review`, where a user might prefer Opus with high thinking for planning and GPT-5.5 for coding, without hardcoding those choices into reusable workflow definitions.

## Problem

Workflow steps can currently set concrete session options such as `:model` and `:thinking-level`, and workflow runs snapshot inherited defaults at invocation time, including speed/effort values inherited from the parent run. That supports fixed workflow-authored choices for currently-supported direct keys, but it does not let users map workflow roles to their own preferred model/session settings.

A workflow author needs to say “this step wants the planning profile” while each user/project decides what `:planning` means. The profile mechanism must use the existing config system rather than introducing another config location.

## Terms

- **Session profile**: a named config bundle that may contain any subset of this task's supported profile fields: `:model-provider`, `:model-id`, `:thinking-level`, `:speed-mode`, and `:effort-override`.
- **Profile name**: a user/workflow keyword chosen from the open profile-name space, for example `:planning`, `:coding`, `:review`, `:triage`, or `:fast-summary`. The keyword `:clear` is reserved by the `/session-profile clear` command action and is not an available profile name.
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

The supported profile field set for this task is exactly:

- `:model-provider`
- `:model-id`
- `:thinking-level`
- `:speed-mode`
- `:effort-override`

No other profile fields affect request/session construction in this task. In particular, profiles do not support `:tools`, `:skills`, `:prompt-mode`, `:system-prompt`, `:temperature`, `:response-mode`, `:logprobs`, `:top-logprobs`, or prompt-component selection. Unknown profile-map keys may remain in raw config data if existing config readers preserve them, but profile resolution, command output, workflow snapshots, and applied session settings must ignore them.

## Config precedence and merge rules

Applying a profile materializes concrete settings into the live session, where those concrete values then follow the existing session/config precedence rules for session settings.

Persisted profile definitions themselves have no system default layer and no runtime profile-definition override in this task. They use a profile-specific deep-merge resolver across the existing config files only:

```text
project-local > project-shared > user
```

The resolver deep-merges only `:agent-session :session-profiles` across user config, project shared config, and project local config in that order; project-local fields win over project-shared fields, and project fields win over user fields. This task does not change the current non-profile `:agent-session` config resolution behavior, where ordinary top-level session config keys continue to be resolved by the existing config accessors and merge rules.

Within a profile definition, profile maps merge recursively, so a project can override one supported field of a user-defined profile without redefining every field.

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

The effective `:coding` profile keeps the user model/thinking fields and uses project-local `:speed-mode :fast`. This deep merge is scoped to profile definitions; it must not imply a broad rewrite of unrelated config resolution.

## Profile resolution and validity

A profile is valid when its name is not reserved, every present supported field is valid, and at least one concrete supported setting resolves.

Supported-field rules:

- `:model-provider` and `:model-id` form one model identity. Both may be absent, and both may be present; exactly one present is invalid.
- When the model identity is present, it must resolve through the existing model registry/model-selection path to a known provider/model. Unknown provider or model ids are invalid.
- `:thinking-level` must be one of the existing canonical thinking levels: `:off`, `:minimal`, `:low`, `:medium`, `:high`, or `:xhigh`.
- `:speed-mode` must be one of the existing speed modes: `:normal` or `:fast`.
- `:effort-override` must be `nil`, `:low`, `:medium`, `:high`, or `:xhigh`.
- A profile with none of the supported fields, or with supported fields that all resolve to no concrete setting, is invalid for application.
- A profile named `:clear` is reserved and invalid for application. If config contains `:clear`, `/session-profiles` must show it as unavailable/reserved rather than treating it as a selectable profile.

Partial profiles are allowed when the present supported fields are valid. For example, a profile containing only `:speed-mode :fast` is valid and applies only speed.

Invalid profile handling:

- `/session-profiles` lists all effective profile names. Valid profiles show their readable resolved settings; invalid profiles are shown separately or annotated with their invalid reasons so users can repair config.
- `/session-profile <profile-name>` is atomic. It either applies all valid resolved fields from the profile or applies nothing. A named invalid profile fails rather than partially applying valid fields.
- Workflow steps that request an invalid profile fail or block before creating the child session/attempt that would consume it; they must not silently ignore invalid fields or partially apply the profile.
- Unknown profile names fail with an actionable message listing available effective profile names. Invalid-profile errors must include the requested profile name, the invalid field or no-settings reason, and the available valid profile names when that helps recovery.

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

- `/session-profiles` lists the effective configured profile names. Valid profiles show readable resolved settings; invalid profiles show terse invalid reasons.
- `/session-profile` shows the currently selected/applied profile metadata for the session, if any, and the session’s current concrete model/thinking/speed/effort settings.
- `/session-profile <profile-name>` resolves the named effective profile and atomically applies its concrete settings to the current session.
- `/session-profile clear` clears only the “selected profile” metadata on the current session; it does not revert concrete model/thinking/speed/effort values that were already applied.
- The command token `clear` is always parsed as the clear action. There is no escaping or alternate spelling for selecting a `:clear` profile in this task because `:clear` is reserved and invalid as a profile name.
- Unknown profile names fail with an actionable message listing available profile names.
- Invalid profile names fail with an actionable message containing the profile name and invalid reason; no concrete settings are changed.

Selecting a profile materializes concrete values into existing session state rather than leaving the session dynamically bound to a mutable profile name. Later config edits do not silently change already-applied session settings.

The command should be discoverable in the built-in slash-command surface and available to existing UIs that consume that surface.

### Live-session persistence and lifecycle semantics

`/session-profile <profile-name>` is a session-scope operation, not a user/project config write. It does not write profile definitions or concrete settings back to user or project config.

Applying a profile uses existing setting semantics for the concrete fields:

- `:model-provider`/`:model-id` materialize to the session `:model` value and use the existing session-model mutation behavior, including the existing model journal entry and thinking-level clamp behavior.
- `:thinking-level` uses the existing session thinking-level mutation behavior, including the existing thinking-level journal entry.
- `:speed-mode` remains session-transient like `/speed`: it affects current live session state and new/fork descendants that inherit live session state, but it is not journaled and is not restored by cold journal resume.
- `:effort-override` remains session-transient like `/effort`: it affects current live session state and new/fork descendants that inherit live session state, but it is not journaled and is not restored by cold journal resume.

The selected-profile metadata is session-local observability state. Applying a profile records metadata such as the selected profile name and the concrete resolved fields applied on the current session. That metadata is not written to user/project config, not journaled, not restored by cold resume, and not copied to new/fork child sessions. New/fork sessions may inherit concrete model/thinking/speed/effort values through existing lifecycle inheritance, but they do not claim that the profile itself is selected.

`/session-profile clear` clears only the selected-profile metadata for the current session. It does not journal an entry, change model/thinking/speed/effort, edit config, or clear inherited concrete values in descendant sessions.

### Command architecture and ownership

`/session-profiles` and `/session-profile` must be added to the backend single-source built-in command spec table, so help text, slash autocomplete, and command routing derive from the same command definitions as the rest of the built-in slash-command surface. Adapters such as TUI and Emacs must continue to consume the backend `:psi.agent-session/builtin-command-specs` resolver; they must not introduce adapter-local profile command lists, profile caches, or profile selection state.

Profile selection mutates canonical session state only through the existing backend command dispatch and session mutation/dispatch patterns. The command handler may format user-facing text, but applying a profile must go through a session-owned mutation/dispatch event that materializes concrete `:model`, `:thinking-level`, `:speed-mode`, and `:effort-override` values plus selected-profile metadata on the session. It must not write directly to the root atom or to adapter-owned state.

Profile reads must be exposed through EQL resolvers using the existing resolver registration surface. At minimum, resolvers should expose the effective session-profile definitions/readable resolved settings and the current session selected-profile metadata. Command formatting should read those resolver surfaces or equivalent session-owned read helpers rather than inspecting adapter state.

## Workflow grammar

Add a workflow step key named `:session-profile`.

Authored EDN workflows use the compact top-level step key, next to existing direct session-setting keys:

```edn
{:name "plan"
 :type :session
 :session-profile :planning
 :tools ["read" "bash"]
 :contributions [...]}
```

Supported authored placements for this task:

- `:session` steps may carry top-level `:session-profile` plus the concrete profile-affecting direct step overrides already supported by the workflow grammar today: `:model` and `:thinking-level`.
- `:delegate` steps may carry top-level `:session-profile` plus the same supported direct step overrides, `:model` and `:thinking-level`. These keys shape the concrete inherited-defaults snapshot passed to the delegated run; they do not create an actor session for the delegate step itself.
- Single-step markdown workflows may carry a `:session-profile` frontmatter key. The parser/compiler treats it like the same compact key on the generated target-authored `:session` step.

This task does not introduce direct authored workflow step keys or markdown frontmatter keys for `:speed-mode` or `:effort-override`. Speed and effort can still come from a resolved session profile and then materialize into the step's effective config. A later task may add direct authored speed/effort keys by updating the target IR compiler, markdown frontmatter parser, loader validation, tests, and workflow grammar docs together.

Unsupported placements for this task:

- Legacy or nested authored `:session` maps do not gain a new `{:session {:session-profile ...}}` spelling. Authors use the compact top-level key.
- `:invoke` steps do not accept `:session-profile` or concrete profile-affecting overrides because they run deterministic operations rather than model sessions.
- LLM judge specs do not accept `:session-profile` or concrete profile-affecting overrides in this task. Judge-session profile selection can be added by a later task under the judge's own session-config boundary.

Canonical IR storage:

- For `:session` steps, the target IR compiler stores `:session-profile` and supported direct concrete overrides inside the canonical step `:session` config map alongside existing session options.
- For `:delegate` steps, the target IR compiler stores the same authored fields in a canonical delegate step session-config surface dedicated to inherited-default shaping. The field is part of the effective step state used by profile resolution, but profile names/profile maps are never stored in `:inherited-defaults`.
- Markdown frontmatter compiles to the same canonical `:session` config as an EDN `:session` step.

For delegate steps, `:session-profile` applies to the delegating step’s effective config and therefore to the inherited-defaults snapshot captured for the delegated sub-workflow. The delegated run receives the delegating step’s profile-derived concrete defaults through that existing narrow inherited-defaults field set, not by passing profile names or profile maps through `:inherited-defaults`. The callee workflow still retains its own explicit overrides.

## Workflow resolution semantics

When resolving a workflow step session config:

1. Read the canonical effective step state for the step form that supports profile configuration.
2. Resolve the step’s `:session-profile` name against the workflow run’s canonical session-profile snapshot.
3. Fail/block atomically when the profile is unknown or invalid.
4. Merge the resolved profile settings into the step effective config.
5. Apply explicit step keys supported by the current workflow grammar as the highest-precedence workflow-authored overrides for their fields.
6. Fall back to the workflow run’s inherited defaults snapshot when neither the step nor the profile supplies a value.

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

Existing direct step keys remain valid where already supported and keep their current meaning. In this task, the supported direct profile-affecting workflow step overrides are `:model` and `:thinking-level`; direct authored `:speed-mode` and `:effort-override` step keys remain out of scope.

## Snapshot and replay requirement

Workflow runs must not re-read mutable profile config for later steps after the run starts.

Top-level workflow invocation snapshots the effective session-profile definitions into canonical workflow-run state before any step executes. The snapshot stores the resolved valid profile settings plus invalid-profile diagnostics needed to produce deterministic errors; it does not store ignored unknown keys. The snapshot must be self-contained enough for later step resolution and replay without consulting user/project config again. A concrete implementation should use a dedicated workflow-run field (for example `:session-profile-snapshot`) rather than overloading `:inherited-defaults`.

Nested delegated workflow runs use two distinct deterministic channels:

1. The delegated run copies or derives its own canonical session-profile snapshot from the delegating run’s immutable snapshot, so profile names requested by steps inside the callee resolve without re-reading mutable config.
2. The delegating step’s already-resolved effective config is projected into the existing narrow `:inherited-defaults` snapshot for the child run. If the delegating step used `:session-profile`, only the resulting concrete model/thinking/speed/effort defaults flow through `:inherited-defaults`; profile maps, profile names, and effective profile definitions do not.

For this task, the nested-run projection must treat profile-derived `:speed-mode` and `:effort-override` in the delegating step's effective config as concrete inherited defaults for the child run. This intentionally extends the task-207 projection rule for those two fields: when the effective config contains a speed or effort field because profile resolution supplied it, that value outranks the parent run snapshot for the delegated child; when the effective config does not contain the field, the projection falls back to the parent run snapshot as task 207 did. This keeps `:inherited-defaults` narrow and concrete while allowing delegate profiles to affect speed/effort deterministically. Implementations must distinguish field presence from truthiness so an explicit resolved effort value of `nil`, if treated as a concrete clear by profile resolution, is not accidentally replaced by the parent snapshot.

This preserves the task-207 inherited-defaults boundary: `:inherited-defaults` remains a resolved concrete default set for model/prompt/tools/skills/thinking/speed/effort, not a profile registry. Profile-name resolution belongs to the workflow-run profile snapshot.

Consequences:

- Editing user or project config after a workflow starts does not affect that run’s later steps.
- Resuming a blocked run reuses its original profile snapshot.
- A fresh workflow invocation uses the current effective config.
- A delegated sub-workflow does not re-read user/project config to resolve profile names, and a parent delegate step does not widen `:inherited-defaults` to carry profile maps.

## Introspection and observability

Profile resolution should be observable enough to answer “why did this session/step use this model?”

At minimum, expose or record:

- selected profile name, when a live session selected one via `/session-profile`
- profile name requested by a workflow step, when present
- concrete resolved settings applied from the profile
- invalid-profile diagnostics for listed or requested profiles
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
2. Project-local/project-shared/user precedence and profile-specific deep merge semantics apply to profile definitions without changing unrelated config merge behavior.
3. `/session-profiles` lists effective profile names and readable settings.
4. `/session-profile <name>` resolves and applies the selected profile to the current session’s concrete model/thinking/speed/effort state.
5. `/session-profile clear` clears selected-profile metadata without reverting concrete session settings.
6. Unknown or invalid profile selection fails atomically with a helpful message and available names or invalid reasons.
7. Supported workflow step forms can specify `:session-profile :profile-name` and receive profile-derived model/thinking/speed/effort settings.
8. Explicit workflow step settings override profile-derived settings for fields where direct workflow step settings are supported.
9. Workflow profile resolution is snapshotted on canonical workflow-run state for deterministic run behavior; mid-run config edits do not affect later steps, delegated runs, or resumed runs.
10. Existing workflows without `:session-profile` behave unchanged.
11. Docs and changelog describe the config shape, command surface, workflow key, and snapshot semantics.

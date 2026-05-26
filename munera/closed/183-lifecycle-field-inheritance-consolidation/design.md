# 183 lifecycle field inheritance consolidation

## Intent

Consolidate the session lifecycle field inheritance sets — currently three independent `select-keys` vectors plus a fourth child-session construction path — into a shared vocabulary that makes the inheritance rules explicit, classifiable, and maintainable.

## Context

Follow-on D from `178-registry-session-membership-unification`.

Tasks 179–182 completed the authority/projection split for tools and prompts. Each migration touched all three lifecycle `select-keys` sets independently (new/resume/fork in `init.clj`) and the child-session construction path (`child_session_state.clj`). The field lists are now correct, but the pattern has accumulated to 17–23 keys per lifecycle path with no shared structure, no classification, and divergences that are intentional but undocumented.

## Problem

Four lifecycle paths independently decide which session fields to inherit:

| Path | Location | Keys inherited | Source |
|------|----------|----------------|--------|
| new | `init.clj:initialize-new-session-state` | 23 | `current-sd` |
| resume | `init.clj:initialize-resumed-session-state` | 21 | `current-sd` |
| fork | `init.clj:initialize-forked-session-state` | 19 | `parent-sd` |
| child | `child_session_state.clj:child-session-base-state*` | ~18 | `parent-sd` + explicit opts |

The divergences between them are intentional but implicit:

- **new + resume** inherit `:base-system-prompt`, `:prompt-component-selection`, `:system-prompt`, `:system-prompt-build-opts` — fork does not (fork rebuilds prompt state from the parent through a different path)
- **new + fork** inherit `:model`, `:thinking-level` — resume does not (resume takes these as explicit parameters)
- **All three** share a common core of 17 keys covering capability membership, preferences, and UI state

When a new field is added to the session schema, a developer must remember to update 3–4 independent key vectors. When a field is removed (as in tasks 180, 182), 3–4 sites must be edited. There is no single place that documents which fields are inherited and why.

## Scope

### In scope

- Extract a shared named constant for the common inherited field set (the 17 keys shared by all three lifecycle paths)
- Define named constants or helpers for the per-path extensions on top of the common set
- Classify each inherited field by role: capability membership, preference, UI, prompt state, or identity
- Apply the shared vocabulary to all three `init.clj` lifecycle functions
- Document the child-session path's relationship to the shared vocabulary: which common-core fields it inherits from parent (7 of 17), which it intentionally defaults (10 of 17, including `:nucleus-prelude-override` which is consumed during prompt derivation rather than set as a standalone field), and the rationale for the divergence
- Document the child-session's relationship to `prompt-state-fields` (all 4 derived via `derive-child-prompt-state`) and `model-identity-fields` (`:model` falls back to parent, `:thinking-level` defaults to `:off`)
- Classify each field group as authoritative (user/config-set) or runtime-derived (set after model resolution)

### Out of scope

- Changing which fields are inherited by which lifecycle path (this task is consolidation, not behavioural change)
- Merging `child_session_state.clj` construction into `init.clj` (the child-session path has different semantics — prompt derivation, tool resolution, workflow linkage — that justify its separate location)
- Shared lifecycle vocabulary for non-session-state domains (workflow registries, runtime handles, etc.)

## Current field classification

### Common core (17 keys — all three lifecycle paths)

Each field is classified by role. Fields are either **authoritative** (set by user/config, persisted across sessions) or **runtime-derived** (set by the runtime after model resolution, transient telemetry).

Capability membership (authoritative):
- `:skill-ids` — authoritative skill membership
- `:tool-ids` — authoritative tool membership
- `:prompt-contribution-ids` — authoritative prompt membership
- `:prompt-templates` — registered prompt templates
- `:extensions` — active extension set

Preferences (authoritative):
- `:auto-retry-enabled` — retry policy
- `:auto-compaction-enabled` — compaction policy
- `:prompt-mode` — lambda vs prose
- `:nucleus-prelude-override` — custom prelude (carried as-is in init.clj paths; consumed during prompt derivation in child-session — see child-session note below)
- `:developer-prompt` — developer-provided prompt
- `:developer-prompt-source` — developer prompt origin
- `:cache-breakpoints` — cache policy
- `:scoped-models` — per-scope model overrides
- `:tool-output-overrides` — per-tool output limits

UI (authoritative):
- `:ui-type` — TUI vs RPC

Telemetry/context (runtime-derived):
- `:context-tokens` — current context usage (set after model resolution, not user-configured)
- `:context-window` — model context window size (set after model resolution, not user-configured)

### new + resume only (4 keys)

Prompt state (carried forward because new/resume preserve the current prompt assembly):
- `:base-system-prompt`
- `:system-prompt`
- `:system-prompt-build-opts`
- `:prompt-component-selection`

### new + fork only (2 keys)

Identity (carried from source because resume takes these as explicit params):
- `:model`
- `:thinking-level`

### Child-session inheritance divergence (intentional)

The child-session path (`child_session_state.clj`) inherits only 7 of the 17 common-core fields from the parent session. The remaining 10 intentionally receive `initial-session` defaults (including `:nucleus-prelude-override`, which is consumed during prompt derivation rather than set as a standalone field). This divergence is correct and must be preserved — child sessions are ephemeral agent-spawned sessions with different semantics.

**Common-core: inherited from parent** (7 fields):
- `:skill-ids` — derived via `derive-child-prompt-state` from parent skills
- `:tool-ids` — derived via `derive-child-prompt-state` (or explicit child opts)
- `:prompt-contribution-ids` — resolved from parent via `prompt-storage/prompt-ids`
- `:prompt-mode` — `(or prompt-mode (:prompt-mode parent-sd))`
- `:developer-prompt` — `(or developer-prompt (:developer-prompt parent-sd))`
- `:developer-prompt-source` — `(or developer-prompt-source (:developer-prompt-source parent-sd))`
- `:cache-breakpoints` — `(or cache-breakpoints (:cache-breakpoints parent-sd) default)`

**Common-core: not inherited — intentional defaults** (10 fields):
- `:nucleus-prelude-override` — read from `parent-sd` inside `default-child-system-prompt-build-opts` and consumed during prompt derivation, but NOT set as a standalone field on the child session data map; flows into the child's `:system-prompt-build-opts` rather than being carried as-is
- `:prompt-templates` — child sessions don't inherit registered prompt templates (default `[]`)
- `:extensions` — child sessions don't inherit active extensions (default `{}`)
- `:auto-retry-enabled` — child sessions use config default, not parent's setting
- `:auto-compaction-enabled` — child sessions default to `false` (ephemeral, no compaction)
- `:scoped-models` — child sessions don't inherit per-scope model overrides (default `[]`)
- `:tool-output-overrides` — child sessions don't inherit per-tool output limits (default `{}`)
- `:ui-type` — child sessions default to `:console` (agent-driven, not user-facing)
- `:context-tokens` — runtime-derived, starts `nil`
- `:context-window` — runtime-derived, starts `nil`

**Prompt-state fields** (all 4 derived):
- `:base-system-prompt` — derived via `derive-child-prompt-state` (built from parent or explicit system-prompt)
- `:system-prompt` — derived via `derive-child-prompt-state`
- `:system-prompt-build-opts` — derived via `default-child-system-prompt-build-opts` (consumes parent's `:nucleus-prelude-override`)
- `:prompt-component-selection` — normalized from child opts via `derive-child-prompt-state`

**Model-identity fields** (`:model` falls back to parent, `:thinking-level` defaults to `:off`):
- `:model` — `(or model (:model parent-sd))`
- `:thinking-level` — `(or thinking-level :off)` (explicit opts, not direct parent inheritance)

The shared classification docstring in `child_session_state.clj` must note this intentional divergence: the child path references the common-core classification but deliberately inherits only the subset needed for prompt derivation and capability membership. The documentation must cover the child-session's relationship to all three constant groups (`common-inherited-fields`, `prompt-state-fields`, `model-identity-fields`), not just common-core.

## Design

### Shared constants

Define in `session_state/init.clj` (or a new `session_state/inheritance.clj` if cleaner):

```clojure
(def ^:private common-inherited-fields
  "Fields inherited by all lifecycle paths (new, resume, fork).

   Authoritative (user/config-set):
     Capability membership: skill-ids, tool-ids, prompt-contribution-ids, prompt-templates, extensions
     Preferences: auto-retry-enabled, auto-compaction-enabled, prompt-mode, nucleus-prelude-override,
                  developer-prompt, developer-prompt-source, cache-breakpoints, scoped-models,
                  tool-output-overrides
     UI: ui-type

   Runtime-derived (set after model resolution, transient):
     Telemetry/context: context-tokens, context-window

   Note: nucleus-prelude-override is carried as-is by init.clj paths but consumed
   during prompt derivation in child-session (not set as standalone child field)."
  [:skill-ids :tool-ids :prompt-contribution-ids :prompt-templates :extensions
   :auto-retry-enabled :auto-compaction-enabled :prompt-mode :nucleus-prelude-override
   :developer-prompt :developer-prompt-source :cache-breakpoints :scoped-models
   :tool-output-overrides :ui-type :context-tokens :context-window])

(def ^:private prompt-state-fields
  "Prompt assembly state — inherited by new and resume, not by fork."
  [:base-system-prompt :system-prompt :system-prompt-build-opts :prompt-component-selection])

(def ^:private model-identity-fields
  "Model identity — inherited by new and fork, not by resume (resume takes explicit params)."
  [:model :thinking-level])
```

### Lifecycle functions

Each lifecycle function replaces its inline `select-keys` vector with a composition:

- **new**: `(select-keys current-sd (into common-inherited-fields (concat prompt-state-fields model-identity-fields)))`
- **resume**: `(select-keys current-sd (into common-inherited-fields prompt-state-fields))`
- **fork**: `(select-keys parent-sd (into common-inherited-fields model-identity-fields))`

### Child-session documentation

`child_session_state.clj` constructs fields explicitly rather than via `select-keys`, so it cannot directly use the same constants. The docstring or comment must:
1. Reference the shared classification constants by name (`common-inherited-fields`, `prompt-state-fields`, `model-identity-fields`)
2. List which 7 common-core fields are inherited from the parent and how (direct carry vs derivation)
3. List which 10 common-core fields intentionally receive `initial-session` defaults and why (including `:nucleus-prelude-override`, consumed during prompt derivation rather than set as a standalone field)
4. Document the child-session's relationship to `prompt-state-fields`: all 4 are derived via `derive-child-prompt-state` (not carried as-is)
5. Document the child-session's relationship to `model-identity-fields`: `:model` falls back to parent, `:thinking-level` defaults to `:off`

## Constraints

- No behavioural change — the exact same fields must be inherited by each path before and after
- The constants must be private to prevent external coupling to the inheritance set
- The classification must be explicit enough that adding/removing a field requires updating one named constant rather than 3 independent vectors

## Acceptance criteria

- A shared named constant defines the common inherited field set
- Named constants define the per-path extensions (prompt-state, model-identity)
- All three `init.clj` lifecycle functions compose their `select-keys` from these constants
- A classification comment or docstring documents the role of each field group (capability membership, preferences, UI, telemetry/context, prompt state, identity) and annotates each group as authoritative or runtime-derived
- `child_session_state.clj` references the shared classification in a comment or docstring, and documents the child-session's relationship to all three constant groups: which common-core fields are intentionally not inherited (with rationale), how prompt-state fields are derived, and how model-identity fields are resolved
- No behavioural change — tests pass without modification
- Adding or removing a lifecycle-inherited field requires changing one constant, not 3+ vectors

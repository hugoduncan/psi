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
- Document the child-session path's relationship to the shared vocabulary (child-session may not directly use the same `select-keys` pattern because it constructs fields explicitly from opts + parent, but it should reference the same classification)
- Ensure every field in the inheritance sets is documented as authoritative or derived

### Out of scope

- Changing which fields are inherited by which lifecycle path (this task is consolidation, not behavioural change)
- Merging `child_session_state.clj` construction into `init.clj` (the child-session path has different semantics — prompt derivation, tool resolution, workflow linkage — that justify its separate location)
- Shared lifecycle vocabulary for non-session-state domains (workflow registries, runtime handles, etc.)

## Current field classification

### Common core (17 keys — all three lifecycle paths)

Capability membership:
- `:skill-ids` — authoritative skill membership
- `:tool-ids` — authoritative tool membership
- `:prompt-contribution-ids` — authoritative prompt membership
- `:prompt-templates` — registered prompt templates
- `:extensions` — active extension set

Preferences:
- `:auto-retry-enabled` — retry policy
- `:auto-compaction-enabled` — compaction policy
- `:prompt-mode` — lambda vs prose
- `:nucleus-prelude-override` — custom prelude
- `:developer-prompt` — developer-provided prompt
- `:developer-prompt-source` — developer prompt origin
- `:cache-breakpoints` — cache policy
- `:scoped-models` — per-scope model overrides
- `:tool-output-overrides` — per-tool output limits

UI:
- `:ui-type` — TUI vs RPC

Telemetry/context:
- `:context-tokens` — current context usage
- `:context-window` — model context window size

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

## Design

### Shared constants

Define in `session_state/init.clj` (or a new `session_state/inheritance.clj` if cleaner):

```clojure
(def ^:private common-inherited-fields
  "Fields inherited by all lifecycle paths (new, resume, fork).
   Capability membership, preferences, UI, and context telemetry."
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

`child_session_state.clj` constructs fields explicitly rather than via `select-keys`, so it cannot directly use the same constants. However, the design should add a docstring or comment that references the shared classification and explains which capability membership fields the child path inherits from the parent.

## Constraints

- No behavioural change — the exact same fields must be inherited by each path before and after
- The constants must be private to prevent external coupling to the inheritance set
- The classification must be explicit enough that adding/removing a field requires updating one named constant rather than 3 independent vectors

## Acceptance criteria

- A shared named constant defines the common inherited field set
- Named constants define the per-path extensions (prompt-state, model-identity)
- All three `init.clj` lifecycle functions compose their `select-keys` from these constants
- A classification comment or docstring documents the role of each field group
- `child_session_state.clj` references the shared classification in a comment or docstring
- No behavioural change — tests pass without modification
- Adding or removing a lifecycle-inherited field requires changing one constant, not 3+ vectors

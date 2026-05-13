# 140 — Workflow IR Compilation Errors Actionable

## Provenance
- GitHub issue: #73
- Issue URL: https://github.com/hugoduncan/psi/issues/73
- Issue title: Make workflow IR compilation errors actionable
- PR: #89
- PR URL: https://github.com/hugoduncan/psi/pull/89
- PR branch: 73-workflow-ir-compilation-errors

## Problem Statement

Workflow IR compilation failures emit a single opaque message:

> Workflow definition does not compile to execution-valid canonical IR

The message carries no location (which step, which section of the definition) and no
reason (which constraint was violated). A developer or agent cannot diagnose or repair
a broken workflow definition from this error alone — source inspection or IR-level
debugging is required.

## Existing Architecture and Failure Path

The compilation pipeline has three distinct phases, each of which can fail:

### Phase 1 — Target-authored shape check (`workflow-loader/compiler.clj`)
`compile-workflow-file` checks that the file has a name and that the config is a
target-authored shape (`{:steps [...]}` with each step a map). Failures return
`{:error string}` — already somewhat actionable.

### Phase 2 — Source-to-IR compilation (`workflow-runtime/target-ir-compiler.clj`)
`compile-workflow-definition` walks each step and calls per-step helpers
(`compile-step`, `compile-contribution`, `compile-source-spec`, etc.). Each helper
throws `ExceptionInfo` with a descriptive message and structured `ex-data` when it
encounters a malformed authored construct. The exception includes the offending value
but **does not include the step name or step index**.

### Phase 3 — IR structural + semantic validation (`workflow-runtime/ir.clj`)
`validate-workflow-ir` runs Malli structural validation and then `semantic-errors`.
`semantic-errors` already returns typed, per-step error maps (e.g.
`{:type :non-prior-step-ref :step "build" :ref {...}}`). Structural errors are raw
Malli `explain-data` — not yet formatted for users.

### Phase 4 — Run creation gate (`workflow-runtime/core.clj`)
`compile-definition-to-ir!` calls phase 2 + phase 3 and, on any failure, throws a
**new** `ExceptionInfo` with the fixed opaque message, discarding the structured
detail from phases 2 and 3. This is the root of the problem.

### Phase 5 — Mutation surface (`mutations/canonical_workflows.clj`)
Pathom mutations catch `Exception` broadly and call `(ex-message e)`, so only the
top-level string survives to the user.

## Root Cause

`compile-definition-to-ir!` in `workflow-runtime/core.clj` wraps all failure detail
in a new opaque exception and throws only the generic message. Two sub-problems:

1. **Phase 2 exceptions lose step context**: `compile-step` dispatches to helpers that
   throw with the offending value but not the step name. The step name is only
   available in the `compile-workflow-definition` loop, not inside the helpers.

2. **Phase 4 discards structured errors**: `compile-definition-to-ir!` receives
   `{:compile-error …, :structural-errors …, :semantic-errors […]}` from
   `compile-and-validate-workflow-definition` and throws a new exception with none of
   that detail in the message.

## Chosen Implementation Strategy

**Enrich errors at the point they are generated, then format them into a single
actionable string at the boundary where they surface to the user.**

### Why this fits the existing architecture
- Phase 3 (`ir.clj` semantic errors) already has per-step typed maps. The format
  function lives there or adjacent to it.
- Phase 2 (`target-ir-compiler.clj`) already throws `ExceptionInfo`; adding step
  context requires one small change in `compile-workflow-definition` to catch and
  re-throw with step name + index appended to `ex-data`.
- Phase 4 (`core.clj`) already holds all the structured data; it needs to call a
  shared formatter instead of discarding it.
- Phase 5 (mutations) already calls `(ex-message e)`; if the message is now
  actionable, no changes are required there.

This strategy avoids changing the public API of any namespace, avoids adding new
protocol concepts, and keeps the change local to the compilation path.

## Key Algorithms and Procedural Approach

### Step 1: Add step context to Phase 2 compile exceptions

In `compile-workflow-definition` (target-ir-compiler.clj), wrap the `(compile-step
step)` call in a try/catch. On `ExceptionInfo`, re-throw with `:step-name` and
`:step-index` merged into `ex-data`. On any other exception, wrap into `ExceptionInfo`
with the same context fields.

```clojure
(defn- compile-step-with-context [step idx]
  (try
    (compile-step step)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (ex-message e)
                      (merge (ex-data e)
                             {:step-name  (:name step)
                              :step-index idx}))))
    (catch Exception e
      (throw (ex-info (str "Unexpected error compiling step: " (ex-message e))
                      {:step-name  (:name step)
                       :step-index idx})))))
```

`compile-workflow-definition` becomes:
```clojure
(cond-> {:version :workflow-ir/v1
         :steps (vec (map-indexed (fn [idx step]
                                    (compile-step-with-context step idx))
                                  (:steps workflow-definition)))}
  ...)
```

### Step 2: Add a shared error-message formatter

Add `format-compilation-errors` in `workflow-runtime/ir.clj` (or a new thin
`workflow-runtime/error-format.clj`). It accepts the three error channels and returns
a single human-readable string.

Formatting rules:
- If `compile-error` is non-nil: it is a map `{:message string :data map}`.
  Include `:step-name` and `:step-index` from `:data` when present.
  Format: `"Step '<name>' (index <N>): <message>"` or `"<message>"` when no step
  context is available.
- If `structural-errors` is non-nil: render the Malli explain-data into human-readable
  form. For each failing path, emit the path and the error message. Keep it brief.
  Format: `"Structural error at <path>: <message>"`.
- If `semantic-errors` is non-empty: render each typed error map.
  Known types and their format strings:
  - `:routing-without-judge` → `"Step '<step>': routing table (:on) requires a judge"`
  - `:judge-without-routing` → `"Step '<step>': judge requires a non-empty routing table (:on)"`
  - `:missing-yields` → `"Step '<step>': missing :yields"`
  - `:missing-local-yield-output-key` → `"Step '<step>': yield references output key :<key> which is not declared in :outputs (available: <keys>)"`
  - `:missing-step-ref` → `"Step '<step>': references unknown step '<target>'"`
  - `:non-prior-step-ref` → `"Step '<step>': references step '<target>' which is not prior (forward/self references are not allowed)"`
  - `:missing-output-key` → `"Step '<step>': references output key :<key> of step '<target>' but that key is not declared (available: <keys>)"`
  - `:missing-yield-field` → `"Step '<step>': references yield field :<field> of step '<target>' but that field is not available (available: <fields>)"`
  - unknown type → `"Step '<step>': <type> (raw: <map>)"`
- Multiple errors are joined with newlines.
- Prefix the whole message with `"Workflow IR compilation failed:\n"`.

### Step 3: Use the formatter in `compile-definition-to-ir!`

Replace the opaque throw in `compile-definition-to-ir!` with:
```clojure
(when-not valid?
  (throw (ex-info (format-compilation-errors compile-error structural-errors semantic-errors)
                  {:source source
                   :definition-id (:definition-id definition)
                   :authored-grammar :target
                   :compile-error compile-error   ; {:message string :data map} or nil
                   :structural-errors structural-errors
                   :semantic-errors semantic-errors})))
```

The `ex-data` is preserved for programmatic consumers; the message is now actionable.

Note: `compile-error` in `ex-data` is now a map `{:message … :data …}` (not a bare
string). Any consumer that previously read `(get (ex-data e) :compile-error)` as a
string must be updated to use `(get-in (ex-data e) [:compile-error :message])`.
In this codebase no external consumer reads `:compile-error` directly — the existing
tests only check `:compile-error` via `compile-and-validate-workflow-definition`
return values, so the shape change is contained to that return map and `core.clj`.

## Main Data Structures and State Shapes

No new persistent state. The internal error data shapes are:

### Compile-error context (ex-data enrichment in Phase 2)
```clojure
{:step-name  string?   ; name of the step being compiled, when available
 :step-index int?      ; zero-based index of the step, when available
 ;; plus whatever the original helper put in ex-data
}
```

### Semantic error map (already exists in ir.clj)
```clojure
{:type    keyword?   ; :routing-without-judge | :judge-without-routing | :missing-yields | ...
 :step    string?    ; step name
 :ref     map?       ; source ref, when applicable
 :output-key keyword? ; when applicable
 :yield-field keyword? ; when applicable
 :available-outputs  [keyword?]  ; when applicable
 :available-yield-fields [keyword?] ; when applicable
}
```

### Compile-error return shape from `compile-and-validate-workflow-definition`

`compile-and-validate-workflow-definition` currently stores only `.getMessage e` (a
string) in `:compile-error`. The formatter needs `:step-name` and `:step-index` from
`ex-data` to emit step-contextual messages. Therefore the catch block must be updated
to also capture `ex-data`:

```clojure
;; updated catch block in compile-and-validate-workflow-definition
(catch clojure.lang.ExceptionInfo e
  {:valid? false
   :ir nil
   :structural-errors nil
   :semantic-errors []
   :compile-error {:message (ex-message e)
                   :data    (ex-data e)}})
```

`compile-definition-to-ir!` in `core.clj` destructures `:compile-error` as a map
(not a string) and passes it to `format-compilation-errors`. The docstring for
`compile-and-validate-workflow-definition` must be updated to reflect the new shape:

```clojure
;; updated return spec comment
{:valid? boolean
 :ir workflow-ir?
 :structural-errors explain-data?
 :semantic-errors [error*]
 :compile-error {:message string :data map}?}   ; nil when no compile error
```

### Formatter input / output
```clojure
;; input
compile-error     : {:message string :data map}? | nil
structural-errors : malli/explain-data | nil
semantic-errors   : [semantic-error-map]

;; output
string  ; multi-line human-readable message
```

## Interface Surfaces to Add or Change

### Code changes
- `psi.workflow-runtime.target-ir-compiler`
  - Add `compile-step-with-context` private helper.
  - Change `compile-workflow-definition` to use it (replaces `(mapv compile-step ...)`)
  - Change `compile-and-validate-workflow-definition` catch block: `:compile-error`
    value changes from `string` to `{:message string :data map}`.
    Docstring updated to reflect new return shape.
  - **Existing callers**: `compile-target-dynamic-delegate-invalid-target-shape-test`
    and `compile-target-judge-routing-and-loop-bounds-test` in
    `target_ir_compiler_test.clj` compare `:compile-error` to a string — these tests
    must be updated to compare against `(get-in result [:compile-error :message])`.

- `psi.workflow-runtime.ir` (preferred) or new `psi.workflow-runtime.error-format`
  - Add `format-compilation-errors [compile-error structural-errors semantic-errors] → string`.
  - Public function; callable from `core.clj`.

- `psi.workflow-runtime.core`
  - Change `compile-definition-to-ir!` to call `format-compilation-errors` instead of
    using the fixed opaque message.
  - No public API change.

### Documentation
- No user-facing doc changes required for this task. The improvement is in error
  output that developers and agents already encounter.
- If the workflow-grammar.md authoring guide references error behavior, update it to
  reflect the new actionable format (optional, not blocking).

### No changes required in
- `mutations/canonical_workflows.clj` — `(ex-message e)` already surfaces the
  message; once the message is actionable, the mutation surface is correct.
- `workflow-loader/compiler.clj` — already returns `{:error string}` with reasonable
  messages.
- `workflow-registry/` — not involved in IR compilation.

## Invariants

- The public API of `compile-workflow-definition` and `compile-and-validate-workflow-definition` does not change.
- `compile-definition-to-ir!` still throws `ExceptionInfo`; only the message content changes.
- `ex-data` on the thrown exception retains all structured fields (for programmatic consumers).
- Valid workflow definitions compile without change — no regression.
- Same malformed input always produces the same error message (deterministic).
- Error messages do not expose raw Clojure stack traces or internal IR field names beyond what is already in the authored grammar doc.

## Edge Cases and Explicit Decisions

- **Multiple errors**: structural and semantic errors can be plural. The formatter must
  enumerate all of them, not just the first.
- **Structural error formatting**: Malli `explain-data` is verbose. The formatter
  should extract the `:errors` seq and render each `{:path … :message …}` pair
  concisely. Full Malli output must not be dumped raw into the user message.
- **Compile error without step context**: Some `ExceptionInfo` throws in phase 2 occur
  before a step is identified (e.g. the top-level `target-authored-workflow-definition?`
  guard). These should surface as-is without a step prefix.
- **Unknown semantic error type**: Use a fallback format rather than throwing again.
- **Formatter placement**: Prefer adding `format-compilation-errors` to
  `psi.workflow-runtime.ir` because it already owns semantic error types and Malli
  schemas. If the function grows complex, extract to `psi.workflow-runtime.error-format`
  and require it from both `ir.clj` and `core.clj`.
- **`NO_PROXY` / proxy scope**: not relevant to this task.

## Alternatives Considered

### Return structured errors instead of throwing
`compile-definition-to-ir!` could return `{:ir … :errors […]}` instead of throwing.
Rejected: would require changing `normalize-effective-definition` and `create-run`
return shapes, which is a larger refactor than the issue warrants. The existing
throw-based contract is preserved; only the message content changes.

### Format errors at the mutation surface
`canonical_workflows.clj` could call a formatter on `(ex-data e)` instead of
`(ex-message e)`. Rejected: the mutation surface is not the right owner of
compilation error formatting, and it would require the mutation layer to understand
IR error structures. Better to own the message at the compilation boundary.

### Add a new `:error-message` field to `compile-and-validate-workflow-definition` output
Could add `:formatted-error-message` to the return map. Viable, but adds a new field
that callers must know to use. The simpler approach — fix the thrown message — is
sufficient and does not change the return shape.

## Verification Expectations

The implementation is done when all of the following are true:

1. A test with a step that has an unsupported type produces a message containing the
   step name and the violation.
2. A test with a step that references a non-existent prior step produces a message
   containing both the referring step name and the referenced step name.
3. A test with a step that has a judge but no routing table produces a message
   containing the step name and the constraint.
4. A test with a structurally invalid IR (Malli schema violation) produces a message
   that includes a path or field name, not raw Malli explain-data.
5. A test with a valid workflow definition produces no error and compiles without
   regression.
6. All existing tests in `target_ir_compiler_test.clj`, `ir_runtime_adoption_test.clj`,
   and `compiler_test.clj` remain green (after updating the two tests that compare
   `:compile-error` to a bare string — see Interface Surfaces section).
7. The formatter is covered by its own focused unit tests for each semantic error type.
8. A test confirms that `create-run` (via `compile-definition-to-ir!`) surfaces a
   step-contextual message when a step with an unsupported type is compiled.

## Acceptance Criteria

1. Every IR compilation failure identifies the problematic location: step name, step
   index, or named workflow section.
2. Every failure states the violated constraint or reason in terms a developer or agent
   can act on.
3. A developer or agent can pinpoint and fix the broken workflow definition solely from
   the error message, without inspecting IR internals or source code.
4. Existing tests remain green; new failure modes introduced by the fix are covered by
   tests.

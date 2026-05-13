# Implementation

Provenance:
- GitHub issue #73 — https://github.com/hugoduncan/psi/issues/73
- PR #89 — https://github.com/hugoduncan/psi/pull/89
- PR branch: 73-workflow-ir-compilation-errors

## Notes

### Design refinement pass (2026-05-13)

Root cause traced precisely:

1. `compile-definition-to-ir!` in `core.clj` is the discard site — it receives
   structured `{:compile-error, :structural-errors, :semantic-errors}` from
   `compile-and-validate-workflow-definition` but throws a new exception with only the
   opaque fixed message.

2. Phase 2 exceptions (`target-ir-compiler.clj` helpers) throw with the offending
   value in `ex-data` but without step name/index — context is only available in the
   `compile-workflow-definition` loop, not inside the helpers.

3. Phase 3 semantic errors (`ir.clj`) already have per-step typed maps with step name.
   Structural errors are raw Malli explain-data.

4. Mutation surface (`canonical_workflows.clj`) calls `(ex-message e)` — no change
   needed there once the message is actionable.

Strategy: enrich at source (step context in phase 2), format at boundary (phase 4),
no public API changes, no mutation surface changes.

Formatter placement decision: `psi.workflow-runtime.ir` preferred because it already
owns semantic error type definitions and Malli schemas. If it grows too large, extract
to `psi.workflow-runtime.error-format`.

### Design refinement pass 2 (2026-05-13) — compile-error shape gap

Identified a data-shape gap: `compile-and-validate-workflow-definition` catch block
stores only `.getMessage e` (a string) as `:compile-error`, but the formatter needs
`:step-name`/`:step-index` from `ex-data` to emit step-contextual messages.

Resolution: change the catch block to store `{:message (ex-message e) :data (ex-data e)}`
as `:compile-error`. Formatter receives the map and extracts `:data` for step context.

Impact on existing tests: two tests in `target_ir_compiler_test.clj` compare
`:compile-error` directly to a string — they must be updated to compare
`(get-in result [:compile-error :message])` instead. No other callers affected.

PR provenance added to design.md and this file.

### Implementation pass (2026-05-13) — complete

Commit: 3fe3d3b7

**Files changed:**
- `target_ir_compiler.clj`: added `compile-step-with-context`; `compile-workflow-definition` uses `map-indexed`; catch block stores `{:message … :data …}`
- `ir.clj`: added `clojure.string` require; added `format-compile-error`, `format-structural-error`, `format-semantic-error`, `format-compilation-errors` (public)
- `core.clj`: added `workflow-ir` require; `compile-definition-to-ir!` calls `format-compilation-errors`
- `compilation_error_format_test.clj`: new — unit tests for all semantic error types, structural errors, step context, multi-error, integration tests for `create-run`
- `target_ir_compiler_test.clj`: two `:compile-error` bare-string comparisons → `(get-in … [:message])`
- `ir_runtime_adoption_test.clj`: `#"execution-valid canonical IR"` → `#"Workflow IR compilation failed"`

**Deviations from design:**
- None. All design decisions followed as specified.

**Lint:** 0 errors, 0 warnings (clj-kondo + cljfmt pre-commit hooks passed)
**Tests:** 1756 tests, 0 failures (full unit suite)

### task-implementation-review pass (2026-05-13)

All acceptance criteria met. All 1756+150 tests green. Code matches design precisely.

**One gap found:**

`format-semantic-error` in `ir.clj` handles `:skills-without-read-tool` (added beyond
the design's semantic-error-type list), but `compilation_error_format_test.clj` has no
focused unit test for this branch. Design acceptance criterion #7 requires "the formatter
is covered by its own focused unit tests for each semantic error type." The
`:skills-without-read-tool` case is exercised via `ir_test.clj` at the semantic-validator
level but not at the formatter level.

**No other issues.** Architecture fit, error enrichment, formatter wiring, mutation-surface
passthrough, and test coverage for all design-listed types are all correct.

### Follow-up pass (2026-05-13) — skills-without-read-tool formatter test

Added `format-semantic-error-skills-without-read-tool-test` to
`compilation_error_format_test.clj`. Asserts that `:skills-without-read-tool` produces
a line containing the step name and the "skills require the 'read' tool" constraint
message. Suite: 16 tests, 50 assertions, 0 failures. Lint clean.

Acceptance criterion #7 now fully satisfied — all semantic error types covered at the
formatter level.

### task-test-review pass (2026-05-13)

**Issue 1 — `format-structural-error` produces blank description with real Malli data (bug)**

Malli `explain-data` error entries do not carry a `:message` key by default — only
`:path`, `:in`, `:schema`, `:value`, `:type` (e.g. `:malli.core/missing-key`).
`format-structural-error` uses `(:message error-entry)` which is `nil` for most schema
violations, producing `"Structural error at [...]: "` (empty after the colon).

Verified at runtime:
```
Structural error at [:steps 0 :session :session :contributions 0 :template :vars 1 0 :value]: 
```

The `format-structural-errors-test` uses hand-crafted `{:errors [{:path [...] :message "..."}]}`
maps that always have `:message` — the test passes but does not exercise real Malli output.

Fix: `format-structural-error` should fall back to `(:type error-entry)` (or a
human-friendly rendering of it) when `:message` is nil. E.g.:
```clojure
(let [msg (or message (some-> type name))]
  (if (seq path)
    (str "Structural error at " (pr-str path) ": " msg)
    (str "Structural error: " msg)))
```
Also update `format-structural-errors-test` to include a case using real Malli
`explain-data` (via `explain-workflow-ir`) to guard against regression.

Design acceptance criterion #4 requires the message "includes a path or field name,
not raw Malli explain-data" — the path is present, but the blank description makes the
message only partially actionable.

**Issue 2 — No `create-run` integration test for structural error path**

Design verification expectation #4: "A test with a structurally invalid IR (Malli
schema violation) produces a message that includes a path or field name, not raw Malli
explain-data." `create-run-surfaces-step-contextual-message-test` covers compile errors
and semantic errors but not structural errors end-to-end. Add a `create-run` case that
triggers a structural error (e.g. `:workflow-runtime` source ref) and asserts the
message contains a path segment and does not contain raw Malli schema data.

### Follow-up pass 3 (2026-05-13) — "invalid value" fallback test

Added `testing` block `"\"invalid value\" fallback when entry has neither :message nor :type"`
inside `format-structural-errors-test` in `compilation_error_format_test.clj`.

Hand-crafts a Malli error entry with only `:path` and `:in` (no `:message`, no `:type`).
Asserts:
- output contains `"invalid value"` (catch-all fires)
- no `"Structural error"` line ends with a bare `":"`

Verification: 16 tests, 63 assertions, 0 failures; lint 0 errors, 0 warnings.

All acceptance criteria and verification expectations now fully satisfied.
Task closed.

### task-test-review pass 2 (2026-05-13)

**All acceptance criteria and verification expectations are met.** 1757 tests, 0 failures.
All semantic error types have formatter-level unit tests. Structural error blank-description
bug was fixed. `create-run` integration tests cover unsupported-step, non-prior-step-ref,
judge-without-routing, and structural-error paths.

**One minor gap found:**

`format-structural-error` has a final `"invalid value"` fallback for Malli error entries
with neither `:message` nor `:type`. The real-Malli test in `format-structural-errors-test`
exercises the `:type` fallback path (e.g. `:malli.core/missing-key`), but the `"invalid value"`
catch-all has no test. This is a robustness gap for hypothetical future Malli error shapes
where no `:type` key is present — unlikely in practice but untested.

**No other issues.** Mutation surface passthrough, step-context enrichment, formatter wiring,
and all design-listed semantic error types are fully covered.

### Follow-up pass 2 (2026-05-13) — structural error formatter fix + tests

**Root cause of blank description:** Real Malli explain-data error entries carry
`:path`, `:in`, `:schema`, `:value` and sometimes `:type` (e.g.
`:malli.core/missing-key`, `:malli.core/invalid-type`) but NEVER `:message`. Some
entries (e.g. `[:= :workflow-input]` schema failures) have no `:type` either —
only `:path`, `:in`, `:schema`, `:value`.

**Fix applied:** `format-structural-error` in `ir.clj` now uses:
```clojure
(let [msg (or message (some-> type name) "invalid value")]
  ...)
```
Falls back to `(name type)` when `:type` is present (e.g. `"missing-key"`),
then to `"invalid value"` as a final catch-all. Description is never blank.

**Tests added:**
1. `format-structural-errors-test` — new `testing` block: calls `explain-workflow-ir`
   with a `:workflow-runtime` source ref (real Malli explain-data), asserts every
   "Structural error" line does not end with a bare ":".
2. `create-run-surfaces-step-contextual-message-test` — new `testing` block: same
   `:workflow-runtime` source ref triggers a structural error via `create-run`; asserts
   message contains "Structural error", description is not blank, and
   "malli.core" does not appear.

**Verification:** 1757 tests, 12130 assertions, 0 failures; lint 0 errors, 0 warnings.
All acceptance criteria now fully satisfied including verification expectation #4.

### test-shaper follow-up pass (2026-05-13) — all 4 signal/robustness gaps resolved

All four gaps from the test-shaper review applied to `compilation_error_format_test.clj`:

**Gap 1 — combined substring assertion:**
`format-compile-error-with-step-context-test` now asserts the combined string
`"Step 'my-step' (index 2): Unsupported target workflow step type"` on a single line.
The two independent `contains-line?` checks were replaced by one joint assertion.

**Gap 2 — path-presence assertion in real-Malli block:**
`format-structural-errors-test` "real Malli explain-data" testing block now asserts
that the output contains `"steps"` or `"contributions"` — satisfying design criterion
#4 ("includes a path or field name").

**Gap 3 — two-step forward-reference integration test:**
`create-run-surfaces-step-contextual-message-test` "non-prior step ref" case replaced
with a two-step workflow where step-b (first) forward-references step-a (second).
Referrer and target now have distinct names; assertions check both `"step-b"`,
`"step-a"`, and `"not prior"` independently.

**Gap 4 — constraint text + mixed-channel test:**
`format-multiple-errors-test` now asserts constraint text (`"missing :yields"`,
`"routing table"`) in addition to step names. Added a second testing block for a
mixed compile-error + semantic-errors call, asserting both channels render.

Verification: 16 tests, 70 assertions, 0 failures; lint 0 errors, 0 warnings.

### test-shaper review (2026-05-13)

`compilation_error_format_test.clj` — four signal/robustness gaps found:

**Gap 1 — step-context + message co-location not pinned.**
`format-compile-error-with-step-context-test` uses two independent `contains-line?`
checks for `"Step 'my-step' (index 2):"` and `"Unsupported target workflow step type"`.
These would pass even if the formatter split them onto separate lines. The contract
is that they appear on the same line; the test does not enforce it.
Fix: assert the combined substring `"Step 'my-step' (index 2): Unsupported target workflow step type"` is present.

**Gap 2 — real-Malli structural test does not assert the path appears.**
The `"real Malli explain-data"` testing block checks only that every "Structural error"
line is non-blank after the colon. It does not assert that the path appears in the
output. Design acceptance criterion #4 and verification expectation #4 require
"includes a path or field name". The path check is absent.
Fix: add an assertion that the output contains a path segment (e.g. `"steps"` or
`"contributions"`) from the real explain-data.

**Gap 3 — non-prior-step-ref integration test uses a self-reference.**
`create-run-surfaces-step-contextual-message-test` "non-prior step ref produces message
naming both steps" uses `step-a` referencing itself. Since referrer and target share
the same name, checking `(str/includes? msg "step-a")` does not distinguish the two
roles. The test description overstates what is verified.
Fix: either use a two-step workflow where step-b forward-references step-a (different
names), or narrow the description to "self-reference is detected".

**Gap 4 — format-multiple-errors-test: only step names checked, not constraint text.**
The test checks that `"step-a"`, `"step-b"`, `"step-c"` appear in the output but does
not verify that the constraint text for each error type is present. A formatter bug
that dropped the constraint text but kept the step name would pass. Also: no test
covers a mix of compile-error + semantic-errors in one call (the formatter supports
this but it is untested).
Fix: add at least one assertion per error entry that checks the constraint text, not
just the step name. Add a mixed compile-error + semantic-error test.

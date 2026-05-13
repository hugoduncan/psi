# Implementation

Provenance: GitHub issue #73 — https://github.com/hugoduncan/psi/issues/73

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

_Append further decisions, discoveries, and trade-offs here as implementation progresses._

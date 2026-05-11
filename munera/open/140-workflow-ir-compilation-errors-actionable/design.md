# 140 — Workflow IR Compilation Errors Actionable

## Problem Statement

Workflow IR compilation failures emit a single opaque message:

> Workflow definition does not compile to execution-valid canonical IR

The message carries no location (which step, which section of the definition) and no reason (which constraint was violated). A developer or agent cannot diagnose or repair a broken workflow definition from this error alone — source inspection or IR-level debugging is required.

## Constraints and Invariants

- Error information must be derivable at compilation time; the IR compiler processes the workflow definition as a pure transformation before any execution occurs.
- Error messages must not expose raw internal IR structure or implementation-private field names.
- All existing compilation failure modes must produce actionable output; no failure path may remain silent or collapse into the existing opaque message.
- Same malformed input always produces the same error message (deterministic).
- Valid workflow definitions must continue to compile without new errors or warnings (no regression).

## Success Criteria

- Every IR compilation failure identifies the problematic location: at minimum the step name or step index, or the named workflow section where the violation occurs.
- Every failure states the violated constraint or reason for rejection in terms a developer or agent can act on.
- A developer or agent can pinpoint and fix the broken workflow definition solely from the error message, without inspecting IR internals, intermediate representations, or source code.
- Existing tests remain green; new failure modes introduced by the fix are covered by tests.

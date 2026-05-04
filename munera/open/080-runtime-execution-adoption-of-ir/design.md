Goal: make workflow runtime execution consume normalized workflow IR rather than current authored workflow step maps.

## Intent

Task `077` established that the normalized workflow IR is the canonical runtime model. After IR schema definition and current-grammar compilation exist, the execution engine itself must pivot to IR so runtime semantics are no longer coupled to the current authored grammar.

## Problem statement

Even with an IR schema and a current-grammar -> IR compiler, the migration does not achieve its architectural goal until runtime execution, progression, routing, attempts, and observability consume IR directly.

If execution continues to depend on current authored step maps:

- the IR remains documentation rather than the real runtime boundary
- compatibility concerns stay distributed through runtime code
- later target-grammar work would require another execution-path rewrite
- deterministic invoke and explicit delegate execution would have no stable substrate

## Scope

In scope:

- adapt workflow execution to consume normalized IR
- adapt step progression, routing, judge execution, and loop handling to IR field locations
- adapt attempt/result recording and observability surfaces to IR concepts internally while keeping current observer-facing workflow run / step-run / attempt contracts shape-stable for this slice
- keep current authored workflows running by compiling them to IR first
- reject current-grammar workflows that still rely on `:workflow-runtime` source refs at the runtime execution-entry seam until a later slice defines a canonical IR runtime-source surface
- prove runtime behavior remains green for representative existing workflows

Out of scope:

- target authored grammar -> IR compiler
- first-class invoke execution implementation beyond what is necessary to preserve current session-style workflows
- broad built-in workflow migration to target grammar

## Desired outcome

Runtime code can execute normalized IR values directly, and existing authored workflows continue to run by passing through the current-grammar compatibility compiler first when that compiled IR is canonical and execution-valid. Current-grammar compatibility refs that compile to non-canonical `:workflow-runtime` sources are rejected explicitly at the runtime execution-entry seam rather than being executed implicitly.

## Acceptance

- the workflow runtime executes normalized IR, not current authored step maps
- progression/routing/judge behavior remains correct for representative existing workflows
- current authored workflows still run through current-grammar -> IR compilation when the compiled IR passes canonical execution validation
- workflows whose compiled IR still contains current-grammar `:workflow-runtime` refs fail fast at the runtime execution-entry seam with an explicit validation error
- observer-facing workflow run / step-run / attempt / history resolver surfaces stay shape-stable in this slice even as execution internals pivot to IR
- observability/attempt recording surfaces remain coherent after the pivot and do not drift independently from execution
- execution no longer depends on current authored field names such as `:executor`, `:prompt-template`, or `:input-bindings`

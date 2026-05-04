Approach:
- treat this as the runtime boundary for deterministic invoke execution, not just as a convenience lookup table
- keep operation identity author-facing and stable via canonical ids such as `"github/search-issues-by-label"`
- make the registry runtime-owned even when extensions provide implementations
- prefer a small explicit contract: operation id, implementation entrypoint, argument map, structured result, and minimal metadata for introspection
- keep first cut narrow and executable before expanding into richer typed schemas or broader discovery stories

Likely steps:
1. inspect existing extension/runtime registration patterns that may inform operation registration shape
2. choose the canonical home for deterministic operation registry state and lookup
3. define the registration shape for an operation, including id, implementation target, and minimal metadata
4. define the invocation contract: what runtime passes in, what operation implementations return, and how failure is signaled
5. define the canonical success/failure result shape used by invoke-step execution and downstream references
6. decide how duplicate ids, missing ids, and malformed returned values are handled
7. add focused tests for registration, lookup, invocation, and boundary enforcement
8. tighten docs if implementation reveals a mismatch with task `077` or `doc/workflow-ir.md`

Proof target:
- runtime can resolve a stable operation id to one explicit implementation contract and receive a canonical structured result suitable for invoke-step execution

Risks:
- over-designing the first-cut contract before real invoke-step execution proves what is needed
- under-specifying result/failure behavior and forcing later runtime code to guess
- leaking implementation-specific code references into authored workflow semantics

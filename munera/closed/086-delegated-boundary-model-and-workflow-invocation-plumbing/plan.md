Approach:
- execution boundary decision: task `086` targets compiled normalized workflow IR as the runtime execution surface; target-authored hoisted delegate fields (`:target`, `:prompt-string`, `:context`) are in scope only insofar as they must compile through the named compiler seam into IR `:delegate` payloads before runtime consumes them
- dependency note: this slice should reuse the shared source/reference/projection semantics defined in task `077` and already exercised by the landed deterministic workflow runtime/compiler slices (`081`, `083`, `085`) so delegated `:context` and templated `:prompt-string` resolution match the rest of workflow data flow; any later extraction into a separate dedicated task can remain a follow-on rather than a prerequisite for this slice
- treat delegated execution as a distinct workflow boundary, not as a disguised child-session call
- keep the boundary explicit: rendered prompt string, ordered forwarded context, target workflow identity, and propagated yielded value should all be visible in data and recording surfaces
- reuse existing workflow-loading and workflow-execution machinery where possible, but normalize delegated semantics through the IR execution path
- preserve first-cut simplicity by forbidding delegated session overrides and keeping context forwarding source-shaped
- authoritative first-cut callee payload shape for this task:
  - callee `:workflow-input` = final rendered delegated `:prompt-string`
  - callee `:workflow-original` = materialized delegated `:context` as a bare ordered vector
  - richer boundary metadata, if recorded, stays in caller-side delegated execution/introspection surfaces rather than being wrapped into the callee input/original values

Likely steps:
1. identify the compiled normalized IR execution seam where `:type :delegate` dispatch should be added or converged
2. confirm the compiler seam keeps authored hoisted delegate fields compiling into runtime IR `:delegate` payloads rather than bypassing the IR boundary
3. resolve the target workflow definition through existing loader/runtime facilities
4. render delegated IR `:prompt-string` to a final string from literal or template form
5. resolve delegated IR `:context` items from workflow input/original and prior step outputs/yields while preserving authored order
6. establish the callee invocation payload so local `:workflow-input` is the rendered prompt string and local `:workflow-original` follows delegated-boundary semantics
7. execute the callee workflow through the canonical workflow runtime path
8. propagate the callee yielded value and relevant execution outcome back into the delegating step runtime surfaces
9. add focused tests for delegate-only and mixed delegate/session/invoke flows

Proof target:
- an IR delegate step invokes a target workflow through an explicit delegated boundary and returns the callee yielded value coherently to the caller workflow

Risks:
- existing delegation paths may carry transport- or UX-specific assumptions that do not match canonical workflow-boundary semantics
- workflow-input/workflow-original semantics could drift unless the callee invocation payload is made explicit
- result propagation and recording may become confusing if delegated step-local and callee workflow surfaces are not clearly separated

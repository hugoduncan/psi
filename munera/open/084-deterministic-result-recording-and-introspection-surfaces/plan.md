Approach:
- dependency note: this slice should build on invoke execution from task `083` and on the normalized output-surface rules stabilized in task `087`
- treat recording and introspection as part of the runtime contract for invoke steps, not as incidental byproducts
- stay as close as possible to existing workflow attempt/result/history patterns while making invoke-specific data first-class and explicit
- prefer stable logical surfaces (`:data`, `:summary`, yielded value, diagnostics) over exposing only raw registry-return values
- use existing workflow query/debug surfaces where possible, extending them only where invoke-step execution would otherwise remain opaque

Likely steps:
1. inspect current workflow attempt/result/introspection surfaces and identify session-oriented assumptions
2. define the canonical invoke recording shape for attempt-local and accepted-result data
3. decide where effective invoke args, canonical outputs, yielded value, and diagnostics live in runtime state
4. expose or extend workflow introspection/query surfaces to read those values cleanly
5. add focused tests for representative success recording, failure recording, and introspection/query behavior
6. tighten implementation or docs if recording surfaces drift from IR/result-contract intent

Proof target:
- after invoke execution, runtime state and introspection tell one clear consistent story about the operation call, its outputs, its yielded value, and any diagnostics

Risks:
- existing result envelopes may encode session-oriented assumptions too strongly
- over-exposing raw internal details could undermine the canonical logical output surface
- insufficient introspection coverage could leave invoke execution technically correct but operationally opaque

Approach:
- dependency note: this slice should build on invoke execution from task `083` and on the normalized output-surface rules stabilized in task `087`
- treat recording and introspection as part of the runtime contract for invoke steps, not as incidental byproducts
- stay as close as possible to existing workflow attempt/result/history patterns while making invoke-specific data first-class and explicit
- prefer stable logical surfaces (`:data`, `:summary`, yielded value, diagnostics) over exposing only raw registry-return values
- use the existing canonical workflow run/step-run/attempt contract as the public surface; broaden read projections from that source-of-truth rather than creating invoke-only top-level query attrs
- record effective invoke args on the attempt that executed them
- keep invoke yielded-value visibility derived from accepted-result outputs plus normalized `:yields`, with any direct yield projection treated as convenience-only
- center invoke failures on the canonical attempt `:execution-error` surface, with history/read projections derived from it
- converge the shared invoke runtime seam so operation `{:status :error ...}` results remain attempt-failure only and do not imply a stored yielded-value/accepted-result failure surface

Likely steps:
1. inspect current workflow attempt/result/introspection surfaces and identify session-oriented assumptions
2. define the canonical invoke recording shape for attempt-local and accepted-result data
3. record effective invoke args on attempts and canonical invoke outputs on accepted-result/result-envelope surfaces
4. expose or extend workflow introspection/query surfaces to read those values cleanly from workflow run state
5. add focused tests for representative success recording, failure recording, and introspection/query behavior
6. tighten implementation or docs if recording surfaces drift from IR/result-contract intent

Proof target:
- after invoke execution, runtime state and introspection tell one clear consistent story about the operation call, its outputs, its yielded value, and any diagnostics

Risks:
- existing result envelopes may encode session-oriented assumptions too strongly
- over-exposing raw internal details could undermine the canonical logical output surface
- insufficient introspection coverage could leave invoke execution technically correct but operationally opaque

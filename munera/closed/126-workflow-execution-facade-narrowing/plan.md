Approach:
- treat this as a boundary-legibility cleanup after the runtime extraction, not a behavior change
- preserve one obvious higher execution façade while removing lower helper forwarding from it
- rewire callers/tests directly to the lower authoritative workflow-runtime helper owners
- keep the slice small and focused on ownership clarity

Planned outcomes:
1. identify the true façade surface in `psi.agent-session.workflow-execution`
2. remove public forwarding vars for lower helper forwards from that namespace, unless one tiny temporary compatibility seam is explicitly justified
3. update ordinary callers, callback wiring sites, dynamic lookup/backfill sites, and affected tests to call the current lower authoritative workflow-runtime namespaces directly
4. keep `execute-run!` and `resume-and-execute-run!` as the canonical higher execution entrypoints, with only directly adjacent façade-local result-shaping helpers remaining alongside them
5. record the final façade role explicitly in `implementation.md`

Scope boundaries:
- no workflow behavior redesign
- no statechart/runtime ownership changes
- no new adapter protocol/seam introduction
- no step-prep role split except wiring changes needed to stop the forwarding
- no renaming of callback keys; only callback targets may change where needed to stop the forwarding
- no removal of the higher execution façade itself unless implementation proves it is wholly redundant, which is not the expected outcome

Approach:
- trace the current canonical and compatibility-shaped session-result reads across:
  - `workflow_step_prep.clj`
  - `workflow_source_resolution.clj`
  - `workflow_statechart_runtime.clj`
  - any helper already closest to authoritative output interpretation
- choose one authoritative normalization boundary rather than adding another wrapper layer
- move runtime callers to that boundary
- simplify or delete now-redundant translation logic
- tighten focused tests around canonical session outputs and compat preload interaction

Likely steps:
1. map current session-output reads and identify duplicate translation responsibility
2. choose the narrowest authoritative normalization point
3. refactor step prep and statechart/runtime callers to use that point
4. remove redundant local translation logic
5. add/update focused tests for canonical output/yield reads and preload interaction
6. run focused verification and adjust for any exposed drift

Key design constraint:
- prefer centralization over adding a second compatibility seam

Proof target:
- there is one small authoritative session-output interpretation surface, and workflow runtime callers delegate to it rather than restating translation rules locally

Risk:
- touching step prep and statechart runtime together can accidentally perturb legacy/current-authored behavior; keep the slice narrow and test-backed

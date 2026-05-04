Approach:
- choose examples that maximize semantic coverage rather than raw count
- prefer workflows that naturally exercise multiple execution forms and shared data-flow semantics
- keep docs concrete and example-led instead of restating the full grammar in prose again
- use the migrated examples to discover any last-mile ergonomics or naming problems before broader adoption

Likely steps:
1. use `plan-build`, `plan-build-review`, and `gh-bug-triage-modular` as the minimum authoritative migration/example set
2. ensure that set collectively covers invoke/session/delegate plus shared refs/outputs/yields/context semantics where the current task-077 implementation makes them executable and teachable
3. migrate those workflows or add target-grammar example variants
4. update `doc/workflows.md` as the primary example-led guide for the preferred target authoring style
5. include concise current->target mapping notes there or in tightly linked secondary docs only where they reduce author confusion
6. run focused verification so examples are known-good
7. tighten docs or example shapes if the migrated workflows reveal readability issues
8. reconcile the final examples/docs with task `077` and name any still-open implementation boundary explicitly

Proof target:
- future authors can learn the target grammar from concrete, executable examples rather than only from design/reference documents

Risks:
- selecting examples that are too simple may fail to prove the model
- selecting examples that are too broad may turn this into a large migration task
- documentation may drift if examples are not treated as executable proof surfaces

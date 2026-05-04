Approach:
- choose examples that maximize semantic coverage rather than raw count
- prefer workflows that naturally exercise multiple execution forms and shared data-flow semantics
- keep docs concrete and example-led instead of restating the full grammar in prose again
- use the migrated examples to discover any last-mile ergonomics or naming problems before broader adoption

Likely steps:
1. choose representative workflows or workflow slices to migrate to target grammar
2. ensure the chosen set covers invoke/session/delegate and shared refs/outputs/yields where possible
3. migrate those workflows or add target-grammar example variants
4. add or update documentation showing the preferred authoring style with compact explanation
5. include concise mapping notes from current-grammar concepts to target-grammar concepts where that reduces author confusion
6. run focused verification so examples are known-good
7. tighten docs or example shapes if the migrated workflows reveal readability issues

Proof target:
- future authors can learn the target grammar from concrete, executable examples rather than only from design/reference documents

Risks:
- selecting examples that are too simple may fail to prove the model
- selecting examples that are too broad may turn this into a large migration task
- documentation may drift if examples are not treated as executable proof surfaces

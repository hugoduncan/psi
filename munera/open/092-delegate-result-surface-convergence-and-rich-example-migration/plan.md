Approach:
- start from the already documented limitation in `doc/workflows.md` and task `089` rather than re-opening the whole workflow design
- answer the narrow core question first: what exact delegated result surface are later steps allowed to consume canonically?
- prefer converging existing delegate yield/output semantics and runtime refs over introducing a new peer compatibility surface
- drive the work from one realistic checked-in workflow example plus focused tests
- keep this task explicitly in service of unblocking task `090`

Likely steps:
1. inventory current delegate result assumptions across docs, runtime, tests, and example workflows
2. choose one minimal canonical downstream-consumable delegated result model
3. converge runtime/source-resolution/compiler behavior on that boundary
4. add focused tests for downstream delegated-result consumption
5. try direct migration of `gh-bug-triage-modular`
6. if direct migration is not the narrowest honest proof target, replace it with a different realistic delegate-heavy example and record why
7. add explicit execution proof that the checked-in example still runs through the supported runtime path
8. update `doc/workflows.md` to teach the executable target-authored delegate example
9. verify the resulting surface clearly reduces a blocker for `090`

Key design constraints:
- prefer one explicit delegated result model over compatibility drift or a second author-facing workaround path
- prefer one realistic executable proof target over broad conceptual documentation without checked-in runtime proof

Proof target:
- a realistic checked-in target-authored delegate-heavy workflow executes and downstream steps consume delegated results through canonical refs/projections using one clearly documented delegated result model

Risks:
- the existing `gh-bug-triage-modular` flow may depend on callee-contract details that make direct migration more expensive than replacing it with a narrower but still realistic example
- touching delegate result semantics can accidentally broaden into generic workflow-runtime redesign; keep the slice tied to downstream authoring and example proof
- documentation can become misleading if the chosen example proves less than the guide claims; keep the taught surface tightly aligned with executable reality

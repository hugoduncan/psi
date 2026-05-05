2026-05-04
- Task created from the explicit delegate-oriented limitation documented during task `089` example migration and follow-up discussion.
- Rationale: the target grammar is proven for compact session/invoke examples, but delegate-heavy realistic orchestration still lacks a fully checked-in target-authored proof because delegated result/dataflow authoring remains incompletely converged.
- This task exists to turn that named limitation into a concrete implementation slice rather than leaving it as a permanent caveat in `doc/workflows.md`.
- Relationship to adjacent tasks:
  - `089` documented the limitation honestly and established the current example/doc boundary
  - `091` centralized canonical session output normalization but intentionally did not resolve delegated result convergence
  - `090` remains the eventual compatibility-retirement task; this task is intended to remove one of its substantive blockers rather than subsume it

2026-05-04 refinement
- Refined the task to make the core design question explicit: the task must choose one minimal canonical delegated result surface that downstream steps may consume.
- Tightened acceptance so success is not just “some delegate work happened,” but that one authoritative downstream-consumption model is reflected consistently in runtime behavior, focused tests, and docs.
- Clarified the anchor-example decision rule:
  - prefer direct migration of `gh-bug-triage-modular`
  - otherwise replace it only if a different realistic checked-in delegate-heavy example is the narrower and more honest proof target for this slice
- Clarified `doc/workflows.md` as the primary documentation surface so the example-led delegate story lands in one obvious place.

2026-05-04 ambiguity review
- Found one actionable ambiguity: "realistic delegate-heavy example" was still underspecified, so different implementers could satisfy the task with materially different proof strength.
- Resolved by defining a minimum proof shape for the checked-in example:
  - at least one delegate step
  - at least one later step consuming the delegated result through the chosen canonical model
  - explicit `:prompt-string`
  - explicit `:context` or an explicit documented omission decision
  - focused execution proof
- Found a second actionable ambiguity: acceptance required focused tests for delegated-result consumption, but did not say what minimum delegated-consumption proof must exist.
- Resolved by requiring proof for at least one later-step delegated yielded-value read, plus one delegated output read if the chosen canonical model includes step-local delegated outputs.

2026-05-04 consistency review
- Found one actionable design/plan/steps gap: the design required focused execution proof for the checked-in example, but plan/steps did not carry that obligation explicitly as an execution task.
- Resolved by adding an explicit execution-proof step to `plan.md` and `steps.md` so the task surfaces stay aligned on the required runtime proof.

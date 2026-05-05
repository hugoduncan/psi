Goal: converge the remaining delegate result/dataflow boundary so the project has a fully checked-in realistic target-authored workflow example and the compatibility-retirement endgame in task `090` has one fewer substantive blocker.

## Intent

Task `089` proved the new workflow authoring surface in compact session-focused examples and documented the remaining delegate-oriented boundary explicitly rather than hiding it.

This follow-on task turns that documented limitation into a concrete implementation target.

The aim is not to redesign workflows broadly. It is to finish enough of delegated result semantics that a realistic orchestration example can be authored and checked in using the target grammar without forcing downstream consumers back through current-authored compatibility shapes or redesigning callee contracts opportunistically.

## Problem statement

The target grammar now has usable examples for `:type :session` and `:type :invoke`, but the richer delegate-oriented path is still incomplete as an author-facing dataflow surface.

Today:

- `:type :delegate` exists and executes
- delegate boundaries already carry explicit `:target`, `:prompt-string`, and ordered `:context`
- the runtime can propagate delegated terminal result envelopes
- docs can teach the target mapping conceptually

But a substantive limitation remains:

- downstream steps do not yet have a fully converged, ergonomic, and explicitly tested author-facing model for consuming delegated results at the same level of clarity as session/invoke results
- the repository still lacks a fully checked-in realistic orchestration example in target-authored syntax that proves delegate composition end-to-end
- `gh-bug-triage-modular` remains current-authored because migrating it directly would currently require either redesigning downstream callee contracts or leaning on partially converged delegate result assumptions

This leaves an awkward gap in the migration story:

- the target grammar is real for compact session/invoke examples
- the richer delegate-oriented orchestration remains partly aspirational
- task `090` cannot cleanly retire compatibility while this remains unresolved

## Core design question

This task must answer one narrow question clearly:

- **what is the minimum canonical author-facing delegated result surface that later steps may consume?**

That answer should make explicit:

- whether downstream steps are expected to read only the delegated step's yielded value, or both yielded value and selected step-local outputs
- which delegated refs are canonical and teachable in examples
- which surfaces remain debug-only or non-goals for this slice

The task should prefer one explicit answer over a broad menu of partially supported forms.

## Scope

In scope:

- identify the exact remaining delegate result/dataflow limitation at the authoring/runtime boundary
- define the minimum canonical delegated output/yield surface needed for downstream authoring
- choose and document one authoritative downstream-consumption story for delegated steps
- prefer narrowing and clarifying the existing delegated result model over adding a second parallel compatibility surface
- add or update focused runtime/compiler/docs proof for downstream consumption of delegated results
- migrate one realistic checked-in workflow example to target-authored delegate syntax if the converged surface allows it
- if `gh-bug-triage-modular` remains the right anchor, migrate it; otherwise replace it with another realistic checked-in delegate-heavy example that proves the same target surface honestly
- update docs so the example-led guide no longer relies on a conceptual delegate mapping where executable target-authored proof is now available
- record any narrow remaining non-goals explicitly if full parity is still not achieved

Out of scope:

- broad redesign of workflow IR or statechart execution beyond the delegate result seam
- unrelated prompt lifecycle or session architecture work
- compatibility retirement itself, except where this task removes a blocker for `090`
- migrating every workflow in the repository immediately
- making delegate steps expose every possible callee-internal surface if a smaller canonical result model is sufficient

## Authoritative example requirement

The task must end with one checked-in workflow example that is both:

- realistic enough to demonstrate delegate composition and downstream delegate-result consumption
- narrow enough that it does not force unrelated redesign just to satisfy the example

For this task, "realistic" means the checked-in example must have at least:

- at least one `:type :delegate` step
- at least one later step that consumes the delegated step result through the chosen canonical delegated result model
- explicit delegated `:prompt-string`
- explicit delegated `:context` or an explicit documented decision that omitted `:context` is the intended proof case
- focused execution proof in tests or verification notes that the checked-in example still runs through the supported runtime path

Preferred anchor order:

1. migrate `gh-bug-triage-modular` directly if the converged delegate surface supports it cleanly
2. otherwise replace it with a different checked-in delegate-heavy workflow that is still realistic and exercises the minimum proof characteristics above

If the anchor changes, the task must explain why the replacement is a better proof target for this slice.

## Minimum proof surface

The focused proof for this task must make explicit which delegated references are canonical.

At minimum, the task must prove downstream consumption for:

- one delegated yielded-value read used by a later step
- if the chosen canonical model includes delegated step-local outputs in addition to yielded value, one delegated output read used by a later step
- any projection form that the checked-in example depends on

The task does not need to prove every imaginable delegated result surface. It must prove the smallest canonical set that the task chooses to support.

## Documentation surface

The primary user-facing documentation surface for this task is:

- `doc/workflows.md`

Secondary supporting surfaces may include:

- workflow-local prose in `.psi/workflows/*.md`
- focused grammar/reference clarifications where needed

This task should not spread the delegate story diffusely across many docs. The example-led answer should be obvious from `doc/workflows.md`.

## Desired outcome

The project has:

- one obvious delegated result/yield model for downstream authoring
- focused tests proving downstream consumption of delegated results through canonical refs/projections
- at least one realistic checked-in target-authored delegate-heavy workflow example
- docs that teach an executable delegate example rather than only a conceptual target mapping

A future reader should be able to answer by reading one small set of files:

- what a delegated step yields
- what step-local outputs of a delegated step are authorable/consumable downstream
- how later steps read delegated results
- which realistic workflow proves that model end-to-end

## Acceptance

- the remaining delegate result/dataflow boundary is made explicit and converged enough that downstream authoring does not depend on vague or compatibility-shaped assumptions
- one authoritative downstream-consumption model for delegated results is chosen and reflected consistently in runtime behavior, focused tests, and docs
- focused tests prove the minimum chosen canonical delegated result surface, including at least one later-step delegated-result consumption path
- the repository contains a realistic checked-in target-authored delegate-heavy workflow example, or the task explicitly records why the chosen anchor was replaced
- `doc/workflows.md` teaches a delegate example that is executable in checked-in target-authored form rather than only conceptually mapped
- the resulting implementation reduces a real blocker to task `090` rather than shifting compatibility burden sideways

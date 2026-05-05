Goal: define and implement the broader multi-phase delegated-workflow contract/dataflow model so realistic orchestration workflows can pass structured handoff data across workflow boundaries without falling back to compatibility-era current-authored shapes or text-only contracts.

## Intent

Task `092` deliberately solved the narrowest useful delegated-result seam:

- delegated steps now have one canonical downstream-consumable yielded surface
- later steps may read delegated text through `{:from {:step "..." :yield :text}}`
- the repository now has one executable delegate-heavy target-authored example

That was the right minimal cut, but it was never intended to solve the broader orchestration problem.

This task exists to solve that broader problem explicitly.

The aim is to make delegated workflows viable as reusable, realistic, multi-phase building blocks whose callers can consume both:

- a human-facing delegated result summary
- a structured machine-facing handoff contract

without requiring callers to parse freeform markdown, reach into callee internals opportunistically, or remain on the current-authored compatibility path.

## Problem statement

The current target grammar and runtime are now good at three things:

- explicit delegated asks via `:prompt-string`
- explicit forwarded context via ordered `:context`
- canonical downstream delegate text consumption via `:yield :text`

But realistic multi-phase workflows need more than yielded text.

Examples such as `gh-bug-triage-modular` need durable, structured values like:

- issue number
- issue URL
- worktree path
- branch name
- reproduction status
- minimum unblocking information needed
- post-classification result type
- comment URL / branch URL

Today, that broader inter-workflow contract is still missing.

Without it, downstream authoring has three bad options:

1. treat delegated text as the only contract and parse markdown heuristically
2. keep using current-authored compatibility shapes whose accepted-result/text wiring already implies richer hidden assumptions
3. overexpose arbitrary callee-local outputs and diagnostics without a clear contract boundary

None of these is a good end-state.

This is the substantive reason `gh-bug-triage-modular` was not migrated in task `092`:

- the narrow delegated yielded-text seam was enough for plan/build/review style composition
- it was not enough for realistic multi-phase orchestration with stable handoff data contracts

The missing abstraction is not "more delegate text". It is:

- a canonical workflow-level exported handoff contract
- a canonical way for delegate steps to surface that exported contract to downstream callers
- a clear distinction between text-for-humans and data-for-orchestration

## Core design question

This task must answer one architectural question clearly:

- **what is the canonical exported contract of a delegated workflow, and how does a caller consume it downstream?**

That answer must make explicit:

- whether reusable workflows export a declared structured handoff surface
- whether delegate callers consume that surface through delegate-step outputs, yield fields, or some new contract layer
- what the standard contract names are for the first cut
- how yielded text and structured handoff data relate without becoming redundant or conflicting
- which surfaces remain non-contractual debug/runtime details

The task should prefer one explicit dual-plane model over a broad menu of ad hoc delegated surfaces.

## First-cut decision obligations

To avoid implementers satisfying the task with materially different contract shapes, this task must explicitly decide all of the following in the first cut:

1. **workflow-level declaration shape**
   - where a reusable workflow declares its exported terminal contract
   - whether that declaration is workflow-level metadata, terminal-step metadata, or another explicit authored surface

2. **caller-side reference shape**
   - the exact canonical downstream ref form for reading structured delegated handoff data
   - whether that is an `:output` ref, a `:yield` ref, or another explicit ref form

3. **first-cut standard contract keys**
   - whether `:handoff` is the only newly canonical structured export key in this slice
   - whether `:transcript` and `:result` are part of the declared caller contract or remain separate/non-primary surfaces

4. **fallback behavior**
   - whether delegated workflows without an explicit structured export declaration are invalid for structured downstream reads
   - or whether there is a narrow compatibility/defaulting rule for the first cut

The task should prefer one explicit answer for each item over leaving multiple equally plausible implementation paths.

## Minimum concept set

This task should stay centered on the smallest set of concepts that can solve the broader orchestration problem:

1. **ask plane**
   - the delegated workflow's immediate prompt/ask
   - still represented by rendered `:prompt-string`

2. **handoff plane**
   - structured exported data intended for downstream orchestration
   - stable enough to be referenced by later steps

3. **terminal export contract**
   - the declared workflow-level surface a delegated workflow exposes to callers at completion

4. **delegate import surface**
   - the surface by which the calling workflow reads the callee's exported contract

5. **context as support, not contract**
   - transcript tails and other projected context remain useful, but they are not the primary machine-facing inter-workflow contract

The task should avoid introducing more concepts than these unless required by proof.

## Desired model

The preferred design direction for this task is:

- delegated workflows continue to yield text canonically through `:yield :text`
- delegated workflows may also export one structured handoff surface intended for downstream orchestration
- callers consume that structured handoff through one canonical delegate-step output surface rather than parsing freeform yielded text
- transcript projection remains available as optional support context, not the main contract

For the first cut, the task should strongly prefer a single standard structured export key such as:

- `:handoff`

rather than many unrelated export names.

The first cut should also decide whether additional conventional export keys like `:transcript` or `:result` are part of the same contract surface, or whether only `:handoff` is newly canonical for delegated downstream authoring.

## Scope

In scope:

- define the canonical workflow-level exported contract for reusable delegated workflows
- define the canonical delegate-caller surface for downstream consumption of those exports
- keep yielded text as a first-class delegated result surface while separating it from structured handoff data
- prefer one standard structured export key for the first cut if that is sufficient
- identify the narrowest runtime/compiler/IR changes needed to support that contract cleanly
- add focused proof that downstream delegated callers can consume structured handoff data canonically
- migrate one realistic multi-phase delegate-heavy workflow that actually needs structured handoff data
- make `gh-bug-triage-modular` the preferred migration anchor if the resulting contract supports it honestly and narrowly
- update docs so the example-led guide explains both delegated yielded text and delegated structured handoffs
- record any explicit remaining non-goals after the first cut

Out of scope:

- broad redesign of all workflow runtime state or replay architecture
- arbitrary exposure of every callee-internal accepted-result or diagnostics field as a caller contract
- replacing transcript projection with a wholly new context system
- compatibility retirement itself, except insofar as this task removes a substantive blocker for `090`
- migrating every delegate-using workflow in the repository immediately

## Preferred solution shape

The preferred solution shape is a **dual-plane delegated contract**:

- **yielded text plane**
  - canonical human-facing delegated summary
  - consumed through `{:from {:step "..." :yield :text}}`

- **structured handoff plane**
  - canonical machine-facing exported contract
  - consumed through one explicit delegate-step output path, ideally under `:handoff`

This means the task should not force a choice between text-only or data-only delegation.

Instead it should make them complementary and explicit.

## Anchor workflow requirement

This task should end with one realistic checked-in target-authored workflow that proves structured delegated handoffs in a way task `092` intentionally did not.

Preferred anchor order:

1. migrate `gh-bug-triage-modular`
2. if and only if that proves materially broader than the intended contract slice, replace it with another checked-in multi-phase delegate-heavy workflow that still genuinely requires structured handoff data

If the anchor changes away from `gh-bug-triage-modular`, the task must explain why the replacement is a narrower honest proof target.

For this task, a valid anchor workflow must include at least:

- at least one `:type :delegate` step that exports structured handoff data canonically
- at least one later step consuming that structured handoff data canonically
- at least one use of delegated yielded text as distinct from structured handoff data, unless the task explicitly justifies why the chosen example cannot prove both
- explicit delegated `:prompt-string`
- explicit delegated `:context` when contextual support material is part of the example
- focused automated proof through the authoritative canonical workflow execution path intended to remain after compatibility retirement

For `gh-bug-triage-modular`, the minimum honest proof strength is:

- at least one downstream step consuming a prior delegated `:handoff` field canonically
- at least one downstream step consuming prior delegated yielded text canonically
- at least one contextual transcript projection carried as support context rather than primary machine contract
- at least one child workflow in the chain exposing a declared structured terminal handoff rather than relying only on markdown output

## Contract-shaping constraints

The task should preserve these architectural constraints unless proof shows otherwise:

- callers should consume declared workflow exports, not arbitrary callee internals
- yielded text should remain the simplest default delegated result surface
- structured handoff should be explicit and named, not implicit in markdown conventions
- transcript output should remain optional support context rather than becoming the sole inter-workflow contract
- the first cut should prefer standardization over unlimited flexibility

## Documentation surface

Primary documentation surface:

- `doc/workflows.md`

Likely supporting surfaces:

- `doc/workflow-grammar.md`
- `doc/workflow-grammar-concepts.md`
- workflow-local prose in `.psi/workflows/*.md`

The docs should teach one obvious model for realistic multi-phase delegation:

- delegated text for human-readable chaining
- delegated structured handoff for stable downstream orchestration

## Desired outcome

The project has:

- one explicit model for reusable delegated workflow terminal contracts
- one explicit model for downstream consumption of delegated structured handoff data
- preserved canonical delegated yielded-text semantics from task `092`
- at least one realistic checked-in target-authored multi-phase delegate-heavy example that depends on structured handoff data honestly
- focused tests proving the chosen contract surface through canonical execution
- one fewer major blocker to retiring the current-authored compatibility grammar in task `090`

A future reader should be able to answer by reading a small set of files:

- what a delegated workflow exports structurally
- what part of that export is canonical downstream contract
- how a caller reads delegated text versus delegated handoff data
- which realistic workflow proves the model end-to-end

## Acceptance

- the broader delegated multi-phase contract/dataflow problem is made explicit and solved with one authoritative canonical model rather than text parsing or compatibility-era hidden assumptions
- delegated yielded text remains supported canonically and is clearly distinguished from structured delegated handoff data
- the first-cut structured delegated contract surface is explicitly named and documented
- the task records one explicit answer for workflow-level declaration shape, caller-side ref shape, standard contract keys, and fallback behavior for undeclared structured exports
- focused tests prove downstream canonical consumption of structured delegated handoff data
- focused tests also prove that delegated yielded text and structured delegated handoff data remain distinct surfaces rather than aliases of the same downstream contract
- at least one realistic checked-in target-authored multi-phase delegate-heavy workflow proves the structured handoff model through the authoritative canonical workflow execution path intended to remain after compatibility retirement
- documentation teaches the dual-plane delegated model clearly enough that future workflow authors do not need to infer it from compatibility examples
- the resulting implementation materially reduces a real remaining blocker for task `090`

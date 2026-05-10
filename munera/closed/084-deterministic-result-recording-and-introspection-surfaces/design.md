Goal: define and implement coherent result-recording and introspection surfaces for deterministic workflow invoke steps.

## Intent

Task `083` makes deterministic invoke steps executable. This task ensures their execution becomes visible, inspectable, and referenceable in a way that is structurally coherent with the rest of the workflow runtime.

Boundary note:

- task `087` owns which step-local output keys exist and how `:output` refs validate against them
- task `088` owns how source refs and projections resolve values from workflow/prior-step data
- this task owns how invoke execution artifacts are recorded in runtime state and exposed through introspection/query surfaces after those semantics are already defined

The focus is on the runtime surfaces that let developers, workflow authors, and debugging tools understand what happened during deterministic execution:

- attempt records
- accepted/terminal results
- step-local outputs
- yielded values
- diagnostics and failure details
- introspection/query surfaces
- history/event recording where applicable

## Problem statement

A deterministic invoke step is only fully integrated once its runtime artifacts are consistently recorded and exposed.

Without this slice:

- invoke-step execution may work operationally but remain hard to inspect or debug
- downstream reference semantics may drift from what attempt/result surfaces actually store
- introspection could remain biased toward session-oriented execution
- deterministic steps might expose ad hoc result shapes instead of stable canonical surfaces

## Scope

In scope:

- define the runtime recording shape for invoke attempts and accepted results
- record already-defined canonical invoke outputs (`:data`, `:summary`, optional `:result`) in runtime state coherently
- define how yielded values derived from invoke steps appear in runtime state and introspection
- expose deterministic result/diagnostic data through existing or extended workflow introspection surfaces
- add focused tests proving recording and query behavior for representative invoke success/failure cases
- keep recording/introspection surfaces aligned with `doc/workflow-ir.md` and task `077`

Out of scope:

- redesigning all workflow introspection from scratch
- broad UI work beyond what is needed to expose coherent canonical surfaces
- adding many real operations or large end-user documentation expansions

## Desired outcome

After an invoke step runs, the runtime exposes one coherent story for:

- what arguments were effectively invoked
- what result was returned
- what outputs are available for downstream references
- what yielded value the step produced
- what failure/diagnostic information exists when the step does not succeed

## Canonical contract decisions

### Public introspection/query contract

Task `084` extends the existing canonical workflow runtime surfaces:

- workflow run
- step run
- step attempt
- accepted result envelope
- workflow history

It does **not** introduce a parallel invoke-only query family.

The canonical public contract remains the workflow run state model already exposed through workflow read/list surfaces. If existing read projections are too narrow for invoke debugging, they should be broadened from that same canonical run/step-run/attempt data rather than publishing new invoke-specific top-level attrs.

### Effective invoke args location

Effective invoke args belong to the attempt-local execution record.

Reasoning:

- they are materialized per execution attempt
- retries may resolve different values
- they are part of "what this attempt actually invoked", not merely historical narration

Therefore the canonical recording target is the step attempt surface, with history entries allowed only as supplementary breadcrumbs. Accepted-result diagnostics must not become the primary storage location for effective args.

### Yielded-value visibility

Invoke yielded-value visibility remains derived from the canonical accepted-result outputs plus the step's normalized `:yields` definition.

Task `084` does not require a second independently recorded invoke-yield field when the runtime can deterministically derive yield from:

- the accepted result envelope's canonical `:outputs`
- the step definition's canonical `:yields`

If a projection/read surface exposes the yielded value directly for convenience, that projection is derived from those canonical sources and must not become a divergent source of truth.

### Failure-side contract

When an invoke operation returns `{:status :error ...}`, the canonical inspectable runtime result is attempt-local failure data, not a synthetic accepted result.

The primary failure surface is:

- step attempt `:execution-error` for the canonical structured failure record

Allowed accompanying surfaces:

- attempt status/finished timestamps
- workflow history breadcrumbs
- derived read/introspection projections built from the same attempt failure record

This task should not introduce an invoke-only diagnostics store separate from the attempt record unless an existing shared workflow failure surface requires it.

### Failure/yield convergence rule

Task `084` converges invoke-step failure onto attempt-local execution failure only.

That means:

- invoke operations returning `{:status :error ...}` do **not** produce an accepted-result envelope
- invoke operations returning `{:status :error ...}` do **not** produce a separately recorded yielded value
- runtime helpers may use a small internal tagged result to distinguish success from failure before recording, but that helper shape is not itself a public runtime/introspection surface
- canonical inspectable failure data lives on the latest attempt `:execution-error`
- any introspection convenience about invoke failure must be derived from that attempt record, not from a second stored failure/yield surface

This also clarifies the relationship to IR `{:type :error ...}` yielded values:

- IR error yields remain part of the shared normalized yield union because other step forms or future flows may use them
- task `084` does not require invoke-step runtime failures to materialize that IR error yield into stored accepted-result/blocked data
- `workflow_ir.clj` yield helpers should therefore not imply that invoke-step runtime failure is recorded via accepted-result `[:blocked ...]`

## Acceptance

- invoke attempt/result recording is explicit and coherent in runtime state
- canonical invoke outputs are visible through accepted-result surfaces
- effective invoke args are inspectable on the canonical attempt surface
- yielded-value visibility is coherent with accepted-result outputs plus normalized `:yields`
- diagnostics/failure details are preserved in a structured, inspectable way, with invoke failure centered on canonical attempt failure recording
- introspection/query surfaces expose invoke-step result data consistently enough for debugging and downstream reasoning
- focused tests prove representative success and failure recording/query cases
- the implemented surfaces align with task `077`, task `083`, and `doc/workflow-ir.md`

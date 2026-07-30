# Control Flow

Control flow describes how execution proceeds from one step to another.

The control-flow surface is made of:

- `:name`
- `:judge`
- `:on`
- `:goto`
- `:max-iterations`

A step produces a result. Routing decisions are made from that result through a judge sub-step.

A judge is itself a routing sub-step. The grammar allows at least two judge forms:

- LLM-backed judge
- deterministic invoke-style judge

The purpose of the judge is to normalize a step result into a routing outcome consumed by `:on`.

## Judge Outcome Contract

All judge forms normalize to one logical outcome value.

That normalized outcome value:

- may be a string or keyword
- is matched against the keys of the parent step's `:on` map
- is case-sensitive for strings
- does not auto-coerce between strings and keywords

A judge result that does not normalize to a declared `:on` key is a workflow execution error in the first cut.

Control flow is orthogonal to step execution form, so invoke, session, and delegate steps may all participate in routing.

## Iteration Bounds

`:max-iterations` appears in two places in the first cut:

- as a step-level loop bound on the parent step
- as an optional transition-local bound inside an `:on` routing directive

The transition-local form uses the literal key `:max-iterations`; the earlier `:max-iterations?` spelling in the docs was only an imprecise optionality notation and not a distinct authored field name.

A transition-local `:max-iterations` bound may carry an optional companion key `:on-max-iterations`, valued like `:goto` (`:next | :previous | :done | step-name`). It names an author-chosen target the judged loop routes to when it exhausts `:max-iterations`, instead of hard-failing with `:reason :iteration-exhausted`. `:on-max-iterations` is only valid alongside transition-local `:max-iterations`; supplying it without `:max-iterations` is rejected.

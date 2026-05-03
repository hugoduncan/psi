Approach:
- keep this task design-first and avoid committing to runtime syntax before comparing alternatives
- use the existing workflow/session-first authoring direction as the baseline, then add deterministic invocation as a first-class parallel step mode rather than a special-case hack
- compare likely invocation shapes (`:deterministic`, `:invoke`, `:executor`/`:kind`) against the current workflow style and statechart-like clarity requirements
- prefer explicit map-shaped argument passing and explicit source/path projection over implicit `$INPUT`-only conventions
- define one canonical result model for deterministic steps before implementation slicing
- keep the GitHub label-search use case as the anchor example for judging ergonomics

Questions to resolve:
- exact step syntax for deterministic invocation
- exact operation naming/registration surface for extension implementers
- exact argument value projection syntax and how it composes with existing `:session :input` / source-selection ideas
- exact result-reference syntax for downstream deterministic and LLM-backed steps
- whether the deterministic contract should look more like a registry of operations or reuse an existing extension mutation/event boundary

Likely output of this umbrella:
- refined `design.md`
- follow-on implementation child tasks with clear boundaries and acceptance criteria

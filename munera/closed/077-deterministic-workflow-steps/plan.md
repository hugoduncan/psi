Approach:
- keep this task design-first, but make the migration path explicit rather than treating the target grammar as a direct runtime rewrite
- treat `doc/workflow-grammar-current.md` as the current authored surface and `doc/workflow-grammar.md` + `doc/workflow-grammar-concepts.md` as the target authored surface
- introduce `doc/workflow-ir.md` as the canonical normalized runtime model and `doc/workflow-grammar-migration.md` as the convergence plan
- use the normalized IR as the boundary between authored syntax and runtime execution
- keep the existing workflow/session-first direction as the compatibility baseline, then add deterministic invoke and explicit delegation as first-class parallel execution forms
- compare likely invocation shapes (`:deterministic`, `:invoke`, `:executor`/`:kind`) against the current workflow style and statechart-like clarity requirements, but land on one preferred target surface
- prefer explicit map-shaped argument passing and explicit source/path projection over implicit `$INPUT`-only conventions
- define one canonical result model and yielded-value model before implementation slicing
- keep the GitHub label-search use case as the anchor example for judging ergonomics
- push compatibility concerns into authored-grammar compilers rather than runtime execution

Questions to resolve:
- exact normalized IR shape to preserve current semantics while staying close to the target grammar
- exact compiler mapping from current `:executor` / `:prompt-template` / `:input-bindings` / `:session-preload` / `:session-overrides` into IR
- exact operation naming/registration surface for deterministic invoke implementations
- exact result/output/yield recording shape in runtime attempts and observability surfaces
- exact materialization details for source/template contributions in child-session conversation assembly
- exact workflow-input widening needed so delegated workflows can receive rendered string input cleanly
- whether any temporary IR compatibility metadata is needed for current preload/binding semantics

Likely output of this umbrella:
- refined `design.md`
- `doc/workflow-grammar.md`, `doc/workflow-grammar-concepts.md`, and `doc/workflow-grammar-current.md`
- `doc/workflow-ir.md`
- `doc/workflow-grammar-migration.md`
- follow-on implementation child tasks with clear boundaries and acceptance criteria oriented around IR-first migration

# Steps

- [x] Audit the workflow compiler/IR validation path that rejects self references.
- [x] Allow self-loop control edges for `:on` / `:goto` transitions.
- [x] Preserve rejection of self/future data dependencies across every canonical `step-source-refs` data-flow surface: step `:invoke` args, step `:session` contributions/template vars, delegate target/prompt/context refs, and judge-owned `:llm`/`:invoke` refs.
- [x] Add focused compiler tests proving the allowed vs forbidden distinction across representative canonical control-edge vs data-flow cases, while keeping the current shared non-prior data-ref error class/message intentional and distinct from other transition/IR validation failures.
- [x] Simplify `.psi/workflows/implement-task.md` back to the intended self-looping judged `implement-pass` form.
- [x] Reload/verify the workflow compiles successfully.
- [x] Add focused workflow IR/compiler tests proving self/future non-prior-step rejection for delegate target source-spec refs and delegate prompt `:map` refs, not just session-template refs.
- [x] Add focused workflow IR/compiler tests proving self/future non-prior-step rejection for delegate context refs and judge-owned source refs (`:llm` session contributions and `:invoke` args).

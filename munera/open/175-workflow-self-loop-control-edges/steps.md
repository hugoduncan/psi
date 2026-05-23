# Steps

- [ ] Audit the workflow compiler/IR validation path that rejects self references.
- [ ] Allow self-loop control edges for `:on` / `:goto` transitions.
- [ ] Preserve rejection of self/future data dependencies across every canonical `step-source-refs` data-flow surface: step `:invoke` args, step `:session` contributions/template vars, delegate target/prompt/context refs, and judge-owned `:llm`/`:invoke` refs.
- [ ] Add focused compiler tests proving the allowed vs forbidden distinction across representative canonical control-edge vs data-flow cases.
- [ ] Simplify `.psi/workflows/implement-task.md` back to the intended self-looping judged `implement-pass` form.
- [ ] Reload/verify the workflow compiles successfully.

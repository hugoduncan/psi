# Steps

- [ ] Audit the workflow compiler/IR validation path that rejects self references.
- [ ] Allow self-loop control edges for `:on` / `:goto` transitions.
- [ ] Preserve rejection of self/future data dependencies in contributions/vars/fields and similar data-flow positions.
- [ ] Add focused compiler tests proving the allowed vs forbidden distinction.
- [ ] Simplify `.psi/workflows/implement-task.md` back to the intended self-looping judged `implement-pass` form.
- [ ] Reload/verify the workflow compiles successfully.

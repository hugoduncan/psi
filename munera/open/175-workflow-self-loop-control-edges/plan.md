# Plan

1. Locate the compiler/IR validation rule that currently rejects self references.
2. Split validation into:
   - control-edge validation
   - data-dependency validation
3. Permit self-loop control edges in transitions.
4. Keep self/future data dependencies invalid.
5. Add focused compiler tests covering:
   - allowed self `:goto`
   - rejected self yield sourcing
   - rejected forward yield sourcing
6. Simplify `implement-task` back to a self-looping judged `implement-pass` once compiler support exists.
7. Verify the workflow reloads successfully.

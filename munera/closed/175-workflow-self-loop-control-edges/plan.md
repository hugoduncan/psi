# Plan

1. Locate the compiler/IR validation rule that currently rejects self references.
2. Split validation into:
   - control-edge validation
   - data-dependency validation
3. Permit self-loop control edges in transitions.
4. Keep self/future data dependencies invalid across every canonical `step-source-refs` surface gathered by IR validation: step `:invoke` args, step `:session` contributions/template vars, delegate target/prompt/context refs, and judge-owned `:llm`/`:invoke` refs.
5. Add focused compiler tests covering:
   - allowed self `:goto`
   - rejected self yield sourcing from representative canonical data-flow surfaces
   - rejected forward yield sourcing from representative canonical data-flow surfaces
   - preserved semantic error separation between non-prior data refs and other transition/IR validation failures, without requiring separate self-vs-forward non-prior error classes/messages
6. Simplify `implement-task` back to a self-looping judged `implement-pass` once compiler support exists.
7. Verify the workflow reloads successfully.

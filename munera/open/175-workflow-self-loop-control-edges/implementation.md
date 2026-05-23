# Implementation

Task created to allow workflow self-loop control edges while keeping invalid self/future data dependencies forbidden.

Motivating case:

- `implement-task` should be expressible as a single judged `implement-pass` step that loops to itself on `REPEAT`
- current compiler behavior rejects that because it treats self control references and self data dependencies as the same class of error

Target design:

- self-loop `:goto` is valid control flow
- self-sourced yields remain invalid data flow
- forward-sourced yields remain invalid data flow

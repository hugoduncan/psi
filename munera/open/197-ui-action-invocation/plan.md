# Implementation plan

1. Re-read task 190 descriptor/request contract.
2. Add request/result schemas and validation helpers.
3. Add constrained extension/core submission helper that emits `:psi.ui/request-action` outside generic manifest `allowed-events`.
4. Wire active UI adapter handling for Emacs make-visible.
5. Add focused request validation, result-shape, and Emacs handling tests.
6. Update extension-authoring docs to mark invocation as implemented.

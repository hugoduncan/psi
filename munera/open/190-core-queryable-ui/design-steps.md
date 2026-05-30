# Design follow-up steps

- [x] Define the concrete adapter-to-core capability provider contract for deriving `:psi.ui/...` capability/action data on demand, including where it is installed in runtime context, what function/data shape it returns, and how missing/errored providers map to headless/unavailable EQL results.
- [x] Specify the authoritative invocation route for action descriptors when generic invocation is optional: whether extensions invoke a Pathom mutation, dispatch event, RPC/frontend-action request, or only present the descriptor in this slice, and how `:emacs-command` invocation data reaches Emacs without extension frontend coupling.
- [x] Align the make-visible capability vocabulary in `design.md` so all examples and extension-usage guidance use the single resolved namespaced keyword `:psi.ui.capability/make-visible` (or explicitly mark any shorthand as non-normative), avoiding a second `:ui.capability/make-visible` contract.

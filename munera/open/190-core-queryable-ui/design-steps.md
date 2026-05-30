# Design follow-up steps

- [ ] Define the concrete adapter-to-core capability provider contract for deriving `:psi.ui/...` capability/action data on demand, including where it is installed in runtime context, what function/data shape it returns, and how missing/errored providers map to headless/unavailable EQL results.
- [ ] Specify the authoritative invocation route for action descriptors when generic invocation is optional: whether extensions invoke a Pathom mutation, dispatch event, RPC/frontend-action request, or only present the descriptor in this slice, and how `:emacs-command` invocation data reaches Emacs without extension frontend coupling.

# Implementation Notes — 137

## 2026-05-10 — initial analysis

Static analysis of the reload-code path confirms:

- `context/create-context*` stores `:all-mutations mutations` as a frozen vector.
- Four sites read `(:all-mutations ctx)`: `refresh-query-runtime!`, `tool_plan.clj:50`,
  `runtime_eql.clj:50`, `runtime_eql.clj:74`.
- `refresh-query-runtime!` creates a throwaway isolated `qctx` — effect is nil.
- The `:mutation-registration-refresh` step is a hardcoded `{:status :ok}` no-op.
- `query/defmutation` macro calls `register-mutation!` (global) at load time; after
  reload new mutations ARE in the global registry — but per-request isolated qctx
  instances (used by extension EQL) only receive what's in `(:all-mutations ctx)`.

Chosen fix: atom-wrapped `:all-mutations-atom` in ctx + `all-mutations-in` helper +
post-reload reset via var resolution of `psi.agent-session.mutations/all-mutations`.

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

## 2026-05-10 — circular dependency discovery

Adding `psi.agent-session.context` to `runtime_eql.clj` caused a cycle:
`context → ext-rt → runtime-fns → runtime-eql → context`.

Resolved by using a private `ctx-all-mutations` helper in each caller file
(`psi_tool.clj`, `tool_plan.clj`, `runtime_eql.clj`) instead of importing context.

`all-mutations-in` in context.clj is available to test files and to `core.clj`
(which already imports context) without cycle risk.

## 2026-05-10 — throwaway qctx in refresh-query-runtime!

Discovered that `refresh-query-runtime!` created a throwaway isolated qctx that
was immediately discarded — the registration had zero effect on the live runtime.
Resolvers are derived fresh per-request via `session-resolver-surface`, so the
only real work needed was the mutation atom reset (now in `refresh-all-mutations!`).

Rewrote `refresh-query-runtime!` to report the per-request resolution accurately
and removed the query import from `psi_tool.clj` (no longer needed).

## 2026-05-10 — pre-existing paren bug in test file

Second existing test `reload-code-preserves-built-in-workflow-loaded-definition-state-test`
was missing its closing `)` for `deftest`. Fixed as part of this work.

# Task 244 — Migrate footer-query `session/query-in` `with-redefs` onto the resolver/query seam

## Goal

Remove the standing `¬mock/¬stub` violation in
`rpc-prompt-footer-updated-tolerates-keyword-sentinel-values-test`
(`components/rpc/test/psi/rpc_prompt_test.clj`, ~line 91): it `with-redefs`
`session/query-in` — a **logic-boundary** (query/resolver) redefinition, the
same class of violation task 243 removed for `turn-runtime/execute-live-turn!`.

Migrate the test onto the injectable Pathom resolver/query seam: inject a stub
footer-query **source** (or otherwise stub the query result via a supported
injection point) rather than redefining `session/query-in` globally.

## Why

- `λtest.[∀d∈deps(test).(infrastructure(d) → nullable(d)) ∧ (logic(d) → real(d))]`.
  `session/query-in` is a logic boundary; `with-redefs` of it is exactly the
  redefinition class task 243 eliminated for the retry-footer tests.
- Task 243 correctly excluded this site from its frozen scope (Slice 4 noted
  it). The task-243 implementation-review pass flagged it as untracked by any
  open task; this task tracks it so the standing violation is not silently
  carried.

## Context

- Test intent: prompt completion must not fail when the footer query returns
  keyword sentinel values (e.g. `:pathom/unknown`). The stub returns a
  `footer-data` map with sentinel values for several
  `:psi.agent-session/*` / `:psi.ui/statuses` keys; the assertions confirm the
  prompt response is accepted, the assistant message arrives, footer events are
  emitted, and no `runtime/failed` error surfaces.
- Current mechanism: `with-redefs [session/query-in (fn ...)]` intercepts the
  3 arities used by the footer-query path, returning `footer-data` when the
  query equals `@#'rpc.events/footer-query`, else delegating to the original.
- The footer query is `rpc.events/footer-query`; the resolver/source that
  feeds it is the migration target — a stub source registered on the Pathom
  env / ctx (the seam authority) rather than a global var redefinition.

## Constraints

- Behaviour-preserving: keep the same assertions (accepted response, assistant
  message present, footer events emitted, no `runtime/failed`) and the same
  keyword-sentinel `footer-data` shape.
- No shims/adapters: prefer an explicit injection point already supported by
  the resolver/query seam over introducing a compatibility layer.
- One-way / dispatch discipline: reads go through resolvers; the stub must
  enter through a resolver/source injection, not a var redefinition.

## Open questions

- Does the query seam expose an injection point for a per-ctx stub footer
  source without a new shim? (Investigate `session/query-in`, the Pathom env
  construction in the rpc/agent-session ctx, and how `create-session-context`
  builds the query environment.) If no clean seam exists, this task's design
  should either identify the minimal seam addition or record an explicit
  deferral rationale.

## Acceptance criteria

- `rpc-prompt-footer-updated-tolerates-keyword-sentinel-values-test` no longer
  `with-redefs` `session/query-in` (or any logic boundary).
- The test still asserts the same accepted-response / assistant-message /
  footer-events / no-`runtime/failed` behaviour with the keyword-sentinel
  footer-data.
- `grep with-redefs` in `components/rpc/test/psi/rpc_prompt_test.clj` shows no
  logic-boundary redefinition remains.
- `bb test --focus psi.rpc-prompt-test` green; lint clean.

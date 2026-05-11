# Plan — 139 active session id root attr

## Approach

Single resolver, single input, zero side effects. Implementation is minimal:
one `pco/defresolver` appended to `resolvers/session.clj`, plus unit tests
in `resolvers_test.clj`.

## Steps

1. Add `active-session-id-resolver` in `resolvers/session.clj`
   - `::pco/input [:psi.agent-session/session-id]`
   - `::pco/output [:psi.agent-session/active-session-id]`
   - body: return `{:psi.agent-session/active-session-id session-id}` (nil passthrough)
   - append to `resolvers` def

2. Add unit tests in `resolvers_test.clj`
   - returns session-id when present and non-nil
   - returns nil when present-but-nil
   - queryable from root without entity seeding (root query via `session/query-in`)

3. `bb test` green, lint clean

## Risks

None. The resolver has no side effects and no new dependencies.
The `root-queryable-attrs-contract-test` in `graph_surface_test.clj`
automatically covers the new attr once it is registered.

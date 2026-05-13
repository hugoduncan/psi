Slice 1 — Mutation dispatch seam and contract alignment
- [ ] Review the current `psi-tool` action dispatch and identify the concrete production-owned canonical hook for registered mutation execution
- [x] Define and record the exact request/result/error contract for `action: "mutate"`
- [x] Record review-driven contract clarifications in `design.md` and `implementation.md`
- [ ] Record the chosen mutation-execution helper/path and routing constraints in `implementation.md`
- [ ] Record the exact validation boundary for `mutation` string parsing, registry lookup, and `:validate` vs `:mutation` phase assignment in code-facing notes

Slice 2 — `psi-tool(action: "mutate")`
- [ ] Add `"mutate"` to the `psi-tool` action contract
- [ ] Implement `psi-tool(action: "mutate", ...)` using the canonical mutation surface rather than raw eval or command parsing
- [ ] Ensure unknown mutation names, malformed params, and unsupported `entity` fail explicitly with structured validation errors
- [ ] Validate `mutation` as a string that parses as a qualified symbol before registry lookup
- [ ] Normalize only top-level string-keyed map/object params into the canonical mutation param shape if needed
- [ ] Preserve values and unknown keys rather than performing broader semantic rewriting in v1
- [ ] Ensure mutate reuses canonical capability/permission/validation enforcement rather than bypassing it
- [ ] Shape successful mutation results under `:psi-tool/result` with `:psi-tool/error` absent, including canonical `nil` results
- [ ] Shape validation and mutation-execution failures under structured `:psi-tool/error` with preserved `ex-data` where available and `:psi-tool/result` absent

Slice 3 — Active session introspection prerequisite
- [x] Confirm task 139 landed `:psi.agent-session/active-session-id` in `resolvers/session.clj`
- [x] Confirm the source of truth is the invoking query context's bound `:psi.agent-session/session-id`, not adapter-local UI focus or session ordering
- [x] Confirm focused proof already exists for non-nil, present-but-nil, root-queryable, and ordering-independent semantics
- [ ] Add only composed-workflow proof in this task where `:psi.agent-session/active-session-id` participates in the query → select → mutate flow

Slice 4 — Compact session summary introspection
- [x] Identify and record the chosen owner/resolver path for `:psi.agent-session/context-session-summaries`
- [ ] Add and wire a compact `:psi.agent-session/context-session-summaries` root attr
- [x] Prefer reusing the existing canonical session-info source/projection trimmed to the allowed fields
- [ ] Extend the shared live context-session projection to expose canonical `:psi.session-info/updated` from `:updated-at` so the compact summary attr can satisfy the exact v1 field contract without diverging from the existing context inventory source
- [ ] Keep the compact session-summary surface free of heavy transcript/message payloads, including `:psi.session-info/first-message`, `:psi.session-info/all-messages-text`, and message-history joins
- [ ] Expose exactly the allowed summary fields in v1 and no additional fields
- [ ] Preserve the canonical ordering of the existing context session inventory surface and record the inherited ordering rule in implementation notes
- [ ] Add focused proof for the new context-session-summaries introspection attr, including exclusions, exact-field shape, and ordering

Slice 5 — Composed workflow proof and docs
- [ ] Add focused proof for successful mutation invocation through `psi-tool`
- [ ] Add focused proof that invalid mutation requests, including unsupported `entity`, malformed mutation strings, and malformed params, fail explicitly and do not silently route to the wrong session
- [ ] Add focused proof that mutate preserves result invariants, including canonical `nil` success, and does not bypass canonical enforcement
- [ ] Add a composed workflow proof for query active session → query compact summaries → mutate chosen session → verify result
- [ ] Verify the chosen session is gone, the active session remains, and `:psi.agent-session/active-session-id` is unchanged when the closed session was not active
- [ ] Update documentation/examples for the canonical query → select → mutate workflow
- [ ] Verify that session cleanup and similar admin workflows are implementable in caller logic without a bespoke delete-old-sessions command

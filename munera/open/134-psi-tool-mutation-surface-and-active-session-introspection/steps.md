Slice 1 — Mutation dispatch seam and contract alignment
- [ ] Review the current `psi-tool` action dispatch and identify the narrowest canonical hook for registered mutation execution
- [x] Define and record the exact request/result/error contract for `action: "mutate"`
- [ ] Record the chosen mutation-execution helper/path and routing constraints in `implementation.md`

Slice 2 — `psi-tool(action: "mutate")`
- [ ] Add `"mutate"` to the `psi-tool` action contract
- [ ] Implement `psi-tool(action: "mutate", ...)` using the canonical mutation surface rather than raw eval or command parsing
- [ ] Ensure unknown mutation names, malformed params, and unsupported `entity` fail explicitly with structured validation errors
- [ ] Normalize string-keyed map/object params into the canonical mutation param shape if needed
- [ ] Ensure mutate reuses canonical capability/permission/validation enforcement rather than bypassing it
- [ ] Shape successful mutation results under `:psi-tool/result` with `:psi-tool/error` absent
- [ ] Shape validation and mutation-execution failures under structured `:psi-tool/error` with preserved `ex-data` where available and `:psi-tool/result` absent

Slice 3 — Active session introspection
- [ ] Add and wire an authoritative `:psi.agent-session/active-session-id` root attr
- [ ] Return `nil` rather than guessing when there is no active conversation target
- [ ] Add focused proof for the new active-session-id introspection attr, including nil behavior

Slice 4 — Compact session summary introspection
- [ ] Add and wire a compact `:psi.agent-session/context-session-summaries` root attr
- [ ] Prefer reusing the existing canonical session-info source/projection trimmed to the allowed fields
- [ ] Keep the compact session-summary surface free of heavy transcript/message payloads, including `:psi.session-info/first-message`, `:psi.session-info/all-messages-text`, and message-history joins
- [ ] Preserve the canonical ordering of the existing context session inventory surface
- [ ] Add focused proof for the new context-session-summaries introspection attr, including exclusions and ordering

Slice 5 — Composed workflow proof and docs
- [ ] Add focused proof for successful mutation invocation through `psi-tool`
- [ ] Add focused proof that invalid mutation requests, including unsupported `entity`, fail explicitly and do not silently route to the wrong session
- [ ] Add focused proof that mutate preserves result invariants and does not bypass canonical enforcement
- [ ] Add a composed workflow proof for query active session → query compact summaries → mutate chosen session → verify result
- [ ] Verify the chosen session is gone, the active session remains, and `:psi.agent-session/active-session-id` is unchanged when the closed session was not active
- [ ] Update documentation/examples for the canonical query → select → mutate workflow
- [ ] Verify that session cleanup and similar admin workflows are implementable in caller logic without a bespoke delete-old-sessions command

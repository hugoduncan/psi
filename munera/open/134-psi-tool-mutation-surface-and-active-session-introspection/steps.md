Slice 1 — Mutation dispatch seam and contract alignment
- [x] Review the current `psi-tool` action dispatch and identify the concrete production-owned canonical hook for registered mutation execution
- [x] Define and record the exact request/result/error contract for `action: "mutate"`
- [x] Record review-driven contract clarifications in `design.md` and `implementation.md`
- [x] Record the chosen mutation-execution helper/path and routing constraints in `implementation.md`
- [x] Record the exact validation boundary for `mutation` string parsing, registry lookup, and `:validate` vs `:mutation` phase assignment in code-facing notes

Slice 2 — `psi-tool(action: "mutate")`
- [x] Add `"mutate"` to the `psi-tool` action contract
- [x] Implement `psi-tool(action: "mutate", ...)` using the canonical mutation surface rather than raw eval or command parsing
- [x] Ensure unknown mutation names, malformed params, and unsupported `entity` fail explicitly with structured validation errors
- [x] Validate `mutation` as a string that parses as a qualified symbol before registry lookup
- [x] Normalize only top-level string-keyed map/object params into the canonical mutation param shape if needed
- [x] Preserve values and unknown keys rather than performing broader semantic rewriting in v1
- [x] Ensure mutate reuses canonical capability/permission/validation enforcement rather than bypassing it
- [x] Shape successful mutation results under `:psi-tool/result` with `:psi-tool/error` absent, including canonical successful empty-target results
- [x] Shape validation and mutation-execution failures under structured `:psi-tool/error` with preserved `ex-data` where available and `:psi-tool/result` absent

Slice 3 — Active session introspection prerequisite
- [x] Confirm task 139 landed `:psi.agent-session/active-session-id` in `resolvers/session.clj`
- [x] Confirm the source of truth is the invoking query context's bound `:psi.agent-session/session-id`, not adapter-local UI focus or session ordering
- [x] Confirm focused proof already exists for non-nil, present-but-nil, root-queryable, and ordering-independent semantics
- [x] Add only composed-workflow proof in this task where `:psi.agent-session/active-session-id` participates in the query → select → mutate flow

Slice 4 — Compact session summary introspection
- [x] Identify and record the chosen owner/resolver path for `:psi.agent-session/context-session-summaries`
- [x] Add and wire a compact `:psi.agent-session/context-session-summaries` root attr
- [x] Prefer reusing the existing canonical session-info source/projection trimmed to the allowed fields
- [x] Extend the shared live context-session projection to expose canonical `:psi.session-info/updated` from `:updated-at` so the compact summary attr can satisfy the exact v1 field contract without diverging from the existing context inventory source
- [x] Keep the compact session-summary surface free of heavy transcript/message payloads, including `:psi.session-info/first-message`, `:psi.session-info/all-messages-text`, and message-history joins
- [x] Expose exactly the allowed summary fields in v1 and no additional fields
- [x] Preserve the canonical ordering of the existing context session inventory surface and record the inherited ordering rule in implementation notes
- [x] Add focused proof for the new context-session-summaries introspection attr, including exclusions, exact-field shape, and ordering

Slice 5 — Composed workflow proof and docs
- [x] Add focused proof for successful mutation invocation through `psi-tool`
- [x] Add focused proof that invalid mutation requests, including unsupported `entity`, malformed mutation strings, and malformed params, fail explicitly and do not silently route to the wrong session
- [x] Add focused proof that mutate preserves result invariants, including canonical successful empty-target results, and does not bypass canonical enforcement
- [x] Add a composed workflow proof for query active session → query compact summaries → mutate chosen session → verify result
- [x] Verify the chosen session is gone, the active session remains, and `:psi.agent-session/active-session-id` is unchanged when the closed session was not active
- [ ] Update documentation/examples for the canonical query → select → mutate workflow
- [x] Verify that session cleanup and similar admin workflows are implementable in caller logic without a bespoke delete-old-sessions command

Verification
- [x] `clj-kondo --lint` clean on touched source/test files
- [x] `JAVA_TOOL_OPTIONS='-Xmx2g' clojure -M:test --focus psi.agent-session.graph-surface-test` → `22 tests, 2247 assertions, 0 failures`
- [x] `JAVA_TOOL_OPTIONS='-Xmx2g' clojure -M:test --focus psi.agent-session.tools-test/psi-tool-integration-test --reporter kaocha.report/dots --no-randomize` → `1 tests, 53 assertions, 0 failures`
- [x] `JAVA_TOOL_OPTIONS='-Xmx2g' clojure -M:test --focus psi.agent-session.resolvers-test --focus psi.agent-session.session-close-mutation-test --reporter kaocha.report/dots --no-randomize` → `23 tests, 141 assertions, 0 failures`
- [ ] full `bb clojure:test:unit` still OOMs elsewhere in the suite; isolate separately from task 134

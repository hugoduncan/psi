# 205 — Steps

## Slice 1 — Shared invocation/listing helper

- [ ] Create ns `psi.agent-session.deterministic-operation-action` in
  `components/agent-session/src/psi/agent_session/deterministic_operation_action.clj`,
  requiring `psi.deterministic-operation-registry.registry :as registry`,
  `psi.deterministic-operation-runtime.core :as runtime`, and
  `psi.session-state.state :as session-state`, `clojure.edn`, `clojure.string`.
- [ ] Implement `list-operations [ctx]`: read
  `(registry/all-operations-in (:deterministic-operation-registry ctx))`,
  map each to `{:id (:id op) :description (:description op)}`, sort ascending by
  `:id` (string compare); return a vector (empty `[]` for empty registry).
- [ ] Implement `build-invocation [ctx session-id args]`: return
  `{:args (or args {}) :ctx ctx :session-id session-id}`, conditionally
  `assoc :parent-session-id` only when
  `(:parent-session-id (session-state/get-session-data-in ctx session-id))`
  is non-nil. Do **not** include `:operation-id`, `:workflow-run-id`, `:step-id`.
- [ ] Implement `invoke-operation [ctx session-id operation-id args]`: build the
  invocation map, then call
  `(registry/invoke-operation-in (:deterministic-operation-registry ctx)
     operation-id invocation runtime/invoke-operation)` passing `operation-id`
  positionally; return the tagged result. Let `:missing-deterministic-operation`
  / malformed ex-info propagate.
- [ ] Implement `truncate-value [s]`: `pr-str`-derived string `s`; when
  `(> (count s) 2000)`, return
  `(str (subs s 0 2000) " … (truncated, " (count s) " chars total)")`; else `s`.
- [ ] Implement `project-result [result]`: for each top-level key, map value →
  `(truncate-value (pr-str v))`; return a map of `{k truncated-string}`
  preserving all keys present (data-level, surface-independent).
- [ ] Write tests
  `components/agent-session/test/psi/agent_session/deterministic_operation_action_test.clj`
  using a real registry (`registry/create-registry`) + real runtime:
  - [ ] `list-operations` returns id+description sorted by id; empty registry → `[]`.
  - [ ] register a fake op; `invoke-operation` returns its `:ok` tagged result.
  - [ ] handler receives `:operation-id` injected by runtime; caller map has no
    `:operation-id`, no `:workflow-run-id`, no `:step-id`.
  - [ ] `:parent-session-id` present only when session-data has a parent.
  - [ ] error op → `:error` tagged result passes through.
  - [ ] unknown id → `:missing-deterministic-operation` ex-info thrown.
  - [ ] `truncate-value` on >2000-char string truncates with exact marker + N.
  - [ ] `project-result` includes all top-level keys, each `pr-str`'d+truncated.
- [ ] Run slice-1 tests; `clj-paren-repair` the new files; `clj-kondo --lint`.
- [ ] Commit: `⚒ 205: shared deterministic-operation invocation/listing helper`.

## Slice 2 — psi-tool `operation` action

- [ ] Create ns `psi.agent-session.psi-tool-operation` in
  `components/agent-session/src/psi/agent_session/psi_tool_operation.clj`,
  requiring the shared helper. Implement
  `execute-psi-tool-operation-report [{:keys [ctx session-id]} {:keys [op operation-id args]}]`:
  - [ ] guard `ctx` present (mirror `psi-tool-workflow`).
  - [ ] `op "list"` → call `list-operations`, build
    `{:psi-tool/action :operation :psi-tool/operation-op :list
      :psi-tool/overall-status :ok :psi-tool/operations [...]}` (empty `[]` ok).
  - [ ] `op "invoke"` → call shared `invoke-operation`, project result via
    `project-result`, build `{:psi-tool/action :operation
      :psi-tool/operation-op :invoke :psi-tool/overall-status (:status result)
      :psi-tool/result <projected>}`.
  - [ ] wrap in try/catch → on exception build
    `:psi-tool/overall-status :error` + `:psi-tool/error` summary (mirror
    `psi-tool-workflow`), so missing/malformed/bad-arg render not-crash.
  - [ ] add `:psi-tool/duration-ms`.
- [ ] In `psi_tool.clj`: add `"operation"` to `psi-tool-supported-actions`.
- [ ] In `psi_tool.clj` tool schema: add `"operation"` to
  `:properties :action :enum`; add `:operation-id` property; ensure `:args`
  property exists (add if absent) with EDN-map-string description; extend the
  `:description` text listing the new action.
- [ ] In `validate-psi-tool-request`: add `operation-id`/`args` to destructuring;
  add `(= effective-action "operation")` cond branch — require
  `op ∈ #{"list" "invoke"}`; for `"invoke"` require non-blank `operation-id`;
  return `{:action "operation" :op op :operation-id operation-id :args args}`.
- [ ] Add EDN-map parse+validate for `args` (default `{}`, "must be an EDN map"
  error) — reuse shared parse helper (place in shared helper ns or mirror
  `parse-workflow-input-string`); call it where `args` is consumed.
- [ ] In `make-psi-tool` `case action`: add `"operation"` arm calling
  `execute-psi-tool-operation-report`, `sanitize-psi-tool-data`, `pr-str`,
  `serialize-operation-output`, set `:is-error` on non-`:ok` overall-status.
- [ ] Add `operation-id`/`args` to `telemetry-args`.
- [ ] Write tests
  `components/agent-session/test/psi/agent_session/psi_tool_operation_test.clj`:
  - [ ] `op list` returns sorted operations; empty registry → `:operations []`.
  - [ ] `op list` ignores `operation-id`/`args`.
  - [ ] `op invoke` ok-result projected, all keys present.
  - [ ] `op invoke` error-result → `:is-error true`, projected.
  - [ ] unknown id → error report, not crash.
  - [ ] malformed args (non-map / unreadable EDN) → validate error, not crash.
  - [ ] side-effecting op invokable (assert observable effect).
  - [ ] over-2000-char value truncated identically to slice-1 helper.
  - [ ] `validate-psi-tool-request` accepts `action "operation"`.
- [ ] Run slice-2 tests; `clj-paren-repair`; `clj-kondo --lint`.
- [ ] Commit: `⚒ 205: psi-tool operation action (list|invoke)`.

## Slice 3 — slash commands `/operations` + `/operation`

- [ ] In `commands.clj`: require the shared helper ns.
- [ ] Add `"/operations" :operations` to `exact-command-handlers`; add
  `:operations` arm in `dispatch*` exact `case` →
  `{:type :text :message (format-operations ctx)}`.
- [ ] Implement `format-operations [ctx]`: call `list-operations`; empty →
  `"No deterministic operations registered."`; else lines of
  `"<id> — <description>"` (sorted already by helper).
- [ ] Add `"/operation"` to `prefixed-command-prefixes`; add arm to
  `dispatch-prefixed-command` → `dispatch-operation-command`.
- [ ] Implement `dispatch-operation-command [ctx session-id trimmed]` (decision
  #11): strip `^/operation\s*`; split tail once on first whitespace run into
  `<id>` + remaining text; blank `<id>` →
  `{:type :text :message "Usage: /operation <id> {edn-args}"}`; blank remaining
  → `args {}`; parse remaining as EDN map, non-map/unreadable →
  `{:type :text :message "<clear parse error>"}`; else call shared
  `invoke-operation`, render projected result via `project-result` as
  `:type :text` (catch `:missing-deterministic-operation`/malformed → clear
  text error, not crash).
- [ ] Confirm precedence: `/operations` matched as exact before `/operation`
  prefix; add a test asserting `/operations` does not dispatch as `/operation`.
- [ ] Write tests
  `components/agent-session/test/psi/agent_session/operation_command_test.clj`:
  - [ ] `/operations` lists id+description sorted; empty → exact message.
  - [ ] `/operation <id> {args}` invokes, renders result text (all keys).
  - [ ] `/operation <id>` (no args) → `args {}` default.
  - [ ] blank id → usage message.
  - [ ] malformed/non-map args → clear text error, not crash.
  - [ ] unknown id → clear text error, not crash.
  - [ ] side-effecting op invokable.
  - [ ] `/operations` vs `/operation` precedence (no collision).
- [ ] Run slice-3 tests; `clj-paren-repair`; `clj-kondo --lint`.
- [ ] Commit: `⚒ 205: /operations + /operation slash commands`.

## Slice 4 — docs + CHANGELOG

- [ ] Update README and relevant `doc/` (command reference + psi-tool action
  reference) documenting `/operations`, `/operation <id> {edn-args}`, and
  `action: "operation"` with `op list|invoke`, params, truncation, side-effects.
- [ ] Add CHANGELOG `[Unreleased] > Added` entry for the new commands +
  psi-tool action (before commit, user-visible).
- [ ] Verify coherence: design ↔ commands/psi-tool ↔ tests ↔ docs.
- [ ] Run full agent-session test suite for touched namespaces.
- [ ] Commit: `⚒ 205: docs + CHANGELOG for deterministic-operation surfaces`.

## Close-out

- [ ] Re-read design acceptance criteria; confirm each is covered by a test.
- [ ] `git mv` task dir `open/ → closed/` and remove from `munera/plan.md`
  (only when implementation + reviews complete).

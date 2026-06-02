# 205 — Plan

## Approach

Add two thin entry points (a slash command and a psi-tool `action`) over a
single shared invocation/listing helper, routing through the **existing**
deterministic-operation registry + runtime boundary. No new execution,
validation, or permission semantics. The shared helper owns building the
invocation map and routing; it owns no rendering — each surface renders.

### Components touched (existing, Polylith layout)

- `components/agent-session/src/psi/agent_session/` — new helper ns + new
  psi-tool action ns + command dispatch wiring.
- Existing reused unchanged:
  - `psi.deterministic-operation-registry.registry`
    (`all-operations-in`, `invoke-operation-in`)
  - `psi.deterministic-operation-runtime.core` (`invoke-operation`)
  - `psi.deterministic-operation-registry.defs` (result schema — read only)
  - `ctx` carries `:deterministic-operation-registry`
    (assembled in `context.clj`, key already present).

### Shared helper (the "underlying mechanism")

New ns, e.g. `psi.agent-session.deterministic-operation-action`
(mirrors the `psi-tool-workflow` / `psi-tool-scheduler` helper-per-action
shape, but shared by both surfaces). Two pure-ish functions:

- `list-operations` — `(fn [ctx] …)`:
  - reads `(registry/all-operations-in (:deterministic-operation-registry ctx))`
  - projects each to `{:id … :description …}` (decision #6)
  - returns **sorted by id ascending, string compare** (decision #12)
  - empty registry → empty seq (surfaces render the empty case)

- `invoke-operation` — `(fn [ctx session-id operation-id args] …)`:
  - builds the **caller invocation map** (decision #10):
    `{:args (or args {}) :ctx ctx :session-id session-id}` plus
    `:parent-session-id` **only** when
    `(:parent-session-id (session-state/get-session-data-in ctx session-id))`
    is non-nil; `:workflow-run-id`/`:step-id` always absent/nil.
  - calls
    `(registry/invoke-operation-in (:deterministic-operation-registry ctx)
       operation-id invocation runtime/invoke-operation)` —
    `operation-id` passed **positionally** (NOT a map key); `runtime`
    injects `:operation-id` via assoc.
  - returns the tagged result (`:ok` / `:error`) unchanged, OR lets the
    `:missing-deterministic-operation` / malformed-result ex-info propagate to
    the surface for clear error rendering.

### Shared result projection (decisions #7, #9)

A shared render helper turning a tagged result map into displayable form by
projecting **all** top-level keys, each value `pr-str`'d then per-key
truncated to **2000 chars** with marker
`… (truncated, N chars total)` (N = untruncated char count). Surface-
independent so command + psi-tool render identically. Put the truncation +
key-projection logic in the shared helper ns (data-level), then each surface
formats: command → text lines; psi-tool → structured `:psi-tool/...` map.

Decision: the *truncation* of each value is shared logic; the *assembly*
(text vs structured map) is per surface. Keep the per-value
`pr-str`+truncate function in the shared ns; both surfaces call it.

### psi-tool action

New ns `psi.agent-session.psi-tool-operation` (mirrors `psi-tool-workflow`).
- `action: "operation"`, `op ∈ {list, invoke}` (decision #2).
- Params: `operation-id` (string), `args` (EDN map string).
- Validation in `psi_tool.clj`'s `validate-psi-tool-request` cond: add
  `(= effective-action "operation")` branch — require `op ∈ #{"list" "invoke"}`;
  for `invoke` require non-blank `operation-id`. `args` parsed/validated as EDN
  map (default `{}`), reusing the "must be an EDN map" pattern from
  `psi-tool-workflow/parse-workflow-input-string`.
- Register `"operation"` in `psi-tool-supported-actions` and in the action enum
  in the tool schema (`:properties :action :enum`), add `operation-id` param to
  schema properties, reuse description style.
- Dispatch branch in `make-psi-tool`'s `case action` → call helper, build
  `:psi-tool/...` structured report (`:psi-tool/action :operation`,
  `:psi-tool/operation-op`, `:psi-tool/overall-status`, payload), set
  `:is-error` on non-`:ok`. `list` → `{:operations [...]}` (empty `[]` for
  empty registry, decision #12). `invoke` → projected result keys.
- Add `operation-id` to `telemetry-args` and to the destructuring in
  `validate-psi-tool-request` / `telemetry-args`.

### Slash command

- `/operations` (exact) — add to `exact-command-handlers` map + dispatch arm →
  `{:type :text :message …}`; empty → `No deterministic operations
  registered.` (decision #12).
- `/operation` (prefixed) — add to `prefixed-command-prefixes` + a
  `dispatch-operation-command` arm. Tail grammar (decision #11): strip
  `^/operation\s*`, split once on first whitespace run → `<id>` + remaining
  `{edn-args}`. Blank `<id>` → `Usage: /operation <id> {edn-args}`. Blank args
  → `{}`. Malformed/non-map args → `:type :text` error naming the parse problem.
- **Ordering guard (decision #11):** exact handlers are matched before prefixed
  (`dispatch*` checks `exact-command-handler` first), and the prefix matcher
  requires exactly `/operation` or `/operation ` prefix — so `/operations`
  never collides. Verified against existing `prefixed-command` logic.

### Docs + CHANGELOG

- README / `doc/` (command reference + psi-tool action reference): document
  `/operations`, `/operation <id> {edn-args}`, and `action: "operation"` with
  `op list|invoke`.
- CHANGELOG `[Unreleased] > Added`: new `/operations` + `/operation` commands
  and new psi-tool `operation` action (user-visible).

## Risks

- **`get-session-data-in` for parent id**: confirm the helper returns
  `:parent-session-id` for a sub-session and `nil`/absent for a root session
  (so the conditional assoc behaves). Verified: `context_index.clj` reads
  `:parent-session-id` off session-data; root sessions lack it.
- **Action enum / schema drift**: the `operation` action must be added to BOTH
  the `:enum` in the tool schema AND `psi-tool-supported-actions` AND the
  `validate-psi-tool-request` cond, or validation/telemetry silently diverge.
  Single-edit discipline; add a test asserting the action is accepted.
- **Truncation identical across surfaces**: both surfaces must call the same
  shared truncate fn, not re-implement. Test both surfaces on an over-2000-char
  value and assert identical truncated value + marker.
- **Missing-operation surfacing**: `invoke-operation-in` throws ex-info
  `:missing-deterministic-operation`; each surface must catch and render
  clearly (psi-tool → `:psi-tool/overall-status :error`; command → `:type
  :text` error), not crash.
- **No `:operation-id` in caller map**: easy to mistakenly add it; decision #10
  forbids it (runtime injects). Test asserts the handler receives the
  positionally-injected id and the caller map has no `:operation-id` key.

## Slice order

Vertical slices, each independently testable; ship in order:

1. **Shared helper** — `deterministic-operation-action` ns: `list-operations`,
   `invoke-operation`, and `project-result` (pr-str + truncate). Unit tests
   against a real in-process registry (testing-without-mocks: real registry +
   real runtime, register a fake op).
2. **psi-tool action** — schema/enum/validate/dispatch wiring +
   `psi-tool-operation` ns rendering. Tests: list (sorted, empty), invoke
   (ok/error/missing/malformed/side-effecting), arg default + bad-EDN.
3. **Slash command** — `/operations` + `/operation` wiring + grammar + empty
   message. Tests: list, invoke, blank id usage, bad args, `/operations` vs
   `/operation` precedence.
4. **Docs + CHANGELOG** — README/`doc/` + CHANGELOG Unreleased entry.

Slice 1 has no dependents-on-surface; slices 2 and 3 both depend only on 1 and
are independent of each other. Slice 4 last.

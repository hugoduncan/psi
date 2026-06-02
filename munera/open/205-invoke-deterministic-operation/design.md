# 205 — Invoke a deterministic operation (command + psi-tool capability)

## Intent

Make registered deterministic operations directly invokable outside a workflow
run, via two new surfaces that **share one underlying mechanism**:

1. a **slash command** (user-facing, in `agent-session/commands.clj`), and
2. a **psi-tool capability** (agent-facing, a new `action` in `psi-tool`).

Both surfaces are thin adapters over a single shared invocation/listing helper;
they differ only in input parsing and output rendering, never in the invocation
mechanism (`one_way`).

Today a deterministic operation can only be executed as a side effect of a
workflow IR `invoke` step (`workflow-runtime .../step_execution.clj` →
`deterministic-op-registry/invoke-operation-in`). The registry, the operation
contract (`deterministic-operation-registry.defs`), and the runtime boundary
(`deterministic-operation-runtime.core`) already exist and are reused unchanged;
this task only adds two new *entry points* into that existing boundary.

## Problem

Deterministic operations (`github/find-issue`, `github/edit-labels`,
`workflow/constant-routing`, …) encapsulate reusable, side-effect-bearing or
pure units that are valuable on their own — for manual user actions, for agent
self-service, and for debugging/inspecting an operation in isolation. There is
currently no way to invoke one except by authoring/running a whole workflow.

This forces overhead (define a workflow, run it) for what should be a single
call, and gives neither the user nor the agent a discoverable way to *list* and
*run* the operations the running session knows about.

## Scope

In scope:

- A psi-tool `action` that invokes a deterministic operation by id with EDN
  args, returning the tagged operation result projected into tool output.
- A psi-tool capability to **list** the operation ids available in the session
  (discoverability — `one_way`: do not force the caller to guess ids).
- A slash command surface that lets the user list operations and invoke one.
- Routing the invocation through the *existing* runtime boundary
  (`deterministic-operation-runtime/invoke-operation`) and registry
  (`invoke-operation-in`) — no new execution/validation semantics.
- Constructing the invocation map from the live session: `:operation-id`,
  `:args`, `:ctx`, `:session-id` (and `:parent-session-id` where available);
  `:workflow-run-id` / `:step-id` are absent (nil) for direct invocation.
- Result projection: success (`:ok` → `:data`/`:summary`/`:details`) and the
  two failure modes that already exist (tagged `:error` result; thrown →
  canonicalized `:error`; malformed result → ex-info).
- Docs + CHANGELOG (user-visible new command + new psi-tool action).

Out of scope:

- Changing the operation contract, registry storage, or runtime boundary.
- Registering any new deterministic operations.
- New permission/capability gating: none. Side-effecting operations are
  invokable; only the existing psi-tool/command governance applies.
- Workflow IR invoke-step behaviour (unchanged).

Adjacent / deferred (separate tasks if wanted):

- Argument-shape introspection / per-operation arg schemas (operations have no
  declared arg schema today — only an `:id`, `:handler`, optional
  `:description`/`:summary`).

## Minimum concepts

- **Deterministic operation**: `{:id :handler :description? :summary? :ext-path?
  :source?}`, id matching `^ns/name$` kebab-case.
- **Invocation map**: keys consumed by handlers; here built from session ctx.
- **Tagged result**: `{:status :ok …}` | `{:status :error …}`.
- **Shared invocation helper** (the "underlying mechanism"): one function pair —
  *list* (operation id + description) and *invoke* (operation-id + args → tagged
  result) — that builds the invocation map from session ctx and routes through
  the existing registry + runtime boundary. Both the command and the psi-tool
  action call this; it owns no rendering.
- **psi-tool action**: discriminator string + parameter set, dispatched in
  `psi_tool.clj` (mirrors the existing `workflow`/`scheduler` actions, each in
  its own helper namespace) — parses input, calls the shared helper, renders the
  structured `:psi-tool/...` result map.
- **Slash command**: `commands.clj` dispatch entry returning a data map
  (`:type :text`), rendered by TUI/RPC — parses input, calls the shared helper,
  renders text.

## Architecture alignment

- Canonical domain-state reads go through resolvers; canonical state changes go
  through dispatch. The deterministic-operation registry is **not** canonical
  `:state*` domain data — it is a *runtime handle* in the
  `doc/architecture.md` sense (a `defrecord` owning its own internal `atom`,
  infrastructure machinery; see the "runtime handles on ctx" table, which lists
  the workflow registry as the same kind of thing). It has no `:state*`
  projection and no resolver/EQL surface today.
- Both **listing** and **invocation** are therefore runtime-boundary reads of a
  runtime handle, not canonical-state reads. The new surfaces call the same
  registry functions the established workflow invoke-step path
  (`step_execution`) already calls — `all-operations-in` for listing,
  `invoke-operation-in` (via `deterministic-operation-runtime/invoke-operation`)
  for invocation. This *is* `one_way`: a single read path shared with the
  existing consumer. Surfacing listing through a new resolver/EQL attribute
  would instead introduce a *second*, parallel read path over the same runtime
  handle (violating `one_way`) and would require first projecting the registry
  into `:state*` — out of scope and unjustified for an infrastructure handle the
  architecture explicitly classifies as "not queryable domain state".
- Invocation is execution at the runtime boundary (already the established
  pattern for workflow invoke steps), so the new surfaces call the same
  `deterministic-operation-runtime`/registry functions rather than introducing
  a parallel path (`one_way`).
- psi-tool already federates several `action`s, each parsed/validated/executed
  in a dedicated `psi-tool-<x>` namespace (`psi-tool-workflow`,
  `psi-tool-scheduler`). The new action follows that shape — no shim.
- Commands return pure data maps; the new command follows the existing
  prefixed-command pattern (`/job`, `/remember`, …).

## Locked decisions

1. **Both surfaces, one mechanism.** Command and psi-tool action are equal
   first-class consumers; both delegate to the single shared invocation/listing
   helper. No parallel invocation path.
2. **psi-tool action.** `action: "operation"` with `op` values `list | invoke`
   (mirrors the `workflow` action's op style). Params: `operation-id` (string)
   and `args` (EDN map string).
3. **Command surface.** `/operations` (list) and `/operation <id> {edn-args}`
   (invoke).
4. **Args format.** EDN map string, consistent with `workflow-input` /
   `session-config`.
5. **Side effects allowed.** Any registered operation may be invoked, including
   side-effecting ones (e.g. `github/edit-labels`). No new permission/capability
   gating beyond what already governs psi-tool and commands.
6. **Listing detail.** List returns operation **id + description** per entry.
7. **Result rendering.** Render **all top-level keys** of the tagged result,
   applying **per-key truncation** to bound oversized values.
8. **Listing read path.** Listing reads the deterministic-operation registry
   directly via `all-operations-in`, *not* through a resolver/EQL attribute.
   Rationale: the registry is a runtime handle (per `doc/architecture.md`), not
   canonical `:state*` domain data, so "reads go through resolvers" (which
   governs canonical domain state) does not apply. Listing shares the single
   existing read path the workflow invoke-step (`step_execution`) already uses
   over the same handle; adding a resolver would create a parallel path and
   violate `one_way`. No `:state*` projection is added.

## Acceptance criteria

- `action: "operation", op: "list"` returns each available operation's id +
  description for the invoking session.
- `action: "operation", op: "invoke"` with `operation-id` + EDN `args` invokes
  the operation through the existing runtime boundary and projects its tagged
  result (ok / error / malformed) into tool output, rendering all top-level
  result keys with per-key truncation.
- `/operations` lists operations (id + description) as a `:type :text` result.
- `/operation <id> {edn-args}` invokes the operation and returns its result as a
  `:type :text` result.
- Both surfaces call the same shared invocation/listing helper (verified: no
  duplicate invocation logic).
- Side-effecting operations are invokable from both surfaces.
- Unknown operation id → the existing `:missing-deterministic-operation` error
  is surfaced clearly (not a crash) on both surfaces.
- No change to operation contract, registry, runtime boundary, or workflow
  invoke-step behaviour.
- README/`doc/` + CHANGELOG updated for the new user-visible surfaces.

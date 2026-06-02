# 205 — Invoke a deterministic operation (command + psi-tool capability)

## Intent

Make registered deterministic operations directly invokable outside a workflow
run, via two new surfaces:

1. a **slash command** (user-facing, in `agent-session/commands.clj`), and
2. a **psi-tool capability** (agent-facing, a new `action` in `psi-tool`).

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
- Permission/capability gating beyond what already governs psi-tool and
  commands (unless an open question below resolves to add it).
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
- **psi-tool action**: discriminator string + parameter set, dispatched in
  `psi_tool.clj` (mirrors the existing `workflow`/`scheduler` actions, each in
  its own helper namespace).
- **Slash command**: `commands.clj` dispatch entry returning a data map
  (`:type :text`), rendered by TUI/RPC.

## Architecture alignment

- Reads go through resolvers; state changes go through dispatch. Operation
  invocation is execution at the runtime boundary (already the established
  pattern for workflow invoke steps), so the new surfaces call the same
  `deterministic-operation-runtime`/registry functions rather than introducing
  a parallel path (`one_way`).
- psi-tool already federates several `action`s, each parsed/validated/executed
  in a dedicated `psi-tool-<x>` namespace (`psi-tool-workflow`,
  `psi-tool-scheduler`). The new action follows that shape — no shim.
- Commands return pure data maps; the new command follows the existing
  prefixed-command pattern (`/job`, `/remember`, …).

## Open questions (to resolve collaboratively before plan.md)

1. **Primary driver / priority**: is the main consumer the *agent* (psi-tool),
   the *user* (command), or equally both? This shapes where effort/detail goes.
2. **psi-tool action name + ops**: proposal — `action: "operation"` with
   `op` values `list | invoke` (mirrors `workflow`'s op style), params
   `operation-id` (string) and `args` (EDN map string). Alternative:
   a bare action `"invoke-operation"` + separate `"list-operations"`. Prefer?
3. **Command surface**: proposal — `/operations` (list) and
   `/operation <id> {edn-args}` (invoke). Alternative single prefixed
   `/operation` with an `list` subcommand. Naming/shape preference?
4. **Args format**: EDN map string (consistent with `workflow-input`,
   `session-config`)? Confirm.
5. **Side-effecting operations from a command**: `github/edit-labels` mutates
   GitHub. Is direct user/agent invocation of side-effecting operations in
   scope now, or should the first cut be invocation-of-any with a clear result,
   deferring any gating? (Current lean: invoke any; no new gating — but flag.)
6. **Listing detail**: list just ids, or ids + `:description`/`:source`/
   `:ext-path` for discoverability? (Lean: ids + description + source.)
7. **Result rendering**: how much of `:data` to surface in the command’s text
   result vs. psi-tool’s structured `:psi-tool/...` map? Truncation policy?

## Acceptance criteria (draft — finalised after open questions)

- A psi-tool request can list the deterministic operation ids available to the
  invoking session.
- A psi-tool request can invoke a named operation with args and receive its
  tagged result (ok/error/malformed) projected into tool output, going through
  the existing runtime boundary.
- A slash command can list operations and invoke one, returning a `:type :text`
  data map.
- Unknown operation id → the existing `:missing-deterministic-operation`
  error is surfaced clearly (not a crash).
- No change to operation contract, registry, runtime boundary, or workflow
  invoke-step behaviour.
- README/`doc/` + CHANGELOG updated for the new user-visible surfaces.

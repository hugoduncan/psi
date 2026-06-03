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
- Constructing the invocation map from the live session: `:args`, `:ctx`,
  `:session-id` (and `:parent-session-id` where available); `:workflow-run-id`
  / `:step-id` are absent (nil) for direct invocation. The `operation-id` is
  passed **positionally** to `invoke-operation-in` (as the workflow path does),
  not as an invocation-map key — `runtime/invoke-operation` injects
  `:operation-id` into the map itself via `assoc`.
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
7. **Result rendering.** Render **all top-level keys** of the tagged result.
   This is the single authoritative projection rule: the displayed key set is
   exactly the keys present on the tagged result, with no enumerated subset.
   By the result schema, `:ok` results carry required `:status` and `:data`
   keys plus optional `:summary`/`:details`; `:error` results carry required
   `:status`, `:reason`, and `:message` keys plus optional `:details`. Each
   top-level **value** is rendered via
   `pr-str` and then **per-key truncated** (see decision #9). (This supersedes
   any earlier `:data/:summary/:details`-enumerated phrasing.)
8. **Listing read path.** Listing reads the deterministic-operation registry
   directly via `all-operations-in`, *not* through a resolver/EQL attribute.
   Rationale: the registry is a runtime handle (per `doc/architecture.md`), not
   canonical `:state*` domain data, so "reads go through resolvers" (which
   governs canonical domain state) does not apply. Listing shares the single
   existing read path the workflow invoke-step (`step_execution`) already uses
   over the same handle; adding a resolver would create a parallel path and
   violate `one_way`. No `:state*` projection is added.

9. **Per-key truncation rule.** Each rendered top-level value (after `pr-str`)
   is truncated to a maximum of **2000 characters**. When the `pr-str` string
   exceeds the bound, keep the first 2000 characters and append the marker
   `… (truncated, N chars total)` where `N` is the untruncated character count.
   The unit is characters of the `pr-str` rendering (not collection size, not
   tree depth) — a single, surface-independent rule so the command and the
   psi-tool action render identically. Truncation is per top-level value only;
   nested structure is not separately bounded. The `:status`, `:reason`, and
   `:message` values are subject to the same rule (in practice well under the
   bound).

10. **Direct-invocation invocation-map shape.** The requested `operation-id` is
    passed **positionally** to `registry/invoke-operation-in` (exactly as the
    workflow path does); it is **not** a key of the caller-built invocation map.
    `runtime/invoke-operation` injects `:operation-id` into the map itself via
    `(assoc invocation :operation-id (:id operation))`, so a caller-supplied
    `:operation-id` would be redundant and overwritten. The caller-built
    invocation map therefore contains: `:args` (parsed EDN map, default `{}`),
    `:ctx` (session ctx), and `:session-id` (the invoking session's id).
    `:parent-session-id` is set to the invoking session's parent id only when
    the invoking session is itself a sub-session whose parent is known on ctx;
    otherwise it is `nil`. `:workflow-run-id` and `:step-id` are always `nil`
    for direct invocation. This reconciles with the workflow path
    (`step_execution`), which likewise passes `operation-id` positionally and
    builds an invocation map with `:parent-session-id` + `:workflow-run-id` +
    `:step-id` but no `:session-id`: both entry points pass `operation-id`
    positionally and populate the invocation-map with the subset of identity
    keys meaningful to each. For a direct call the meaningful identity is
    `:session-id` (who invoked); workflow run/step ids are meaningless and
    therefore nil. Handlers already tolerate absent keys (documented as "may
    include" on `invoke-operation`).

11. **Command arg grammar.** `/operation` is a prefixed command; its tail
    (everything after `/operation `) is split once on the first run of
    whitespace into `<id>` and the remaining `{edn-args}` text. `<id>` is the
    first non-whitespace token. The remaining text is parsed as an EDN map; if
    it is blank/absent, `args` defaults to `{}`. If `<id>` is blank →
    `Usage: /operation <id> {edn-args}`. Malformed EDN args (parse failure or
    non-map) → a `:type :text` error message naming the parse problem
    (mirrors `psi-tool-workflow`'s "must be an EDN map" validation), not a
    crash. The psi-tool `args` param follows the identical default-`{}` and
    "must be an EDN map" validation. `/operations` (list) is a distinct exact
    command and never collides with the `/operation` prefix (exact handlers are
    matched before prefixed ones, and the prefix matcher requires exactly
    `/operation` or a `/operation ` prefix, never `/operations`).

12. **`op: list` behaviour and ordering.** For `op: "list"` (psi-tool) and
    `/operations` (command), `operation-id` and `args` are **ignored** (not
    rejected) — list takes no parameters. The listing is returned **sorted by
    operation id** (ascending, string compare) for deterministic, stable
    output across both surfaces, consistent with the "deterministic" framing.
    An empty registry renders an explicit empty-list message
    (`No deterministic operations registered.` for the command; an empty
    `:operations []` collection for the psi-tool structured result) rather than
    blank output.

## Acceptance criteria

- `action: "operation", op: "list"` returns each available operation's id +
  description for the invoking session, **sorted by id**; `operation-id`/`args`
  are ignored; an empty registry yields an empty `:operations []` result.
- `action: "operation", op: "invoke"` with `operation-id` + EDN `args` (default
  `{}` when absent) invokes the operation through the existing runtime boundary
  and projects its tagged result (ok / error / malformed) into tool output,
  rendering **all** top-level result keys, each value `pr-str`'d and truncated
  to 2000 chars with a `… (truncated, N chars total)` marker.
- The direct-invocation invocation map carries `:args`, `:ctx`, `:session-id`,
  optional `:parent-session-id` (nil unless the invoking session has a known
  parent), and nil `:workflow-run-id`/`:step-id`. `operation-id` is passed
  positionally to `invoke-operation-in` (injected into the map by
  `runtime/invoke-operation`), not built into the caller's invocation map.
- `/operations` lists operations (id + description, sorted by id) as a
  `:type :text` result; empty registry → `No deterministic operations
  registered.`
- `/operation <id> {edn-args}` invokes the operation and returns its result as a
  `:type :text` result. `<id>` is the first whitespace-delimited token; the
  remainder parses as an EDN map (default `{}`). Blank `<id>` → usage message;
  malformed/non-map args → a clear `:type :text` error (not a crash).
- Both surfaces call the same shared invocation/listing helper (verified: no
  duplicate invocation logic).
- Side-effecting operations are invokable from both surfaces.
- Unknown operation id → the existing `:missing-deterministic-operation` error
  is surfaced clearly (not a crash) on both surfaces.
- No change to operation contract, registry, runtime boundary, or workflow
  invoke-step behaviour.
- README/`doc/` + CHANGELOG updated for the new user-visible surfaces.

# Design: Multi-Session State & Process-Scoped ctx

**Status:** Proposal
**Scope:** `psi.agent-session`, consumers (TUI, extensions, RPC)

## Problem

`ctx` mixes two orthogonal concerns: process-scoped runtime (registries,
atom, statechart env) and per-session state (agent-ctx, sc-session-id,
session data). The atom's `[:agent-session :data]` slot time-shares a
single session. The result:

- Only one session can be active at a time
- Session switching requires serialization/deserialization
- Consumers receive ctx or closures over ctx, coupling them to internals
- RPC requires `with-target-session!` and `session-route-lock` to
  multiplex sessions over a single ctx

## Target Atom Shape

```clojure
{:agent-session {:sessions {sid1 {:data           {,,,session-map,,,}
                                  :agent-ctx      agent-ctx-1
                                  :sc-session-id  sc-sid-1}
                            sid2 {:data           {,,,session-map,,,}
                                  :agent-ctx      agent-ctx-2
                                  :sc-session-id  sc-sid-2}}
                 :active-session-id sid1}
 :telemetry       ,,,
 :runtime         ,,,
 :persistence     ,,,
 :turn            ,,,
 :background-jobs ,,,
 :ui              ,,,
 :recursion       ,,,
 :oauth           ,,,}
```

`context-index` disappears. Its metadata merges into each session entry.
`:active-session-id` moves to the top of `:agent-session`.

## ctx Becomes Process-Scoped Only

ctx retains only what is shared across all sessions:

| Key | Rationale |
|-----|-----------|
| `:state*` | The atom |
| `:sc-env` | Statechart env, hosts multiple session instances |
| `:extension-registry` | Process-wide |
| `:workflow-registry` | Process-wide |
| `:config` | Process-wide |
| `:cwd` | Process-wide |
| `:oauth-ctx` | Process-wide |
| `:recursion-ctx` | Process-wide |
| `:event-queue` | Process-wide dispatch bus |
| `:persist?` | Process-wide flag |
| `:compaction-fn`, `:branch-summary-fn` | Process-wide strategies |
| Capability injection fns | Process-wide |

Removed from ctx:

| Key | Disposition |
|-----|-------------|
| `:agent-ctx` | → `[:agent-session :sessions sid :agent-ctx]` |
| `:sc-session-id` | → `[:agent-session :sessions sid :sc-session-id]` |
| `:started-at` | Move per-session or drop |
| `:nrepl-runtime-atom` | Already seeded into atom; remove from ctx |

## Ambiguous Keys: Per-Session or Process-Scoped?

These keys sit at the atom root. Correct scope should be resolved but
does not block the core migration.

### `:telemetry`

Tool-output-stats, tool-call-attempts, tool-lifecycle-events,
provider-requests, provider-replies.

**Assessment:** Per-session. Tool calls and provider requests are
session-scoped. Process-level aggregation is a read-time concern.

**Recommendation:** Move to `[:agent-session :sessions sid :telemetry]`.

### `:persistence`

Journal and flush-state.

**Assessment:** Per-session. Each session has its own journal file.

**Recommendation:** Move to `[:agent-session :sessions sid :persistence]`.

### `:turn`

Currently `{:ctx nil}`. Holds ctx reference during a turn.

**Assessment:** Session-turn concept. May be eliminable by the service
surface change.

**Recommendation:** Flag for elimination. Move per-session if needed
temporarily.

### `:background-jobs`

Job store.

**Assessment:** Jobs are created per-session, but may outlive session
teardown.

**Recommendation:** Keep at root with explicit `:session-id` per job.
Revisit when session lifecycle is defined.

## Service Surface

New namespace: `psi.agent-session.service`

```clojure
(query   session-id eql)
(mutate! session-id op params)
(prompt! session-id text)
(abort!  session-id)
(steer!  session-id text)
```

These are the only entry points for TUI, extensions, and RPC. Session-id
selects. The service routes. Consumer never sees ctx.

Internally, these resolve the session entry from the atom and delegate to
existing `*-in!` functions which gain a `session-id` parameter. The
`active-session-id` provides a default when callers don't specify.

## What Disappears

| Artifact | Reason |
|----------|--------|
| `context-index.clj` | Subsumed by `:agent-session :sessions` |
| `ensure-session-loaded-in!` time-sharing | Sessions always resident |
| `with-target-session!` in rpc.clj | Consumers use session-id |
| `session-route-lock` in rpc.clj | No single-slot contention |
| `global-ctx` singleton in core.clj | Service fns replace it |

## Accessor Pattern Change

```clojure
;; before
(defn get-session-data-in [ctx]
  (get-in @(:state* ctx) [:agent-session :data]))

;; after
(defn get-session-data-in [ctx session-id]
  (get-in @(:state* ctx) [:agent-session :sessions session-id :data]))
```

Default resolution:

```clojure
(defn resolve-session-id [ctx explicit-sid]
  (or explicit-sid
      (get-in @(:state* ctx) [:agent-session :active-session-id])))
```

## Migration Steps

Each step leaves the system working.

### Step 1 — Restructure the atom shape

Migrate atom from current shape to target shape. Update all accessors in
`session-state.clj` to use new paths. Add `session-id` parameters with
default fallback to `active-session-id`. Behavior unchanged — still one
session at a time.

### Step 2 — Move :agent-ctx and :sc-session-id into the atom

Remove from ctx. Store/retrieve via atom under
`[:agent-session :sessions sid]`. ~65 references across 13 files.
Single commit to avoid half-migrated state.

### Step 3 — Introduce the service surface

Add `psi.agent-session.service`. Do not yet change callers.

### Step 4 — Migrate RPC to session-id

Replace `with-target-session!` and `session-route-lock` with service
calls.

### Step 5 — Migrate TUI and extensions to session-id

Update call sites to use service surface.

### Step 6 — Remove global-ctx from core.clj

Process startup still builds ctx internally; it is no longer exported.

### Step 7 — Resolve ambiguous keys

Migrate `:telemetry` and `:persistence` per-session. Evaluate `:turn`
for elimination. Finalize `:background-jobs` scoping.

## Risks and Open Questions

**Concurrency.** Multiple sessions writing to the atom concurrently
requires all updates be pure swap! transforms with no
read-modify-write races. Audit `dispatch_effects.clj` before Step 2.

**Session lifecycle.** What triggers creation, suspension, teardown?
Without bounds, session entries accumulate. Define before Step 4.

**Persistence and journal.** Per-session journals need session-keyed
file paths. Existing journal files need migration or tolerant loading.

**Statechart env sharing.** Verify the statechart library supports
multiple session instances in one env before committing Step 2.

**Background jobs and teardown.** Jobs referencing removed sessions
need a defined policy.

**active-session-id fallback.** Compatibility bridge, not permanent.
Remove once all callers are session-id-aware.

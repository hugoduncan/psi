# Plan

Ordered steps toward AI COMPLETE.

---

## Done

### Step 1 — Split allium specs  ✓
- `spec/session-management.allium`
- `spec/extension-system.allium`
- `spec/compaction.allium`

### Step 2 — Implement `agent-session` component  ✓
- 10 namespaces: core, statechart, session, compaction, extensions, persistence,
  resolvers, tools, executor, main
- 139 tests, 509 assertions, 0 failures

### Step 3 — Runnable entry point  ✓
- `executor.clj` bridges ai streaming → agent-core loop protocol
- `tools.clj` implements read/bash/edit/write
- `main.clj` provides interactive REPL prompt loop
- `:run` alias in root `deps.edn`

### Step 4 — Wire agent-session into global query graph  ✓
- `agent-session.core`: `register-resolvers!` / `register-resolvers-in!`
- `introspection.core`: `create-context {:agent-session-ctx …}`,
  `query-agent-session-in`, single-rebuild registration
- `main.clj`: calls `register-resolvers!` at startup

---

### Step 5 — Fix TUI session input  ✓
- Replaced custom ProcessTerminal + differential renderer with charm.clj
- charm.clj uses JLine3 (FFM) for correct raw mode + input + rendering
- `psi.tui.app`: Elm Architecture (init/update/view)
- Agent integration via `LinkedBlockingQueue` + poll command
- Patched charm.clj JLine compat bug (`bind-from-capability!`)
- JLine smoke test catches API compat issues
- 161 tests, 561 assertions, 0 failures

### Step 6 — Statechart-driven tool calling  ✓
- `turn_statechart.clj`: per-turn statechart definition, context, events, queries
- States: idle → text-accumulating ⇄ tool-accumulating → done | error
- `executor.clj`: `make-turn-actions` bridges agent-core lifecycle → statechart events
- EQL resolver for `:psi.turn/*` attributes (state, text, tool-calls, error)
- Wired through session context via `:turn-ctx-atom`, observable from nREPL
- 18 tests, 76 assertions covering statechart + executor
- 179 tests, 637 assertions, 0 failures total

### Step 6b — Extension UI points  ✓
- `spec/ui-extension-points.allium`: spec for dialogs, widgets, status, notifications, renderers
- `psi.tui.extension-ui`: 469-line implementation in tui component
  - Promise bridge: blocking dialogs for extensions, resolved by TUI update loop
  - Dialog queue: FIFO, one active at a time
  - Widgets: keyed by `[ext-id widget-id]`, above/below editor placement
  - Status: single-line footer entries per extension
  - Notifications: auto-dismiss, overflow cap, level-based styling
  - Render registry: tool renderers + message renderers
  - UI context map: `:ui` key in extension API (nil when headless)
  - EQL resolver: 8 `:psi.ui/*` attributes (read-only introspection)
- Wired through: `extensions.clj` → `:ui` in API, `core.clj` → `:ui-state-atom` in ctx
- TUI integration: `app.clj` renders dialogs, widgets, status, notifications
- 13 tests, 104 assertions covering UI state, dialogs, queue, renderers
- 251 tests, 1070 assertions, 0 failures total

---

## Next

### Step 7 — Graph emergence  ✓
- Spec: `spec/graph-emergence.allium`
- All 4 required domains registered: `ai`, `history`, `agent-session`, `introspection`
- `query-graph-bridge` resolver extended to expose all 9 required Step 7 EQL attrs:
  - `:psi.graph/resolver-count`, `:psi.graph/mutation-count`
  - `:psi.graph/resolver-syms`, `:psi.graph/mutation-syms`, `:psi.graph/env-built`
  - `:psi.graph/nodes`, `:psi.graph/edges`, `:psi.graph/capabilities`, `:psi.graph/domain-coverage`
- `resolvers_test.clj`: 10 new graph bridge tests (16 total, 41 assertions)
- 629 tests, 2924 assertions, 0 failures

### Step 7a — Session introspection hardening  ✓
- Added `:psi.agent-session/messages-count`, `:psi.agent-session/tool-call-count`, `:psi.agent-session/start-time`, `:psi.agent-session/current-time` as top-level resolvers
- `:started-at` captured at `create-context` time (Instant)
- `resolvers_test.clj`: 6 tests, 17 assertions — direct EQL query success for each attr
- Documented canonical query path in STATE.md

8. Step 8 — RPC surface
   - Spec: `spec/rpc.allium` (TBD)
   - EDN stdio protocol for headless / programmatic control (including Emacs frontend parity)
   - Partial impl: `agent-session/rpc.clj` (821 lines), `rpc_test.clj` (12 tests)
   - Complete: handshake, ping, query_eql, prompt, steer, follow_up, abort, new_session, switch_session, fork

9. Step 9 — Memory layer
   - Spec: `spec/memory-layer.allium`
   - Partial impl: `memory/` component (1021 lines src, 29 tests)
   - Complete: remember/recover lifecycle, graph snapshots + deltas, provenance, EQL surface

10. Step 10 — Feed-forward recursion
    - Spec: `spec/feed-forward-recursion.allium`
    - Partial impl: `recursion/` component (1682 lines src, 73 tests)
    - Complete: FUTURE_STATE synthesis, plan proposal, approval gate, execution, verification, learning cycle

11. Step 11 — HTTP API
    - openapi spec + martian client, surface via Pathom mutations
    - Deferred from Step 8 — depends on RPC, memory, and feed-forward being stable

12. AI COMPLETE

### Deferred (agent-session)
- `TreeNavigated` branch tree navigation
- Session persistence to disk (journal currently in-memory only)
  - Spec: `spec/session-persistence.allium` ✓
  - `persistence.clj`: NDEDN write (lazy flush, bulk + append), read, migration, listing ✓
  - `core.clj`: `resume-session-in!` loads from disk, `new-session-in!` creates file ✓
  - Session directory layout + discovery + listing ✓
  - 70 new assertions, 286 total, 0 failures ✓
- `SkillCommandExpanded` / `PromptTemplateExpanded` events
- Streaming token printing during TUI session
- Extension UI: dialog timeouts, widget ordering, theme maps for renderers,
  editor text injection, working message override (see spec open questions)
- Extension UI: screen takeover / custom component injection

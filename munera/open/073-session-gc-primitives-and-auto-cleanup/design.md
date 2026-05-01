Goal: provide core session enumeration and tree-close primitives, then use them so extensions (starting with auto-session-name) clean up after themselves instead of accumulating child sessions indefinitely.

Context:
- Sessions live in `[:agent-session :sessions sid :data]` in the root state atom.
- Child sessions store `:parent-session-id` pointing to the parent; there is no reverse index.
- `session_close/close-session-in!` detaches a single session from runtime: cancels owned schedules, interrupts timer handles, removes the session slot, emits a context-changed projection. It does not recurse into children.
- Persistence files are preserved; close is runtime detachment, not deletion.
- `list-context-sessions-in` returns all sessions with their parent info, but there is no dedicated `children-of` or `descendants-of` query.
- auto-session-name creates a child session per rename checkpoint (helper session for title inference via a cheap model). These accumulate unboundedly — one child per two turns across every top-level session.
- auto-session-name is an extension; nothing in agent-session core should reference it.
- The extension API exposes `(:mutate api)` and `(:mutate-session api)` for extensions to call core mutations.
- No mutation currently exists for closing a session or closing a session tree via the mutation/extension API surface.

Problem:
- Child sessions created by extensions (auto-session-name, workflow steps, delegate runs) accumulate in memory with no cleanup path.
- There is no way to remove a top-level session and its descendants in one operation.
- The core provides no `children-of` enumeration, so even an extension that wanted to clean up would have to reach into internal state.
- The extension mutation API has no close/remove operation, so extensions cannot trigger session cleanup through the sanctioned API surface.

Required behavior:

Layer 1 — Core primitives (agent-session component):

- `children-of-in`: given ctx and a session-id, return a vec of session-ids whose `:parent-session-id` equals the given id. Pure scan of the sessions map.
- `descendants-of-in`: transitive closure of `children-of-in`, returned in bottom-up order (leaves first) suitable for safe sequential close.
- `close-session-tree-in!`: close a session and all its descendants. Calls `close-session-in!` on each descendant in bottom-up order, then on the root. Returns `{:closed-count N :closed-session-ids [...]}`.
- New mutations exposed on the extension API surface:
  - `psi.extension/close-session` — close a single session by id (wraps `close-session-in!`).
  - `psi.extension/close-session-tree` — close a session and all descendants (wraps `close-session-tree-in!`).

Layer 2 — Extension self-cleanup (auto-session-name extension):

- After each rename checkpoint completes (success or failure), the extension closes the helper child session it just created via `(:mutate api) 'psi.extension/close-session {:session-id child-sid}`.
- The extension's local `:helper-session-ids` tracking set is updated to remove closed ids.
- No accumulation of helper sessions across the lifetime of a context.

Scope boundaries:
- Periodic/threshold-based GC orchestration is out of scope — premature until more extensions show accumulation patterns.
- Persistence file cleanup is out of scope — close remains runtime detachment.
- Workflow child session cleanup is out of scope for this task — the primitives enable it but the policy belongs to workflow-loader and is a separate concern.
- No resolver changes needed — `list-context-sessions-in` already returns parent info.

Acceptance:
- `children-of-in` returns direct child session-ids for a given parent.
- `descendants-of-in` returns the full subtree in bottom-up (leaf-first) order.
- `close-session-tree-in!` closes all descendants then the root, returning the count and ids of closed sessions.
- `psi.extension/close-session` and `psi.extension/close-session-tree` mutations are registered and callable through the extension API.
- auto-session-name closes its helper session after each checkpoint; helper sessions do not accumulate.
- Existing `close-session-in!` behavior is preserved — the new tree function composes it, does not replace it.
- Closing a session that has no children via `close-session-tree-in!` behaves identically to `close-session-in!`.
- Tests cover: children-of enumeration, descendants-of ordering, tree close with nested children, mutation API integration, auto-session-name cleanup.

Architecture alignment:
- Follows existing pattern: pure query functions in `session_state.clj`, effectful close in `session_close.clj`, mutations in `mutations/session.clj`.
- Extension cleanup uses the sanctioned mutation API (`(:mutate api)`), not internal state access.
- No new dispatch event types needed — `close-session-in!` already dispatches `:session/context-closed`.

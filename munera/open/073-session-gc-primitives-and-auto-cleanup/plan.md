Approach:
- Build bottom-up: enumeration primitives → close hardening → tree close → mutations → extension cleanup.
- Each step is independently testable and committable.
- Core changes are in `session_state.clj` (pure queries) and `session_close.clj` (effectful close), following existing module boundaries.
- Mutations go in `mutations/session.clj` alongside existing session mutations.
- Extension change is isolated to `auto_session_name.clj` and uses only the sanctioned mutation API.

Steps:

1. Add `children-of-in` and `descendants-of-in` to `session_state.clj`
   - `children-of-in` scans sessions map for matching `:parent-session-id`
   - `descendants-of-in` builds transitive closure in bottom-up (leaf-first) order
   - Test: empty children, single child, multi-level tree, bottom-up ordering

2. Harden `close-session-in!` in `session_close.clj`
   - Make idempotent: return `{:closed? false :session-id sid}` when session-id not found instead of throwing
   - Add statechart working memory cleanup via `sp/delete-working-memory!` using the session's `sc-session-id` (read before removing session state)
   - Document no-phase-guard in docstring
   - Test: idempotent close of missing session, statechart cleanup on close, existing close behavior preserved

3. Add `close-session-tree-in!` to `session_close.clj`
   - Uses `descendants-of-in` to get bottom-up ordered list
   - Calls `close-session-in!` on each descendant then on the root
   - Returns `{:closed-count N :closed-session-ids [...]}`
   - Test: tree close with nested children, leaf-only session (behaves like single close), already-closed descendants handled by idempotency

4. Add `psi.extension/close-session` and `psi.extension/close-session-tree` mutations to `mutations/session.clj`
   - `close-session` wraps `close-session-in!` via `core/close-session-in!`
   - `close-session-tree` wraps `close-session-tree-in!` via a new `core/close-session-tree-in!` passthrough
   - Output attrs as specified in design
   - Register both in `all-mutations`
   - Neither mutation is added to `session-scoped-extension-mutation-ops` — callers must pass explicit `:session-id` targeting the session to close (not the calling session)
   - Test: mutation callable through extension API surface, including with a target session-id that differs from the calling session

5. Wire auto-session-name extension cleanup
   - In `infer-session-title`, after the child session completes (success or failure), close it via `(:mutate api) 'psi.extension/close-session {:session-id child-sid}`
   - Only when `child-session-id` is non-nil (not on early returns)
   - Remove closed id from `:helper-session-ids` tracking set
   - Test: helper session is closed after checkpoint, no accumulation over multiple checkpoints, early-return checkpoint does not attempt close

Risks:
- Statechart working memory deletion on a session that never started a statechart (defensive: check `sc-session-id` before deleting)
- Concurrent close during streaming (documented as caller's responsibility; auto-session-name helpers are idle when closed)
- Tree close emitting N projections causing UI flicker (acceptable for now; child sessions are not UI-visible)

Plan:
- inspect current `agent-session` namespaces for session-shaped vs prompt/turn/workflow/tool ownership
- choose the smallest coherent first-cut `session-state` public API
- extract the lowest-risk authoritative helpers first: session lookup/update, lifecycle/state shape, tree/worktree invariants
- split mixed namespaces only where necessary to keep the new boundary clean
- migrate the core `agent-session` boundary-establishing consumers first (`session_lifecycle`, session resolvers, and relevant session dispatch handlers), then repoint at least one real lower-level non-`agent-session` consumer path to prove the boundary is practical before widening the move
- move focused tests with the extracted ownership, then rerun focused and full verification

Decisions to preserve while implementing:
- keep `state-kernel` as the sole generic dispatch substrate owner
- keep `system-bootstrap` as the sole whole-system registration owner
- do not extract prompt/turn semantics in this task
- prefer a narrower but clean `session-state` component over an over-broad move

Risks:
- mixed namespaces may hide prompt/workflow/tool assumptions inside apparently session-shaped helpers
- child-session initialization may need a pre-move split because prompt-state derivation currently lives inside an otherwise pure-looking state initializer
- scheduler ownership may be partially session-state and partially higher-level orchestration; move only the clearly lower-level part

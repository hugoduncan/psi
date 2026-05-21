# Plan

## Approach
1. Reproduce the live TUI `/resume` discovery failure through the tmux harness and/or the smallest launcher-bound seam that still exercises persisted-session discovery for the current worktree.
2. Trace the end-to-end discovery path:
   - launcher/worktree resolution
   - effective cwd/worktree-path seen by the TUI runtime
   - persisted session root and session-dir derivation
   - persisted-session listing for the active worktree
   - selector population/rendering
3. Separate already-fixed resume-state issues from the still-open live discovery problem.
4. Implement the smallest fix at the authoritative boundary.
5. Re-enable the quarantined tmux rehydration scenario once stable.

## Current status at takeover
- Completed adjacent fix: resumed session timestamp canonicalization to `java.time.Instant`, including `/resume` crash repair and regression coverage (`609aa51d`).
- Remaining task scope is the live TUI `/resume` discovery path, not resume transcript hydration semantics.
- The task currently has only `design.md`; execution files are being created by this takeover.

## Design choices
- Prefer fixing authoritative worktree/session discovery boundaries over adding UI-only fallbacks.
- Keep transcript rehydration concerns separate from selector discovery concerns.
- Re-enable the tmux scenario only after the underlying discovery path is reliable, not by weakening assertions.

## Risks
- The failure may depend on tmux launch cwd vs worktree-path divergence.
- The discovery issue may involve multiple path normalizations across launcher, runtime, and session-journal store.
- The quarantined scenario may expose additional timing issues after discovery is fixed.

## Verification
- Focused unit tests for any discovered path/session-dir invariants.
- Existing RPC/TUI selector tests still pass.
- Re-enabled tmux rehydration scenario passes in environments where tmux is available.

# Plan

## Approach

Implement workflow-run retention as a post-terminal-transition cleanup pass owned by the workflow runtime / agent-session integration layer where run terminal status changes are already recorded.

Key decisions:

- Treat the retained terminal status set as exactly `:completed`, `:failed`, and `:cancelled`, matching `design.md`.
- Read the effective retention count from runtime context config at `[:config :completed-workflow-run-retention-count]`, defaulting to `1` when absent.
- Reject negative configured counts at the configuration boundary before retention cleanup executes.
- Trigger cleanup immediately when a workflow run transitions into a retained terminal status, so retention remains deterministic and does not depend on later background sweeps.
- Determine removal candidates per originating agent session by ordering only retained terminal runs by terminal transition time, newest first, and when `:finished-at` ties occur break them by canonical workflow run creation order from `[:workflows :run-order]` so later-created runs are treated as newer; then drop any runs beyond the effective retention count.
- Remove workflow runs through canonical workflow-run removal semantics rather than ad hoc state editing.
- For each removed run, derive the authoritative linked workflow-owned root sessions from the run data itself as the deduplicated union of attempt `:execution-session-id` and `:judge-session-id` values.
- Align higher workflow-run read/introspection projections to that same authoritative linked-session set by adding or updating a canonical linked-session id projection instead of leaving execution-only ids as the sole surface.
- Remove linked workflow-owned sessions with session-tree close semantics for each linked root, skipping missing, already-closed, or non-`workflow-owned?` sessions.
- Keep the cleanup narrowly scoped: never infer cleanup targets from ancestry alone, never touch the originating parent session, and never remove non-terminal runs.
- Prove behavior with focused workflow runtime / agent-session integration tests that cover retention ordering, defaulting, zero retention, per-originating-session isolation, multi-linked-session cleanup, subtree cleanup, linked-session projection alignment, and negative-config rejection.

Likely implementation seam:

- Add small workflow-retention helper(s) that:
  - compute the effective retention count
  - identify retained terminal runs for one originating session in canonical keep/remove order
  - derive linked workflow-owned session roots from a removed run
  - apply workflow-run removal plus session-tree cleanup
- Wire those helpers into the workflow terminalization path after the newly terminal run is recorded.

## Risks

- The runtime may not already persist an explicit terminal transition timestamp distinct from generic update ordering; if so, implementation must choose the existing authoritative completion-time surface or add the minimal timestamp/data needed to preserve deterministic newest-first retention.
- Terminalization can happen through more than one code path (complete, fail, cancel, resume-to-terminal); cleanup must be attached at the canonical shared terminal transition seam, not duplicated incompletely.
- Session-tree cleanup must use existing close-tree semantics correctly so removed workflow-owned descendants disappear without accidentally closing the originating parent or unrelated sibling sessions.
- Some introspection/listing tests may currently assume historical workflow runs or child sessions remain present forever; those proofs may need focused updates even though user-facing behavior is consistent with the new design.
- Negative retention validation must happen at a boundary that is exercised consistently by runtime creation/execution paths, otherwise invalid config could leak deeper into cleanup logic.

## Slice order

1. Retention configuration and canonical terminal-retention helpers
   - Add effective retention-count lookup/defaulting and negative-value rejection.
   - Add retained-terminal status predicate and per-originating-session keep/remove ordering helper based on terminal transition time, using canonical workflow run creation order as the deterministic `:finished-at` tie-breaker.

2. Cleanup execution on terminal transition
   - Hook retention evaluation into the canonical workflow-run terminal transition path.
   - Remove older retained terminal runs beyond the configured count for that originating session.

3. Linked workflow-owned session subtree cleanup
   - Derive deduplicated linked execution/judge session roots from each removed run.
   - Tree-close workflow-owned linked roots while skipping missing/non-workflow-owned roots.

4. Focused proof and surface verification
   - Add focused tests for default retention, explicit retention `2`, explicit retention `0`, non-terminal immunity, per-originating-session isolation, multi-linked-session cleanup, subtree cleanup, canonical linked-session projection alignment, and negative-config rejection.
   - Update any affected introspection/listing proofs if current expectations assume indefinite retention or currently expose execution-only linked-session ids.

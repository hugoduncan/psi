# 058 — Plan

## Approach

Start with consumer inventory, then migrate bottom-up. Do not delete compatibility code first. First prove which callers still depend on it, migrate those callers, then remove the layer in one final convergence step.

## Steps

1. Inventory remaining compatibility consumers
   - `workflow_statechart_compat`
   - `workflow_progression` legacy control helpers
   - compatibility-only tests
2. Classify each consumer
   - runtime
   - query/mutation
   - test-only
   - documentation-only
3. Migrate shallow/test-only consumers first
4. Migrate runtime consumers next
5. Remove dead compatibility surfaces
6. Run focused workflow tests, full unit suite, then full `bb test`
7. Update docs/task notes with final authoritative workflow surfaces

## Risks

- hidden callers may still depend on sequential/status-tracker semantics
- test migration may reveal implicit assumptions about old progression ownership
- removing compatibility code too early could produce broad compile/load fallout

## Decision rule

If a compatibility surface still has a real structural consumer after migration attempts, stop and document why. Do not replace one compatibility layer with a differently named compatibility layer.

# Plan

## Approach

Purely mechanical refactor in `init.clj` plus documentation in `child_session_state.clj`. No behavioural change — the inline `select-keys` vectors become compositions of shared named constants, and existing tests must pass without modification.

## Order

1. **Define constants** — add the three named constants (`common-inherited-fields`, `prompt-state-fields`, `model-identity-fields`) with classification docstrings in `init.clj`
2. **Rewrite lifecycle functions** — replace each inline `select-keys` vector with a composition of the constants
3. **Verify equivalence** — confirm the composed vectors produce exactly the same key sets as the originals
4. **Document child-session** — add a classification comment to `child_session_state.clj` covering the relationship to all three constant groups
5. **Test** — `bb test` with no modifications to tests

## Risks

- **Low**: constant composition order could introduce subtle key duplication. Mitigation: `into` on vectors is fine — `select-keys` tolerates duplicate keys harmlessly. Verify via REPL that composed sets match originals exactly.

## Decisions

- Constants go in `init.clj` rather than a new file — there are only 3 small defs, and `init.clj` is the authoritative owner of the lifecycle functions that consume them. A separate `inheritance.clj` would add a file for minimal benefit.
- Constants are `^:private` — no external consumer should couple to the inheritance set.
- Child-session gets documentation only, not code changes — it constructs fields explicitly with per-field logic (fallbacks, derivation, opts), so `select-keys` composition doesn't fit.

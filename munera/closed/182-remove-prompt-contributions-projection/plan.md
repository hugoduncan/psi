# Plan

## Approach

Straight removal — no new APIs or derivation functions needed. The derivation path (`prompt-storage/list-contributions`) already exists and all consumers already use it. This task only removes the redundant persistence of the derived vector.

The change is mechanical: remove the field from schema/defaults, stop writing it at 4 handler sites and all lifecycle/init/test-helper sites listed in the design, and update tests.

## Order

Bottom-up: schema/model first, then lifecycle, then handlers, then child-session, then test helper, then test updates.

1. **Schema and defaults** — remove `:prompt-contributions` from `model.clj` (schema + `initial-session`)
2. **Lifecycle** — remove from `select-keys` in `init.clj` (new/resume/fork)
3. **Prompt handlers** — remove `assoc-in ... :prompt-contributions` from the 4 mutation handlers in `prompt_handlers.clj`
4. **Child-session** — remove `:prompt-contributions` persistence from `child_session_state.clj`
5. **Test helper** — remove `:prompt-contributions []` seed from `nullable_api.clj`
6. **Tests** — update tests that assert on `:prompt-contributions` presence in session state
7. **Verify** — `bb test`, confirm no regressions

## Risks

- **Low**: some test may assert the exact shape of session data including `:prompt-contributions`. Mitigation: grep tests for the key and update assertions.
- **Low**: persisted session files from before this change contain `:prompt-contributions`. Mitigation: resume reads `:prompt-contribution-ids` (authoritative); the extra key in persisted data is harmlessly ignored by `select-keys`.

## Decisions

- Remove in one pass rather than a deprecation period — the field has zero read sites in production code.
- No new derivation API needed — `prompt-storage/list-contributions` already serves this role.

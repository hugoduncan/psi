# 180 — Plan

## Approach

Bottom-up: introduce the derivation API first, then migrate consumers layer by layer (leaf → root), then remove the fields from schema/lifecycle. Each slice is independently committable and testable.

## Ordering rationale

1. **Derivation API first** — all consumer migrations depend on `resolve-tool-defs` existing.
2. **Session-state read sites** — these are internal to a single handler/function; migrating them is safe and doesn't change any public contract.
3. **Dispatch event/contract fields** — changing these affects multiple callers; done after session-state reads to reduce surface area of concurrent changes.
4. **Schema/lifecycle/helper removal** — final cleanup once no code references the fields.

## Decisions

- `resolve-tool-defs` goes in `psi.tool-registry.defs` (co-located with `normalize-tool-defs` and `tool-authority-fields`).
- Pure function: caller provides tool-source. No atom access.
- Each consumer migration is verified by running existing tests — no new tests needed for migrations that preserve behaviour, only for the new `resolve-tool-defs` function.
- `tool-authority-fields` is removed last (after all callers are migrated to `assoc :tool-ids` directly).

## Risks

| Risk | Mitigation |
|------|------------|
| Runtime agent data not available at all call sites | Audit each call site for access to ctx/agent-data; thread through where missing |
| Step-config chain breakage | Step-config continues to output `:tool-defs` (maps) — downstream untouched |
| Workflow child-session contract is a malli schema used at runtime | Update schema and all callers atomically in one commit |
| Extension code (`auto_session_name.clj`) may have more callers | grep confirms single site; extension tests will catch others |

## Deferred

- Registering base tools in tool-registry (prerequisite for removing runtime agent data as tool-source).
- Migrating `build-system-prompt` parameter name from `:tool-defs` to something else (cosmetic; function accepts derived maps which remain valid).

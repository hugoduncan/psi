# Plan

## Approach

Introduce singular session-owned tool authority as exact-name membership in `:tool-ids`, then treat `:tool-defs` as a derived execution/compatibility projection materialized from canonical tool-registry definitions in `:tool-ids` order.

## Why this shape

- Current direct mutation authority is already exact-name based: `:session/set-active-tools` derives session state from tool names embedded in the provided tool maps.
- Current child-session and prompt narrowing semantics are allowlist/exact-name based, not richer per-tool configuration. Workflow step `:session :tools` resolves either inline maps or exact tool names, but the session-owned persisted authority needed by this slice is only the exact-name membership of registry-backed tools.
- A richer persisted selection structure would prematurely pull workflow narrowing concerns from task `178` follow-on B into this authority-introduction slice.

## Decisions

1. `:tool-ids` is the new authoritative persisted session field for tool availability.
2. `:tool-ids` is an ordered vector of exact tool names. Order is preserved from the authorizing mutation/request and becomes the canonical derivation order for `:tool-defs`.
3. `:tool-defs` remains persisted temporarily, but only as derived execution/compatibility payload for seams that still consume concrete tool maps.
4. Existing session `:active-tools` is no longer an authority field. During this slice it is retained only as a derived compatibility projection equal to `(set :tool-ids)` for seams that still read active-tool membership.
5. This slice updates direct authority-setting seams now, but defers broader workflow/lifecycle inheritance contract migration from `:tool-defs` inputs to `:tool-ids` inputs unless a minimal coherence note is required.

## Direct seams to align in this slice

- `:session/set-active-tools` handler becomes authority-first: normalize incoming tool maps, derive `:tool-ids`, persist `:tool-ids`, derive/persist compatibility `:active-tools`, then derive/persist `:tool-defs`.
- `:session/add-tool` handler must also derive/persist `:tool-ids` (and derived `:tool-defs`/`:active-tools`) so that adding a tool through this path does not bypass the new authority field. Currently it only dispatches `:runtime/agent-set-tools` without updating session state fields.
- Session model/init/default state must include `:tool-ids` so authority is explicit in canonical session data.
- All three lifecycle paths in `session_state/init.clj` (`initialize-new-session-state`, `initialize-resumed-session-state`, `initialize-forked-session-state`) must add `:tool-ids` to their `select-keys` baseline field-copy sets.
- Child-session state derivation must treat parent/child `:tool-defs` inputs as compatibility only: absent child override inherits parent `:tool-ids` first, while explicit child `:tool-defs` overrides normalize into child `:tool-ids` before any persisted compatibility projection.
- Task artifacts should name workflow child-session creation, child-session state derivation, prompt rebuild, and runtime refresh seams as still temporarily compatible with derived `:tool-defs`, not authoritative.

## Authority-feeding seams to verify (indirect, flow through `:session/set-active-tools`)

- `refresh-active-tools-in!` in `extension_runtime.clj:112` — merges runtime+registry tools and dispatches `:session/set-active-tools`. Once the handler is authority-first, this path inherits correct behavior. Verify during implementation.
- `refresh-active-tools!` in `workflow/bootstrap.clj:28` — same pattern as above. Verify during implementation.

## `:active-tools` status

`:active-tools` is ephemeral runtime state, not a persisted schema field. It does not appear in `agent-session-schema` or in any lifecycle `select-keys` set. This slice does not promote it to the schema. It remains a derived compatibility projection that is nil after resume until the next `:session/set-active-tools` dispatch.

## Artifact updates completed in this design follow-up

- Added missing `steps.md` so the task now has bounded implementation decomposition.
- Resolved the authority-field decision to ordered exact-name `:tool-ids`.
- Reconciled `:active-tools` as derived compatibility-only state.
- Defined the interim parent→child compatibility fallback so later implementation can normalize all child authority paths back through `:tool-ids`.

## Deferred to the next follow-on slice

- Changing workflow child-session contract inputs from `:tool-defs` to `:tool-ids` or a separate narrowing shape.
- Reworking `workflow-step-session-config` to derive child tool payloads from parent authority plus workflow selection first.
- Removing persisted compatibility `:tool-defs` and `:active-tools` after downstream consumers no longer require them.

## Verification intent

This pass is design-only follow-up execution. No implementation `steps.md` items are executed here.
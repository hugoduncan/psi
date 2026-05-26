# Reload fixup inventory

Inspected against the current `reload-code` implementation and long-lived runtime surfaces.

| Namespace / owner | Component / layer | Surviving in-memory surface | Stale retained value across reload | Status | Severity | Canonical symptom if unfixed | Preferred fixup owner / path |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `psi.agent-session.resolvers` | agent-session / query | defonce cached Pathom env `query-env` | env snapshot built from old resolver vars | implemented | breaks-psi | queries keep using stale resolver fns after successful reload | `psi.agent-session.psi-tool/refresh-query-runtime!` → `session-resolvers/invalidate-query-env!` |
| `psi.agent-core.core` | agent-core / query | defonce cached Pathom env `query-env` | env snapshot built from old resolver vars | implemented | breaks-psi | agent-core introspection continues through stale resolver graph | `psi.agent-session.psi-tool/refresh-query-runtime!` → `agent/invalidate-query-env!` |
| `psi.agent-session.mutations` via ctx `:all-mutations-atom` | agent-session / mutation runtime | per-ctx mutable mutation snapshot | old mutation vector remains in extension EQL + tool-plan qctx | already-fixed | breaks-psi | new or changed mutations invisible after reload | `psi.agent-session.psi-tool/refresh-all-mutations!` |
| `psi.state-kernel.dispatch` + `psi.agent-session.dispatch-handlers.*` | state-kernel / dispatch | defonce handler registry | old handler fn values registered during context creation | implemented | breaks-psi | dispatch routes to stale handler implementations after reload | `psi.agent-session.psi-tool/refresh-dispatch-handlers!` |
| live session agent tool state | agent-session / runtime agent | per-session agent-core `:tools` vector | tool defs assembled before reload remain active in running session | already-fixed | degrades-behavior | runtime uses stale tool metadata/handlers until session restart or manual reset | `psi.agent-session.psi-tool/refresh-live-tool-defs!` |
| built-in workflow runtime state (`psi.agent-session.workflow.runtime-state`) | agent-session / built-in workflow | defonce runtime `state`, loaded definitions, API closures, command/tool registrations, prompt contribution callback | workflow runtime keeps old closures and bootstrap-owned surfaces | implemented | breaks-psi | `/delegate`, workflow prompt contribution, or workflow runtime calls point at stale code after reload | `psi.agent-session.psi-tool/maybe-refresh-built-in-workflow!` |
| extension registry on namespace-mode reload | agent-session / extensions | per-ctx extension registry | extension-owned runtime surfaces not rebuilt in namespace mode | not-needed for current mandatory safety | freshness-only | extension changes loaded by explicit namespace reload may not be reinstalled until worktree reload | preserve registry in namespace mode; use worktree reload for extension rediscovery |
| extension registry on worktree-mode reload | agent-session / extensions | per-ctx extension registry + install state | stale extension install/runtime surfaces | already-fixed | degrades-behavior | worktree reload would miss extension rediscovery/install reconciliation | `psi.agent-session.psi-tool/refresh-worktree-extensions!` |
| model registry (`psi.ai.model-registry`) | ai / models | defonce registry state | stale project/user model config after model namespace reload | already-fixed | degrades-behavior | changed models.edn or model loader code not reflected after reload | `psi.agent-session.psi-tool/reload-model-registry-step!` |
| session callback fn map assembled in `psi.agent-session.context/callback-fns` | agent-session / context assembly | per-ctx callback closures and direct fn refs | long-lived ctx map may still reference old implementations | partially addressed | degrades-behavior | some callbacks keep old behavior until ctx rebuild if not routed through explicit refresh owner | fix individual mandatory surfaces through explicit refresh steps; broader context rebuild out of scope |
| scheduler timer handles / projection listeners / executor pools | agent-session / runtime infra | per-ctx mutable handles | handles survive reload but do not inherently capture namespace vars needing refresh | not-needed | freshness-only | existing scheduled work continues on old already-created threads; not a known reload breaker | leave unchanged in this task |

## Mandatory fixups implemented in task 149

- invalidate cached query envs for `psi.agent-session.resolvers` and `psi.agent-core.core`
- re-register dispatch handlers into `psi.state-kernel.dispatch`
- reinitialize built-in workflow runtime state when already active

## Follow-on notes

- namespace-mode reload intentionally preserves extension registry state; this is acceptable for now because the task scope is reload safety, not full freshness for every extension-owned surface
- broader context-wide callback-map rebuilding remains a possible future simplification, but the current mandatory breakages are covered by narrower refresh owners

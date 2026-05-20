# 160 — Plan

## Approach

Direct replacement: the three mutation loops in `load-startup-resources-via-mutations-in!` each create a Pathom query context and call an EQL mutation that does nothing more than `dispatch/dispatch!` to the corresponding session event. We replace those loops with direct dispatch calls, eliminating the Pathom round-trip entirely.

The `:mutations` key in the startup-bootstrap summary schema and resolvers is vestigial provenance — it records *which mutations were used*. With direct dispatch, it has no meaning and is removed from the summary, the session-data schema, and the two resolvers that project it.

## Decisions

- **Rename** `load-startup-resources-via-mutations-in!` → `load-startup-resources-in!` to reflect the mechanism change. Keep the same signature and return shape so callers (`bootstrap-in!`) need only a call-site rename.
- **Remove** the `run-mutation-in!` helper — it becomes dead code.
- **Remove** `psi.agent-session.mutations` and `psi.query.core` from the bootstrap ns requires — they become unused.
- **`:mutations` key removal** cascades through: session-data schema, `bootstrap-in!` summary construction, `startup-bootstrap-resolver` (agent-session), `startup-bootstrap-summary` (introspection), and the introspection test.
- **`:origin :core`** for all replacement direct dispatch calls. The mutations currently pass `{:origin :mutations}`, but bootstrap is core infrastructure — `:origin :core` is consistent with the other direct dispatch calls already in `bootstrap-in!` (e.g. `:session/bootstrap-prompt-state`, `:session/refresh-system-prompt`, `:session/set-active-tools`).
- **Dispatch return values are intentionally discarded.** The current code uses `doseq` for template/skill/tool loops, discarding `run-mutation-in!` return values. The dispatch handlers return `{:return {:added? ... :count ...}}` but the final counts are read from session-data after all loops complete. The replacement direct dispatch calls also discard returns via `doseq`.
- **Extension-path loading** converts from mutation-mediated `add-extension` to direct `ext-rt/add-extension-in!`. The mutation is just a Pathom wrapper around `ext-rt/add-extension-in!` (no dispatch involved), so the replacement calls the same runtime function directly. Production passes `extension-paths []` so this path is untested through bootstrap but is converted for consistency.

## Risks

- **Extension init-var path** — already uses `ext/load-extension-init-in!` directly, not mutations. No change needed.
- **Summary shape** — `adopt-startup-plan-into-session!` in app-runtime merges summaries via `merge-startup-summary`. The `:mutations` key disappears from the base summary; the manifest summary never had it. No merge conflict.
- **Schema validation** — the `:startup-bootstrap` schema in `session_state/model.clj` has `:mutations` as a required key inside the maybe-map. Removing it could break validation if any persisted session data still has the key. The schema uses a closed `:map` — we make `:mutations` `:optional` in an intermediate step, then remove it. Given that `:startup-bootstrap` is `:optional` + `:maybe` itself, and sessions are ephemeral at this layer, direct removal is safe.

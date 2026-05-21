# 160 — Remove mutation-mediated bootstrap resource loading

## Intent

`load-startup-resources-via-mutations-in!` creates a throwaway Pathom query context, registers all resolvers and mutations into it, then calls `add-prompt-template`, `add-skill`, `add-tool`, and `add-extension` EQL mutations in loops. The template, skill, and tool mutations each simply dispatch a session event (`:session/register-prompt-template`, `:session/register-skill`, `:session/add-tool`) — a heavy indirection where bootstrap round-trips through the Pathom graph layer to perform what are direct dispatch calls. The `add-extension` mutation is different: it calls `ext-rt/add-extension-in!` directly (not dispatch), making it a Pathom round-trip to a direct runtime call rather than a dispatch round-trip.

## Goal

Replace the mutation-mediated resource loading with direct dispatch calls during session bootstrap. The EQL mutations remain available for external consumers (extensions, psi-tool mutate surface) — we only remove bootstrap's use of them as an unnecessary intermediary.

## Scope

### In scope

1. Replace the body of `load-startup-resources-via-mutations-in!` with direct `dispatch/dispatch!` calls for templates, skills, and tools
2. Replace the `extension-paths` loop with direct `ext-rt/add-extension-in!` calls — the `add-extension` mutation is just a Pathom wrapper around this same call, so the replacement is a direct runtime call (not dispatch). Production currently passes `extension-paths []`, so this path is untested through bootstrap, but the code path exists and should be converted for consistency.
3. Remove the throwaway query-context setup (`query/create-query-context`, `register-resolvers-in!`, `register-mutations-in!`) from bootstrap
4. Simplify the init-var extension loading path — it already uses `ext/load-extension-init-in!` directly, not mutations
5. Update or rename the function to reflect it no longer uses mutations
6. Update the summary map emitted by `bootstrap-in!` to drop the `:mutations` key (it listed mutation symbols as provenance; no longer applicable)
7. Update tests that assert on the mutation-mediated path or the `:mutations` summary key

### Out of scope

- Removing the EQL mutations themselves (`psi.extension/add-prompt-template`, `psi.extension/add-skill`, `psi.extension/add-tool`) — they serve external consumers
- Unifying the two-stage system prompt build in `adopt-startup-plan-into-session!` (follow-on)
- Collapsing the active-tool refresh/composition paths (follow-on)
- Changing `bootstrap-manifest-extensions-in!` — it already uses direct extension activation, not mutations

## Constraints

- The dispatch events `:session/register-prompt-template`, `:session/register-skill`, `:session/add-tool` are the authoritative state owners — they must be called, not bypassed
- Return shape of `bootstrap-in!` must remain compatible with its callers (`adopt-startup-plan-into-session!` in app-runtime, tests)
- Extension init-var loading via `ext/load-extension-init-in!` is already direct — preserve that path unchanged
- Extension-path loading converts from mutation-mediated `add-extension` to direct `ext-rt/add-extension-in!` — same runtime call the mutation wraps

## Acceptance criteria

1. `load-startup-resources-via-mutations-in!` (or its replacement) performs no Pathom query-context setup and calls no EQL mutations
2. Templates, skills, and tools are registered via direct `session/dispatch-in!` calls to the same event types the mutations dispatched
3. The `:mutations` key is removed from the bootstrap summary map
4. All existing bootstrap tests pass with no behavioural change in registered resources
5. Lint clean, focused test verification green

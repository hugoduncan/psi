# 160 — Remove mutation-mediated bootstrap resource loading

## Intent

`load-startup-resources-via-mutations-in!` creates a throwaway Pathom query context, registers all resolvers and mutations into it, then calls `add-prompt-template`, `add-skill`, and `add-tool` EQL mutations in loops — each of which simply dispatches a session event (`:session/register-prompt-template`, `:session/register-skill`, `:session/add-tool`). This is a heavy indirection: the bootstrap code round-trips through the entire Pathom graph layer to perform what are direct dispatch calls.

## Goal

Replace the mutation-mediated resource loading with direct dispatch calls during session bootstrap. The EQL mutations remain available for external consumers (extensions, psi-tool mutate surface) — we only remove bootstrap's use of them as an unnecessary intermediary.

## Scope

### In scope

1. Replace the body of `load-startup-resources-via-mutations-in!` with direct `dispatch/dispatch!` calls for templates, skills, and tools
2. Remove the throwaway query-context setup (`query/create-query-context`, `register-resolvers-in!`, `register-mutations-in!`) from bootstrap
3. Simplify the init-var extension loading path — it already uses `ext/load-extension-init-in!` directly, not mutations
4. Update or rename the function to reflect it no longer uses mutations
5. Update the summary map emitted by `bootstrap-in!` to drop the `:mutations` key (it listed mutation symbols as provenance; no longer applicable)
6. Update tests that assert on the mutation-mediated path or the `:mutations` summary key

### Out of scope

- Removing the EQL mutations themselves (`psi.extension/add-prompt-template`, `psi.extension/add-skill`, `psi.extension/add-tool`) — they serve external consumers
- Unifying the two-stage system prompt build in `adopt-startup-plan-into-session!` (follow-on)
- Collapsing the active-tool refresh/composition paths (follow-on)
- Changing `bootstrap-manifest-extensions-in!` — it already uses direct extension activation, not mutations

## Constraints

- The dispatch events `:session/register-prompt-template`, `:session/register-skill`, `:session/add-tool` are the authoritative state owners — they must be called, not bypassed
- Return shape of `bootstrap-in!` must remain compatible with its callers (`adopt-startup-plan-into-session!` in app-runtime, tests)
- Extension loading via `ext/load-extension-init-in!` is already direct — preserve that path unchanged

## Acceptance criteria

1. `load-startup-resources-via-mutations-in!` (or its replacement) performs no Pathom query-context setup and calls no EQL mutations
2. Templates, skills, and tools are registered via direct `dispatch/dispatch!` calls to the same event types the mutations dispatched
3. The `:mutations` key is removed from the bootstrap summary map
4. All existing bootstrap tests pass with no behavioural change in registered resources
5. Lint clean, focused test verification green

# 161 — Collapse startup prompt build and tool composition

## Intent

`adopt-startup-plan-into-session!` builds and persists the system prompt four times during startup (two builds, four persists), and has two independent tool-composition mechanisms. This task collapses both to single-pass operations.

## Why

Task 159 introduced the four-phase bootstrap split. Task 160 removed mutation-mediated resource loading. The remaining complexity in `adopt-startup-plan-into-session!` is redundant layering left over from those earlier shapes:

1. The system prompt is built twice and persisted four times:
   - BUILD #1 + PERSIST #1: `adopt-startup-plan-into-session!` builds `base-prompt` via `build-system-prompt` and calls `persist-system-prompt!` (`:session/set-system-prompt`)
   - PERSIST #2: `bootstrap-in!` dispatches `:session/bootstrap-prompt-state` — sets `:base-system-prompt` and `:system-prompt` to the same value (state mutation, no build, no prompt contributions applied)
   - PERSIST #3: `bootstrap-in!` dispatches `:session/refresh-system-prompt` — at this point no `:system-prompt-build-opts` exist in session data, so the handler falls back to re-persisting `:base-system-prompt` without calling `build-system-prompt` (a no-op re-persist, not a rebuild)
   - BUILD #2 + PERSIST #4: `finalize-startup-system-prompt!` rebuilds with graph-capabilities + refreshed tool-defs and persists via `:session/set-system-prompt` (the only persist that produces the correct final prompt)

   Only the last persist produces the correct prompt. The first three are wasted work.

2. Tool composition has two mechanisms:
   - `bootstrap-in!` has `refresh-active-tools-in!` (passed `false` to disable it)
   - `adopt-startup-plan-into-session!` does its own `merge-tool-defs-by-name` + `:session/set-active-tools` dispatch after manifest extension loading

   The `bootstrap-in!` path is dead code in the startup flow. Only the `adopt-startup-plan-into-session!` path runs.

3. The startup summary is persisted twice:
   - `bootstrap-in!` dispatches `:session/set-startup-bootstrap-summary` with its own summary
   - `adopt-startup-plan-into-session!` merges extension summary updates and dispatches the same event again, overwriting the first

## Problem statement

The current startup flow performs redundant prompt builds, redundant tool-set dispatches, and redundant summary persistence because `bootstrap-in!` was designed as a self-contained bootstrap that `adopt-startup-plan-into-session!` then partially overrides. Now that the four-phase split is established and mutations are removed, the boundary between these two functions can be simplified so each concern happens exactly once.

## Scope

### In scope

1. Eliminate redundant system-prompt builds — the prompt should be built and persisted once, after all inputs (graph-capabilities, extension tools, skills, prompt contributions) are known
2. Unify tool composition — one path that assembles the final tool set from base tools + extension tools
3. Collapse startup-summary persistence — one persist with the complete summary
4. Simplify the `bootstrap-in!` / `adopt-startup-plan-into-session!` boundary — inline `bootstrap-in!`'s startup responsibilities into `adopt-startup-plan-into-session!` (developer-prompt seeding, resource loading, summary building). Retain `bootstrap-in!` as a self-contained test-oriented bootstrap; tests that redef it are unaffected since they stub the function entirely

### Out of scope

- Removing or redesigning the dispatch events themselves (`:session/set-system-prompt`, `:session/bootstrap-prompt-state`, `:session/refresh-system-prompt`, `:session/set-active-tools`, etc.) — they serve runtime needs beyond startup
- Redesigning extension loading or manifest activation
- Changing the four-phase bootstrap structure from task 159
- Prompt lifecycle or contribution architecture changes
- Redesigning `build-system-prompt` inputs

## Current flow (annotated)

```
adopt-startup-plan-into-session!(ctx, session-id, ai-model, startup-plan, opts)
  │
  ├─ install background-job UI
  ├─ init built-in workflows
  ├─ make session-scoped psi-tool
  ├─ base-tool-defs = startup-plan.base-tools + psi-tool
  ├─ build base-prompt-opts (no graph-caps, no ext tools)
  │
  ├─ BUILD system prompt #1 (base-prompt-opts)           ◄── wasted
  ├─ PERSIST system prompt #1 (persist-system-prompt!)   ◄── wasted
  │
  ├─ bootstrap-in!(ctx, session-id, ...)
  │    ├─ PERSIST #2: :session/bootstrap-prompt-state    ◄── wasted
  │    ├─ PERSIST #3: :session/refresh-system-prompt     ◄── wasted (no build-opts exist yet; re-persists base-system-prompt as-is)
  │    ├─ load resources (templates, skills, tools, extensions)
  │    ├─ refresh-active-tools? = false                  ◄── disabled
  │    ├─ PERSIST summary #1                             ◄── overwritten
  │    └─ return summary-base
  │
  ├─ bootstrap-manifest-extensions-in!
  │    └─ return summary-updates
  │
  ├─ merge-tool-defs-by-name (base + ext tools)
  ├─ DISPATCH :session/set-active-tools                  ◄── only active tool-set
  │
  ├─ merge summaries → PERSIST summary #2               ◄── overwrites #1
  │
  ├─ finalize-startup-system-prompt!
  │    ├─ register-all-domains!
  │    ├─ query graph-capabilities
  │    ├─ BUILD system prompt #4 (with graph-caps + ext tools)
  │    ├─ PERSIST system prompt #4                       ◄── final, correct
  │    └─ PERSIST build-opts
  │
  ├─ sync memory layer
  ├─ register extension run fn
  └─ capture startup rehydrate
```

## Target flow

```
adopt-startup-plan-into-session!(ctx, session-id, ai-model, startup-plan, opts)
  │
  ├─ install background-job UI
  ├─ init built-in workflows
  ├─ make session-scoped psi-tool
  ├─ base-tool-defs = startup-plan.base-tools + psi-tool
  │
  ├─ set developer-prompt + developer-prompt-source in session state
  │    (DISPATCH :session/bootstrap-prompt-state with developer-prompt,
  │     developer-prompt-source, and empty system-prompt — state seeding only)
  │
  ├─ load resources (templates, skills via direct dispatch)
  │    — uses load-startup-resources-in! with templates + skills only
  │    — does NOT pass tools (individual :session/add-tool dispatches are
  │      redundant because :session/set-active-tools overwrites the full set)
  │    — does NOT pass extension-paths or extension-targets (handled by
  │      bootstrap-manifest-extensions-in! below)
  │
  ├─ bootstrap-manifest-extensions-in!
  │
  ├─ compose final tool set (base + extension, once)
  ├─ DISPATCH :session/set-active-tools (once)
  │
  ├─ register-all-domains!
  ├─ query graph-capabilities
  ├─ BUILD system prompt (once, with all inputs)
  ├─ DISPATCH :session/set-system-prompt (once)
  │    — uses :session/set-system-prompt (not :session/bootstrap-prompt-state)
  │      so that extension prompt contributions are applied via effective-prompt
  ├─ PERSIST build-opts
  │
  ├─ build + PERSIST summary (once)
  │
  ├─ sync memory layer
  ├─ register extension run fn
  └─ capture startup rehydrate
```

### Target flow design decisions

**Prompt dispatch**: The target uses `:session/set-system-prompt` for the single prompt persist because it applies extension prompt contributions via `effective-prompt`. `:session/bootstrap-prompt-state` is used only once at the start to seed `developer-prompt` and `developer-prompt-source` into session state (with an empty system-prompt that will be overwritten).

**Tool registration**: Individual `:session/add-tool` dispatches from `load-startup-resources-in!` are eliminated for base tools. Both `:session/add-tool` and `:session/set-active-tools` produce `:runtime/agent-set-tools` effects, and `set-active-tools` replaces the full set, making prior `add-tool` dispatches redundant. Tools are excluded from the `load-startup-resources-in!` call.

**`load-startup-resources-in!` scope**: Called with templates and skills only. Tools are excluded (composed separately via `set-active-tools`). Extension-paths and extension-targets are excluded (handled by `bootstrap-manifest-extensions-in!`).

**`bootstrap-in!` fate**: `bootstrap-in!` is eliminated from the startup flow. Its responsibilities are inlined into `adopt-startup-plan-into-session!`:
- Developer-prompt seeding → explicit `:session/bootstrap-prompt-state` dispatch
- Resource loading → direct call to `load-startup-resources-in!` (templates + skills only)
- Summary persistence → single dispatch at the end

`bootstrap-in!` itself is retained as a test-oriented convenience function. Its interface is unchanged; tests that redef it continue to work because they stub out the function entirely. Tests that call it directly still get the self-contained bootstrap behaviour it provides.

## Constraints

- The four-phase bootstrap structure from task 159 must be preserved — `build-startup-plan` → `create-initial-startup-session!` → `adopt-startup-plan-into-session!` remains the shape
- Resource loading (templates, skills, tools via dispatch) must still use the authoritative dispatch events
- `bootstrap-in!` is also used by tests — if its interface changes, test callers must be updated; if it is no longer the right abstraction for tests, tests should call the replacement
- The final prompt must include graph-capabilities and extension tool definitions — this is why it must be built last
- Developer prompt and prompt-mode must reach session state
- `bootstrap-manifest-extensions-in!` is out of scope for changes but its outputs (extension tools, summary updates) must be consumed correctly
- Existing test assertions on bootstrapped session state must continue to pass

## Acceptance criteria

1. The system prompt is built exactly once during startup — after graph-capabilities and extension tools are known
2. The system prompt is persisted exactly once via a single dispatch path
3. The active tool set is composed and dispatched exactly once
4. The startup summary is persisted exactly once with complete information (base + extension results)
5. No dead-code tool-refresh paths remain in the startup flow
6. All existing bootstrap and startup tests pass
7. Lint clean

## Verification expectations

- Count dispatches of `:session/set-system-prompt` during startup — should be 1
- Count dispatches of `:session/bootstrap-prompt-state` during startup — should be 1 (developer-prompt seeding only)
- Count dispatches of `:session/set-active-tools` during startup — should be 1
- Count dispatches of `:session/set-startup-bootstrap-summary` during startup — should be 1
- The final system prompt contains graph-capabilities and extension tool names
- Session state after bootstrap has correct `:base-system-prompt`, `:system-prompt`, `:developer-prompt`, `:tool-defs`

## Likely hotspots

- `components/app-runtime/src/psi/app_runtime.clj` — `adopt-startup-plan-into-session!`, `finalize-startup-system-prompt!`, `persist-system-prompt!`, `merge-tool-defs-by-name`, `startup-base-prompt-opts`
- `components/agent-session/src/psi/agent_session/bootstrap.clj` — `bootstrap-in!`, `refresh-active-tools-in!`, `load-startup-resources-in!`
- `components/app-runtime/test/psi/app_runtime_test.clj`
- `components/app-runtime/test/psi/app_runtime_bootstrap_test.clj`
- `components/app-runtime/test/psi/extension_install_startup_test.clj`
- `components/app-runtime/test/psi/bootstrap_extension_invariant_test.clj`
- `components/agent-session/test/psi/agent_session/bootstrap_test.clj`

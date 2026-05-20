# Implementation Notes — 161

## Design review: ambiguity pass (2026-05-19)

### A1 — Intent miscount: "three times" vs actual four persists / two builds

The Intent says the system prompt is "built and persisted three times." The annotated Current Flow shows four persists and two builds. Specifically:
- BUILD #1 + PERSIST #1: `adopt-startup-plan-into-session!` calls `build-system-prompt` then `persist-system-prompt!` (`:session/set-system-prompt`)
- PERSIST #2: `bootstrap-in!` dispatches `:session/bootstrap-prompt-state` (state mutation, no build)
- PERSIST #3: `bootstrap-in!` dispatches `:session/refresh-system-prompt` — at this point no `:system-prompt-build-opts` exist in session data, so the handler falls back to re-persisting `:base-system-prompt` without calling `build-system-prompt`. The design says "rebuilds from build-opts" but build-opts aren't stored until `finalize-startup-system-prompt!` runs later.
- BUILD #2 + PERSIST #4: `finalize-startup-system-prompt!` builds and persists the correct final prompt.

The annotated flow is mostly accurate but the "rebuilds from build-opts" annotation on PERSIST #3 is wrong — it's a no-op re-persist, not a rebuild. The Intent section's "three times" is also wrong.

### A2 — Target flow: `:session/bootstrap-prompt-state` does not apply prompt contributions

The target flow proposes `DISPATCH :session/bootstrap-prompt-state (once)` for the single prompt persist. However, `:session/bootstrap-prompt-state` simply `assoc`s `:base-system-prompt` and `:system-prompt` to the same value — it does **not** apply prompt contributions from extensions.

In contrast, the current final persist uses `:session/set-system-prompt` (via `persist-system-prompt!`), which calls `effective-prompt` to fold in registered prompt contributions.

The design must clarify: should the target use `:session/set-system-prompt` (which applies contributions), `:session/bootstrap-prompt-state` + a follow-up `:session/refresh-system-prompt`, or a modified approach? This affects whether extension prompt contributions appear in the startup system prompt.

### A3 — Target flow omits individual tool registration via `:session/add-tool`

The current `bootstrap-in!` → `load-startup-resources-in!` dispatches `:session/add-tool` for each tool individually, then `adopt-startup-plan-into-session!` later dispatches `:session/set-active-tools` which replaces the entire tool set.

The target flow shows only `compose final tool set` → `DISPATCH :session/set-active-tools (once)`. It's unclear whether the individual `:session/add-tool` dispatches in `load-startup-resources-in!` should be removed (since `set-active-tools` overwrites them) or retained (because they may have side effects beyond the tool list, e.g. agent-core tool registration). The design should state which path to take.

### A4 — Fate of `bootstrap-in!` is ambiguous

The design's Scope says "either inline `bootstrap-in!`'s remaining responsibilities into `adopt-startup-plan-into-session!`, or restructure `bootstrap-in!` so it no longer performs work that its caller immediately overrides." The target flow shows no `bootstrap-in!` call at all, implying full inlining.

But the Constraints say "bootstrap-in! is also used by tests — if its interface changes, test callers must be updated; if it is no longer the right abstraction for tests, tests should call the replacement." Seven test call sites redef `bootstrap-in!` (5 in `app_runtime_test.clj`, 2 in `app_runtime_bootstrap_test.clj`).

The design should decide: (a) keep `bootstrap-in!` as a test-oriented subset, (b) eliminate it and update test redefs to target the replacement, or (c) restructure it. The current ambiguity leaves the implementer to make an architectural choice.

### A5 — `load-startup-resources-in!` scope in target flow is unclear

The target flow says "load resources (templates, skills, extensions via direct dispatch)." The current `load-startup-resources-in!` also loads tools (`:session/add-tool`) and handles extension-paths and extension-targets. The target flow doesn't mention tools in the resource-loading step (only in "compose final tool set" later) and doesn't mention extension-paths/extension-targets (only `bootstrap-manifest-extensions-in!`).

Should `load-startup-resources-in!` still be called? With which subset of its current parameters? The design should clarify what "load resources" means concretely.

### A6 — `developer-prompt` delivery path in target flow

Currently `developer-prompt` and `developer-prompt-source` reach session state via `bootstrap-in!` → `:session/bootstrap-prompt-state`. The target flow removes `bootstrap-in!` but shows `:session/bootstrap-prompt-state` which does carry these fields. This is consistent — but only if the target flow passes `developer-prompt` into the `:session/bootstrap-prompt-state` dispatch payload. The target flow diagram doesn't show this explicitly. Minor, but worth confirming the intent.

## Design review: ambiguity follow-up resolutions (2026-05-19)

All six ambiguity items (A1–A6) resolved. Design.md updated:

- **A1**: Intent corrected to "four times" (2 builds, 4 persists). PERSIST #3 annotation fixed: no build-opts exist at that point, handler falls back to re-persisting base-system-prompt as-is.
- **A2**: Target flow uses `:session/set-system-prompt` for the single prompt persist (applies prompt contributions via `effective-prompt`). `:session/bootstrap-prompt-state` used only once at start to seed developer-prompt + developer-prompt-source.
- **A3**: Individual `:session/add-tool` dispatches removed from startup. Both `add-tool` and `set-active-tools` produce `:runtime/agent-set-tools` effects; `set-active-tools` replaces the full set, making prior `add-tool` dispatches redundant.
- **A4**: `bootstrap-in!` retained as test-oriented convenience. Startup responsibilities inlined into `adopt-startup-plan-into-session!`. Tests that redef `bootstrap-in!` are unaffected (they stub the function entirely).
- **A5**: `load-startup-resources-in!` called with templates + skills only. Tools excluded (composed separately via `set-active-tools`). Extension-paths/targets excluded (handled by `bootstrap-manifest-extensions-in!`).
- **A6**: Confirmed. Target flow explicitly dispatches `:session/bootstrap-prompt-state` with developer-prompt and developer-prompt-source before the prompt build. Now shown in target flow diagram.

## Design review: inconsistency pass (2026-05-19)

### IC1 — `set-active-tools` side effect contradicts "persisted exactly once" acceptance criteria

The `:session/set-active-tools` handler produces a `:runtime/refresh-system-prompt` effect, which dispatches `:session/refresh-system-prompt`. In the target flow, this happens *before* `:session/set-system-prompt` and *before* build-opts are stored. The `:session/refresh-system-prompt` handler falls back to `:base-system-prompt` when no build-opts exist — and at that point `:base-system-prompt` is empty (set by the earlier `:session/bootstrap-prompt-state` with empty system-prompt). This means:

1. The target flow has 3 prompt state-writes, not 1: `:session/bootstrap-prompt-state` (empty), side-effect `:session/refresh-system-prompt` (empty), and `:session/set-system-prompt` (correct final).
2. The side-effect-triggered `:session/refresh-system-prompt` produces a `:runtime/agent-set-system-prompt` effect that pushes an empty prompt to the AI agent, immediately before the correct prompt overwrites it.
3. Acceptance criterion #2 ("The system prompt is persisted exactly once via a single dispatch path") is violated.
4. Verification expectation "Count dispatches of `:session/set-system-prompt` during startup — should be 1" is technically true but misleading — `:session/refresh-system-prompt` is also dispatched as a side effect.

Note: this same side effect exists in the current flow, but there `:base-system-prompt` is the base prompt (not empty), so the intermediate push is less harmful. The design should either: (a) suppress the `refresh-system-prompt` effect during startup (e.g., by not dispatching `set-active-tools` until after the prompt build, or by adding a startup-mode flag), (b) reorder the target flow to build+persist the prompt before `set-active-tools`, or (c) accept the intermediate empty prompt and update acceptance criteria/verification expectations accordingly.

### IC2 — Hotspot file doesn't exist

"Likely hotspots" lists `components/agent-session/test/psi/agent_session/bootstrap_test.clj` — this file doesn't exist. The relevant bootstrap tests in agent-session are in `components/agent-session/test/psi/agent_session/model_dispatch_test.clj`.

# 152 — tool display render spec on tool definitions

## Goal

Make tool-call display formatting a tool-specifiable capability so built-in tools and extension-contributed tools use the same display contract instead of relying on frontend hardcoded special cases for built-ins.

## Why

Today built-in tools display with richer formatting in interactive frontends, while most extension tools fall back to showing only their tool name.

Examples of current built-in-only behavior include:

- `bash` displays as `$`
- `read`, `edit`, and `write` display a primary path summary
- `read` and `edit` can display derived line-range suffixes

That behavior is currently encoded in frontend-specific special cases rather than in the registered tool definition itself. Extensions can improve rendering only through separate imperative UI registration (`:register-tool-renderer`), which means the canonical tool definition does not carry the display contract that frontends actually want.

This creates three problems:

- built-in and extension tools do not share one display path
- tool display behavior is duplicated across TUI and Emacs frontends
- the canonical tool contract does not describe how a tool wants its requests/results summarized in UI

## Problem

The current system has an architectural asymmetry:

- built-in tools get special-cased display formatting in frontend code
- extension tools generally display only their raw tool name unless an extension separately registers custom UI renderers
- the richer formatting behavior is not owned by the tool definition that conceptually describes the tool

As a result, display quality depends on whether a capability is built in or extension-contributed, rather than on whether the tool definition itself specifies how it should be rendered.

## Intent

Introduce a tool-specifiable display contract and migrate built-in special-cased formatting onto that shared path.

This task should:

- let a registered tool definition optionally describe how its call header and result should be rendered in UI
- preserve a generic fallback display when no custom display contract is provided
- allow extension-contributed tools to use the same display-specification path as built-in tools
- reduce or remove frontend hardcoded built-in display special cases where the new shared path covers them
- preserve existing built-in display quality for `read`, `bash`, `edit`, `write`, and any other currently polished built-in tool rows

This task should not:

- redesign the whole transcript or message rendering system
- require arbitrary UI code to be serializable across RPC boundaries
- broaden into generic widget rendering changes unrelated to tool-call display
- redesign provider-facing tool schemas or tool execution semantics

## Preferred design direction

### Design decision for this task

This task chooses the **render-hook shape**.

The minimal coherent slice is to let the canonical registered tool definition carry optional executable display hooks:

- `:render-call-fn`
- `:render-result-fn`

The alternative declarative display-spec shape is deferred because the immediate problem is built-in-only display asymmetry, and the existing UI machinery already knows how to consume render hooks. The important architectural constraint is that these hooks become owned by the canonical tool definition rather than by frontend-only `case` branches or extension-only imperative registration.


### Canonical ownership

The display contract should be attached to the tool definition or to the canonical tool-registration path, not encoded only in frontend-specific `case` logic.

For this task, the canonical owner is the runtime tool registry entry itself: the registered tool definition is the authoritative source for optional `:render-call-fn` and `:render-result-fn` hooks.

### Projection path for non-serializable hooks

Because render hooks are executable functions, they are not a public serializable graph contract.

This task therefore uses a two-surface model:

- **canonical storage:** the registered tool definition in the runtime tool registry keeps the hook fns
- **interactive projection:** tool registration/backfill projects those hook fns into the existing UI-state `:tool-renderers` map so live TUI and Emacs rendering can call them
- **snapshot/introspection projection:** EQL and UI snapshots continue to expose only metadata and must keep stripping executable fns from `:psi.ui/tool-renderers`

Implications:

- the runtime tool registry remains the source of truth for whether a tool has custom display behavior
- UI state is a consumer-facing execution cache/projection for interactive frontends, not the authoritative owner
- `:register-tool-renderer` may remain as an advanced escape hatch or compatibility surface, but the common path for tool display customization becomes tool registration itself
- no new requirement is introduced for render hooks to cross RPC or graph boundaries as data

This resolves the current asymmetry between interactive projections, which preserve renderer functions, and EQL snapshots, which intentionally drop them.

### Near-term preferred shape

Support optional tool-level display hooks that can be supplied as part of registration and then projected into the existing UI renderer machinery.

Preferred capability shape:

- tool definitions or tool-registration inputs may include optional call-summary rendering behavior
- tool definitions or tool-registration inputs may include optional result rendering behavior
- registration should automatically wire those display hooks into the existing UI tool-renderer registry where appropriate

This task does not require the final field names to be exactly these, but the preferred initial shape is conceptually equivalent to:

- `:render-call-fn`
- `:render-result-fn`

attached directly to the registered tool surface, or nested under a dedicated display key if that is cleaner.

### Longer-term direction, but not required now

A more declarative display shape may be preferable later, for example a display map describing icon/display-name/primary-arg/summary behavior. This task does not need to solve that whole declarative design if direct render hooks are the smallest clean step.

Constraint for this task:

- if executable render hooks are chosen now, the design and implementation must keep the path open for a later declarative display contract rather than baking in more frontend-only special cases

### Result-rendering scope for the minimal slice

This task does **not** defer result rendering entirely.

The canonical registered tool contract in this task includes both optional hooks:

- `:render-call-fn`
- `:render-result-fn`

However, the migration obligation is asymmetric:

- built-in parity migration is required only for the in-scope built-in **call-header** cases enumerated in this design
- custom **result** rendering must be supported by the shared registration path so extensions can opt in through tool registration alone
- built-in tools are not required to adopt custom result renderers in this task if their current generic result formatting remains acceptable

This resolves acceptance 7 by requiring result-rendering support in the canonical contract and shared wiring, while keeping the smallest migration slice for built-ins focused on call formatting.

## Behavioural requirements

### Shared display path

Built-in and extension-contributed tools must be able to participate in the same display customization mechanism.

### Fallback behavior

When a tool does not specify custom display behavior, frontends must still render a stable generic fallback based on the tool name and existing generic result formatting.

### Built-in parity preservation

After this task lands, the currently polished built-in display summaries must remain at least as informative as before from a user perspective.

At minimum this covers:

- `bash` retains a shell-like short header rather than degrading to a raw tool-name-only row
- `read`, `edit`, and `write` retain concise path-oriented summaries
- existing useful line-range suffix behavior for `read`/`edit` is preserved if that behavior is still part of the chosen shared path

For acceptance and migration-boundary purposes, the in-scope built-in tool display cases for this task are exactly:

- `bash` call-header rendering
- `read` call-header rendering, including the current derived offset/limit line-range suffix behavior
- `edit` call-header rendering, including the current derived changed-line span suffix behavior when details support it
- `write` call-header rendering

No other built-in tool rows are required to migrate in this task. Any remaining built-in-specific display behavior outside that set is out of scope and should remain unchanged or be handled in a separate follow-on task.

### Extension opt-in

An extension must be able to register a tool with display behavior without also separately calling a frontend-only imperative UI registration API.

That is, the tool registration path itself must be sufficient for the common case.

## Scope

In scope:

- adding optional tool-display metadata or render hooks to the canonical tool registration path
- deciding the correct owner for storing and projecting that display contract
- wiring tool registration so the display contract reaches existing UI rendering surfaces
- migrating built-in tool display special cases to the shared mechanism where covered
- updating extension documentation to teach the new preferred way to customize tool display
- focused proofs for TUI and Emacs parity where the canonical shared path changes behavior

Out of scope:

- redesigning arbitrary extension-injected message rendering
- changing tool execution results for provider/runtime semantics
- broad frontend visual redesign unrelated to tool rows
- forcing every tool to provide a custom renderer
- solving cross-process serialization of arbitrary functions as a public remote API contract

## Design constraints

- preserve a clear generic fallback when no custom display contract exists
- prefer one canonical registration/display path over duplicated built-in-vs-extension frontend logic
- keep built-in and extension display behavior convergent
- avoid making frontend code depend on more special-case tool-name branches than exist today
- keep the implementation small enough to land as a focused vertical slice rather than a broad UI architecture rewrite

## Required design decision

This task must choose one of these two shapes and implement it coherently:

1. **render-hook shape** — optional executable render hooks on tool registration, reused by both built-ins and extensions
2. **declarative display-spec shape** — optional pure display metadata on tool registration, interpreted by both frontends

The task does not need to solve both.

Decision rule:

- choose the smallest shape that eliminates the built-in-only special-case asymmetry now
- record why the unchosen shape was deferred

## Acceptance criteria

1. Tool registration supports an optional display customization contract on the canonical registered tool surface.
2. The common extension path can provide that contract as part of tool registration without separate manual UI renderer registration.
3. Built-in tool display no longer depends solely on frontend hardcoded built-in-name branches for the cases covered by this task.
4. TUI preserves existing rich summaries for built-ins while obtaining them through the shared mechanism.
5. Emacs preserves existing rich summaries for built-ins while obtaining them through the shared mechanism.
6. A focused extension proof demonstrates that an extension-contributed tool can supply custom call display behavior via the new shared path.
7. A focused extension proof demonstrates that an extension-contributed tool can supply custom result display behavior via the new shared path, or explicitly documents why result rendering is deferred if the chosen minimal slice covers call formatting only.
8. Extension-facing documentation teaches the new preferred display customization path and demotes direct imperative renderer registration to either implementation detail or advanced escape hatch, if it remains available.
9. Generic fallback rendering remains correct for tools that do not opt in to custom display behavior.

## Proof surfaces to update

At minimum, inspect and update the relevant focused proofs around:

- TUI tool-row rendering
- Emacs tool-row rendering
- UI state / projection of tool renderers
- extension registration and extension UI documentation
- built-in tool registration definitions for the built-ins migrated in this task

## Out-of-scope follow-ons

If discovered during design or implementation, keep these as separate follow-ons rather than broadening this task:

- a fully declarative cross-frontend tool-display DSL
- custom display policy for background-job listings
- generalized serialization/introspection of display functions in graph surfaces
- unifying custom message rendering under the same contract as tool rendering

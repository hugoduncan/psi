# 153 — tool-specifiable tool header formatting

## Goal

Replace the current built-in-only hardcoded tool header formatting with a tool-specifiable formatting path, so extension-contributed tools can display rich request summaries instead of falling back to just the tool name.

## Why

Today some built-in tools display helpful compact headers in interactive displays:

- `read` shows the path and optional line range
- `edit` shows the path and changed line span
- `bash` shows `$ <command>`
- `write` shows the target path

Other important tools, including built-in `delegate` and extension `work-on`, should be audited and brought under the same explicit formatter ownership rather than being left as incidental name-only summaries.

But extension-contributed tools generally do not get equivalent treatment. Their displayed header usually degrades to the tool name alone, even when the tool request contains a clear primary subject worth showing.

That is both a user-experience and architecture problem:

- the UX is inconsistent between built-in and extension tools
- the formatting knowledge is hardcoded in UI-specific logic instead of being owned by the tool definition
- adding a rich header for a new tool currently requires editing core display code rather than declaring formatting at the tool surface

The current code suggests this is special-cased today:

- TUI has built-in-name dispatch in `components/tui/src/psi/tui/tool_render.clj`
- Emacs has built-in-name dispatch in `components/emacs-ui/psi-tool-rows.el`
- normalized tool definitions currently carry prompt-facing fields such as `:lambda-description`, but no display-specific request formatter metadata
- TUI extension UI already has renderer registration for extension-owned custom rendering, but that is a separate mechanism from the canonical collapsed/single-line tool header summary shown in ordinary tool rows

This task should remove the built-in-only privilege from the canonical tool header summary path.

## Problem

The canonical compact tool-call header is currently derived by UI-local hardcoding keyed off tool names like `read`, `edit`, `write`, and `bash`.

As a result:

- built-in tools get rich summaries because the UIs know about them ahead of time
- extension tools usually display only their name
- TUI and Emacs duplicate the same built-in-specific formatting knowledge
- there is no tool-owned way to say how a request should be summarized for display
- RPC/backend surfaces cannot authoritatively provide the same tool-specific header summary without re-encoding the special cases again

This means the nicest display behavior is coupled to built-in identity and repeated across frontends instead of being a capability of the tool definition itself.

## Intent

Introduce one canonical tool-header formatting mechanism owned by tool definitions and shared by backend display surfaces.

This task should:

1. let a tool definition specify how its request should be summarized for a compact header line
2. migrate the existing built-in rich header behavior onto that mechanism
3. provide one shared backend function that invokes the formatter for a given tool call
4. use that shared function from TUI and RPC/backend-visible display surfaces, and carry the preformatted header string through RPC for Emacs consumption
5. preserve a safe fallback to the tool display name when formatter invocation fails or returns invalid output

The task should not:

- redesign full expanded tool-body rendering
- redesign extension UI custom renderers beyond clarifying the boundary with header formatting
- require every existing extension tool to add a formatter immediately
- broaden into a generic prompt-description or lambda-description redesign

## Desired outcome

After this task:

- built-in tools still display the same or better compact headers they do today
- extension-contributed tools participate in the same compact-header contract instead of relying on name-only fallback as the normal case
- the header-formatting rule for a tool is discoverable from the tool definition/registration path rather than buried in each UI
- TUI and RPC/backend surfaces use one shared formatter invocation path
- RPC carries the resulting preformatted compact header string so Emacs can consume the canonical backend-produced summary without reimplementing formatting in Elisp
- fallback behavior remains stable and unsurprising only for formatter failure or invalid output, not as the normal no-formatter path

Examples of intended behavior:

- `read` can still render `read src/foo.clj:10:29`
- `bash` can still render `$ git status`
- `psi-tool` must follow the canonical per-action compact header spec recorded in `psi-tool-format.md`
- built-in `delegate` must gain an explicit compact header formatter rather than remaining an implicit name-only case; its formatter should summarize the effective action and primary workflow/run target according to `delegate-format.md`
- extension `work-on` must gain an explicit compact header formatter summarizing the work description and optional base branch according to `work-on-format.md`
- `edit-clj` should render with the same request-shape semantics as built-in `edit` where applicable (target path and changed-line span derivation), while still identifying itself as `edit-clj` rather than claiming to be `edit`
- a formatter failure still falls back to the tool display name defensively

## In scope

- inventorying the current hardcoded tool-header formatting path across TUI, Emacs, RPC, and any backend display helpers
- choosing the canonical tool-definition hook for compact request-header formatting
- adding normalized tool-definition support for the chosen mandatory hook
- defining the exact executable formatter contract, validation, and safe invocation rules
- introducing one shared backend formatter invocation function for compact tool header lines
- migrating built-in tool header special cases onto the new mechanism
- updating TUI to use the shared mechanism
- updating RPC/backend display shaping to use the shared mechanism where it surfaces tool header lines or summaries
- carrying the backend-produced preformatted compact header string through RPC for Emacs consumption
- removing Emacs-local tool-name dispatch for compact header formatting in favor of the RPC/backend-produced summary
- focused tests for built-in parity, extension opt-in formatting, fallback behavior, and backend/frontend shared use

## Out of scope

- rich multi-line tool body rendering
- general tool result formatting beyond the compact header line
- extension-owned fully custom UI renderers already handled by separate UI extension mechanisms
- model/provider tool schema changes unrelated to display
- changing the canonical tool name or tool execution contract

## Canonical concepts

### Tool header formatter

A tool-owned way to turn a tool call into a compact single-line display header.

For this task, the formatter owns only the header summary for the call, not the full rendered body.

Minimum required inputs:

- normalized tool definition
- tool name
- parsed request arguments
- raw arguments string/map when useful
- optional tool-call details already known at header-render time

Minimum required output:

- one plain single-line header string, or `nil` to indicate fallback

Failure rule:

- formatter failure must not break tool display
- on failure, the system falls back to the standard display-name summary and may optionally log diagnostic information

### Shared backend formatter invocation

One canonical backend helper that:

1. resolves the registered tool definition for a tool call
2. invokes the tool-specific formatter safely
3. falls back to the default display-name summary when necessary
4. returns the canonical preformatted compact header string for downstream display consumers

This helper becomes the authoritative owner for compact tool-call header semantics used by backend and frontend-facing display shaping.

### Default fallback summary

When no formatter is configured or formatting fails, the system must still produce a stable compact header.

Minimum fallback rule for this task:

- use the tool display name
- do not treat missing formatter metadata as a normal runtime case; formatter presence is mandatory on canonical tool definitions after this task

The task must avoid reintroducing hidden built-in-only heuristics through the fallback path.

## Design requirements

### Tool-definition ownership

The compact header formatting rule must be tool-specifiable.

That means:

- built-in tools and extension tools use the same definition-level mechanism
- canonical tool definitions must include formatter metadata; absence is invalid for this surface after migration
- rollout scope for this task is all canonical registered tools participating in the runtime tool catalog; this task must migrate every currently registered built-in and owned extension tool to `:format-request` rather than relying on an open-ended compatibility allowlist
- if implementation discovers a legacy compatibility shim is required briefly to land the migration safely, that shim must be explicitly scoped to named tools, documented in task artifacts, and removed before this task can be considered complete
- the registration path for tools must preserve whatever formatter metadata is needed to invoke the behavior later
- the mechanism must be explicit in normalized tool definitions rather than inferred only from special tool names

### Shared invocation path

TUI and RPC/backend display shaping must use the same shared function to compute the canonical compact header line.

This task is successful only if that shared function replaces at least the current built-in-specific TUI special casing and provides the backend/RPC path a single authoritative formatter hook.

### Built-in parity

The existing rich built-in header behavior must be preserved when migrated to the new mechanism.

At minimum this covers the current built-in special cases already visible in interactive display code:

- `read`
- `edit`
- `write`
- `bash`
- `psi-tool`, following the canonical action-specific compact-header contract recorded in `munera/open/153-tool-specifiable-tool-header-formatting/psi-tool-format.md`
- `delegate`, with the explicit action/target compact-header contract recorded in `munera/open/153-tool-specifiable-tool-header-formatting/delegate-format.md`

If implementation discovers another built-in header special case in the same canonical path, it must either migrate it too or explicitly record why it remains outside this slice.

Additional parity/convergence rules for this slice:

- `edit-clj` must reuse the same request-summary semantics as built-in `edit` where applicable, but preserve its own tool identity in the rendered header (`edit-clj …`, not `edit …`)
- `delegate` must not be left as an unexamined fallback case; this task must either define and implement its formatter contract or record a narrowly scoped follow-on with a concrete reason

### Extension participation

An extension-contributed tool must supply the formatter through its tool definition without requiring a core UI code edit for that specific tool.

This is the central acceptance condition of the task.

### Boundary with custom renderers

This task must explicitly separate two concepts:

- compact canonical header summary for ordinary tool rows
- fully custom extension UI renderers

The new formatter mechanism should cover the first concept only.

If an existing extension UI renderer API overlaps with header rendering, the implementation must document which surface is authoritative for compact headers after this task.

## Preferred implementation direction

Preferred implementation order:

1. inventory the existing built-in hardcoding sites and determine which are canonical vs UI-local duplication
2. introduce one normalized tool-definition field for header formatting
3. add a shared backend helper for header formatting invocation and fallback
4. migrate built-in tool definitions to use the new hook
5. update TUI to use the shared helper
6. update RPC/backend display shaping to use the same helper
7. decide whether Emacs should consume the backend-provided header summary now or remain a follow-on migration

## Fixed mechanism choice

This task fixes the mechanism now.

Chosen mechanism:

- normalized tool definitions must include an executable formatter function at `:format-request`
- `:format-request` is the canonical tool-owned hook for compact single-line tool-call header formatting
- the shared backend formatter helper invokes that function and falls back safely only when it returns blank, returns invalid output, or throws

Why this mechanism:

- it matches the existing runtime shape of tool definitions, which already preserve executable in-process functions such as `:execute`
- it satisfies the user-facing requirement directly: a tool can describe how to format its own request
- it avoids inventing a second named-var indirection or a new declarative mini-language for a small, runtime-local display problem
- it works equally for built-in and extension-contributed tools registered in-process
- it keeps the formatting logic owned by the tool definition rather than by each UI
- making it mandatory prevents silent regression back to name-only headers for new tools

Why not a declarative spec in this task:

- the current built-in formatting cases are already slightly behavioral (`read` line-range derivation, `edit` changed-line span derivation, `bash` `$` prefix)
- a declarative mini-language would either be too weak and leak special cases back into the shared helper, or be broad enough to become a larger design problem than this task needs
- a direct function hook is the smallest mechanism that preserves built-in parity and enables extension opt-in immediately

Why not a named var reference in this task:

- tool definitions are already runtime-local rich maps, not purely serializable manifests
- adding a second resolution step would broaden the mechanism without adding value for this slice
- direct function preservation through normalization is simpler and easier to test

### `:format-request` contract

` :format-request` is an in-process function stored on the normalized tool definition.

Signature for this task:

- single map argument

Required input map keys:

- `:tool` — normalized tool definition map
- `:tool-name` — canonical tool name string
- `:parsed-args` — parsed request arguments map when available
- `:arguments` — raw request arguments string/map when available
- `:details` — optional tool-call details map when available

Allowed behavior:

- return a single plain string for the compact header summary
- return `nil` only to trigger defensive fallback when the formatter cannot produce a valid summary

Disallowed/unsupported expectations:

- no UI styling/ANSI/face properties in the returned value
- no multiline output; shared helper normalizes to one line or falls back
- no requirement that the function be serializable or provider-facing

Failure rule:

- if `:format-request` throws, returns a non-string non-nil value, or returns blank output after normalization, the shared helper must fall back to the default display-name summary
- formatter failure may be logged diagnostically but must never break tool rendering

### Normalization/projection rule

- `psi.tool-registry.defs/normalize-tool-def` must require and preserve `:format-request` the same way it preserves `:execute`
- canonical tool registration paths must reject or fail validation for tool definitions missing `:format-request`, unless an explicitly scoped compatibility shim is recorded during migration
- provider-facing and agent-core tool projections must not include `:format-request`; it is runtime display metadata, not provider protocol data
- extension registration paths and built-in registration paths must both be able to supply `:format-request`

### Shared helper rule

One backend-owned helper is authoritative for compact header formatting.

Chosen owner for this task:

- `components/tool-runtime/`
- namespace direction: `psi.tool-runtime.call-summary` or a closely equivalent lower tool-runtime owner

Why this owner:

- compact tool-call header summarization is tool-runtime-domain work: it derives a transport-safe summary from tool name, arguments, and optional details
- the helper must be usable before and during tool execution lifecycle events, which fits the existing canonical tool event owner in `psi.tool-runtime.core`
- the helper must be shared by TUI and RPC/backend shaping without depending on either UI stack
- `tool-registry` owns definition normalization/lookup semantics, but not lifecycle display shaping
- `agent-session` would be too high and would re-couple a lower tool concern to one runtime owner

Minimum responsibilities:

1. derive/normalize argument inputs needed by formatters
2. resolve and invoke `:format-request` from the tool definition when present
3. normalize the result to a single plain line
4. apply standard fallback when needed
5. return a transport-safe call summary string suitable for inclusion on canonical tool lifecycle events and RPC payloads

Boundary rule:

- `psi.tool-runtime.call-summary` owns compact header summary computation only
- it does not own full UI row rendering, ANSI styling, truncation-by-terminal-width, or custom extension UI renderers
- TUI, RPC, and Emacs consume its output according to their own presentation concerns

### Backend/frontend usage rule

- TUI must use the shared helper for canonical compact tool headers
- RPC/backend display shaping must use the same helper wherever it emits or computes compact tool header summaries
- the authoritative transport field for this task is `:call-summary` on canonical tool lifecycle events/payloads that already carry tool-call metadata, including the pre-completion `tool/executing` path and the corresponding RPC-facing tool row payloads derived from it
- RPC tool events/payloads must carry the resulting preformatted compact header string under `:call-summary`
- Emacs must consume that RPC/backend-produced compact header string rather than keeping separate local tool-name dispatch, because Emacs cannot invoke the Clojure formatter directly

## Inventory required before mechanism choice

Implementation must inventory the current tool-header formatting path and classify each use:

- TUI compact tool header rendering
- Emacs compact tool row summary rendering
- RPC/backend event or projection surfaces that could or should carry the shared formatted header
- built-in tool-definition registration sites for `read`, `edit`, `write`, `bash`, `psi-tool`, `delegate`, and any other built-ins in the same path
- extension tool registration paths and normalization points, including `edit-clj` and `work-on`
- any tests that currently prove built-in-only formatting by direct name dispatch
- task-local per-tool format specs such as `psi-tool-format.md`, `delegate-format.md`, and `work-on-format.md` that must be treated as authoritative acceptance input for migrated tool formatters

The final mechanism choice must be grounded in that inventory.

## Key design questions

1. How should the mechanism interact with existing extension UI custom renderers so responsibilities remain clear?

## Success criteria

This task is successful only if all of the following are true:

- built-in tool header formatting is no longer hardcoded as a built-in-only privilege in the canonical display path
- every canonical tool definition carries compact request-header formatting through `:format-request`
- TUI and RPC/backend display shaping use one shared formatter invocation path
- RPC carries the canonical preformatted compact header string for Emacs consumption
- built-in header behavior for `read`, `edit`, `write`, `bash`, `psi-tool`, and `delegate` is preserved or made explicit according to the canonical per-tool specs for this slice
- `edit-clj` participates using `edit`-equivalent request-summary semantics without losing its own tool identity
- `work-on` participates according to `work-on-format.md`
- an extension-contributed tool can participate in richer header display without editing core UI logic for that tool
- fallback behavior remains stable only for formatter failure/invalid output, not for missing formatter metadata
- the task explicitly documents the boundary between compact header formatting and full custom UI renderers

## Acceptance

- a new Munera task exists for tool-specifiable tool header formatting
- the task captures the current built-in-vs-extension display asymmetry as the motivating problem
- the task requires one tool-definition-level formatting mechanism instead of hardcoded built-in-only name dispatch
- the task requires one shared backend formatter invocation path used by TUI and RPC/backend display shaping
- the task preserves current built-in header behavior while enabling extension participation through mandatory `:format-request`
- the task treats `munera/open/153-tool-specifiable-tool-header-formatting/psi-tool-format.md` as authoritative acceptance input for `psi-tool` header formatting
- the task treats `munera/open/153-tool-specifiable-tool-header-formatting/delegate-format.md` as authoritative acceptance input for `delegate` header formatting
- the task treats `munera/open/153-tool-specifiable-tool-header-formatting/work-on-format.md` as authoritative acceptance input for `work-on` header formatting
- the task requires built-in `delegate` to be explicitly inventoried and brought under the formatter contract in this slice
- the task requires `edit-clj` to use built-in `edit` request-summary semantics while preserving the `edit-clj` label
- the task keeps scope focused on compact header summaries rather than full tool rendering redesign

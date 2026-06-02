# 205 — Single-source built-in slash commands across backend, TUI, and Emacs

## Intent

Make the backend the single authoritative source of the built-in slash-command
surface (names + descriptions), exposed via an EQL resolver, and have both the
TUI and Emacs UIs consume it — the same way they already consume
`:psi.extension/command-names`. Eliminate the hardcoded, drift-prone built-in
command lists currently duplicated in three places.

## Why

`/reload-models` does not appear in Emacs slash-command autocomplete. Root
cause: the built-in command set is duplicated in **three** locations that have
diverged:

| Source | Location | State |
|---|---|---|
| Backend dispatch (authoritative routing) | `commands.clj` `exact-command-handlers` + `prefixed-command-prefixes` → `builtin-command-names` | complete |
| Backend help text (authoritative descriptions) | `commands.clj` `format-help` prose | complete but separate |
| TUI | `tui/app/shared.clj` `builtin-slash-commands` | drifted — missing `/model`, `/skills`, `/prompts`, `/login`, `/logout`, `/reload-*`, `/speed`, `/effort`, … |
| Emacs | `psi-completion.el` `psi-emacs-slash-command-specs` | drifted — missing `/reload-models`, `/reload-extension-installs`, `/project-repl`, `/speed`, `/effort` |

The UIs only learn built-in commands from their own hardcoded lists; the
backend authoritative set (`builtin-command-names`, commands.clj:715) is **not
exposed via any resolver**. The UIs already consume extension commands via
`:psi.extension/command-names` — built-ins should travel the same path.

This is a single-source-of-truth / one-way violation. Each new built-in command
(e.g. `/reload-prompts` from task 204) silently fails to appear in the UIs
until each hardcoded list is manually updated.

## Scope

### In scope

- **Backend authoritative spec table**: introduce one source of built-in
  command specs in `psi.agent-session.commands` — each entry carries at least
  `:name` (without leading slash, or with — pick one and apply uniformly) and
  `:description`. This table becomes the single source from which the existing
  name surfaces are derived.
- **Derivation, not duplication**:
  - `builtin-command-names` derives from the spec table (or the spec table and
    the routing maps share a derivation so names cannot drift).
  - `format-help`'s built-in command lines derive from the spec table rather
    than re-listing prose. (Arg-hint richness decision below.)
  - Dispatch routing (`exact-command-handlers` value = handler keyword;
    `prefixed-command-prefixes`) stays the routing authority; the spec table
    must stay coherent with it (a test asserts the spec-table name set equals
    the routed name set — no command is routable-but-undescribed or
    described-but-unroutable).
- **New EQL resolver/attribute** exposing built-in command specs, e.g.
  `:psi.agent-session/builtin-command-specs` (vector of `{:name :description}`)
  and/or `:psi.agent-session/builtin-command-names`. Mirror
  `extension-commands-resolver` in `resolvers/extensions.clj`. Discoverable via
  the graph (resolver-index / attr-index).
- **TUI consumption**: `tui/app/support.clj` queries the new attribute (extend
  `command-refresh-query` / `build-init` query) and `tui/app/autocomplete.clj`
  builds built-in candidates from it; remove or empty
  `shared.clj/builtin-slash-commands` as the source of truth.
- **Emacs consumption**: `psi-session-commands.el` query frame requests the new
  attribute; `psi-completion.el` builds built-in candidates from backend specs
  instead of the hardcoded `psi-emacs-slash-command-specs`. Decide the residual
  role of the `defcustom` (see decisions).
- Tests: backend resolver output; spec-table ↔ routing coherence; TUI
  autocomplete includes a representative previously-missing command
  (`/reload-models`); Emacs capf test includes built-in specs from a queried
  frame; coherence/regression lock that adding a built-in name flows to UIs.
- CHANGELOG `[Unreleased]` entry (user-visible: built-in commands now appear in
  TUI/Emacs autocomplete consistently; previously-missing commands now listed).
- Docs: update any reference describing how slash commands are surfaced to UIs.

### Out of scope

- Extension commands (already exposed via `:psi.extension/command-names`).
- Prompt-template completion (already exposed/consumed).
- Skill (`/skill:`) completion mechanics — keep the existing Emacs/TUI helpers;
  only built-in *commands* are single-sourced here.
- Changing command routing, names, or behaviour.
- Restructuring `exact-command-handlers` / `prefixed-command-prefixes` routing
  beyond what is needed to derive names coherently.
- Task 204's `/reload-prompts` work (this task makes that command appear in the
  UIs for free once 204 adds it to the spec table; sequencing noted below).

## Key behaviour / design decisions

### One spec table, two derivations

There are currently two backend authorities: routing (name → handler) and
description (`format-help` prose). The spec table holds **descriptions** keyed
by name; routing maps hold **handlers** keyed by name. The invariant to enforce
by test: `set(spec-table names) == set(routed names)`. Open question for plan:
whether to physically merge routing+description into one richer table, or keep
two maps with a coherence test. Design preference: keep routing maps as-is
(handler values), add a parallel description table, and enforce equality by
test — minimal blast radius, no routing churn.

### Description granularity

`format-help` carries rich arg hints (e.g.
`/model [provider model-id [session|project|user]]`). Autocomplete annotations
(Emacs) and TUI menus want a short description. Decision: the spec table holds a
**short description** per command; `format-help` may additionally render an
arg-usage hint it owns, OR the spec includes an optional `:usage` field.
Resolve in plan; design preference: single `:description` field reused by
help + both UIs; keep arg-usage prose in `format-help` only if it would bloat
autocomplete annotations.

### Emacs `defcustom` residual role

`psi-emacs-slash-command-specs` is a user-facing `defcustom`. Decision: backend
specs become authoritative for built-ins; the `defcustom` is repurposed as a
**user override/supplement** (default empty or limited to Emacs-only helpers
like `/skill:`), merged after backend specs with backend winning on name
collision — or the reverse (user overrides backend). Resolve precedence in
plan; design preference: backend authoritative for built-ins, `defcustom`
retained only for Emacs-only affordances (`/skill:`) and user additions, merged
without overriding backend descriptions.

### Slash prefix normalization

Backend `builtin-command-names` strips the leading `/`. Emacs/TUI specs use the
`/`-prefixed form. The resolver must pick one canonical form; UIs already
normalize (`as-slash-command`, Emacs `concat "/"`). Decision: resolver returns
names **without** leading slash (consistent with `:psi.extension/command-names`,
which returns bare names that UIs prefix). Apply uniformly.

### `/?` and aliases

`/?` (help alias) and `/exit` (quit alias) exist in routing. Decision: include
aliases in the spec table with descriptions so they autocomplete, matching
current Emacs behaviour (which lists `/?` and `/exit`).

## Acceptance criteria

- AC1: A new EQL attribute exposes built-in command specs (name + description),
  discoverable via the graph, resolvable for a session.
- AC2: The backend built-in command name set is derived from a single source;
  a test asserts the spec-table name set equals the routable built-in name set
  (no drift possible between routing and the exposed spec).
- AC3: `format-help` built-in command listing derives from the single spec
  source (no independent hardcoded built-in list remains in `format-help`).
- AC4: TUI slash autocomplete includes `/reload-models` (and other
  previously-missing built-ins), sourced from the backend query — not from a
  hardcoded TUI list.
- AC5: Emacs slash autocomplete includes `/reload-models` (and other
  previously-missing built-ins), sourced from the backend query — not solely
  from the hardcoded `psi-emacs-slash-command-specs`.
- AC6: Adding a built-in command to the backend spec table makes it appear in
  both TUI and Emacs autocomplete with no UI-side list edit (proven by a test
  exercising a representative command end-to-end on each consumer).
- AC7: Built-in command names flow through resolvers; UIs read via EQL
  (one-way / single-source-of-truth respected).
- AC8: CHANGELOG `[Unreleased]` documents the corrected/consistent built-in
  command autocomplete; docs describing UI command surfacing updated.

## Architectural alignment

- Reads via resolvers; UIs are projections that query EQL. Mirrors the existing
  `:psi.extension/command-names` resolver + TUI/Emacs consumption exactly, so no
  new pattern is introduced.
- No shim/adapter: the UIs gain one more attribute on queries they already make.
- Kills a triplicated-state coherence violation; aligns with
  `single_source_of_truth` and `λ one_way`.

## Open questions for plan stage

1. Merge routing + description into one table vs. parallel table + coherence
   test (design preference: parallel + test).
2. Description vs. usage-hint granularity and whether the resolver carries
   `:usage`.
3. Emacs `defcustom` precedence/residual role and whether built-in entries are
   removed from its default value.
4. Sequencing: task 204 (`/reload-prompts` command + mutation) has **already
   landed and closed**. `/reload-prompts` is therefore another concrete
   built-in currently missing from both UI autocomplete lists — confirm it is
   swept into the spec table by this task and appears in TUI + Emacs.

# 204 — Reload prompt templates: `/prompts-reload` command + mutation (psi-tool visible)

## Intent

Add the ability to reload prompt templates from disk into a live session,
without restarting psi. Expose this two ways:

1. A `/prompts-reload` slash command (TUI + Emacs renderers via command result).
2. A registered mutation `psi.extension/reload-prompts`, which — by virtue of
   being in `mutations/all-mutations` — is automatically discoverable and
   invokable through `psi-tool` `action: "mutate"`.

This mirrors the existing model-reload surface
(`/reload-models` command + `:session/reload-models` dispatch handler).

## Why

Prompt templates are Markdown files discovered once at session startup from:

- Global: `~/.psi/agent/prompts/*.md`
- Project: `<worktree>/.psi/prompts/*.md`
- CLI `--prompt-template <path>` extra paths

Today, editing a prompt `.md` file (or adding a new one) requires starting a
new session to pick up the change: `discover-templates` runs only in
`app-runtime/build-startup-plan`. There is a `/reload-models` and a
`/reload-extension-installs` reload path but no equivalent for prompts.
Authoring/iterating on prompts is a common loop; a reload closes it.

## Scope

### In scope

- A core reload operation that re-discovers prompt templates from disk for the
  session's worktree and **replaces** the session's registered templates with
  the freshly discovered set.
- A `:session/reload-prompts` dispatch handler (the single source-of-truth
  state mutation path), following the canonical dispatch → handler → effect/
  root-state-update model. Reading goes through resolvers; the state change
  goes through dispatch.
- A `psi.extension/reload-prompts` Pathom mutation in
  `psi.agent-session.mutations.prompts`, added to `all-mutations` so it is
  visible to `psi-tool` `mutate` (which enumerates registered mutation syms).
- A `/prompts-reload` exact command handler in `psi.agent-session.commands`,
  returning a `:text` result summarizing the reload (worktree, count, and any
  discovery diagnostics), mirroring `format-reload-models`.
- Tests covering: dispatch-handler replace semantics, mutation
  output/dispatch, command result text, and psi-tool mutation visibility.
- CHANGELOG `[Unreleased]` entry (user-visible: new command + new mutation).
- Doc updates where prompt templates / reload commands are documented
  (e.g. `doc/` prompt-template/command references, `/help` listing if it
  enumerates reload commands).

### Out of scope

- Reloading **skills** (no skill reload exists; a sibling task if desired).
- Reloading **prompt contributions** (extension-owned; separate registry and
  lifecycle — `register/update/unregister-prompt-contribution` already exist).
- Reloading the **system prompt / nucleus prelude** or context files.
- Hot-watching the prompts directory (manual reload only).
- Changing prompt-template discovery sources, ordering, or precedence.

## Key behaviour decisions

### Replace, not append

`:session/register-prompt-template` (existing) **appends** one template.
Reload must **replace** the entire `:prompt-templates` vector with the result
of a fresh `discover-templates`, so that:

- Edited templates reflect new content.
- Newly added `.md` files appear.
- Deleted `.md` files disappear.

Open question for plan: confirm whether a dedicated
`:session/set-prompt-templates` replace handler is cleaner than reusing
`register` in a loop after a clear. Design preference: a single replace
handler that sets `:prompt-templates` to the discovered vector
(analogous to `:session/set-skills` / `set-active-tools` replace semantics).

### Discovery inputs

Reload must use the **session's worktree path** to resolve the project
prompts dir (`<worktree>/.psi/prompts`), not process `cwd`. The startup path
calls `(pt/discover-templates)` with defaults (process-relative project dir);
reload must pass `:project-prompts-dir` derived from
`(ss/session-worktree-path-in ctx session-id)` so the correct project
prompts are picked up. CLI `--prompt-template` extra paths: decide in plan
whether reload re-applies them (they are not currently persisted on the
session — likely dropped on reload unless captured; document the chosen
behaviour).

### Return shape

Reload operation returns at least:

- `:reloaded?` / count of templates after reload
- discovery `:diagnostics` (if `discover-templates` surfaces any — currently
  it does not return diagnostics; skills do. Document that prompt discovery
  has no diagnostics channel, or add minimal error capture.)
- worktree path used (for the command summary)

Mutation `psi.extension/reload-prompts` output keys (namespaced), e.g.:

- `:psi.prompt-template/reloaded?`
- `:psi.prompt-template/count`

mirroring the existing `:psi.prompt-template/added?` / `count` convention in
`add-prompt-template`.

### psi-tool visibility

No psi-tool code change is required for mutation visibility: `psi-tool`
`mutate` resolves the op-name from registered mutations
(`registered-mutation-syms`). Adding `reload-prompts` to `all-mutations` makes
it invokable as `action: "mutate", mutation: "psi.extension/reload-prompts",
params: {:session-id "..."}`. Confirm in a test that the op-name appears in
the registered mutation set.

## Acceptance criteria

- AC1: Editing an existing `.psi/prompts/foo.md` body, then invoking
  `/prompts-reload`, makes `/foo` expand with the new content in the same
  session (no restart).
- AC2: Adding a new `.psi/prompts/bar.md`, then `/prompts-reload`, makes `/bar`
  discoverable (appears in `/prompts`) and invokable.
- AC3: Deleting a previously-discovered `.psi/prompts/baz.md`, then
  `/prompts-reload`, removes `/baz` from the session's templates.
- AC4: `/prompts-reload` returns a `:text` result summarizing worktree and
  resulting template count (consistent with `/reload-models` formatting).
- AC5: `psi.extension/reload-prompts` is a registered mutation present in
  `mutations/all-mutations` and enumerated by psi-tool's registered-mutation
  set, invokable via `psi-tool action: "mutate"`.
- AC6: Reload uses the session's worktree path to resolve the project prompts
  directory.
- AC7: Reload replaces (does not append to) the session's prompt-template set.
- AC8: All state change flows through `:session/reload-prompts` dispatch; reads
  flow through resolvers.
- AC9: CHANGELOG `[Unreleased]` documents the new command and mutation; user
  docs referencing prompt templates / reload commands are updated.

## Architectural alignment

- Reads via resolvers, writes via mutations through the dispatch pipeline
  (one-way guideline).
- Prompt reload is a **pure root-state replacement**: the `:session/reload-prompts`
  handler performs the `discover-templates` file IO inline and returns a
  `:root-state-update` that sets `:prompt-templates` to the freshly discovered
  vector. This is the architecturally aligned shape because:
  - `:prompt-templates` is **canonical session state** (in `:state*`, written
    via `:root-state-update`), not an external runtime handle.
  - The dispatch sequencing contract runs effects **last** (handler → apply →
    validate → trim → effects), so an effect's result cannot feed a
    `:root-state-update`. To replace canonical state with a discovery result,
    the discovery must happen in the handler.
- This deliberately does **not** mirror the `model-registry/reload` effect
  shape. `model-registry/reload` mutates an **external runtime handle** (the
  ctx-owned, mutable model registry) and its handler returns no
  `:root-state-update`; that surface differs in kind from canonical-state
  replacement and its analogy does not transfer to prompt templates.
  - The only existing precedent for an effect writing canonical state is
    `mark-flushed` calling `apply-root-state-update-in!` from inside the effect
    (a second apply). If an effect path were ever pursued instead of the pure
    handler, it would be a deliberate, documented exception to the pure
    handler-result model — not the default. The default here is the pure
    handler.
- Replay consequences: replay **suppresses effects** but **preserves state
  application** (state application is replayed from the log). Because the
  discovery IO lives in the pure handler, the **last applied `:prompt-templates`
  value is preserved on replay** (the applied `:root-state-update` is part of
  the event record). File-IO-derived template state is inherently
  non-replay-deterministic (the filesystem may differ at replay time), so
  replay does not re-run discovery; it reproduces the value that was applied
  when the reload originally dispatched. There is no "effect for replay
  fidelity" benefit — that framing is incorrect and is dropped.
- No new shim/adapter; reuse `psi.prompt-assets.prompt-templates/discover-templates`.

## Open questions for plan stage

1. ~~Effect vs in-handler discovery.~~ **Resolved** (see Architectural
   alignment): discovery file IO runs **in the pure `:session/reload-prompts`
   handler**, which returns a `:root-state-update` replacing `:prompt-templates`.
   No reload effect; the `model-registry/reload` effect analogy does not apply
   (it mutates an external runtime handle, not canonical state), and there is no
   "effect for replay fidelity" benefit (replay suppresses effects, preserves
   state application).
2. CLI `--prompt-template` extra-path persistence across reload.
3. Whether `/prompts-reload` joins a generalized `/reload` umbrella later
   (out of scope here; keep dedicated command consistent with existing
   `/reload-models`, `/reload-extension-installs`).

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

(`discover-templates` also accepts an `:extra-paths` opt, but **no CLI flag
currently wires it**: startup calls `(pt/discover-templates)` with no opts, so
`:extra-paths` is never populated in practice. The `--prompt-template <path>`
flag referenced in a `prompt_templates.clj` doc-comment is **not implemented**.
Reload therefore omits `:extra-paths` — there is nothing to persist or drop.)

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
  returning a `:text` result summarizing the reload (worktree + template
  count; no diagnostics line — see Return shape A3), mirroring
  `format-reload-models`.
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

**Resolved handler shape.** There is **no** dedicated
`:session/set-prompt-templates` handler and **no** register-in-a-loop. The
`:session/reload-prompts` handler itself:

1. derives the discovery opts (see Discovery inputs),
2. calls `pt/discover-templates` **inline** (handler-local file IO),
3. returns a single `:root-state-update` that **replaces**
   `:prompt-templates` with the freshly discovered vector via
   `(session/session-update session-id #(assoc % :prompt-templates discovered))`.

This mirrors the `:session/set-active-tools` / `:session/set-skills` replace
handlers (which set `:tool-ids` / skills wholesale through a single
`:root-state-update`), rather than the appending
`:session/register-prompt-template` handler. The handler also returns
`{:reloaded? true :count (count discovered) :worktree <path>}` for the command
and mutation surfaces (see Return shape).

### Discovery inputs

Reload re-runs `pt/discover-templates` with this **exact** opts map:

```clojure
{:global-prompts-dir  (:global-prompts-dir pt/default-config)  ; ~/.psi/agent/prompts
 :project-prompts-dir (str (ss/session-worktree-path-in ctx session-id) "/.psi/prompts")}
```

- `:global-prompts-dir` is passed **explicitly** as the `default-config`
  value (rather than relying on the no-arg default) so the resolved opts map
  is fully determined at the reload call site and the worktree-derived project
  dir is the only intentional divergence from startup.
- `:project-prompts-dir` is derived from the **session worktree path**
  (`<worktree>/.psi/prompts`), not process `cwd`.
- `:extra-paths` is **omitted** (none exist — see A2 / OQ#2).
- `:disabled` is **not** passed (reload always discovers).

**Intentional startup divergence.** Startup calls `(pt/discover-templates)`
with **no opts**, which resolves `:project-prompts-dir` to the *process-relative*
`.psi/prompts` (the `default-config` value). Reload instead resolves the
project dir from the **session worktree**. For worktree sessions where
`cwd ≠ worktree`, startup and reload therefore read from **different project
prompt directories** by design — reload is worktree-correct, startup is
cwd-relative. This divergence is intentional: the session's templates belong
to its worktree, and reload corrects the discovery root accordingly. (Aligning
startup to also use the worktree path is out of scope here.)

### Return shape

The `:session/reload-prompts` handler returns:

- `:reloaded?` — `true` on a completed reload
- `:count` — number of templates after reload (`(count discovered)`)
- `:worktree` — the worktree path used (for the command summary)

**No `:diagnostics` key.** `discover-templates` returns a plain vector of
templates and surfaces **no diagnostics channel** (unlike skill discovery).
This task does **not** add error capture to prompt discovery. The return shape
therefore omits `:diagnostics`, and the AC4 command summary reports only
worktree + count (see AC4). (Adding a diagnostics channel to prompt discovery
is a separate task if ever desired.)

The `psi.extension/reload-prompts` mutation exposes exactly these
namespaced `::pco/output` keys (mirroring `add-prompt-template`'s
`:added?`/`:count`):

```clojure
::pco/output [:psi.prompt-template/reloaded?
              :psi.prompt-template/count]
```

returning:

```clojure
{:psi.prompt-template/reloaded? (boolean reloaded?)
 :psi.prompt-template/count     (or count 0)}
```

The mutation does **not** surface `:worktree` (that is a command-summary
concern only).

### No system-prompt refresh

`:session/reload-prompts` does **not** emit `:runtime/refresh-system-prompt`.
`:session/set-skills` and `:session/set-active-tools` emit that effect because
skills and active tools are **enumerated in the system prompt**, so changing
those sets requires rebuilding it. By contrast, `:prompt-templates` are
**`/name`-invoked at message time** and are **not enumerated by any
system-prompt builder** (no system-prompt module reads `:prompt-templates`).
Reloading templates therefore changes only the `/name → expansion` lookup
table consulted on invocation, with no system-prompt content to refresh. The
handler emits **no effects**; it returns only the `:root-state-update` and the
`:reloaded?`/`:count`/`:worktree` return value.

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
- AC4: `/prompts-reload` returns a `:text` result summarizing worktree path
  and resulting template count (consistent with `/reload-models` formatting).
  The summary reports **only** worktree + count — there is no diagnostics line
  (prompt discovery has no diagnostics channel).
- AC5: `psi.extension/reload-prompts` is a registered mutation present in
  `mutations/all-mutations`, enumerated by psi-tool's registered-mutation set,
  invokable via `psi-tool action: "mutate"`, with `::pco/output`
  `[:psi.prompt-template/reloaded? :psi.prompt-template/count]`.
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
2. ~~CLI `--prompt-template` extra-path persistence across reload.~~
   **Resolved / moot.** No `--prompt-template` flag exists; startup never
   passes `:extra-paths`, so there is nothing to persist or drop. Reload omits
   `:extra-paths`. If an extra-paths CLI flag is ever implemented and persisted
   on the session, reload-persistence becomes a follow-up task at that time.
3. Whether `/prompts-reload` joins a generalized `/reload` umbrella later
   (out of scope here; keep dedicated command consistent with existing
   `/reload-models`, `/reload-extension-installs`).

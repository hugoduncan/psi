# Steps — 204 prompts-reload command + mutation

Vertical slices from plan.md. Tick each item with its sha / decision note.

## Slice 1 — `:session/reload-prompts` dispatch handler

- [ ] Add `[psi.prompt-assets.prompt-templates :as pt]` require to
      `dispatch_handlers/session_mutations.clj` (and confirm the worktree
      helper `ss`/`session/session-worktree-path-in` is already in scope).
- [ ] Register `:session/reload-prompts` core handler: resolve opts
      `{:global-prompts-dir (:global-prompts-dir pt/default-config)
        :project-prompts-dir (str (session/session-worktree-path-in ctx session-id) "/.psi/prompts")}`,
      call `(pt/discover-templates opts)` inline.
- [ ] Handler returns a single `:root-state-update`
      `(session/session-update session-id #(assoc % :prompt-templates discovered))`
      and a `:return {:reloaded? true :count (count discovered) :worktree <path>}`;
      emits **no** `:effects` (no `:runtime/refresh-system-prompt`).
- [ ] Add a dispatch-handler test: seed a session with an explicit worktree
      path and pre-existing `:prompt-templates`; dispatch
      `:session/reload-prompts`; assert `:prompt-templates` is **replaced** by
      the discovered vector (AC7) and the return map carries
      `:reloaded?`/`:count`/`:worktree`.
- [ ] Add a test asserting the handler emits **no** effects (no
      `:runtime/refresh-system-prompt`).
- [ ] `clj-kondo` clean for the changed handler ns; focused handler test green.

## Slice 2 — `reload-prompts-in!` core entry fn

- [ ] Add `reload-prompts-in!` to `session_settings.clj` mirroring
      `reload-models-in!`: `(dispatch/dispatch! ctx :session/reload-prompts
      {:session-id session-id} {:origin :core})`, returning the handler
      `:return` map `{:reloaded? :count :worktree}`.
- [ ] Confirm `dispatch!` surfaces the handler `:return` map (not an effect
      result) — add/extend a focused test asserting the returned
      `:reloaded?`/`:count`/`:worktree` values.
- [ ] `clj-kondo` clean; focused test green.

## Slice 3 — `psi.extension/reload-prompts` mutation

- [ ] Add `reload-prompts` `pco/defmutation` to `mutations/prompts.clj`:
      `::pco/op-name 'psi.extension/reload-prompts`,
      `::pco/params [:psi/agent-session-ctx :session-id]`,
      `::pco/output [:psi.prompt-template/reloaded? :psi.prompt-template/count]`.
- [ ] Mutation body calls the core path (`reload-prompts-in!` or
      `dispatch!`) and returns
      `{:psi.prompt-template/reloaded? (boolean reloaded?)
        :psi.prompt-template/count (or count 0)}` — does **not** surface
      `:worktree`.
- [ ] Add `reload-prompts` to `all-mutations` in `mutations/prompts.clj`.
- [ ] Add a mutation test: invoke the mutation, assert output keys/values and
      that `:prompt-templates` was replaced via dispatch (AC5).
- [ ] Add a psi-tool visibility test (extend/mirror `psi_tool_mutate_test`):
      assert `'psi.extension/reload-prompts` is in the registered-mutation set
      and is invokable via `action: "mutate"` with
      `params {:session-id "..."}` (AC5).
- [ ] `clj-kondo` clean; focused mutation + psi-tool tests green.

## Slice 4 — `/prompts-reload` command

- [ ] Add `format-prompts-reload` in `commands.clj` mirroring
      `format-reload-models`: read `worktree-path` via
      `ss/session-worktree-path-in`, call the core reload fn, build a `:text`
      summary with **worktree + count only** (no diagnostics line) (AC4).
- [ ] Add `"/prompts-reload" :prompts-reload` to `exact-command-handlers`.
- [ ] Add `:prompts-reload {:type :text :message (format-prompts-reload ctx
      session-id)}` to the exact-command `case`.
- [ ] Add a `/help` listing line for `/prompts-reload` (near the existing
      `/reload-models` / `/reload-extension-installs` entries).
- [ ] Add a command test (mirror reload-models command tests): assert the
      `/prompts-reload` result is `{:type :text}` and the message contains the
      worktree path and resulting template count (AC4).
- [ ] `clj-kondo` clean; focused command test green.

## Slice 5 — docs, changelog, coherence

- [ ] Add `CHANGELOG.md` `[Unreleased] → Added` entry: new `/prompts-reload`
      command + new `psi.extension/reload-prompts` mutation (psi-tool visible)
      that re-discovers prompt templates from disk and replaces the session's
      templates (AC9).
- [ ] Update docs referencing prompt templates / reload commands (`doc/tui.md`
      command/help listing; any prompt-template reference) to mention
      `/prompts-reload` (AC9).
- [ ] End-to-end acceptance proof (AC1–AC3, AC6): with a temp worktree
      `<wt>/.psi/prompts`, prove (a) editing `foo.md` body then reload makes
      `/foo` expand with new content, (b) adding `bar.md` then reload makes
      `/bar` discoverable, (c) deleting `baz.md` then reload removes it — all
      against the **worktree** root.
- [ ] Run `clj-kondo --lint` over all changed src + test paths; fix findings.
- [ ] Run full `bb test`; confirm green.
- [ ] Re-read changed files (sync) and confirm coherence across
      handler/core/mutation/command/docs/changelog.

## Plan/steps ambiguity follow-ups (2026-06-01)

- [ ] P1: Pin the correct per-file worktree-helper alias (only
      `psi.session-state.state` defines `session-worktree-path-in`). Use
      `session/session-worktree-path-in` in `session_mutations.clj`
      (`session` = `psi.session-state.state`; `ss` there =
      `psi.session-state.init`, which lacks it) and
      `ss/session-worktree-path-in` in `session_settings.clj` / `commands.clj`.
      Remove the ambiguous "`ss`/`session`" slash-alternative from slice 1 and
      the plan "Surfaces touched" note.
- [ ] P2: In slice 1, name the single worktree binding so the opts
      `:project-prompts-dir` and the `:return :worktree` value derive from one
      computed path (replace the bare `<path>` placeholder); avoid recomputing
      `session-worktree-path-in` twice in the handler.
- [ ] P3: Resolve the slice-3 mutation entry-point either/or to
      `reload-prompts-in!` only (the plan's single shared entry point); drop
      the `dispatch!` alternative from the step wording.

# Steps — 204 prompts-reload command + mutation

Vertical slices from plan.md. Tick each item with its sha / decision note.

## Slice 1 — `:session/reload-prompts` dispatch handler

- [ ] Add `[psi.prompt-assets.prompt-templates :as pt]` require to
      `dispatch_handlers/session_mutations.clj` (and confirm
      `session/session-worktree-path-in` — `session` =
      `psi.session-state.state` — is already in scope; the `ss` alias in this
      ns is `psi.session-state.init`, which does **not** define it).
- [ ] Register `:session/reload-prompts` core handler: `let`-bind a single
      `worktree-path` = `(session/session-worktree-path-in ctx session-id)`,
      then resolve opts
      `{:global-prompts-dir (:global-prompts-dir pt/default-config)
        :project-prompts-dir (str worktree-path "/.psi/prompts")}`,
      call `(pt/discover-templates opts)` inline.
- [ ] Handler returns a single `:root-state-update`
      `(session/session-update session-id #(assoc % :prompt-templates discovered))`
      and a `:return {:reloaded? true :count (count discovered) :worktree worktree-path}`
      (reusing the same `worktree-path` binding from the opts step — no second
      `session-worktree-path-in` call); emits **no** `:effects` (no
      `:runtime/refresh-system-prompt`).
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
- [ ] Add a `reload-prompts-in!` **re-export** to `psi.agent-session.core`
      delegating to `settings/reload-prompts-in!` (mirror `core.clj:181`'s
      `reload-models-in!` re-export). This is the surface both the command
      (`session/reload-prompts-in!`, `session` = `psi.agent-session.core`) and
      the mutation (`core/reload-prompts-in!`) call — matching the live
      `reload-models` surface pair (command `commands.clj:255` →
      `core/reload-models-in!`; mutation `mutations/session.clj:283` →
      `core/reload-models-in!`).
- [ ] Confirm `dispatch!` surfaces the handler `:return` map (not an effect
      result) — add/extend a focused test asserting the returned
      `:reloaded?`/`:count`/`:worktree` values.
- [ ] `clj-kondo` clean; focused test green.

## Slice 3 — `psi.extension/reload-prompts` mutation

- [ ] Add `reload-prompts` `pco/defmutation` to `mutations/prompts.clj`:
      `::pco/op-name 'psi.extension/reload-prompts`,
      `::pco/params [:psi/agent-session-ctx :session-id]`,
      `::pco/output [:psi.prompt-template/reloaded? :psi.prompt-template/count]`.
- [ ] Mutation body calls the single shared core entry point
      `core/reload-prompts-in!` (passing `agent-session-ctx` as the `ctx` arg
      of `reload-prompts-in! [ctx session-id]`), **not** `dispatch!` directly,
      and returns
      `{:psi.prompt-template/reloaded? (boolean reloaded?)
        :psi.prompt-template/count (or count 0)}` — does **not** surface
      `:worktree`. This matches the live `reload-models` mutation
      (`mutations/session.clj:283`), which already calls `core/reload-models-in!`
      (not `dispatch!`); it is **not** a divergence from that reload idiom.
      (The `add-prompt-template`/`register-*` mutations in `prompts.clj` call
      `dispatch!` directly only because they have no shared core fn; mirror
      `add-prompt-template` for the `::pco/output` shape, but `reload-models`
      for the core-fn invocation.)
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
      `ss/session-worktree-path-in`, call `session/reload-prompts-in!`
      (`session` = `psi.agent-session.core`, the slice-2 re-export — exactly as
      `format-reload-models` (`commands.clj:255`) calls
      `session/reload-models-in!`), build a `:text` summary with **worktree +
      count only** (no diagnostics line) (AC4).
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

- [x] P1: Pinned per-file worktree-helper alias. Slice-1 now states
      `session/session-worktree-path-in` (`session` = `psi.session-state.state`;
      noted `ss` = `psi.session-state.init` lacks it); removed the
      `ss`/`session` slash-alternative. Updated plan "Surfaces touched" + the
      "Exact discovery opts" example to `session/`. (`session_settings.clj` /
      `commands.clj` keep `ss/session-worktree-path-in` — `ss` =
      `psi.session-state.state` there — already correct in their slices.)
- [x] P2: Named the single `worktree-path` binding in slice 1; opts
      `:project-prompts-dir` and the `:return :worktree` value both derive from
      it (bare `<path>` placeholder removed; no double `session-worktree-path-in`
      call). Plan opts example shows the `let` binding.
- [x] P3: Resolved slice-3 mutation entry point to `reload-prompts-in!` only;
      dropped the `dispatch!` alternative from the step wording.

## Plan/steps inconsistency follow-ups (2026-06-01)

- [x] I1: Command core-fn surface pinned to **mirror `/reload-models` exactly**.
      Added a `reload-prompts-in!` re-export to `psi.agent-session.core`
      (delegating to `settings/reload-prompts-in!`, paralleling `core.clj:181`);
      added `core.clj` to plan "Surfaces touched" + slice order 2; slice 2 now
      has a re-export step and slice 4 calls `session/reload-prompts-in!`
      (`session` = `psi.agent-session.core`) — exactly as
      `format-reload-models` (`commands.clj:255`) calls `session/reload-models-in!`.
      No direct-`settings/` divergence taken.
- [x] I2: Mutation-idiom contradiction **resolved as no divergence**. The
      premise ("every `prompts.clj` mutation calls `dispatch!` directly") held
      only for `prompts.clj`; the live `reload-models` mutation
      (`mutations/session.clj:283`) — the true reload analog — already calls
      `core/reload-models-in!`, **not** `dispatch!`. So routing `reload-prompts`
      through `core/reload-prompts-in!` mirrors the `reload-models` mutation
      surface; it is not a departure. Slice-3 step now: mirror
      `add-prompt-template` for `::pco/output` shape but `reload-models` for the
      core-fn invocation; mutation passes `agent-session-ctx` as the `ctx` arg
      of `reload-prompts-in! [ctx session-id]`. Plan "Key decisions" records the
      shared-`core.clj`-re-export rationale.

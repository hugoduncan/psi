# Plan — 204 prompts-reload command + mutation

## Approach

Add an in-session prompt-template reload surface that re-discovers `.md`
templates from disk and **replaces** the session's `:prompt-templates`,
exposed both as a `/prompts-reload` command and as a registered
`psi.extension/reload-prompts` mutation (psi-tool visible). The design has
been fully reviewed (architecture-fit, ambiguity, inconsistency) and all open
questions are resolved; this plan turns the resolved decisions into an ordered
build.

The single source-of-truth change path is a **pure dispatch handler**
`:session/reload-prompts` that:

1. resolves the discovery opts from the session worktree,
2. runs `pt/discover-templates` **inline** (handler-local file IO),
3. returns a single `:root-state-update` replacing `:prompt-templates` with
   the freshly discovered vector,
4. returns `{:reloaded? true :count (count discovered) :worktree <path>}`,
5. emits **no effects** (templates are `/name`-invoked, not enumerated in the
   system prompt — no `:runtime/refresh-system-prompt`).

This mirrors the replace shape of `:session/set-active-tools` /
`:session/set-skills` (`:root-state-update` wholesale replace) — **not** the
`:session/reload-models` effect shape (which mutates an external runtime
handle and returns no `:root-state-update`).

### Key decisions (carried from design.md)

- **Pure handler, in-handler IO** — not an effect. Effects run last in the
  dispatch sequence, so an effect result cannot feed a `:root-state-update`;
  canonical-state replacement must compute the value in the handler.
- **Replace, not append** — wholesale `assoc :prompt-templates discovered`,
  unlike the appending `:session/register-prompt-template`.
- **Exact discovery opts** (handler in `session_mutations.clj`, so
  `session/` = `psi.session-state.state`; `let`-bind `worktree-path` once and
  reuse it for both the opts and the `:worktree` return value):
  ```clojure
  (let [worktree-path (session/session-worktree-path-in ctx session-id)]
    {:global-prompts-dir  (:global-prompts-dir pt/default-config)
     :project-prompts-dir (str worktree-path "/.psi/prompts")})
  ```
  `:global-prompts-dir` passed **explicitly** (the `default-config` value);
  `:project-prompts-dir` from the **session worktree** (intentional divergence
  from cwd-relative startup); `:extra-paths` omitted; `:disabled` not passed.
- **No diagnostics** — `discover-templates` returns a plain vector with no
  diagnostics channel; return shape and the command summary report only
  worktree + count.
- **Mutation output** pinned to
  `[:psi.prompt-template/reloaded? :psi.prompt-template/count]`, mirroring
  `add-prompt-template`'s `[:added? :count]`; the mutation does **not** surface
  `:worktree` (command-summary concern only).
- **psi-tool visibility** is automatic via adding `reload-prompts` to
  `prompts/all-mutations` (aggregated into `mutations/all-mutations`,
  enumerated by the registered-mutation set). No psi-tool code change.

### Surfaces touched

- `dispatch_handlers/session_mutations.clj` — new `:session/reload-prompts`
  handler (requires `prompt-templates`; worktree helper via
  `session/session-worktree-path-in`, `session` = `psi.session-state.state` —
  **not** the `ss` = `psi.session-state.init` alias, which lacks it).
- `session_settings.clj` — a thin `reload-prompts-in!` core fn dispatching
  `:session/reload-prompts` (mirrors `reload-models-in!`), so the command and
  mutation share one entry point.
- `mutations/prompts.clj` — new `reload-prompts` mutation; added to
  `all-mutations`.
- `commands.clj` — `format-prompts-reload`, `/prompts-reload` →
  `:prompts-reload` in `exact-command-handlers`, a `case` arm, and `/help`
  listing.
- `CHANGELOG.md` `[Unreleased] → Added`.
- Docs referencing prompt templates / reload commands (`doc/tui.md` help/
  command listing; any prompt-template reference).

## Risks

- **In-handler file IO** is a deliberate, documented departure from "pure
  handlers do no IO". It is the architecturally aligned choice here (see
  design Architectural alignment) but reviewers may flag it — keep the design
  rationale referenced in `implementation.md` when building.
- **Worktree-vs-cwd divergence**: reload reads `<worktree>/.psi/prompts` while
  startup reads process-relative `.psi/prompts`. For worktree sessions these
  differ. This is intended; tests must construct sessions with an explicit
  worktree path so AC1–AC3 and AC6 exercise the worktree root, not cwd.
- **`reload-models-in!` signature mismatch**: `reload-models-in!` returns
  `{:error :count}` from a handler that returns `:return-effect-result? true`.
  `reload-prompts` returns a plain handler `:return` map — confirm the core
  fn surfaces the handler `:return` (dispatch! return semantics) rather than an
  effect result.
- **Discovery cost**: reload does synchronous disk IO on the dispatch thread;
  acceptable (manual, infrequent, mirrors `/reload-models`).

## Slice order (vertical slices; each independently verifiable)

1. **Reload handler** — `:session/reload-prompts` dispatch handler + tests
   (replace semantics, return shape, no effects). Core of the feature.
2. **Core entry fn** — `reload-prompts-in!` in `session_settings.clj` (shared
   by command + mutation) + test.
3. **Mutation** — `reload-prompts` in `mutations/prompts.clj`, added to
   `all-mutations`; tests for output keys, dispatch, and psi-tool
   registered-mutation visibility.
4. **Command** — `/prompts-reload` exact command + `format-prompts-reload`
   summary + `/help` listing; command-result text test.
5. **Docs + changelog + coherence** — CHANGELOG `[Unreleased]` entry, doc
   updates, full lint + `bb test`, and an end-to-end AC1–AC3 edit/add/delete
   reload proof.

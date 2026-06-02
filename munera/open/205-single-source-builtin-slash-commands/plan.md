# 205 — Plan

## Approach

Single-source the built-in slash-command surface (names + descriptions) on the
backend behind one ordered keyed spec table, expose it via a new EQL resolver,
and have TUI + Emacs consume that attribute exactly like the existing
`:psi.extension/command-names` path. This is a single-source-of-truth /
one-way correction: no new pattern is introduced, the UIs gain one more
attribute on queries they already make.

The design is stable (A1, B1–B5, C1 all resolved). Option B (single keyed spec
table → derived routing/help/resolver projections) is chosen so name drift is
*structurally unrepresentable*.

### Backend (commands.clj) — the single keyed spec table

Introduce one ordered `array-map`, `builtin-command-specs`, authored in the
**current `format-help` display order**. Keys are **leading-slash-prefixed**
(`"/help"`, `"/model"`, …). Each entry:

- `:kinds` — required set `⊆ #{:exact :prefixed}`, non-empty.
- `:handler` — required iff `:exact ∈ :kinds` (the dispatch keyword, e.g.
  `:help`, `:quit`, `:reload-models`).
- `:description` — required short string (resolver-exposed).
- `:usage` — optional help-only arg-hint (e.g.
  `"[provider model-id [session|project|user]]"`), not resolver-exposed.
- `:hide-in-help?` — optional boolean, help-only suppression for aliases
  (`/?`, `/exit`), not resolver-exposed, not UI-consulted.

Derive every existing name surface from this one table (no independent
literals):

- `exact-command-handlers` = projection: `{key → (:handler s)}` for entries with
  `:exact ∈ :kinds`. Keys stay `/`-prefixed (zero transform).
- `prefixed-command-prefixes` = projection: `(vec keys)` for entries with
  `:prefixed ∈ :kinds`, in table order.
- `builtin-command-names` = `(set (map strip-slash (keys table)))` — no
  `concat`.
- `format-help` built-in lines = iterate `(seq table)` in order, **skip
  `:hide-in-help?`**, render `"  /name [:usage ]— :description"`. `/skill:name`
  helper line + trailing prose stay literal; Prompt/Skills/Extension sections
  unchanged.

The prefixed-command **handler** `case` in `dispatch-prefixed-command` stays
hand-written (handler-wiring residual, design-acknowledged out of scope). A
narrow coherence test asserts its branch keys match the prefixed spec-table
entries.

Concrete spec-table membership (current routing ∪ help, swept complete) —
exact-only: `/quit /exit(alias) /new /resume /status /history /help /?(alias)
/prompts /skills /worktree /logout /reload-models /reload-prompts
/reload-extension-installs`; prefixed-only: `/tree /jobs /job /cancel-job
/remember /model /thinking /speed /effort /login`; dual `#{:exact :prefixed}`:
`/project-repl`. `/reload-prompts` (task 204) is included so it appears in both
UIs for free.

### Backend resolver

Add `builtin-commands-resolver` in `resolvers/extensions.clj` (alongside
`extension-commands-resolver`, mirroring it). It reads `agent-session-ctx`, calls
a new public `commands/builtin-command-specs-for-resolver` (returns vector of
`{:name :description}`, bare names, **table order**), and outputs
`:psi.agent-session/builtin-command-specs` (and a derived
`:psi.agent-session/builtin-command-names` vector for symmetry with the
extension surface). Register it in `extensions.clj` `resolvers` (so it flows into
`all-resolvers` and the graph attr-index).

Keeping the resolver in `agent-session` co-locates it with the routing maps it
must equal (architecture-fit review confirmed this placement).

### TUI consumption

- `support.clj`: extend `command-refresh-query` and `build-init`'s introspection
  query with `:psi.agent-session/builtin-command-specs`; store
  `:builtin-command-specs` in state (parallel to `:extension-command-names`).
- `autocomplete.clj`: build built-in candidates from
  `state :builtin-command-specs` (slash-prefix the bare names) instead of
  `shared/builtin-slash-commands`.
- `shared.clj`: remove `builtin-slash-commands` as a source of truth (delete, or
  empty + drop the require usage).

### Emacs consumption

- `psi-session-commands.el`: extend the slash-completion query string (line 257)
  with `:psi.agent-session/builtin-command-specs`; add a frame-extractor and
  store backend built-in specs into state (new globals slot, parallel to
  `extension-command-names`). Extend `psi-emacs--apply-slash-completion-data` to
  carry built-in specs.
- `psi-completion.el`: `psi-emacs--state-slash-command-specs` merges
  backend built-in specs first; `psi-emacs-slash-command-specs` `defcustom`
  becomes a **user override/supplement** — default trimmed to Emacs-only
  affordances (`/skill:`) plus a documented user-addition slot; backend wins on
  name collision (resolves open question #3).
- `psi-events.el`: thread built-in specs through the same data-application path
  that already handles `extension-command-names` / templates.

### Resolver-output / key-form invariants (from design)

- Resolver output names are **bare** (no `/`), matching
  `:psi.extension/command-names`; UIs prefix.
- Spec-table keys are `/`-prefixed; only `builtin-command-names` and the
  resolver `strip-slash`.
- Resolver output is `{:name :description}` only (`:usage`/`:hide-in-help?`/
  `:kinds`/`:handler` never exposed), in table order.

## Slice order (vertical, each independently shippable + green)

1. **Backend spec table + derived projections** — introduce
   `builtin-command-specs`; derive `exact-command-handlers`,
   `prefixed-command-prefixes`, `builtin-command-names`, and `format-help`
   built-in lines from it; add the prefixed-`case` branch-coherence test. Prove
   routing/help/name behaviour is byte-for-byte unchanged. No resolver/UI yet.
2. **Backend resolver** — public spec accessor +
   `builtin-commands-resolver` + registration + graph discovery; resolver
   output tests (shape `{:name :description}`, bare names, table order,
   representative `/reload-models`/`/reload-prompts` present).
3. **TUI consumption** — query the new attribute, build autocomplete from it,
   retire `shared/builtin-slash-commands`; TUI autocomplete test that
   `/reload-models` is present and sourced from backend specs.
4. **Emacs consumption** — query attribute, merge backend specs first, repurpose
   `defcustom`; capf test that built-in specs from a queried frame include
   `/reload-models`; byte-compile clean.
5. **Coherence lock + docs/changelog** — end-to-end test that adding a built-in
   spec-table entry surfaces in both TUI and Emacs autocomplete with no UI-list
   edit (AC6); CHANGELOG `[Unreleased]`; docs describing UI command surfacing;
   full `bb test` + targeted lint.

Slice ordering is dependency-respecting: each later slice consumes the prior
slice's backend surface. Slice 1 is the highest-risk structural change and is
isolated so the routing-unchanged proof gates everything downstream.

## Risks

- **Routing regression from projection conversion (Slice 1, highest risk).**
  Converting `exact-command-handlers` / `prefixed-command-prefixes` from literals
  to derived projections must produce *exactly* the same maps/vectors. Mitigate:
  keep the same key form (`/`-prefixed, zero transform); add an explicit test
  asserting derived projections `=` the prior literal values (snapshot the
  current literals in the test) before deleting them; run the full existing
  commands/dispatch test suite.
- **`format-help` output drift.** Help text is user-visible and partly
  whitespace-sensitive. Mitigate: author the table in current help order, encode
  `:usage` so arg-hint lines (`/model …`, `/speed …`) render identically, skip
  `:hide-in-help?`, and add a `format-help` golden/substring test that the
  built-in block matches the current rendering (alias lines absent).
- **Dual-kind `/project-repl`.** Must feed both projections; exact-first dispatch
  precedence unchanged. Mitigate: explicit test that `/project-repl` is in both
  derived projections and that bare vs `/project-repl <args>` dispatch is
  unchanged.
- **Emacs `defcustom` migration breaks existing user config.** Trimming the
  default value could surprise users who customised it. Mitigate: backend specs
  authoritative; `defcustom` retained, merged (backend-wins) so a stale user
  value never *removes* a backend command; document the change in changelog.
- **Graph/attr-index registration miss.** Forgetting to add the resolver to
  `extensions.clj` `resolvers` means the attribute is undiscoverable. Mitigate:
  Slice 2 graph-discovery test asserts the attr is in the resolver/attr index.
- **Prefixed `case` handler-wiring residual.** Out of scope by design, but a
  prefix could be in the table with no `case` branch. Mitigate: narrow
  branch-coherence test (Slice 1) — set of prefixed table keys `=` set of
  `dispatch-prefixed-command` `case` keys.

## Open questions (carried, non-blocking)

- Open question #3 (Emacs `defcustom` residual role) is **resolved in this plan**:
  backend authoritative for built-ins; `defcustom` repurposed as user
  override/supplement (Emacs-only `/skill:` + user additions), backend-wins on
  collision. Confirm exact trimmed default during Slice 4.
- Whether to also add a `:psi.agent-session/builtin-command-names` bare-vector
  attribute alongside `:psi.agent-session/builtin-command-specs` for symmetry —
  plan includes it; drop if unused by either UI after Slices 3–4.

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
- `:hide-in-help?` — optional boolean, help-only suppression for routed-but-
  help-absent entries (aliases `/?`, `/exit`, plus the real dual-kind command
  `/project-repl`, which has never had a help line), not resolver-exposed, not
  UI-consulted.

Derive every existing name surface from this one table (no independent
literals):

- `exact-command-handlers` = projection: `{key → (:handler s)}` for entries with
  `:exact ∈ :kinds`. Keys stay `/`-prefixed (zero transform).
- `prefixed-command-prefixes` = projection: `(vec keys)` for entries with
  `:prefixed ∈ :kinds`, in table order.
- `builtin-command-names` = `(set (map strip-slash (keys table)))` — no
  `concat`.
- `format-help` built-in lines = iterate `(seq table)` in order, **skip
  `:hide-in-help?`** (`/?`, `/exit`, `/project-repl`), render
  `"  /name [:usage ]— :description"`. `/skill:name`
  helper line + trailing prose stay literal; Prompt/Skills/Extension sections
  unchanged.

The prefixed-command **handler** `case` in `dispatch-prefixed-command` stays
hand-written (handler-wiring residual, design-acknowledged out of scope). Its
branch keys are named once in `commands/prefixed-case-branches` (the literal
`case` needs compile-time keys), with a load-time `assert` that they equal the
prefixed spec-table projection, so spec-table↔`case` drift is caught at load
(`unreachable > forbidden`). The narrow coherence test reads that same live def,
genuinely locking the seam (R1 follow-up).

Concrete spec-table membership (current routing ∪ help, swept complete) —
exact-only: `/quit /exit(alias) /new /resume /status /history /help /?(alias)
/prompts /skills /worktree /logout /reload-models /reload-prompts
/reload-extension-installs`; prefixed-only: `/tree /jobs /job /cancel-job
/remember /model /thinking /speed /effort /login`; dual `#{:exact :prefixed}`:
`/project-repl`. `/reload-prompts` (task 204) is included so it appears in both
UIs for free. `:hide-in-help? true` is carried by `/?`, `/exit`, **and
`/project-repl`** — the three routed commands the current `format-help` omits —
so whole-table help derivation reproduces the existing help membership exactly
(I1).

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

- `support.clj`: extend `command-refresh-query` (currently
  `[:psi.extension/command-names]`, support.clj:188) and `build-init`'s
  introspection query (support.clj:226–230) with
  `:psi.agent-session/builtin-command-specs`; store `:builtin-command-specs` in
  state (parallel to `:extension-command-names`).
- `autocomplete.clj`: build built-in candidates from
  `state :builtin-command-specs` (slash-prefix the bare names) instead of
  `shared/builtin-slash-commands` at the line-59 `concat`.
- `shared.clj`: remove `builtin-slash-commands` as a source of truth.

#### P4 — TUI refresh fn: extend in place, no sibling (resolves steps "(or sibling)")

**Decision.** Extend `refresh-extension-command-names` **in place** (no new
sibling fn, no rename). Today it issues `command-refresh-query`, destructures
`:psi.extension/command-names` from the result, and `assoc`es
`:extension-command-names`. The change:

- `command-refresh-query` becomes
  `[:psi.extension/command-names :psi.agent-session/builtin-command-specs]`.
- The single `refresh-extension-command-names` body destructures **both** keys
  from the one query result and `assoc`es **both** state slots
  (`:extension-command-names` from `:psi.extension/command-names`,
  `:builtin-command-specs` from `:psi.agent-session/builtin-command-specs`),
  guarded the same way (only `assoc` a slot when its value is a vector).

Rationale: one query already round-trips; folding the new attribute into the
existing query + existing refresh fn avoids a second query/refresh and keeps a
single command-refresh code path. The fn name stays
`refresh-extension-command-names` (it already refreshes the command-completion
surface; broadening it to built-ins is in-charter and a rename would churn its
call sites for no benefit). `build-init`'s introspection query is extended in
parallel and seeds `:builtin-command-specs` directly into initial state.

#### P5 — `shared.clj/builtin-slash-commands` disposal (resolves the double-spec)

**Decision (single, pinned).** Delete **only** the `builtin-slash-commands`
`def` from `shared.clj`, and remove **only** the `shared/builtin-slash-commands`
symbol from the `autocomplete.clj` line-59 `concat`. The
`[… psi.tui.app.shared :as shared]` require in `autocomplete.clj` **stays** — it
is still used by `shared/input-value` (14, 226), `shared/input-pos` (15), and
`shared/set-input-value` (245). The earlier plan/steps "empty + drop the require
usage" alternative is dropped: dropping the `shared` require would break those
remaining usages. No other `shared.clj` symbol is touched.

### Emacs consumption

- `psi-session-commands.el`: extend the slash-completion query string
  (`psi-emacs--prompt-template-query`, line 256 — **not** 257) with
  `:psi.agent-session/builtin-command-specs`; add a frame-extractor
  (`psi-emacs--builtin-command-specs-from-query-frame`, parallel to
  `…-extension-command-names-from-query-frame`) and store backend built-in specs
  into state (new globals slot, parallel to `extension-command-names`). Extend
  `psi-emacs--apply-slash-completion-data` to carry built-in specs (see P1).
- `psi-completion.el`: `psi-emacs--state-slash-command-specs` merges
  backend built-in specs first; `psi-emacs-slash-command-specs` `defcustom`
  becomes a **user override/supplement** — default trimmed to Emacs-only
  affordances (`/skill:`) plus a documented user-addition slot; backend wins on
  name collision (resolves open question #3).
- `psi-events.el`: thread built-in specs through the same data-application path
  that already handles `extension-command-names` / templates (see P3).

#### P1 — `apply-slash-completion-data` threading shape + all call sites

**Decision: gain a third positional arg.**
`psi-emacs--apply-slash-completion-data` changes arity from
`(names templates)` to `(names builtin-specs templates)` (built-in specs in the
middle, names first to preserve the existing leading arg). `builtin-specs` is a
list of `(:name … :description …)` alists, stored on the new globals slot. ALL
sites change together:

1. **Definition** — `psi-session-commands.el:302`
   `(defun psi-emacs--apply-slash-completion-data (names builtin-specs templates) …)`;
   body `setf`s the new globals slot from `builtin-specs` and folds built-in
   specs into the token (see P2).
2. **`declare-function`** — `psi-events.el:28`
   `(declare-function psi-emacs--apply-slash-completion-data "psi-session-commands" (names builtin-specs templates))`.
3. **Query-frame call** — `psi-session-commands.el:323` inside
   `psi-emacs--refresh-slash-completion-data`: extract built-in specs from the
   frame (`psi-emacs--builtin-command-specs-from-query-frame`) and pass them as
   the middle arg.
4. **Event-data call** — `psi-events.el:109` inside
   `psi-emacs--slash-completion-data-changed-p`: pass the event-extracted
   built-in specs (see P3) as the middle arg.

A new globals slot `builtin-command-specs` is added to the state struct
(`psi-globals.el`, parallel to `extension-command-names` at line 100) and
seeded `nil` in `psi-lifecycle.el` (parallel to `slash-completion-token` at
line 85).

#### P2 — Slash-completion change-detection token includes built-in specs

**Decision.** Built-in specs **are folded into the change-detection token** so a
built-in-spec-only change is detected and Emacs autocomplete refreshes (AC5/AC6).
Two token constructors must both gain a built-in-specs component, kept structurally
identical so equal data yields equal tokens:

- `psi-emacs--slash-completion-token` (`psi-session-commands.el:292`) gains a
  `builtin-specs` parameter and emits a `:builtins` token segment (list of
  `(name description)` pairs, normalized the same way templates are).
- The inline `next-token` in `psi-emacs--slash-completion-data-changed-p`
  (`psi-events.el:80`) gains the matching `:builtins` segment built from the
  event-extracted built-in specs.

**Change-detection guard extension (resolves I2).** Adding the `:builtins`
segment alone is insufficient: `next-token` is currently computed only under
`(and (or has-command-names has-templates) …)` and used under `(when next-token …)`,
so an event carrying ONLY built-in specs (no `:extension-command-names`, no
`:prompt-templates`) leaves both `has-command-names` and `has-templates` nil →
`next-token` nil → no refresh, regardless of the new segment. P2 therefore also
extracts `raw-builtin-specs`
(`psi-emacs--event-data-get data '(:builtin-command-specs builtin-command-specs)`),
computes `has-builtin-specs`, and adds it to the guard's `or`:
`(and (or has-command-names has-templates has-builtin-specs) …)`. A
built-in-spec-only event then produces a non-nil `next-token` and triggers the
refresh (AC5/AC6), and the `apply-slash-completion-data` call inside the `when`
passes the event-extracted built-in specs as its middle arg (P1 site 4).

`psi-emacs--apply-slash-completion-data` recomputes the token via the updated
`psi-emacs--slash-completion-token` call (now passing built-in specs). Token
segment order is fixed (`:commands … :builtins … :templates …`) and identical in
both constructors so the change-detection compares like-for-like.

#### P3 — Built-in specs arrival channel(s): BOTH query frame and event path

**Decision.** Built-in specs arrive on **both** existing channels, matching how
`extension-command-names` already travels:

- **Channel 1 — `query_eql` frame** (`psi-emacs--refresh-slash-completion-data`,
  via the extended `psi-emacs--prompt-template-query` string): add
  `psi-emacs--builtin-command-specs-from-query-frame` to extract
  `:psi.agent-session/builtin-command-specs` from the query result, passed to
  `apply-slash-completion-data` (P1 site 3).
- **Channel 2 — session-update event-data path**
  (`psi-emacs--slash-completion-data-changed-p`, `psi-events.el:80`): extract
  built-in specs from the pushed event data (via
  `psi-emacs--event-data-get` with `(:builtin-command-specs builtin-command-specs)`
  keys, parallel to the existing `:extension-command-names` extraction), fold
  them into `next-token` (P2), and pass them to `apply-slash-completion-data`
  (P1 site 4).

Both channels feed the same `apply-slash-completion-data` arity and the same
token shape, so neither path can regress the other (a built-in-spec change on
either channel updates the token and triggers a refresh; a names/templates-only
change still detects via its own token segment). This mirrors the existing
two-channel handling of `extension-command-names`/`prompt-templates` exactly — no
new pattern.

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
  `:hide-in-help?` (`/?`, `/exit`, `/project-repl`), and add a `format-help`
  golden/substring test that the built-in block matches the current rendering
  (alias lines `/?`/`/exit` absent **and** no new `/project-repl` line — I1).
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

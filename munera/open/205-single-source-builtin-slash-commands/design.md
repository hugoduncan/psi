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
- **Derivation, not duplication** (single keyed spec table — see decision
  "Single spec table is the source of names"):
  - The spec table is the **sole** place built-in command names exist. Its keys
    are the names; every other name surface is a pure function of those keys.
  - `builtin-command-names` derives from `(keys spec-table)` (strip slash, set)
    — no independent `concat` of routing literals.
  - `exact-command-handlers` and `prefixed-command-prefixes` become **derived
    projections** of the spec table (filtered by each entry's dispatch kind),
    not independent literals, so a routed name cannot diverge from a described
    name — name drift is unrepresentable, not merely test-rejected.
  - `format-help`'s built-in command lines derive from the spec table rather
    than re-listing prose. (Arg-hint richness decision below.)
  - Residual seam (out of scope here): the prefixed-command *handler* `case` in
    `dispatch-prefixed-command` stays keyed by prefix string; a narrow coherence
    test may assert its branch keys match the prefixed spec-table entries. This
    is handler-wiring, not name drift.
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

### Single spec table is the source of names (unreachable > forbidden)

There are currently two backend routing authorities and one description
authority:

- `exact-command-handlers` — a **map** `name → handler-keyword` (e.g.
  `"/help" :help`).
- `prefixed-command-prefixes` — a **vector** of prefix strings (no handler
  values; prefixed dispatch is a hardcoded `case` in
  `dispatch-prefixed-command`).
- `format-help` prose — descriptions, keyed implicitly by name.

`builtin-command-names` is already *derived* (`concat` of the two routing
collections, strip `/`, `set`). Descriptions are not derived from anything; they
are independent prose.

**Two structural options, evaluated against `λ shape. unreachable > forbidden`,
`impossible_invalid_states`, and
`λ robust ... shaped_by(code, formalisms) → enforceable(invariants(code))`:**

#### Option A — Parallel description table + coherence test (drift *forbidden*)

Keep the routing maps as-is, add a parallel `name → description` table, and
enforce `set(spec-names) == set(routed-names)` **by test**. A missing or extra
description is a *representable but invalid* state: the code compiles and runs;
only a test rejects it. This is `forbidden`, not `unreachable` — it contradicts
the project ethos (`impossible_invalid_states`). Lower blast radius, but the
invariant lives outside the type/data shape.

#### Option B — Single keyed spec table is the source of names (drift *unreachable*)

Make **one** ordered map (Clojure array-map) the single source of built-in
command identity. **Keys are leading-slash-prefixed** (see "Spec-table key form"
below); each entry carries a **set of dispatch kinds** (see "Dispatch-kind
representation" below):

```clojure
;; "/name" (leading slash) → spec
(array-map
  "/help"         {:kinds #{:exact}    :handler :help     :description "Show this help"}
  "/model"        {:kinds #{:prefixed} :description "Show or set the model"
                                       :usage "[provider model-id [session|project|user]]"}
  "/project-repl" {:kinds #{:exact :prefixed} :handler :project-repl
                                       :description "Open/manage the project nREPL"}
  ,,,)
```

Then *derive every other surface from its keys*:

- `builtin-command-names` = `(map strip-slash (keys spec-table))` — no separate
  `concat`.
- exact-vs-prefixed routing = the `:kinds` set per entry; the
  `exact-command-handlers` map and the `prefixed-command-prefixes` vector are
  both *projections* of the spec table (e.g. `exact-command-handlers` =
  `(into {} (for [[n s] table :when (:kinds s :exact)] [n (:handler s)]))`;
  `prefixed-command-prefixes` = `(vec (for [[n s] table :when (:exact-and-or-prefixed) …] n))`),
  not independent literals. An entry whose `:kinds` contains **both** feeds both
  projections (see "Dispatch-kind representation").
- `format-help` built-in lines = derived from `(seq spec-table)` in table order
  (see "format-help derivation").
- resolver specs = `(for [[n s] table] {:name (strip-slash n) :description (:description s)})`.

Because names exist in exactly **one** place (the keys of the spec table) and
every other name surface is a pure function of those keys, "a command routable
but undescribed" or "described but unroutable" is **not representable** — there
is no second name list to diverge from. Drift is `unreachable`, satisfying
`single_source_of_truth` structurally rather than by test.

**Residual constraint (honest scoping).** The prefixed-command *behaviour* still
lives in the `case` of `dispatch-prefixed-command`, keyed by prefix string.
Option B removes name drift (the `prefixed-command-prefixes` *vector* becomes a
derived projection, so a prefix can no longer be listed-but-undescribed), but a
prefix could still be in the table yet missing a `case` branch. That is a
*handler-wiring* coherence concern, narrower than the name-drift this task
targets, and the same gap exists today (a prefix in the vector with no `case`
branch). It is out of scope to fully data-drive the prefixed `case`; if desired,
a single `count`/membership test (`set(prefixed table entries) == set(case
keys)`) covers that narrower seam. The *name* invariant — which is what this
task exists to fix — is made structurally unreachable by Option B regardless.

**Decision: Option B (single keyed spec table) is chosen.** It is selected on
`unreachable > forbidden` grounds, not blast-radius: a single keyset makes
built-in command name divergence unrepresentable, which is the architectural
ethos (`impossible_invalid_states`, `enforceable(invariants)`). The routing maps
become derived projections of the spec table; the residual prefixed-`case`
handler-wiring seam (unchanged in scope from today) is the only coherence point
that remains test-enforced rather than structural, and it is explicitly out of
this task's name-drift remit.

### Dispatch-kind representation (resolves B1)

`/project-repl` is in **both** `exact-command-handlers` (`:project-repl`) and
`prefixed-command-prefixes` today. At runtime `dispatch*` tries the exact map
first and, finding `/project-repl`, calls
`dispatch-project-nrepl-command`; the prefixed `case` branch for
`/project-repl` is therefore only reached for the *prefixed-arg* form
(`/project-repl <args>`), since `exact-command-handler` matches the bare
`/project-repl` exactly but not `/project-repl foo`. In other words the command
is legitimately **both** exact (bare) and prefixed (with args).

**Decision.** Each spec entry carries a `:kinds` **set** drawn from
`#{:exact :prefixed}` (never empty). The projections filter by membership, so an
entry can feed **both** the exact map and the prefixed vector:

- `exact-command-handlers` projection includes an entry iff `:exact ∈ :kinds`,
  mapping `"/name" → (:handler spec)` (so dual-kind and exact-only entries
  **must** carry `:handler`).
- `prefixed-command-prefixes` projection includes a name iff
  `:prefixed ∈ :kinds`.
- `builtin-command-names` derives from **all** keys regardless of `:kinds`
  (every key is a name).

**Dispatch precedence is unchanged:** exact-first, then prefixed — exactly
today's `dispatch*` order. For a dual-kind entry the bare form hits the exact
handler and the prefixed-arg form falls through to the prefixed `case`; the
spec table does not alter this, it only makes both projections derive from one
keyed source. The prefixed `case` body in `dispatch-prefixed-command` stays
hand-written (handler-wiring residual, already out of scope); the `:kinds`
machinery governs only which *names* each projection lists.

### Spec-table key form (resolves B2)

**Decision.** Canonical spec-table **keys are leading-slash-prefixed**
(`"/help"`, `"/model"`, …), matching `exact-command-handlers`' existing key form
and the Emacs/TUI `/`-prefixed convention. Rationale: the routing projections
(`exact-command-handlers` keys, `prefixed-command-prefixes` entries) already use
`/`-prefixed strings, so a `/`-prefixed table key reaches them with **no
transform**; only the two surfaces that want bare names (`builtin-command-names`
and the resolver output) apply a single `strip-slash`. This minimises the number
of strip operations and keeps the table visually aligned with the routing it
replaces. Every projection's keep/strip is therefore fixed: routing projections
keep `/`; `builtin-command-names` and resolver strip `/`.

### format-help derivation (resolves B3)

Scope of `format-help`'s single-sourcing is bounded as follows:

- **Built-in command lines** (currently the hand-written `/quit … /skill:name`
  block) derive from `(seq spec-table)` in **table order** (see "Spec-table
  ordering"). The spec table is authored in the current help's intended display
  order so the help listing is unchanged in order. Each line renders
  `"  /name — :description"`, optionally with the entry's `:usage` inserted
  before the em-dash (`"  /name :usage — :description"`) so arg hints like
  `/model [provider model-id …]` survive (see "Description granularity" for the
  `:usage` field).
- **`/skill:name` line stays hand-written prose.** It is *not* a built-in
  routing entry (no key in `exact-command-handlers` / `prefixed-command-prefixes`
  — it is matched by the skill resolver, not the command dispatcher), so it is
  **not** in the spec table and remains a literal line in `format-help`. AC3's
  "no independent hardcoded built-in *list* remains in format-help" refers to the
  block of routed built-in commands; the `/skill:name` affordance line and the
  trailing `(anything else …)` prose are not a built-in command list and stay.
- **Prompt-template / Skills / Extension-command sections** are unchanged
  (already derived from their own resolvers).

So after this task `format-help` contains **no hardcoded built-in command
name+description literals** — those come from the spec table — while the
non-routed `/skill:name` helper line and section prose remain literal.

### Spec-table ordering (resolves B4)

**Decision.** The spec table is an **ordered** map (Clojure `array-map`, or a
literal map laundered through an explicit order vector). Emission order is
**authoritative for `format-help`** (which must reproduce the current help
order, per "format-help derivation"). For the **resolver output, TUI
autocomplete, and Emacs autocomplete**, table order is the *natural* emission
order (the resolver returns specs in table order; UIs may re-sort for display as
they already do). AC4/AC5/AC6 assert *membership* of representative commands,
not a specific autocomplete sort, so the deterministic target is: resolver
output preserves table order; help reproduces table order; autocomplete
membership is order-independent. This gives every AC a concrete, testable
target without over-constraining UI display sorting.

### Description granularity (resolves B5)

`format-help` carries rich arg hints (e.g.
`/model [provider model-id [session|project|user]]`). Autocomplete annotations
(Emacs) and TUI menus want a short description.

**Decision.** Each spec carries a required `:description` (short) and an
**optional `:usage`** arg-hint string. `:usage` is consumed by `format-help`
only (rendered inline before the em-dash, per "format-help derivation"); it is
**not** exposed by the resolver. The resolver output shape stays
`{:name :description}` exactly (consistent with AC1 and with
`:psi.extension/command-names`), so autocomplete annotations get the short
description and are not bloated by arg hints. This keeps AC1's
`{:name :description}` resolver shape **final** while letting the Option B
example's `:usage` field exist purely as a help-rendering detail. Entries with
no arg hint simply omit `:usage`.

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
which returns bare names that UIs prefix). Apply uniformly. (This governs
resolver *output*; the spec-table *keys* are `/`-prefixed — see "Spec-table key
form" — so the resolver applies one `strip-slash`.)

### `/?` and aliases

`/?` (help alias) and `/exit` (quit alias) exist in routing. Decision: include
aliases in the spec table with descriptions so they autocomplete, matching
current Emacs behaviour (which lists `/?` and `/exit`).

## Acceptance criteria

- AC1: A new EQL attribute exposes built-in command specs as a vector of
  **`{:name :description}`** maps (bare name, short description — no `:usage`;
  see "Description granularity"), discoverable via the graph, resolvable for a
  session, in spec-table order (see "Spec-table ordering").
- AC2: The backend built-in command name set is derived from a **single keyed
  spec table** whose keys are the only place built-in names exist; routing maps
  (`exact-command-handlers`, `prefixed-command-prefixes`) and the resolver specs
  are derived projections of that table, so name divergence between routing and
  the exposed spec is structurally unrepresentable (`unreachable`, not merely
  test-forbidden). Each entry carries a `:kinds` set (`⊆ #{:exact :prefixed}`);
  dual-kind commands (`/project-repl`) feed both projections (see "Dispatch-kind
  representation"). Any residual handler-wiring coherence (prefixed `case`
  branches) is a separate, narrower test.
- AC3: `format-help`'s routed built-in command lines derive from the single
  spec table in table order (no hardcoded built-in command name+description
  literals remain in `format-help`). The non-routed `/skill:name` helper line
  and trailing prose stay literal; `:usage` arg hints render inline in help only
  (see "format-help derivation").
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
- Single keyed spec table makes built-in command name divergence
  *unrepresentable* rather than test-rejected — satisfies
  `λ shape. unreachable > forbidden`, `impossible_invalid_states`, and
  `enforceable(invariants)`.

## Open questions for plan stage

1. ~~Merge routing + description into one table vs. parallel table + coherence
   test.~~ **Resolved (architecture-fit review A1):** single keyed spec table
   (Option B) on `unreachable > forbidden` grounds — routing maps become derived
   projections; name drift is unrepresentable. See "Single spec table is the
   source of names". Remaining sub-question for plan: whether to also data-drive
   the prefixed `case` (out of this task's scope) or cover it with a narrow
   branch-coherence test. **Dual-kind commands resolved (ambiguity review B1):**
   each entry carries a `:kinds` set; `/project-repl` is `#{:exact :prefixed}`
   and feeds both projections; dispatch stays exact-first then prefixed. See
   "Dispatch-kind representation".
2. ~~Description vs. usage-hint granularity and whether the resolver carries
   `:usage`.~~ **Resolved (ambiguity review B5):** spec carries required
   `:description` + optional help-only `:usage`; resolver exposes
   `{:name :description}` only. See "Description granularity".
3. Emacs `defcustom` precedence/residual role and whether built-in entries are
   removed from its default value.
4. Sequencing: task 204 (`/reload-prompts` command + mutation) has **already
   landed and closed**. `/reload-prompts` is therefore another concrete
   built-in currently missing from both UI autocomplete lists — confirm it is
   swept into the spec table by this task and appears in TUI + Emacs.

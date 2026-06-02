# Implementation notes

## Reviews

### 2026-06-01 — Architecture-fit review (review-task-architecture), pass 1

Scope: architectural fit only (¬correctness, ¬clarity, ¬ambiguity, ¬consistency).
Sources consulted: `AGENTS.md`, `META.md`, `doc/architecture.md`, plus the
existing `extension-commands-resolver` (`resolvers/extensions.clj`) and
`commands.clj` `builtin-command-names` / routing maps for precedent.

Strong fit overall:
- Mirrors the established `:psi.extension/command-names` resolver precedent;
  both TUI (`tui/app/support.clj`) and Emacs (`psi-session-commands.el`) already
  consume that attribute via EQL — design adds one more attribute to queries
  they already make. Satisfies `λ one_way`, `single_source_of_truth`, and the
  architecture convergence rule ("backend answers it once; adapters differ only
  in rendering").
- Resolver placement in `agent-session` (alongside the routing maps) is correct
  and consistent: `extension-commands-resolver` lives there too, `app-runtime`
  hosts no resolvers, and co-locating the spec table with its routing authority
  keeps the coherence invariant local. Moving it to `app-runtime` would split
  the spec from the routing maps it must equal — rejected.
- No shim/adapter introduced; aligns with `λ shims_adapters`.
- Bare-name (no leading `/`) resolver shape matches `:psi.extension/command-names`.

One actionable architectural-fit misfit (see design-steps.md A1):
- A1 — Drift-prevention is *forbidden* (test fails) rather than *unreachable*
  (structurally impossible). The design's stated preference (open question #1)
  keeps routing+description as two parallel maps and enforces
  `set(spec-names) == set(routed-names)` **by test**. This conflicts with the
  project's structural-invariant ethos: `λ shape. unreachable > forbidden`,
  `impossible_invalid_states`, and
  `λ robust ... shaped_by(code, formalisms) → enforceable(invariants(code))`.
  A single table where each command entry carries both its handler/routing and
  its description (so name divergence is structurally impossible) better fits
  the architecture. The design already frames this as an open question; the
  architectural principle favours the single-source-of-keys option over
  "minimal blast radius." Plan should weigh `unreachable > forbidden` explicitly
  rather than defaulting to the test-enforced variant.

PASS_STATUS: ACTIONABLE_FEEDBACK

## R1 follow-up resolution (2026-06-01)

Resolved R1 via Option 1 (genuine live-seam lock) rather than a rename, since
it aligns with the project `unreachable > forbidden` ethos and the existing
single-source pattern.

- Added private `commands/prefixed-case-branches` — a set holding the `/`-prefixed
  keys that `dispatch-prefixed-command`'s `case` actually routes. `case` requires
  compile-time literal keys, so this set is authored adjacent to the `case` as the
  single literal expression of its branches (the heterogeneous handler arities —
  `/tree` needs `supports-session-tree?`, `/login` needs `oauth-ctx ai-model` —
  keep the `case` hand-written and design-scoped out of data-driven dispatch).
- Added a load-time `(assert (= prefixed-case-branches (set
  bspec/prefixed-command-prefixes)) …)` directly after the def, so any drift
  between the spec-table prefixed projection and the real `case` branch set is
  caught when the namespace loads (not just in a test run).
- `prefixed-case-branch-coherence-test` now reads `@#'commands/prefixed-case-branches`
  instead of a second hardcoded literal `case-keys` set, so the test genuinely
  locks the live seam.

Verification: `commands-builtin-specs-test` 6/18, `commands-test` 51/206, both
0 failures (the load-time assert holds — namespaces load clean); clj-kondo clean
on both changed files.

## Design-steps follow-up

### 2026-06-01 — A1 (architecture-fit follow-up): single keyed spec table

Completed A1. Grounded the decision in the actual routing shape:
`exact-command-handlers` is a `name → handler-keyword` **map**;
`prefixed-command-prefixes` is a prefix **vector** dispatched via a hardcoded
`case` in `dispatch-prefixed-command`; `builtin-command-names` is already a
derived `concat`.

design.md changes:
- Replaced "One spec table, two derivations" decision with "Single spec table is
  the source of names (unreachable > forbidden)" — evaluates Option A (parallel
  table + coherence test = drift *forbidden*) vs Option B (single keyed spec
  table = drift *unreachable*), and **chooses Option B** on `unreachable >
  forbidden` / `impossible_invalid_states` grounds rather than blast-radius.
- Routing maps reframed as *derived projections* of the keyed spec table; names
  exist in exactly one place (the table keys), so name divergence is
  unrepresentable.
- Updated Scope "Derivation, not duplication", AC2, "Architectural alignment",
  and resolved open question #1 to match.

Honest residual scoping recorded in design: prefixed-command *handler* wiring
still lives in the `dispatch-prefixed-command` `case`. Option B removes name
drift (prefix vector becomes a projection) but a prefix could be in the table
yet lack a `case` branch — that is handler-wiring coherence, narrower than the
name drift this task fixes, unchanged from today, and explicitly out of scope.
Optionally covered by a narrow branch-coherence test (left to plan).

No blocking reasons; A1 fully addressed at design level (plan/code stages will
implement the projections).

### 2026-06-01 — Ambiguity review (review-task-design ambiguity pass), pass 1

Scope: ambiguities only (¬architecture, ¬correctness, ¬consistency). Read
design.md + design-steps.md (A1 resolved) and grounded against real code:
`commands.clj` `exact-command-handlers` (664), `prefixed-command-prefixes`
(682), `builtin-command-names` (715), `dispatch-prefixed-command` (697),
`format-help` (111), and `extension-commands-resolver` precedent.

Five new actionable ambiguities (B1–B5 in design-steps.md):

- B1 — **Dual-kind command unrepresentable.** `/project-repl` is in BOTH
  `exact-command-handlers` (`:project-repl`) AND `prefixed-command-prefixes`
  today; `dispatch*` tries exact first, then the prefixed `case` (both have a
  `/project-repl` branch). Option B's single keyed entry carries one
  `:dispatch`/`:handler` field — it cannot express "exact AND prefixed" without
  a rule. Design must specify: does an entry carry a set/either of kinds, which
  projection(s) it feeds, and which dispatch path wins? Unspecified → the
  projection of `exact-command-handlers`/`prefixed-command-prefixes` is
  ambiguous for this command.

- B2 — **Spec-table key form still "pick one."** Scope says key "without leading
  slash, or with — pick one and apply uniformly," yet "Slash prefix
  normalization" fixes only the *resolver output* (bare). The table-key form
  (Option B examples use `/`-prefixed keys; `strip-slash` is applied in the
  projections) is never decided. Pin the canonical table-key form in design so
  every projection's strip/keep is unambiguous.

- B3 — **`format-help` ordering + non-built-in lines.** AC3 says help "derives
  from the single spec source," but current `format-help` has hand-curated
  ordering, an arg-usage hint per line, a `/skill:name` line that is NOT a
  built-in routing entry (it's prose), and separate Prompt/Skill/Extension
  sections. Design doesn't state whether spec-table iteration order must
  reproduce the current help order, whether `/skill:name` stays hand-written,
  and where the per-line `— description` vs `:usage` split renders. Ambiguous
  what "no independent hardcoded built-in list remains in format-help" requires.

- B4 — **Spec-table ordering authority.** AC2 mandates a "single keyed spec
  table" but doesn't state whether it is an ordered map and whether resolver
  output, help listing, and autocomplete must preserve that order. UIs and help
  currently differ in order; "derived projection" leaves emission order
  undefined. Specify ordered-map requirement (or explicitly that order is
  unspecified) so AC4/AC5/AC6 tests have a deterministic target.

- B5 — **`:usage` field decision deferred into AC-bearing scope.** "Description
  granularity" leaves whether the spec carries `:usage` as a plan-stage
  resolution, but Option B's example spec already shows a `:usage` key and AC1
  fixes the resolver output to `{:name :description}` (no `:usage`). If `:usage`
  lands in the table, the resolver-output shape in AC1 and the design's example
  diverge. Decide at design whether `:usage` is a table field and whether it is
  exposed by the resolver, so AC1's `{:name :description}` is or is not final.

PASS_STATUS: ACTIONABLE_FEEDBACK

### 2026-06-01 — Ambiguity follow-up (B1–B5) executed

All five ambiguities resolved at design level. Grounded against `commands.clj`:
`exact-command-handlers` (664) is a `"/name" → handler-kw` map; `/project-repl`
is in it (`:project-repl`) AND in `prefixed-command-prefixes` (682);
`dispatch*` (761) runs exact-first (`case (exact-command-handler trimmed)`) then
`dispatch-prefixed-command` (697); `format-help` (111) is hand-written prose with
a `/skill:name` line that is NOT a routing entry; `builtin-command-names` (715)
is the derived `concat`.

- **B1 (dual-kind).** New "Dispatch-kind representation" decision: each spec
  entry carries a `:kinds` set `⊆ #{:exact :prefixed}`. Projections filter by
  membership, so `/project-repl` = `#{:exact :prefixed}` feeds BOTH. Runtime
  dispatch precedence is unchanged (exact-first then prefixed): bare
  `/project-repl` hits the exact handler; `/project-repl <args>` falls through
  to the prefixed `case`. Exact/dual entries must carry `:handler`. Updated
  Option B example, projection bullets, AC2, open question #1.

- **B2 (key form).** New "Spec-table key form" decision: keys are
  **leading-slash-prefixed** (`"/help"`), matching `exact-command-handlers` and
  the routing projections (zero transform); only `builtin-command-names` and the
  resolver apply one `strip-slash`. Cross-linked from "Slash prefix
  normalization" (which governs resolver output) to remove the apparent overlap.

- **B3 (format-help).** New "format-help derivation" decision: routed built-in
  lines derive from `(seq spec-table)` in table order (table authored in current
  help order); `:usage` renders inline before the em-dash; the `/skill:name`
  line + trailing prose stay LITERAL (not a routing entry → not in the table);
  Prompt/Skills/Extension sections unchanged. AC3 reworded to "no hardcoded
  built-in command name+description literals remain."

- **B4 (ordering).** New "Spec-table ordering" decision: ordered map
  (`array-map`); emission order authoritative for `format-help`; resolver output
  preserves table order; autocomplete asserts membership (order-independent), so
  AC4/AC5/AC6 have a deterministic target without over-constraining UI sort.

- **B5 (`:usage`).** "Description granularity" rewritten: required
  `:description` + optional `:usage`; `:usage` consumed by `format-help` only,
  NOT exposed by the resolver. Resolver output stays `{:name :description}`
  (AC1 made final). Resolves the Option-B-example-vs-AC1 divergence: `:usage`
  exists in the table purely as a help-rendering detail. Open question #2
  resolved.

No blocking reasons; B1–B5 fully addressed at design level (projections/`:kinds`
machinery and help/resolver derivation land in plan/code stages).

### 2026-06-01 — Inconsistency review (review-task-design inconsistency pass), pass 1

Scope: inconsistencies only (¬architecture, ¬correctness, ¬ambiguity). Read
design.md + design-steps.md (A1, B1–B5 all resolved) and grounded against real
code: `commands.clj` `exact-command-handlers` (664; includes alias keys `"/?"`→
`:help` and `"/exit"`→`:quit`), `prefixed-command-prefixes` (682), `format-help`
(111; current help text **omits** `/?` and `/exit` lines), `prefixed-command`
(matcher requires `= prefix` or `starts-with (str prefix " ")` — `/jobs`/`/job`
do **not** collide, so prefixed-vector order is not load-bearing → deriving it in
help order is safe, no inconsistency), plus TUI `shared.clj/builtin-slash-commands`
and Emacs `psi-completion.el/psi-emacs-slash-command-specs` (drift claims in the
"Why" table verified accurate, incl. Emacs listing `/?` and `/exit`).

One new actionable inconsistency:

- C1 — **Aliases-in-table contradicts unchanged-help-output.** The "`/?` and
  aliases" decision puts `/?` and `/exit` in the spec table "with descriptions so
  they autocomplete." But "format-help derivation" + AC3 state that *all* routed
  built-in lines derive from `(seq spec-table)` in table order, with the explicit
  claim "the help listing is unchanged in order." Since aliases are routed
  entries they must be table keys (Option B: names exist only as table keys), so
  help-derives-from-whole-table would now emit `/?` and `/exit` lines — yet the
  current `format-help` deliberately omits both. The data model gained `:kinds`
  and `:usage` fields but no help-suppression mechanism, so an aliased routed
  entry cannot be both in the table (for autocomplete) and absent from help. The
  three decisions ("`/?` and aliases", "format-help derivation", AC3) are
  mutually inconsistent as written; design must reconcile (e.g. a `:hide-in-help?`
  / help-only-filter flag, or explicitly accept that help now lists aliases and
  drop the "unchanged listing" claim).

PASS_STATUS: ACTIONABLE_FEEDBACK

### 2026-06-01 — Inconsistency follow-up (C1) executed

C1 resolved at design level. Grounded against `commands.clj`:
`exact-command-handlers` (664) carries alias keys `"/?" → :help` and
`"/exit" → :quit`; `format-help` (111–145) deliberately **omits** both `/?` and
`/exit` lines. Option B (single keyed table = names exist only as keys) forces
aliases to be table keys (for completeness + autocomplete), so whole-table help
derivation would newly emit alias lines — contradicting the "listing unchanged"
claim. The post-B5 data model (`:kinds`, `:usage`) had no help-suppression
field.

**Decision: add a per-entry `:hide-in-help?` boolean** (help-rendering filter
only, parallel to help-only `:usage`). `format-help`'s built-in-line derivation
skips truthy `:hide-in-help?` entries; `/?` and `/exit` carry it. Resolver
output and UI autocomplete include hidden entries (aliases still autocomplete),
so `:hide-in-help?` is not resolver-exposed nor UI-consulted. Chosen on
`unreachable > forbidden` grounds: name drift stays structurally unrepresentable
(aliases remain keys feeding every name surface + routing projections); help
suppression becomes an **explicit intentional data field** rather than a hidden
literal omission, and the "help listing unchanged" claim now holds in order
*and* membership. Rejected alternative (accept alias lines in help, drop the
"unchanged listing" claim): changes user-visible help for no benefit, weakens
AC3.

design.md changes:
- Rewrote "`/?` and aliases" decision → adds `:hide-in-help?`, states rationale
  + rejected alternative + scope (help-only).
- "format-help derivation": built-in lines skip `:hide-in-help?` entries;
  "unchanged in order *and* membership".
- Option B example: added `/?` and `/exit` entries with `:hide-in-help? true`
  and a help-only-filter note; format-help projection bullet now filters hidden
  entries.
- AC3: derivation skips `:hide-in-help?` entries (listing unchanged in order +
  membership; aliases stay out of help but in table for autocomplete).
- "Description granularity": added a "Spec-entry field set" summary
  (`:kinds`/`:handler`/`:description`/`:usage`/`:hide-in-help?`; resolver exposes
  only name+description).

No blocking reasons; C1 fully addressed at design level. AC1 resolver shape
`{:name :description}` unchanged (`:hide-in-help?` not exposed).

### 2026-06-01 — Plan ambiguity review (review-task-plan ambiguity pass), pass 1

Scope: ambiguities in plan.md + steps.md only (¬architecture, ¬correctness,
¬consistency; design A1/B1–B5/C1 already resolved). Grounded against real code:
TUI `support.clj` (`command-refresh-query` 188, `refresh-extension-command-names`
191, `build-init` introspection query 226–230), `autocomplete.clj`
(`shared/builtin-slash-commands` concat at 59 + many other `shared/` uses),
`shared.clj` 22; Emacs `psi-completion.el` (`psi-emacs-slash-command-specs`
defcustom 19, `psi-emacs--state-slash-command-specs` 99–103), `psi-session-commands.el`
(`psi-emacs--prompt-template-query` 256 — **not** 257; `psi-emacs--apply-slash-completion-data`
`(names templates)` 302; `psi-emacs--slash-completion-token` ~290), `psi-events.el`
(`declare-function …apply-slash-completion-data… (names templates)` 28;
`psi-emacs--slash-completion-data-changed-p` event-data path calls it 109);
`psi-globals.el` `extension-command-names` slot 100. Backend resolver/registration
(`extensions.clj` resolvers vector 263–277, `extension-commands-resolver` 134) is
unambiguous and well-specified — no findings there.

Five new actionable plan/steps ambiguities (P1–P5 in steps.md):

- P1 — **Emacs `apply-slash-completion-data` threading shape unspecified.**
  Plan ("Extend `psi-emacs--apply-slash-completion-data` to carry built-in specs")
  + steps ("thread built-in specs through `psi-emacs--apply-slash-completion-data`")
  don't say whether the fn gains a third positional arg (`(names builtin-specs
  templates)`) or stores built-in specs via a separate path. The fn's signature
  is `(names templates)` and is fixed in THREE places that must all change
  together if the arity changes: the `declare-function` in `psi-events.el:28`,
  the query-frame call in `psi-session-commands.el:323`, and the event-data call
  in `psi-events.el:109`. Plan names only the first call site. Pin the
  signature/threading shape and enumerate every `apply-slash-completion-data`
  call site + the `declare-function` to update.

- P2 — **Slash-completion change-detection token not addressed.** Refresh only
  fires when `psi-emacs--slash-completion-token` differs
  (`psi-emacs--slash-completion-data-changed-p`, `psi-events.el`; token built
  from names+templates only). If built-in specs become a new completion source
  but are NOT folded into the token, a built-in-spec-only change won't be
  detected as "changed" and Emacs autocomplete will go stale — defeating AC5/AC6.
  Plan/steps are silent on the token. Specify that the token includes built-in
  specs (in both `psi-emacs--slash-completion-token` and the
  `…changed-p` event path), or state explicitly why the token need not change.

- P3 — **Built-in specs arrival channel unspecified (query frame vs session
  event).** Emacs has TWO data-application channels into
  `apply-slash-completion-data`: the explicit `query_eql` frame
  (`psi-emacs--prompt-template-query` / `…-from-query-frame`) AND the
  session-update event-data path (`psi-events.el`
  `psi-emacs--slash-completion-data-changed-p`, which reads
  `:extension-command-names`/`:prompt-templates` off pushed events). Plan extends
  the query string (channel 1) and a frame-extractor, but does not say whether
  built-in specs also arrive on the session-update event path (channel 2) or only
  via the query. If only via query, the event path's token/extraction must still
  not regress; if via both, both extractors are needed. Decide and specify the
  arrival channel(s) for built-in specs.

- P4 — **TUI refresh fn "(or sibling)" left as a choice.** Steps say
  "`support.clj` `refresh-extension-command-names` (or sibling): refresh built-in
  specs." "(or sibling)" defers whether to extend the existing fn (and rename it)
  or add a new refresh fn — an unresolved implementation fork. Also
  `command-refresh-query` currently returns only `:psi.extension/command-names`;
  the fn destructures that one key, so extending it requires a decision on
  reading the new key too. Pin: extend `refresh-extension-command-names` in place
  vs add a sibling, and how `command-refresh-query` result feeds both keys.

- P5 — **`shared.clj/builtin-slash-commands` disposal is double-specified and
  partly incoherent.** Plan offers "remove … (delete, **or** empty + drop the
  require usage)"; steps pin "delete + drop usages." The two are not reconciled
  (plan two-option vs steps one-option). Worse, "drop the require usage" is
  unsound as written: `autocomplete.clj` uses MANY other `shared/` symbols
  (`shared/input-value`, `shared/input-pos`, `shared/set-input-value`), so the
  `[… app.shared :as shared]` require must STAY; only the
  `shared/builtin-slash-commands` reference at line 59 is removed. Pin the exact
  disposal (delete the `def` only; keep the `shared` require; drop just the
  `builtin-slash-commands` symbol from the `concat`) so the step is unambiguous
  and doesn't suggest removing a still-needed require.

PASS_STATUS: ACTIONABLE_FEEDBACK

### 2026-06-01 — Plan ambiguity follow-up (P1–P5) executed

All five plan/steps ambiguities resolved in plan.md, grounded against real code.

- **P1 (Emacs apply threading).** `psi-emacs--apply-slash-completion-data` gains
  a third positional arg → `(names builtin-specs templates)`. Enumerated all 4
  co-changing sites: defun (`psi-session-commands.el:302`), `declare-function`
  (`psi-events.el:28`), query-frame call (`psi-session-commands.el:323`),
  event-data call (`psi-events.el:109`); plus a new `builtin-command-specs`
  globals slot (`psi-globals.el` parallel to line 100) seeded nil in
  `psi-lifecycle.el` (parallel to line 85). Plan section "P1 …" added.

- **P2 (change-detection token).** Built-in specs folded into the token so a
  built-in-spec-only change refreshes Emacs autocomplete (AC5/AC6). `:builtins`
  segment added to BOTH `psi-emacs--slash-completion-token` (292) and the inline
  `next-token` in `psi-emacs--slash-completion-data-changed-p` (`psi-events.el:80`),
  kept structurally identical; fixed segment order `:commands :builtins
  :templates`. Plan section "P2 …" added.

- **P3 (arrival channels).** BOTH channels carry built-in specs, mirroring
  `extension-command-names`: channel 1 `query_eql` frame
  (`psi-emacs--builtin-command-specs-from-query-frame` extractor +
  `psi-emacs--prompt-template-query` line 256 — corrected from steps' "257");
  channel 2 session-update event path (extract via `psi-emacs--event-data-get`
  `(:builtin-command-specs builtin-command-specs)`). Both feed the same arity +
  token, no cross-regression. Plan section "P3 …" added.

- **P4 (TUI refresh fork).** Pinned: extend `refresh-extension-command-names`
  **in place** (no sibling, no rename). `command-refresh-query` (support.clj:188)
  gains `:psi.agent-session/builtin-command-specs`; the one fn destructures both
  keys and `assoc`es both state slots (vector-guarded); `build-init` query
  (226–230) extended in parallel. Plan section "P4 …" added.

- **P5 (shared.clj disposal).** Pinned single disposal: delete the
  `builtin-slash-commands` `def` only; KEEP the `app.shared` require in
  `autocomplete.clj` (still used by `input-value`/`input-pos`/`set-input-value`
  at 14/15/226/245); remove only the `shared/builtin-slash-commands` symbol from
  the line-59 `concat`. "empty + drop require" alternative dropped (would break
  remaining `shared/` usages). Plan section "P5 …" added.

Slice-3 and Slice-4 steps in steps.md updated in place to reflect the pinned
decisions (no longer ambiguous). P1–P5 marked done. These are plan/steps-stage
refinements — no production code/test/doc change yet (Slices 1–5 remain
unimplemented); plan is now unambiguous enough to execute. No blocking reasons.

### 2026-06-01 — Plan inconsistency review (review-task-plan inconsistency pass), pass 1

Scope: inconsistencies in plan.md + steps.md only (¬architecture, ¬correctness,
¬ambiguity; design A1/B1–B5/C1 + plan P1–P5 already resolved). Grounded against
real code: `commands.clj` `exact-command-handlers` (664; alias keys `"/?"`→`:help`,
`"/exit"`→`:quit`, plus `"/project-repl"`→`:project-repl`), `prefixed-command-prefixes`
(682; includes `/project-repl`), `format-help` body (111–161), `dispatch-prefixed-command`
(697); TUI `support.clj` (188/191/204), `autocomplete.clj` (line-59 concat + `shared/`
usages 14/15/226/245), `shared.clj` (22); Emacs `psi-completion.el` defcustom (19),
`psi-session-commands.el` (`psi-emacs--prompt-template-query` 255, token 292, apply 302,
query-frame call 323), `psi-events.el` (`declare-function` 28, `…-changed-p` 80, apply
call 109). Verified accurate: spec-table membership lists (plan/steps vs routing maps),
all plan/steps line references, design "Why"-table drift claims (TUI + Emacs).

Two new actionable inconsistencies (I1–I2 in steps.md):

- I1 — **`/project-repl` is routed-but-help-absent yet not flagged `:hide-in-help?`.**
  The current `format-help` (111–161) omits THREE routed commands: `/?`, `/exit`,
  AND `/project-repl` (confirmed: no `/project-repl` line in the block). The C1
  resolution and the plan/steps membership assign `:hide-in-help? true` to ONLY
  `/?` and `/exit` (both plan "Concrete spec-table membership" and steps Slice-1
  populate-membership item say "`/?` and `/exit` carry `:hide-in-help? true`").
  `/project-repl` is a dual-kind real command with no `:hide-in-help?`. Since
  format-help derives from `(seq table)` skipping only `:hide-in-help?` entries
  (AC3, plan "format-help derivation"), a `/project-repl` help line would be
  NEWLY emitted — contradicting AC3 / "format-help derivation" / the plan risk
  "`format-help` output drift" claim of "help listing unchanged in order *and*
  membership". The C1 follow-up enumerated only `/?`/`/exit` as help-absent
  routed entries and missed `/project-repl`. Plan/steps must reconcile: either
  flag `/project-repl` `:hide-in-help? true` (keeping help membership unchanged),
  or explicitly accept a new `/project-repl` help line and drop the
  "unchanged membership" claim + adjust the golden/substring test expectation.

- I2 — **P2 token segment does not make a built-in-spec-only change detectable.**
  P2 claims "a built-in-spec-only change is detected and Emacs autocomplete
  refreshes (AC5/AC6)" by adding a `:builtins` token segment to the inline
  `next-token` in `psi-emacs--slash-completion-data-changed-p`. But that fn
  (`psi-events.el:80`) computes `next-token` only under
  `(and (or has-command-names has-templates) …)` and then guards `(when next-token …)`.
  An event carrying ONLY built-in specs (no `:extension-command-names`, no
  `:prompt-templates`) leaves `has-command-names`/`has-templates` both nil →
  `next-token` is nil → no refresh, regardless of the new `:builtins` segment.
  Plan P2 + Slice-4 steps add only the token SEGMENT and never extend the
  `(or has-command-names has-templates)` change-detection GUARD (nor add a
  `has-builtin-specs`). So P2's stated AC5/AC6 outcome is inconsistent with the
  mechanism it specifies. Plan/steps must extend the guard (add
  `has-builtin-specs` to the `or`) so a built-in-spec-only event triggers
  `next-token`, or state why a built-in-spec-only event cannot occur on channel 2.

PASS_STATUS: ACTIONABLE_FEEDBACK

### 2026-06-01 — Plan inconsistency follow-up (I1–I2) executed

Both inconsistencies resolved at plan/design/steps level (no production code yet
— Slices 1–5 remain unimplemented). Grounded against real code first.

- **I1 (`/project-repl` routed-but-help-absent).** Verified against
  `commands.clj`: `format-help` (111–161) lists `/quit … /cancel-job /help` but
  **omits** `/?`, `/exit`, AND `/project-repl`; `/project-repl` is routed in both
  `exact-command-handlers` (680) and `prefixed-command-prefixes` (683).
  **Decision (first option): flag `/project-repl` `:hide-in-help? true`** so
  whole-table help derivation reproduces the existing help membership exactly —
  preserving the "unchanged in order *and* membership" claim (rejected the
  alternative of accepting a new help line, which changes user-visible output for
  no benefit and weakens AC3). `:hide-in-help?` now covers two cases: help-omitted
  aliases (`/?`, `/exit`) and the help-omitted real command `/project-repl`.
  Edits: design.md — `/project-repl` spec example gains `:hide-in-help? true`;
  "format-help derivation" + "`/?` and aliases (… resolves C1, I1)" + AC3 widened
  to enumerate `/project-repl` alongside the aliases. plan.md — `:hide-in-help?`
  field doc, format-help derivation bullet, "Concrete spec-table membership", and
  the "format-help output drift" risk all updated to flag `/project-repl`.
  steps.md — Slice-1 populate-membership item and the `format-help` test item
  (now asserts no NEW `/project-repl` line).

- **I2 (built-in-spec-only change undetectable).** Verified against
  `psi-events.el:80`: `next-token` is gated by
  `(and (or has-command-names has-templates) …)` then `(when next-token …)`, so a
  built-in-spec-only event (no `:extension-command-names`/`:prompt-templates`)
  leaves `next-token` nil → no refresh, the `:builtins` segment alone being
  insufficient. **Decision: extend the guard** — extract `raw-builtin-specs`
  (`'(:builtin-command-specs builtin-command-specs)`), compute `has-builtin-specs`,
  and add it to the `or`:
  `(and (or has-command-names has-templates has-builtin-specs) …)`. Edits:
  plan.md P2 — added a "Change-detection guard extension (resolves I2)" paragraph
  enumerating the extraction + guard + the `when`-branch apply call (P1 site 4).
  steps.md — the Slice-4 token step now also requires extending the guard with
  `has-builtin-specs`.

Both choices follow `unreachable > forbidden` (I1 keeps name drift structurally
unrepresentable; help suppression stays an explicit data field) and keep the
two-channel Emacs handling identical to `extension-command-names`. No blocking
reasons; plan/design/steps now internally consistent. These are plan/design-stage
refinements — no code/test/doc change yet.

## Slice 1 implementation (2026-06-01)

Landed the single keyed spec table `builtin-command-specs` (ordered `array-map`,
`/`-prefixed keys, current help display order) in `commands.clj`. Derived
`exact-command-handlers`, `prefixed-command-prefixes`, `builtin-command-names`,
and `format-help`'s built-in block from it. Added `strip-slash` helper and a
`builtin-help-block` renderer (forward-`declare`d, used by `format-help`).

Deviations / notes:
- **`prefixed-command-prefixes` order changed** (now table order: `/tree /login
  /model /thinking /speed /effort /remember /jobs /job /cancel-job
  /project-repl`). Verified safe: the dispatch matcher uses `(= trimmed prefix)`
  or `(starts-with trimmed (str prefix " "))`, so no prefix shadows another
  (`/job` vs `/jobs` don't collide). The regression lock therefore compares
  prefixes as a **set**, matching the inconsistency-review finding that prefix
  order is not load-bearing.
- **`format-help` line alignment dropped.** The old hand-tuned column padding
  (`/quit    —`) is gone; lines now render `"  /name [usage ]— description"`.
  Membership + order unchanged; only cosmetic padding differs. Tests assert
  substring/order, not byte-exact padding.
- **Help block: `/skill:name` line + trailing `(anything else …)` prose stay
  literal** (not routing entries → not in the table), per design.
- **`/project-repl <args>` dispatch test** uses `/project-repl start` (a real
  prefixed subcommand) rather than a bogus `status` arg, since unknown
  subcommands fall through to nil.
- **Prefixed-`case` branch-coherence test** hardcodes the case-key set (handler
  wiring stays hand-written, out of scope per design); narrow lock only.
- Added public `commands/builtin-command-specs-for-resolver` (returns
  `[{:name :description}]`, bare names, table order) ahead of Slice 2 — it lives
  with the spec table it must mirror.

Verification: `clojure -M:test --focus psi.agent-session.commands-test` →
57 tests, 224 assertions, 0 failures. clj-kondo clean on changed files.

## Slice 2 implementation (2026-06-01)

Added `builtin-commands-resolver` in `resolvers/extensions.clj` (mirrors
`extension-commands-resolver`), output `:psi.agent-session/builtin-command-specs`
(vector of `{:name :description}`, bare names, table order) +
`:psi.agent-session/builtin-command-names` (bare-name vector). Registered in the
`resolvers` vector. New test ns `builtin-commands-resolver-test` covers resolver
shape, bare names, internal-field exclusion, previously-missing built-ins
(`reload-models`/`reload-prompts`/`speed`/`effort`/`project-repl`), aliases
present, table order, name-vector mirroring, plus a graph-discovery test
(resolver-syms contains the resolver + attrs resolvable).

### Deviation (significant): leaf ns to break a load cycle

Putting the spec accessor on `commands` and requiring `commands` from the
resolver introduced a **cyclic load dependency**:
`core → commands/effort → commands → resolvers/extensions → resolvers →
psi_tool → … → context → core`. (`commands.effort` requires `core`, which
transitively pulls in the resolvers.)

Fix: extracted the entire single-source machinery into a new **leaf namespace**
`psi.agent-session.commands.builtin-specs` (only `clojure.string`):
`builtin-command-specs` (the table), `strip-slash`, `exact-command-handlers`,
`prefixed-command-prefixes`, `builtin-command-names`, `builtin-help-block`,
`builtin-command-specs-for-resolver`. `commands` now `:as bspec` and references
these; the resolver `:as builtin-specs` references the accessor. No cycle, and
the single-source-of-names invariant is unchanged (the table is still the sole
name source). Architecture-fit review's placement-in-agent-session intent is
preserved (the leaf ns is co-located under `agent-session/commands/`).
Slice-1 tests updated to reference `bspec/...` (the vars are now public on the
leaf ns rather than `^:private` on `commands`).

Verification: focused run of `commands-test` + `builtin-commands-resolver-test`
→ 59 tests, 247 assertions, 0 failures. clj-kondo clean.

## Slice 3 implementation (2026-06-01)

TUI now consumes the backend built-in command surface via EQL, exactly like
`:psi.extension/command-names`:

- `support.clj`: `command-refresh-query` + `build-init` introspection query gain
  `:psi.agent-session/builtin-command-specs`. `refresh-extension-command-names`
  (extended in place, P4) destructures both keys from the one query result and
  `cond->` `assoc`es `:extension-command-names` and `:builtin-command-specs`,
  each vector-guarded. `build-init` seeds `:builtin-command-specs` from the
  introspection result.
- `autocomplete.clj`: `slash-candidates` builds built-in candidates from
  `(:builtin-command-specs state)` (slash-prefixed bare names via
  `as-slash-command`), replacing `shared/builtin-slash-commands` in the concat.
- `shared.clj`: deleted the `builtin-slash-commands` `def` (P5). The `shared`
  require in `autocomplete.clj` stays (still used by
  `input-value`/`input-pos`/`set-input-value`).

Tests (`app_input_selector_test.clj`): seeded a representative
`sample-builtin-command-specs` into `init-state` (post-init `assoc`, since
`build-init` seeds the slot from the query result, nil in tests) so existing
`/help`-present assertions hold; added `/reload-models` backend-sourced
autocomplete test + empty-specs→no-builtins test + a
`refresh-extension-command-names` two-key fold test.

Verification: `app-input-selector-test` 14 tests, 38 assertions, 0 failures;
full `clojure -M:test` unit suite exits 0 (no FAIL/ERROR). clj-kondo clean.

## Slice 4 implementation (2026-06-01)

Emacs now consumes the backend built-in command surface on both channels,
exactly like `extension-command-names`:

- `psi-session-commands.el`: query string gains
  `:psi.agent-session/builtin-command-specs`; new
  `psi-emacs--builtin-command-specs-from-query-frame` extractor (channel 1);
  `psi-emacs--apply-slash-completion-data` gains a 3rd positional arg
  `(names builtin-specs templates)` and stores the new state slot;
  `psi-emacs--slash-completion-token` gains a `:builtins` segment (via new
  `psi-emacs--normalize-builtin-command-specs`), order `:commands :builtins
  :templates`. Refresh-fn passes built-in specs through.
- `psi-events.el`: `declare-function` arity updated; the inline `next-token` in
  `psi-emacs--slash-completion-data-changed-p` extracts `raw-builtin-specs`,
  adds `has-builtin-specs` to the change-detection guard `or` (I2), emits the
  matching `:builtins` segment, and passes built-in specs to the apply call
  (channel 2).
- `psi-globals.el`: `builtin-command-specs` struct slot.
  `psi-lifecycle.el`: seed `nil` in initial state + transcript-reset path.
- `psi-completion.el`: `psi-emacs--state-slash-command-specs` builds
  `backend-specs` from state and prepends them (backend-wins via `seq-uniq`
  first-wins); `defcustom` default trimmed to Emacs-only `/skill:` with a
  rewritten docstring (open question #3 resolved). Added `declare-function` for
  `psi-emacs--alist-get-any`.

### Deviation: existing capf/tree tests reseeded

Removing the built-in commands from the `defcustom` default meant tests that
asserted `/resume`, `/jobs`, `/job`, `/remember`, `/history`, `/tree` from the
defcustom default now have no source. These tests were updated to seed
`:builtin-command-specs` into `psi-emacs--state` (representing the backend
resolver output) — they now correctly exercise the backend-sourced path. The
token-equality regression test gained the `:builtins nil` segment. New tests:
backend-sourced `/reload-models`, backend-description-wins-over-custom,
built-in-spec-only session event refresh (I2 lock).

Verification: `bb emacs:byte-compile` clean; `bb emacs:check` 324/324, 0
unexpected.

## Slice 5 implementation + AC verification (2026-06-01)

Coherence lock, changelog, docs, full verify.

- AC6 backend coherence lock: `builtin-commands-resolver-exposes-full-spec-table
  -membership-test` asserts the resolver bare-name set == `builtin-command-names`
  (the single source). Combined with the TUI test (candidates derive purely from
  `:builtin-command-specs`; empty→none) and Emacs tests (candidates derive from
  the `builtin-command-specs` state slot), adding a spec-table entry surfaces in
  both UIs with no UI-side list edit.
- CHANGELOG `[Unreleased]` → Changed: built-in commands now appear consistently
  in TUI/Emacs autocomplete; previously-missing commands listed; Emacs
  `defcustom` repurposed.
- `doc/architecture.md` EQL Introspection Tips: documents the single-source spec
  table → resolver → UI-projection surfacing of slash commands.

### AC verification

- AC1 ✓ `:psi.agent-session/builtin-command-specs` = vector of
  `{:name :description}`, bare names, short descriptions (no `:usage`),
  graph-discoverable, resolvable, in spec-table order — `builtin-commands
  -resolver-shape-test`.
- AC2 ✓ single keyed `builtin-command-specs` table; `exact-command-handlers`,
  `prefixed-command-prefixes`, resolver specs are derived projections;
  `/project-repl` `#{:exact :prefixed}` feeds both; prefixed-`case` branch
  coherence is a separate narrow test — Slice-1 projection/dual-kind/branch
  tests.
- AC3 ✓ `format-help` built-in lines derive from the table in order, `:usage`
  inline, `:hide-in-help?` (`/?`,`/exit`,`/project-repl`) skipped, `/skill:name`
  literal — `format-help-derived-from-spec-table-test`.
- AC4 ✓ TUI autocomplete includes backend-sourced `/reload-models` —
  `autocomplete-slash-includes-backend-builtin-commands-test`.
- AC5 ✓ Emacs autocomplete includes backend-sourced `/reload-models` —
  `psi-capf-slash-includes-backend-builtin-commands`; built-in-only refresh
  (`psi-session-updated-applies-builtin-specs-only-change`).
- AC6 ✓ end-to-end membership lock (above) + UI tests prove no UI-side list.
- AC7 ✓ UIs read via EQL resolvers; no UI hardcoded built-in list remains
  (`shared/builtin-slash-commands` deleted; Emacs `defcustom` trimmed).
- AC8 ✓ CHANGELOG `[Unreleased]` + `doc/architecture.md` updated.

Verification: full `clojure -M:test` exits 0; `bb lint` 0 errors/0 warnings;
`bb emacs:check` 324/324; `bb fmt:check` clean.

## 2026-06-01 — Implementation review (task-implementation-review), pass 1

Scope: implementation quality (design-fit, architecture-fit, new-vs-existing
pattern, unnecessary abstraction, structural perf). Read all task artifacts +
the landed code/tests/docs. Verified projections at runtime against the prior
literals (`exact-command-handlers`, `prefixed-command-prefixes`,
`builtin-command-names`, handler keywords) — **byte-identical**, no command
dropped/added. `format-help` order + membership + descriptions reproduce the
pre-task help exactly (only column padding dropped, as noted). Ran focused
suites green: `commands-test` (51/206), `commands-builtin-specs-test` (via
suite), `builtin-commands-resolver-test` (3/24), TUI `app-input-selector-test`
(14/38), `bb emacs:check` 324/324; `bb fmt:check` + clj-kondo clean.

Strong fit: single keyed `builtin-command-specs` table is the sole name source;
every other surface is a pure projection (`unreachable > forbidden` satisfied
structurally). Resolver mirrors `extension-commands-resolver`; UIs consume via
EQL (one-way). Leaf-ns extraction (`commands.builtin-specs`) cleanly breaks the
documented load cycle without weakening the invariant. Emacs merge prepends
backend specs so `seq-uniq` first-wins gives backend precedence — correct. No
unnecessary abstraction; reuses the existing extension-command-names pattern; no
structural perf concern.

One minor (non-blocking, design-sanctioned) observation:

- R1 — `prefixed-case-branch-coherence-test` compares the prefixed spec-table
  keys against a **second hardcoded literal** `case-keys` set, not against the
  live `dispatch-prefixed-command` `case` branch keys. It therefore locks the
  spec table against a static snapshot and does **not** detect drift between the
  table and the real `case` form (the seam its name implies it guards). The
  design explicitly scoped the prefixed-`case` handler-wiring out (hand-written,
  residual), so this is not a defect — but the test gives less protection than
  its name suggests. Consider either deriving `case-keys` from the actual `case`
  form (e.g. a small data-driven branch table the `case` and the test both read)
  or renaming/recommenting the test to reflect that it is a spec-table snapshot
  lock, not a live-`case` coherence check.

No correctness, design-fit, or architecture-fit defects found. R1 is a
test-clarity nicety, not a blocker.

PASS_STATUS: ACTIONABLE_FEEDBACK

## 2026-06-01 — Implementation review (task-implementation-review), pass 2

Scope: implementation quality (design-fit, architecture-fit, new-vs-existing
pattern, unnecessary abstraction, structural perf). Read all task artifacts +
the landed code (`commands/builtin_specs.clj`, `commands.clj`,
`resolvers/extensions.clj`), tests
(`commands_builtin_specs_test.clj`, `builtin_commands_resolver_test.clj`),
CHANGELOG, `doc/architecture.md`. R1 follow-up (66e5d7b49) confirmed landed: the
live `commands/prefixed-case-branches` def + load-time `assert` + the test
reading `@#'commands/prefixed-case-branches` genuinely lock the prefixed seam.
Re-ran focused suites green: `builtin-commands-resolver-test` 3/24,
`commands-builtin-specs-test` 6/18, 0 failures; clj-kondo clean on the three
changed src files.

Strong fit confirmed: single keyed table is the sole name source; projections
byte-identical to prior literals (snapshot tests pass); resolver mirrors the
extension surface; UIs read via EQL; leaf-ns extraction breaks the load cycle
cleanly; docs+changelog accurate.

Two new actionable findings:

- R2 — **Exact-handler `case` seam has no live coherence guard (asymmetric to
  R1).** R1 locked the *prefixed* `case` (`dispatch-prefixed-command`) with a
  live `prefixed-case-branches` def + load-time `assert` + a test reading that
  def. The *exact* dispatch `case` in `dispatch*` (commands.clj ~744–757) is
  structurally identical — a hand-written `case` keyed by the `:handler`
  keywords the spec table's `:exact` entries carry (`:quit :new :resume :status
  :history :help :prompts :skills :worktree :reload-models :reload-prompts
  :reload-extension-installs :project-repl :logout`) — yet has **no** equivalent
  live guard. Its only protection is `exact-command-handlers-projection-unchanged
  -test`, a **static snapshot** lock (compare to a hardcoded snapshot map), i.e.
  exactly the weakness R1 was raised to fix, left in place on the exact side. An
  `:exact` spec entry whose `:handler` is missing from the `dispatch*` `case`
  falls silently to `nil` (no exact dispatch), then to the prefixed/extension
  fallthrough — a handler-wiring drift identical in class to the prefixed seam
  R1 closed. The design's "residual constraint" / "handler-wiring residual"
  scoping (design.md:55–57,170–190,217–220; plan.md:48–50,291) names **only** the
  prefixed `case`; it never mentions the exact `case`, so this seam is neither
  closed nor explicitly scoped out. Either (a) lock it symmetrically — a live
  `exact-case-branches` (or `exact-handler-keywords`) def + load-time `assert`
  that it equals `(set (vals bspec/exact-command-handlers))` plus a coherence
  test reading that def — or (b) explicitly extend the design's handler-wiring
  out-of-scope note to cover the exact `case` and rename/recomment
  `exact-command-handlers-projection-unchanged-test` to reflect it is a
  snapshot lock, not a live-`case` coherence check (the same R1 clarity remedy).

- R3 — **Undocumented resolver-input deviation from plan.**
  `builtin-commands-resolver` (resolvers/extensions.clj:150) is written
  `[_env]` with `::pco/input []` — it takes **no** `:psi/agent-session-ctx`,
  unlike `extension-commands-resolver` (which it claims to "mirror") and unlike
  the plan's explicit statement that the resolver "reads `agent-session-ctx`"
  (plan.md:70). The deviation is sound and arguably better (built-in specs are
  static, session-independent, so an input-free resolver is correct and still
  resolves for a session — graph-discovery test passes), but it is a real
  divergence from the plan that is **not recorded** in implementation.md's
  Slice-2 notes (which only document the leaf-ns load-cycle deviation). Record
  the input-free shape as a deliberate deviation (static specs ⇒ no session
  context needed) so the plan↔code divergence is traceable, or note why the
  empty-input form is preferred over the mirrored `:psi/agent-session-ctx` input.

Both are non-blocking: no correctness, behaviour, or AC defect. R2 is a
seam-symmetry/test-protection gap of the same kind R1 addressed; R3 is a
traceability/documentation gap. Routing, help, resolver, and UI behaviour are
correct and verified.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Review pass 2 follow-up execution (R2, R3)

- **R2 — exact-`case` seam locked symmetrically with R1 (option (a) + (b) clarity
  remedy).** Added `commands/exact-case-branches` — a private set of the
  `:handler` keywords that the exact-command `case` in `dispatch*` actually
  routes, authored adjacent to the `case` (which needs compile-time literal
  keys) as the single literal source of its branch set. A load-time
  `(assert (= exact-case-branches (set (vals bspec/exact-command-handlers))) …)`
  proves the live `case` stays coherent with the spec-table exact projection's
  handler values, so an `:exact` spec entry whose `:handler` is absent from the
  `case` is caught at namespace load (`unreachable > forbidden`), exactly
  mirroring `prefixed-case-branches`. Added
  `exact-case-branch-coherence-test` (reads `@#'commands/exact-case-branches`,
  parallels `prefixed-case-branch-coherence-test`) and recommented
  `exact-command-handlers-projection-unchanged-test` to state plainly it is a
  static snapshot lock, NOT a live-`case` coherence check. Both gaps R2 named
  (missing live guard + misleading snapshot-test framing) are closed.
  Verification: `commands-builtin-specs-test` + `builtin-commands-resolver-test`
  10 tests/43 assertions green; `commands-test` 51/206 green; clj-kondo clean
  over the changed files. The load-time assert also fires on every test load —
  another live proof the seam matches.

- **R3 — resolver input-free shape recorded as a deliberate Slice-2 deviation.**
  Extended the `builtin-commands-resolver` docstring to explain why it is written
  `[_env]` / `::pco/input []` rather than mirroring
  `extension-commands-resolver`'s `:psi/agent-session-ctx` input (and the plan's
  "reads agent-session-ctx"): the built-in spec table is a compile-time constant
  — session-independent — so an agent-session-ctx input would be inert ceremony.
  The input-free form keeps the resolver honest about its (lack of) dependencies
  and lets it resolve without an agent-session context present. The plan↔code
  divergence is now traceable at the most local point (the resolver itself) and
  here. Existing graph-discovery + resolver-shape tests already cover its
  behaviour (unchanged).

## 2026-06-01 — Implementation review (task-implementation-review), pass 3

Scope: implementation quality (design-fit, architecture-fit, new-vs-existing
pattern, unnecessary abstraction, structural perf). Independent re-read of all
task artifacts + the landed code across all three layers + tests + docs:
`commands/builtin_specs.clj`, `commands.clj` (both `case` seams),
`resolvers/extensions.clj`, `commands_builtin_specs_test.clj`,
`builtin_commands_resolver_test.clj`, TUI `autocomplete.clj`/`support.clj`/
`shared.clj`, Emacs `psi-completion.el`/`psi-session-commands.el`/`psi-events.el`/
`psi-globals.el`/`psi-lifecycle.el`, CHANGELOG, `doc/architecture.md`.

Verified prior findings landed and correct:
- R1 (prefixed-case seam) — live `commands/prefixed-case-branches` def +
  load-time `assert` + `prefixed-case-branch-coherence-test` reads
  `@#'commands/prefixed-case-branches`. ✓
- R2 (exact-case seam) — symmetric live `commands/exact-case-branches` def +
  load-time `assert (= … (set (vals bspec/exact-command-handlers)))` +
  `exact-case-branch-coherence-test`; `exact-command-handlers-projection-
  unchanged-test` recommented as a static snapshot lock (not live-`case`). ✓
- R3 (resolver input-free deviation) — `builtin-commands-resolver` docstring
  documents the `::pco/input []`/`_env` choice (static specs ⇒ no session ctx)
  and the plan↔code divergence is recorded here. ✓

Fresh independent checks (no prior pass covered these in depth):
- TUI `autocomplete.clj` builds built-in candidates purely from
  `(:builtin-command-specs state)` via `as-slash-command`; `shared/
  builtin-slash-commands` is gone; `shared` require retained (P5). The `nil`
  built-in `:description` in `slash-candidates` matches the pre-existing
  candidate shape (extension/template candidates are also `:description nil`
  there) — no regression. ✓
- Emacs `psi-emacs--state-slash-command-specs` prepends `backend-specs` then
  `seq-uniq` first-wins → backend descriptions win on collision (AC, design). ✓
  `defcustom` default correctly trimmed to `/skill:`; docstring accurate.
- No hardcoded built-in command list remains in any TUI/Emacs source
  (grep over `components/tui/src` + `components/emacs-ui/*.el`). ✓
- CHANGELOG `[Unreleased]` + `doc/architecture.md` EQL tips accurately describe
  the single-source spec-table → resolver → UI-projection surfacing. ✓
- Resolver tests cover shape/bare-names/internal-field-exclusion,
  full-membership coherence (resolver bare-name set == `builtin-command-names`,
  AC6), and graph discovery.

No new actionable findings. Single keyed table is the sole name source; every
surface (routing maps, names set, help block, resolver, both UIs) is a pure
projection; both `case` seams are live-locked; no unnecessary abstraction; the
extension-command-names pattern is reused, not reinvented; no structural perf
concern. R1/R2/R3 from prior passes are resolved. Design-fit, architecture-fit,
and all ACs hold.

PASS_STATUS: REVIEW_COMPLETE

## 2026-06-01 — Test review (task-test-review), pass 1

Scope: test quality only per skill — well-formedness, behaviour coverage
(∀ design behaviour ∃ covering test), and infra-dep hygiene (injectable ∧
nullable ∧ ¬mock ∧ ¬stub). Read the three net-new test surfaces:
`commands_builtin_specs_test.clj`, `builtin_commands_resolver_test.clj`, TUI
`app_input_selector_test.clj`, Emacs `psi-capf-test.el`; cross-checked against
`builtin_specs.clj` and the design ACs. Re-ran focused suites green:
`commands-builtin-specs-test` 7/19, `builtin-commands-resolver-test` (with the
former) 10/43, 0 failures.

Infra-dep hygiene — clean on the net-new surface:
- No `with-redefs` in any net-new test. The TUI `stub-agent-fn` and `query-fn`
  are injected REAL functions (queue-pushers / query stubs at the agent/EQL
  boundary), not interaction-asserting mocks — they verify resulting state, not
  calls. Aligns with `testing-without-mocks`.
- Resolver tests drive the REAL session context (`session/create-context` +
  `new-session-in!` + `query-in`) through the live Pathom graph — no faked
  resolver. Good.

Coverage strengths: projection-unchanged snapshots (exact/prefixed/names),
dual-kind `/project-repl` both-projection + bare-vs-args dispatch, both live
`case`-seam coherence tests (R1/R2), resolver shape/bare-name/internal-field-
exclusion/order, graph discovery, TUI backend-sourced `/reload-models` +
empty-specs→none + two-key refresh fold, Emacs backend-sourced `/reload-models`
+ backend-desc-wins + built-in-only token refresh (I2). `/exit`/`/?` routing is
covered in `commands_test.clj` (hidden-in-help but still routed). AC1–AC8 each
have at least one covering test.

Two actionable test gaps:

- TT1 — **Resolver `:description` content is never locked to the spec table.**
  `builtin-command-specs-resolver-shape-test` asserts each spec has keys
  `#{:name :description}` and that `:description` is *non-blank* (`(seq …)`), but
  never that the resolver's `:description` for a name **equals** that name's
  spec-table `:description`. The full-membership lock
  (`builtin-commands-resolver-exposes-full-spec-table-membership-test`) compares
  **names only**. So a regression in `builtin-command-specs-for-resolver` that
  dropped/swapped descriptions (e.g. emitting the name as the description, or
  off-by-one zipping) would pass every test. AC1 fixes the resolver shape as
  `{:name :description}` carrying the *short description* — the description
  *content* is part of that behaviour and is currently unverified. Add an
  assertion that the resolver `{name → description}` map equals
  `(into {} (for [[k s] bspec/builtin-command-specs] [(bspec/strip-slash k)
  (:description s)]))` (or at least spot-check a representative entry's exact
  description), locking resolver descriptions to the single source.

- TT2 — **`:hide-in-help?` absence assertions are fragile whole-message
  substring checks.** `format-help-derived-from-spec-table-test` asserts hidden
  entries are absent via `(not (str/includes? message "/?"))` /
  `"/exit"` / `"/project-repl"` against the **entire** help message (all
  sections + description prose), not the built-in block specifically. These pass
  today only incidentally (no description renders the literal `/exit`/`/?`/
  `/project-repl` token), but the check is brittle in both directions: a future
  description containing one of those literal tokens would false-FAIL, and the
  check does not actually prove the *built-in block* omits the entry (only that
  the token appears nowhere). Tighten to assert against the built-in block
  directly — e.g. call `bspec/builtin-help-block` and assert the hidden entries'
  lines are absent while shown entries' lines are present — so the test locks the
  `:hide-in-help?` projection rather than a global substring coincidence.

Non-actionable observation (not a 205 net-new test): `commands-test.clj` ~733
`reload-extension-installs` test uses `with-redefs` on
`session/reload-extension-installs-in!`. It predates this task's test surface and
is out of the test-review remit here; flagged only for future `testing-without-
mocks` cleanup, not as a 205 follow-up.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test review follow-up execution (TT1, TT2) — 2026-06-01

Executed the two newly-added test-review follow-ups; both are the only unchecked
`steps.md` items added by the preceding test-review pass.

- **TT1 — resolver `:description` content locked to the spec table.** Added a
  `testing` block to `builtin-command-specs-resolver-shape-test`
  (`builtin_commands_resolver_test.clj`) asserting the resolver-output
  `{name → description}` map equals the spec-table-derived
  `(into {} (for [[k s] bspec/builtin-command-specs] [(bspec/strip-slash k)
  (:description s)]))`. Prior tests only checked descriptions were non-blank and
  locked the name order, not description *content* — so a dropped, swapped, or
  zip-misaligned description in `builtin-command-specs-for-resolver` is now
  caught (AC1). Both maps built via `(juxt :name :description)` / table walk for
  an order-independent, content-exact comparison.

- **TT2 — `:hide-in-help?` projection locked against the built-in block.**
  Removed the fragile whole-`/help`-message substring checks
  (`(not (str/includes? message "/?"/"/exit"/"/project-repl"))`) from
  `format-help-derived-from-spec-table-test` and added a dedicated
  `builtin-help-block-hide-in-help-projection-test` that renders
  `(bspec/builtin-help-block)` and, for every spec entry, asserts the entry's
  exact rendered line (`"  /name [usage ]— description"`) is absent iff
  `:hide-in-help?` and present otherwise. This proves built-in-block *omission*
  of hidden entries (data-driven over the table, so a new hidden/shown entry is
  covered automatically) rather than a global substring coincidence that would
  false-fail on a description carrying a hidden token. The `/skill:name`
  literal-line check stays in the `/help` message test.

Verification: `commands-builtin-specs-test` + `builtin-commands-resolver-test`
→ 11 tests, 67 assertions, 0 failures (up from 10/43 — TT1 adds 1 assertion in
the resolver shape test, TT2 adds a new test with the table-driven assertions).
clj-kondo clean over both changed test files. No production code/docs changed
(test-only follow-ups). No blocking reasons; both items checked.

## 2026-06-01 — Test review (task-test-review), pass 2

Scope: test quality only per skill — well-formedness, behaviour coverage
(∀ design behaviour ∃ covering test), infra-dep hygiene (injectable ∧ nullable
∧ ¬mock ∧ ¬stub). Independent re-read of the net-new test surface
(`commands_builtin_specs_test.clj`, `builtin_commands_resolver_test.clj`, TUI
`app_input_selector_test.clj`, Emacs `psi-capf-test.el`) against the spec source
(`commands/builtin_specs.clj`) and the design ACs / "Spec-entry field set"
decision. Re-ran `commands-builtin-specs-test` + `builtin-commands-resolver-test`
→ 11 tests, 67 assertions, 0 failures.

Confirmed pass-1 strengths hold and TT1/TT2 follow-ups landed correctly:
- Infra-dep hygiene clean: no `with-redefs` in any net-new Clojure test; TUI
  `query-fn`/`stub-agent-fn` are injected REAL boundary fns (state-asserting,
  not interaction-asserting); resolver tests drive the live Pathom graph via the
  real session context. Emacs `cl-letf` (psi-capf-test.el:287) is a pre-existing
  `project-current` stub in an unrelated tree test, outside the 205 surface.
- TT1 (resolver description content == spec table) and TT2
  (`builtin-help-block` `:hide-in-help?` projection) verified present and exact.
- Both `case` seams (R1/R2) live-locked; projection-unchanged snapshots; dual-kind
  dispatch; resolver shape/order/membership/graph-discovery; TUI + Emacs
  backend-sourced `/reload-models`, empty-specs→none, two-key fold,
  backend-desc-wins, built-in-only token refresh (I2). Hidden-entry
  (`/?`/`/exit`/`/project-repl`) autocomplete presence is covered transitively:
  the resolver test asserts `?`/`exit` present, and UI candidates derive purely
  from the resolver surface (empty→none proves it), so no separate UI hidden-entry
  test is needed.

One new actionable test gap:

- TT3 — **Spec-table per-entry well-formedness invariant is unverified.** The
  design's "Spec-entry field set" decision (design.md "Description granularity";
  AC2 "Dispatch-kind representation") mandates a structural invariant on EVERY
  `builtin-command-specs` entry: `:kinds` is a non-empty subset of
  `#{:exact :prefixed}`, and `:handler` is **required iff `:exact ∈ :kinds`**.
  The projections in `builtin_specs.clj` silently *assume* this without
  enforcement, and no test asserts it. Two representable-but-invalid states slip
  through every current test:
  (a) an entry with an EMPTY `:kinds` set is a valid map — it would vanish from
  BOTH `exact-command-handlers` and `prefixed-command-prefixes` (filtered by
  `contains? :kinds`), yet still appear in `builtin-command-names`, the resolver,
  and UI autocomplete: a "named but unroutable" command. That is precisely the
  class of invalid state Option B claims to make `unreachable`, yet it is
  representable and untested.
  (b) an `:exact` entry MISSING `:handler` projects `"/foo" → nil` into
  `exact-command-handlers`; the `nil` then drifts from `exact-case-branches`
  (so the R2 load-time assert would catch the handler-keyword mismatch) — but
  the *table-level* contract ("`:handler` present whenever `:exact`") is not
  asserted directly, and a stray `:kinds #{:prefixed} :handler …` or
  unknown-keyword `:kinds` member has no guard at all.
  The R1/R2 case-coherence tests lock the *projection ↔ dispatch case* seam, and
  TT1/TT2 lock description/help content, but none of them locks the *spec entry
  shape itself* — the single source whose well-formedness every projection
  depends on. Add a `builtin-command-specs-well-formed-test` asserting, for every
  entry: `(:kinds spec)` is a non-empty subset of `#{:exact :prefixed}`, and
  `(:exact ∈ :kinds) ⇒ (some? (:handler spec))` (a short malli schema over the
  entry-value shape, or explicit `doseq`/`every?` assertions). This locks the
  single-source data shape so a malformed entry is caught as a test failure
  rather than silently mis-projecting. Non-blocking: the current table is
  well-formed, so no behaviour is wrong today — this guards future edits to the
  sole name source.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test review follow-up TT3 — done

Added `builtin-command-specs-well-formed-test` in
`components/agent-session/test/psi/agent_session/commands_builtin_specs_test.clj`.
Explicit `doseq`/`every?` assertions (no malli dep added) over every
`bspec/builtin-command-specs` entry:
- `:kinds` is a set, non-empty, and `⊆ #{:exact :prefixed}` (rejects
  empty-`:kinds` "named-but-unroutable" entries and unknown `:kinds` members);
- `:exact ∈ :kinds ⇒ (some? (:handler spec))` (rejects the `"/foo" → nil`
  exact-projection mis-shape, table-level — complementing R2's load-time
  case-keyword assert);
- `:description` is a non-blank string on every entry (locks the resolver/help
  text source shape).
Locks the single-source entry shape itself (the gap R1/R2 — projection↔case
seam — and TT1/TT2 — content — did not cover). Targeted namespace green:
9 tests, 188 assertions, 0 failures; clj-kondo + cljfmt clean. Pre-existing
unrelated TUI `app_projection_test` failure ("moving selection … highlighted
autocomplete row") is outside this change's scope.

PASS_STATUS: REVIEW_COMPLETE

## 2026-06-01 — Test review (task-test-review), pass 3

Scope: test quality only per skill — well-formedness, behaviour coverage
(∀ design behaviour ∃ covering test), infra-dep hygiene (injectable ∧ nullable
∧ ¬mock ∧ ¬stub). Independent re-read of the full net-new test surface
(`commands_builtin_specs_test.clj`, `builtin_commands_resolver_test.clj`, TUI
`app_input_selector_test.clj`, Emacs `psi-capf-test.el`) against the spec source
(`commands/builtin_specs.clj`), the trimmed `defcustom`
(`psi-completion.el:19`), and every AC. Re-ran
`commands-builtin-specs-test` + `builtin-commands-resolver-test`
→ 12 tests, 213 assertions, 0 failures.

Confirmed prior strengths + TT1/TT2/TT3 follow-ups all landed and exact:
- Infra-dep hygiene clean: no `with-redefs` in any net-new Clojure test; TUI
  `query-fn`/`stub-agent-fn` are injected REAL boundary fns (state-asserting);
  resolver tests drive the live Pathom graph via the real session context;
  Emacs tests seed real state structs (no interaction mocking on the 205
  surface). `cl-letf` at psi-capf-test.el is a pre-existing unrelated tree-test
  stub.
- AC1 resolver shape + description content (TT1), AC2 projections/dual-kind/
  well-formedness (TT3)/both `case` seams (R1/R2), AC3 `:hide-in-help?` block
  projection + `:usage` inline (TT2), AC4 TUI backend-sourced `/reload-models`
  + empty-specs→none, AC5 Emacs backend-sourced `/reload-models` + I2
  built-in-only token refresh, AC6 resolver full-membership lock — all covered.

One new actionable test gap:

- TT4 — **Emacs has no "no-backend ⇒ defcustom supplies no built-ins" guard
  (asymmetric to the TUI, leaves AC6/AC7's `defcustom`-trimmed claim
  unverified).** The design's open-question-#3 resolution (and AC7's "no UI-side
  hardcoded built-in list remains") makes built-ins arrive on the Emacs consumer
  **solely** from the backend specs; the `defcustom`
  `psi-emacs-slash-command-specs` default is trimmed to the Emacs-only `/skill:`
  affordance (psi-completion.el:19–20). The TUI locks the symmetric claim with
  `autocomplete-slash-includes-backend-builtin-commands-test`'s second block:
  with `:builtin-command-specs []`, `/quit` is **absent** ("built-in candidates
  come from state, not a hardcoded list"). The Emacs capf tests have **no
  equivalent**: every built-in-exercising test
  (`psi-capf-slash-includes-backend-builtin-commands`,
  `psi-capf-slash-context-*`) seeds `:builtin-command-specs`, and
  `psi-capf-slash-backend-builtin-description-wins-over-custom` seeds BOTH a
  backend `/help` and a stale custom `/help`, so it never isolates the
  no-backend case. So a regression that re-added built-in commands to the
  `defcustom` default value (or a future `psi-emacs--state-slash-command-specs`
  edit that re-introduced a hardcoded built-in list) would pass every Emacs
  test — the very "no UI-side list" invariant this task exists to enforce is
  unguarded on the Emacs side. Add a capf test that, with
  `:builtin-command-specs nil`/`'()` (no backend) and the default trimmed
  `psi-emacs-slash-command-specs`, slash completion for `/qu` (or `/he`) yields
  **no** `/quit`/`/help` candidate — only `/skill:`-style affordances and any
  user-added entries survive — mirroring the TUI empty-specs→none guard so the
  trimmed-`defcustom` / backend-sole-source claim is locked on both consumers.
  Non-blocking: the default is correctly trimmed today, so no behaviour is wrong
  now — this guards future `defcustom`/merge-fn edits to the Emacs built-in
  source.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test review follow-up execution (TT4) — 2026-06-01

- **TT4 — Emacs "no-backend ⇒ defcustom supplies no built-ins" guard added.**
  New ert test `psi-capf-slash-no-backend-yields-no-builtins`
  (`components/emacs-ui/test/psi-capf-test.el`), symmetric to the TUI
  empty-specs→none block in
  `autocomplete-slash-includes-backend-builtin-commands-test`. The test:
  - First asserts the shipped `(default-value 'psi-emacs-slash-command-specs)`
    is exactly the trimmed `(("/skill:" . …))` — so a regression re-adding
    built-ins to the defcustom default fails here directly.
  - Then, with `:builtin-command-specs nil` (no backend) and **without**
    `let`-binding the defcustom (exercises the real shipped default), asserts
    slash completion for `/qu` yields no `/quit` candidate and `/he` yields no
    `/help` candidate. Only the Emacs-only `/skill:` affordance / user-added
    entries survive. A future `psi-emacs--state-slash-command-specs` edit
    re-introducing a hardcoded built-in list would also fail this.
  - `bb emacs:check` green: 325/325 (was 324; +1 new test);
    `psi-capf-slash-no-backend-yields-no-builtins` passes. Byte-compile clean.
  This locks the backend-sole-source / trimmed-defcustom invariant (AC6/AC7) on
  both consumers — the asymmetry TT4 flagged is closed.

PASS_STATUS: NO_ACTIONABLE_FEEDBACK

## 2026-06-01 — Implementation review (task-implementation-review), pass 4

Scope: implementation quality (design-fit, architecture-fit, new-vs-existing
pattern, unnecessary abstraction, structural perf). Independent re-read of the
landed code across all three layers + tests + docs, and **independent
verification** (not trusting prior notes):
- `commands/builtin_specs.clj`: single ordered `builtin-command-specs` table is
  the sole name source; `exact-command-handlers`/`prefixed-command-prefixes`/
  `builtin-command-names`/`builtin-help-block`/`builtin-command-specs-for-resolver`
  are all pure projections. Leaf ns (only `clojure.string`) → no load cycle. ✓
- `commands.clj`: both `case` seams live-locked — `prefixed-case-branches` (R1)
  and `exact-case-branches` (R2) each with a load-time `assert` against the
  spec-table projection; `format-help` consumes `bspec/builtin-help-block`. ✓
- `resolvers/extensions.clj`: `builtin-commands-resolver` mirrors
  `extension-commands-resolver`; input-free `::pco/input []` deviation documented
  in its docstring (R3, static specs ⇒ no session ctx). Registered in the
  `resolvers` vector. ✓
- TUI `autocomplete.clj` sources built-ins purely from `(:builtin-command-specs
  state)`; `shared.clj/builtin-slash-commands` gone (grep confirms no
  `builtin-slash-commands` reference remains in `components/tui`). ✓
- Emacs `psi-completion.el` defcustom trimmed to `/skill:`; backend specs
  prepended (first-wins). ✓
- CHANGELOG `[Unreleased]` + `doc/architecture.md` accurately describe the
  single-source spec-table → resolver → UI-projection surfacing. ✓

Independently re-ran the suites green:
- `commands-builtin-specs-test` + `builtin-commands-resolver-test` → 12 tests,
  213 assertions, 0 failures.
- TUI `app-input-selector-test` → 14 tests, 38 assertions, 0 failures.
- `bb emacs:check` → 325/325, 0 unexpected.
- clj-kondo over the 3 changed src files → 0 errors, 0 warnings.

No new actionable findings. Single keyed table is the sole name source; every
surface is a pure projection; both `case` seams are live-locked; resolver reuses
the extension-command-names pattern (no new pattern, no unnecessary abstraction);
no structural perf concern (compile-time constant table). R1/R2/R3 and TT1–TT4
from prior passes are verified resolved. Design-fit, architecture-fit, and all
ACs (AC1–AC8) hold.

PASS_STATUS: REVIEW_COMPLETE

## 2026-06-01 — Test review (task-test-review), pass 4

Scope: test quality only per skill — well-formedness, behaviour coverage
(∀ design behaviour ∃ covering test), infra-dep hygiene (injectable ∧ nullable
∧ ¬mock ∧ ¬stub). Independent re-read of the full net-new test surface
(`commands_builtin_specs_test.clj`, `builtin_commands_resolver_test.clj`, TUI
`app_input_selector_test.clj`, Emacs `psi-capf-test.el`) against the spec source
(`commands/builtin_specs.clj`), `dispatch*`/`format-help` in `commands.clj`, and
every design AC.

Confirmed prior strengths + all prior follow-ups (R1/R2/R3, TT1–TT4) landed and
exact:
- Infra-dep hygiene clean: no `with-redefs`/mock/stub in any net-new Clojure
  test; TUI `query-fn`/`stub-agent-fn` are injected REAL boundary fns
  (state-asserting); resolver tests drive the live Pathom graph via the real
  session context; Emacs tests seed real `psi-emacs-state` structs. `cl-letf` at
  psi-capf-test.el is a pre-existing unrelated tree-test stub.
- Well-formedness lock (TT3), resolver description content (TT1),
  `:hide-in-help?` block projection (TT2), both `case` seams (R1/R2),
  projection-unchanged snapshots, resolver shape/order/membership/graph,
  TUI/Emacs backend-sourced + empty-specs→none guards (TT4) — all present.

Two new actionable coverage gaps (both non-blocking; current behaviour correct):

- TT5 — **`format-help` whole-block table-order reproduction is under-asserted.**
  `format-help-derived-from-spec-table-test` asserts only `quit < status < help`
  — three positions where `quit`/`status` are the first two table entries and
  `help` is the last non-hidden entry. The 18 intervening built-in lines,
  including every `:usage`-bearing prefixed entry (`/tree`, `/model`, `/speed`,
  `/effort`, `/thinking`, `/login`, `/jobs`, …), have **no** positional
  assertion. The design's "format-help derivation" / AC3 claim is "the help
  listing is reproduced unchanged **in order** and membership"; a regression
  that re-ordered the *middle* of `builtin-command-specs` (e.g. moving `/effort`
  before `/skills`, or `/model` after `/help`) would still pass `quit<status<help`
  and every membership/`:usage`-presence/`:hide-in-help?` test. The order
  invariant is therefore only spot-checked at the two endpoints, not locked
  across the block. Tighten: assert the rendered built-in block line sequence
  equals the spec-table-ordered, `:hide-in-help?`-filtered projection of
  `bspec/builtin-command-specs` (the same `line-for` shape
  `builtin-help-block-hide-in-help-projection-test` already builds), so the
  whole-block order — including interleaved `:usage` lines — is locked to the
  single source rather than the two endpoints. Closes the gap that AC3's
  "unchanged in order" is currently provable only for 3 of ~21 lines.

- TT6 — **Dual-kind `/project-repl` exact-first dispatch precedence is asserted
  by behaviour, not by precedence.** The design "Dispatch-kind representation"
  decision pins a specific invariant: bare `/project-repl` hits the **exact**
  handler and `/project-repl <args>` falls through to the **prefixed** `case`,
  with "dispatch precedence unchanged (exact-first then prefixed)".
  `project-repl-dual-kind-test` proves (a) both projections contain
  `/project-repl` and (b) bare → `:text` "Project nREPL" and `<args>` → `:text`.
  But because both the exact handler and the prefixed `case` route
  `/project-repl` to the same `dispatch-project-nrepl-command`, a behaviour
  assertion cannot distinguish exact-first from prefixed-first for the **bare**
  form — both paths yield the same `:text` result. So the precedence invariant
  the design explicitly states (and that step 37 calls "precedence unchanged") is
  not actually locked: a regression flipping `dispatch*`'s `(or (case …)
  (dispatch-prefixed-command …))` order, or dropping `/project-repl` from the
  exact projection, would still pass this test (the prefixed path would serve the
  bare form identically). Tighten to lock the seam, e.g. assert
  `(exact-command-handler "/project-repl") = :project-repl` (bare exact match) and
  that the prefixed matcher does **not** match the bare form
  (`(prefixed-command "/project-repl")` matches only `/project-repl <args>`, not
  bare) — proving the bare form is genuinely served by the exact path, not merely
  that "something returns Project nREPL". Closes the gap that the design's
  exact-first precedence claim is behaviourally indistinguishable under the
  current assertions.

Note: the task is already closed (`munera/closed/`); both gaps guard future edits
to the single source / dispatch ordering and harden AC3 + the dual-kind
precedence contract. Neither indicates a current behaviour defect.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Follow-up execution — review pass 4 (TT5, TT6)

TT5 (whole-block help order) and TT6 (dual-kind exact-first precedence) added as
`commands-builtin-specs-test` deftests.

### TT5 — full built-in help-block line order locked
Added `format-help-block-line-order-test`: builds the expected line sequence from
`bspec/builtin-command-specs` filtered by `:hide-in-help?`, in table order (reusing
the `line-for` shape from `builtin-help-block-hide-in-help-projection-test`), and
asserts `(str/split-lines (bspec/builtin-help-block))` equals it exactly. This
locks the whole ~18-line block — every interleaved `:usage`-bearing prefixed entry
(`/tree`, `/model`, `/speed`, `/effort`, `/thinking`, `/login`, `/jobs`, …)
included — to the single source, so a middle-of-table reorder now fails (AC3
"unchanged in order"), which the leading/trailing-only `<` checks in
`format-help-derived-from-spec-table-test` did not catch.

### TT6 — exact-first precedence reconciliation
TT6's proposed second assertion ("the prefixed matcher does **not** match the bare
form") is **inaccurate against the implementation**: `commands/prefixed-command`
matches the bare form too — `(= trimmed prefix)` is an explicit branch, so
`(prefixed-command "/project-repl")` returns `"/project-repl"`. The exact-first
precedence for the bare form is therefore NOT decided by the prefixed matcher
declining the bare form; it is decided by `dispatch*`'s `(or (case
(exact-command-handler …) …) (dispatch-prefixed-command …))` — the exact `case`
short-circuits the `or` for the bare form because the bare form has an exact
handler.

Honoured TT6's intent ("prove the bare form is genuinely exact-routed, exact-first")
with seam assertions that hold against the real code:
- `(@#'commands/exact-command-handler "/project-repl") = :project-repl` — bare form
  HAS an exact handler, so the `or`'s exact `case` returns non-nil and
  short-circuits before `dispatch-prefixed-command`.
- `(@#'commands/exact-command-handler "/project-repl start") = nil` — the `<args>`
  form has NO exact handler, so it is exclusively prefixed-routed (proving the two
  forms take different paths; the prefixed path is reachable only for `<args>`).
- `(@#'commands/prefixed-command "/project-repl start") = "/project-repl"` — the
  prefixed matcher reaches the `<args>` form.

Together these lock: bare → exact path (exact-first wins via `or` ordering), args →
prefixed path. A regression dropping `/project-repl` from the exact projection makes
assertion 1 fail (`nil`); flipping the `or` order is caught because the bare form
would then be prefixed-routed while still — but the discriminating lock is assertion
1 + the design's `or` ordering: with no exact handler the bare form could only be
prefixed-served. (Did NOT assert "prefixed does not match bare" since that is false
for the live matcher.)

## 2026-06-01 — Test review (task-test-review), pass 5

Scope per skill: well-formedness, behaviour coverage (∀ design behaviour ∃
covering test), infra-dep hygiene (injectable ∧ nullable ∧ ¬mock ∧ ¬stub).
Independent re-read of the full net-new test surface
(`commands_builtin_specs_test.clj` 14t/217a, `builtin_commands_resolver_test.clj`,
TUI `app_input_selector_test.clj`, Emacs `psi-capf-test.el`) against
`commands/builtin_specs.clj`, `commands.clj` `dispatch*`/`format-help`,
`resolvers/extensions.clj`, TUI `autocomplete.clj`/`support.clj`, and every AC.
Ran the two backend suites (14 tests / 217 assertions / 0 failures) and
`bb emacs:test` (325/325) — green.

Confirmed strengths (all prior follow-ups R1–R3, TT1–TT6 landed and exact):
- Infra-dep hygiene clean: no `with-redefs`/mock/stub in any net-new Clojure
  test. TUI `query-fn`/`stub-agent-fn` are injected REAL boundary fns
  (state-asserting, nullable seam per `λ one_way`); resolver tests drive the
  live Pathom graph via real session contexts; Emacs tests seed real
  `psi-emacs-state` structs and exercise the real event handler. The
  `psi-capf-test.el` `cl-letf` is a pre-existing unrelated tree-test stub.
- Spec-entry well-formedness (TT3), resolver shape/order/membership/graph +
  description content (TT1), `:builtin-command-names` ↔ specs symmetry
  (`names == (mapv :name specs)`) and spec-name-set == `builtin-command-names`
  (full membership lock), `:hide-in-help?` block projection (TT2), both `case`
  seams via live branch defs (R1/R2), projection-unchanged snapshots, whole
  help-block line order (TT5), dual-kind `/project-repl` exact-first seam (TT6),
  TUI backend-sourced `/reload-models` + `as-slash-command` bare→`/name`
  prefixing + empty-specs→none + folds-both-keys refresh guard, Emacs
  backend-sourced + desc-wins + no-backend⇒no-builtins (TT4) + builtin-spec-only
  event refresh through the real session-update handler (I2) — all present.

Independent checks of areas not explicitly flagged by prior passes — all found
already covered:
- `:psi.agent-session/builtin-command-names` symmetry attribute: content locked
  transitively (`names == (mapv :name specs)` + spec-name-set ==
  `builtin-command-names`); no separate divergence path.
- TUI `as-slash-command` bare→`/name` prefixing: exercised end-to-end by the
  backend-sourced `/reload-models` autocomplete test (seeds bare names).
- TUI non-vector refresh guard: symmetric for both slots
  (`refresh-extension-command-names-folds-builtin-specs-test`).
- I2 change-detection guard (built-in-spec-only event): locked through the real
  `psi-emacs--handle-session-updated-event` path with token-segment assertion.

No new actionable issues. Behaviour coverage is complete across the design ACs
(AC1–AC8); infra-dep hygiene is clean; tests are well-formed and lock to the
single source rather than spot-checks. No follow-up steps added (criterion 3:
nothing to add without duplicating existing TT1–TT6 coverage).

PASS_STATUS: REVIEW_COMPLETE

## Test review follow-ups (review pass 6)

- TT7 (NEW, actionable): The resolver's **full** name-order is under-locked
  against the spec table, asymmetrically with the help block (TT5 fully locks
  help-block line order). AC1 requires resolver output "in spec-table order",
  but `builtin-command-specs-resolver-shape-test` only asserts the representative
  `quit < status < help` triple ("specs appear in table order"), and the
  description-content lock (TT1) compares an **order-insensitive** `{name →
  description}` map. So a middle-of-table reorder — or a future `sort`/`set`
  introduced into `builtin-command-specs-for-resolver` — would pass every
  resolver test while violating AC1's order guarantee. Prior pass 5's
  REVIEW_COMPLETE note listed "resolver shape/order" as covered; the *full*
  order is not. Symmetric to the TT5 help-block-order finding that prior passes
  accepted as actionable. Lock the full sequence:
  `(mapv :name specs) == (mapv #(bspec/strip-slash (key %)) bspec/builtin-command-specs)`
  in `builtin-commands-resolver-test`, locking the whole resolver name order —
  every interleaved entry — to the single source. See steps.md "Test review
  follow-ups (review pass 6)".

## Follow-up execution — review pass 6 (TT7)

- TT7 (DONE): Added the full resolver name-order lock to
  `builtin-command-specs-resolver-shape-test`
  (`components/agent-session/test/psi/agent_session/builtin_commands_resolver_test.clj`),
  in a new `"the FULL resolver name-order equals the spec-table key order (AC1)"`
  testing block placed immediately after the existing representative
  `quit < status < help` triple block:

      (is (= (mapv #(bspec/strip-slash (key %)) bspec/builtin-command-specs)
             (mapv :name specs)))

  This locks the WHOLE, interleaved resolver output sequence to the single
  source — symmetric with TT5's whole help-block line-order lock — closing the
  gap that the leading-triple block and the order-insensitive TT1
  `{name → description}` map left every middle-of-table reorder (or a
  `sort`/`set` slipped into `builtin-command-specs-for-resolver`) passing every
  resolver test while violating AC1's "in spec-table order".

  Note: the pre-existing
  `"the bare-name vector mirrors the spec names in the same order"` block locks
  `builtin-command-names` (the resolver's `-names` attr) to the resolver `specs`
  order, but neither was tied to the *single source* spec-table key order — TT7
  anchors the `specs` sequence to `bspec/builtin-command-specs` itself, which
  transitively re-anchors `-names` too.

  Verification: `psi.agent-session.builtin-commands-resolver-test` — 3 tests /
  26 assertions, 0 failures (was 25; +1 assertion). clj-kondo clean on the
  changed test file. No source/doc/changelog change needed: TT7 is a pure
  test-coverage lock over already-correct behaviour (AC1 was already satisfied;
  the resolver projects in table order via `builtin-command-specs-for-resolver`'s
  ordered `for` over the `array-map`).

## 2026-06-01 — Test review (test-shaper), pass 7

Independent test-shaper re-read of the full net-new test surface
(`commands_builtin_specs_test.clj` 11t/192a, `builtin_commands_resolver_test.clj`
3t/26a, TUI `app_input_selector_test.clj` 14t/38a, Emacs `psi-capf-test.el`)
against the source seams (`commands/builtin_specs.clj`, `commands.clj`
`dispatch*`/`format-help`, `resolvers/extensions.clj`, TUI
`autocomplete.clj` `slash-candidates`, Emacs
`psi-completion.el`/`psi-events.el`). All four suites run green (backend 11+3,
TUI 14, Emacs 325/325 reported).

Confirmed strengths (R1–R3, TT1–TT7 all landed): infra-dep hygiene clean (no
mocks/stubs; injected real boundary fns + live Pathom graph + real
`psi-emacs-state` structs); single-source locks (well-formedness TT3, resolver
shape/full-order TT7 + description content TT1, help-block full line order TT5,
`:hide-in-help?` block projection TT2, both `case` seams R1/R2, dual-kind
exact-first seam TT6, empty-specs→none on both UIs TT4); behaviour-focused
state assertions throughout.

### New actionable (TS1)

- TS1 — **built-in ↔ template/extension name-collision dedup is untested on
  both UIs.** Task 205 newly folds `builtin-command-specs` into the TUI
  `slash-candidates` `(concat builtins templates skills ext-cmds)` →
  `distinct` (autocomplete.clj:61-63) and into the Emacs
  `psi-emacs--state-slash-command-specs` merge (`seq-uniq`, backend-first). A
  built-in name that also appears as a prompt-template or extension command is
  therefore deduped to a single candidate — new behaviour introduced by this
  task. But no test exercises a built-in colliding with a template/extension:
  - TUI: `autocomplete-slash-includes-backend-builtin-commands-test` seeds only
    built-ins; the dedup path (`distinct`) is never driven with a built-in name
    equal to a template/ext-cmd, so a regression dropping `distinct` (or merging
    in an order that emits two `/resume` entries) passes every TUI test.
  - Emacs: `psi-capf-slash-dedupes-command-template-collision-by-command-name`
    seeds a `resume` *template* but NO `:builtin-command-specs`, and the trimmed
    defcustom default has no `/resume`, so it does not actually exercise a
    built-in↔template collision either; the backend-first `seq-uniq` merge is
    only proven for the desc-wins case (`/help` backend vs stale custom), not for
    a built-in vs a *template/extension* of the same name.

  This is symmetric to the existing desc-wins (TT-era) and empty-specs (TT4)
  locks but covers the *dedup* contract of the newly added source. Add narrow
  collision tests:
  - TUI: seed `:builtin-command-specs [{:name "resume" …}]` AND
    `:prompt-templates [{:name "resume" …}]` (or `:extension-command-names
    ["resume"]`), open `/re`, assert exactly one `/resume` candidate.
  - Emacs: seed `:builtin-command-specs '((( :name . "resume") …))` AND a
    `resume` `:prompt-templates`/`:extension-command-names`, `/re`, assert
    `(= 1 (length (seq-filter (lambda (c) (equal c "/resume")) cands)))`.

  Pure test-coverage locks over already-correct behaviour (both UIs already
  dedup); no source change expected. See steps.md "Test review follow-ups
  (review pass 7)".

## Follow-up execution — review pass 7 (TS1)

- TS1 (DONE): Locked the built-in ↔ template/extension name-collision *dedup*
  contract that task 205 newly introduced by folding `builtin-command-specs`
  into both UIs' candidate sources. Pure test-coverage; both UIs already dedup
  correctly, so no source change was made.

  - TUI: added `autocomplete-slash-dedupes-builtin-template-collision-test`
    (`components/tui/test/psi/tui/app_input_selector_test.clj`) with two arms —
    `:builtin-command-specs [{:name "resume" …}]` colliding with (a) a
    `:prompt-templates [{:name "resume"}]` entry and (b) an
    `:extension-command-names ["resume"]` entry. Opens `/`, asserts exactly one
    `/resume` candidate via `(filter #{"/resume"} cand-vals)`. This is the first
    test to drive `slash-candidates`'s `(concat builtins templates skills
    ext-cmds)` → `distinct` (autocomplete.clj:61-63) with a built-in name equal
    to a template/ext-cmd name; the existing
    `autocomplete-slash-includes-backend-builtin-commands-test` seeds only
    built-ins. A regression dropping `distinct` (or merging two `/resume`
    entries) now fails. Suite: 15t/40a (was 14t/38a).

  - Emacs: added `psi-capf-slash-dedupes-builtin-template-collision-by-command-name`
    and `psi-capf-slash-dedupes-builtin-extension-collision-by-command-name`
    (`components/emacs-ui/test/psi-capf-test.el`). Each seeds
    `:builtin-command-specs '(((:name . "resume")(:description . "resume the
    session")))` plus a colliding `resume` `:prompt-templates` /
    `:extension-command-names`, completes `/re`, and asserts exactly one
    `/resume` candidate (`seq-filter` length 1) — driving the backend-first
    `seq-uniq` merge in `psi-emacs--state-slash-command-specs` against a genuine
    built-in↔template/ext collision (the pre-existing
    `psi-capf-slash-dedupes-command-template-collision-by-command-name` seeds a
    template with NO built-in specs, and the desc-wins test seeds backend vs a
    stale *custom* `/help`, not a *template/extension* of the same name). The
    template arm additionally asserts backend-wins on collision (the built-in
    `"resume the session"` description survives). `bb emacs:check` 327/327 green
    (+2 deftests; was 325).

  Verification: TUI `psi.tui.app-input-selector-test` 15t/40a 0 failures;
  `bb emacs:check` 327/327; clj-kondo clean on the changed Clojure test file. No
  source/doc/changelog change: TS1 is a pure dedup-coverage lock over
  already-correct behaviour (AC6/AC7 were already satisfied — both UIs dedup via
  `distinct`/`seq-uniq`). Closes the pass-7 test-shaper finding that no test
  exercised a built-in name colliding with a template/extension on either UI.

## Test review follow-ups (review pass 8)

- test-shaper pass 8 — REVIEW_COMPLETE. Fresh independent review of the full
  205 test surface across all five slices and seven prior passes (TT1–TT7, TS1).
  Re-ran `bb clojure:test:unit` (all green; `commands-builtin-specs-test` +
  `builtin-commands-resolver-test` confirmed executing) and inspected the Emacs
  (`psi-capf-test.el`) + TUI (`app_input_selector_test.clj`) test files.
  Assessment against test-shaper {simple ∧ consistent ∧ robust ∧ economical}:
  - **single-concern / behavior-focused**: each lock targets one contract
    (projection-unchanged snapshots, TT3 entry well-formedness, hide-in-help
    projection, TT5 full help-block line order, resolver shape/order/content,
    TT7 full resolver name-order, TS1 dedup, TT4 no-backend⇒no-builtins,
    builtin-only refresh / I2).
  - **meaningful failures / seam-level**: exact+prefixed case-branch coherence
    read the *live* `commands/{exact,prefixed}-case-branches` defs (R1/R2);
    TT6 locks dual-kind exact-first *precedence* at the seam (bare has exact
    handler, `<args>` does not), not path-blind behaviour.
  - **economical / deterministic**: representative commands over case explosion;
    real session contexts (no mocks); symmetric TUI↔Emacs coverage; AC6 proven
    end-to-end (resolver bare-name set == `builtin-command-names`); no
    time/randomness/IO concerns.
  No new actionable gap found. The `as-slash-command` nil-name drop on the TUI
  builtin path is incidental robustness already exercised via the ext-cmds path
  and made unreachable from the real backend by TT3 (every entry has a non-blank
  string name) — not a task-contract behaviour warranting a dedicated test.

## Docs review (review-task-docs, pass 1)

Applied `review-task-docs` skill {accuracy ∧ completeness ∧ consistency} over
`README.md`, `doc/`, `CHANGELOG.md`.

Verified accurate:
- CHANGELOG `[Unreleased]` ▸ "Changed" entry: correct attribute name
  (`:psi.agent-session/builtin-command-specs`), correct previously-missing
  command set, correct `defcustom` repurposing. ✓
- `doc/architecture.md` EQL tips: attribute names, ns
  (`psi.agent-session.commands.builtin-specs`), `{:name :description}` shape,
  and projection list (`exact-command-handlers`, `prefixed-command-prefixes`,
  `format-help`, `builtin-commands-resolver`) all match source. ✓
- No stale slash-command surface references in `doc/emacs-ui.md`, `doc/tui.md`,
  `doc/graph-surface.md`, or `README.md`; the trimmed `defcustom` is not
  documented in user docs, so trimming needed no user-doc change beyond the
  (present) CHANGELOG mention. ✓
- `doc/graph-surface.md` relies on dynamic discovery (`resolver-syms` /
  attr-index), so it correctly needs no per-attribute enumeration of the two new
  attrs. ✓

Actionable finding (D1): `doc/architecture.md:121-122` overstates the Emacs side.
It asserts "Both the TUI and Emacs build their slash autocomplete by querying the
graph — **they hold no hardcoded command lists**". This is true for the TUI
(`shared/builtin-slash-commands` deleted) but **inaccurate for Emacs**: the
`psi-emacs-slash-command-specs` `defcustom` (psi-completion.el:19-20) is
deliberately retained with a non-empty default
`(("/skill:" . "Invoke a skill (append skill name)"))` and is merged into the
completion candidates (`psi-emacs--state-slash-command-specs`, backend-first
`seq-uniq`). The CHANGELOG already describes this nuance correctly ("`defcustom`
is now a user override/supplement (default trimmed to the Emacs-only `/skill:`
affordance)"); architecture.md should be reconciled to match — qualify the claim
to "no hardcoded built-in *command* lists" and note the Emacs `defcustom`
survives as a user override/supplement for the Emacs-only `/skill:` affordance,
so the doc does not contradict the CHANGELOG or the retained defcustom default.

## Follow-up execution — docs review pass 1 (D1)

Resolved D1. Edited the `doc/architecture.md` "slash-command surface offered to
UIs" bullet:
- Qualified the blanket "they hold no hardcoded command lists" → "they hold no
  hardcoded built-in *command* lists" (true for both UIs; the TUI deleted
  `shared/builtin-slash-commands`, and neither UI hardcodes the built-in command
  set any longer).
- Added a sub-bullet recording that the Emacs `psi-emacs-slash-command-specs`
  `defcustom` survives as a user override/supplement (default trimmed to the
  Emacs-only `/skill:` affordance, which is not a backend routing target),
  merged after the backend specs so backend built-in descriptions win on any
  name collision.

This reconciles architecture.md with both the retained `defcustom` default
(psi-completion.el:19-20) and the CHANGELOG wording ("`defcustom` ... default
trimmed to the Emacs-only `/skill:` affordance ... backend built-in descriptions
win on any name collision"). Pure doc-accuracy change; no source/test edits, so
no suite run required.

## Docs review — review-task-docs pass 2

Re-ran the review-task-docs checklist (README ∧ doc/ ∧ CHANGELOG) against the
implemented single-source command surface.

Verified accurate/complete:
- CHANGELOG `[Unreleased]` "Changed" entry — attribute name
  `:psi.agent-session/builtin-command-specs`, the previously-missing command
  list (`/reload-models /reload-prompts /reload-extension-installs /speed
  /effort /project-repl`), and the `defcustom` repurposing all match the code
  (builtin_specs.clj membership, resolver output, psi-completion.el:19-20
  trimmed default).
- `doc/architecture.md` "slash-command surface" bullet — ns
  `psi.agent-session.commands.builtin-specs`, both graph attributes
  (`builtin-command-specs` / `builtin-command-names`), the projection claim
  (exact/prefixed/format-help/resolver all derive from the table), and the
  Emacs `defcustom` override note all verified against source. D1 reconciliation
  holds.
- No stale references to the deleted `shared/builtin-slash-commands` or to a
  hardcoded Emacs built-in list in any user-facing doc.

New actionable finding (→ D2):
- `doc/tui.md` "In-session commands" reference list (lines 57–60) enumerates
  built-in commands but is **incomplete** relative to the now-authoritative
  `builtin-command-specs` table. Missing real, user-invokable built-ins:
  `/reload-models`, `/reload-extension-installs`, `/jobs [status ...]`,
  `/job <job-id>`, `/cancel-job <job-id>`. This task swept these commands into
  the single-sourced surface (they now autocomplete in TUI/Emacs), so the
  user-facing TUI command reference is stale/incomplete. The prior D1 pass only
  touched `doc/architecture.md` and did not check `doc/tui.md`'s command
  reference for completeness. (`/reload-models` is documented in
  `doc/custom-providers.md`, but absent from the canonical TUI command list.)

## 2026-06-01 — Docs review follow-up execution (D2)

Completed D2 (the sole newly-added unchecked item from the review-task-docs
pass 2). Grounded the additions against the authoritative single source
`commands/builtin_specs.clj` `builtin-command-specs` rather than re-deriving:
filtered to help-visible, user-invokable entries (skipping the three
`:hide-in-help? true` autocomplete-only entries `/?`, `/exit`, `/project-repl`,
the last already documented as its own sub-command block; `/login` already lives
in the OAuth section).

`doc/tui.md` "In-session commands" edits:
- Appended `/reload-models` and `/reload-extension-installs` to the reload line
  beside the already-present `/reload-prompts`.
- Added a new line `/jobs [status ...]` `/job <job-id>` `/cancel-job <job-id>`,
  usage hints lifted verbatim from the spec table `:usage` fields
  (`/jobs` `[status ...]`, `/job` `<job-id>`, `/cancel-job` `<job-id>`).

The TUI command reference now enumerates exactly the help-visible built-in
surface (matches `builtin-help-block`'s membership). Pure doc-accuracy change;
no source/test/changelog edit (the CHANGELOG `[Unreleased]` entry already covers
the user-visible behaviour; this only corrects a stale reference list). No
blocking reasons.

## Docs review — review-task-docs pass 3

Independent re-run of the review-task-docs checklist
{accuracy ∧ completeness ∧ consistency} over `README.md`, `doc/`, `CHANGELOG.md`,
grounded against the authoritative `commands/builtin_specs.clj`
`builtin-command-specs` table, the resolver, and `psi-completion.el:19-20`.

Verified accurate + complete (no new actionable findings):
- CHANGELOG `[Unreleased]` "Changed" entry — correct attribute
  `:psi.agent-session/builtin-command-specs`, correct previously-missing command
  set (`/reload-models /reload-prompts /reload-extension-installs /speed /effort
  /project-repl`), correct `defcustom` repurposing wording. ✓
- `doc/architecture.md` "slash-command surface" bullet — D1 reconciliation holds:
  "no hardcoded built-in *command* lists", both graph attrs, the single-source
  projection claim, ns `psi.agent-session.commands.builtin-specs`, and the Emacs
  `defcustom` user-override sub-bullet all match source. ✓
- `doc/tui.md` "In-session commands" — D2 complete: the reference block now
  enumerates exactly the help-visible built-in surface (matches
  `builtin-help-block` membership); usage hints (`/jobs [status ...]`,
  `/job <job-id>`, `/cancel-job <job-id>`, `/reload-models`,
  `/reload-extension-installs`) match the spec table; help-hidden `/?`/`/exit`
  correctly omitted, `/project-repl` documented as its own sub-command line. ✓
- `/thinking <off|minimal|low|medium|high|xhigh>` in tui.md is a more explicit
  enum than the spec table's `:usage "[level]"` — informative, not stale. ✓
- No stale references to the deleted `shared/builtin-slash-commands` in any
  user-facing doc (grep clean over `doc/` + `README.md`). ✓
- `doc/emacs-ui.md` correctly describes backend-sourced capf with no hardcoded
  built-in list; `doc/graph-surface.md` relies on dynamic attr-index discovery
  (no per-attr enumeration needed); `doc/custom-providers.md` `/reload-models`
  references are accurate; `README.md:105` correctly points to the doc set. ✓

D1 + D2 from prior passes verified resolved. No new actionable docs issues; no
follow-up steps added (criterion 3: nothing to add without duplicating the prior
D1/D2 coverage).

PASS_STATUS: REVIEW_COMPLETE

## 2026-06-01 — Code-shaper review (code-shaper), pass 1

λcode. simplicity ∧ consistency ∧ robustness → shape. Scope: the
single-source surface `commands/builtin_specs.clj` + its projections in
`commands.clj`, the `builtin-commands-resolver` (resolvers/extensions.clj), TUI
`autocomplete.clj`, and the spec-table tests (`commands_builtin_specs_test.clj`,
`builtin_commands_resolver_test.clj`). Verified `bspec` projections, dispatch
seams (R1/R2 load-time asserts), help-block derivation, and UI consumption.

Strong points (no action): leaf-ns single source is clean; projections are pure
fns of the keyset (name drift unrepresentable per Option B); the `prefixed-command`
matcher's trailing-space guard genuinely prevents `/job`↔`/jobs` shadowing, so the
"order not load-bearing" comment is sound; R1/R2 `assert`s make the hand-wired
`case`↔spec-table seam drift load-fatal; em-dash rendering is consistent between
`builtin-help-block` and the literal `/skill:name` line; clj-kondo clean on all
changed src.

New actionable (code-shaper):

- CS1 (robustness — help-line render formula duplicated 3×, tests are
  tautological). The line-rendering formula
  `(str "  " k " " (when usage (str usage " ")) "— " description)` lives in
  production at `builtin_specs.clj:110` (`builtin-help-block`) AND is copied
  verbatim as a local `line-for` fn in TWO tests
  (`commands_builtin_specs_test.clj:163-164` and `:203-204`,
  `format-help-block-line-order-test` + `builtin-help-block-hide-in-help-projection-test`).
  Because the tests re-derive the SAME formula they assert against, a regression
  in the production line format (spacing, em-dash, usage placement) changes both
  the rendered block and the test's expected string identically → the tests stay
  green while the user-visible help format silently drifts. This violates
  `robust ... shaped_by(code, formalisms) → enforceable(invariants)`: the
  rendering shape has no single enforceable home, and TT5/TT2's "locks the block
  to the single source" claim only holds for *membership/order*, not the *line
  shape*. Shape: extract the single-line renderer to one public fn in `bspec`
  (e.g. `(render-help-line k spec)`), have `builtin-help-block` map it over the
  filtered table, and have the tests call THAT fn (or assert a stable literal
  golden for ≥1 representative line) so a format change is caught rather than
  mirror-confirmed. Pure consolidation; no behaviour change.

- CS2 (simplicity — resolver exposes an unused `builtin-command-names` attribute).
  `builtin-commands-resolver` outputs both `:psi.agent-session/builtin-command-specs`
  AND `:psi.agent-session/builtin-command-names` (extensions.clj:160-164), but NO
  UI consumes the bare-name attribute: TUI `autocomplete.clj` reads
  `:builtin-command-specs` only, and Emacs (`psi-completion.el`,
  `psi-session-commands.el:257`) queries/consumes `:builtin-command-specs` only.
  The bare-name attribute is referenced solely by its own resolver test
  (`builtin_commands_resolver_test.clj`). plan.md's carried open question
  explicitly said "drop if unused by either UI after Slices 3–4" — it is unused.
  This is speculative public surface (`λ extend ... absent(default)`,
  single-responsibility): a second graph attribute + its derivation + its test
  exist with no consumer. Shape: either (a) drop
  `:psi.agent-session/builtin-command-names` from the resolver output and its
  dedicated test assertions (keep only `builtin-command-specs`), or (b) if
  retained deliberately for graph symmetry with `:psi.extension/command-names`,
  record that decision in plan.md's open question so the "drop if unused" gate is
  closed explicitly rather than left dangling. Prefer (a) unless symmetry is a
  stated requirement.

No duplication of prior R/TT/D notes: CS1 targets the render-formula *duplication
across production+tests* (orthogonal to TT2/TT5 which lock membership/order via
that same copied shape); CS2 targets the *unused exposed attribute* (no prior
pass flagged the dead surface — the plan deferred it as an open question).

PASS_STATUS: ACTIONABLE_FEEDBACK

---

## Follow-up execution — code-shaper pass 1 (CS1, CS2)

Executed the two newly-added unchecked code-shaper follow-ups.

### CS1 — single help-line renderer (robustness)
- `builtin_specs.clj`: extracted public `render-help-line [k spec]` — the sole
  renderer of `"  /name [usage ]— description"` (usage before the em-dash when
  present). `builtin-help-block` now maps it over the `:hide-in-help?`-filtered
  table; output is byte-identical (verified: full unit suites green, no golden
  drift).
- Tests: `format-help-block-line-order-test` and
  `builtin-help-block-hide-in-help-projection-test` now call
  `bspec/render-help-line` instead of their copied test-local `line-for` fns
  (the duplicated formula is gone). Added `render-help-line-format-test` with
  two *independent literal goldens* — `/model` (with-usage) and `/quit`
  (no-usage) — so a spacing/em-dash/usage-placement regression is caught by a
  stable anchor, not mirror-confirmed by the shared formula the other two tests
  re-derive. No behaviour change.

### CS2 — drop unused resolver attribute (simplicity) — option (a)
- Confirmed `:psi.agent-session/builtin-command-names` (resolver attribute) is
  consumed by NO UI (TUI `support.clj`/`autocomplete.clj`, Emacs
  `psi-session-commands.el` query `:builtin-command-specs` only). The
  `commands.clj:699` `bspec/builtin-command-names` *def* is the unrelated
  bare-name set used by `all-command-names`, not the resolver attribute.
- `resolvers/extensions.clj`: dropped `:psi.agent-session/builtin-command-names`
  from `builtin-commands-resolver`'s `::pco/output` and return map — resolver
  now exposes `:builtin-command-specs` only.
- `builtin_commands_resolver_test.clj`: removed the bare-name query/assertions
  (shape-test query + the redundant "bare-name vector mirrors spec names" block
  + the graph-discovery resolvability assertion) and reworded the ns docstring
  (`/ -names` → single attribute). The end-to-end membership lock
  (`…-exposes-full-spec-table-membership-test`) still derives its name set from
  `:builtin-command-specs`, so AC6 coverage is preserved.
- `doc/architecture.md`: trimmed the stale `[:psi.agent-session/builtin-command-names]`
  "symmetric" mention from the EQL Introspection Tips slash-command bullet.

### Verification
- `clojure -M:test --focus psi.agent-session.commands-builtin-specs-test
  --focus psi.agent-session.builtin-commands-resolver-test`: 15 tests, 218
  assertions, 0 failures.
- `psi.agent-session.commands-test`: 51 tests, 206 assertions, 0 failures.
- clj-kondo over the four changed clj files: 0 errors, 0 warnings.
- clj-paren-repair: no changes needed (well-formed).

Both items checked in steps.md. No remaining unchecked CS items.

### Incidental fix — stale TUI command-refresh stub (Slice-3 regression caught now)
While running the full unit suite to verify CS1/CS2, found a PRE-EXISTING red
test (confirmed failing on HEAD with my changes stashed):
`psi.tui.app-update-runtime-test/explicit-refresh-boundary-refreshes-extension-command-names-test`.
Its stub `query-fn` guarded `(= [:psi.extension/command-names] query)`, but
Slice 3 widened `support/command-refresh-query` to
`[:psi.extension/command-names :psi.agent-session/builtin-command-specs]`, so the
exact-match guard returned nil and the assertion failed. Slice 3 added a NEW
refresh test in the autocomplete/selector test file but did not update this older
runtime test. Minimal fix: relaxed the stub guard to
`(some #{:psi.extension/command-names} query)` (respond when the widened query
asks for extension command names). Test-only change; full `bb clojure:test:unit`
now green.

## Reviews

### 2026-06-01 — Code-shaper review (code-shaper), pass 2

Scope: simplicity ∧ consistency ∧ robustness of the shipped code (`builtin_specs.clj`,
`commands.clj` dispatch/case seams, `resolvers/extensions.clj` resolver, TUI
`autocomplete.clj`/`support.clj`, Emacs `psi-completion.el`). Suite + lint
re-verified green at review time (commands-builtin-specs + builtin-commands-resolver
+ commands-test: 66 tests / 424 assertions / 0 failures; clj-kondo: 0/0 over the
four changed clj files).

Overall the implementation is well-shaped: the single keyed `builtin-command-specs`
table is a genuine leaf ns (only `clojure.string`), every name surface is a pure
projection of its keys, CS1 already collapsed the help-line formula into one
`render-help-line`, and CS2 dropped the unused `:builtin-command-names` resolver
attribute. The two `case` seams (`prefixed-case-branches`, `exact-case-branches`)
are correctly locked by **load-time asserts** in `commands.clj`, honouring
`unreachable > forbidden` for the handler-wiring residual.

Actionable (1):

- CS3 (robustness, `unreachable > forbidden` consistency) — the spec table's own
  **per-entry shape** (`:kinds` non-empty ⊆ #{:exact :prefixed}; `:exact ⇒ :handler`;
  `:description` non-blank) — which is the *primary* invariant of the single source
  the projections silently assume — is enforced **only by runtime test TT3**
  (`builtin-command-specs-well-formed-test`), i.e. `forbidden`, not `unreachable`.
  This is inconsistent with (a) the project ethos (`impossible_invalid_states`,
  `shaped_by(code, formalisms) → enforceable(invariants)`), and (b) the sibling
  idiom already in this very namespace cluster: `commands.clj` guards both `case`
  seams with **load-time `assert`s** so drift is caught at namespace load. The
  weaker invariant (case↔table seam) is structurally guarded; the stronger one
  (the table entries themselves) is not. TT3's own note records that "a short
  malli schema over the entry-value or explicit doseq/every? assertions both
  work" and chose the test. Tighten to load-time: add a malli entry schema in
  `builtin_specs.clj` (malli is the project validation lib, already used in
  `dispatch_schema.clj`) and a load-time `(assert (m/validate ...))` /
  `(m/coerce ...)` over `builtin-command-specs`, so a malformed entry fails at
  load (`unreachable`) rather than only when a test runs. TT3 can then shrink to
  asserting the schema rejects representative malformations, or be retired.

Non-actionable observations (recorded, no follow-up):

- `strip-slash` uses `(str/replace s #"^/" "")` — a regex for a single-leading-char
  strip where `(cond-> s (str/starts-with? s "/") (subs 1))` is cheaper/plainer.
  Micro; correct and locally clear; not worth the churn. Noted only.
- TUI `slash-candidates` derives `builtins`/`ext-cmds` via `as-slash-command`
  (tolerant of bare-or-`/`-prefixed) but `templates`/`skills` via raw `(str "/" name)`.
  The new built-in code is *consistent with* its nearest neighbour (`ext-cmds`);
  the templates/skills idiom predates this task and is out of its remit.

### 2026-06-01 — CS3 execution (code-shaper pass-2 follow-up)

Tightened the `builtin-command-specs` per-entry-shape invariant from *forbidden*
(runtime test TT3) to *unreachable* (load-time), matching `unreachable >
forbidden` and the sibling load-time `case`-seam asserts in `commands.clj`.

- `builtin_specs.clj`: added `(:require [malli.core :as m])` and a public
  `entry-schema`. `:kinds` is a non-empty `[:set [:enum :exact :prefixed]]`;
  `:description` a non-blank `:string`; `:usage` optional `:string`;
  `:hide-in-help?` optional `:boolean`. The `:exact ⇒ :handler` cross-key
  constraint is an entry-level `[:fn …]` (a malli `:map` cannot make one key's
  requiredness depend on another's value). `:handler` is optional `:keyword` at
  the map level, required-when-`:exact` via the `:fn`. Added a load-time
  `(assert (every? #(m/validate entry-schema (val %)) builtin-command-specs) …)`
  whose message `m/explain`s the offending entries, so a malformed entry fails
  at namespace load rather than only when a test runs. Both `case`-seam asserts
  in `commands.clj` left unchanged.
- `commands_builtin_specs_test.clj`: shrank TT3 from per-entry `doseq` runtime
  checks (now covered by the load-time guard) to
  `builtin-command-specs-entry-schema-rejects-malformations-test`, which locks
  that the schema *rejects* the representative malformations the projections
  silently assume away — empty `:kinds`, `:exact`-without-`:handler`,
  out-of-enum `:kinds`, blank `:description` — and *accepts* a well-formed
  entry (plus a guard that the shipped table validates).
- Verification: namespace loads clean (shipped table passes the load-time
  guard); schema rejects all four malformations and accepts a valid entry
  (REPL-checked). `clojure -M:test` focus on commands-builtin-specs +
  builtin-commands-resolver + commands-test → 66 tests / 284 assertions / 0
  failures. clj-kondo over both changed files → 0/0.

## Code-shaper review (code-shaper pass 3)

Reviewed 205's changed surfaces against `simple ∧ consistent ∧ robust`.

Backend (`builtin_specs.clj`, `commands.clj`, resolver), TUI
(`autocomplete.clj`, `support.clj`, `shared.clj` disposal) are well-shaped:
single keyed source, derived projections, load-time `assert`/malli seams
(`unreachable > forbidden`), zero lint. Prior passes (CS1–CS3, CS-incidental)
hold. No new backend/TUI findings.

One Emacs finding (CS4): the two slash-completion token constructors that this
task extended with a `:builtins` segment are **not** structurally identical
despite the comments on both asserting they are ("identical to the inline
token …", "normalized identically"). They normalize via **different** helpers:

- `psi-session-commands.el` `psi-emacs--slash-completion-token` /
  `psi-emacs--normalize-builtin-command-specs`: `psi-emacs--trim-optional-input`
  (trims via `(format "%s" …)`, nil-on-blank) + `psi-emacs--alist-get-any`.
- `psi-events.el` inline `next-token`: `psi-emacs--non-blank-text` (returns the
  **untrimmed** string, nil for non-strings) + `psi-emacs--event-data-get`.

So for a `:name`/`:description` with surrounding whitespace, or a non-string
value, the two constructors emit **different** token values for equal backend
data → spurious "changed" detection (false-positive refresh) or a real change
normalized away on one side. The contract P2 relies on ("equal data yields
equal tokens, structurally identical constructors") is asserted only by comment,
not enforced — the `forbidden`-but-not-`unreachable` shape this task otherwise
eliminates. Same class as CS1's render-formula tautology, in Emacs. The
divergence spans all three segments (`:commands`/`:builtins`/`:templates`), but
the `:builtins` segment is the one 205 introduced into both sites, so the new
duplication is in-scope here.

Remedy: extract one shared token-segment builder (single normalization of a
`(name description)` pair list) used by **both** call sites, parallel to CS1's
`render-help-line`, so equal data provably yields equal tokens. Note the two
sites read different alist shapes (query-frame `:keyword` keys vs event-data
`(:k k)` key-lists), so the shared helper takes already-extracted pair lists or
a key-list arg; the normalization itself must be the single shared fn.

## CS4 follow-up executed (code-shaper pass 3)

Unified the two slash-completion token constructors onto a single shared
normalization, so "equal backend data → `equal` tokens" is structural rather
than asserted by comment.

- Added two pure helpers to `psi-globals.el` (the base module both call sites
  already `require`, so no new circular `declare-function`s):
  - `psi-emacs--slash-completion-normalize-text` — the single canonical scalar
    normalizer (`(string-trim (format "%s" (or value "")))` → nil-on-blank).
    This is the trimming/coercing semantics of the former
    `psi-emacs--trim-optional-input`, chosen as canonical because the stored
    token (apply path) already used it.
  - `psi-emacs--slash-completion-pair` — builds a canonical `(name description)`
    pair from already-extracted raw values, normalizing both through the scalar
    fn.
- Apply path (`psi-session-commands.el`): replaced the bespoke
  `:builtins`/`:templates` mappers with one `psi-emacs--alist-pair-segment`
  (extracts via `psi-emacs--alist-get-any`, normalizes via the shared pair).
  `psi-emacs--slash-completion-token` uses it for both `:builtins` and
  `:templates`. The former `psi-emacs--normalize-builtin-command-specs` had no
  remaining callers after this and was removed (dead delegate).
- Event-data path (`psi-events.el`): added `psi-emacs--event-pair-segment`
  (extracts via `psi-emacs--event-data-get`, normalizes via the shared pair).
  The inline `next-token` now calls it for `:builtins` and `:templates`; the
  former `psi-emacs--non-blank-text`-based mappers are gone (the helper remains,
  still used by assistant/error text paths). Extraction stays per-site (the two
  alist shapes differ); only the normalization is shared, per the CS4 remedy.
- The `:commands` segment was already identical on both sides
  (`(string-trim (format "%s" …))`) so it is left in place.

Tests (`test/psi-capf-test.el`):
- `psi-slash-completion-token-constructors-agree-on-padded-edge-data` — seeds
  the same logical built-in specs (padded `:name`, non-string `:description`)
  through the apply-path constructor and the event-data segment builder and
  asserts the tokens are `equal` (and trim/coerce to the expected canonical
  shape). Would have failed pre-CS4: the old event path used the *untrimmed*
  `non-blank-text` and dropped non-string values.
- `psi-session-updated-no-false-positive-refresh-on-padded-equal-data` — stores
  a token, then sends a `session/updated` whose built-in specs differ only by
  surrounding whitespace; asserts the cached token is untouched (no spurious
  refresh).

`bb emacs:check` green: 329 tests / 329 expected / 0 unexpected; byte-compile
clean (0 warnings).

## Code-shaper review (code-shaper pass 4)

Re-reviewed 205's changed surfaces against `simple ∧ consistent ∧ robust`.

Backend (`builtin_specs.clj`, `commands.clj`, resolver) and TUI
(`autocomplete.clj`, `support.clj`) remain well-shaped: single keyed source,
derived projections, load-time `assert`/malli seams (`unreachable > forbidden`),
zero lint. Prior passes (CS1–CS4) hold and are not re-litigated. CS4's two
pair-segment builders (`psi-emacs--alist-pair-segment` /
`psi-emacs--event-pair-segment`) differ only in the irreducible getter
(`alist-get-any` vs `event-data-get` — the two source shapes genuinely differ)
and both route through the shared `psi-emacs--slash-completion-pair` normalizer;
that residual is correct, not a finding.

One new Emacs finding (CS5, consistency ∧ robustness — open-coded
slash-prefix idiom duplicated). `psi-emacs--state-slash-command-specs`
(`psi-completion.el`) open-codes the ensure-leading-slash idiom
`(if (string-prefix-p "/" x) x (concat "/" x))` **twice** in one function:
once in the `backend-specs` block (line 109 — the path 205 *added* to consume
backend built-in specs) and once in the pre-existing `ext-specs` block
(line 118). The TUI consumer of the same backend surface extracted exactly this
normalization into a named `as-slash-command` helper
(`tui/app/autocomplete.clj`) and reuses it for both `builtins` and `ext-cmds`;
the Emacs consumer has no equivalent and repeats the shape inline. Because 205
introduced the second occurrence (backend-specs) adjacent to the existing one,
the duplication is in-scope here.

Why it matters (`consistent ∧ robust`): the slash-prefix rule now lives in two
literal sites in one function (and a third bare-concat form at
`psi-session-commands.el:363`), so a change to the prefixing rule (e.g. trimming
empties, normalizing `//`) must be made in lock-step or the backend-command and
extension-command surfaces silently diverge — the same class of open-coded-shape
duplication CS1 and CS4 eliminated, in Emacs. It is a `forbidden`-not-`unreachable`
shape the rest of this task removes.

Remedy: extract one shared `psi-emacs--ensure-slash-prefix` helper (parallel to
the TUI `as-slash-command`; place in `psi-globals.el` next to
`psi-emacs--slash-completion-normalize-text` so both consumers `require` it
without new `declare-function`s) and call it from both the `backend-specs` and
`ext-specs` blocks. Folding the `psi-session-commands.el:363` `(concat "/" name)`
template form onto the same helper is a welcome consistency win if low-risk.
`bb emacs:check` must stay green.

No duplication of prior notes: CS1 = backend help-line render formula; CS2 =
unused resolver attribute; CS3 = entry-shape load-time guard; CS4 = token-
constructor normalization. CS5 is a distinct surface (Emacs slash-prefix
idiom in `psi-completion.el`'s merged-specs builder), untouched by CS1–CS4.

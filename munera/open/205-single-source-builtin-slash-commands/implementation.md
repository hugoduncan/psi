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

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

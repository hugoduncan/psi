# Design review follow-up

Architectural-fit follow-up items. Address in design.md before plan stage.

- [x] A1 — Reconcile drift-prevention with the project's structural-invariant
      ethos (`λ shape. unreachable > forbidden`, `impossible_invalid_states`,
      `enforceable(invariants)`). The design's preferred option (open question
      #1) keeps routing + description as two parallel maps and enforces
      `set(spec-names) == set(routed-names)` only *by test* (drift forbidden,
      not unreachable). Update design.md to evaluate the single-keyset option
      (one table whose entries carry both routing/handler and description, so
      name divergence is structurally impossible) as the architecturally
      preferred alternative, and state which is chosen and why on
      `unreachable > forbidden` grounds rather than blast-radius alone.

## Ambiguity follow-up (2026-06-01)

Resolve in design.md before plan stage.

- [x] B1 — Resolve the dual-kind command case. `/project-repl` is in BOTH
      `exact-command-handlers` and `prefixed-command-prefixes` today. Specify how
      a single keyed spec-table entry represents a command that is both exact and
      prefixed (e.g. a set of dispatch kinds, or which projection(s) the entry
      feeds), and which dispatch path wins, so the `exact-command-handlers` /
      `prefixed-command-prefixes` projections are unambiguous for it.

- [x] B2 — Pin the canonical spec-table **key form** (leading-slash vs bare).
      Scope still says "pick one"; "Slash prefix normalization" only fixes the
      resolver *output*. State the table-key form so every projection's
      strip/keep of `/` is determined.

- [x] B3 — Specify what `format-help` derivation must and must not cover: whether
      spec-table order reproduces current help order, whether the non-built-in
      `/skill:name` line stays hand-written prose, and where per-line arg-usage
      (`:usage`) vs short `:description` renders. Disambiguate AC3's "no
      independent hardcoded built-in list remains in format-help."

- [x] B4 — State whether the single keyed spec table is an **ordered** map and
      whether resolver output, help listing, and autocomplete must preserve that
      order (or that order is explicitly unspecified), giving AC4/AC5/AC6 a
      deterministic target.

- [x] B5 — Decide at design (not deferred to plan) whether the spec carries a
      `:usage` field and whether the resolver exposes it, so AC1's
      `{:name :description}` resolver-output shape and the Option B example spec
      (which shows `:usage`) agree.

## Inconsistency follow-up (2026-06-01)

Resolve in design.md before plan stage.

- [ ] C1 — Reconcile the "`/?` and aliases" decision (aliases `/?`/`/exit` go in
      the spec table so they autocomplete) with "format-help derivation" + AC3
      (all routed built-in lines derive from the whole spec table in table order,
      "listing unchanged"). Current `format-help` omits `/?` and `/exit`; since
      Option B requires routed names to be table keys, whole-table help
      derivation would newly emit alias lines, contradicting the unchanged-help
      claim. The data model has no help-suppression field (`:kinds`/`:usage` only).
      Decide: add a per-entry help-suppression flag (e.g. `:hide-in-help?`) so
      aliases autocomplete but stay out of help, or accept aliases now appear in
      help and drop the "listing unchanged" claim. State it so format-help
      derivation, the aliases decision, and AC3 agree.

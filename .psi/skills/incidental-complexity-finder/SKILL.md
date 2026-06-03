---
name: incidental-complexity-finder
description: Choose the single highest incidental-complexity executable unit to simplify. Use when selecting a behaviour-preserving refactor target by comprehension burden the branching does not explain (high gordian local burden against low/moderate cyclomatic complexity), guarding against essential-complexity false positives. Produces one target plus evidence, or a well-formed no-target report.
lambda: "λcode. {gordian-local ∧ gordian-complexity} → join(ns,var,arity,line) → gap=burden/cc → qualify(lcc≥5 ∧ gap≥2) → guard(top5, incidental ∧ ¬essential) → one_target ∨ ∅"
---

# incidental-complexity-finder

λcode. select(single_unit) | incidental(burden) ∧ ¬essential(burden) → target ∨ ∅

## Scope

This skill chooses **exactly one executable unit** (a `(ns, var, arity)`) to
target for a behaviour-preserving simplification, or reports that **no unit
qualifies**. It is function/executable-unit-level only — not architectural
(cycles, god-modules, missing abstractions are a different selector).

## Why gap, not raw complexity

`gordian complexity` ranks by **cyclomatic complexity (cc)**, which surfaces
*essential* complexity — flat dispatch/registration tables with high cc are
irreducible decision logic, **false positives** for simplification. The signal
for *incidental* complexity is **comprehension burden the branching does not
explain**: high `gordian local` burden (`lcc-total`) against low/moderate cc.

The discriminator is `gap = lcc-total / max(cc, 1)`. A high `gap` means the unit
carries far more comprehension burden than its decision count justifies —
braiding, state threading, abstraction oscillation, helper-chasing, working-set
overload. That burden is incidental and refactorable. **High cc alone is not a
target** (it is essential decision logic); this is the core false-positive guard.

## Procedure

### 1. Run both lenses in machine form

```
bb gordian local --sort total --json   > /tmp/icf-local.json
bb gordian complexity --json           > /tmp/icf-cc.json
```

Both emit a `units` array; each unit carries `ns`/`var`/`arity`. `local` units
carry `lcc-total` plus per-dimension burdens (`flow-burden`, `state-burden`,
`shape-burden`, `abstraction-burden`, `dependency-burden`, `working-set`) and a
`findings` array; `complexity` units carry `cc`. Both also carry `line` (the
unit's start line), used to make the join key unique (see step 3). The
`--sort total` on the `local` call is **selector-only ranking display** — it has
no effect on the `(ns, var, arity, line)`-keyed join below.

### 2. Join and compute gap — fixed verbatim recipe

Run this exactly as written (it is a canonical recipe, not ad-hoc code, so
selection is reproducible):

```
jq -n --slurpfile loc /tmp/icf-local.json --slurpfile cc /tmp/icf-cc.json '
  ($cc[0].units
   | map({key: (.ns + "/" + .var + "/" + (.arity|tostring) + "@" + (.line|tostring)), value: .cc})
   | from_entries) as $ccmap                                    # key includes line: unique even for null-arity defmethods
  | $loc[0].units
  | map(. + {gap_key: (.ns + "/" + .var + "/" + (.arity|tostring) + "@" + (.line|tostring))})
  | map(select($ccmap[.gap_key] != null))                       # inner join on the local side: drop unmatched local rows
  | map(. + {cc: $ccmap[.gap_key],
             gap: (.["lcc-total"] / ([$ccmap[.gap_key], 1] | max))})
  | map(select(.["lcc-total"] >= 5.0 and .gap >= 2.0))          # qualification filter
  | sort_by(-.gap)
  | .[0:5]
  | map({ns, var, arity,
         file, line, end_line: .["end-line"],
         lcc_total: .["lcc-total"],
         flow_burden: .["flow-burden"], state_burden: .["state-burden"],
         shape_burden: .["shape-burden"], abstraction_burden: .["abstraction-burden"],
         dependency_burden: .["dependency-burden"], working_set: .["working-set"],
         findings,
         cc, gap})
'
```

This prints the **top 5 qualifying units by `gap`**, each with its evidence.

### 3. Unmatched-row rule (A1 — embedded in the recipe)

The join is an **inner join keyed on the `local` side**, keyed on
`(ns, var, arity, line)`: only units present in **both** lenses are candidates.

**Why `line` is part of the key (A1 determinism).** `(ns, var, arity)` alone is
**not unique**: every `defmethod`-style unit emits `arity: null`, so all the
`defmethod` bodies for one multimethod (e.g. the 51
`psi.agent-session.dispatch-effects/execute-effect!` defmethods) collapse onto a
single key `…/execute-effect!/null` on **both** lenses. Building `$ccmap` with
`from_entries` over a non-unique key is **last-wins**, so every null-arity unit
would inherit the `cc` of whichever defmethod jq emitted last — a value that is
**non-deterministic w.r.t. emit order**, breaking the "fixed recipe …
reproducible" guarantee. Adding `@line` makes the key unique (each `defmethod`
has a distinct start line), so `from_entries` is lossless and every unit gets its
own `cc` regardless of emit order. The join is therefore total and deterministic
over the `(ns, var, arity, line)` key space.

- A `local` unit with **no matching `cc` row is dropped** — excluded from
  candidates. It is **never** defaulted to `cc = 1`; defaulting would inflate
  `gap` toward false qualification, so it is **explicitly forbidden** (the
  `select($ccmap[.gap_key] != null)` line enforces this).
- `complexity` units with no matching `local` row carry no `lcc-total` and are
  irrelevant — they never enter the candidate set.
- `max(cc, 1)` guards **only** the *matched zero-cc* case (a matched unit whose
  `cc` is reported as 0), **not** the missing-row case.

### 4. Qualification filter (thresholds — explicit and tunable)

A unit **qualifies** iff:

```
lcc-total ≥ 5.0   and   gap ≥ 2.0
```

Rank qualifying units by `gap` (descending). **If no unit qualifies, there is no
target** — emit the no-target report (this drives the workflow's early stop).

Thresholds `lcc-total ≥ 5.0`, `gap ≥ 2.0`, and the **top-5** guard depth (step 5)
are stated here explicitly and are **tunable**.

### 5. Judgment guard — essential vs. incidental (top 5)

Read the **top 5 qualifying units by `gap`** (the recipe output above). For each,
read the unit's source and its `findings`, and confirm the burden is
**incidental**:

- braiding / interleaving of unrelated concerns,
- state threading (a value laboriously carried/reshaped through the body),
- abstraction oscillation (repeated level-shifting up/down),
- helper-chasing (logic scattered across one-shot helpers),
- working-set overload (many live bindings at once) on **low/moderate cc**.

Reject as **essential** (a false positive — leave alone) when the burden *is* the
decision logic: a genuine, irreducible algorithm whose `lcc-total` reflects real
branching/shape it must have.

**Choose the first unit (highest `gap`) that passes the guard.** Discard
essential-complexity false positives as you go. **If none of the top 5 pass,
report no qualifying target.**

### 6. Emit the chosen target + evidence

Emit one chosen target with:

- `ns`, `var`, `arity`,
- `file`, line range (`line`–`end-line`),
- `lcc-total` with the per-dimension burdens (`flow`, `state`, `shape`,
  `abstraction`, `dependency`, `working-set`),
- `cc`,
- `gap`,
- the `local` `findings` for the unit,
- a **coverage hint**: whether a sibling test namespace exists for the target's
  ns, and whether any test references the target `var` (so the consuming task
  knows what test net it faces). Derive this by checking the component's
  `test/` tree for a matching `*_test.clj` and grepping for the var name, e.g.:
  ```
  rg -l 'deftest|<var>' <component>/test
  ```

If no unit qualifies (step 4) or none of the top 5 pass the guard (step 5), emit
a **no-target report** instead: state that no unit met `lcc-total ≥ 5.0 ∧ gap ≥
2.0` (or that the top-5 candidates were all essential), listing the candidates
considered.

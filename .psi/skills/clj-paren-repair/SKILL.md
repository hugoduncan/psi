---
name: clj-paren-repair
description: Recover structurally broken Clojure/EDN files by rebalancing delimiters with minimal edits. Use this as soon as manual editing causes structural syntax issues, EOF/read errors, unmatched parens, or cascading syntax damage.
lambda: " λbroken_form. broken_form ∈ {structural_syntax_damage ∨ unbalanced_parens} ∧ language(broken_form) ∈ {clojure ∨ edn} → recover(broken_form) → balanced_forms(minimal_change)"
---

# Clj Paren Repair

Use this skill to recover a structurally broken Clojure or EDN file when hand-editing has become unreliable.

## When to use

Trigger this skill when you see any of the following:
- `EOF while reading`
- unmatched parentheses, brackets, or braces
- syntax drift after repeated manual edits
- a Clojure/EDN file that no longer reads or compiles cleanly
- test files or source files that have become risky to repair by hand

Use it early once the problem shifts from a local typo to structural syntax damage.

## What it does

λ. bash-command("clj-paren-repair")
  ∧ cli-for(recover(clojure ∨ edn, structural_syntax_damage))
  ∧ supports(
      invocation("clj-paren-repair path/to/file.clj"),
      invocation("clj-paren-repair src/core.clj src/util.clj test/core_test.clj"),
      invocation("clj-paren-repair --help")
    )
  ∧ invoke("clj-paren-repair", target-files)
  → repair(target-files)
  → balanced-forms(minimal-change)
  ∧ accepts(file*)

## Examples

- `clj-paren-repair path/to/file.clj`
- `clj-paren-repair src/core.clj src/util.clj test/core_test.clj`
- `clj-paren-repair --help`

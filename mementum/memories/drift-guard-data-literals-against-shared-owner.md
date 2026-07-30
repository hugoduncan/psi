🔁 drift-guard-data-literals-against-shared-owner

When a rule (e.g. "how a model becomes codex": `:api`/`:base-url`/native-capability triple) is factored into one runtime owner (`with-openai-codex-transport` + shared constants in `structured_output.clj`) but sibling data-authored catalog literals still restate the same values inline, prefer a drift-guard test over rewriting the authored data — especially when rewriting would touch many pre-existing sibling entries and exceed a task's frozen scope.

Two hard-won rules (task 240, 7 code-shaper rounds):

1. **Selector independence**: a drift-guard must select its population by an identity *independent of the fields it asserts*. Selecting codex entries by `:api == codex-api` then asserting `:api == codex-api` is tautological and catches nothing. Select by a disjunction (`:api OR :base-url matches`) so a drifted entry is still picked up and flagged.

2. **Compile-time-literal irreducibility**: Clojure `case` needs literal keys, so a `case` branch can't reference a shared constant. Don't broaden scope to `condp`; document the deliberate restatement inline and add a test pinning the literal to the constant so a constant retarget fails loudly and points back to the `case`.

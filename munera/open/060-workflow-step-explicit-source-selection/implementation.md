# 060 — Implementation notes

This task is Phase 1 extracted from umbrella task 059.

Key constraints:
- use `:session` immediately, not a temporary `:bind` syntax
- in this task, the recognized source-selection fields are `:input` and `:reference`
- prior-step references only in the first cut, validated by definition order
- compile into existing canonical `:input-bindings`
- keep prompt rendering semantics unchanged
- do not yet broaden scope into transcript projection, message preloading, or full session shaping

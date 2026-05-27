# Plan

## Approach

Refine the design only. Do not implement code.

1. Resolve the remaining ambiguity around deterministic skill-source precedence and collision handling by aligning the design with current `discover-skills` behavior and the chosen built-in packaging model.
2. Create a minimal execution checklist in `steps.md` for any later implementation task shaping, but do not add implementation work here.
3. Record the design-only follow-up pass in `implementation.md`.

## Decisions to lock

- Discovery may remain source-ordered, but collision selection must no longer be described only as implicit first-discovered wins once built-in skills are added.
- The design must define explicit precedence across all intended sources: built-in, user-global, project, and `:extra-paths`.
- `:extra-paths` must be specified as the highest-precedence explicit override input because they are caller-supplied and already bypass `:disabled` loading.
- Diagnostics should report both winner and shadowed candidate so implementation does not preserve only legacy loser-only collision wording.

## Risks

- Over-prescribing implementation mechanics instead of design intent.
- Leaving `:extra-paths` under-specified, which would force a later implementer to re-derive precedence from current code.

## Out of scope

- Changing runtime code.
- Executing `steps.md` implementation items.
- Choosing the exact cache directory path or concrete diagnostic data structure beyond the needed semantic contract.

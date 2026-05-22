# 173 skill registry ordering removal

## Intent

Decide whether `skill-registry` should stop treating registration order as part of its behavior, and if so, remove that ordering from the contract with the smallest coherent change.

This task exists because the registry-unification audit currently treats skill ordering as a meaningful difference from `root-registry`, but recent review suggests that what is actually needed may be deterministic skill listing rather than preserved registration order.

## Context

Current audit state:

- task `164` records `skill-registry` as an ordered collection with first-registration-wins semantics
- task `165` classifies `skill-registry` as an adapter-backed rather than direct `root-registry` adopter partly because of its ordered-collection behavior
- recent review suggests the stronger requirement may only be:
  - exact-name lookup
  - duplicate-ignore behavior
  - behaviorally meaningful `:added?` / `:changed?`
  - deterministic listing for prompt/display/projection surfaces

The key open question is whether any real caller requires preserved registration order, or whether order is merely carried through today because skills live in a vector.

## Scope

This task should determine and, if justified, remove registration-order semantics from `skill-registry`.

It includes:

- auditing skill-order assumptions in callers, tests, and prompt/display surfaces
- distinguishing required deterministic ordering from accidental registration-order preservation
- defining the replacement contract if registration order is dropped
- updating `skill-registry` behavior and proof surfaces if removal is justified
- updating task `164` conclusions if the skill-order mismatch is reduced or eliminated

It does not include:

- migrating `skill-registry` onto `root-registry`
- redesigning duplicate-ignore semantics
- redesigning `:added?` / `:changed?` behavior
- broader registry-unification work beyond the skill-ordering question

## Problem statement

`skill-registry` currently appears to preserve first-registration order.

That matters only if some caller truly depends on that specific order.

If callers only require:

- a deterministic list of skills
- stable name lookup
- duplicate no-op behavior
- prompt/display coherence

then registration-order preservation is an unnecessary contract burden that makes `skill-registry` look less compatible with shared registry substrates than it really is.

## Desired outcome

This task should leave behind a clear answer to:

1. whether preserved registration order is actually required anywhere
2. what deterministic ordering, if any, should replace it
3. whether `skill-registry` should be treated as less order-sensitive in task `164`

Current refinement direction:

- the caller audit should treat "stable prompt/discovery listing" and "preserved registration order" as different claims
- if no caller truly depends on insertion order, the registry contract should shrink to deterministic exact-name lookup plus deterministic listing
- the smallest coherent replacement is a canonical name-sorted read surface while preserving duplicate-ignore and `:added?` / `:changed?`

If ordering removal is justified, the resulting behavior should be explicit and test-backed.

## Candidate replacement directions

Possible outcomes include:

### A. Keep current behavior

If a real caller or user-visible contract requires first-registration order, preserve it and record that requirement explicitly.

### B. Drop registration order but keep deterministic order

If callers only need predictability, replace registration-order semantics with an explicit deterministic order.

Refined preferred candidate:

- `all-skills` returns skills in canonical `:name` order
- `skill-names` follows that same canonical order
- `find-skill` remains exact-name lookup
- duplicate registration remains first-write-wins by identity, but the visible listing order is no longer insertion order

This keeps prompt/discovery surfaces deterministic without requiring the registry contract to remember registration sequence.

### C. Make ordering explicitly non-semantic at the registry layer

If ordering belongs only to presentation/prompt projection layers, make `skill-registry` itself order-insensitive and let higher layers sort explicitly when rendering.

Current audit evidence makes B more likely than C, because higher callers already consume `all-skills` / `skill-names` directly and would otherwise each need to re-establish the same deterministic sort.

## Constraints

- Do not assume that current vector order is required just because it is preserved today.
- Prefer the smallest change that removes accidental contract burden.
- Preserve duplicate-ignore behavior unless separately justified otherwise.
- Preserve `:added?` / `:changed?` behavior; higher orchestration depends on it.
- Keep prompt and display surfaces deterministic even if registration order is removed.

## Acceptance

This task is complete when:

- the codebase has an evidence-backed answer about whether skill registration order is required
- if not required, `skill-registry` no longer treats registration order as meaningful behavior
- affected tests and prompt/display surfaces are updated to prove deterministic canonical listing rather than insertion-order preservation
- task `164` is updated to reflect the new conclusion about skill-order sensitivity

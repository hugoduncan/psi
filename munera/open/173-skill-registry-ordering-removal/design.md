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


## Review and follow-up surfaces

This task uses the standard task artifacts as follows:

- `design.md` defines the intended behavior and decision branches.
- `plan.md` defines the implementation approach for both the remove-order and keep-order outcomes.
- `design-steps.md` is the actionable surface for review follow-up items that refine task design, including ambiguity-review and inconsistency-review follow-ups.
- `steps.md` is the later implementation checklist and must not be used for design-review follow-up execution.
- `implementation.md` is the append-only review, audit, decision, and blocking-note log.

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
- the smallest coherent replacement is a canonical name-sorted registry output/read surface while preserving duplicate-ignore and `:added?` / `:changed?`
- if the audit finds a real insertion-order dependency, no ordering-removal code change should be made; instead, the dependency should be documented, test-backed, and reflected in task `164` as a confirmed requirement

If ordering removal is justified, the resulting behavior should be explicit and test-backed.

## Candidate replacement directions

Possible outcomes include:

### A. Keep current behavior

If a real caller or user-visible contract requires first-registration order, preserve it and record that requirement explicitly.

### B. Drop registration order but keep deterministic order

If callers only need predictability, replace registration-order semantics with an explicit deterministic order.

Refined preferred candidate:

- `all-skills` returns skills in canonical `:name` order
- canonical `:name` order means ascending Clojure/JVM string comparison of the skill `:name` values (`compare` / `String.compareTo`)
- the comparator is case-sensitive and locale-independent; names that differ only by case appear according to JVM string ordering rather than folded together
- skill names are already unique by exact string, so case-distinct names remain distinct entries if accepted elsewhere by existing validation
- `skill-names` follows that same canonical order
- `find-skill` remains exact-name lookup
- duplicate registration remains first-write-wins by identity, but the visible listing order is no longer insertion order
- `register-skill` should return `:skills` in the same canonical order so session state written through registration is canonicalized at the registry boundary
- arbitrary pre-existing or externally supplied session `:skills` vectors are not themselves a trusted ordering contract; prompt/display/introspection code that renders or projects skills must use `skill-registry/all-skills`, `skill-registry/skill-names`, or an equivalent canonical sort before exposing ordered output

This keeps prompt/discovery surfaces deterministic without requiring the registry contract to remember registration sequence.

### C. Make ordering explicitly non-semantic at the registry layer

If ordering belongs only to presentation/prompt projection layers, make `skill-registry` itself order-insensitive and let higher layers sort explicitly when rendering.

This branch is viable only if the audit shows registry callers can safely treat `all-skills`, `skill-names`, and `register-skill` result `:skills` as unordered membership/count/exact-lookup surfaces, while every user- or model-visible prompt/display/introspection surface applies its own deterministic canonical `:name` sort before rendering or projection.

Current audit evidence makes B more likely than C, because higher callers already consume `all-skills` / `skill-names` directly and would otherwise each need to re-establish the same deterministic sort.

## Affected ordered surfaces

The implementation audit must check and, when needed, route these skill-list surfaces through canonical ordering:

- `psi.skill-registry.registry/all-skills` and `skill-names`
- `:session/register-skill` stored result and returned result map
- discovery resolvers in `psi.agent-session.resolvers.discovery`, especially `:psi.skill/all`, `:psi.skill/names`, summaries, and source groupings
- session introspection resolver `:psi.agent-session/skills`
- prompt construction paths in prompt lifecycle handlers and `psi.prompt-assets.system_prompt` that render ordered skill lists
- prompt-assets skill helpers such as `format-skills-for-prompt`, `skill-summary`, `skills-by-source`, `visible-skills`, and `hidden-skills` when their output order is user- or model-visible
- TUI display/autocomplete surfaces that project `(:skills state)`
- `psi.agent-session.prompt_request` only for exact `/skill:name` lookup expansion; it does not own or consume canonical skill-list ordering
- workflow child-session skill resolution only for exact-name lookup; prompt-component / workflow `:skill-names` is an allowlist, not an ordering directive

## Prompt-component skill subset ordering

Prompt-component and workflow child-session `:skill-names` values select which skills are included; they do not define the order in which selected skills are rendered to the model.

Expected behavior:

- `:skill-names` preserves caller-declared order only as input/configuration metadata.
- filtering by `:skill-names` should not expose inherited parent/session vector order as a model-visible ordering contract.
- when the filtered skill subset is rendered or projected as an ordered list, it should use the same canonical skill `:name` ordering as other prompt/display surfaces.
- branch B satisfies this naturally if the source skill collection is canonicalized at the registry/read boundary; any prompt path that filters a raw or externally supplied `:skills` vector must still canonicalize before model-visible rendering.
- branch C keeps the registry order-insensitive, so prompt-component filtering/rendering is explicitly in scope as a higher prompt projection seam that must canonicalize the filtered subset before exposing it.
- the keep-order branch may preserve insertion-order rendering only if the implementation audit finds and documents a real requirement for that behavior.


## Task 164 update scope

If registration order is removed via branch B, task `164` should update the current conclusions that describe `skill-registry` as order-sensitive:

- revise the registry comparison row from "registration order preserved" / "ordered collection likely required" to canonical deterministic name ordering, while preserving duplicate-ignore and exact lookup conclusions
- revise caller/test notes that currently treat stable prompt/discovery listing as evidence for insertion-order semantics
- add a dated note that this task refined the audit conclusion after checking callers
- keep historical audit notes as prior evidence when useful, but mark them superseded rather than deleting context that explains why this task was created

If branch C is selected, task `164` should instead record that skill registration order was removed without making registry read order meaningful: registry helpers provide unordered membership/count/exact lookup, and deterministic sorting is owned by higher prompt/display/introspection projection seams. The update should identify the higher seams that now own canonical presentation sorting and should not describe `skill-registry` itself as canonical-name-sorted.

If registration order is kept, task `164` should only add a dated note identifying the confirmed insertion-order dependency and should leave the order-sensitive conclusion intact.

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

---
name: resolve-task-design-entities-resolve
description: Resolve ambiguous references in a Munera task design and update design.md with canonical values
tools:
  - read
  - bash
  - edit
  - write
skills:
  - entity-resolution
---
Use the entity-resolution skill to process the Munera task design
identified by {{input}} and update that task's design.md with resolved
canonical values.

Treat the input as a task reference, not as free-form design text.

Required procedure:

1. Normalize and resolve the task reference.
   - Accept exactly one of: `NNN-slug`, `munera/open/NNN-slug`, or `munera/closed/NNN-slug`.
   - Resolve against `munera/open/` and `munera/closed/`.
   - If zero or multiple task directories match, do not edit anything. Report what was searched and end with `PASS_STATUS: ACTIONABLE_FEEDBACK`.

2. Read task artifacts.
   - Always read the resolved task's `design.md`.
   - Read `design-steps.md`, `plan.md`, `steps.md`, and `implementation.md` when present.

3. Identify ambiguous references in `design.md`.
   - Look for pronouns, deixis, aliases, shorthand, project-specific
     terms, incomplete paths, informal workflow/skill/extension names,
     task refs, namespace/var refs, and command names.
   - Build the internal mapping: surface -> canonical project
     entity/path/term -> evidence -> confidence.

4. Resolve with evidence.
   - Prefer filesystem/runtime evidence over memory or docs.
   - Use `git ls-files`, `find`, and `git grep` as needed.
   - Do not silently guess project paths or entities.

5. Update `design.md` only when the mapping is unambiguous.
   - Replace ambiguous surface text with the canonical entity/path/term,
     preserving the design's meaning and style.
   - Keep the change localized to entity-resolution edits; do not
     redesign the task, broaden scope, create `plan.md`, edit
     implementation code, or alter acceptance criteria except to name
     the resolved entity precisely.
   - If no ambiguous references are present, leave `design.md` unchanged
     and report that no entity-resolution update was needed.

6. Update `implementation.md` with useful discoveries for future task steps.
   - Ask yourself: "What would the next task-lifecycle step,
     implementation slice, or review need to know about the
     entity-resolution work just done?"
   - Append a minimalist entry to `implementation.md` when you
     discovered information that will help later steps or reviews.
   - Useful entries include rationale or evidence for a non-obvious
     resolution, unresolved options and why they remain ambiguous,
     useful project paths used for resolution.
   - Avoid duplicating information already obvious in `design.md` or
     other task files. If nothing useful was discovered beyond the
     direct `design.md` edit, do not add noise.

7. Handle unresolved ambiguity.
   - If any reference remains ambiguous, do not force a guess.
   - Prefer recording the smallest focused `SCOPE_QUESTION:` item in `design-steps.md` when that file exists and the ambiguity blocks lifecycle progress.
   - Also record concise implementation.md context for future steps when the unresolved ambiguity affects planning, implementation, or review.
   - Otherwise report the likely options and end with `PASS_STATUS: ACTIONABLE_FEEDBACK`.

8. Commit only this workflow's task-artifact edits.
   - Inspect `git status --short` before committing.
   - Stage only the resolved task's `design.md`, `implementation.md`, and, if you added scope questions, its `design-steps.md`. Never use `git add .` or `git add -A`.
   - Commit with a concise message such as `⊨ resolve task design entities`.
   - If there are no edits, do not commit.
   - If unrelated dirty changes prevent a safe isolated commit, leave your edits unstaged and report the block.

Final response:
- resolved task path
- whether `design.md` was updated
- whether `implementation.md` was updated with useful future-step context
- entity mappings applied, with concise evidence
- unresolved ambiguities or scope questions, if any
- commit id, if committed
- end with exactly one status line at column 0:
  - `PASS_STATUS: REVIEW_COMPLETE` when all design references are resolved or no resolution was needed
  - `PASS_STATUS: ACTIONABLE_FEEDBACK` when ambiguity remains or task resolution failed

Input task reference:
{{input}}

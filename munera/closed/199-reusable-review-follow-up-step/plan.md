# Plan — 199 Unified review follow-up step

## Approach

Configuration/prompt-only change. No runtime, compiler, grammar, or routing
code is touched. The work replaces five near-identical follow-up prompts with
**two** shared profile follow-up `.md` files and rewires the three review hosts
to reference them.

### Key decisions (inherited from design.md, all resolved)

- **One shared follow-up `.md` per scope profile (two files), not one
  parameterized file.** The compiler wires a `:prompt-workflow` `.md`'s
  `{{var}}` tokens only against that `.md`'s own frontmatter `vars:`
  (→ `:workflow-input`/`:workflow-original`) plus standard vars; a host step
  cannot inject a per-step literal profile. Profile is therefore encoded by
  *which file* a host references. Each file uses `{{input}}` only.
  - `review-follow-up-design.md` — `design` profile: items file
    `design-steps.md`; writable `design.md` + `design-steps.md` +
    `implementation.md`; forbidden `plan.md`/`steps.md`.
  - `review-follow-up-steps.md` — `steps` profile: items file `steps.md`;
    writable `plan.md` + `steps.md` + `implementation.md`; `design.md`
    read-only context.
- **Generic "preceding review pass" wording** in both shared files (no named
  review step). Deliberate generalization of the current per-step named
  references; each host wires the follow-up immediately after its review step,
  so the reference is unambiguous at runtime.
- **Predate-exclusion guard generalized to both profiles.** Identical to
  current behaviour for `steps`/plan and `design`; an intentional small
  tightening for `review-step` (no prior explicit guard).
- **Host routing/looping is preserved exactly.** Design/plan advance forward
  per aspect; `review-step` loops `REPEAT→review` with `:max-iterations`. Only
  the follow-up *step body* is shared; the `:on`/`:judge` wiring around each
  follow-up is left untouched.

### Mechanics per host

- `review-task-design.edn`: both `ambiguity-follow-up` and
  `inconsistency-follow-up` steps point `:prompt-workflow` at
  `review-follow-up-design.md`.
- `review-task-plan.edn`: both follow-up steps point `:prompt-workflow` at
  `review-follow-up-steps.md`.
- `review-step.edn`: the inline `follow-up` step's `:contributions` template is
  replaced by `:prompt-workflow "review-follow-up-steps.md"` (preserving the
  `:source` contributions, judge, and `:on`).

### Removal

Delete the four per-aspect follow-up `.md` files and the inline `review-step`
follow-up template (no orphans):
`review-task-design-ambiguity-follow-up.md`,
`review-task-design-inconsistency-follow-up.md`,
`review-task-plan-ambiguity-follow-up.md`,
`review-task-plan-inconsistency-follow-up.md`.

### Tests

`components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`
enumerates the old follow-up filenames in `review-task-design-test`,
`review-task-plan-test`, and the body-content assertions. Update these to the
two new shared filenames and add coverage that all three hosts reference the
correct profile file. Verify `review-step` still wires its loop and that all
review workflows load/validate.

### Docs

`doc/workflows.md` does not currently document the review-workflow family or
its follow-up steps (confirmed). AC#7 is satisfied by *adding* a short
review-workflow follow-up reference describing the two shared profile follow-ups
and which hosts use each profile.

## Risks

- **Test enumeration drift:** the definitions test lists exact filenames in
  several places; missing one leaves a stale reference. Mitigation: grep for all
  old filenames after edits and confirm zero matches outside git history.
- **`review-step` template→prompt-workflow conversion:** the inline template
  carries two `:source` contributions plus the template text. Converting to
  `:prompt-workflow` must preserve the `:source` contributions and the judge/`:on`
  loop. Mitigation: edit only the template contribution; re-validate the loaded
  workflow.
- **Behaviour-change flag (`review-step` predate guard):** the new shared
  `steps` file adds an explicit predate guard absent from the old inline
  template. This is intentional and flagged in design.md; confirm no test
  asserts the old (guard-free) wording.
- **Profile artifact-scope wording must match design table exactly** (writable
  vs read-only vs forbidden), or behaviour drifts. Mitigation: derive each file's
  prose directly from the design profile table.

## Slice order

1. **Authoring the shared follow-up files** — create the two profile `.md`
   files with correct frontmatter and profile-specific contract prose.
2. **Rewire hosts + remove redundant prompts** — point the three hosts at the
   shared files; delete the four obsolete per-aspect follow-up files and the
   inline `review-step` template.
3. **Tests** — update/extend `workflow_definitions_test.clj` for the shared
   wiring across all three hosts; run the workflow-loader test suite.
4. **Docs** — add review-workflow follow-up reference content to
   `doc/workflows.md`.
5. **Coherence + close** — verify all review workflows load/validate, no orphan
   references remain, lint clean, then close the task.

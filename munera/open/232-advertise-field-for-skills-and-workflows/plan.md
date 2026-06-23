# Plan — 232 `advertise` field for skills and workflows

## Resolved open questions

1. **disable-model-invocation overlap** — Decision (a): keep both as
   independent fields. A skill is hidden from the system context iff
   `disable-model-invocation` is true **or** `advertise` is `false`. No
   migration; behaviour-preserving for existing `disable-model-invocation`
   users.
2. **Interactive listings** — "system context" = prompt contribution only.
   `advertise: false` affects only `build-prompt-contribution` (workflows) and
   `format-skills-for-prompt`/`-lambda` (skills). The user-facing `/delegate
   list` / `action=list` (`available-workflows-text`/`delegate-list-text`) are
   **not** changed — non-advertised items still show there.
3. **Exact set to mark** — applied in slice 2 (see steps). Enumerated set:
   - Skills (`.psi/skills/*/SKILL.md`): `review-implementation-architecture`,
     `review-task-architecture`, `review-task-docs`, `task-implementation-review`,
     `task-test-review`, `issue-bug-triage`, `issue-feature-triage`.
   - Sub-only workflows: the `*-core`, `*-final-summary`, `*-implement-pass`,
     `*-resolve`, `*-in-worktree`, `*-create-plan`, `review-step`, the
     `review-task-design-*`/`review-task-plan-*` sub-review definitions, and
     `gh-bug-request-more-info` (enumerated concretely in slice 2).
4. **Parse robustness** — only the literal value `false` disables. The coercion
   is `advertise = (not (= "false" (lower-case (str value))))`; absent → `true`;
   any non-`false` value (typos included) → advertised. Mirrors the existing
   `disable-model-invocation` string-coercion idiom.

## Design refinement

The design scoped the workflow source to EDN `:advertise`. Many sub-only
workflows are single-step **markdown** files, so the field is supported
uniformly in markdown workflow frontmatter too (one concept, both file kinds).
This keeps acceptance criterion 4 (review-*/issue-* sub-only workflows
no longer advertised) satisfiable.

## Slices

1. **Mechanism** — parse + filter for skills, EDN workflows, markdown workflows;
   tests. (Behaviour-preserving: nothing flipped yet.)
2. **Apply the field** — flip the enumerated skills/workflows to
   `advertise: false`; verify they drop from the system context but stay
   invocable.

# Steps

## Slice 1 — Standalone extraction workflow prompt

- [ ] Create `.psi/workflows/extract-task-knowledge.md` as a single-step markdown workflow with frontmatter `name: extract-task-knowledge`, an accurate description, and tools `read`, `bash`, and `write`.
- [ ] In the prompt, require the actor to normalize `{{input}}` from either an exact `NNN-slug` or exact `munera/{open|closed}/NNN-slug` task path, reject any other path/string shape, and resolve the normalized slug against `munera/closed/{NNN-slug}` and `munera/open/{NNN-slug}`.
- [ ] In the prompt, specify missing or duplicate slug matches stop with no extraction and a concise report.
- [ ] In the prompt, specify standalone runs may extract only from `munera/closed/{NNN-slug}`; an open-only standalone match is incomplete and produces no mementum writes.
- [ ] In the prompt, specify the sole open-task exception: a `task-lifecycle` trailing invocation may extract from `munera/open/{NNN-slug}` only when lifecycle context supplied through `{{original}}` includes the immediately preceding `review-task-implementation` yielded text with `PASS_STATUS: REVIEW_COMPLETE`.
- [ ] In the prompt, list required task artifacts to inspect when present: `design.md`, `plan.md`, `steps.md`, and `implementation.md`.
- [ ] In the prompt, constrain git-history evidence to commits touching the resolved task directory, commits whose message mentions the task id or slug, and commit SHAs explicitly recorded in task artifacts.
- [ ] In the prompt, require recall of existing `mementum/memories/` and `mementum/knowledge/` before writing, with update-or-skip behavior for duplicates.
- [ ] In the prompt, encode the extraction filter: gate-1, gate-2, project-general usefulness, significant future-development value, task-local trivia rejection, and `uncertain → skip`.
- [ ] In the prompt, describe allowed outputs: `mementum/memories/{slug}.md` for one insight and `mementum/knowledge/{topic}.md` with required frontmatter for synthesized topic pages.
- [ ] In the prompt, require autonomous git commits for any mementum writes using mementum commit conventions and require no human approval request.
- [ ] In the prompt, require a final summary that reports extracted memories/knowledge, skipped/updated duplicates, zero-extraction success when applicable, and any lifecycle/review outcome supplied in context.
- [ ] Confirm `.psi/workflows/extract-task-knowledge.edn` does not exist.

## Slice 2 — Task-lifecycle integration

- [ ] Append a final `:delegate` step named `extract-task-knowledge` to `.psi/workflows/task-lifecycle.edn` targeting `extract-task-knowledge`.
- [ ] Wire the extraction delegate `:prompt-string` to pass the same original task `:input` unchanged from `:workflow-input` at path `[:input]`; the extraction prompt normalizes slug/path inputs.
- [ ] Add `:workflow-original` to the extraction delegate context.
- [ ] Add the `review-task-implementation` yielded text to the extraction delegate context as the only success evidence for the open-task exception.
- [ ] Update the `task-lifecycle` description to mention design → plan → implement → review → extract knowledge.
- [ ] Confirm the previous five lifecycle delegate steps keep their names, targets, prompt-string input threading, and original context.

## Slice 3 — Workflow definition tests

- [ ] Add or update workflow-loader tests proving `extract-task-knowledge.md` loads as workflow `extract-task-knowledge` without errors.
- [ ] Add a test asserting there is no `.psi/workflows/extract-task-knowledge.edn` same-name sibling.
- [ ] Add tests asserting the extraction workflow uses exactly the intended tool set (`read`, `bash`, `write`).
- [ ] Add prompt content-lock tests for slug/path normalization, closed-only standalone extraction, missing/duplicate stop behavior, and lifecycle-only open-task extraction after `{{original}}` context includes `PASS_STATUS: REVIEW_COMPLETE` from implementation review.
- [ ] Add prompt content-lock tests for task-scoped git-history lenses and the prohibition on roaming unrelated repository history.
- [ ] Add prompt content-lock tests for mementum recall/dedupe/update-or-skip, significance/project-generality filters, `uncertain → skip`, zero-extraction success, and the rule that success-looking text in `{{input}}` never authorizes open-task extraction.
- [ ] Update `task-lifecycle-test` to expect six delegate steps ending in `extract-task-knowledge`.
- [ ] Update `task-lifecycle-test` to assert the first five steps still thread input and context as before.
- [ ] Update `task-lifecycle-test` to assert the final extraction step threads the same input unchanged, carries `:workflow-original`, and carries `{:step "review-task-implementation" :yield :text}` context.
- [ ] Update `task-lifecycle-test` to assert no step declares unexpected `:yields` or `:terminal-contract` unless the implementation deliberately adds one.
- [ ] Add a workflow-loader test locking that the compiled `extract-task-knowledge` markdown contribution references `{{original}}` with `{:from :workflow-original}` so lifecycle/review context is visible to the prompt.

## Slice 4 — Docs and changelog

- [ ] Add a `doc/workflows.md` section for `extract-task-knowledge`, including `/delegate extract-task-knowledge {NNN-slug}` usage.
- [ ] Document that standalone extraction only mines closed tasks, applies conservative mementum gates, may produce zero entries successfully, and commits mementum writes autonomously when entries pass the filters.
- [ ] Document that `task-lifecycle` now runs extraction as its final stage and preserves the implementation-review/lifecycle outcome in the final summary.
- [ ] Add a `CHANGELOG.md` `[Unreleased]` Added entry for the new `/delegate extract-task-knowledge` workflow.
- [ ] Add a `CHANGELOG.md` `[Unreleased]` Changed entry for the changed `task-lifecycle` terminal behavior.

## Slice 5 — Verification/coherence pass

- [ ] Run the focused workflow-loader tests covering workflow definitions.
- [ ] Run `clj-kondo --lint` on any changed Clojure test files.
- [ ] Run formatting or EDN read checks needed for the changed workflow EDN.
- [ ] Inspect `git diff` to confirm no same-name workflow `.edn` was created and no mementum extension code was changed.
- [ ] Verify each acceptance criterion from `design.md` is represented by workflow prompt text, lifecycle wiring, tests, docs, or changelog.
- [ ] Append implementation notes summarizing decisions, verification commands, and any non-blocking limitations.
- [ ] Commit the completed implementation slices.

## Plan/steps ambiguity review follow-ups

- [x] PA1: DONE — resolved identifier shape by making extraction normalize either an exact `NNN-slug` or exact `munera/{open|closed}/NNN-slug` task path, rejecting any other shape. Lifecycle callers may forward their original input unchanged, including the `munera/open/NNN-slug` path produced by `task-lifecycle-in-worktree`; the extraction prompt/tests must lock normalization.
- [x] PA2: DONE — specified that the markdown workflow body must explicitly include `{{original}}`, which the compiler auto-wires to `{:from :workflow-original}`. The lifecycle delegate will pass `:workflow-original` plus the implementation-review yielded text as context; tests must lock the compiled `{{original}}` source.
- [x] PA3: DONE — chose the observable marker `PASS_STATUS: REVIEW_COMPLETE` in the lifecycle-injected `review-task-implementation` yielded text supplied through `{{original}}`. Delegate completion alone and final-summary prose are insufficient; success-looking text in `{{input}}` must not authorize open-task extraction. Prompt/tests must lock this marker and source distinction.

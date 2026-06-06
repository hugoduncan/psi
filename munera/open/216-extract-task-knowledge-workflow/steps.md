# Steps

## Slice 1 — Standalone extraction workflow prompt

- [x] Create `.psi/workflows/extract-task-knowledge.md` as a single-step markdown workflow with frontmatter `name: extract-task-knowledge`, an accurate description, and tools `read`, `bash`, and `write`.
- [x] In the prompt, require the actor to normalize `{{input}}` from either an exact `NNN-slug` or exact `munera/{open|closed}/NNN-slug` task path, reject any other path/string shape, and resolve the normalized slug against `munera/closed/{NNN-slug}` and `munera/open/{NNN-slug}`.
- [x] In the prompt, specify missing or duplicate slug matches stop with no extraction and a concise report.
- [x] In the prompt, specify standalone runs may extract only from `munera/closed/{NNN-slug}`; an open-only standalone match is incomplete and produces no mementum writes.
- [x] In the prompt, specify the sole open-task exception: a `task-lifecycle` trailing invocation may extract from `munera/open/{NNN-slug}` only when the dedicated `{{implementation_review_yield}}` section, sourced from the labeled `:implementation-review-yield` prompt-string field, contains the immediately preceding `review-task-implementation` yielded text with `PASS_STATUS: REVIEW_COMPLETE`; `{{original}}` is ambient and non-authorizing.
- [x] In the prompt, list required task artifacts to inspect when present: `design.md`, `plan.md`, `steps.md`, and `implementation.md`.
- [x] In the prompt, constrain git-history evidence to commits touching the resolved task directory, commits whose message mentions the task id or slug, and commit SHAs explicitly recorded in task artifacts.
- [x] In the prompt, require recall of existing `mementum/memories/` and `mementum/knowledge/` before writing, with update-or-skip behavior for duplicates.
- [x] In the prompt, encode the extraction filter: gate-1, gate-2, project-general usefulness, significant future-development value, task-local trivia rejection, and `uncertain → skip`.
- [x] In the prompt, describe allowed outputs: `mementum/memories/{slug}.md` for one insight and `mementum/knowledge/{topic}.md` with required frontmatter for synthesized topic pages.
- [x] In the prompt, require autonomous git commits for any mementum writes using mementum commit conventions and require no human approval request.
- [x] In the prompt, require a final summary that reports extracted memories/knowledge, skipped/updated duplicates, zero-extraction success when applicable, and any lifecycle/review outcome supplied in context.
- [x] Confirm `.psi/workflows/extract-task-knowledge.edn` does not exist.

## Slice 2 — Task-lifecycle integration

- [x] Append a final `:delegate` step named `extract-task-knowledge` to `.psi/workflows/task-lifecycle.edn` targeting `extract-task-knowledge`.
- [x] Wire the extraction delegate `:prompt-string` to pass the same original task `:input` unchanged from `:workflow-input` at path `[:input]`; the extraction prompt normalizes slug/path inputs.
- [x] Add `:workflow-original` to the extraction delegate context.
- [x] Add the `review-task-implementation` yielded text to the extraction delegate `:prompt-string` map as the labeled `:implementation-review-yield` field; this labeled prompt input is the only success evidence for the open-task exception.
- [x] Update the `task-lifecycle` description to mention design → plan → implement → review → extract knowledge.
- [x] Confirm the previous five lifecycle delegate steps keep their names, targets, prompt-string input threading, and original context.

## Slice 3 — Workflow definition tests

- [x] Add or update workflow-loader tests proving `extract-task-knowledge.md` loads as workflow `extract-task-knowledge` without errors.
- [x] Add a test asserting there is no `.psi/workflows/extract-task-knowledge.edn` same-name sibling.
- [x] Add tests asserting the extraction workflow uses exactly the intended tool set (`read`, `bash`, `write`).
- [x] Add prompt content-lock tests for slug/path normalization, closed-only standalone extraction, missing/duplicate stop behavior, and lifecycle-only open-task extraction after the dedicated `{{implementation_review_yield}}` section includes `PASS_STATUS: REVIEW_COMPLETE` from implementation review; ambient `{{original}}` success-looking text is insufficient.
- [x] Add prompt content-lock tests for task-scoped git-history lenses and the prohibition on roaming unrelated repository history.
- [x] Add prompt content-lock tests for mementum recall/dedupe/update-or-skip, significance/project-generality filters, `uncertain → skip`, zero-extraction success, and the rule that success-looking text in `{{input}}` never authorizes open-task extraction.
- [x] Update `task-lifecycle-test` to expect six delegate steps ending in `extract-task-knowledge`.
- [x] Update `task-lifecycle-test` to assert the first five steps still thread input and context as before.
- [x] Update `task-lifecycle-test` to assert the final extraction step threads the same input unchanged, carries ambient `:workflow-original` context only in delegate `:context`, and carries `{:step "review-task-implementation" :yield :text}` as the labeled `:implementation-review-yield` prompt-string field.
- [x] Update `task-lifecycle-test` to assert no step declares unexpected `:yields` or `:terminal-contract` unless the implementation deliberately adds one.
- [x] Add workflow-loader tests locking that the compiled `extract-task-knowledge` markdown contribution references `{{original}}` with `{:from :workflow-original}` as ambient context and `{{implementation_review_yield}}` with `{:from :workflow-input :path [:implementation-review-yield]}` as the labeled authorization/review-yield source.

## Slice 4 — Docs and changelog

- [x] Add a `doc/workflows.md` section for `extract-task-knowledge`, including `/delegate extract-task-knowledge {NNN-slug}` usage.
- [x] Document that standalone extraction only mines closed tasks, applies conservative mementum gates, may produce zero entries successfully, and commits mementum writes autonomously when entries pass the filters.
- [x] Document that `task-lifecycle` now runs extraction as its final stage and preserves the implementation-review/lifecycle outcome in the final summary.
- [x] Add a `CHANGELOG.md` `[Unreleased]` Added entry for the new `/delegate extract-task-knowledge` workflow.
- [x] Add a `CHANGELOG.md` `[Unreleased]` Changed entry for the changed `task-lifecycle` terminal behavior.

## Slice 5 — Verification/coherence pass

- [x] Run the focused workflow-loader tests covering workflow definitions.
- [x] Run `clj-kondo --lint` on any changed Clojure test files.
- [x] Run formatting or EDN read checks needed for the changed workflow EDN.
- [x] Inspect `git diff` to confirm no same-name workflow `.edn` was created and no mementum extension code was changed.
- [x] Verify each acceptance criterion from `design.md` is represented by workflow prompt text, lifecycle wiring, tests, docs, or changelog.
- [x] Append implementation notes summarizing decisions, verification commands, and any non-blocking limitations.
- [x] Commit the completed implementation slices.

## Plan/steps ambiguity review follow-ups

- [x] PA1: DONE — resolved identifier shape by making extraction normalize either an exact `NNN-slug` or exact `munera/{open|closed}/NNN-slug` task path, rejecting any other shape. Lifecycle callers may forward their original input unchanged, including the `munera/open/NNN-slug` path produced by `task-lifecycle-in-worktree`; the extraction prompt/tests must lock normalization.
- [x] PA2: DONE — originally specified that the markdown workflow body must explicitly include `{{original}}`, which the compiler auto-wires to `{:from :workflow-original}`. IR1 superseded the review-yield part: `{{original}}` is now ambient non-authorizing context only, while the implementation-review yielded text is passed via the labeled `:implementation-review-yield` prompt-string field and consumed through `{{implementation_review_yield}}`; tests lock both sources.
- [x] PA3: DONE — chose the observable marker `PASS_STATUS: REVIEW_COMPLETE` in the lifecycle-injected `review-task-implementation` yielded text. IR1 superseded the source: the marker must appear in the dedicated `{{implementation_review_yield}}` section sourced from the labeled `:implementation-review-yield` prompt-string field. Delegate completion, final-summary prose, success-looking text in `{{input}}`, and ambient `{{original}}` text are insufficient. Prompt/tests lock this marker and source distinction.

## Implementation review follow-ups

- [x] IR1: DONE — labeled the lifecycle review yield by moving it into the extraction delegate `:prompt-string` map as `:implementation-review-yield`, leaving `:workflow-original` as ambient non-authorizing context. Updated the extraction markdown prompt to use the dedicated `{{implementation_review_yield}}` section as the sole open-task authorization source and to reject success-looking ambient `{{original}}` text. Updated workflow-loader tests to lock the prompt-visible source distinction and lifecycle wiring. Original IR1 requested a prompt-visible label or equivalent structure for the `review-task-implementation` yield so arbitrary prior `:workflow-original` text could not authorize open-task extraction.
- [x] IR2: DONE — synchronized task design/docs/stale step wording after IR1 so every artifact says the implementation-review yield is carried as the labeled `:implementation-review-yield` prompt-string field and consumed through `{{implementation_review_yield}}`; `{{original}}` / delegate context is ambient and non-authorizing. Updated `design.md` acceptance/terminal-context wording, `doc/workflows.md` lifecycle-open-task wording, and stale completed steps/PA2/PA3 notes that still used obsolete source wording.

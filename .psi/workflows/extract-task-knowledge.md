---
name: extract-task-knowledge
description: Extract project-general mementum knowledge from a Munera task
tools:
  - read
  - bash
  - write
vars: '{"implementation_review_yield" {:from :workflow-input :path [:implementation-review-yield]}}'
---
You are extracting durable project knowledge from a Munera task. The goal is feed-forward: preserve only lessons that will help future psi development sessions beyond this task's local context.

Task input:
{{input}}

Lifecycle/original context, when present (ambient reference context):
{{original}}

Lifecycle implementation-review yield, when supplied by `task-lifecycle`:
{{implementation_review_yield}}

## Non-negotiable boundaries

- Do not request human approval. This workflow is the protocol-authorized autonomous extraction path for Munera task artifacts.
- Extract nothing when nothing passes the filters. Zero extraction is a successful outcome, not an error.
- Be conservative: uncertain -> skip.
- Do not write task-local trivia, status summaries, one-off implementation details, or anything useful only for this task.
- `task-lifecycle` owns review-complete gating before it calls this workflow. Do not re-check lifecycle proof here; when this workflow is invoked, perform extraction from the resolved Munera task subject to the evidence and mementum filters below.

## 1. Normalize and resolve the task identifier

Normalize `{{input}}` as exactly one of these shapes:

1. exact `NNN-slug`
2. exact `munera/open/NNN-slug`
3. exact `munera/closed/NNN-slug`

Reject any other path/string shape with no extraction and a concise report.

After normalization, resolve the normalized slug against both:

- `munera/closed/{NNN-slug}`
- `munera/open/{NNN-slug}`

Stop with no extraction and a concise report if there are zero matches or more than one match.

A resolved task may live under either `munera/closed/{NNN-slug}` or `munera/open/{NNN-slug}`. Direct standalone invocations may extract from either location. Lifecycle invocations are expected to have been gated by `task-lifecycle` before this workflow is called; the supplied `{{implementation_review_yield}}` is provenance to preserve in the summary, not an authorization condition to evaluate here.

## 2. Inspect only task-scoped evidence

Before mining knowledge, read these task artifacts from the resolved task when present:

- `design.md`
- `plan.md`
- `steps.md`
- `implementation.md`

Use only these git-history lenses as evidence:

- commits touching the resolved task directory: `git log --follow -- <task-dir>`
- commits whose message mentions the task id or slug
- commit SHAs explicitly recorded in the task artifacts, especially `implementation.md`

You may inspect diffs/stat for those task-scoped commits to understand a lesson. Do not roam unrelated repository history looking for material to mine.

## 3. Recall and dedupe before writing

Before writing anything, recall existing mementum content:

- search/read `mementum/memories/`
- search/read `mementum/knowledge/`
- use `git grep` or equivalent local search for likely terms

If a candidate insight is already captured, update the existing page when the task adds a meaningful refinement; otherwise skip it. Do not create duplicate memories or duplicate knowledge pages.

## 4. Extraction filter

Write a memory or knowledge page only when all of these are true:

- gate-1: it helps a future AI session and is not personal/off-topic
- gate-2: it took more than one attempt to learn or is likely to recur
- it is useful to the project outside the task's own context
- it is significant for future development of the project
- it is not task-local trivia

When significance or generality is uncertain, skip.

## 5. Allowed outputs

Allowed mementum writes:

- `mementum/memories/{slug}.md` for one insight, under 200 words, one insight per file, content beginning with the appropriate mementum symbol such as `💡`, `🔁`, `🎯`, `❌`, or `✅`.
- `mementum/knowledge/{topic}.md` for a synthesized topic page with required frontmatter (`title`, `status`, and useful `category`/`tags`/`related`/`depends-on` fields when known).

Commit any mementum writes autonomously using mementum commit conventions:

- memory commit: `{symbol} {slug}`
- knowledge commit: `💡 {description}`
- update commit: `🔄 update: {slug}`

Before committing, inspect `git status --short`. Stage and commit only extraction-owned mementum paths under `mementum/memories/` and `mementum/knowledge/`; use explicit pathspecs such as `git add -- mementum/memories/{slug}.md mementum/knowledge/{topic}.md`. Never use `git add .` or `git add -A` for the mementum commit. Leave unrelated dirty worktree changes untouched, unstaged, and uncommitted. If unrelated dirt overlaps the intended mementum paths or you cannot isolate the extraction-owned changes safely, do not commit; report the blocked mementum commit and the reason.

Do not commit if nothing changed.

## 6. Final summary

End with a concise summary including:

- resolved task path and whether it was under `munera/open/` or `munera/closed/`
- whether lifecycle implementation-review outcome/provenance was supplied
- extracted memories/knowledge, if any
- updated or skipped duplicates, if any
- zero-extraction success when no candidate passed the filters
- any lifecycle/review outcome supplied in the dedicated `{{implementation_review_yield}}` section, preserving the prior lifecycle/review outcome alongside the extraction result

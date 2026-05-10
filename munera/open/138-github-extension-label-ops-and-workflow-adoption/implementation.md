# Implementation Notes

## 2026-05-10 — Design review pass 1 follow-up execution (design-steps A–G)

All seven design-steps resolved:

- **A** — steps.md `gh-bug-triage` and `gh-bug-post-repro` conditional-label items split:
  unconditional `remove-label triage` is a deterministic `:invoke` step; conditional add
  (`waiting` vs `fix`) stays AI-driven with explicit NOTE in steps.md.  design.md scope
  table updated to match.
- **B** — decided: extract to `psi.github.slug` (not inline copy).  design.md §slug
  derivation updated; steps.md Phase 1 already reflects this.
- **C** — decided: `find-pr` URL regex is `#"/pull/(\d+)"`.  design.md §find-pr URL
  narrowing added; steps.md Phase 2 test item updated with explicit regex note.
- **D** — decided: `:input` wired as `{:from :workflow-input :path [:input]}` in both
  `gh-issue-implement` and `gh-pr-fix-checks` `find-pr` steps.  design.md §:input wiring
  added; steps.md Phase 5 items updated.
- **E** — clarified: `gh-bug-triage-modular` already migrated for discovery; not touched
  here.  `gh-bug-triage` (monolithic) migrated for discovery + unconditional label step
  only.  Neither variant deprecated.  design.md Out of scope updated.
- **F** — tightened: `extension-test` must assert all four operation ids explicitly, not
  just count.  steps.md Phase 3 item updated.
- **G** — `plan.md` created with six-phase approach, key decisions, and risks.



## 2026-05-10 — Design review pass 1

**Ambiguities found:**

1. **`design-steps.md` absent** — workflow protocol expects follow-up items in `design-steps.md`; only `steps.md` exists. Created `design-steps.md`.
2. **Conditional label migration contradictory in steps.md** — `gh-bug-triage` and `gh-bug-post-repro` steps say "Add trailing `:invoke github/add-label` (add `waiting` or `fix`, determined by AI classify output)" but implementation.md correctly notes this must stay AI-driven. The steps item is misleading — needs clarification/split.
3. **`gh-bug-triage` vs `gh-bug-triage-modular` scope gap** — `gh-bug-triage-modular` already uses `:invoke github/find-issue` for discovery. Design does not address whether `gh-bug-triage` (monolithic) should be migrated or deprecated. Steps only touch `gh-bug-triage.md`.
4. **`find-pr` URL narrowing regex unspecified** — design says "same narrowing logic as `find-issue`" but `find-issue` uses `/issues/(\d+)` regex; PRs live at `/pull/NNN`. The difference is not called out in design or steps.
5. **`psi.github.slug` extraction vs inline copy — unresolved OR** — design says "extract to `psi.github.slug` shared ns, or inline a parallel copy." Must pick one before implementation.
6. **`:input` wiring for `find-pr` steps in migrated workflows** — design specifies `find-pr` args include `:input nil-or-hint` but does not specify how the narrowing hint from `:workflow-input` is wired into the `:invoke` step in `gh-issue-implement` and `gh-pr-fix-checks`.
7. **`extension-test` update under-specified** — steps say "assert four registrations" but do not say to assert all four operation ids specifically (not just count).
8. **No `plan.md`** — task has no `plan.md`; Munera requires one before execution.

## 2026-05-10 — Design review pass 2

**Inconsistencies found:**

1. **Label-ops :number arg vs find-issue/find-pr output key names** — design data flow says
   label-ops receive `:number from upstream :data`, but find-issue outputs `:issue-number`
   and find-pr outputs `:pr-number`. The wiring requires `:path [:issue-number]` or
   `:path [:pr-number]` respectively. Neither design.md nor steps.md specifies this mapping.

2. **Existing find-issue :outputs missing :data** — `gh-issue-refine` and `gh-bug-fix-and-pr`
   already have a `find-issue` step that only declares `:outputs {:summary {:source :invoke/summary}}`.
   Label-ops steps need `:data` from that step. Steps.md migration items for these two workflows
   do not say to add `:data {:source :invoke/data}` to the discover step `:outputs`.

3. **gh-issue-refine add-label (PR target): PR number not in structured :data** — the PR is
   created inside the `publish` delegate step. The publish step handoff includes `pr_url` in
   markdown text but no structured `:data` with `:pr-number`. There is no upstream `:data`
   source for the add-label `:number` arg (PR target). Design.md does not specify how to
   obtain the PR number for this step.

## 2026-05-10 — Task created

Initial scope survey:

**Extension current state:**
- `psi.github.extension/init` registers exactly one operation: `github/find-issue`
- `psi.github.find-issue` uses `derive-slug` inline (not shared); must be extracted first
- `psi.github.extension-test` asserts exactly one registration — will need update

**Workflows using inline `gh issue list` (need find-issue or find-pr adoption):**
- `gh-bug-discover-and-read` — full AI session for discovery
- `gh-bug-triage` — flat single-session, does both discovery and label mutation inline
- `gh-issue-ingest` — flat single-session, does both inline
- `gh-issue-implement` — dedicated discover session using `gh pr list`
- `gh-pr-fix-checks` — dedicated discover session using `gh pr list`

**Workflows using inline `gh issue edit` / `gh pr edit` for labels only:**
- `gh-bug-post-repro` — AI classify session emits label instructions
- `gh-bug-request-more-info` — AI post session emits label instructions
- `gh-issue-refine` — AI publish session emits label instructions (issue + PR)
- `gh-bug-fix-and-pr` — AI implement session emits label instruction (remove `fix`)

**Key design note on conditional label steps:**
`gh-bug-triage` and `gh-bug-post-repro` branch: add `waiting` OR add `fix` depending on
reproduction outcome.  The current workflow IR does not support conditional `:invoke`
steps.  Resolution: keep the AI session responsible for posting the comment and
signalling outcome via handoff data (`result_type: waiting-for-reporter` vs
`repro-ready-for-fix`); add two unconditional label `:invoke` steps after the classification
AI session — but only for the label that is always applied (remove `triage` is always
done).  The conditional add (`waiting` vs `fix`) must stay AI-driven until the workflow
IR gains conditional branching.  Document this explicitly in the steps for those two
workflows so the builder does not over-migrate.

**`gh-pr-fix-checks` note:**
PR discovery is the only change here; no label mutation occurs in this workflow.

**`gh-issue-refine` note:**
Two label targets: remove `refine` from the *issue*, add `waiting` to the *PR*.  Both
are now deterministic `:invoke` steps with explicit `:target` args.

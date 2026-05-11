# Implementation Notes

## 2026-05-10 — Design review pass 4 follow-up execution (design-steps O–R)

All four design-steps resolved:

- **O** — acceptance criteria updated in design.md to read "…no longer instruct the AI
  to perform *unconditional* label changes or shell-based discovery; conditional
  label-add in `gh-bug-triage` and `gh-bug-post-repro` remains AI-driven per §Out of
  scope."
- **P** — decided: option (b) — update `gh-bug-triage-modular` to pass structured input
  `{:issue_number N :report "..."}` to the `post-repro` delegate step.  The `discover`
  step gains `:data {:source :invoke/data}`; the `post-repro` `prompt-string` becomes a
  `:map` type with `:issue_number` wired from discover `:data` and `:report` from
  reproduce yield text.  `gh-bug-post-repro` classify session changes to
  `{:from :workflow-input :path [:report]}`; label-ops `:number` wires from
  `{:from :workflow-input :path [:issue_number]}` — correct for both standalone and
  delegate-call cases.  `gh-bug-request-more-info` unchanged (no delegate-call case).
  design.md §P added; steps.md `gh-bug-post-repro` and `gh-bug-triage-modular` blocks
  updated.
- **Q** — tightened prompt-stripping spec for `gh-bug-triage` and `gh-issue-ingest`.
  Steps now say: strip "Primary selection rule:" paragraph, "Input expectations:"
  paragraph, and step 1 of "Required procedure:" (named explicitly for each workflow).
  Also added stripping of label-change instructions in "Required procedure:" steps 4–5
  and "Goal" bullet for `gh-issue-ingest`.  design.md §Q added; steps.md migration
  blocks for both workflows updated.
- **R** — decided: keep `"discover"` for the new `:invoke` step; rename existing session
  step from `"discover"` to `"read"`.  `gh-bug-discover-and-read` is not called as a
  delegate by any workflow that wires from its step names — zero downstream references
  break.  design.md §R added; steps.md `gh-bug-discover-and-read` block updated with
  both step names and the session prompt stripping scope.

## 2026-05-10 — Design review pass 4

**Inconsistencies found:**

1. **Acceptance criteria contradicts §Out of scope on conditional label-add.** The acceptance
   criteria states "All nine listed workflows no longer instruct the AI to perform label changes"
   but design §Out of scope explicitly keeps conditional label add (`waiting` vs `fix`) AI-driven
   in `gh-bug-triage` and `gh-bug-post-repro`. The acceptance criteria must be qualified to
   exclude the conditional label-add case.

2. **Design §K wiring broken for delegate-call case.** `{:from :workflow-input :path [:issue_number]}`
   returns nil when `gh-bug-post-repro` is called as a delegate from `gh-bug-triage-modular`
   because `:workflow-input` is the rendered `prompt-string` (a plain text string, not a map).
   `get-path*` on a string returns nil. The design assumes structured map input but both
   `gh-bug-post-repro` and `gh-bug-request-more-info` receive plain text. The wiring source
   for `:number` in these two workflows is unresolved for the delegate-call case.

3. **Steps.md prompt-stripping underspecified for gh-bug-triage and gh-issue-ingest.** Steps say
   "strip `gh issue list` instruction from AI prompt" but the prompts contain entire discovery
   sections ("Primary selection rule:", "Input expectations:", step 1 "Discover and select/candidate
   issues") that also need stripping after the `:invoke` discover step takes over. The acceptance
   criteria requires stripping ALL shell-based discovery instructions, not just the literal
   `gh issue list` command.

4. **gh-bug-discover-and-read step names unspecified after migration.** The migration adds a
   leading `:invoke` step to a workflow that currently has ONE step named `"discover"`. After
   migration there will be two steps; neither step name is specified in steps.md (unlike §L and §M
   which explicitly name the retained step and note zero downstream changes).

## 2026-05-10 — Design review pass 3 follow-up execution (design-steps K–N)

All four design-steps resolved:

- **K** — decided: wire `:number` from `:workflow-input :path [:issue_number]` in both
  `gh-bug-post-repro` and `gh-bug-request-more-info`.  The upstream `gh-bug-reproduce`
  handoff already emits `issue_number:` as a structured bullet, so no new discover step
  is needed.  design.md §K added; steps.md migration blocks for both workflows updated
  with corrected wiring.
- **L** — decided: keep step name `search` in `gh-issue-implement` after replacing the
  AI delegate with `:invoke github/find-pr`.  Five downstream steps wire from
  `{:step "search" :yield :text}` — keeping the name means zero downstream changes.
  Label-ops `:number` wires from `{:step "search" :output :data}`.  design.md §L added;
  steps.md `gh-issue-implement` block updated.
- **M** — decided: keep step name `select` in `gh-pr-fix-checks` after replacing the AI
  delegate with `:invoke github/find-pr`.  `heal-checks` wires from
  `{:step "select" :yield :text}` — keeping the name means zero downstream changes.  No
  label-ops steps added (PR discovery only).  design.md §M added; steps.md
  `gh-pr-fix-checks` block updated.
- **N** — confirmed: `:input nil` is intentional for `gh-bug-fix-and-pr`.  The `fix`
  label narrows to a single issue; no caller-supplied hint is needed.  design.md §N
  added; no steps.md change required.

## 2026-05-10 — Design review pass 3

**Ambiguities found:**

1. **`gh-bug-post-repro` and `gh-bug-request-more-info` have no discover step** — Both
   workflows have no `:invoke github/find-issue` discover step; the issue number arrives
   only through workflow input (the upstream repro/handoff text). Steps.md wires
   `remove-label :number` as `{:from {:step "discover" ...}}` but there is no `discover`
   step in either workflow. The wiring source for `:number` is unspecified.

2. **`gh-issue-implement` step name mismatch** — The existing AI discovery step is named
   `search` (not `discover`). Steps.md wires label-ops from `{:step "discover" ...}`.
   After migration to `:invoke github/find-pr`, the step name to use (keep `search` or
   rename to `discover`) is unspecified; downstream context wiring in `prep` and
   `implement` also references the old step name.

3. **`gh-pr-fix-checks` step name mismatch** — Existing AI step is named `select`. Steps.md
   wires from `{:step "discover" ...}`. Step name after migration is unspecified.

4. **`gh-bug-fix-and-pr` discover `:input` not wired from workflow input** — The existing
   `discover` step passes `:input nil` rather than `{:from :workflow-input :path [:input]}`.
   Design §D specifies the wiring for `gh-issue-implement` and `gh-pr-fix-checks` but is
   silent on `gh-bug-fix-and-pr`. Intentional (bug/fix issues need no narrowing hint) or
   oversight?

5. **`gh-issue-refine` publish prompt label-change instructions not precisely scoped** —
   Steps.md says "strip label instructions from AI publish prompt" but the publish prompt
   contains two distinct label instructions (remove `refine` from issue; add `waiting` to
   PR) that must both be removed. No explicit call-out of which lines/sentences to strip.

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

## 2026-05-10 — Design review pass 2 follow-up execution (design-steps H–J)

All three design-steps resolved:

- **H** — design.md §data flow expanded with explicit `:path` wiring syntax for both
  `find-issue` (`:path [:issue-number]`) and `find-pr` (`:path [:pr-number]`) cases.
  All Phase 5 steps.md migration items updated with concrete wiring expressions.
  New/existing discover steps must expose `:data {:source :invoke/data}` in `:outputs`.
- **I** — steps.md `gh-issue-refine` and `gh-bug-fix-and-pr` migration blocks each
  gained a sub-item: "Add `:data {:source :invoke/data}` to the existing `discover`
  step `:outputs`."
- **J** — decided: option (a) — update publish delegate prompt to output `pr_number:`
  in `## Handoff Data` and add `:outputs {:data {:source :delegate/handoff}}` to the
  publish step.  Avoids extra `find-pr` round-trip; publish already has the PR number.
  design.md §PR number source added; steps.md `gh-issue-refine` migration block updated
  with two sub-items (update publish step; wire add-label `:number` from publish `:data`).

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

## 2026-05-10 — Implementation complete

All six phases executed:

- **Phase 1** — `psi.github.slug` extracted; `find-issue` rewired. Tests green.
- **Phase 2** — `psi.github.find-pr` implemented; 10 tests; registered.
- **Phase 3** — `psi.github.label-ops` implemented; 14 tests; registered; `extension-test` updated to assert all four ids explicitly.
- **Phase 4** — `clj-kondo` clean (0 errors, 0 warnings); 36 extension unit tests, 117 assertions, 0 failures.
- **Phase 5** — 10 workflows migrated:
  - `gh-bug-discover-and-read`: split into discover (invoke) + read (session)
  - `gh-bug-triage`: leading find-issue + unconditional remove-triage; conditional add stays AI
  - `gh-issue-ingest`: leading find-issue + remove-triage + add-waiting
  - `gh-issue-implement`: search delegate → find-pr invoke + remove-implement + add-review
  - `gh-pr-fix-checks`: select delegate → find-pr invoke (labels: []); no label-ops
  - `gh-bug-post-repro`: input rewired from :report path; remove-triage invoke added; §P
  - `gh-bug-triage-modular`: discover gets :data output; post-repro prompt → :map type; §P
  - `gh-bug-request-more-info`: remove-triage + add-waiting invokes; labels stripped from prompt
  - `gh-issue-refine`: discover gets :data; publish gets :data output + pr_number; remove-refine + add-waiting-pr
  - `gh-bug-fix-and-pr`: discover gets :data; remove-fix invoke; fix-label instructions stripped
- **Phase 6** — Final verification clean; committed.

Notable: `gh-pr-fix-checks` uses `:labels []` (empty) to find all open PRs — no label filter — matching the previous AI step's behavior of selecting any PR needing check healing.

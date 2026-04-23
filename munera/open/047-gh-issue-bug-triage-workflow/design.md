Goal: add a GitHub bug-triage workflow and companion bug-triage skill that can find labeled bug issues, attempt reproduction in an issue-specific worktree, and either request more information or continue through fix-and-PR completion.

Issue provenance:
- branch/worktree intent: `gh-bug-triage`

Intent:
- automate first-response handling for GitHub bug reports that are ready for triage
- make reproduction attempts happen in an isolated issue-specific worktree rather than the current checkout
- distinguish clearly between bugs that are reproducible now and bugs that still need reporter information
- reuse existing Munera-task design and autonomous implementation skills when reproduction succeeds

Context:
- the repository already has:
  - `gh-issue-ingest` workflow for enhancement/feature triage
  - `gh-issue-implement` workflow for implementation-labeled issues
  - `issue-feature-triage` skill for concise issue analysis
  - `munera-task-design` and `work-independently` skills for design and autonomous execution
  - `work-on` command/tool for creating issue-specific worktrees, including explicit base-branch support
- there is not yet a workflow dedicated to bug issues labeled for triage and reproduction
- there is not yet a skill dedicated to bug reproduction triage and evidence gathering

Problem:
- bug reports need a different triage path than enhancement requests
- feature-triage analysis is not sufficient for bug reports because the key first branch is whether the issue can be reproduced
- without a dedicated bug workflow, the reproduction step, label transitions, issue reply content, and handoff into fix implementation remain inconsistent and manual

Desired outcome:
- a new workflow can find open GitHub issues carrying both `triage` and `bug` labels
- the workflow uses a new `issue-bug-triage` skill to:
  - read the selected issue carefully
  - create an issue-specific worktree using `work-on` from `origin/master`
  - attempt to reproduce the reported bug in that worktree
  - capture a concise, concrete reproduction assessment
- when reproduction fails:
  - the workflow posts a GitHub reply that clearly says reproduction did not succeed
  - the reply asks for the minimum additional information most likely to make reproduction possible
  - the workflow removes `triage` and adds `waiting`
  - the workflow stops without creating a Munera task or PR
- when reproduction succeeds:
  - the workflow creates a new Munera task in the issue worktree
  - the workflow uses `munera-task-design` to refine the task design until it is complete and unambiguous
  - the workflow then fixes the bug in that worktree
  - the workflow commits, pushes the branch, and creates a PR that mentions the original issue number
  - the workflow removes the `triage` label from the original issue
  - adding `waiting` on the success path is out of scope and not required

Scope:
In scope:
- add a new skill at `.psi/skills/issue-bug-triage/SKILL.md`
- add a new workflow at `.psi/workflows/gh-bug-triage.md`
- add a prompt/example invocation doc for the workflow
- make the workflow select issues by `triage` + `bug` labels
- make the workflow instruct use of `issue-bug-triage`, `munera-task-design`, and `work-independently`
- make the workflow explicitly use an issue-specific worktree created via `work-on` from `origin/master`
- define the two outcome branches:
  - reproduction failed -> comment + relabel to `waiting`
  - reproduction succeeded -> create Munera task -> fix bug -> push -> PR -> remove `triage`

Out of scope:
- adding new runtime support for GitHub or worktree operations
- changing `work-on` semantics
- changing Munera task protocol
- implementing a general bug-classification schema beyond what the new skill needs
- forcing every successful reproduction to add more labels than requested
- changing existing enhancement-ingest or implementation workflow behavior

Canonical workflow behavior:
1. discover open GitHub issues with both `triage` and `bug` labels
2. select one issue, preferring ascending issue-number order unless input narrows the target
3. read the issue in detail
4. use `issue-bug-triage`
5. inside that skill-driven reproduction phase:
   - fetch `origin/master`
   - create an issue-specific worktree with `work-on` using `base_branch origin/master`
   - treat the returned worktree path as authoritative
   - attempt reproduction in that isolated worktree
   - produce one of two explicit outcomes:
     - reproducible
     - not reproducible with current information
6. if not reproducible:
   - post a concise request-for-information comment tailored to reproduction needs
   - remove `triage`
   - add `waiting`
   - stop
7. if reproducible:
   - create a new Munera task in the issue worktree using the next canonical task id
   - record issue provenance in task files
   - refine the task design with `munera-task-design`
   - when design is clean, implement the fix autonomously with `work-independently`
   - run relevant verification
   - commit and push the branch
   - create a PR mentioning the original issue number, ideally with `Closes #<n>` when appropriate
   - remove `triage` from the issue

Skill contract for `issue-bug-triage`:
- the skill is for bug reports, not feature requests
- it should stay grounded in the issue as written and in observed reproduction evidence
- it should create/use an issue-specific worktree before attempting reproduction
- it should extract and structure:
  - reported behavior
  - expected behavior if inferable from the issue
  - attempted reproduction steps
  - observed reproduction result
  - reproduction status: reproducible | not-yet-reproducible
  - missing information most likely to unblock reproduction
- on failed reproduction, it should help shape a concise GitHub reply requesting only the most useful missing information
- on successful reproduction, it should provide enough concrete reproduction evidence to seed a Munera bug-fix task design

Acceptance:
- `.psi/skills/issue-bug-triage/SKILL.md` exists and clearly describes bug reproduction triage in an issue-specific worktree
- `.psi/workflows/gh-bug-triage.md` exists and targets GitHub issues labeled `triage` and `bug`
- the workflow explicitly uses the new skill for reproduction
- the workflow explicitly branches on reproduction failure vs success
- the failure branch posts a GitHub comment requesting reproduction-helping information, removes `triage`, and adds `waiting`
- the success branch creates a Munera task, uses `munera-task-design`, fixes the bug, pushes a branch, creates a PR mentioning the original issue number, and removes `triage`
- the workflow uses the issue-specific worktree path returned from `work-on` as the authoritative location for edits and git commands
- the enhancement-ingest workflow and existing issue-feature-triage skill remain unchanged

Why this design is complete and unambiguous:
- it defines one issue-selection rule, one reproduction gate, and two explicit outcome branches
- it names the exact labels involved on each branch
- it reuses existing repository capabilities instead of requiring new runtime behavior
- it separates bug reproduction triage from later fix implementation while still connecting them on the reproducible path

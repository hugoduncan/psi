# Design follow-up steps

Unchecked items added by design review pass 1 (2026-05-10).

- [x] **A. Fix contradictory conditional-label steps in steps.md.** Steps for `gh-bug-triage` and `gh-bug-post-repro` say "Add trailing `:invoke github/add-label` (add `waiting` or `fix`, determined by AI classify output)" — but implementation.md correctly notes the conditional add must stay AI-driven until the IR gains conditional branching. Split each item into: (a) an unconditional `:invoke github/remove-label` (remove `triage`) step that IS deterministic, and (b) a note that the conditional add (`waiting` vs `fix`) remains AI-driven and is explicitly out of scope for this task. Update design.md scope table accordingly.

- [x] **B. Decide `psi.github.slug` extraction vs inline copy.** Design says "extract to `psi.github.slug` shared ns, or inline a parallel copy." Pick one and document. Recommendation: extract to `psi.github.slug` — eliminates duplication, is a one-file change, and is the approach already described first in the design. Update design.md and steps.md Phase 1 to reflect the decision unambiguously.

- [x] **C. Specify `find-pr` URL narrowing regex.** Design says "same narrowing logic as `find-issue`" but `find-issue` uses `#"/issues/(\d+)"`. PRs live at `/pull/NNN`. Add an explicit note in design.md that `find-pr` uses `#"/pull/(\d+)"` for URL extraction (not `/issues/`). Add a corresponding unit test case in steps.md Phase 2.

- [x] **D. Specify `:input` wiring for `find-pr` in migrated workflows.** Design specifies `find-pr` args as `{:labels [...] :input nil-or-hint :state "open"}` but does not say how `:input` is sourced from `:workflow-input` in `gh-issue-implement` and `gh-pr-fix-checks`. Specify in design.md: `:input {:from :workflow-input :path [:input]}` (matching the `find-issue` pattern), so the narrowing hint passes through from the workflow caller.

- [x] **E. Clarify `gh-bug-triage` migration vs `gh-bug-triage-modular`.** `gh-bug-triage-modular` already uses `:invoke github/find-issue` for discovery. Design does not state whether `gh-bug-triage` (monolithic) should be migrated, deprecated, or left as-is. Add a sentence to design.md scope clarifying the intent (e.g. migrate `gh-bug-triage` for label-mutation steps only; discovery migration is already done in the modular variant; the monolithic variant is not deprecated here).

- [x] **F. Tighten `extension-test` update spec.** Steps say "Update `extension-test` to assert four registrations." Specify that the test must assert all four operation ids explicitly (`"github/find-issue"`, `"github/find-pr"`, `"github/add-label"`, `"github/remove-label"`) — not just `(= 4 (count @calls*))`. Update steps.md Phase 3 item accordingly.

- [x] **G. Create `plan.md`.** Task has no `plan.md`. Munera requires one before execution. Write it now.

Unchecked items added by design review pass 2 (2026-05-10).

- [x] **H. Specify :path wiring for label-ops :number arg.** Design data flow says label-ops receive `:number from upstream :data`, but find-issue outputs `:issue-number` and find-pr outputs `:pr-number`. The wiring requires `{:from {:step "discover" :output :data} :path [:issue-number]}` or `{:path [:pr-number]}` respectively. Add explicit wiring syntax to design.md §data flow and to the relevant steps.md Phase 5 migration items for each workflow.

- [x] **I. Add :data to :outputs for existing find-issue steps in gh-issue-refine and gh-bug-fix-and-pr.** Both workflows already have a `find-issue` step that only declares `:outputs {:summary ...}`. Label-ops steps need `:data` from that step. Add a sub-item to each workflow's steps.md migration block: "Add `:data {:source :invoke/data}` to the discover step `:outputs`."

- [x] **J. Resolve PR number source for gh-issue-refine add-label (PR target).** The `publish` step creates the PR inside a delegate; its handoff text includes `pr_url` but no structured `:data` with `:pr-number`. The add-label step (`:target "pr"`) has no upstream `:data` source for `:number`. Design.md must specify how to obtain the PR number — options include: (a) update the publish step prompt to output `pr_number:` in handoff data and add `:outputs {:data {:source :delegate/handoff}}` to the publish step so downstream steps can wire from it; or (b) use a separate `find-pr` step after publish to look up the PR by branch name. Pick one and update design.md and steps.md accordingly.

Unchecked items added by design review pass 3 (2026-05-10).

- [x] **K. Specify :number wiring source for gh-bug-post-repro and gh-bug-request-more-info label-ops steps.** Neither workflow has a discover step. The issue number arrives only through workflow input (the upstream repro/handoff text). Steps.md currently wires `remove-label :number` as `{:from {:step "discover" ...}}` but no such step exists in these workflows. Specify the correct wiring source — options: (a) parse issue number from workflow input via a structured path (`:from :workflow-input :path [:issue_number]` if the handoff is structured), or (b) add a leading `:invoke github/find-issue` discover step to each workflow before the label-ops steps. Update design.md scope and steps.md migration blocks for both workflows.

- [x] **L. Specify step name to use after migrating gh-issue-implement discovery.** The current AI discovery step is named `search`. After replacing it with `:invoke github/find-pr`, the step name must be decided: keep `search` or rename to `discover`. All downstream context wiring in `prep`, `design`, `implement`, `review`, and `push` steps references `{:step "search" :yield :text}` — if renamed, all those references must update. Steps.md must state the chosen step name explicitly and list all wiring references that need updating.

- [x] **M. Specify step name to use after migrating gh-pr-fix-checks discovery.** The current AI discovery step is named `select`. After replacing it with `:invoke github/find-pr`, the step name must be decided: keep `select` or rename to `discover`. The `heal-checks` delegate step wires from `{:step "select" :yield :text}` — if renamed, that reference must update. Steps.md must state the chosen step name and list the wiring references that need updating.

- [x] **N. Clarify whether gh-bug-fix-and-pr discover :input should be wired from workflow input.** The existing discover step passes `:input nil`. Design §D specifies `:input {:from :workflow-input :path [:input]}` for `gh-issue-implement` and `gh-pr-fix-checks` but is silent on `gh-bug-fix-and-pr`. If narrowing hints are not needed for bug/fix issues (label already narrows to one), document that `:input nil` is intentional. If they are needed, add the wiring. Update design.md §D or add a note to the `gh-bug-fix-and-pr` migration block in steps.md.

Unchecked items added by design review pass 4 (2026-05-10).

- [x] **O. Qualify acceptance criteria for conditional label-add.** The acceptance criteria states
  "All nine listed workflows no longer instruct the AI to perform label changes" but design §Out of
  scope explicitly keeps conditional label add (`waiting` vs `fix`) AI-driven in `gh-bug-triage`
  and `gh-bug-post-repro`. Update the acceptance criteria to read "…no longer instruct the AI to
  perform *unconditional* label changes or shell-based discovery; conditional label-add in
  `gh-bug-triage` and `gh-bug-post-repro` remains AI-driven per §Out of scope."

- [x] **P. Resolve §K :number wiring for gh-bug-post-repro and gh-bug-request-more-info delegate case.**
  Design §K wires `:number` as `{:from :workflow-input :path [:issue_number]}`. When
  `gh-bug-post-repro` is called as a delegate from `gh-bug-triage-modular`, its `:workflow-input`
  is the rendered `prompt-string` (a plain text string). `get-path*` on a string returns nil, so
  the wiring silently produces nil. Options: (a) expose `:issue_number` from the `classify` session
  step's output by adding `:outputs {:data {:source :session/handoff}}` (requires `classify` to emit
  `issue_number:` in its handoff); (b) change `gh-bug-triage-modular`'s `post-repro` delegate step
  to pass structured input `{:issue_number N :report "..."}` and update `gh-bug-post-repro`'s
  session step to use `{:from :workflow-input :path [:report]}` for `{{input}}`; (c) wire from
  `gh-bug-reproduce`'s parsed handoff data via `context` (already present in `gh-bug-triage-modular`
  as `:output :handoff`). Pick one approach, update design.md §K, and update steps.md migration
  blocks for both workflows.

- [x] **Q. Tighten steps.md prompt-stripping spec for gh-bug-triage and gh-issue-ingest.** Steps
  currently say "Strip `gh issue list` instruction from AI prompt" but the prompts contain entire
  discovery sections that must also be removed: "Primary selection rule:", "Input expectations:",
  and the discovery step in "Required procedure:" (step 1 "Discover and select the issue" /
  "Discover candidate issues"). Update both migration blocks in steps.md to say: strip the
  "Primary selection rule" paragraph, the "Input expectations" paragraph, and step 1 of "Required
  procedure" (the AI session receives the selected issue from the upstream `:invoke` step and
  should start from reading, not discovering).

- [x] **R. Specify step names for gh-bug-discover-and-read migration.** The workflow currently has
  one step named `"discover"` (a session). After migration it will have two steps: a leading
  `:invoke` step and a session step. Steps.md must name both steps explicitly (e.g. keep `"discover"`
  for the `:invoke` step and rename the session to `"read"`, or vice versa) and confirm no other
  workflow wires from `gh-bug-discover-and-read` step names (it is not called as a delegate, so
  there are no downstream references to break). Update the `gh-bug-discover-and-read` migration
  block in steps.md.

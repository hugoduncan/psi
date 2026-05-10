# Design follow-up steps

Unchecked items added by design review pass 1 (2026-05-10).

- [x] **A. Fix contradictory conditional-label steps in steps.md.** Steps for `gh-bug-triage` and `gh-bug-post-repro` say "Add trailing `:invoke github/add-label` (add `waiting` or `fix`, determined by AI classify output)" — but implementation.md correctly notes the conditional add must stay AI-driven until the IR gains conditional branching. Split each item into: (a) an unconditional `:invoke github/remove-label` (remove `triage`) step that IS deterministic, and (b) a note that the conditional add (`waiting` vs `fix`) remains AI-driven and is explicitly out of scope for this task. Update design.md scope table accordingly.

- [x] **B. Decide `psi.github.slug` extraction vs inline copy.** Design says "extract to `psi.github.slug` shared ns, or inline a parallel copy." Pick one and document. Recommendation: extract to `psi.github.slug` — eliminates duplication, is a one-file change, and is the approach already described first in the design. Update design.md and steps.md Phase 1 to reflect the decision unambiguously.

- [x] **C. Specify `find-pr` URL narrowing regex.** Design says "same narrowing logic as `find-issue`" but `find-issue` uses `#"/issues/(\d+)"`. PRs live at `/pull/NNN`. Add an explicit note in design.md that `find-pr` uses `#"/pull/(\d+)"` for URL extraction (not `/issues/`). Add a corresponding unit test case in steps.md Phase 2.

- [x] **D. Specify `:input` wiring for `find-pr` in migrated workflows.** Design specifies `find-pr` args as `{:labels [...] :input nil-or-hint :state "open"}` but does not say how `:input` is sourced from `:workflow-input` in `gh-issue-implement` and `gh-pr-fix-checks`. Specify in design.md: `:input {:from :workflow-input :path [:input]}` (matching the `find-issue` pattern), so the narrowing hint passes through from the workflow caller.

- [x] **E. Clarify `gh-bug-triage` migration vs `gh-bug-triage-modular`.** `gh-bug-triage-modular` already uses `:invoke github/find-issue` for discovery. Design does not state whether `gh-bug-triage` (monolithic) should be migrated, deprecated, or left as-is. Add a sentence to design.md scope clarifying the intent (e.g. migrate `gh-bug-triage` for label-mutation steps only; discovery migration is already done in the modular variant; the monolithic variant is not deprecated here).

- [x] **F. Tighten `extension-test` update spec.** Steps say "Update `extension-test` to assert four registrations." Specify that the test must assert all four operation ids explicitly (`"github/find-issue"`, `"github/find-pr"`, `"github/add-label"`, `"github/remove-label"`) — not just `(= 4 (count @calls*))`. Update steps.md Phase 3 item accordingly.

- [x] **G. Create `plan.md`.** Task has no `plan.md`. Munera requires one before execution. Write it now.

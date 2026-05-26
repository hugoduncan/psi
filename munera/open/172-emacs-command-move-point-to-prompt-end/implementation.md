# Implementation notes

- 2026-05-25 ambiguity review: found actionable design ambiguity. The task only had `design.md`; `plan.md`, `steps.md`, and `design-steps.md` were absent. The design does not name the exact public `M-x` command symbol/autoload location, does not define the outside-Psi-buffer behavior, and leaves open whether the command should use existing `psi-emacs--focus-input-area` window-point synchronization or only move current-buffer point. Added follow-up items to `design-steps.md`; implementation artifacts still need to be created before build.

- 2026-05-25 ambiguity follow-up execution: resolved the newly added design follow-ups without executing implementation `steps.md`. Design now names public command `psi-emacs-move-point-to-prompt-end`, locates it in `components/emacs-ui/psi-entry.el` with autoload discovery, specifies `user-error` outside initialized Psi session buffers, and requires delegation to `psi-emacs--focus-input-area` so visible window points synchronize. Created `plan.md` and `steps.md`; marked all design-steps complete.

- 2026-05-25 inconsistency review: found no new actionable inconsistency feedback after checking design.md, plan.md, steps.md, design-steps.md, implementation.md, current `components/emacs-ui/psi-entry.el`, and related Emacs focus/window-point tests. Task artifacts are aligned on command name/autoload location, outside-session `user-error`, helper delegation, window-point synchronization, and focused test scope.

- 2026-05-25 inconsistency follow-up execution: preloaded inconsistency review added no actionable design follow-up items. `design-steps.md` was already fully checked, so no task implementation `steps.md` items were executed; task artifacts remain aligned.

- 2026-05-25 ambiguity review repeat: found no new actionable ambiguity feedback after rechecking design.md, plan.md, steps.md, design-steps.md, implementation.md, current `components/emacs-ui/psi-entry.el`/`psi.el`, Emacs frontend README, and existing focus/draft/window-point tests. Task artifacts remain clear on command name, `psi-entry.el` autoloaded location, outside-session `user-error`, helper delegation, prompt preservation, and selected/additional visible window point expectations.

- 2026-05-25 ambiguity follow-up execution repeat: preloaded ambiguity review added no actionable design follow-up items. `design-steps.md` was already fully checked, so no task implementation `steps.md` items were executed; task artifacts remain unchanged.

- 2026-05-25 inconsistency review repeat 2: found no new actionable inconsistency feedback after rechecking design.md, plan.md, steps.md, design-steps.md, implementation.md, current `components/emacs-ui/psi-entry.el`/`psi.el`, Emacs frontend README, and related focus/window-point tests. Task artifacts remain aligned on command symbol, `psi-entry.el` autoload location, outside-session `user-error`, helper delegation, prompt preservation, visible-window synchronization, and focused test scope.

- 2026-05-25 inconsistency follow-up execution repeat 2: preloaded inconsistency review added no actionable design follow-up items. `design-steps.md` was already fully checked, so no task implementation `steps.md` items were executed; task artifacts remain aligned.

- 2026-05-25 ambiguity review repeat 2: found no new actionable ambiguity feedback after rechecking design.md, plan.md, steps.md, design-steps.md, implementation.md, current `components/emacs-ui/psi-entry.el`, related focus/window-point tests, and Emacs frontend docs. Task artifacts remain clear on command symbol/autoload location, outside-session `user-error`, helper delegation, prompt preservation, visible-window synchronization, and focused test scope.

- 2026-05-25 ambiguity follow-up execution repeat 2: preloaded ambiguity review added no actionable design follow-up items. `design-steps.md` was already fully checked, so no task implementation `steps.md` items were executed; task artifacts remain unchanged.

- 2026-05-25 inconsistency review repeat 3: found one new actionable inconsistency. Design acceptance criterion 6 requires existing prompt submission and editing behavior to still work after the command runs, but plan.md and steps.md only call out empty/non-empty/output/error/window-point tests and do not include any verification or explicit narrowing for post-command editing/submission behavior. Added a design follow-up to align the verification scope.

- 2026-05-26 inconsistency follow-up execution repeat 3: completed the newly added design follow-up by keeping acceptance criterion 6 intact and aligning plan.md/steps.md to require an explicit post-command prompt editing/submission smoke check. No task implementation steps were executed.

- 2026-05-26 ambiguity review repeat 3: found one new actionable ambiguity after rechecking design.md, plan.md, steps.md, design-steps.md, implementation.md, current `components/emacs-ui/psi-entry.el`/`psi.el`, Emacs frontend README, and `doc/emacs-ui.md`. The task adds a user-visible `M-x` command, but artifacts do not decide whether user docs must be updated alongside code/tests, despite current docs listing frontend entry/compose commands. Added a design follow-up to choose the docs scope.

- 2026-05-26 ambiguity follow-up execution repeat 3: chose to update user-facing Emacs frontend docs rather than mark docs out of scope. Design now requires docs that enumerate commands to mention `M-x psi-emacs-move-point-to-prompt-end`; plan and implementation steps include updating `components/emacs-ui/README.md` and `doc/emacs-ui.md`. The design-step is checked; no implementation `steps.md` items were executed.

- 2026-05-26 inconsistency review repeat 4: found no new actionable inconsistency feedback after rechecking design.md, plan.md, steps.md, design-steps.md, implementation.md, current `components/emacs-ui/psi-entry.el`, related focus/window-point tests, and Emacs frontend docs. Task artifacts remain aligned on command symbol/autoload location, outside-session `user-error`, helper delegation/window-point synchronization, prompt editing/submission smoke coverage, and user-doc update scope.

- 2026-05-26 inconsistency follow-up execution repeat 4: preloaded inconsistency review added no actionable design follow-up items. `design-steps.md` was already fully checked, so no task implementation `steps.md` items were executed; task artifacts remain aligned.

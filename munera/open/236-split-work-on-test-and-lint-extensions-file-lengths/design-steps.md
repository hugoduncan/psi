# Design review follow-ups

- [ ] SCOPE_QUESTION: `bb commit-check:file-lengths` behavior changes are command behavior changes, while AGENTS.md says user-visible command/behaviour changes need changelog/user-doc synchronization; should this frozen slice include the required documentation/changelog artifact work, or is this commit-check task considered internal enough to omit it?
- [ ] Clarify the intended split boundary/naming for `work_on_test.clj` before planning, so the implementation has cohesive target test namespaces/files rather than choosing an ad hoc line-count split.
- [ ] Clarify whether `bb commit-check:file-lengths` is expected to pass after this task or to fail on pre-existing out-of-scope oversized `extensions/` files once `extensions/` is added to the scan.
- [ ] Reconcile design.md's exact `work_on_test.clj` line-count context with the current file: design.md says 1292 lines, but the referenced file is currently 1298 lines.

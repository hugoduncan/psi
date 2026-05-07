Initialized on 2026-05-06.

Coordination note

- This task follows `097-session-state-component-extraction-from-agent-session` and `098-journal-append-dispatch-effect-convergence`.
- It is intentionally a narrow extraction slice.
- The authoritative first-cut target is the journal codec/store layer currently mixed into `psi.agent-session.persistence`.
- The task should preserve the higher-level session-facing persistence API shape as much as practical while relocating low-level codec/store authority.

Execution notes to capture during implementation

- final namespace split and dependency direction
- exact helpers moved into `psi.session-journal.codec`
- exact helpers moved into `psi.session-journal.store`
- exact helpers intentionally retained in `psi.agent-session.persistence`
- whether any in-memory journal helpers moved after all, and why
- focused verification commands and results

# 164 — TUI idle polling elimination

## Notes
- Task created from investigation of persistent idle CPU use in TUI sessions.
- Initial root-cause hypothesis: idle `poll-cmd` reschedules synthetic `:agent-poll` indefinitely, and each wakeup runs `update-tick-state` work.
- Record here which behaviors were previously coupled to idle polling, which were preserved, and how their triggers changed.
- 2026-05-21 ambiguity review: actionable feedback found. The task currently requires follow-up items to be added to `design-steps.md`, but the task artifacts do not themselves declare that review/follow-up surface, so the canonical place for ambiguity follow-ups is implicit rather than explicit. The design also leaves two execution-shaping ambiguities open: it does not choose the authoritative idle trigger rule for notification-dismiss maintenance after idle self-polling is removed, and it does not define the minimum authoritative focused proof set / exact verification targets for the pre-change tick-provided behaviors, leaving completion open to materially different interpretations.

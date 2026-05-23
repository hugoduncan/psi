# Implementation notes

- 2026-05-22 ambiguity review: design is directionally clear, but expanded call-detail source/format still needs tightening before planning. New follow-ups added to `design-steps.md` for raw-vs-structured argument precedence, Emacs/TUI layout parity, and extension renderer behavior.


- 2026-05-22 ambiguity follow-up execution: completed all newly added design follow-ups. `design.md` now defines full-call data precedence with raw fallback for invalid/partial parses, the shared Emacs/TUI expanded layout contract (`Call` before response details, header remains separate, nil/empty explicit, no summary truncation), and the rule that tool-specific/extension renderers may not replace generic auditable call details unless they provide the same complete call plus raw fallback. Marked the three ambiguity design-steps done.

- 2026-05-23 inconsistency review: found the task has refined `design.md`/completed `design-steps.md` but no `plan.md` or `steps.md`, which conflicts with Munera required pre-execution artifacts and leaves the implementation/test checklist absent despite design acceptance requiring focused Emacs and TUI coverage. Added one follow-up to create the missing execution artifacts before implementation.

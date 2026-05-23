# Implementation notes

- 2026-05-22 ambiguity review: design is directionally clear, but expanded call-detail source/format still needs tightening before planning. New follow-ups added to `design-steps.md` for raw-vs-structured argument precedence, Emacs/TUI layout parity, and extension renderer behavior.


- 2026-05-22 ambiguity follow-up execution: completed all newly added design follow-ups. `design.md` now defines full-call data precedence with raw fallback for invalid/partial parses, the shared Emacs/TUI expanded layout contract (`Call` before response details, header remains separate, nil/empty explicit, no summary truncation), and the rule that tool-specific/extension renderers may not replace generic auditable call details unless they provide the same complete call plus raw fallback. Marked the three ambiguity design-steps done.

- 2026-05-23 inconsistency review: found the task has refined `design.md`/completed `design-steps.md` but no `plan.md` or `steps.md`, which conflicts with Munera required pre-execution artifacts and leaves the implementation/test checklist absent despite design acceptance requiring focused Emacs and TUI coverage. Added one follow-up to create the missing execution artifacts before implementation.

- 2026-05-23 inconsistency follow-up execution: created `plan.md` and `steps.md` before implementation. The plan now defines the Emacs/TUI rendering approach, data-precedence decisions, risks, and verification strategy aligned with the finalized acceptance criteria. The implementation checklist now covers Emacs and TUI inspection, rendering, focused coverage, and verification. Marked the new design-step done.

- 2026-05-23 ambiguity review: found one remaining actionable ambiguity. The design requires parsed arguments to be treated as absent/incomplete/partial/suspected-partial in some cases, but does not define a concrete frontend-detectable completeness rule, so Emacs and TUI implementers could diverge on when to render parsed only vs parsed plus raw fallback. Added a design-step to make that decision explicit.

- 2026-05-23 ambiguity follow-up execution: completed the parsed/raw completeness design-step. `design.md` now defines trusted complete argument fields, excludes summary/preview/ellipsized display text from completeness, specifies when parsed-only versus parsed-plus-raw versus raw fallback rendering is required, and aligns live `tool/executing`/rehydrated canonical argument fields across Emacs and TUI. Marked the design-step done.

- 2026-05-23 inconsistency review: found one new actionable inconsistency. `design.md` acceptance requires toggling the same row closed to remove expanded call/response details for frontends that support closing detail rows, and TUI `ctrl+o` does support closing, but `plan.md`/`steps.md` only require TUI collapsed+expanded coverage while Emacs gets explicit toggled-closed coverage. Added a design-step to align TUI verification with the close behavior or explicitly document why existing coverage is sufficient.

- 2026-05-23 inconsistency follow-up execution: aligned TUI verification with the close-toggle acceptance criterion by requiring focused TUI collapsed, expanded/detail, and toggled-closed coverage in `design.md`, `plan.md`, and `steps.md`. Marked the design-step done; implementation `steps.md` items remain unexecuted as requested.

- 2026-05-23 ambiguity review: found one new actionable ambiguity. The design and acceptance describe `C-c C-t`/tool-detail toggling in row-local terms ("same row closed"), while the current Emacs implementation and TUI state use a global tools-expanded mode. Added a design-step to decide whether the change must preserve global expand/collapse semantics or introduce/require row-local detail state, and to align acceptance/tests accordingly.

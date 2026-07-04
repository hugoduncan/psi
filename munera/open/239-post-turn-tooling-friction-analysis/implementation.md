- architectural review (design-review turn 1) added 1 new design step: the
  planned recursion-guard atom copies the extension-local `helper-session-ids`
  pattern rather than the ctx-keyed managed-service model documented in
  `ramora/META.md`. Reviewed against AGENTS.md, ramora/META.md, and
  doc/architecture.md; autonomous task-file creation itself (writing
  `munera/open/NNN-slug/design.md` directly, outside `:state*`/dispatch) is
  not flagged — munera task files are git-tracked project artifacts, not
  canonical root state, and the write mechanism is left to planning.
- ambiguity review (design-review turn 2) added 2 new design steps: (1) the
  per-run task-creation cap is stated only as a suggested range ("1–2"), not
  a decided value, despite acceptance criterion 6 requiring a cap; (2) the
  recursion-guard scope ("except the extension's own helper sessions") leaves
  open whether other extensions'/runtime infra helper sessions (e.g.
  entity-resolution helpers) should also be excluded as analysis inputs.

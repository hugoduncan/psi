- architectural review (design-review turn 1) added 1 new design step: the
  planned recursion-guard atom copies the extension-local `helper-session-ids`
  pattern rather than the ctx-keyed managed-service model documented in
  `ramora/META.md`. Reviewed against AGENTS.md, ramora/META.md, and
  doc/architecture.md; autonomous task-file creation itself (writing
  `munera/open/NNN-slug/design.md` directly, outside `:state*`/dispatch) is
  not flagged — munera task files are git-tracked project artifacts, not
  canonical root state, and the write mechanism is left to planning.

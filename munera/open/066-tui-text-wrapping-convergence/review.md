Terse review

Verdict: good and closeable.

Matches the design well, stays within the existing TUI render architecture, and improves simplicity by making width behavior explicit per surface. The policy artifact is useful and the proof is strong at both unit and representative tmux levels.

Optional follow-up shaping:
- data-drive `render-banner` to reduce repetition
- consider a tiny style-aware wrapped-line helper if another surface repeats the same shape
- tighten proof references in `doc/tui-text-width-policy.md` for secondary rows

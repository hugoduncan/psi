💡 `git check-ignore` NEVER reports paths already tracked in the index (exits 1
with or without `-v`), so a test asserting "not ignored" against a tracked file
is vacuous — it passes whether or not the ignore negation exists. Probe an
untracked (even nonexistent — check-ignore evaluates paths regardless of
existence) path under the target dir instead.

Two exit-code traps in the same tool:
- `-v` on an untracked path whose last matching rule is a NEGATION prints the
  negation pattern and exits 0 — so with `-v`, exit 0 means "a pattern matched",
  NOT "path is ignored"; the negated-vs-ignored distinction lives only in the
  printed pattern. Plain `check-ignore` (no `-v`) exits 1 for a negation.
- Fatal git errors exit 128, which a naive `(not (zero? exit))` conflates with
  the intended not-ignored exit 1 — a dead git would falsely pass a "not
  ignored" arm. Assert exact exit codes and keep `-v`/`no -v` semantics in mind.

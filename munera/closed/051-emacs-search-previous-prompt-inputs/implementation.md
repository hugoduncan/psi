# Implementation: Emacs search previous prompt inputs

## Review notes (post-close, 2026-04-28)

Three issues found in review:

1. **`completing-read` instead of `psi-emacs--ordered-completing-read`** — bare
   `completing-read` allows completion UIs (vertico, ivy) to re-sort candidates
   alphabetically, destroying recency order.  Design called out
   `psi-emacs--ordered-completing-read` as the pattern to follow.

2. **AC4 stash/M-n semantics diverge from design** — design says "M-n can
   recover the pre-search draft"; actual behaviour: M-n immediately after
   selection errors (index is nil), because M-p overwrites the stash on first
   use.  Design AC4 needs updating to match reality.

3. **Dead-code empty-string guard** — `(not (string-empty-p chosen))` is
   unreachable when `REQUIRE-MATCH t` is passed to `completing-read`; kept as
   a safety net against frameworks that return `""` on cancel, but should be
   commented to explain intent.

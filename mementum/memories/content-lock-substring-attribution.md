🔁 When you change text a workflow *emits*, content-lock tests (substring `.contains`
assertions over the loaded `select-text`) break — and the obvious count is usually an
UNDERCOUNT. A lock substring belongs to whichever emitted bullet actually produces it,
which is not always the bullet it superficially resembles.

Task 215 trap: replacing the net-sum A2 bullet, the plan first named "two" breaking
locks (the literal net-sum strings). A THIRD, `"identified by (ns, var, arity, line)"`,
was emitted ONLY by that A2 bullet and also broke — while the near-identical sibling
`"keyed by (ns, var, arity, line)"` was emitted by the A5 bullet and had to stay intact.
Near-identical substrings can belong to different criteria.

✅ Before editing emitted text: grep EVERY content-lock substring and attribute each to
the exact bullet that emits it (occurrence count matters — `identified by` appeared once
= A2-only; `keyed by` twice = A5 + step-5). Disposition each: remove, re-point, or leave.
Don't trust the plan's headline count.

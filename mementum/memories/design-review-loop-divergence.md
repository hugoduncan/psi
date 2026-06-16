❌ The autonomous review loops (`review-task-design`, and the test-review pass) can DIVERGE instead of converge: the follow-up executor resolves items by *adding* in-place specification, which (a) grows the review surface and (b) manufactures new inconsistencies — an edit that relocates/renames something leaves cross-referenced wording stale, which the next round files as a fresh INC item. The loop ends up reviewing its own output.

Observed on task 229 (dev-http): design review ran 12 rounds, filed ~50 AF/AMB/INC items, bloated `design.md` 10× (9.6 KB → 100 KB) for a small dev-only extension, then failed with `:reason :iteration-exhausted` even though the final judge routed DONE — failing the whole `task-lifecycle`. Same shape later in a 10-round test-review nitpick spiral (string-key variants, embed coverage).

Tells: artifact growth disproportionate to scope; late items only reconcile review-introduced wording.

Remedies: for a human-settled design, skip the autonomous loop and go straight to `create-task-plan`. When divergence happens, reconstruct a tight artifact folding only the genuine early findings (rounds 1–2) and discard the churn. The loops need a marginal-value/size convergence guard; follow-ups should prefer tightening/removing over adding.

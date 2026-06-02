# Design follow-up steps

## Architecture-fit review (ψ)

- [x] Extend the "resolve target buffer/state explicitly" requirement to cover
      the `--dispatch-mutation` RPC **response** callback
      (psi-widget-projection.el:354), not just the timeout callback. The
      response path must cancel/clear against the originating buffer's
      buffer-local timer store (captured `buffer`/`state` + `buffer-live-p`
      guard, mirroring `psi-emacs--schedule-notification-dismiss`), so it cannot
      mutate the wrong buffer's store when a response arrives while another
      buffer is current. Update Scope/Constraints/Acceptance accordingly.

## Ambiguity review (ψ)

- [ ] B1 — Specify the helper signatures and arm-path store-resolution rule.
      Post-change, `--cancel-mutation-timer` (today `(tkey)` against the global
      hash) must locate a buffer-local store and is called from three contexts:
      the inline pre-cancel inside `--arm-mutation-timer` (psi-widget-projection.el:303),
      the response callback (:356), and the timeout callback (:321). Define (a)
      the new cancel/arm helper signatures (which `state`/`buffer` they take),
      and (b) whether the arm path + its inline pre-cancel may use the dynamic
      (then-current) `psi-emacs--state` while only the *callbacks* must use the
      captured `buffer`/`state` — the current "neither callback may dereference
      `psi-emacs--state`" constraint does not cover `--arm` or the shared cancel
      helper's arm-path use. Resolve so a single, unambiguous store-resolution
      rule governs all three call sites. Update Scope/Constraints/Acceptance.

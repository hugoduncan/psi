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

# Design review follow-up steps

## Ambiguity (ψ)

- [ ] A1/A2 unit identity includes `line` (target pinned to `(..., 4, 12)`), but the
  intended helper extraction shifts the `line` of `start-nrepl!` and `stop-nrepl!`
  in `nrepl_runtime.clj`. Specify how the target and the touched-unit set `T` are
  matched across line drift (e.g. match by `(ns, var, arity)` ignoring `line`, or
  pin `start-nrepl!` to line 12).
- [ ] A2 does not define `before(u)` for newly-created helper units absent from
  `before-local.json`. Specify that an absent baseline entry is treated as `0` (or
  otherwise) so the extracted seam's after-burden is counted in `sum_after` and the
  net-burden check stays honest.

# Steps

- [ ] Add built-in packaged skill discovery/materialization path before external directory loading.
- [ ] Replace implicit first-discovered-wins collision retention with explicit precedence-aware winner selection across built-in, user-global, project, and `:extra-paths` sources.
- [ ] Add collision diagnostics that name both the winning and shadowed skill definitions and their sources.
- [ ] Prove deterministic precedence and override behavior with focused discovery tests, including `:extra-paths` and `:disabled` interactions.
- [ ] Prove packaged built-in skills remain readable through ordinary `:file-path` / `:base-dir` semantics in non-source-tree execution.
- [ ] Update user-facing docs for built-in skill shipping, source precedence, overrides, and AI-readable materialized paths.

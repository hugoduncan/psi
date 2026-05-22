# Implementation

Task created from the registry-unification guidance refined in `164` and the migration pattern established by `167` and `168`.

Initial intent:

- migrate `workflow-registry` to `root-registry`
- preserve current workflow-registry public behavior at the adapter boundary
- explicitly inventory higher workflow read/projection seams so stale direct-state reads do not survive the storage move

2026-05-21 ambiguity review:
- Actionable: design/plan do not say whether this migration changes the canonical persisted path `[:workflows :definitions]` or preserves it as a compatibility surface; direct-path consumers exist in `session-state`, `workflow-runtime.model`, and related tests/docs.
- Actionable: seam inventory does not classify extension-runtime `:loaded-definitions`, which remains a higher projection/read surface that may need explicit coherence or explicit out-of-scope treatment.

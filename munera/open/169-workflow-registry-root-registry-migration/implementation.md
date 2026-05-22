# Implementation

Task created from the registry-unification guidance refined in `164` and the migration pattern established by `167` and `168`.

Initial intent:

- migrate `workflow-registry` to `root-registry`
- preserve current workflow-registry public behavior at the adapter boundary
- explicitly inventory higher workflow read/projection seams so stale direct-state reads do not survive the storage move

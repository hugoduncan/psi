# Context Management via Intermediate Representations — Index

Split from a single 5963-word file into scoped sub-files.

## Files

| File | Topic |
|------|-------|
| [overview.md](context-management/overview.md) | Intent, why, problems solved |
| [turn-model.md](context-management/turn-model.md) | Turn definition, turn IDs, turn boundaries |
| [ir-types.md](context-management/ir-types.md) | Five IR type schemas with examples |
| [ir-storage.md](context-management/ir-storage.md) | Storage model, versioning, size budgets, overflow |
| [ir-pruning.md](context-management/ir-pruning.md) | Pruning policy, triggers, strategy, cleanup |
| [session-lifecycle.md](context-management/session-lifecycle.md) | Lifecycle flows for prompt, continue, finish, compaction, resume |
| [core-model-changes.md](context-management/core-model-changes.md) | Journal metadata, session data, ctx functions, dispatch events, extension hooks |
| [extension-design.md](context-management/extension-design.md) | Extension ownership, projection strategy/contract, reply processing, compaction strategy |
| [compaction-invariants.md](context-management/compaction-invariants.md) | Compaction semantics, design invariants |
| [architecture-fit.md](context-management/architecture-fit.md) | VSM alignment, one-way guideline, no shims, migration, risks, decisions |

# Context Management via Intermediate Representations — Index

Split from a single 5963-word file into scoped sub-files.

## Files

| File | Topic |
|------|-------|
| [overview.md](overview.md) | Intent, why, problems solved |
| [turn-model.md](turn-model.md) | Turn definition, turn IDs, turn boundaries |
| [ir-types.md](ir-types.md) | Five IR type schemas with examples |
| [ir-storage.md](ir-storage.md) | Storage model, versioning, size budgets, overflow |
| [ir-pruning.md](ir-pruning.md) | Pruning policy, triggers, strategy, cleanup |
| [session-lifecycle.md](session-lifecycle.md) | Lifecycle flows for prompt, continue, finish, compaction, resume |
| [core-model-changes.md](core-model-changes.md) | Journal metadata, session data, ctx functions, dispatch events, extension hooks |
| [extension-design.md](extension-design.md) | Extension ownership, projection strategy/contract, reply processing, compaction strategy |
| [compaction-invariants.md](compaction-invariants.md) | Compaction semantics, design invariants |
| [architecture-fit.md](architecture-fit.md) | VSM alignment, one-way guideline, no shims, migration, risks, decisions |

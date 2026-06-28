# Context Management — Compaction Semantics & Design Invariants

## Compaction Semantics

IR-based compaction controls future provider projection only. It does not rewrite
the persisted journal. The journal remains the authoritative raw history from
which IRs are rebuilt. Compaction stores extension-owned summary metadata and
projection policy in `:extension-ir-data`; the projector uses that metadata to
replace older raw/semantic turns with compact summaries when building the
provider-neutral conversation.

Because compaction does not mutate journal history, pruning old in-memory IRs is
safe: removed IRs can be rebuilt from the journal if needed. Overflow cache files
may be deleted and recreated.

## Design Invariants

The context-management design depends on these invariants:

1. **Journal is authoritative.** The persisted journal is the durable raw history
   for a session.
2. **IR is derived and rebuildable.** Session IRs are extension-owned semantic
   indexes/projections over the journal, not independent sources of truth.
3. **Core does not interpret IR.** Core may store opaque extension IR maps and
   route lifecycle events, but it must not depend on IR schema semantics.
4. **Projection is the IR-to-neutral-conversation boundary.** The active context
   manager shapes psi's provider-neutral conversation before existing
   provider-specific projection/adaptation runs. Legacy journal projection
   remains the fallback.
5. **Compaction changes projection policy, not journal history.** IR compaction
   stores summaries and retention choices for future projections; it does not
   rewrite persisted journal entries.
6. **Session IR and caches are rebuildable.** Session IR is in-memory and
   rebuilt from the journal; overflow files and Project State IR are local,
   non-version-controlled caches that may be deleted and recreated.
7. **One context manager owns projection.** Only one extension may be the active
   projector for a session. Other extensions may observe IR lifecycle events or
   contribute data, but they do not compete to shape provider context.
8. **State changes go through dispatch.** IR creation, reply processing,
   compaction metadata updates, pruning, and rebuilds use dispatch/mutations.
   The only ctx hook is synchronous projection during request preparation.
9. **Reply processing is ordered per session.** Completed turns are processed in
   increasing turn-id order for each `session-id`.
10. **Projected conversations must be neutral-schema-valid.** Projection must
    preserve psi conversation constraints, especially role ordering and tool-call/
    tool-result adjacency. Provider-specific protocol constraints are handled by
    the existing provider adapter after context projection. If a raw segment
    cannot be included validly in the neutral conversation, the projector must
    replace the whole segment with a summary/context representation rather than
    emit an invalid partial sequence.

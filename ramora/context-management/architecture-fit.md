# Context Management — Architecture Fit, Migration, Risks, Decisions

## VSM Alignment

- **S1 (Operations)**: IR storage is session state; IR builders/projectors are
  extension handlers. Core dispatches events; extension handles them.
- **S2 (Regulation)**: IR schemas are extension-defined; core validates only
  the opaque storage envelope (map shape / extension key), not IR semantics.
  Extension permissions control what state the extension can read.
- **S3 (Coordination)**: Dispatch pipeline feeds IR lifecycle events in the
  right order. Extension handlers should keep state transitions pure and return
  effects-as-data for cache/file/model work; impure work runs at the runtime
  boundary.
- **S4 (Adaptation)**: Extension can evolve IR schemas and projection
  strategies without core changes. EQL introspection exposes IR data.

## One-Way Guideline

IR data flows one way:
- Core → Extension: raw data via dispatch event input or ctx fn call
- Extension → Core: structured IR via handler return or ctx fn return
- Extension → Core: projected provider-neutral conversation via projector return
- Core → Provider adapter: provider-neutral conversation converted by existing
  provider-specific projection

Core never reads IR data for its own purposes. The extension is the sole
consumer and producer of IR semantics.

## No Shims

The IR pipeline replaces the current compaction system rather than wrapping it.
The old `compaction.clj` logic becomes a fallback when no context manager
extension is installed.

## Migration Path

### Phase 1: Core Hooks

1. Add `:extension-ir-data` to session schema
2. Add new dispatch events as no-op handlers (pass-through)
3. Add ctx fn slots (`:ir-project-conversion-fn`, etc.) — nil by default
4. Wire dispatch calls at lifecycle points in existing handlers
5. Wire ctx fn calls in `build-prepared-request` and compaction flow
6. All gated: when ctx fns are nil, legacy behaviour is unchanged

### Phase 2: Extension Implementation

1. Build the context manager extension with IR builders, projector, processor
2. Implement projection strategy with configurable retention
3. Implement out-of-band reply processing
4. Test with live sessions alongside legacy compaction

### Phase 3: Compaction Replacement

1. Implement IR-based compaction in the extension
2. Gate on extension presence: use IR compaction when available, fall back to
   legacy compaction otherwise
3. Validate that IR compaction produces equal or better context quality

### Phase 4: Legacy Retirement

1. Deprecate legacy compaction when IR compaction is stable
2. Remove `session_before_compact` extension hook (replaced by `:session/ir-compact`)
3. Clean up `compaction.clj` (keep message rebuild helpers for journal I/O)

## Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Extension projection is slow | Projector runs in prepare-request path; must be fast. Cache projected state IRs. Timeout fallback to legacy projection. |
| Out-of-band processing races with next turn | IR storage is atomic; next turn reads latest IR. Processing is append-only. If Turn IR is absent, projector falls back to raw journal entries. |
| IR schema drift between extension versions | IRs carry `:ir/version`; projector handles multiple versions. |
| Context manager extension not installed | Core falls back to legacy journal projection and compaction. |
| IR storage grows unbounded | Extension owns retention policy; compaction removes old IRs. |
| Turn IR is incomplete if session crashes | Turn IRs are rebuilt from journal on resume. IRs are derived, not authoritative. |
| IR rebuild on resume is slow | Only rebuild since last compaction boundary. Optimise with incremental extraction. |

## Design Decisions (Resolved Open Questions)

### 1. Session IR is not persisted

Session IRs (turn IRs, user input IRs, session state IR, project events) are
in-memory only. They are stored on the session atom under `:extension-ir-data`
but are excluded from the session file. On resume, the extension rebuilds them
from the journal.

**Rationale**: The journal is the single source of truth. Persisting IRs would
create format coupling between the extension and the session file, and would
require migration logic on every schema change. Rebuilding from the journal is
deterministic and always possible.

### 2. IR versioning uses integer version numbers

Each IR carries `:ir/version` (integer). The extension handles backwards-
compatible versions during projection and processing.

**Rationale**: Simple, explicit, and sufficient. The extension controls both
production and consumption of IRs, so it can manage version compatibility
internally.

### 3. One context manager extension

Only one extension owns the projection pipeline. Other extensions contribute
via event hooks (`ir_turn_extracted`, `ir_reply_processed`, etc.) but do not
register their own projectors.

**Rationale**: Multiple projectors would create ambiguity about what the LLM
sees. A single authority ensures coherent context management. Other extensions
can react to IR lifecycle events without competing for projection control.

### 4. Session state is in the session; project IR is cached

Session State IR lives on the session atom (in-memory, session-scoped).
Project State IR is persisted by the extension as a local, non-version-controlled
cache (file-based by default, shared across sessions in the same worktree).

**Rationale**: Session state is cheap to materialise and is session-specific.
Project state is expensive to materialise (git status, munera state, mementum
state) and is shared across sessions. Caching avoids redundant work.

### 5. Fallback to raw journal entries

When the context manager extension is not installed, or when projection fails,
the projector returns `nil` and core uses the default journal-to-messages
projection. When individual Turn IRs are absent, the projector may fall back to
raw journal entries for those turns while still using IRs for the rest of the
projection.

**Rationale**: The IR pipeline is an enhancement, not a requirement. The
system must always work, even without the context manager extension. Raw
journal entries are always available as a fallback.

### 6. Rebuild IRs on session resume

When a session is resumed from a persisted file, the extension rebuilds all
IRs from the journal entries. This is a synchronous, one-time cost at session
start.

**Rationale**: IRs are in-memory only and must be reconstructed on resume.
Rebuilding from the journal is deterministic and ensures IRs are available for
the first projection. The extension can optimise by only rebuilding since the
last compaction boundary.

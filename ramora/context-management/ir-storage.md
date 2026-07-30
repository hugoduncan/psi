# Context Management — IR Storage

IRs are stored on the session under `:extension-ir-data` as a map:

```clojure
{:extension-ir-data
 {:context-manager
  {:user-input-irs {0 user-input-ir-0
                    1 user-input-ir-1
                    ...}
   :turn-irs       {0 turn-ir-0
                    1 turn-ir-1
                    ...}
   :project-events {event-id-1 project-event-1
                    event-id-2 project-event-2
                    ...}
   :session-state  session-state-ir
   :project-state  project-state-ir
   :projection     {:compaction-summary "..."
                    :compacted-turn-ids #{0 1 2}}}}}
```

The outer key (`:context-manager`) is the active context-manager extension key.
The key is a keyword selected at extension activation time; for a manifest-backed
extension it should be derived from the manifest id. The inner keys are
extension-defined.

Core never reads or interprets these values. The extension queries them via EQL.

**Session IRs are not authoritative persisted state.** They are kept in memory
on the session and are excluded from session-file persistence. On session
resume, the extension rebuilds IRs from the journal entries. The journal is the
single source of truth for persisted session history. This avoids persistence
format coupling and keeps the session file clean.

**Project State IR is persisted as a local cache** (file-based by default)
because it is expensive to materialise and is shared across sessions in the same
worktree. The cache is invalidated on project events or at projection time if
stale. The cache is not version-controlled and is not authoritative; durable
project knowledge belongs in mementum, munera, docs, or source artifacts.

IR instances may contain `:ir/overflow-ref` fields pointing to local cache files when the IR exceeds its configured size budget. These overflow files are rebuildable, non-authoritative cache entries, not version-controlled artifacts. The extension tracks budget metadata and enforces budgets at creation and projection time. See **Size Budgets and Overflow** for details.

The extension also manages a pruning budget for the total IR store, controlling how many IRs are retained and when old ones are removed. See **IR Pruning** for details.

## IR Versioning

Each IR carries `:ir/version` (integer). The extension's projector and processor
handle backwards-compatible versions. When the extension evolves its schema, it
increments the version and supports reading older versions during projection.

Core never inspects `:ir/version` — it is an extension concern.

## Size Budgets and Overflow

Each IR type has a configurable size budget. When an IR instance exceeds its budget, the extension offloads excess detail to an external file and replaces it with a reference.

### Budget Configuration

Budgets are declared in the extension configuration:

```clojure
{:ir-budgets
 {:user-input    {:max-tokens 512  :overflow? true}
  :turn          {:max-tokens 2048 :overflow? true}
  :session-state {:max-tokens 256  :overflow? false}
  :project-event {:max-tokens 256  :overflow? true}
  :project-state {:max-tokens 1024 :overflow? true}}}
```

- `:max-tokens` — soft budget for the IR instance (measured in tokens or bytes)
- `:overflow?` — whether the IR type supports file overflow; when `false`, the extension must compress the IR to fit rather than offload

Budgets are per-IR-instance, not per-collection. Each individual turn IR is checked against the `:turn` budget independently.

### Overflow Mechanism

When an IR exceeds its budget and `:overflow?` is true:

1. The extension identifies the fields contributing most to size (typically `:ir/entities`, `:ir/claims`, `:ir/actions`)
2. Offloads those fields to a file under `.psi/context/irs/{ir-type}/{id}.edn`
3. Replaces the field in the in-memory IR with an overflow reference containing a summary and file path
4. The full detail is available on-demand by reading the file

An IR field with overflow looks like:

```clojure
{:ir/entities
 [{:name "compaction.clj"
   :type :file
   :description "compaction handler"
   :ir/overflow-ref
   {:file ".psi/context/irs/turns/abc-123/entities.edn"
    :summary "3 entities: compaction.clj, journal, rebuild"
    :token-count 1843}}]}
```

The `:ir/overflow-ref` map contains:
- `:file` — relative path to the overflow file
- `:summary` — concise prose summary of the offloaded content
- `:token-count` — size of the offloaded content (for budget tracking)

When the projector needs the full detail (e.g., for a high-fidelity projection under low context pressure), it reads the overflow file. For normal projection, the summary suffices.

### File Storage Layout

Overflow files are stored in the worktree under `.psi/context/irs/`:

```
.psi/context/irs/
  user-inputs/
    {turn-id}.edn
  turns/
    {turn-id}.edn
  events/
    {event-id}.edn
  project-state.edn
```

Files are plain EDN. They are local, rebuildable cache files and are not
version-controlled. The extension is responsible for creating, updating, and
cleaning them up.

### Budget Enforcement

The extension enforces budgets at three points:

1. **On IR creation/update**: after building or updating an IR, check size and overflow if needed
2. **On IR rebuild**: when rebuilding IRs from the journal on session resume, enforce budgets and create overflow files as needed
3. **On projection**: if a projected IR is over budget, compress or overflow before including it

Budget enforcement is an extension concern. Core never measures IR sizes or enforces budgets.

# Context Management via Intermediate Representations

## Intent

Replace the current monolithic journal-as-context model with an extension-owned
IR pipeline. The core session stores structured IR representations of each turn
and of session/project state. An extension (the *context manager*) projects
those IRs into the provider-facing conversation, controls what is retained, and
processes replies out-of-band to maintain derived state.

This gives the extension full authority over context shape, compaction strategy,
and memory extraction — while the core owns only lifecycle hooks, opaque
extension IR storage, and the journal as the rebuildable source of truth.

## Why

Current problems:

- **Journal is the context**: raw messages accumulate; compaction is a blunt
  cut-and-summarise that loses structure.
- **Compaction is core-owned**: the summarisation prompt, cut-point logic, and
  rebuild are baked into `psi.agent-session.compaction`. Extensions can only
  override the final summary via `session_before_compact`.
- **No structured turn semantics**: user input, assistant reply, tool calls,
  and tool results are all the same `:message` journal entry kind. Nothing
  distinguishes intent from observation from side-effect.
- **No project state projection**: the LLM sees conversation history but has no
  structured, up-to-date view of the project (open tasks, git state, file
  changes, mementum state).
- **Reply processing is inline**: anything that wants to react to a reply
  (memory extraction, state updates) must happen synchronously in the turn
  loop or via extension event handlers that see raw messages.

IR-based context management solves these by:

1. Giving each turn a **structured IR** that the extension can query, project,
   and summarise at will.
2. Giving the session and project **state IRs** that the extension can
   materialise into the conversation at the right time.
3. Making the **projection** (what the LLM sees) an extension concern, not a
  core concern.
4. Providing **out-of-band reply processing** so memory extraction and state
   updates don't block the user.

## Turn Model

A **turn** is one complete LLM interaction cycle: user input → LLM response →
tool calls → tool results → LLM response → ... → final response.

In the current code, this is the `prompt-submit` → `prompt` →
`prompt-prepare-request` → `prompt-execute` → (`prompt-continue` → repeat) →
`prompt-finish` lifecycle. One turn may produce multiple journal entries
(assistant messages with tool calls, tool result messages, final assistant
message).

The IR model treats this as **one turn IR** that captures the *semantic content*
of the turn — what was discussed, decided, or discovered — rather than the LLM
mechanics of how it was produced. The journal remains the authoritative record
of raw messages; the turn IR is a structured extraction of meaning.

```
Turn IR:
  user-intent:   what the user wanted (structured)
  entities:      named things mentioned (files, tasks, concepts)
  relationships: how entities relate to each other
  claims:        factual assertions made during the turn
  questions:     questions asked or raised
  decisions:     decisions reached or proposed
  actions:       things done or committed to
  metadata:      turn-id, timestamp, source references
```

This is different from the journal model where each message is a separate entry
with no semantic structure. The IR groups by turn boundary and extracts meaning.


### Turn IDs

Core creates turn ids per session at prompt submission time. Turn ids are
sequential from zero within each `session-id` (for example, turn `0`, `1`, `2`,
...). Every journal entry produced by the prompt/continue/finish lifecycle is
stamped with the current turn id. This makes tool-call continuations and final
assistant messages recoverable as one semantic turn.

On resume, the extension rebuilds Turn IRs by grouping journal entries by the
persisted turn id. For legacy journal entries without turn ids, rebuild uses a
best-effort boundary scan (`user` entry followed by assistant/tool/result entries
until the next user entry) and may assign synthetic sequential ids for rebuilt
IRs.

## IR Types

Core knows only opaque extension IR storage. The first-party context-manager
extension defines five IR types with fixed extension-owned schemas. Core does
not interpret these schemas.

### 1. User Input IR

Built when a user prompt is submitted. Captures the user's intent in structured
form.

```clojure
{:ir/type :user-input
 :ir/version 1
 :ir/turn-id "uuid"
 :ir/timestamp (Instant)
 :ir/raw-text "original user text"
 :ir/commands ["command" ...]          ;; extracted /model, /remember, etc.
 :ir/images [{:type :image :url "..."} ...]
 :ir/expansion {:kind :skill|:template :name "..." :expanded-text "..."}
 :ir/metadata {}}                      ;; extension-owned, e.g. intent classification
```

Built at `:session/prompt-submit` time from the user message and the
`expand-user-message` result. Stored on the session as extension-specific data.

### 2. Turn IR

Built during and after a turn. Captures the semantic content of the conversation
turn — what was discussed, decided, or discovered — rather than the LLM
mechanics of how it was produced.

```clojure
{:ir/type :turn
 :ir/version 1
 :ir/turn-id "uuid"
 :ir/timestamp (Instant)
 :ir/user-input user-input-ir          ;; reference to the User Input IR
 :ir/entities [{:name "..." :type :file|:task|:concept|:person|:custom
                :description "..."
                :references [...]} ...]
 :ir/relationships [{:from "entity-name"
                     :to "entity-name"
                     :type :depends-on|:modifies|:references|:custom
                     :description "..."} ...]
 :ir/claims [{:text "assertion"
              :confidence :high|:medium|:low
              :source :user|:assistant|:tool
              :evidence "..."} ...]
 :ir/questions [{:text "question"
                 :asked-by :user|:assistant
                 :answered? true|false
                 :answer "..."} ...]
 :ir/decisions [{:text "decision"
                 :made-by :user|:assistant|:joint
                 :rationale "..."
                 :status :proposed|:accepted|:rejected} ...]
 :ir/actions [{:text "action taken or committed"
               :type :code-change|:task-update|:research|:custom
               :completed? true|false
               :artifacts [...]} ...]
 :ir/raw-summary "concise prose summary of the turn"
 :ir/metadata {}}
```

Built by the extension during out-of-band reply processing. The extension
analyses the raw turn messages and extracts structured semantic content.
Stored on the session as extension-specific data, keyed by turn-id.

### 3. Session State IR

A snapshot of session-relevant state that the extension wants projected into
context. Re-materialised on demand by the extension at projection time.

```clojure
{:ir/type :session-state
 :ir/version 1
 :ir/timestamp (Instant)
 :ir/session-id "uuid"
 :ir/model {:provider "..." :id "..."}
 :ir/thinking-level :off|:low|:medium|:high
 :ir/context-tokens N
 :ir/context-window N
 :ir/phase :idle|:streaming|:compacting
 :ir/turn-count N
 :ir/tool-ids ["read" "bash" ...]
 :ir/worktree-path "..."
 :ir/git-branch "..."
 :ir/git-dirty? true
 :ir/metadata {}}
```

Built by the extension from EQL queries against session state. The extension
decides when to refresh and what to include. Typically refreshed at projection
time.

### 4. Project Event IR

Discrete events derived from reply processing. These are the extension's way of
recording that something noteworthy happened.

```clojure
{:ir/type :project-event
 :ir/version 1
 :ir/event-id "uuid"
 :ir/timestamp (Instant)
 :ir/event-type :file-changed|:task-advanced|:decision-made|:error-encountered|:custom
 :ir/source-turn-id "uuid"
 :ir/summary "concise description"
 :ir/details {}
 :ir/metadata {}}
```

Generated by the extension during out-of-band reply processing. Stored on the
session as extension-specific data. Triggers re-materialisation of project state
IR.

### 5. Project State IR

A snapshot of project-relevant state. Re-materialised when project events
accumulate or on demand at projection time.

```clojure
{:ir/type :project-state
 :ir/version 1
 :ir/timestamp (Instant)
 :ir/worktree-path "..."
 :ir/git-branch "..."
 :ir/git-status {:clean? false :modified [...] :untracked [...]}
 :ir/open-tasks [{:id "001-slug" :state :open :summary "..."} ...]
 :ir/recent-events [{:event-type :file-changed :summary "..."} ...]
 :ir/mementum-state {:working-memory "..." :recent-memories [...]}
 :ir/metadata {}}
```

Built by the extension from project sources (git, munera, mementum, file system).
The extension decides the refresh cadence and content. Typically refreshed at
projection time.

**Storage**: The extension decides where and how project state is persisted.
The default approach is file-based storage in a local cache under the worktree
(e.g., `.psi/context/project-state.edn`). This cache is **not
version-controlled**. Project State IR is persisted as a rebuildable cache
because it is expensive to materialise and may be shared by sessions in the same
worktree. The extension may choose other backends (database, remote storage) as
needed. Core never reads or interprets project state storage.

## IR Storage Model

IRs are stored on the session under `:extension-ir-data` as a map:

```clojure
{:extension-ir-data
 {:context-manager
  {:turn-irs        {turn-id-1 turn-ir-1
                     turn-id-2 turn-ir-2
                     ...}
   :project-events  [project-event-1 project-event-2 ...]
   :session-state   session-state-ir
   :project-state   project-state-ir
   :compaction-summary "..."}}}}
```

The outer key (`:context-manager`) is the extension path, allowing multiple
extensions to store IR data without collision. The inner keys are extension-defined.

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

## IR Pruning

The extension is responsible for keeping the total IR store within bounds. Pruning manages the IR storage footprint on the session; it is separate from compaction, which manages what the LLM sees in the conversation projection.

### Pruning vs Compaction

| | Compaction | Pruning |
|---|---|---|
| **Purpose** | Reduce context sent to LLM | Reduce IR storage on session |
| **Trigger** | Context window threshold | IR store size threshold, turn completion, periodic |
| **Target** | Conversation projection | Raw IR instances and overflow files |
| **Reversibility** | Journal preserved; IRs can be rebuilt | IRs can be rebuilt from journal; overflow files may be deleted |

Compaction and pruning are often coordinated — when compaction removes old turns from the projection, pruning can remove the corresponding IRs from storage. But they can also operate independently.

### Pruning Policy

Pruning policy is declared in the extension configuration:

```clojure
{:ir-pruning
 {:max-turn-irs        50
  :max-project-events  100
  :max-total-ir-tokens 50000
  :prune-on            [:turn-complete :compaction :threshold]
  :retention
  {:turn-irs        :by-relevance
   :project-events  :by-age}}}
```

- `:max-turn-irs` — maximum number of turn IRs to retain
- `:max-project-events` — maximum number of project event IRs to retain
- `:max-total-ir-tokens` — maximum total token count across all IRs
- `:prune-on` — events that trigger pruning
- `:retention` — strategy for selecting which IRs to keep per type

### Pruning Triggers

Pruning runs on:

1. **Turn completion** — lightweight check after each turn; prunes if counts are exceeded
2. **Compaction** — coordinated with IR compaction; removes IRs for turns that were compacted away
3. **Threshold** — when total IR token count exceeds `:max-total-ir-tokens`
4. **Periodic** — configurable interval (e.g., every N turns)

### Pruning Strategy

The extension scores IRs by relevance and removes the lowest-scoring first:

1. **Recency** — newer IRs score higher
2. **User interaction** — turns with direct user input score higher
3. **Decisions** — turns containing decisions score higher
4. **Entity references** — IRs whose entities are referenced in recent turns score higher
5. **Actions** — turns with completed actions (commits, file changes) score higher

Before removing an IR:
- Update any cross-references (e.g., project events referencing the removed turn)
- Delete associated overflow files
- If the IR can be rebuilt from the journal, mark it as prunable rather than deleting (lazy rebuild on demand)

### Cross-Reference Maintenance

When a turn IR is pruned, the extension updates any project events that reference it:
- Replace `:ir/source-turn-id` with a summary of the turn (if available from the compaction summary)
- Or mark the event as `:ir/source-turn-pruned? true`

This ensures project events remain meaningful even after their source turns are pruned.

### Cleanup

The extension periodically scans `.psi/context/irs/` for orphaned overflow files (files whose parent IR no longer exists) and removes them. This prevents disk accumulation from crashed sessions or aggressive pruning.

## Session Lifecycle with IRs

### On Prompt Submission

```
user text
  → :session/prompt-submit handler
    → core builds user-message map
    → core dispatches :session/ir-build-user-input (NEW dispatch event)
      → extension handler builds User Input IR
      → handler returns {:root-state-update f :return ir-map}
      → core stores IR in :extension-ir-data
    → core appends journal entry (as before)
    → returns {:turn-id uuid :user-msg msg}
  → :session/prompt handler
    → starts agent loop with user message
  → :session/prompt-prepare-request handler
    → calls :build-prepared-request-fn (ctx fn, not dispatch)
      → inside build-prepared-request, core calls
        :ir-project-conversion-fn (NEW ctx fn, extension-owned)
        → extension projects IRs into provider messages:
          - queries :extension-ir-data for turn IRs and state IRs
          - selects which turns to include
          - projects Session State IR and Project State IR as system messages
          - removes older turns (replacing with summary or dropping)
          - returns projected message list
        → core uses projected messages for the provider request
    → returns prepared-request with projected messages
  → :session/prompt-execute handler
    → starts agent loop with prepared messages
```

Key point: projection happens inside `build-prepared-request` via a ctx fn,
not via dispatch. This is because `build-prepared-request` is already a ctx fn
(`:build-prepared-request-fn`), not a dispatch handler. Adding a dispatch event
here would break the synchronous flow.

### On Reply Completion (Turn Continue)

```
assistant message with tool calls
  → :session/prompt-record-response handler
    → core records response (journals assistant message)
    → core schedules :session/prompt-continue
  → :session/prompt-continue handler
    → runs tool calls (via :runtime/prompt-continue-chain effect)
    → after tool execution, tool results are journalled
  → loops back to :session/prompt-prepare-request
```

No Turn IR extraction during the continue loop. The raw journal entries
accumulate; the extension extracts semantic content from the complete turn after
it finishes. Core still stamps each journal entry in the loop with the current
turn id so the extension can recover the turn boundary.

### On Reply Completion (Turn Finish)

```
final assistant message (no tool calls)
  → :session/prompt-record-response handler
    → core records response (journals assistant message)
    → core schedules :session/prompt-finish
  → :session/prompt-finish handler
    → emits :on-agent-done event
    → dispatches :session/ir-process-reply (NEW, via effect, async)
      → extension processes the complete turn out-of-band:
        - reads all journal entries for this turn
        - extracts Turn IR: entities, relationships, claims, questions,
          decisions, actions
        - updates Session State IR (turn count, context usage)
        - generates Project Event IRs from extracted content
        - re-materialises Project State IR if events warrant
        - optionally extracts memories/knowledge
      → user sees reply immediately; processing happens in background
```


Reply processing is ordered per session. The context manager processes completed
turns sequentially by turn id for a given session-id; it does not process turn
`N+1` before turn `N`. If the user submits the next prompt before processing has
finished, projection uses the latest completed IRs and raw journal fallback for
any still-unprocessed turns.

### On Auto-Compaction

```
context threshold exceeded
  → :runtime/auto-compact-workflow effect
    → core dispatches :session/ir-compact (NEW dispatch event)
      → extension handler decides compaction strategy:
        - queries :extension-ir-data for all turn IRs
        - scores turns by relevance
        - summarises old turns into compact summary
        - returns {:root-state-update f :return {:summary "..." :turns-to-remove [...]}}
        → f updates :extension-ir-data with compaction summary
          and removes old turn IRs
      → core records the compaction metadata for future projection; the journal
        remains the source of truth and is not rebuilt from IRs
    → extension dispatches ir_compacted event
```

### On Session Resume

```
session file loaded
  → :session/resume-loaded handler
    → core rebuilds messages from journal entries (as today)
    → core dispatches :session/ir-rebuild (NEW dispatch event)
      → extension rebuilds IRs from journal entries:
        - scans journal for turn boundaries
        - extracts Turn IRs for each turn
        - builds Session State IR from current session data
        - loads or re-materialises Project State IR from cache
        - enforces size budgets and creates overflow files as needed
      → IRs are ready for the next projection
```

IR rebuild is a one-time cost at session start. The extension can optimise by
only rebuilding IRs since the last compaction boundary.

### Projection Fallback

When the context manager extension is not installed, or when projection fails,
the ctx fn `:ir-project-conversion-fn` returns `nil` to signal core to use the
default journal-to-messages projection (`journal->provider-messages` in
`psi.agent-session.prompt-request`). When only some Turn IRs are absent (e.g.,
the next turn starts before out-of-band processing finishes), the projector may
mix IR-projected turns with raw journal fallback for the missing turns.

This means the system always works — the IR pipeline is an enhancement, not a
requirement.

## Core Model Changes

### Session Data

Add to `agent-session-schema` in `psi.session-state.model`:

```clojure
[:extension-ir-data {:optional true}
 [:map-of :keyword [:map-of :keyword :any]]]
```

Outer key is extension path, inner keys are extension-defined.
Initial value in `initial-session`: `{}`.

This is an opaque store. Core never reads or interprets the values — only the
extension does via EQL query.

### Context Function Additions

Add one context-manager projection function to the ctx map (built in
`context.clj`):

```clojure
:ir-project-conversion-fn  (fn [ctx session-id turn-id user-msg base-messages]
                             ;; returns {:messages [...] :metadata {...}}
                             ;; or nil to use base-messages unchanged)
```

Projection is synchronous and happens inside request preparation. All IR state
creation and mutation (`build-user-input`, reply processing, compaction metadata,
and rebuild) goes through dispatch events/mutations, not ctx functions. When no
context manager extension is installed, the projector is nil and core uses
legacy behaviour.

### New Dispatch Events

#### `:session/ir-build-user-input`

Dispatched during `:session/prompt-submit`, after the user message is built
but before journaling.

**Input:**
```clojure
{:session-id sid
 :user-msg user-message-map
 :expansion expansion-map-or-nil
 :commands command-names-vector}
```

**Handler:** stores the User Input IR on the session under
`:extension-ir-data :context-manager :turn-irs :user-input turn-id`.

**Return:** `{:root-state-update f :return ir-map}`

**Fallback:** when no handler registered, returns `{:return nil}`.

#### `:session/ir-process-reply`

Dispatched as an effect during `:session/prompt-finish` (async, non-blocking).

**Input:**
```clojure
{:session-id sid
 :turn-id "uuid"
 :journal-entries [journal-entry ...]}
```

**Handler:** reads the journal entries for the completed turn, extracts the
Turn IR (entities, relationships, claims, questions, decisions, actions),
generates project events, updates session state, and optionally extracts
memories. No return value needed.

**Fallback:** when no handler registered, returns `{}`.

#### `:session/ir-compact`

Dispatched during auto-compaction when the context manager extension is
installed.

**Input:**
```clojure
{:session-id sid
 :ir-data :extension-ir-data-from-session
 :context-tokens N
 :context-window N
 :keep-recent-tokens N}
```

**Return:** `{:root-state-update f :return {:summary "..." :turns-to-remove [...]}}`

**Fallback:** when no handler registered, falls back to legacy compaction.

#### `:session/ir-rebuild`

Dispatched during `:session/resume-loaded` after journal entries are loaded.

**Input:**
```clojure
{:session-id sid
 :journal-entries [journal-entry ...]}
```

**Handler:** rebuilds all IRs from journal entries. Runs synchronously during
resume so IRs are available for the first projection.

**Fallback:** when no handler registered, returns `{}`.

### Extension Event Hooks

New extension dispatch events for extensions that want to observe IR lifecycle:

- `ir_user_input_built` — fired after User Input IR is stored
- `ir_turn_extracted` — fired after Turn IR is extracted from journal entries
- `ir_conversation_projected` — fired after projection, with before/after message counts
- `ir_reply_processed` — fired after out-of-band reply processing
- `ir_compacted` — fired after IR-based compaction
- `ir_rebuilt` — fired after IR rebuild on session resume

## Extension Design

The context manager extension owns:

1. **IR builders** — functions that construct each IR type from raw data
2. **IR projector** — function that projects IRs into provider messages
3. **IR processor** — function that processes replies out-of-band
4. **IR compactor** — function that compacts IRs when context is full
5. **State IR materialisers** — functions that build Session State IR and
   Project State IR from live data
6. **IR rebuilder** — function that rebuilds all IRs from journal on resume

### Extension Init

```clojure
(defn init [api]
  ;; Register dispatch handlers via on!
  (api/on "session/ir-build-user-input" build-user-input-handler)
  (api/on "session/ir-process-reply" process-reply-handler)
  (api/on "session/ir-compact" compact-handler)
  (api/on "session/ir-rebuild" rebuild-handler)

  ;; Register the one synchronous projection hook
  (api/register-ir-projector! project-conversation))
```

### Projection Strategy

The projector decides what the LLM sees. A typical strategy:

1. **System layer**: Session State IR + Project State IR as system messages
2. **Recent turns**: Last N turns projected from their semantic IRs — entities,
   decisions, claims, and actions rendered as structured context
3. **Summarised history**: Older turns replaced with a compact summary derived
   from their IRs (preserving key decisions and entities)
4. **Active context**: Any mid-system messages, steering messages, follow-ups

The projector can use different strategies based on context pressure:
- **Low pressure**: include more turns with full semantic detail
- **High pressure**: aggressive summarisation, keep only decisions and key entities
- **Critical**: keep only the last turn + essential state + active decisions

### Reply Processing

Out-of-band processing after each reply:

1. **Read journal entries** for the completed turn
2. **Extract Turn IR**: parse entities, relationships, claims, questions,
   decisions, and actions from the raw conversation content
3. **Extract project events**: file changes, task progress, decisions made
4. **Update session state IR**: turn count, context usage, phase
5. **Check for memory extraction**: does the turn contain insights worth
   storing? (integrates with mementum)
6. **Re-materialise project state IR** if events warrant

This runs in a background thread and does not block the user.

### Compaction Strategy

IR-based compaction is more surgical than the current approach:

1. **Score each turn** by relevance (recency, decisions made, entities introduced,
   user interaction)
2. **Group consecutive turns** into thematic clusters
3. **Summarise low-scoring clusters** into compact IR summaries preserving key
   decisions and entities
4. **Drop turns** below a threshold (with summary preservation)
5. **Consolidate related entities and decisions** across turns

The extension can use the structured semantic IR data to produce better
summaries than raw message text — preserving what matters (decisions, entities,
relationships) and discarding conversational noise.

### IR Rebuild on Resume

When a session is resumed, the extension rebuilds all IRs from journal entries:

1. **Scan journal** for turn boundaries (user message → assistant reply chain)
2. **Extract Turn IRs** for each turn since the last compaction boundary
3. **Build Session State IR** from current session data
4. **Load Project State IR** from cache (or re-materialise if stale)
5. **Enforce size budgets** and create overflow files as needed

This is a one-time synchronous cost at session start. The extension can
optimise by only rebuilding IRs since the last compaction entry in the journal.

## Compaction Semantics

IR-based compaction controls future provider projection only. It does not rewrite
the persisted journal. The journal remains the authoritative raw history from
which IRs are rebuilt. Compaction stores extension-owned summary metadata and
projection policy in `:extension-ir-data`; the projector uses that metadata to
replace older raw/semantic turns with compact summaries when building provider
messages.

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
4. **Projection is the IR-to-provider boundary.** Provider-facing messages are
   produced only by the active context manager's projector or by legacy journal
   projection fallback.
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
10. **Projected messages must be provider-valid.** Projection must preserve
    provider protocol constraints, especially role ordering and tool-call/tool-
    result adjacency. If a raw segment cannot be included validly, the projector
    must replace the whole segment with a summary/context representation rather
    than emit an invalid partial message sequence.

## Architecture Fit

### VSM Alignment

- **S1 (Operations)**: IR storage is session state; IR builders/projectors are
  extension handlers. Core dispatches events; extension handles them.
- **S2 (Regulation)**: IR schemas are extension-defined; core validates that
  IR data is stored but never interprets it. Extension permissions control
  what state the extension can read.
- **S3 (Coordination)**: Dispatch pipeline feeds IR lifecycle events in the
  right order. Extension handlers are pure functions that return IR data.
- **S4 (Adaptation)**: Extension can evolve IR schemas and projection
  strategies without core changes. EQL introspection exposes IR data.

### One-Way Guideline

IR data flows one way:
- Core → Extension: raw data via dispatch event input or ctx fn call
- Extension → Core: structured IR via handler return or ctx fn return
- Extension → Provider: projected messages via projector return

Core never reads IR data for its own purposes. The extension is the sole
consumer and producer of IR semantics.

### No Shims

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

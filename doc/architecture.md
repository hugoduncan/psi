# Architecture

```text
Engine      (statecharts) → substrate
Query       (EQL/Pathom3) → capability surface
AI          (providers)   → streaming LLM layer
Agent       (statechart)  → per-turn lifecycle
App Runtime (interactive) → shared adapter-neutral UI/session domain
RPC         (transport)   → remote adapter over app-runtime
TUI         (charm.clj)   → terminal adapter over app-runtime
Emacs       (rpc client)  → editor adapter over app-runtime
```

## Components

| Component       | Role                                              |
|-----------------|---------------------------------------------------|
| `engine`        | Statechart infrastructure, system state           |
| `query`         | Pathom3 EQL registry, `query-in`                  |
| `ai`            | Provider streaming, model registry (Anthropic, OpenAI) |
| `agent-core`    | LLM agent lifecycle statechart + EQL resolvers    |
| `agent-session` | Full coding-agent session: tools, extensions, OAuth, canonical state |
| `app-runtime`   | Shared interactive application runtime for adapter-neutral session/UI semantics |
| `history`       | Git log resolvers                                 |
| `introspection` | Engine queries itself — self-describing graph     |
| `rpc`           | Transport, framing, subscriptions, request/response adaptation |
| `tui`           | JLine3 + charm.clj terminal adapter               |
| `emacs-ui`      | Emacs RPC client adapter                          |

## Adapter convergence target

The architecture target is:

> `app-runtime` contains everything common between TUI and Emacs.
> RPC is a transport layer on top of `app-runtime`, not a second home for
> session or UI-domain logic.

Current duplication pressure exists where the same user-visible question is
answered in more than one adapter path, for example:
- session selector/tree ordering and fork-point interleaving
- footer/status semantic composition
- session summary fragments used by headers/diagnostics
- picker definitions (`/tree`, `/resume`, `/model`, `/thinking`)
- session navigation result shaping (`new` / `resume` / `switch` / `fork`)
- background job and context snapshot presentation data

### Convergence rule

If both TUI and Emacs need the same answer, `app-runtime` should answer it once.
Adapters should differ only in:
- rendering
- local interaction mechanics
- transport/protocol concerns

### Projection delivery rule

For runtime-owned interactive projections, canonical state changes first and public payloads are derived later:
- session/runtime handlers mutate canonical state
- handlers emit semantic invalidations such as `:projection/context-changed` and `:projection/ui-changed`
- `app-runtime` remains the owner of canonical public projection models
- RPC delivers those projections to subscribed clients by recomputing payloads from current canonical state plus connection-local focus
- runtime-owned context/session-tree and shared UI updates are event-driven rather than polling-driven

### Ownership target

#### `app-runtime` owns
- adapter-neutral session navigation operations
- focus-scoped session operations parameterized by adapter-owned focus
- selector/picker models and item ordering
- footer semantic model
- shared session-summary/model-label/status fragments for adapter diagnostics/header use
- context snapshot / session tree model, including canonical session-tree widget projection when adapters need the same rendered structure
- canonical transcript message reconstruction from journal state
- transcript rehydration packages and other shared presentation-facing domain projections
- canonical UI action/result vocabulary
- shared public summaries for jobs, statuses, and extension UI state where both adapters need the same meaning

#### `rpc` owns
- transport framing
- subscriptions and event delivery
- request/response correlation
- transport-focused handshake / protocol negotiation
- adaptation of `app-runtime` models onto the RPC protocol
- explicit `session-id` routing whenever the operation can reasonably carry it
- RPC-local focus pointer only as transport-scoped adapter fallback state
- subscriber-aware fanout of runtime-owned projection invalidations (`:projection/context-changed`, `:projection/ui-changed`) with per-connection payload recomputation

RPC should not be the long-term home for selector semantics, footer semantics,
or session navigation domain logic.

#### `tui` owns
- terminal layout
- key handling
- local widget/view state
- adapter-specific rendering concerns

#### `emacs-ui` owns
- buffer rendering
- minibuffer completion
- overlays/faces
- local widget/view state
- adapter-specific rendering concerns

## EQL Introspection Tips

- Query only attributes that exist in the graph; unknown attrs can cause the whole `psi-tool` request to fail.
- For the active system prompt, use:
  - `[:psi.agent-session/system-prompt]`
- For extension UI behaviour, prefer capability/action discovery through the
  `:psi.ui/...` query surface when available:
  - `[:psi.ui/type :psi.ui/available? :psi.ui/capabilities :psi.ui/actions :psi.ui/make-visible-action]`
  - branch on `:psi.ui.capability/...` keywords and action descriptor availability,
    not concrete frontend types
- For runtime UI type diagnostics and compatibility with older callers, use:
  - `[:psi.agent-session/ui-type]`  ; `:console` | `:tui` | `:emacs`
  - treat this as low-level introspection/compatibility data, not the normative
    extension-authoring contract for invokable UI behaviour
- For prompt sizing (chars + estimated tokens), use:
  - `[{:psi.agent-session/request-shape [:psi.request-shape/system-prompt-chars :psi.request-shape/estimated-tokens :psi.request-shape/total-chars]}]`
- For the slash-command surface offered to UIs, the backend is the single
  authoritative source. Both the TUI and Emacs build their slash autocomplete by
  querying the graph — they hold no hardcoded command lists:
  - extension commands: `[:psi.extension/command-names]`
  - built-in commands: `[:psi.agent-session/builtin-command-specs]` (a vector of
    `{:name :description}`, bare names, in table order) and the symmetric
    `[:psi.agent-session/builtin-command-names]`
  - built-in command identity lives in exactly one place — the
    `builtin-command-specs` table in `psi.agent-session.commands.builtin-specs`.
    The routing maps (`exact-command-handlers`, `prefixed-command-prefixes`),
    `format-help`'s built-in lines, and the `builtin-commands-resolver` are all
    pure projections of that table, so a routed-but-undescribed (or vice-versa)
    command is structurally unrepresentable, and adding a built-in flows to both
    UIs with no UI-side list edit.
- For prompt lifecycle introspection summaries, use:
  - `[:psi.agent-session/last-prepared-request-summary :psi.agent-session/last-execution-result-summary]`
- For normalized prompt lifecycle fields, use attrs such as:
  - `:psi.agent-session/last-prepared-turn-id`
  - `:psi.agent-session/last-prepared-message-count`
  - `:psi.agent-session/last-prepared-tool-count`
  - `:psi.agent-session/last-execution-turn-id`
  - `:psi.agent-session/last-execution-turn-outcome`
  - `:psi.agent-session/last-execution-stop-reason`
- Anthropic prompt caching is session policy projected into request shape:
  - session state stores `:cache-breakpoints` such as `:system` and `:tools`
  - executor projects those into conversation `:system-prompt-blocks` / tool `:cache-control`
  - the Anthropic provider emits `cache_control` only for supported directives (`{:type :ephemeral}`)
- Avoid non-existent attrs like `:psi.agent-session/prompt`, `:psi.agent-session/instructions`, `:psi.agent-session/messages` unless resolvers are added for them.

## State boundary: canonical root vs runtime handles

`:state*` owns queryable session truth — one atom, one root. Everything
else on `ctx` is a handle to a running subsystem.

**Principle:** when a subsystem has observable status worth querying
(OAuth login state, nREPL endpoint, workflow progress), that status is
projected into `:state*` as canonical data through dispatch. The handle
itself stays external.

A runtime handle is any object that:
- owns internal mutable lifecycle (atoms, watches, threads)
- performs side-effecting I/O (disk, network, locks)
- is infrastructure machinery (compiled envs, registries, engines)

Current runtime handles on ctx:

| Handle | What it is | Projection in `:state*` |
|--------|-----------|------------------------|
| `:agent-ctx` | agent-core loop, queues, event stream | turn context, provider captures |
| extension registry | loaded extensions, flags, event bus | extension prompt contributions |
| workflow registry | workflow instances, pump thread, statechart env | background jobs, workflow public data |
| `:oauth-ctx` | credential store, token refresh, file locks | authenticated providers, login status |
| nREPL server | live server object | `[:runtime :nrepl]` endpoint metadata |
| project nREPL registry | managed project/worktree nREPL runtime handles | `:psi.project-nrepl/*` projected instance state |
| query context | Pathom3 registry, compiled env | (is the query infrastructure itself) |
| engine context | statechart engines, system state, transition log | (is the engine infrastructure itself) |
| memory context | memory stores, store registry | (is the memory infrastructure itself) |

These are all the same kind of thing: opaque subsystems with their own
internal mutable lifecycle. They are not queryable domain state.

## Dispatch migration status

- `dispatch!` is active and queryable via the retained dispatch event log.
- Current dispatch ownership is partial, not full-system.
- Migrated families include:
  - statechart action handlers
  - auto flags / ui type
  - model / thinking
  - session name / worktree / cache breakpoints
  - active tools
  - system prompt recomposition
  - prompt contribution mutations
  - startup/bootstrap lifecycle + summary writes
  - context usage / extension prompt telemetry / runtime prompt retargeting
  - rpc trace / oauth projection / recursion projection setters
  - extension UI mutations (widget/widget-spec/status/notify/dialog + renderer registration)
- Remaining direct mutation pockets still exist outside those migrated slices.
- Treat `dispatch_pipeline_active` as "dispatch active for migrated slices"
  during migration, not yet "all mutations converge through dispatch".

## Dispatch sequencing contract

Current `agent-session` dispatch sequencing for pure handler results is:
1. handler computes a pure result
2. apply writes state and surfaces declared effects onto interceptor context
3. validate checks the post-apply interceptor context
4. replay trimming may suppress effects
5. effects execute last

Current scaffold semantics:
- validation is post-apply, not pre-commit
- invalid validation suppresses effects but does not roll back already-applied state
- replay suppresses effects but preserves state application and return values

Current default interceptor ids:
- `:permission`
- `:log`
- `:statechart`
- `:handler`
- `:effects`
- `:trim-effects-on-replay`
- `:validate`
- `:apply`

Because after fns run in reverse order, the effective after-order is:
- `:apply -> :validate -> :trim-effects-on-replay -> :effects`

## Dispatch event-log observability

The retained dispatch log now exposes more architectural debugging signal than
just event type and timing. Current log entries include:
- event identity:
  - event type
  - event data
  - origin
  - ext id
- control flow:
  - blocked?
  - block reason
  - replaying?
  - statechart-claimed?
  - validation error
- pure-result/effect shape:
  - pure-result kind (`:db`, `:root-state-update`, `:session-update`, etc.)
  - declared effects
  - applied effects
- bounded state summaries:
  - db-summary-before
  - db-summary-after
- timing:
  - timestamp
  - duration-ms

Retention/volume tradeoff:
- the log keeps bounded summaries rather than full root-state snapshots
- all entries are replay-safe by construction: replay suppresses effects and applies
  only pure state transforms, so no classification is needed to determine safety
- this log is the coarse-grained dispatch journal: one summarized entry per dispatch
- it is the preferred surface for replay-oriented questions like "what events happened?"
  and "what broad state/effect shape did they produce?"

## Canonical dispatch trace observability

In addition to the retained event log, `agent-session` now keeps a bounded
canonical dispatch trace keyed by `dispatch-id`.

Current trace entry kinds include:
- `:dispatch/received`
- `:dispatch/interceptor-enter`
- `:dispatch/interceptor-exit`
- `:dispatch/handler-result`
- `:dispatch/effects-emitted`
- `:dispatch/effect-start`
- `:dispatch/effect-finish`
- `:dispatch/service-request`
- `:dispatch/service-response`
- `:dispatch/service-notify`
- `:dispatch/completed`
- `:dispatch/failed`

Current guarantees:
- every dispatch-created trace has one stable `dispatch-id`
- dispatch-owned traces now include interceptor stage boundaries, handler-result summaries,
  and emitted-effect summaries where the flow passes through the dispatch pipeline
- post-tool flows can create and explicitly thread a `dispatch-id` through
  nested extension/service activity
- managed-service protocol helpers record service request/response/notify events
  under the explicitly supplied `dispatch-id`
- dispatch effect execution records effect start/finish entries including
  `:effect-type`
- trace storage is bounded in memory

Current EQL surface:
- `:psi.dispatch-trace/count`
- `{:psi.dispatch-trace/recent [...]}`
- `{:psi.dispatch-trace/by-id [...]}` from seed `[:psi.dispatch-trace/dispatch-id some-id]`

Useful attrs on trace entries include:
- `:psi.dispatch-trace/trace-kind`
- `:psi.dispatch-trace/dispatch-id`
- `:psi.dispatch-trace/event-type`
- `:psi.dispatch-trace/interceptor-id`
- `:psi.dispatch-trace/method`
- `:psi.dispatch-trace/effect-type`
- `:psi.dispatch-trace/tool-call-id`
- `:psi.dispatch-trace/error-message`

This canonical trace is the preferred observability surface for end-to-end
runtime coordination. It is the fine-grained complement to the dispatch event-log:
- use the event-log for replay-oriented, one-entry-per-event journaling
- use the dispatch trace for correlated stage-by-stage diagnosis under one `dispatch-id`

Adapter-local debug atoms remain useful for low-level transport diagnosis, but
normal architectural debugging should prefer the queryable dispatch trace.

## Conforming vertical slice — manual compaction

The first explicit conforming vertical slice target is manual compaction.

Current intended slice flow:
1. public API entry via `manual-compact-in!`
2. dispatch-routed statechart transition via `:session/compact-start`
3. synchronous dispatch-owned compaction execution via `:session/manual-compaction-execute`
4. dispatch-visible session-data cleanup via `:session/compaction-finished`
5. dispatch-routed statechart completion via `:session/compact-done`

Current intentional boundary:
- the compaction execution step itself is still synchronous so the caller can
  receive the compaction result directly
- the surrounding control flow is dispatch-visible and statechart-visible

Current proof surface for the slice:
- focused core tests now prove dispatch-visible event sequences for:
  - default stub compaction
  - custom compaction function
  - extension-cancelled compaction
  - extension-supplied compaction result
- the dispatch event log is the primary slice observability surface; no bespoke
  local-only debug hooks are required to understand the slice flow

This slice is the proving ground for broader convergence from partial dispatch
ownership toward more reference-architecture-conforming vertical behavior.

## Next vertical slice — prompt / turn lifecycle

The next target slice after manual compaction is prompt / turn lifecycle.

Current implemented outer shell:
1. public API entry via `prompt-in!`
2. dispatch-visible prompt submission via `:session/prompt-submit`
3. dispatch-routed statechart transition via `:session/prompt`
4. dispatch-owned request preparation via `:session/prompt-prepare-request`
5. runtime execute-and-record boundary via `:runtime/prompt-execute-and-record`
6. dispatch-owned response recording via `:session/prompt-record-response`

Architectural convergence target:
1. public API entry via `prompt-in!`
2. dispatch-visible prompt submission via `:session/prompt-submit`
3. dispatch-routed statechart transition via `:session/prompt`
4. dispatch-owned request preparation via `:session/prompt-prepare-request`
5. runtime execute-and-record boundary via `:runtime/prompt-execute-and-record`
6. dispatch-owned response recording via `:session/prompt-record-response`
7. dispatch-owned continuation / terminalization via `:session/prompt-continue` or `:session/prompt-finish`

Current converged slice semantics:
- `:session/prompt-submit`
  - normalize the submitted user message
  - append the user journal entry
  - establish the requested turn as dispatch-visible state
- `:session/prompt-prepare-request`
  - project canonical session state into a prepared provider request artifact
  - assemble prompt layers (base prompt, extension contributions, profiles/skills, runtime metadata)
  - project cache policy into system/tool/message cache controls
  - emit the runtime execute-and-record effect
- `:runtime/prompt-execute-and-record`
  - perform provider streaming against the prepared request artifact
  - capture provider request/response telemetry
  - dispatch `:session/prompt-record-response` with the shaped execution result
- `:session/prompt-record-response`
  - append assistant output deterministically
  - record usage / telemetry / tool-call outcomes
  - decide continuation from canonical recorded state
- `:session/prompt-continue` / `:session/prompt-finish`
  - route tool execution or follow-up turn continuation
  - return the session lifecycle to its terminal state for the turn

Current intentional boundary:
- prompt journal append, request preparation, assistant result recording, and
  continuation decisions are now dispatch-visible, while provider streaming and
  turn accumulation remain concentrated in the runtime execute-and-record boundary
- the active slice currently reads as prepare -> execute-and-record -> continue/finish;
  this is an intentional convergence waypoint toward the stricter prepare -> execute -> record model
- request preparation is the architectural center for prompt lifecycle convergence;
  prompt layering, cache breakpoint policy, and provider request shaping should
  become explicit there rather than remain distributed across string concatenation
  and runtime orchestration paths
- tool execution is now dispatch-owned end-to-end:
  - `:session/tool-run` composes two dispatch-owned phases:
    1. `:session/tool-execute-prepared` — may run concurrently, emits start/executing
       lifecycle, performs runtime tool execution through `:runtime/tool-execute`, and
       returns a shaped result without final recording
    2. `:session/tool-record-result` — records the final tool result in deterministic
       tool-call order, including lifecycle projection, telemetry, journal append,
       and agent-core tool-result recording
  - the executor now owns only batch scheduling and deterministic ordered recording,
    not tool transaction semantics

## Adapter convergence roadmap

Near-term architectural direction:
1. move shared selector/session-tree semantics into `app-runtime`
2. move shared footer semantic projection into `app-runtime`
3. define a canonical adapter-neutral picker/action vocabulary in `app-runtime`
4. converge navigation result shaping, context snapshots, and transcript rehydration packages into `app-runtime`
5. leave RPC as protocol adaptation and adapters as rendering/mechanics

What success looks like:
- TUI and Emacs consume the same selector, footer, navigation, context, and transcript rehydration models
- RPC projects shared runtime models onto transport events instead of owning their semantics
- adapter bugs no longer require re-solving shared domain questions in multiple places
- explicit `session-id` routing becomes the default for targetable RPC operations, with adapter focus used only as fallback

## Roadmap

- ✓ Engine + Query substrate
- ✓ AI provider layer (Anthropic, OpenAI)
- ✓ Agent core loop
- ✓ Coding-agent session
- ✓ TUI (charm.clj / JLine3)
- ✓ Extension system + Extension UI
- ✓ OAuth (PKCE, Anthropic, OpenAI)
- ✓ Git history resolvers
- ✓ Session persistence
- ◇ HTTP API (openapi + martian)

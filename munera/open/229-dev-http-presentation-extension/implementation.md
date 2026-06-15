# 229 — implementation notes

## Review log

### design-review · architecture (turn 1)

Architectural-fit pass (¬correctness, ¬ambiguity, ¬inconsistency). Sources:
AGENTS.md, META model, doc/architecture.md, doc/extension-api.md, doc/extensions.md.

Strong fit confirmed: reads-via-`:query`/writes-via-`:mutate`, runtime-handle
externality (integrant system in extension's own atom, not core `:state*`),
replay/determinism boundary reasoning, minimal capability + `:allowed-events`,
interaction re-entering session through a mutation (input-as-event-source
analog to TUI/RPC). Extension-local `deps.edn` matches the per-extension
local-root precedent (work-on, mcp-tasks-run).

Actionable architectural misfits:

- AF-1 Persisted routes load from the root `:dev {:extra-paths ["dev"]}`
  project-global path, coupling an otherwise strictly-isolated extension to a
  project-global source location. Isolation posture favors extension-local
  `extensions/dev-http/dev/` (extension owns its own dev extra-path) unless the
  root-`dev/` coupling is explicitly justified. (Task already flags this as a
  planning decision; the architecture-fit lens favors extension-local.)
- AF-2 Observable server status is surfaced only via `status` output + log. The
  State-boundary principle projects subsystem status worth querying into
  `:state*` via dispatch (precedent: nREPL `[:runtime :nrepl]` endpoint
  metadata, OAuth login status, workflow progress) while the handle stays
  external. Decide explicitly: project running?/url/token into canonical state
  for EQL/psi-tool introspection (recommended, matches precedent) or document
  the deliberate extension-local-only divergence.
- AF-3 The extension-facing mutation wrapping the currently-internal
  `:session/submit-synthetic-user-prompt` should be bound to the documented
  `psi.extension/*` mutation surface, dispatch-routed, and permission-gated via
  `:allowed-events` — an explicit contract update (one-way/no-shim,
  untrusted-extension posture), not an internal-event back-door. Design states
  it is in scope but does not locate it on the extension-API contract.

### design-review · architecture (round 2, turn 1)

Fresh architecture-fit pass over the post-resolution design (AF-1..3 already
resolved into design.md). Sources: AGENTS.md, META.md, doc/architecture.md
(State boundary table; Dispatch sequencing contract). AF-1..3 confirmed
genuinely resolved (extension-local `dev/`, status projection, first-class
`psi.extension/*` mutation). Two new actionable misfits:

- AF-4 AF-2's resolution projects `running?`/`url`/**`token`** into canonical
  `:state*` via dispatch, modeled on the nREPL endpoint-metadata precedent. But
  the State-boundary table keeps the OAuth credential store *external* and
  projects only login *status*; secrets do not enter canonical state. The
  per-launch token is a credential-class secret — projecting it also lands it in
  the replayable event-log + dispatch-trace summaries. Conforming choice: keep
  the token in the extension-local handle, project only `running?`/`url`, and
  surface the live token via the `status`/log path (reads the external handle).
  `url`/port non-determinism is already precedented (nREPL); the token's
  *secrecy* is the distinguishing misfit. Not covered by AF-1..3.
- AF-5 D3 says the wrapping mutation's "handler internally dispatches
  `:session/submit-synthetic-user-prompt`". The Dispatch sequencing contract
  sanctions follow-on dispatch only via the `:runtime/dispatch-event` effect
  (pure handler → effects-as-data). Imperative in-handler dispatch violates the
  pure-handler boundary. Conforming mechanism: the wrapping mutation emits a
  `:runtime/dispatch-event` follow-on effect targeting the synthetic-user-prompt
  event. Distinct from AF-3 (contract surface + `:allowed-events`).

### design-review · ambiguity (turn 2)

Ambiguity pass (¬correctness, ¬architecture, ¬inconsistency). Used in-context
design.md + architecture sources + AF notes. Six new actionable ambiguities:

- AMB-1 Token transport (query param/header/cookie) and which routes the token
  gates (HTML / vendored JS assets / choice POST / `:file`) are unspecified.
- AMB-2 Route-id assignment model (caller-supplied vs system-generated) is
  unclear and differs implicitly between `dev-present` and `register-route!`;
  O4 replace semantics presume caller-controlled ids.
- AMB-3 "drives the agent's next turn immediately" is undefined when the
  originating session is mid-turn/busy at choice-submit time (queue/reject/
  interrupt).
- AMB-4 Choice feedback targets "the invoking session," but `register-route!`
  is a REPL fn with no invoking agent session; the feedback target session for
  REPL-registered routes is undefined.
- AMB-5 AC-1 "configurable/ephemeral port" vs Lifecycle/O3 "ephemeral
  OS-assigned" leaves it ambiguous whether a port-config override exists.
- AMB-6 SSE appears as slicing slice 4 but is absent from the "In scope" list;
  whether SSE is a deliverable of this task is ambiguous.

### design-review · inconsistency (turn 3)

Inconsistency pass (¬correctness, ¬architecture, ¬ambiguity). Used in-context
design.md + architecture + AF/AMB notes. Two new actionable inconsistencies.
Port (AC-1 vs Lifecycle/O3) and SSE (slicing vs In-scope) inconsistencies are
already captured under AMB-5/AMB-6; not duplicated here.

- INC-1 Slice 1 demo route renders "something real (e.g. a benchmark table)"
  but the renderer set (incl. `:table`) is introduced in Slice 2; the slice-1
  example presupposes a later-slice mechanism.
- INC-2 `dev-present` is described as "safe, replay-friendly, model-driven" yet
  its target renderer set includes the `:hiccup` raw-HTML escape hatch and
  `:file` arbitrary-disk-file serving, contradicting the "safe" framing.

### design-review · follow-up (this pass)

Evidence rule applied. No previous design-follow-up exists; the review-batch
segment is the three contiguous review commits (`98080a4f0` architecture,
`2ea8cfdc4` ambiguity, `16bacb444` inconsistency). Baseline = parent of oldest
segment commit = task-creation `5e108158e`. `git diff 5e108158e..HEAD --
…/design-steps.md` shows the entire `design-steps.md` was added by the batch
(absent at baseline); all 11 items (AF-1..3, AMB-1..6, INC-1..2) are diff-added,
unchecked, still present → candidate work set = all 11. No predating/stale/steps
items in scope.

Verified two referenced precedents before editing: `:session/submit-synthetic-
user-prompt` exists as an internal handler riding the statechart turn path
(prompt-lifecycle.clj); nREPL status projection precedent is `[:runtime :nrepl]`
(context.clj / `:session/set-nrepl-runtime`). Extension-local `dev` extra-path is
consistent with the per-extension local-root precedent (work-on, mcp-tasks-run).

Resolutions written into design.md (and crystallized under "Design-review
follow-up resolutions"):

- AF-1 → persisted routes load from extension-local `extensions/dev-http/dev/`
  via the extension's own `:dev` extra-path (isolation preserved); updated Model,
  Dependencies, Scope, AC-2, Slicing.
- AF-2 → project `running?`/`url`/`token` into `:state*` via dispatch (nREPL
  precedent); integrant handle stays extension-local. Added Lifecycle bullet +
  Architectural constraint.
- AF-3 → first-class `psi.extension/*` dispatch-routed mutation in
  `:allowed-events` wraps the internal synthetic-user-prompt event; no
  back-door. Updated Interaction + constraints.
- AMB-1 → token via URL query param; gates HTML/choice-POST/`:file`; static
  vendored assets exempt.
- AMB-2 → optional caller-supplied id (replace on collision) else
  system-generated; one model for both registration paths.
- AMB-3 → mid-turn submit queued as next user turn (not reject/interrupt);
  "immediately" = no manual trigger.
- AMB-4 → `register-route!` takes explicit `:session-id`; `dev-present` defaults
  to invoking session; no target → presentation-only.
- AMB-5 → port not user-configurable; ephemeral OS-assigned (honors O3); AC-1
  reworded.
- AMB-6 → SSE out of scope; removed from slices, added to Out of scope.
- INC-1 → slice-1 demo uses platform-only hand-rolled handler output,
  independent of Slice 2 renderer set.
- INC-2 → `dev-present` restricted to safe declarative renderers; `:hiccup` /
  `:file` escape hatches via `register-route!` only.

All 11 design-steps marked done. No blocked/skipped items.

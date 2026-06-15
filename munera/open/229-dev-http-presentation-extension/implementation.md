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

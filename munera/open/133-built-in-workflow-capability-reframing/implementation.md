# 133 — Built-in workflow capability reframing

## Current extension-surface review

Reviewed extension-owned workflow surfaces:

- `extensions/workflow-loader/src/extensions/workflow_loader.clj`
- `extensions/workflow-loader/src/extensions/workflow_loader/{delivery,orchestration,text}.clj`
- `extensions/workflow-display/src/extensions/workflow_display.clj`
- related config/test path ownership in `deps.edn`, `tests.edn`, and extension-local `deps.edn`

Moved into built-in core wiring/ownership:

- canonical workflow display/read-model helpers moved from `extensions.workflow-display` to `psi.agent-session.workflow.display`
- canonical workflow bootstrap/wiring moved into built-in core owner `psi.agent-session.workflow.core`
- canonical workflow helper ownership for delivery/text/orchestration moved into built-in core owners:
  - `psi.agent-session.workflow.delivery`
  - `psi.agent-session.workflow.text`
  - `psi.agent-session.workflow.orchestration`
- runtime bootstrap now installs built-in workflow directly from `components/app-runtime/src/psi/app_runtime.clj`

Removed extension framing:

- `extensions/workflow-display/` disappeared entirely as an extension-owned canonical surface
- `psi/workflow-loader` was removed from recognized manifest-installed built-in extension catalog in `psi.agent-session.extension-installs`
- `.psi/extensions.edn` no longer lists `psi/workflow-loader`

Remaining outside built-in core:

- no canonical workflow-owned extension package remains outside built-in core

## Built-in home for workflow framing

Chosen built-in owners:

- built-in composition root: `components/app-runtime/src/psi/app_runtime.clj`
- higher core workflow owners:
  - `psi.agent-session.workflow.core`
  - `psi.agent-session.workflow.delivery`
  - `psi.agent-session.workflow.text`
  - `psi.agent-session.workflow.orchestration`
  - `psi.agent-session.workflow.display`

Composition choice:

- the task preferred `system-bootstrap` first, but the actual smallest coherent move was to install built-in workflow from `app-runtime` because that is the existing live runtime bootstrap assembly point that already owns background-job UI refresh and startup session bootstrap sequencing
- workflow installation here stays an assembly concern; lower workflow behavior remains in lower workflow components and session-facing behavior remains in `agent-session.workflow.*`
- this does not broaden `agent-session` incorrectly into lower workflow semantics; it only gives higher workflow orchestration a coherent built-in namespace family

## Naming rule for higher core workflow namespaces

Followed the preferred nested `workflow.*` family:

- `psi.agent-session.workflow.core`
- `psi.agent-session.workflow.delivery`
- `psi.agent-session.workflow.text`
- `psi.agent-session.workflow.orchestration`
- `psi.agent-session.workflow.display`

No new flat `workflow-*` namespace family was introduced.

## Preserved lower boundaries

This task intentionally preserved extracted lower workflow component boundaries:

- `components/workflow-loader/`
- `components/workflow-runtime/`
- `components/workflow-registry/`
- `components/workflow-judge/`
- `components/workflow-step-materialization/`
- `components/workflow-step-session-config/`
- `components/deterministic-operation-registry/`
- `components/deterministic-operation-runtime/`

No lower workflow authored-definition loading, registry semantics, runtime semantics, judge semantics, step materialization semantics, or step session-config semantics were moved upward by this task.

## Public surface preservation

Preserved user-facing workflow surfaces:

- `delegate` tool availability and behavior
- `/delegate`
- `/delegate-reload`
- workflow definition loading/reloading from `.psi/workflows/`
- workflow registration/removal behavior after reload
- current session-switch reload behavior
- available-workflow prompt contribution surfacing via `workflow-loader-workflows`
- workflow run execution through canonical core workflow mutations/resolvers/psi-tool surfaces

Incidental changes allowed by the task:

- canonical workflow bootstrap provenance now shows built-in workflow registration instead of manifest-installed workflow-loader ownership
- canonical workflow background-job provenance id changed to built-in `built-in:workflow`
- internal namespace placement and test placement changed

## Extension residue status

`extensions/workflow-loader/` status:

- deleted
- canonical ownership/bootstrap moved to built-in core

`extensions/workflow-display/` status:

- moved into built-in core ownership and the extension-owned canonical surface disappeared

Why residue does not preserve old framing confusion:

- runtime bootstrap no longer depends on manifest extension install of workflow-loader
- built-in workflow is installed directly by core runtime assembly
- lower and higher workflow owners now live under core components/namespaces

## Capability-model status

After the change workflow is treated as:

- built-in core capability
- not extension-provided canonical behavior

Explicit consequences:

- `psi.agent-session.extension-installs/psi-owned-extension-catalog` no longer lists `psi/workflow-loader`
- minimal manifest entry expansion no longer treats `psi/workflow-loader` as a recognized installed built-in extension
- `.psi/extensions.edn` no longer needs `psi/workflow-loader`
- runtime bootstrap installs workflow directly through built-in core assembly
- live extension registry still contains a workflow registration entry, but its provenance is built-in (`built-in:workflow`) rather than manifest extension ownership

## Residual exception

Residual exceptions kept in this task:

- built-in workflow still reuses extension-registry/API registration machinery under built-in provenance id `built-in:workflow`

Reason:

- reusing extension-registry/API machinery keeps the built-in workflow surface aligned with existing command/tool/prompt registration paths while canonical provenance and bootstrap are now built-in rather than manifest-installed

Why transitional rather than preferred end state:

- canonical runtime bootstrap no longer loads workflow through manifest extension activation
- canonical ownership already lives under built-in core owners
- only shared registration machinery remains

## Review note

Terse review: good reframing progress, but do not close yet.

Follow-up items flagged in implementation review:

- built-in workflow runtime ownership still relies on process-global `state`/`inflight-runs` plus runtime fns seeded from one bootstrapped session; review and fix or explicitly prove session targeting is correct for tool execution after session switches/new sessions
- user-facing docs still describe workflow as an installable `psi/workflow-loader` extension and must be synchronized with the built-in capability model
- built-in workflow still installs through extension-registry/API machinery under `built-in:workflow`; either reduce that residual extension-style modeling further or record it explicitly as a broader residual exception rather than only a compatibility-namespace residue

## 2026-05-24 ambiguity review
- Task scaffolding ambiguity: design requires review of `plan.md`, `steps.md`, and `implementation.md`, but task 176 currently contains only `design.md`, so the review pass cannot tell whether those artifacts are intentionally absent or still required before execution.
  - Added design follow-up to require explicit plan/steps/implementation scaffolding or a documented rationale for omission before design review can be considered complete.

## 2026-05-24 ambiguity follow-up execution
- Completed the newly added ambiguity follow-up by creating `plan.md` and `steps.md`, confirming in `design.md` that standard Munera scaffolding is required for task 176, and preserving `implementation.md` as the execution log surface.

## 2026-05-24 inconsistency review
- New actionable inconsistency: `design.md` now defines the task around resolving single-id identity semantics (canonical id normalization, same-owner vs cross-owner duplicate behavior, post-change lookup/update/unregister targeting, ordering, and compatibility), but `plan.md` and `steps.md` still constrain the task to a scaffolding-only pass and explicitly avoid design-resolution work. That leaves the execution artifacts out of sync with the task's current intent and acceptance criteria.
  - Added design follow-up steps to align plan/steps with the actual design scope before further review or implementation proceeds.

## 2026-05-24 inconsistency follow-up execution
- Rewrote `plan.md` and `steps.md` to match the current task design instead of the earlier scaffolding-only follow-up.
- This pass completed the artifact-alignment follow-up and did not execute implementation work from `steps.md`.

## 2026-05-24 ambiguity review
- New actionable ambiguity: `design.md` requires explicit post-change targeting/compatibility behavior for callers that currently pass `ext-path + id`, but the task artifacts do not distinguish which already-single-id extension-facing API/doc surfaces are intentionally preserved versus which lower-level dispatch or Pathom mutation surfaces remain owner-qualified today and therefore need explicit migration or temporary compatibility rules. Without that surface-by-surface targeting statement, the compatibility scope is still ambiguous across concrete callers.
  - Added a `design-steps.md` follow-up to make the surface-level targeting/compatibility contract explicit and avoid hidden divergence between extension API helpers, session dispatch handlers, query mutations, and docs.

## 2026-05-24 ambiguity follow-up execution
- Reviewed the concrete caller surfaces behind prompt contributions before updating the design:
  - extension-facing API helpers in `components/agent-session/src/psi/agent_session/extensions/api.clj` and user docs in `doc/extensions.md` already expose single-id register/update/unregister helpers
  - built-in workflow prompt contribution registration in `components/agent-session/src/psi/agent_session/workflow/bootstrap.clj` supplies owner provenance internally while still presenting contribution-level single-id targeting
  - lower-level session dispatch handlers in `dispatch_handlers/prompt_handlers.clj` and Pathom mutations in `mutations/prompts.clj` still accept `ext-path` today
  - query/projection surfaces still expose `ext-path` for provenance and prompt-component selection still uses owner allowlisting by extension path
- Updated `design.md` to make the surface-by-surface contract explicit: canonical identity is `id` alone, extension-facing helpers remain single-id-only, lower-level seams may only retain temporary `ext-path` acceptance as provenance/ownership metadata, and query/projection surfaces may expose owner provenance without making it identity.
- Rewrote `plan.md` to focus on this ambiguity follow-up, marked the new `design-steps.md` item done, and kept `steps.md` aligned without executing implementation work from it.

## 2026-05-24 inconsistency review
- New actionable inconsistency: `design.md` now explicitly preserves extension-facing helpers/docs as single-id-only surfaces and limits temporary `ext-path` acceptance to lower-level seams, but `steps.md` still says to inventory prompt-registry APIs, projections, and callers that rely on composite `ext-path + id` identity. That wording is broad enough to imply the extension-facing API/docs themselves still rely on composite identity, which conflicts with the refined design split.
  - Added a `design-steps.md` follow-up to narrow the implementation inventory/remaining work so it targets only lower-level seams, projections, tests/helpers, and other composite-identity-dependent callers.

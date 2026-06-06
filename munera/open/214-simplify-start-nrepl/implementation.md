# Implementation notes

## Design review — architectural fit (ψ)

Reviewed `design.md` for architectural fit against AGENTS.md, META.md, and
doc/architecture.md (fit only; not ambiguity/inconsistency).

Verdict: **fits**. No new actionable architectural-misfit found.

- Direction is a behaviour-preserving local helper extraction (decomplect the
  stdout-suppression interop from server lifecycle / endpoint publication / file
  side effects). Consistent with `compose > monolith`, `simple > complex`, and the
  refactoring/local-change principles.
- State boundary (architecture.md §"State boundary"): nREPL is a *runtime handle*
  whose endpoint is projected into `:state*`. The current code writes the endpoint
  via direct `accessors/set-nrepl-runtime-in!` rather than dispatch — but
  architecture.md explicitly classes this as a remaining direct-mutation pocket
  outside migrated dispatch slices. The design preserves this (behaviour-preserving),
  introducing no new boundary violation; migrating it to dispatch would be scope
  drift (Munera), not a design misfit.
- No new shims/adapters introduced (one-way guideline respected).
- A3 acceptance gate (`gordian gate --fail-on new-cycles,new-high-findings
  --max-new-medium-findings 0`) embeds architectural no-regression into acceptance.
- Phase 0 test net honors `testing-without-mocks` (assert state/outputs; prefer
  real seams over `with-redefs`/`binding`).

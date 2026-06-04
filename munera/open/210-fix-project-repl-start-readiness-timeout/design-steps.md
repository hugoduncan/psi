# Design follow-up — architectural fit

- [x] Locate the stale-`.nrepl-port` guard in the started-mode acquisition layer,
      not in the shared `.nrepl-port` discovery helper, so the discovery
      primitive stays orthogonal (single responsibility) and attach-mode's
      documented `.nrepl-port` fallback semantics are unchanged.
      → Resolved in design.md A1 + acceptance criteria.
- [x] Specify how new observable started-mode outcomes (stale-port rejection
      diagnostic, effective configured timeout) are projected into `:state*`
      via dispatch as canonical instance status (aligning with the existing
      `readiness` / `:last-error` projection), rather than surfaced only as
      ad-hoc op-return data.
      → Resolved in design.md A2 + acceptance criteria (projected onto the
      runtime-owned registry instance — the canonical status surface here —
      not dispatch `:state*`, since subprocess/port I/O is documented
      runtime-owned and does not move under dispatch effects).

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

## Design follow-up — ambiguity

- [x] AMB1: Resolve Q1–Q4 in design.md before plan. Acceptance criteria already
      reference "the confirmed Q1 surface" and "a reasonable default and/or
      configured timeout", so each open question needs a decision: Q1 config key
      name + default value + validation bounds; Q2 stale-port strategy (a/b/c);
      Q3 the empirically-confirmed cause(s); Q4 in-scope-vs-deferred for
      process-exit (stdout/stderr tail) diagnostics.
      → Resolved: "Open questions" section rewritten as "Resolved questions
      (Q1–Q4)" with concrete decisions for each; acceptance criteria updated to
      match.
- [x] AMB2: Reconcile A1 vs Q2. A1 commits to the launch-time/mtime gate as the
      stale-port mechanism, but Q2 still presents it as one open option of three
      and asks to "confirm which". Make A1 the resolution of Q2 (or fold Q2 into
      A1) so the stale-port strategy is stated once, unambiguously.
      → Resolved: Q2 states the combination (pre-launch removal + mtime gate) and
      explicitly says "A1 is the resolution of Q2"; A1 cross-references Q2 as one
      decision, not two.
- [x] AMB3: State explicitly whether `instance-payload` (`ops.clj`) is extended.
      A2 names a new `:readiness-timeout-ms` instance status field "surfaced
      through instance-payload", but instance-payload's fixed projected key list
      lacks it (and any stale-port diagnostic beyond `:last-error`). Specify the
      key(s) added to the projection so "surfaced through instance-payload" has a
      single interpretation.
      → Resolved: A2 now states `instance-payload`'s fixed key list is extended
      with `:readiness-timeout-ms`, and that no new stale-port key is added (it
      rides existing `:last-error`).
- [x] AMB4: Specify the mtime-gate comparison semantics. Define the
      precision/tolerance of "last-modified ≥ launch instant" given coarse
      filesystem mtime vs. ms launch instant, and state whether Q2(a) pre-launch
      `.nrepl-port` removal supplements the gate (Q2c combination) to close the
      same-second / clock-skew race.
      → Resolved: Q2 specifies the gate as last-modified ≥ launch-instant floored
      to whole seconds (tolerates coarse mtime), and that pre-launch removal
      (Q2c combination) makes correctness independent of mtime precision.
- [x] AMB5: Clarify which fixes are required relative to Q3. Acceptance mandates
      both the timeout raise and the stale-port gate while the design warns
      "avoid speculative changes" and Q3 (cause) is unconfirmed. State whether
      both fixes are required regardless of Q3's empirical finding, or whether a
      single-cause finding scopes one of them out.
      → Resolved: Q3 states both fixes ship unconditionally (each an independent
      inspection-proven defect), no single-cause finding scopes either out;
      acceptance criteria add an explicit Q3 line.

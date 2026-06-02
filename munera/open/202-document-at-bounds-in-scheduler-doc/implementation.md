# Implementation notes

## Design ambiguity review — 2026-06-02

Reviewed `design.md` against `doc/scheduler.md`, `resolve-fire-time!`, `validate-delay-ms!`, task 201 findings, and `psi-tool-scheduler-at-resolution-matrix-test`. No new actionable design ambiguities found. The design clearly scopes a doc-only update for absolute `:at` bound behaviour: past/now fires immediately; positive future delays below `min-delay-ms` or above `max-delay-ms` are rejected.

## Design ambiguity review — 2026-06-02 (sub-ms `:at` precision)

Reviewed `design.md` against `resolve-fire-time!`, `millis-until`, `doc/scheduler.md`, task 201 findings, and the `:at` resolution matrix. Found one new actionable ambiguity: the design sometimes says any future `:at` below `min-delay-ms` is rejected, but the implementation validates only a positive **millisecond** delay; sub-millisecond future instants truncate to delay 0 and fire immediately. The doc wording should be clarified around positive resolved millisecond delays (1–999ms) so it does not overclaim all future instants below the minimum are rejected.

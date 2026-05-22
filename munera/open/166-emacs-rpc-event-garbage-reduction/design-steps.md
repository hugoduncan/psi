# Design follow-up steps

- [x] Add the missing `plan.md` and `steps.md`, or explicitly record that this task is still design-only and cannot enter execution until those artifacts exist.
- [x] Define the measurable garbage/CPU success threshold for the optimized hot path: what counter, allocation proxy, or helper-call proof is sufficient to show append-only updates avoided full redraw work.
- [x] Clarify the assistant stream payload contract: whether `assistant/delta` is authoritative as cumulative, incremental, or mixed, and give explicit append-vs-redraw examples for incremental deltas, extending cumulative snapshots, tail churn, and divergent snapshots.
- [x] Specify which hotspot classes are mandatory for this task versus conditional: assistant/thinking only, tool rows only when touched, widget subscriptions only if measured material, and projection only if changed.
- [x] Define the promotion condition and owner for moving from design-only placeholder artifacts to implementation-ready `plan.md` and `steps.md` once ambiguity/inconsistency review produces no new actionable design feedback.
- [x] Define the pre-optimization instrumentation seam: either add named helper wrappers for full redraw, stream property application, and prefix overlay creation before optimization, or explicitly allow primitive-level advice in tests and state which primitives/ranges are authoritative.

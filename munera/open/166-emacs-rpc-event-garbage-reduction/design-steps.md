# Design follow-up steps

- [ ] Add the missing `plan.md` and `steps.md`, or explicitly record that this task is still design-only and cannot enter execution until those artifacts exist.
- [ ] Define the measurable garbage/CPU success threshold for the optimized hot path: what counter, allocation proxy, or helper-call proof is sufficient to show append-only updates avoided full redraw work.
- [ ] Clarify the assistant stream payload contract: whether `assistant/delta` is authoritative as cumulative, incremental, or mixed, and give explicit append-vs-redraw examples for incremental deltas, extending cumulative snapshots, tail churn, and divergent snapshots.
- [ ] Specify which hotspot classes are mandatory for this task versus conditional: assistant/thinking only, tool rows only when touched, widget subscriptions only if measured material, and projection only if changed.

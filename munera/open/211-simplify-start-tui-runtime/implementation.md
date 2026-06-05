# Implementation Notes

2026-06-04 architecture-fit review: design fits the current app-runtime/TUI architecture. It keeps shared session/navigation/UI-domain semantics in `app-runtime`, treats TUI-specific work as callback/options wiring for the TUI entrypoint rather than terminal rendering, preserves the provider install/clear lifetime, and constrains any helper extraction to the target unit's local blast radius. It also respects the current partial-dispatch migration by requiring behaviour preservation instead of broad boundary movement. No new actionable architectural misfit found; no `design-steps.md` follow-up was created.

PASS_STATUS: REVIEW_COMPLETE

2026-06-04 ambiguity review: found one new actionable ambiguity (B1). A2/A4 use the selector's `(ns, var, arity, line)` key and pin the target to line `603`, while the allowed refactor may insert/extract local helpers and move the `defn`. That makes the after metric row potentially missing or shifted, and A2 does not define added/deleted-unit handling for the metric-derived touched set. Added a `design-steps.md` follow-up to specify the executable comparison rule before planning/refactoring.

PASS_STATUS: ACTIONABLE_FEEDBACK


2026-06-04 ambiguity follow-up B1 executed: clarified the burden-comparison identity rules in `design.md`. A2 now reconciles before/after Gordian rows by unique logical key `(file, ns, var, arity)` when that key is unique on both sides, falls back to the selector full key `(ns, var, arity, line)` for ambiguous/unpaired rows, and counts added/deleted units with zero on the missing side. A4 no longer requires preserving line `603`; that line is baseline selector provenance, while the after target is the unique `(components/app-runtime/src/psi/app_runtime.clj, psi.app-runtime, start-tui-runtime!, 5)` row. If the after target is missing or duplicated, A4 fails.

PASS_STATUS: REVIEW_COMPLETE

2026-06-04 inconsistency review: no new actionable inconsistency found. Checked `design.md` internally and against the referenced target code, existing sibling tests, `before-local.json`, `META.md`, and `doc/architecture.md`. The burden-key clarification from B1 keeps A2/A4 executable, the coverage hint matches the sibling test surface, and the blast-radius/architecture constraints are mutually consistent with app-runtime ownership and TUI adapter boundaries. No `design-steps.md` follow-up added.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 planning from stable design: created `plan.md` and `steps.md`. Design was complete enough to plan; no unresolved blocking ambiguity found. Noted the resolved B1 metric-key clarification as non-blocking planning context. Plan slices are safety-net assessment, characterization-if-needed, local lifecycle/data-shape refactor, behaviour verification, Gordian burden/architecture gates, and final blast-radius review.

PASS_STATUS: PLAN_COMPLETE

2026-06-05 plan/steps ambiguity review: no new actionable ambiguity found. Re-read `plan.md`, `steps.md`, `design.md`, this implementation log, `components/app-runtime/src/psi/app_runtime.clj` around `start-tui-runtime!`, `components/app-runtime/src/psi/app_runtime/tui_wiring.clj`, and the direct sibling app-runtime tests. The execution order is singular (safety-net assessment → characterize gaps if any → local refactor → tests/lint → Gordian gates → blast-radius note), the resolved B1 burden-key rule is referenced where needed, and the potentially under-covered callback/nullable/current-session behaviours are deliberately handled by the Phase 0 coverage-map gate rather than left as undefined implementation choices. No unchecked `steps.md` follow-up added.

PASS_STATUS: REVIEW_COMPLETE

2026-06-05 plan/steps inconsistency review: no new actionable inconsistency found. Checked `plan.md` and `steps.md` against `design.md`, `design-steps.md`, prior implementation notes, `start-tui-runtime!`, `tui_wiring.clj`, and the sibling app-runtime tests. Slice order, Phase 0 gating, provider lifetime, `/new` focus targeting, nullable execution-mode coverage, callback/wiring assembly, A2/A4 burden-key reconciliation, and A3 gate command all align across task artifacts. No unchecked `steps.md` follow-up added.

PASS_STATUS: REVIEW_COMPLETE

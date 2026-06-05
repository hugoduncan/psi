# Implementation Notes

2026-06-04 architecture-fit review: design fits the current app-runtime/TUI architecture. It keeps shared session/navigation/UI-domain semantics in `app-runtime`, treats TUI-specific work as callback/options wiring for the TUI entrypoint rather than terminal rendering, preserves the provider install/clear lifetime, and constrains any helper extraction to the target unit's local blast radius. It also respects the current partial-dispatch migration by requiring behaviour preservation instead of broad boundary movement. No new actionable architectural misfit found; no `design-steps.md` follow-up was created.

PASS_STATUS: REVIEW_COMPLETE

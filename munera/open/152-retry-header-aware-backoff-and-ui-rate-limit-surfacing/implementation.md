2026-05-14 ambiguity review
- Missing implementation.md at review start; created for review log continuity.
- Ambiguity: task requires new actionable follow-up items in `design-steps.md`, but task only had `steps.md`; review uses new `design-steps.md` as requested.
- Ambiguity: design says stale retry metadata must clear when retry waiting ends or is superseded, but does not define whether successful non-retry terminal completion (`on-agent-done`) must always clear it as the canonical owner rather than relying only on later replacement.
- Ambiguity: design requires one canonical retry-metadata surface across backend projections and RPC, but does not specify whether `session-summary`, Pathom resolvers, and RPC `session/updated` must expose the same nested shape/field names or may diverge into UI-specific flattened payloads; define one authoritative shape.
- Ambiguity: design requires Emacs and TUI to render retry timing/rate-limit information, but does not pin the exact backend-owned TUI-visible surface (status line vs footer usage/session-activity vs other summary surface), leaving acceptance subjective.
- Ambiguity: design requires provider headers to reach the retry scheduler, but does not identify the canonical error/result shape that must carry headers across transport → turn/runtime → session; without that contract, "preserve headers" is underspecified.

2026-05-14 ambiguity follow-up execution
- Completed all newly added ambiguity design-steps in `design-steps.md`.
- Updated `design.md` to fix the canonical retry metadata shape as one shared nested `:retry` map, including nested normalized `:rate-limit` fields.
- Defined projection parity explicitly: session summary, Pathom/resolvers, and RPC `session/updated` all preserve the same `:retry` shape/field naming.
- Defined lifecycle ownership explicitly: session/runtime retry owner owns active retry metadata; `on-agent-done` and other terminal non-retrying completion paths must clear `:retry`.
- Defined objective UI acceptance surfaces: TUI uses the existing session summary/status line surface; Emacs uses the existing session/status diagnostics surface.
- Defined the transport-to-session propagation contract explicitly: the retry scheduler consumes provider response headers from `:provider-error/headers` on the terminal error/result map passed to `:on-retry-triggered`.
- Updated `plan.md` to record the resolved ambiguity decisions so implementation can proceed without reopening these questions.
- Did not touch `steps.md` execution items per task instruction.

2026-05-14 inconsistency review
- Inconsistency: design/acceptance now require one explicit tested backend rule for numeric `RateLimit-Reset` interpretation, but plan only lists that rule as a decision to make during implementation and steps only mention implementing/documenting a rule. This leaves task intent, plan, and execution checklist out of sync on whether the rule is already fixed versus still open; resolve by choosing and recording the canonical rule in design/plan and adding proof expectations against that exact rule.

2026-05-14 inconsistency follow-up execution
- Fixed the open `RateLimit-Reset` inconsistency in task artifacts.
- Updated `design.md` to choose one canonical numeric interpretation rule: `>= 1000000000000` → epoch ms; otherwise `>= 1000000000` → epoch seconds; otherwise relative seconds from now.
- Updated `plan.md` so the reset rule is no longer deferred to implementation.
- Tightened proof expectations in task artifacts so focused tests must assert each branch of the canonical reset rule directly.
- No blocking reason; newly added design-step completed.

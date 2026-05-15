2026-05-14 task created
- Created task artifacts for tool-specifiable compact tool header formatting.
- No implementation work has been performed yet.
- Initial design is grounded in current built-in-only header special casing in TUI and Emacs, existing tool-definition normalization, and the user-requested direction of a tool-specifiable request/header formatter plus a shared backend invocation path for RPC and TUI.

2026-05-14 mechanism fixed
- Chose mandatory executable `:format-request` on canonical normalized tool definitions as the canonical mechanism.
- Chose a single-map input contract: `{:tool :tool-name :parsed-args :arguments :details}`.
- Chose runtime-only preservation: normalization/registration keeps `:format-request`, while provider-facing and agent-core tool projections must exclude it.
- Chose fallback semantics: nil/blank/invalid/throwing formatter results fall back to the default tool display-name summary, but missing formatter metadata is no longer a normal accepted case.
- Chose frontend convergence in this slice: TUI and Emacs both migrate to the shared compact header surface.
- Chose RPC payload direction: RPC must carry the preformatted compact header string because Emacs cannot invoke the Clojure formatter directly.
- User added `psi-tool-format.md`; treat it as authoritative acceptance input for `psi-tool` header formatting in this task.
- User called out other tools to audit, especially built-in `delegate`; task now requires explicit delegate formatter design/proof rather than leaving it as a fallback-only name.
- Added `delegate-format.md` as the authoritative task-local acceptance spec for delegate compact headers, with an 80-character maximum line target.
- Added `work-on-format.md` as the authoritative task-local acceptance spec for work-on compact headers, summarizing description plus optional base branch within 80 characters.
- User clarified that `edit-clj` should use built-in `edit` request-summary semantics while preserving its own `edit-clj` identity in the rendered header.
- Fixed shared-helper owner: compact call-summary computation belongs under `components/tool-runtime/`, with `psi.tool-runtime.call-summary` as the preferred namespace direction.

2026-05-14 ambiguity review
- Ambiguity: mandatory `:format-request` validation is underspecified for rollout scope. Design/plan require canonical registrations to reject missing formatters, but do not say whether this task must migrate every currently registered tool or introduce a narrowly scoped compatibility allowlist/shim during transition.
- Ambiguity: RPC/backend acceptance surface for the canonical preformatted compact header remains underspecified. Per-tool specs name transport-safe `:call-summary`, but design/plan do not identify the authoritative event/payload shape(s) that must carry it.

2026-05-14 ambiguity follow-up
- Resolved rollout scope in design/plan: this task must migrate every currently registered built-in and owned extension tool in the runtime tool catalog onto mandatory `:format-request`; only named, task-documented, temporary migration shims are allowed during in-flight rollout, and none may remain at task completion.
- Resolved RPC/backend transport surface in design/plan: canonical tool lifecycle events/payloads carry the preformatted compact header under `:call-summary`, including pre-completion `tool/executing` and the RPC-facing tool row payloads derived from that lifecycle data.

2026-05-14 inconsistency review
- Inconsistency: design fixes Emacs migration into this task and makes RPC/backend `:call-summary` authoritative for Emacs consumption, but plan verification still says “any follow-on Emacs decision documented,” implying Emacs migration could remain undecided or deferred.

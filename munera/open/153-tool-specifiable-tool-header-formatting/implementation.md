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

2026-05-14 inconsistency follow-up
- Updated plan verification to remove the stale follow-on wording and require proof in this task that Emacs consumes RPC/backend `:call-summary`, with no remaining local compact-header tool-name dispatch in the canonical tool-row path.

2026-05-15 implementation landed
- Added lower shared owner `components/tool-runtime/src/psi/tool_runtime/call_summary.clj` for canonical compact tool-call header formatting.
- Implemented mandatory runtime-preserved `:format-request` support in normalized tool defs and registration-time rejection for extension tool defs missing that fn.
- Kept `:format-request` runtime-only by preserving it canonically while leaving agent-core/provider projections unchanged.
- Migrated built-ins and owned extensions in this slice onto formatter ownership:
  - built-ins: `read`, `edit`, `write`, `bash`, `psi-tool`, `delegate`
  - extensions: `work-on`, `edit-clj`
  - test/demo extension tools: `hello-upper`, `hello-wrap`
- Implemented canonical per-tool compact header contracts for `psi-tool`, `delegate`, `work-on`, and `edit-clj` per task-local specs.
- Moved canonical header computation into tool-runtime lifecycle emission so tool events now carry transport-safe `:call-summary` on `tool/start`, `tool/executing`, `tool/update`, and `tool/result`.
- Wired TUI state/rendering to consume event `:call-summary` instead of built-in-only header-name dispatch.
- Wired RPC event projection to require and emit `:call-summary` for tool lifecycle events.
- Wired Emacs live event handling and switch-time rehydration to consume backend-provided `:call-summary`; retained local summary derivation only as compatibility fallback when summary is absent.
- Extended tool lifecycle telemetry summaries and turn/app transcript rehydration to preserve `:call-summary`.
- Boundary after landing: compact single-line call summaries are authoritative from tool defs/backend `:call-summary`; full custom extension renderers remain separate for expanded/custom body rendering.
- Verification green:
  - `clojure -M:test --focus psi.tool-registry.defs-test --focus psi.tool-registry.registry-test --focus psi.tool-runtime.call-summary-test --focus psi.tool-runtime.core-test`
  - `clojure -M:test --focus psi.rpc.events-test --focus psi.agent-session.eql-introspection-test --focus psi.tui.app-view-runtime-test`
  - `bb emacs:test components/emacs-ui/test/psi-rpc-test.el components/emacs-ui/test/psi-streaming-runtime-test.el components/emacs-ui/test/psi-streaming-transcript-test.el components/emacs-ui/test/psi-tool-output-mode-test.el`

2026-05-15 implementation review
- No new actionable implementation issues found.
- Reviewed shared formatter ownership in `components/tool-runtime/src/psi/tool_runtime/call_summary.clj`, mandatory registration enforcement in `components/tool-registry/src/psi/tool_registry/{defs,registry}.clj`, backend event transport in `components/tool-runtime/src/psi/tool_runtime/core.clj` and `components/rpc/src/psi/rpc/events.clj`, plus TUI/Emacs consumption in `components/tui/src/psi/tui/tool_render.clj` and `components/emacs-ui/psi-tool-rows.el`.
- Re-ran focused verification green:
  - `clojure -M:test --focus psi.tool-runtime.call-summary-test --focus psi.tool-runtime.core-test --focus psi.tool-registry.defs-test --focus psi.tool-registry.registry-test --focus psi.rpc.events-test --focus psi.tui.app-view-runtime-test`
  - `bb emacs:test components/emacs-ui/test/psi-rpc-test.el components/emacs-ui/test/psi-streaming-runtime-test.el components/emacs-ui/test/psi-streaming-transcript-test.el components/emacs-ui/test/psi-tool-output-mode-test.el`

2026-05-14 follow-up execution pass
- Read `steps.md`, `implementation.md`, `design.md`, and `plan.md` to execute newly added review follow-up work.
- Found no unchecked follow-up items in `steps.md` and no actionable work added by the preceding review pass.
- No task changes were required; task remains ready for judge/close decision.

2026-05-14 test review
- No new actionable test issues found.
- Reviewed `components/tool-runtime/test/psi/tool_runtime/call_summary_test.clj`, `components/tool-registry/test/psi/tool_registry/registry_test.clj`, `components/tui/src/psi/tui/tool_render.clj`, `components/emacs-ui/psi-tool-rows.el`, and `components/tool-runtime/src/psi/tool_runtime/call_summary.clj` against the task test-review contract.
- Current focused proof covers formatter parity and fallback behavior, registration enforcement, TUI shared-helper use, and Emacs consumption of backend `:call-summary`; no missing design-behaviour cluster was found in the reviewed test surface.
- Explicitly no new actionable feedback from this test review pass.

2026-05-14 test-shaper review
- No new actionable test-shaping issues found.
- Reviewed task-local formatter specs plus `components/tool-runtime/{src,test}/psi/tool_runtime/call_summary*.clj`, `components/rpc/src/psi/rpc/events.clj`, `components/rpc/test/psi/rpc_prompt_test.clj`, and Emacs tool-row/runtime transcript tests for clarity, signal, and robustness.
- Coverage already hits the key behavior partitions the task cares about: per-tool formatter parity, fallback on formatter failure, registration enforcement, RPC `:call-summary` transport, and Emacs preference for backend-provided summaries with compatibility fallback.
- Explicitly no new actionable feedback from this test-shaper pass.

2026-05-14 follow-up execution pass 2
- Re-read `steps.md`, `implementation.md`, `design.md`, and `plan.md` to execute newly added actionable follow-up work from the preceding review pass.
- Found no unchecked steps and no newly added actionable follow-up items from the preloaded review result.
- No task artifact changes were needed beyond recording this pass; task remains ready for judge/close decision.

Initialized from user request on 2026-04-20.

Inspection notes

- `extensions/workflow_loader.clj` already contains the canonical pieces needed for the fix:
  - `delegate-run` accepts `:include_result_in_context`
  - `execute-async!` threads that flag into async completion
  - `on-async-completion!` injects transcript messages when the flag is true
- The `/delegate` command currently does not opt into that path. Its handler calls:
  - `(delegate-run {:workflow workflow :prompt prompt :mode "async"})`
- `extensions/workflow_loader/delivery.clj` shows the intended transcript behavior clearly:
  - query last role in the originating session
  - maintain user/assistant alternation
  - append `"Workflow run <id> result:"` as a user message
  - append the workflow result text as an assistant message
- `extensions/workflow_loader/orchestration.clj` also confirms an important invariant for this task:
  - when `include_result?` is true, async completion injects messages into chat
  - when `include_result?` is false, it instead appends a custom `delegate-result` entry
  - so the command-path fix should reuse the existing flag to avoid double delivery

Coordination note

- This task is narrowly about the `/delegate` slash-command UX.
- The lower-level `delegate` tool already supports result injection through `include_result_in_context`; this task should converge the command path onto that existing capability rather than inventing a parallel mechanism.
- Focus proof on the command handler path so future regressions cannot hide behind tool-only coverage.
- Best likely regression-test home is alongside existing `/delegate` command tests in `extensions/workflow_loader_test.clj`.

Implementation notes

- Updated the `/delegate` command handler in `extensions/workflow-loader/src/extensions/workflow_loader.clj` so its `delegate-run` call now passes `:include_result_in_context true` together with `:mode "async"`.
- This keeps the command path on the existing canonical async path:
  - `delegate-run`
  - `execute-async!`
  - `on-async-completion!`
  - `inject-result-into-context!`
- Added focused command-path regression coverage in `extensions/workflow-loader/test/extensions/workflow_loader_test.clj`.
- The new test proves:
  - `/delegate` still returns its immediate acknowledgement string
  - successful async completion queries the originating session explicitly
  - completion appends the existing bridge-shaped user + assistant messages into that originating session
  - the assistant-side message carries the workflow result text
  - background-job start and terminal marking still occur
  - fallback `psi.extension/append-entry` delivery is not used for this successful command-path case

Verification

- `clojure -M:test --focus extensions.workflow-loader-test --focus extensions.workflow-loader-delegate-test`
- Result: `28 tests, 93 assertions, 0 failures.`

Review note

- Code-shaper review: accept as-is; minor follow-up opportunities only around making the command-path test less brittle and less sleep-based.
- Follow-up shaping now applied:
  - command-path test now captures the created run-id directly from mocked `psi.workflow/create-run`
  - command-path async proof now waits on observed completion conditions via a small local helper instead of fixed sleeping
  - `/delegate` callsite now carries a short intent comment about conversational result return

Follow-up bugfix after live validation

- Live validation showed a deeper propagation bug: `/delegate` had been fixed to inject workflow results into chat, but some successful delegated runs still produced an empty injected assistant message.
- Root cause was not just delivery. Workflow step execution and workflow judge execution were submitting prompts via `prompt-in!` and then rereading the child-session journal with `last-assistant-message-in` to recover the result text.
- That journal reread was the wrong boundary for bounded workflow callers. The canonical prompt path already has the exact turn result available as `:execution-result/assistant-message`; workflow/judge code should consume that directly instead of depending on a later journal read.

Follow-up implementation notes

- Added `prompt-execution-result-in!` in `components/agent-session/src/psi/agent_session/prompt_control.clj`.
- Extended `:session/prompt-prepare-request` handling so callers can opt into receiving the executed turn result (`:return-execution-result? true`) while staying on the same canonical dispatch/runtime path.
- A second live validation exposed one more seam in that opt-in path: the first effect emitted by prompt preparation was still `:memory/recover-query`, so dispatch effect-return semantics were handing workflow callers the memory-recovery result instead of the prompt execution result. Fixed by introducing a combined `:runtime/recover-query-prompt-execute-and-record` effect for the execution-result-returning path so the returned effect result is the actual completed turn.
- Updated `workflow_statechart_runtime.clj` so workflow step execution now uses `prompt-execution-result-in!` and records the assistant message from `:execution-result/assistant-message` directly.
- Updated `workflow_judge.clj` so judge prompts and judge retries also use `prompt-execution-result-in!` instead of rereading the journal.
- Kept the earlier defensive hardening in place:
  - canonical workflow result projection trims blank `:outputs :text` to nil
  - workflow-loader async completion trims blank `:psi.workflow/result` to nil before deciding whether to inject into chat

Follow-up verification

- `clojure -M:test --focus psi.agent-session.workflow-judge-test --focus psi.agent-session.workflow-statechart-runtime-test --focus psi.agent-session.workflow-execution-test --focus psi.agent-session.prompt-lifecycle-test --focus psi.agent-session.mutations.canonical-workflows-test --focus extensions.workflow-loader-delegate-test --focus extensions.workflow-loader-test`
- Result: `91 tests, 370 assertions, 0 failures.`

Persistent live RPC verification

- Added `components/rpc/test/psi/rpc_real_delegate_command_test.clj` proof for the real bootstrapped RPC command path.
- The immediate-ack test documents an important harness boundary: `support/run-loop` closes stdin after the command request, so it proves the canonical `command-result` ack but cannot prove later async bridge events on that same connection because the transport-owned external-event loop is torn down at EOF.
- Added a second persistent-connection RPC integration test using `PipedReader`/`PipedWriter` so the same live RPC connection stays subscribed long enough to observe the real async completion bridge sequence.
- That persistent test proves:
  - immediate `command-result` ack first
  - later `assistant/message` user marker `Workflow run <id> result:`
  - later `assistant/message` assistant result text
  - no visible `(workflow context bridge)` filler on the live RPC surface
  - originating session transcript state matches the same semantic sequence

Adapter/live verification additions

- Added TUI-focused rendering and role proofs:
  - `components/tui/test/psi/tui/app_external_message_role_test.clj`
  - `components/tui/test/psi/tui/delegate_live_sequence_test.clj`
  - `components/tui/test/psi/tui/real_delegate_command_path_test.clj`
- Added Emacs-focused rendering and role proofs:
  - `components/emacs-ui/test/psi-delegate-transcript-role-test.el`
  - `components/emacs-ui/test/psi-delegate-command-and-result-test.el`
  - `components/emacs-ui/test/psi-delegate-live-sequence-test.el`
- Added no-filler delivery proof:
  - `components/agent-session/test/psi/agent_session/workflow_loader_delivery_test.clj`

Live end-to-end verification tasks

- Added a real TUI tmux scenario for `/delegate`:
  - `components/tui/test/psi/tui/test_harness/tmux_delegate.clj`
  - wired into `components/tui/test/psi/tui/tmux_integration_harness_test.clj`
- Updated the tmux harness to use `mise exec tmux -- ...` automatically when `mise` is available so live TUI integration checks work in environments where tmux is tool-managed rather than globally on PATH.
- Added a focused real Emacs `/delegate` end-to-end harness:
  - `components/emacs-ui/test/psi-delegate-e2e-test.el`
  - this harness intentionally uses the repo-local backend command (`bb bb/psi.clj -- --rpc-edn`) so the live Emacs check validates the current worktree code rather than an installed `psi` binary.

Canonical verification commands now available

- `bb tui:delegate:e2e`
  - runs the live TUI tmux `/delegate` scenario against the current worktree
- `bb emacs:delegate:e2e`
  - runs the focused Emacs `/delegate` end-to-end harness against the current worktree backend
- `bb delegate:e2e`
  - aggregate task that runs both live `/delegate` end-to-end checks

Latest verification results

- `clojure -M:test --focus integration --focus psi.tui.tmux-integration-harness-test/tui-tmux-delegate-live-sequence-scenario-test`
  - green
- `emacs -Q --batch -L components/emacs-ui -l components/emacs-ui/test/psi-delegate-e2e-test.el -f psi-delegate-e2e-run`
  - `psi-delegate-e2e:ok`
- `bb tui:delegate:e2e`
  - green
- `bb emacs:delegate:e2e`
  - `psi-delegate-e2e:ok`
- `bb delegate:e2e`
  - green
- `clojure -M:test --focus psi.rpc-real-delegate-command-test --focus psi.rpc-delegate-bridge-test --focus psi.rpc-delegate-command-and-bridge-order-test --focus psi.tui.real-delegate-command-path-test --focus psi.tui.delegate_live_sequence_test --focus psi.agent-session.workflow-loader-delivery-test`
  - green after the live-e2e additions as well

Architectural note

- The sequence of fixes indicates the underlying issue was broader than a missing `/delegate` flag.
- The same user-visible concept — delegated workflow result delivery — was represented in several partially overlapping forms:
  - workflow run result
  - child-session assistant journal entry
  - parent-session injected transcript messages
  - background-job terminal payload
  - RPC `assistant/message` events
  - TUI/Emacs external-message rendering
- The clearest root-boundary problem was that workflow execution and workflow judge code were recovering result text by rereading the child-session journal via `last-assistant-message-in` after prompt submission.
- That made persistence/journal order act as an API for bounded workflow callers. The canonical semantic result already existed at prompt execution time as `:execution-result/assistant-message`; bounded callers should consume that value directly.
- `prompt-execution-result-in!` is therefore not just a bug fix convenience. It is the architectural correction that restores the right ownership boundary:
  - prompt execution returns the semantic turn result
  - persistence records history
  - transcript/UI projection renders the result
  - callers do not reconstruct execution semantics from storage
- A second exposed seam was publication policy. Async completion could publish via transcript injection, append-entry fallback, notification, background-job terminal payload, and adapter event emission. That worked, but it meant result visibility semantics were spread across several side-effect channels.
- A third exposed seam was adapter contract drift. RPC, TUI, and Emacs each needed explicit convergence tests for ordering, role preservation, and blank/filler suppression, which suggests the cross-adapter external-message contract was previously too implicit.
- Working diagnosis:
  - there was no single explicit canonical abstraction for delegated-result publication across execution, persistence, and UI projection boundaries.
  - the fix set improved this by converging on direct execution results, explicit parent-context injection, and cross-adapter sequence tests.
- Suggested future shaping direction:
  - keep direct execution-result return as the bounded-caller contract
  - treat journals as audit/history, not semantic recovery
  - define a single shaped delegated-result publication model that adapters/projectors consume consistently

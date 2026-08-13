# Design steps — architectural review (design-review session, turn 1)

- [x] Define one canonical owner for delegated-child failure normalization at the
      workflow-runtime delegate-step boundary. The parent workflow attempt should
      receive one structured failure envelope derived from the persisted child
      run's canonical failure surfaces (including step attempt
      `:execution-error` and `:terminal-outcome`), while agent-session/tool/API
      layers only project or render that envelope. Do not let each caller inspect
      child sessions or independently synthesize competing error semantics. This
      preserves the single-source/one-way architecture and keeps generic workflow
      execution semantics in workflow runtime rather than in an adapter.

- [x] Replace the suggested raw stack-trace propagation with a bounded, redacted,
      deterministic delegated-failure diagnostic contract. Preserve an actionable
      human-readable message plus stable structured cause metadata (for example
      reason and child run/step/attempt identity), retain a generic fallback when
      no safe cause is available, and do not expose arbitrary exception data,
      transcripts, provider payloads, or session internals through the parent
      `:error` surface. Raw stack traces are unstable implementation detail and
      may leak sensitive/runtime-local data across the child-parent boundary.

# Design steps — ambiguity review (design-review session, turn 2)

- [x] Identify the exact observed execution and caller-visible boundary behind
      `:error "Delegated workflow failed"` and `:result nil`. The literal generic
      message currently originates when a workflow `:delegate` step normalizes a
      failed child run, but the design's phrase "delegation runner/tool" does not
      name whether acceptance is observed at the parent workflow execution
      mutation, the registered `delegate` tool result, or another API projection.
      Name the authoritative end-to-end path and the boundary at which regression
      proof must inspect the result, without moving failure semantics into that
      adapter.

- [x] Define deterministic cause-selection precedence when a failed delegated run
      exposes more than one candidate diagnostic: child step-attempt
      `:execution-error`, child `:terminal-outcome`, and a nested delegated-child
      failure. State which attempt/terminal step is selected, whether nested
      delegate failures are recursively unwrapped or retained as immediate cause
      metadata, and when the generic fallback is used. "Propagate the specific
      error" is otherwise not singular for common failed-run shapes.

- [x] Specify the exact parent-visible failure contract after propagation. State
      whether the parent workflow/delegate operation remains `:failed`, whether
      `:result` intentionally remains nil/absent on failure, which part of the
      canonical structured failure envelope is rendered into the public `:error`
      string, and which representative cases must be proven (at minimum an
      attempt execution error, a terminal-outcome-only failure, and a failure
      with no safe actionable cause). The current design mentions both `:error`
      and `:result nil` but only explicitly proposes changing `:error`.

- [x] Make the canonical public-message normalization contract testable and
      singular. Define the exact target/step prefix and terminal-outcome rendering,
      the allowlist behind "safe keyword" and "bounded numeric/count metadata",
      the observable treatment of stack frames and local/secret-bearing paths,
      the normalization/redaction/truncation order, and the exact truncation marker
      (including how it fits within 512 characters). Behavioural examples may
      define the contract without prescribing implementation regexes. As written,
      conforming implementations can persist different messages or disagree on
      whether unsafe input falls back.

- [x] Define the exact source and shape of `:nested-cause` identity. A delegated
      execution error has both outer `:execution-error :reason`
      (`:delegated-workflow-failed`) and inner `:delegate-failure :reason`; state
      which one is copied, confirm that run/target/step/attempt fields come from
      the immediate inner `:delegate-failure`, and define behaviour for a partial
      or malformed immediate envelope. This avoids two valid interpretations of
      the one-level non-recursive nesting contract.

- [ ] Define execution-error eligibility and fallback precedence around
      sanitization. "Safe nonblank `:execution-error :message`" is undefined and
      can mean either that any nonblank string is selected before sanitization or
      that safety/actionability is tested first. Those readings differ when a raw
      message contains redactable credentials or sanitizes to placeholders only:
      the design can either fall back immediately or continue to an actionable
      terminal outcome. State the singular selection/normalization order for
      non-string, blank, redactable-but-actionable, and sanitized-nonactionable
      messages.

- [ ] Specify the observable delegated-failure contract for the in-scope
      `psi.workflow/resume-run` path. Scope requires projection through
      execute/resume, but the parent-visible contract and acceptance criteria
      prove only `psi.workflow/execute-run`. State whether a resumed run that
      terminalizes with a delegated failure returns the same canonical terminal
      attempt message in `:psi.workflow/error`, including retry-history
      selection, without implying a new result field on the existing resume
      mutation.

# Design steps — inconsistency review (design-review session, turn 3)

- [ ] Reconcile the canonical envelope's nil-versus-omitted optional fields. The
      envelope shape currently shows `:reason`, `:step-id`, and `:attempt-id`
      present with nil when unavailable, while immediate-envelope recognition
      says those fields are optional nonblank values and nil optional fields are
      omitted. Define one persisted parent `:delegate-failure` shape and use the
      same rule when recognizing it as an immediate nested envelope, so exact-map
      tests and nested metadata validation agree.

- [x] Reconcile the parent-visible claim that async completion, background-job,
      notification, and append-entry projections "reuse the same error string"
      with their current public shapes. Completion/background-job payloads carry
      the canonical string as an `:error` value, but notification and append-entry
      text embed it inside workflow/status/run context via
      `completion-notification-text` and `completion-entry-content`; they cannot
      all be exactly equal to the 512-character envelope message. State per
      surface whether equality applies to a structured `:error` field or whether
      the canonical message is embedded unchanged in surrounding projection text,
      and make the acceptance wording match without introducing new normalization.

- [x] Reconcile the design's blanket claim that delegated failure details are
      "swallowed" with the current two-path behavior. For a failed child run,
      `delegate-run-runtime-result` preserves `:terminal-outcome` under the parent
      failure payload's `:details`, but falls back to only `{:status :failed}`
      when the child has attempt-level `:execution-error`; later,
      `workflow-execution/execution-result` and `run-failure-error` render only
      the parent attempt's generic `:message`, so even retained terminal details
      do not reach public `:error`. Update the problem/root-cause description to
      distinguish (a) attempt diagnostics lost at delegate normalization from
      (b) terminal diagnostics retained canonically but lost at public
      projection, and require proof for both paths. This aligns the design with
      the referenced runtime artifacts without changing its scope.

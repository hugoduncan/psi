# 250 — Plan

## Approach

Implement delegated-failure diagnostics once at the workflow-runtime boundary and
carry the resulting persisted envelope upward without reconstruction. Keep the
normalization and child-cause selection logic pure and state-based so the exact
message, metadata allowlist, fallback behavior, and bounds can be exhaustively
table-tested without sessions or adapters.

### Key decisions

- Add a focused lower-runtime delegated-failure module rather than expanding
  `statechart_runtime/delegate.clj` with the sanitizer and selection algorithm.
  The module will own deterministic terminal step/attempt selection, safe reason
  validation, component sanitization, nested-envelope recognition, public-message
  assembly, and the exact canonical envelope constructor.
- Reuse the lower-runtime terminal attempt selector at both legitimate selection
  sites: child-run normalization in the delegate-step boundary and parent-run
  envelope handoff in the agent-session execution facade. Only the delegate-step
  boundary interprets child causes or constructs delegated-failure envelopes;
  the facade reads the selected parent attempt's persisted `:execution-error`
  map verbatim.
- Keep the current parent attempt recording path. Its existing
  `record-attempt-execution-failure` behavior already persists execution-error
  maps verbatim, so the delegate failure payload should contain the canonical
  envelope directly rather than add a second persistence shape.
- Add private `:terminal-execution-error` to the facade result while preserving
  the exact public `:steps-executed` entries. Execute/resume will use this handoff
  only when its reason is `:delegated-workflow-failed`; existing non-delegated
  terminal-outcome wording remains unchanged.
- Prove behavior with real immutable run maps and existing nullable/in-process
  workflow harnesses. Assert returned state and values, not collaborator calls;
  do not inspect child sessions, transcripts, or provider internals.
- Treat projection code as pass-through. Change registered-tool or async code
  only if end-to-end tests show that it alters or replaces the canonical message.
- Add an `[Unreleased]` `Fixed` changelog entry because actionable delegated
  workflow failures are user-visible behavior. No broader workflow documentation
  change is expected unless implementation discovers an existing statement that
  becomes inaccurate.

## Risks

- The lexical sanitizer is security-sensitive: a boundary error can either leak
  sensitive text or over-redact an actionable cause. Mitigate with table-driven
  positive/negative cases for every specified span type, delimiter, precedence,
  punctuation, actionability, and Unicode-code-point bound.
- Regex-only implementation may be difficult to make precedence-aware and exact
  for quoted credentials, token punctuation, Windows/UNC paths, and Unicode
  boundaries. Prefer a deterministic left-to-right scanner with small pure span
  recognizers where that is clearer than a compound expression. Scanner
  boundary/actionability traversal must use Unicode code points, not Clojure
  `char` coercion, so supplementary letters cannot throw or evade actionability.
- Retry and terminal selection can accidentally depend on map iteration or pick
  historical failures. Derive candidates only from effective `:step-order` and
  ordered attempt vectors, and assert exact selected identities with deliberately
  scrambled step maps. Target-authored workflow compilation does not preserve an
  arbitrary `:retry-policy`, so prove a child retry's persisted shape with a
  state-based run map rather than assuming an authored test definition can set it.
- Retention cleanup can remove the canonical parent run before mutations project
  its error. Select and return the exact envelope in `workflow-execution` before
  cleanup, then test execute and resume with completed-run retention zero.
- Adding the private facade key could accidentally leak into `:steps-executed` or
  Pathom output. Assert the existing step projection's exact key set and keep the
  private value outside mutation output.
- Existing non-delegated failures, blocked/cancelled/removed children, successful
  yields, retries, and async wrapper text share this path. Focused regression
  tests must show these semantics and shapes remain unchanged.
- `canonical_workflows_test.clj` is already near the project file-size limit.
  Put substantial new retention/projection scenarios in a focused adjacent test
  namespace if needed rather than growing that file beyond the standard.

## Slice order

1. **Pure normalization contract** — add the lower-runtime selector, sanitizer,
   nested-envelope recognizer, and canonical envelope constructor with exhaustive
   table-driven unit proof for exact maps, redaction, fallthrough, and bounds.
2. **Delegate-step normalization** — wire failed child runs through the canonical
   constructor and prove execution-error, terminal-outcome, fallback, retry, and
   nested-child cases persist the correct parent attempt envelope without changing
   non-failed delegation behavior.
3. **Facade terminal-error handoff** — select the terminal parent attempt before
   control returns, expose its exact persisted map privately as
   `:terminal-execution-error`, and prove `:steps-executed` remains unchanged.
4. **Execute/resume projection through retention** — project delegated errors from
   the facade handoff for both mutations, preserve non-delegated fallback behavior
   and response shapes, and prove correctness after retention-zero cleanup.
5. **Registered tool and async surfaces** — prove the synchronous registered
   `delegate` tool returns `Error: <canonical message>` and async completion,
   background-job, notification, and append-entry surfaces reuse the same message
   under their existing wrappers.
6. **Regression, coherence, and user-facing record** — run focused and relevant
   suites, lint/format changed Clojure files, verify forbidden child data cannot
   cross the envelope boundary, update the changelog, and re-check all acceptance
   criteria and scope invariants.
## Implementation progress

- Slice 4 now projects a `:delegated-workflow-failed` envelope exclusively from
  the facade's private `:terminal-execution-error` handoff. The mutation applies
  retention cleanup before its canonical run read, so the retention-zero proof
  confirms that this handoff—not `:steps-executed` or a subsequent run read—is
  the durable public-error source for both execute and resume.
- Slice 4 proof now table-drives all canonical envelope sources
  (`:execution-error`, `:terminal-outcome`, and `:fallback`) through the execute
  mutation with retention zero. Projection is intentionally source-agnostic: it
  returns the handed-off message verbatim while lower-runtime tests remain the
  authority for source selection and envelope construction.
- Slice 5 proves the registered synchronous tool through a real parent/child
  statechart failure and proves all asynchronous output surfaces are pass-through:
  completion/background-job fields contain the canonical message unchanged, while
  notification and append-entry add their existing wrappers exactly once. The
  sync wait had been reading only namespaced mutation keys while its async future
  returns unqualified keys; it now supports both equivalent internal result
  shapes without changing the provider-facing contract.
- Slice 6 hardens lower-runtime lexical proof with safe-reason rejection,
  token-length boundaries, quoted credentials, scanner precedence, Windows/UNC,
  and secret-bearing relative paths. Bearer padding is excluded before its
  eight-character minimum is applied, and all Unicode whitespace now terminates
  a path span. Focused runtime, delegate, handoff, mutation, tool, and async
  Scry namespaces are green; broader regression and the remaining exhaustive
  acceptance matrix remain open.
- Slice 6 follow-up corrects terminal attempt selection when a terminal outcome
  omits `:attempt-id`: a historical attempt with a nil id no longer matches that
  absent id, so selection deterministically uses the latest ordered attempt as
  required. The pure contract now also proves negative lexical boundaries and
  control removal before whitespace normalization.
- Slice 6 matrix expansion proves the remaining high-risk deterministic selector
  branches without implementation changes: an explicitly named terminal attempt
  wins, effective step order—not map order—selects a fallback failed step, and
  absent selected identity is omitted. It also proves exact 512-code-point
  non-truncation, escaped target/step rendering, placeholder actionability, and
  nested allowlist exclusion of child data.
- Slice 6 lexical completion treats U+0085 (NEXT LINE) as whitespace despite its
  JVM control classification, so it delimits paths and normalizes safely instead
  of being silently removed. Scanner regexes now use Unicode whitespace semantics
  and support escaped line breaks inside quoted credentials. Terminal reason
  rendering now explicitly follows the safe-keyword grammar, avoiding Clojure's
  namespace auto-resolution syntax in public text.
- Slice 6 credential-boundary correction rejects empty quoted values as required:
  `token=\"\"` and `credential=''` remain unchanged rather than becoming a
  redaction placeholder. The scanner now requires at least one quote-body unit,
  while retaining escaped-content support and existing nonempty-value behavior.

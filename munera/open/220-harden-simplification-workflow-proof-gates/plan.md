# 220 — Plan

Derived from the stable `design.md` after architecture-fit, ambiguity, and
inconsistency design follow-ups were resolved (`design-steps.md` unchecked count
0). The design is complete enough to plan: it fixes the deterministic task-path
boundary, proof-sync fixed-point topology, parse-checked Gordian artifacts,
split terminal-stop shape, selector uncertainty semantics, and the registered
`workflow/proof-sync-disposition-routing` operation contract.

## Approach

Harden the two simplification workflows by tightening explicit workflow grammar,
registered deterministic operations, prompt contracts, and content-lock tests.
Do not change the target-selection algorithms or what counts as a valid
simplification target. Keep the work inside existing workflow/runtime seams:

- registered deterministic operations for non-LLM routing,
- `.psi/workflows/*.edn` target-authored orchestration for topology/data-flow,
- workflow-local `:session` steps for proof/validation prompts,
- existing `review-step`, `implement-task`, and review-follow-up machinery,
- focused workflow-loader/content-lock tests for authored workflow contracts,
- runtime operation tests for deterministic parser behaviour.

Implement dependency-first:

1. Add and test the deterministic proof-sync disposition operation before any
   workflow EDN invokes it.
2. Introduce the missing incidental `extract-task-path` identity boundary and
   convert downstream incidental task consumers to the extracted path.
3. Strengthen generated-task and gate prompts so both workflows name and
   parse-check their authoritative task-local proof artifacts.
4. Add the proof-sync fixed-point topology and validation-capture changes in the
   workflows, using committed task-local artifacts as proof authority.
5. Replace generic terminal-stop summaries with split stop-source steps carrying
   explicit preceding gate context.
6. Lock all topology/prompt contracts with focused tests, then update docs and
   changelog for the user-visible reliability guarantees.

### Key decisions from design

- `reduce-incidental-complexity` must mirror the architecture workflow's
  deterministic `extract-task-path` boundary. Downstream task identity comes from
  `{:from {:step "extract-task-path" :yield :text}}`; the full
  `select-and-create` handoff is context only.
- `reduce-architectural-complexity` keeps its existing deterministic task-path
  boundary and gains regression locks for valid, extra-prose, malformed, and
  missing path cases.
- `proof-sync` is a mutating gate only when it fixes stale/incomplete artifacts.
  A mutating pass returns `PASS_STATUS: ACTIONABLE_FEEDBACK`, commits the proof
  update, and emits exactly one `PROOF_SYNC_ROUTE: ...` marker.
- `proof-sync-disposition` is a `:type :invoke` step using registered operation
  `workflow/proof-sync-disposition-routing`. It accepts only
  `COVERAGE_REVIEW`, `VALIDATION_RECAPTURE`, or `BOOKKEEPING_FIXED_POINT`.
  Missing, duplicated, or unsupported markers are operation errors.
- Final success may follow only a clean/no-op proof-sync pass or a clean read-only
  `proof-sync-fixed-point` pass. It must never follow directly from the same
  pass that mutated proof artifacts.
- `proof-sync-fixed-point` is read-only. Any blocking note it reports must already
  have been written and committed by the preceding mutating `proof-sync` pass.
- `reduce-incidental-complexity` target-present tasks require task-local
  `coverage-map.md`, `before-local.json`, `before-diagnose.edn`,
  `after-local.json`, `incidental-burden-check.edn`, `incidental-gate.edn`, and
  `characterization-baseline.edn` as named proof artifacts.
- Architecture target-present tasks require task-local `coverage-map.md` as
  proof authority, written first by `select-and-create` as an initial scaffold
  and maintained by coverage review/fix, diff gate, validation capture,
  proof-sync, and final summary.
- Architecture validation artifacts remain `after-diagnose.edn`,
  `after-architecture-targets.edn`, `architecture-compare.edn`, and
  `architecture-gate.edn`; every successful artifact must parse after write, and
  exit-0 unreadable output is a failure map plus repair routing.
- Terminal-stop summaries are split by stop source. Every terminal route carries
  explicit source context from the immediately failing gate; malformed task-path
  stops do not consume or invent a task path.
- Selector uncertainty is recorded, not silently ignored: low-confidence
  architecture winners proceed only with explicit actionability/falsification
  notes in generated `design.md`; marginal incidental targets record top-5 guard
  evidence, rejected essential false positives when present, and review questions.


### Plan/steps ambiguity follow-up decisions

- **Proof-sync marker grammar (PA1).** A mutating `proof-sync` final reply may
  contain normal final-reply prose and its `PASS_STATUS` line around the route
  marker. The deterministic operation reads split lines and accepts exactly one
  marker line whose whole line is `PROOF_SYNC_ROUTE: <route>`, with the prefix at
  column 0, exactly one ASCII space after the colon, and no leading whitespace,
  trailing whitespace, or same-line prose after the route token. The only route
  tokens are `COVERAGE_REVIEW`, `VALIDATION_RECAPTURE`, and
  `BOOKKEEPING_FIXED_POINT`. Surrounding prose is valid; same-line route prose
  such as `PROOF_SYNC_ROUTE: COVERAGE_REVIEW because tests changed`, duplicated
  marker lines, unsupported tokens, malformed prefixes, or missing markers are
  tagged operation errors. Runtime tests must lock valid surrounding prose and
  malformed same-line route text.
- **Validation-capture repair-vs-terminal routing (PA2).** Validation-capture
  failure routing uses an explicit disposition marker and registered
  deterministic operation rather than the undifferentiated `ACTIONABLE_FEEDBACK`
  branch. Add `workflow/validation-capture-disposition-routing`, accepting
  exactly one line `VALIDATION_CAPTURE_ROUTE: IMPLEMENTATION_REPAIR` or
  `VALIDATION_CAPTURE_ROUTE: TERMINAL_STOP` with the same exact-line grammar as
  proof-sync markers. In both simplification workflows, `validation-capture` (or
  `incidental-validation-capture`) routes `PASS_STATUS: REVIEW_COMPLETE` to the
  next review/proof gate. On any validation failure it records/commits the
  failure artifact, emits `PASS_STATUS: ACTIONABLE_FEEDBACK` plus the validation
  route marker, then goes to a `validation-capture-disposition` invoke step.
  `IMPLEMENTATION_REPAIR` routes to `implement-task`; `TERMINAL_STOP` routes to
  `terminal-stop-validation-capture` with the failing validation yield. Thus the
  terminal stop is not reached through the same branch used for repair.
- **Incidental `coverage-map.md` first writer and lifecycle (PA3).** For every
  target-present incidental task, `select-and-create` is the first writer: it
  creates and commits an initial `coverage-map.md` scaffold alongside
  `design.md`, `before-local.json`, and `before-diagnose.edn`. The scaffold
  contains the mandatory field headings from the design and records unknown
  coverage/test counts as pending rather than omitting the fields. Subsequent
  lifecycle ownership is: `coverage-review` reads and updates coverage/test-net
  fields, `coverage-fix` updates it when tests or seams are added,
  `diff-gate` records coverage-phase classification relationship to
  `characterization-baseline.edn`, `incidental-validation-capture` records final
  Gordian proof-artifact references, `proof-sync` is the final mutating
  synchronizer when stale, and `final-summary` reads it as committed proof
  authority.

### Plan/steps inconsistency follow-up decisions

- **Architecture `coverage-map.md` writer/lifecycle (PI1).** Architecture
  target-present tasks use the same committed coverage-proof authority model as
  incidental tasks. `select-and-create` is the first writer: it creates and
  commits an initial `coverage-map.md` scaffold alongside `design.md`,
  `architecture-targets.edn`, `before-diagnose.edn`, and the target-issues
  artifact. Pending coverage/test counts must be represented explicitly. The
  architecture lifecycle ownership is: `coverage-review` records affected
  behaviours, authoritative test commands, coverage/test-net fields, and latest
  counts; `coverage-fix` updates it for added characterization tests or minimal
  seams; `diff-gate` records coverage-phase classification and its relationship
  to `characterization-baseline.edn`; `validation-capture` records references to
  `after-diagnose.edn`, `after-architecture-targets.edn`,
  `architecture-compare.edn`, and `architecture-gate.edn`; `proof-sync`
  synchronizes stale coverage/proof fields; and `final-summary` reads
  `coverage-map.md` as committed proof authority. Do not narrow architecture
  proof-sync's artifact set to omit `coverage-map.md`.
- **Mandatory low-confidence architecture handling (PI2).** Architecture
  `select-and-create` generated-design prompt and content-lock tests must always
  cover selected candidate score and confidence. When `:confidence` is `:low`,
  the generated design must include actionability despite low confidence,
  falsification evidence, design-review questions, and scope-narrowing
  considerations. This is not optional and has no Slice 2 `if needed` escape
  hatch.
- **Terminal-stop route ordering (PI3).** Split terminal-stop step definitions
  are a topology prerequisite. Add the split terminal-stop steps in both
  workflow EDNs before the first slice that routes to any of them, then migrate
  routes slice-by-slice only after their target step already exists. After every
  slice that changes workflow topology, verify the affected EDN loads through
  the workflow-loader/registry path, not merely that the file is EDN-readable,
  so undefined route targets are caught immediately.

## Risks

- **R1 — Workflow topology regression.** Adding proof-sync loops and split
  terminal stops could make routes cyclic, unreachable, or accidentally bypass
  required gates. Mitigation: lock step order, `:on` routes, and source contexts
  in workflow-loader tests for both workflows.
- **R2 — Deterministic operation parser drift.** A permissive
  `proof-sync-disposition` parser could accept ambiguous prose and misroute stale
  proof. Mitigation: operation-level tests cover each valid marker plus missing,
  duplicated, whitespace, extra-prose, and unsupported-marker cases.
- **R3 — Prompt-only parse checking.** Prompts can say "parse-check" without
  making unreadable artifacts fail. Mitigation: content locks require artifact
  names, parse-after-write wording, failure-map replacement, and repair/terminal
  routing for unreadable JSON/EDN, especially exit-0 unreadable output.
- **R4 — Stale proof remains authoritative.** Review follow-ups may add tests
  after coverage artifacts were written. Mitigation: final success is gated on a
  clean/no-op proof-sync or fixed-point pass that rereads committed task-local
  artifacts, not ephemeral review prose.
- **R5 — Incidental validation becomes too broad.** A5/A2/A3 validation could
  turn into subjective review instead of deterministic artifact comparison.
  Mitigation: name `after-local.json`, `incidental-burden-check.edn`, and
  `incidental-gate.edn`, and require deterministic JSON/EDN parse checks plus
  recorded numeric A5/A2 summaries.
- **R6 — Terminal summaries infer from hidden state.** A single generic summary
  can misidentify the failed gate from missing artifacts. Mitigation: implement
  split terminal steps with explicit `:type :source` contributions from the
  failing step and content-lock that context.
- **R7 — User-visible docs lag behind workflow guarantees.** Hardening changes
  how users should trust workflow output. Mitigation: update `doc/workflows.md`,
  README if needed, and `CHANGELOG.md` after the final workflow/test contract is
  known.

## Slice order

1. **Slice 1 — Preflight and deterministic operations.** Reconfirm current
   workflow/runtime surfaces, add registered operations
   `workflow/proof-sync-disposition-routing` and
   `workflow/validation-capture-disposition-routing`, and cover valid/malformed
   exact-marker parsing with runtime tests.
2. **Slice 2 — Task identity boundary, terminal prerequisites, and
   selector/proof generation contracts.** Add split terminal-stop step
   definitions before routing to them; add incidental `extract-task-path` plus
   deterministic routing; wire downstream incidental task consumers to the
   extracted path; and strengthen both `select-and-create` prompts so
   architecture and incidental generated tasks create initial mandatory
   `coverage-map.md` scaffolds, name parse-checked proof artifacts, and record
   selector uncertainty/guard evidence.
3. **Slice 3 — Parse-checked validation capture.** Strengthen architecture
   `validation-capture` parse-after-write/failure-map contract and add incidental
   `incidental-validation-capture` for `after-local.json`,
   `incidental-burden-check.edn`, and `incidental-gate.edn`, with explicit
   validation-capture disposition routing for repair versus terminal stops.
4. **Slice 4 — Proof-sync fixed-point topology.** Add workflow-local
   `proof-sync`, `proof-sync-disposition`, and `proof-sync-fixed-point` steps to
   both workflows in the design-specified order. Wire coverage-review,
   validation-recapture, bookkeeping fixed-point, clean final-summary, and
   proof-sync terminal-stop routes.
5. **Slice 5 — Split terminal prompt completion and final summaries.** Complete
   stop-source-specific terminal prompt content and remove any remaining generic
   `terminal-stop-summary` routes for malformed task path, clean baseline,
   coverage disposition, diff gate, validation-capture, and proof-sync. Ensure
   final summaries independently read committed proof artifacts.
6. **Slice 6 — Workflow-loader/content-lock tests.** Expand focused tests for
   both simplification workflows to lock task-path routing, proof-sync ordering,
   registered operation id, terminal-stop context, parse-check artifact contracts,
   selector uncertainty wording, and final-summary proof authority.
7. **Slice 7 — User-facing docs, changelog, and verification.** Update docs and
   changelog for the hardened guarantees, run focused workflow-loader/runtime
   tests plus targeted lint/EDN checks, and record verification in task artifacts.

## Non-blocking notes

- The exact helper code used to parse incidental JSON/EDN artifacts may be added
  only if implementation proves prompt-only capture is too weak; the design does
  not require a new Gordian subcommand.
- A persistent target skip list remains out of scope.
- If workflow-loader tests become too large, prefer a dedicated task-220 test
  namespace rather than expanding already-large task-209/task-218 namespaces.
# Plan — review-task-design multi-prompt exemplar

## Approach

Implement this as a small vertical migration of the `review-task-design` workflow onto the multi-prompt session surface delivered by task 226, while tightening the existing generic pass-feedback router so the merged workflow keeps the validation guard formerly provided by per-phase `pass-status-routing` judges.

Key decisions:

- Treat the task as an authored-workflow topology redesign, not a runtime capability change: no changes to the multi-prompt execution machinery are planned.
- Update `workflow/pass-feedback-routing` in place to parse every supplied reply with the existing `PASS_STATUS` grammar and allowed statuses `ACTIONABLE_FEEDBACK|REVIEW_COMPLETE`; return an operation error for any missing, duplicate, malformed, or disallowed status before choosing a route.
- Replace the three separate design review session steps and three per-phase follow-ups in `.psi/workflows/review-task-design.edn` with one step-level configured multi-prompt `design-review` step plus one `design-follow-up` step.
- Route the merged step directly from the post-drain `pass-feedback-routing` judge over per-prompt `:final-llm-reply` outputs; keep the six-review-pass bound on the `design-follow-up --DONE--> design-review` transition.
- Keep the existing three review prompt files as prompt-group bodies, but adjust their text so the architecture prompt loads the task design and architecture sources first, while later prompts reuse the shared session context by default.
- Keep `review-follow-up-design.md` as the shared design follow-up body and clarify its batch evidence rule rather than adding a bespoke runtime operation or filesystem-state router.
- Update tests before or with each behavioral change so the workflow definition, routing operation, and prompt contracts are locked by executable proof.

## Risks

- `pass-feedback-routing` is shared by `review-task-plan`; tightening malformed-output handling is intentional but can expose previously silent bad review outputs as workflow failures. Tests must cover current valid plan/design paths and invalid diagnostics.
- Multi-prompt source refs are legal only for `:output`, not `:yield`; final-summary and judge refs must use `{:step "design-review" :prompt "<phase>" :output :final-llm-reply}` exactly.
- Prompt-group `:prompt-workflow` imports prompt bodies but not per-prompt frontmatter capabilities; the merged session step must carry the union `:tools`/`:skills` at step level.
- The single follow-up changes review semantics from interleaved per-phase follow-up to batch-review-then-follow-up. Docs and prompt wording must make this deliberate topology change visible.
- The post-batch follow-up evidence rule relies on task-scoped git history. The prompt must prefer a block note over guessing when the preceding review-batch baseline cannot be identified confidently.

## Slice order

1. **Pass-feedback routing validation** — tighten `workflow/pass-feedback-routing` and add deterministic routing coverage for valid DONE/REPEAT and invalid/missing/malformed/duplicate/disallowed status cases.
2. **Merged workflow topology** — rewrite `.psi/workflows/review-task-design.edn` to the two-step review loop plus final summary, using per-prompt output refs and the correct iteration-limit placement.
3. **Prompt contracts** — update the three review prompt bodies and the shared design follow-up prompt for shared-session context reuse and post-batch follow-up selection.
4. **Definition/runtime test alignment** — update workflow-loader/definition tests and review-routing tests to cover the merged topology, prompt groups, final-summary refs, and loop behavior.
5. **Documentation and changelog** — update `doc/workflows.md` and `CHANGELOG.md` to describe the batch-review-then-follow-up shape, pass-feedback validation, and deliberate departure from the old interleaved topology.
6. **Verification and cleanup** — run focused tests and lint for touched Clojure/workflow files, fix fallout, and record implementation notes in the task artifacts.

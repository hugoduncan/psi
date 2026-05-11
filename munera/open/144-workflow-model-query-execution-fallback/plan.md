# Plan

Implement this as one workflow-scoped vertical slice that adds execution-time fallback for workflow-authored `:model-query` session steps while preserving current single-model behaviour everywhere else.

## Approach

1. **Inventory the current single-model workflow path**
   - identify exactly where workflow `:model-query` is collapsed to one concrete model today
   - identify the narrowest seam where ranked candidates can be preserved or reintroduced without widening generic session execution
   - record the chosen fallback seam and fallback-worthy error predicate in `implementation.md` before code changes

2. **Preserve ordered ranked candidates for workflow query models**
   - extend `resolve-step-session-config` so a workflow session step with `:model-query` returns both the compatibility concrete `:model` and preserved ordered ranked metadata derived from `[:ranking :ranked]` in `psi.ai.model-selection/resolve-selection`
   - keep explicit concrete models on the existing single-model path with no ranked metadata added
   - treat that session-config payload as the authoritative ranked-sequence carrier for the rest of workflow execution
   - prefer one stable ranked sequence per step attempt rather than re-resolving on every fallback attempt

3. **Add workflow-local candidate iteration**
   - execute the same step contract against ranked concrete candidates in order
   - stop on first success
   - continue only for fallback-worthy execution/setup failures
   - preserve existing canonical execution-result and workflow progression semantics on success

4. **Surface exhaustion coherently**
   - when all candidates fail, record one terminal workflow attempt failure rather than minting per-candidate workflow attempts
   - store the aggregate failure on that attempt’s `:execution-error`
   - include a stable exhaustion reason plus a ranked `:candidate-failures` collection carrying per-candidate model identity and failure payload so EQL/result consumers can diagnose what was tried
   - avoid swallowing the underlying failure context

5. **Proof**
   - first candidate fails, second succeeds, in exact ranked order
   - explicit concrete model remains single-shot
   - non-fallback-worthy failure stays terminal
   - empty/no-winner ranked case remains coherent

## Risks

- The current workflow step-session config seam may normalize query-shaped models too early; the smallest fix may require carrying richer resolved metadata instead of only a concrete `:model`.
- Execution failures may be surfaced through more than one error/result shape; inventory first so the fallback predicate is narrow and explicit rather than substring-driven everywhere.
- Step-attempt bookkeeping and child-session creation may currently assume a single candidate per attempt; fallback should not corrupt attempt history or result reporting.

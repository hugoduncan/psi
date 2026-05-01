Approach:
- Keep the refactor narrow and behavior-preserving.
- Introduce one small pure publication-decision function in the workflow-loader completion path.
- Separate two concerns explicitly:
  1. derive delegated-result publication intent from completion outcome
  2. apply that publication intent through existing side-effecting primitives
- Treat the publication shape as an internal workflow-loader artifact unless implementation pressure proves a broader contract is necessary.
- Avoid changing adapter contracts unless a tiny change is strictly required to preserve existing behavior under the refactor.

Likely implementation shape:
1. Define a pure helper in `extensions.workflow-loader.orchestration` (or a nearby namespace if extraction clarifies ownership) that converts async completion inputs into a canonical publication map.
2. Keep the publication map minimal but explicit. It should carry the information currently spread across branching conditions, such as:
   - run/workflow/session/status/result/error summary
   - whether chat injection is selected
   - whether fallback append-entry is selected
   - whether notification is selected
   - whether terminal background-job message should be suppressed
   - any already-trimmed/nonblank result-text decision needed so application code does not re-decide policy
3. Refactor `on-async-completion!` to:
   - build the publication map once
   - apply background-job terminal update from that map
   - apply transcript injection / notification / append-entry from that map
   - keep cleanup and widget refresh unchanged
   - avoid recomputing delivery policy inline after map derivation
4. Use the explicit preserved behavior matrix from the task design as the implementation guide:
   - completed + include-result + nonblank result
   - completed + include-result + blank result
   - completed + no include-result
   - failed/cancelled/timed-out preserving current semantics
5. Add focused decision-level tests for those mandatory cases.
6. Keep or extend side-effect-level tests so current visible behavior remains proven.

Risks / watchpoints:
- accidentally changing visible behavior while “just refactoring”
- deriving a publication map but still leaking policy recomputation into the effecting code
- duplicating status/result derivation logic between decision and application phases
- broadening into a general workflow-result architecture redesign
- obscuring the current successful `/delegate` invariant: one final conversational result delivery path, not two

Verification:
- focused workflow-loader unit tests around orchestration/delivery
- existing `/delegate` command-path regressions remain green
- adapter parity tests should stay green without needing new behavior-specific branches
- code review should be able to point to one decision function and confirm that async completion side-effect branches consume, rather than recompute, publication policy

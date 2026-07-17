# Implementation

- no architectural review feedback (design aligns with ¬mock/¬stub standard; migrates off `with-redefs` of a logic boundary onto the injectable per-ctx `:provider-registry` provider seam; behaviour-preserving, no shims, preserves focus-gate invariants)

- ambiguity review added 1 new design step (undefined done-condition for the "re-evaluate flakiness" acceptance criterion)
- no inconsistency review feedback

## Notes for the design-step task (design-review slices)

- Resolving the one open design-step (undefined done-condition for the
  "re-evaluate parallel `with-redefs` flakiness" acceptance criterion) is a
  design.md acceptance-wording sharpening, not a scope change — keep the two
  frozen call sites and behaviour-preserving constraint intact.
- The flakiness referenced is the parallel `with-redefs` test-isolation issue
  recorded against task 242; primary sources to consult when specifying the
  concrete outcome: `munera/closed/242-retry-footer-focus-gate-regression/`
  (implementation.md, esp. Slice 6 seam-existence + the flakiness notes) and
  the test file under change `components/rpc/test/psi/rpc_prompt_test.clj`.
- The provider seam authority is `psi.ai.core/create-context` (`:provider-registry`);
  the retry-key propagation path to verify lives in
  `components/turn-runtime/src/psi/turn_runtime/core.clj`
  (`make-provider-event-consumer` `:error` case). Retry-footer text authority is
  `psi.app-runtime.retry-display/retry-status-text`.

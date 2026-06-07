# 219 — Coverage Map

Authoritative coverage/gap record for the `psi.rpc.session` architecture simplification. Filled during Slice 2 before production refactoring; rechecked during Slice 5.

## Verification command

```bash
bb clojure:test:scry --dir components/rpc/test \
  --namespace psi.rpc-command-results-test \
  --namespace psi.rpc-prompt-command-test \
  --namespace psi.rpc-prompt-test \
  --namespace psi.rpc-session-navigation-test \
  --namespace psi.rpc-events-test \
  --namespace psi.rpc-invariants-test \
  --namespace psi.rpc-ops-test \
  --namespace psi.rpc-test
```

## Source-area coverage

To be completed in Slice 2. Record one subsection per target source file, naming covered behaviours and tests/vars.

## Behaviour coverage

To be completed in Slice 2. Cover command/result, picker/model/thinking/frontend-action, command-tree/resume/navigation, prompt/stream, and projection/emit behaviours.

## Gaps and disposition

To be completed in Slice 2. Record each gap as `covered-by`, `added-test`, `infeasible-stop`, or `accepted-existing-coverage`, with terse evidence.

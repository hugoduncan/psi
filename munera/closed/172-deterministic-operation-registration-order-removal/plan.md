# Plan

## Approach

1. Treat this as a simplifying prerequisite for task `171`, not as a storage migration.
   - remove registration-order preservation first
   - keep canonical local storage in `:operations`
   - keep duplicate rejection, lookup semantics, invoke semantics, and extension cleanup semantics unchanged

2. Simplify the registry implementation directly at the current owner.
   - reduce registry state from `{:operations {...} :registration-order [...]}` to `{:operations {...}}`
   - derive `operation-ids-in` from the registered operation keys without promising order
   - derive `all-operations-in` from the registered operations without promising order
   - derive `operation-count-in` from canonical registered membership rather than an ordering projection

3. Change the proof surface to match the actual intended contract.
   - replace insertion-order assertions with membership/count/coherence assertions
   - remove or rewrite the dedicated order-preservation test
   - keep missing-invoke, duplicate-rejection, and cleanup proofs intact

4. Audit higher proof surfaces explicitly rather than only the lower component tests.
   - inspect `components/agent-session/test/psi/agent_session/extensions_test.clj`
   - keep the extension cleanup and invoke-staleness expectations
   - relax any operation-id ordering assumptions to unordered membership assertions only where needed

5. Record the simplification as a prerequisite input to task `171`.
   - edit `munera/open/171-deterministic-operation-registry-shared-storage-migration/design.md` as the required follow-up artifact
   - make the minimum semantic change explicit there: task `171` must assume task `172` is complete and must not preserve or reintroduce adapter-owned ordering metadata or ordering guarantees in its migration target
   - no additional `171` artifact edits are required by this task unless they already exist and need synchronization with that design update

## Proof strategy

- Lower component tests should prove:
  - registering N distinct operations yields exactly N ids and N operations
  - duplicate registration throws and does not change membership or count
  - unregister-by-extension removes exactly matching operations
  - unregistering a missing extension is a no-op on membership and count
  - invoke lookup behaviour is unchanged

- Higher proof surfaces should prove:
  - extension cleanup still removes stale runtime deterministic operations
  - invoke lookup still fails after cleanup
  - no higher test still depends on insertion order unless it explicitly sorts at the assertion boundary

## Constraints carried into execution

- Do not broaden this task into task `171` shared-storage migration work.
- Do not replace removed insertion-order guarantees with a new implicit sorted-order contract.
- Keep invoke behaviour unchanged.
- Prefer the smallest implementation change that removes `:registration-order` as a maintained concept entirely.

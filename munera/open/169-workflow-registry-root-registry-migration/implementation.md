# Implementation

Task created from the registry-unification guidance refined in `164` and the migration pattern established by `167` and `168`.

Initial intent:

- migrate `workflow-registry` to `root-registry`
- preserve current workflow-registry public behavior at the adapter boundary
- explicitly inventory higher workflow read/projection seams so stale direct-state reads do not survive the storage move

2026-05-21 ambiguity review:
- Actionable: design/plan do not say whether this migration changes the canonical persisted path `[:workflows :definitions]` or preserves it as a compatibility surface; direct-path consumers exist in `session-state`, `workflow-runtime.model`, and related tests/docs.
- Actionable: seam inventory does not classify extension-runtime `:loaded-definitions`, which remains a higher projection/read surface that may need explicit coherence or explicit out-of-scope treatment.

2026-05-21 ambiguity follow-up execution:
- Reviewed current authoritative workflow-definition path helpers and callers in `workflow-registry`, `session-state`, `workflow-runtime.model`, built-in workflow reload/runtime code, and direct-path integration tests.
- Resolved the canonical-path question in favor of preservation for this task: `[:workflows :definitions]` is already exposed intentionally via `workflow-registry/definitions-path`, `session-state/state-path :workflow-definitions`, model docs, and root-state tests, so this migration keeps that path as a compatibility surface instead of relocating it.
- Classified extension-runtime `:loaded-definitions` as an in-memory reload/prompt-contribution cache. It is not canonical persisted storage, but it is an in-scope higher projection/read seam that must stay coherent with canonical registered definitions after reload-oriented flows.
- Updated `design.md` and `plan.md` to record both decisions and to require canonical-path compatibility plus higher-seam coherence coverage during implementation.

2026-05-21 inconsistency review:
- Actionable: `design.md`/`plan.md` require redirecting higher semantic read/projection seams away from direct root-state shape where appropriate, but `steps.md` has no explicit step to audit and fix helper/test seams that still bypass `workflow-registry`, including `components/agent-session/test/psi/agent_session/workflow_test_support.clj` directly `assoc-in`ing `[:workflows :definitions ...]`. Add a seam-specific follow-up so preserved canonical-path compatibility does not blur with semantic helper bypasses.

# Implementation log

- Decided to use `:session-root` on the agent-session context as the explicit seam for isolated persisted test storage. Production callers keep default nil behavior, which still resolves to the normal user-home store.
- Tightened `psi.agent-session.test-support/safe-context-opts` so persisted tests must provide an explicit isolated `:session-root`; otherwise helper-based test contexts fail fast before creating persisted session files.
- Standardized helper-owned lifecycle for persistence tests with `with-temp-session-root`, which allocates a per-test temp root and deletes it in `finally`.
- Chosen first shared seam for ordinary non-persisting runtime/bootstrap tests: `psi.app-runtime/create-runtime-session-context` callers in `run-session` and `start-tui-runtime!` now pass `:persist? false`, preventing incidental temp-cwd runtime tests from writing into the real default session store.
- Updated agent-session lifecycle persistence path creation to honor `(:session-root ctx)` for both new-session and fork-session persisted file creation.
- Narrowed `session_lifecycle_test` helper default to `:persist? false`, then kept explicit persistence coverage by moving the fork persistence proof onto `with-temp-session-root` + `:persist? true` + explicit `:session-root`.
- Updated app-runtime proof to assert non-persisting default (`:session-file nil`) for ordinary console runtime tests.

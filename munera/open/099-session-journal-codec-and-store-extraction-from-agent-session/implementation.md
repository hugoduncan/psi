Initialized on 2026-05-06.

Coordination note

- This task follows `097-session-state-component-extraction-from-agent-session` and `098-journal-append-dispatch-effect-convergence`.
- It is intentionally a narrow extraction slice.
- The authoritative first-cut target is the journal codec/store layer currently mixed into `psi.agent-session.persistence`.
- The task should preserve the higher-level session-facing persistence API shape as much as practical while relocating low-level codec/store authority.

Execution notes to capture during implementation

- final namespace split and dependency direction
- exact helpers moved into `psi.session-journal.codec`
- exact helpers moved into `psi.session-journal.store`
- exact helpers intentionally retained in `psi.agent-session.persistence`
- whether any in-memory journal helpers moved after all, and why
- focused verification commands and results

2026-05-06 step 1

- Used `clj-surgeon :op :ls` and `:op :deps` on `components/agent-session/src/psi/agent_session/persistence.clj` to inventory the namespace structurally before editing.
- Scaffolded the new lower component at `components/session-journal/`.
- Added initial authoritative namespaces:
  - `psi.session-journal.codec`
  - `psi.session-journal.store`
- Seeded focused component-local test namespaces:
  - `psi.session-journal.codec-test`
  - `psi.session-journal.store-test`
- This step started as scaffolding only.
- Follow-up in the same implementation slice thinned `psi.agent-session.persistence` into a session-facing adapter over `psi.session-journal.store` for store/load/list/write entry points while retaining ctx-oriented orchestration and domain entry constructors above the boundary.
- Moved low-level focused proof to the new component tests and reduced `agent-session`-local persistence tests to session-facing orchestration coverage.
- `psi.session-journal.store` now owns default sessions-root and directory-layout policy, with explicit root overrides on `session-dir-for` and `list-all-sessions` for tests and controlled callers.
- Follow-up consumer cleanup started: non-session listing/discovery consumers in TUI selector surfaces and agent-session discovery resolvers now depend directly on `psi.session-journal.store` instead of reaching those store-owned operations through `psi.agent-session.persistence`.
- Continued cleanup: `psi.agent-session.session-lifecycle` now depends directly on `psi.session-journal.store` for file allocation/load/flush operations, and remaining direct store-only test fixtures were updated to use `psi.session-journal.store` as well.
- Added an explicit `psi/session-journal` component dependency to `components/tui/deps.edn` because TUI production code now reads session listings directly from `psi.session-journal.store`.
- Preserved return-shape contracts for `load-session-file`, `find-most-recent-session`, `list-sessions`, and `list-all-sessions`.
- Preserved `:message-count` semantics as count of `:message` entries only; the new store-local tests were corrected to match the existing contract instead of changing behavior.
- Fixed v3→v4 header migration parent-id derivation in the extracted store using filename-based extraction from the parent-session path basename.
- Fixed a root-selection bug in `psi.session-journal.store/session-dir-for`: the two-arity form treated `nil` root as a relative path and created `--...--` directories in the project cwd. `nil` root now correctly falls back to the default `~/.psi/agent/sessions` root.

Verification

- `clojure -M:test --focus psi.session-journal.codec-test --focus psi.session-journal.store-test --focus psi.agent-session.persistence-test`
  - green: `10 tests, 78 assertions, 0 failures`
- `clojure -M:test --focus psi.agent-session.session-lifecycle-test/fork-session-persists-child-file-with-parent-lineage-test --focus psi.agent-session.session-lifecycle-test/ensure-session-loaded-in!-resumes-by-context-session-id`
  - green: `1 tests, 7 assertions, 0 failures`
- `clojure -M:test --focus psi.tui.app-session-selector-test --focus psi.agent-session.resolvers-test --focus psi.session-journal.store-test`
  - green: `43 tests, 244 assertions, 0 failures`

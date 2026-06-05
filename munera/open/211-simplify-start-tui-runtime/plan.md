# Plan — Simplify `psi.app-runtime/start-tui-runtime!`

## Approach

Refactor `psi.app-runtime/start-tui-runtime!` only after a green characterization net exists. Treat the current implementation as the behavioural source for this task and preserve all observable TUI startup semantics: runtime context creation, nullable deterministic execution-mode installation, startup session bootstrap, TUI provider install/clear lifetime, session focus/navigation, `/new` targeting the currently focused session, command dispatch, frontend callbacks, and TUI option assembly.

The implementation strategy is local decomplecting rather than broad redesign:

1. Confirm the existing sibling tests cover the observable behaviours named in the design; add characterization tests only for gaps that would let a refactor change behaviour silently.
2. Extract or reshape only coherent local concepts already present in `start-tui-runtime!`, such as bootstrap state, provider-protected TUI startup, navigation callbacks, command options, wiring dependencies, and TUI options.
3. Keep provider lifetime structurally obvious: provider installed after bootstrap and always cleared with `finally` around the frontend start call.
4. Keep `tui-focus*` as the single source of current TUI focus and ensure `/new` continues to fork from `@tui-focus*`, not from the callback argument.
5. Re-run the focused and affected tests, then enforce the Gordian gates using the task's stored baselines.

Key decisions:

- Do not move ownership out of `psi.app-runtime`; extracted helpers, if any, stay local/private and within the target's blast radius.
- Do not change meta/spec/docs or user-visible behaviour; this task is a behaviour-preserving refactor.
- Prefer named local data shapes where they reduce live-binding pressure and correspond to real concepts; avoid arbitrary line-range extraction.
- The target line `603` is baseline provenance only. Burden comparison follows the design's unique logical-key reconciliation rules.

## Risks

- Existing tests may cover many behaviours but not every callback/value in `tui-opts`; Phase 0 must explicitly record any sufficient coverage judgment or add tests before refactoring.
- Provider lifetime can regress if install/clear is split without preserving the `try`/`finally` relationship.
- `/new` session targeting can regress if the focused session atom is obscured or if callback argument semantics are accidentally used.
- Extracting helpers may reduce the target burden while adding burden to neighbouring units; A2's metric-derived touched-set comparison must catch this.
- Gordian line keys may move during helper extraction; use the design's A2/A4 reconciliation rule rather than raw line equality.

## Slice order

### Slice 0 — Orientation and safety-net assessment

Read the target implementation, existing sibling tests, and baselines. Map each behaviour named in Phase 0 and the coverage hint to existing tests or identify precise characterization gaps.

### Slice 1 — Characterization net, if needed

Add minimal characterization tests only for uncovered observable behaviours. Verify they pass against the unmodified production code before any refactor.

### Slice 2 — Local lifecycle/data-shape refactor

Decomplect `start-tui-runtime!` by introducing minimal private helpers or local shapes for coherent concepts while preserving ordering, provider lifetime, focus semantics, and callback surfaces.

### Slice 3 — Behaviour verification

Run focused affected tests and then the relevant unit suite. Fix only regressions caused by the refactor, without weakening expectations.

### Slice 4 — Burden and architecture gates

Run the after `bb gordian local --json`, compare A2/A4 using the design's reconciliation rules, and run the required `bb gordian gate` command against `before-diagnose.edn`.

### Slice 5 — Final review and documentation of blast radius

Review the final diff for minimality, record why any touched helper is in the blast radius, update task notes, and prepare the task for implementation review.

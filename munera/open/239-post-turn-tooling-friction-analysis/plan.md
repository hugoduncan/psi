# Plan — 239 post-turn tooling-friction analysis

## Approach

Implement entirely inside `extensions/context-manager`, following the
structure of the existing entity-resolution augmenter (task 238): pure
core functions + an orchestration function with injectable collaborators
+ thin wiring in `init`.

### Key decisions

1. **Recursion-guard state: extension-local `defonce` atom** (deliberate
   choice, per design's requirement). Rationale: the extension API map
   exposes no ctx to key a managed service on; both existing guards
   (`helper-session-ids`, `entity-resolution-helper-session-ids`) use the
   atom pattern; migrating all three to a ctx-keyed managed service is a
   coherent separate task, not something to do piecemeal here. Name:
   `friction-helper-session-ids`. Record this choice in implementation.md
   and note the ctx-keyed migration as a candidate follow-up task.

2. **Dedup mechanism: model-judged, single helper session, two phases.**
   The helper prompt contains (a) the last-4-turns excerpt and detection
   instructions, and (b) the dedup list — all open task ids+titles plus
   the 20 most-recently-closed (by closure order, approximated by git
   commit order of moves into `munera/closed/`; fall back to directory
   mtime/name order if git query fails). The model outputs only
   *non-duplicate* issues in a strict line format; extension code does no
   similarity matching itself. A "suppressed as duplicate of NNN" output
   line lets us log the AC3 diagnostic.

3. **Helper output contract (strict, parseable):** one block per issue:
   `ISSUE: <slug> | <title>` followed by `FRICTION:`, `EVIDENCE:`,
   `SUGGESTION:` lines; `DUPLICATE: <slug> ~ <existing-task-id>` for
   suppressed matches; `NONE` when nothing found. Parser ignores anything
   malformed (fail-safe: no task rather than a garbage task).

4. **Trigger & async boundary:** extend the existing
   `session_turn_finished` subscription in `init`. The handler returns
   immediately, spawning a `future` that runs the whole analysis; every
   failure inside is caught, logged via `(:log api)`, and swallowed.
   No dispatch/turn-path involvement.

5. **Session exclusion (AC5):** skip when the event's session-id is in
   `friction-helper-session-ids`, in `entity-resolution-helper-session-ids`,
   or identifiable as a known helper/infra session via session metadata
   (EQL query for session-name / helper flag — mirror how
   auto-session-name/metrics query session data; exact attribute decided
   at implementation from the resolver graph). Known helper session names
   (e.g. "entity-resolution") are excluded by name as a backstop.

6. **Analysis input:** last 4 turns rendered with an adapted
   `render-history-excerpt` (generalize the existing private fn to take a
   turn-count + char cap rather than duplicating it). History fetched via
   EQL through the extension api (post-turn events carry no projection).

7. **Task creation: plain file IO in the analyzed session's own worktree**
   (queried via EQL, same effective-cwd source the projection uses).
   Munera task files are git-tracked artifacts, not canonical root state
   (confirmed by design review), so no dispatch mutation is needed.
   NNN = max over `munera/open/` ∪ `munera/closed/` + 1; on pre-existing
   directory, re-allocate (retry with next NNN, bounded retries).
   Generated design.md contains: auto-generated marker (naming this
   analyzer), friction description, evidence (turn references), suggested
   tooling/dependency change. design.md only — no plan.md/steps.md.
   Do not touch `munera/plan.md` (v1, per design's open question).

8. **Cap:** at most 2 tasks per analysis run — take first 2 parsed issues,
   log a diagnostic for the remainder.

9. **Helper session bounding:** reuse the `default-run-helper` pattern
   (bash-tool-less this time — the helper needs no tools, it gets excerpt
   + task list in the prompt; simpler and safer), 120s wall-clock budget,
   same future-owns-teardown timeout handling, tracked in
   `friction-helper-session-ids`.

10. **Testability:** orchestration fn `friction-analysis` takes
    `(api payload collaborators)` where collaborators inject
    `:select-model`, `:run-helper`, `:fetch-history`, `:list-tasks`,
    `:create-task!`, `:session-info` — all AC7 cases testable without a
    model or real sessions, mirroring the entity-resolution test approach.

## Risks

- **EQL surface for history/worktree/session-kind post-turn:** the exact
  attributes for last-N-turn history, effective worktree, and
  helper-session identification must be confirmed against the resolver
  graph at implementation; if no helper-flag attribute exists, fall back
  to atom-membership + session-name matching (accepted degradation,
  note in implementation.md).
- **Closed-task "closure order":** git-derived ordering may be slow or
  unavailable in odd checkouts; mitigated by fallback ordering and by
  dedup being best-effort (a missed duplicate just creates a task a
  human can close).
- **Local-model output discipline:** small models may not follow the line
  format; mitigated by strict parsing that drops malformed output
  (failure → no task, AC4-consistent).
- **Concurrent NNN collisions** across sessions/branches: tolerated via
  bounded re-allocation retries; cross-branch collisions resolved by the
  human per munera convention (rename, never merge).
- **Noise/task spam:** cap=2 plus dedup limit volume; if quality is poor
  in practice, that is v2 tuning (config flag deferred by design).

## Slice order

1. **Pure core** — helper prompt building (excerpt + dedup list +
   instructions), output parsing (issues / duplicates / NONE), generated
   design.md rendering, cap selection. Pure fns + tests.
2. **Task-file creation** — task-id allocation over open/∪closed/,
   collision re-allocation, design.md write. Tested against temp dirs.
3. **Orchestration** — `friction-analysis` with injectable collaborators:
   recursion/helper-session guards, model selection, single bounded
   helper run, dedup-skip diagnostic, cap, failure→no-op paths. Tests
   cover every AC7 case.
4. **Wiring & real collaborators** — real EQL fetchers (history, worktree,
   task lists, session info), real bounded helper runner with
   `friction-helper-session-ids` tracking, fire-and-forget future hooked
   into the existing `session_turn_finished` subscription; manifest /
   permissions alignment.
5. **Docs & verification** — extension docstring/README/doc updates,
   CHANGELOG entry (user-visible behaviour), full `bb test`, lint.

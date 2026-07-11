# Design steps — architectural review (design-review session, turn 1)

- [x] Reconsider the "Home" decision's reuse of a bare `defonce` recursion-guard
      atom (explicitly modeled on `helper-session-ids`) for tracking this
      analyzer's own helper sessions. `ramora/META.md` states: "psi runtime
      owns process-scoped managed services on ctx for long-lived subprocesses
      and similar runtime resources" and "managed services are keyed by
      logical identity and reused within ctx rather than extension-local
      hidden state." Adding a second such atom compounds an already-noted
      anti-pattern rather than moving toward the ctx-keyed managed-service
      model. Design should note (even if implementation defers the actual
      migration) whether this new guard state should be ctx-keyed instead of
      another top-level extension atom, so the plan/implementation stage
      makes a deliberate choice rather than defaulting to copy-paste of the
      existing pattern.

# Design steps — ambiguity review (design-review session, turn 2)

- [x] Acceptance criterion 6 says "Task creation is capped per analysis run,"
      but the Constraints section only offers a range as a suggestion
      ("suggest: 1–2"), not a decided value. Resolve to a single stated cap
      (or explicitly state the cap is a planning-stage decision) so the
      acceptance criterion has one unambiguous number to test against.

- [x] The recursion-guard decision excludes only "the extension's own helper
      sessions." It's unclear whether other extensions' or the runtime's own
      non-substantive helper/infra sessions (e.g. entity-resolution helper
      sessions, other workflow helper sessions) are in-scope inputs for
      friction analysis, or should also be excluded as noise/non-representative
      sessions. Clarify whether "every session" is meant literally (any
      session other than this analyzer's own helpers) or is intended to
      exclude other known helper/infra session categories too.

# Design steps — ambiguity review (design-review round 2, turn 2)

- [x] Dedup says "Recently-closed duplicates also suppress creation (avoid
      reopening churn)" but does not define "recently" (no time window, no
      count of closed tasks to scan, no reference commit range). Clarify
      what "recently-closed" means (e.g. all closed tasks, a bounded lookback
      window, or N most-recently-closed) so the dedup mechanism has an
      unambiguous scope to check against.

- [x] It's unclear whether the dedup/duplicate-matching step ("the helper
      model can be given the list of existing task ids + titles and asked to
      match") runs inside the same bounded helper-session invocation that
      performs friction detection, or as a second, separate helper-session
      call. This affects how "Helper sessions must be bounded (rounds,
      wall-clock, output size)" and the recursion-guard's own-helper-session
      tracking apply — a second helper call would itself need guarding/
      accounting for, which the design does not currently address. Clarify
      whether dedup matching is one phase of a single helper session or a
      distinct helper session.

# Design steps — inconsistency review (design-review round 2, turn 3)

- [x] The "Scope of sessions" decision excludes both "the extension's own
      helper sessions" *and* "other extensions'/runtime's known helper/infra
      sessions (e.g. entity-resolution helper sessions, other workflow helper
      sessions)" as non-representative analysis inputs. However, acceptance
      criterion 5 ("The analyzer never runs on its own helper sessions.") and
      the AC7 test list ("recursion guard") only name the own-helper-session
      exclusion — neither mentions excluding other extensions'/runtime's
      helper/infra sessions. Update AC5 (and/or AC7's test coverage list) to
      reflect the full exclusion scope stated in Decisions, so acceptance
      criteria don't understate what the design commits to.

# Design steps — inconsistency review (design-review session, turn 3)

- [x] The Goal states the analyzer should "automatically create a Munera task
      for each newly identified issue," implying one task per issue with no
      upper bound. The Constraints section directly contradicts this: "at
      most a small fixed number of tasks created per turn analysis (suggest:
      1–2) even if more issues are detected; remaining issues will recur and
      be caught later." Reword the Goal to reflect the capped/best-effort
      behaviour (e.g. "create a Munera task for newly identified issues, up
      to a per-run cap") so the top-level statement of intent doesn't overstate
      what the capped mechanism (AC6) actually delivers.

# Design steps — ambiguity review (design-review round 3, turn 2)

- [x] "Task location: the session's effective worktree" is undefined. The
      Decisions section scopes analysis to "every session (top-level,
      delegated, workflow, helper)", but delegated/workflow sessions can run
      in a different checkout than their parent/originating session. It's
      unclear whether "effective worktree" means the analyzed session's own
      worktree (even if it is a delegated/workflow child), or the worktree
      resolved by walking up to some originating/top-level session. Clarify
      what "effective worktree" resolves to when the analyzed session is a
      delegated or workflow session, so task creation has one unambiguous
      target directory.

# Design steps — inconsistency review (design-review round 3, turn 3)

- [x] The Dedup decision bounds the *closed*-tasks dedup list to a fixed
      count ("N=20 most-recently-closed tasks... a fixed count keeps the
      list passed to the helper model boundable within the single session's
      output-size limit"), but states no analogous bound for the *open*-tasks
      side of the same dedup check ("check existing open **and closed**
      tasks... the helper model can be given the list of existing task ids +
      titles"). As open tasks accumulate over the project's life, the
      open-tasks list is unbounded, undermining the stated boundedness
      rationale that motivated capping the closed list. Clarify whether the
      open-tasks list also needs a bound (and if so, what it is), or state
      why it doesn't need one (e.g. open task count is expected to stay
      small in practice).

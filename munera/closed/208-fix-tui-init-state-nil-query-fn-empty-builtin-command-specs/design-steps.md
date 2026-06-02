# Design follow-up — architectural fit

- [x] Resolve single-source drift: doc/architecture.md forbids hardcoded
      built-in command lists in adapters; the proposed literal test seed
      duplicates the canonical `builtin-command-specs` table. Since
      `components/tui/deps.edn` cannot reference `agent-session`, either (a)
      add explicit drift protection / documented seam rationale to design, or
      (b) re-scope the fix to derive the seed from the authoritative source.
      → Resolved (a): design now documents the seam rationale and bounds the
      drift — the literal specs are *test fixture data* feeding the production
      `query-fn` seam (stub backend), not a production UI command list; no
      production projection gains a hardcoded list. Deriving from the
      authoritative table (b) is structurally blocked by the deps boundary.
- [x] Reconsider seam layer: prefer a Nullable/Configurable-Response seam in
      `build-init`/`make-init` (nil/stub query-fn → representative state via
      embedded stub) over the test-only `(assoc :builtin-command-specs ...)`
      that asserts state the production path never produces. If the test-only
      constraint is retained, justify it explicitly in design against the
      testing-without-mocks Nullable principle.
      → Resolved: re-scoped to drive `init-state` through the production
      `query-fn` seam with a stub `query-fn` (Configurable Response). The real
      `build-init` introspection populates `:builtin-command-specs`, so tests
      assert only producible state. `query-fn` is the genuine infrastructure
      boundary; `make-init` already accepts/validates a callable `query-fn`.
      The post-hoc `(assoc :builtin-command-specs ...)` of the default surface
      is dropped from the design.

## Design follow-up — ambiguity

- [x] Define the stub fixture membership rule: state which built-in command
      names the stub query-fn must return and/or the minimum the autocomplete
      test must assert. Replace the "e.g. help/status/quit" + "one or more"
      phrasing with a decidable acceptance (e.g. "asserts all of help, status,
      quit" or "asserts at least one named X").
      → Resolved: design now requires the stub fixture to contain at minimum
      `help`, `status`, `quit`, and the autocomplete test to assert all three
      of `/help`/`/status`/`/quit` appear. ("Stub `query-fn` contract" +
      Acceptance.)
- [x] Disambiguate the "broader introspection query keys that build-init
      issues" constraint: specify whether the stub query-fn must populate all
      keys build-init queries (prompt-templates, skills, extension-summary,
      session-id, session-file, extension/command-names, builtin-command-specs)
      or only :psi.agent-session/builtin-command-specs.
      → Resolved: design's "Stub `query-fn` contract" states the stub need only
      populate `:psi.agent-session/builtin-command-specs`; build-init defaults
      the other six keys via `or`. The constraint's parenthetical "broader
      query keys" obligation is removed.
- [x] Clarify the empty-surface acceptance example: build-init treats the
      query-fn result as a map keyed by query keys, not a bare collection.
      State the empty case precisely (e.g. "stub query-fn returns a map with []
      under :psi.agent-session/builtin-command-specs, or a nil query-fn")
      instead of "stub query-fn returning []".
      → Resolved: Acceptance now defines "empty surface" as a map with `[]`
      under `:psi.agent-session/builtin-command-specs` (or a nil query-fn),
      explicitly not a bare `[]` return.
- [x] Define "where appropriate" for updating autocomplete tests: give the
      criterion for which existing tests must assert real built-in candidates
      versus remain unchanged, so test scope is unambiguous.
      → Resolved: new "Test scope" section names the built-in autocomplete
      tests that must source candidates from the seam-produced state
      (`autocomplete-slash-includes-backend-builtin-commands-test`,
      `autocomplete-slash-dedupes-builtin-template-collision-test`) and states
      the criterion (subject = built-in surface) leaving unrelated tests
      unchanged.

## Design follow-up — inconsistency

- [x] Reconcile the post-hoc `assoc` prohibition. Constraints state the test
      must use the seam "not a post-hoc `(assoc state :builtin-command-specs
      ...)`" (unconditional), but Acceptance narrows it to assoc "to inject the
      default surface" and Test scope allows
      `autocomplete-slash-dedupes-builtin-template-collision-test` to set
      `:builtin-command-specs` directly. State one rule: either per-case direct
      `assoc` of non-default specs is permitted (and Constraints must be
      qualified to match Acceptance), or it is forbidden (and the collision/
      empty cases must be re-expressed through the stub `query-fn`).
      → Resolved (forbidden): chose the single rule that the post-hoc
      `(assoc state :builtin-command-specs ...)` is **unconditionally**
      forbidden for every built-in-surface case (default, collision, empty).
      Per-case specs are fed to the stub `query-fn` via an `init-state` per-case
      option the helper routes into the stub's returned map, so the real
      `build-init` path produces the surface. Constraints (unconditional
      prohibition + per-case-via-seam), Acceptance (no post-hoc assoc for any
      case), Scope (parameterized stub input), and Test scope all aligned.
- [x] Reconcile "source candidates from the seam-produced state" vs. "set
      `:builtin-command-specs` directly" for the same named tests. Test scope
      mandates the built-in autocomplete tests source from the seam yet
      describes the collision test (and the empty-surface assertion sets `[]`
      directly) as setting `:builtin-command-specs` directly. Decide and state
      whether the collision and empty-surface cases must be converted to drive
      their specs through the stub `query-fn`, or may retain a direct per-case
      `assoc`; align Scope, Acceptance, and Test scope to the chosen rule.
      → Resolved (convert to seam): the collision and empty-surface cases are
      converted to drive their per-case specs through the stub `query-fn` (the
      helper feeds `[{:name "resume" …}]` / `[]` to the stub). Test scope no
      longer describes those cases as "set `:builtin-command-specs` directly";
      it now states every case sources from the seam, with only the *input* to
      the stub varying per case. Scope, Acceptance, and Test scope aligned to
      this single rule.

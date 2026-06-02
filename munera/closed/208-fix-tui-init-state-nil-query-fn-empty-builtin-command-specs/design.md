# 208 — Fix TUI test `init-state` empty `:builtin-command-specs` (nil `query-fn`)

## Intent

Fix the broken TUI slash-command autocomplete test setup on `master`, where the
test helper `init-state` produces a state with an empty `:builtin-command-specs`,
so built-in slash-command autocomplete candidates never render in tests.

## Problem

In `components/tui/test/psi/tui/app_input_selector_test.clj`, the private test
helper `init-state` builds state via:

```clojure
(app/make-init nil ui-read-fn ui-disp-fn ...)
```

The first argument is `query-fn`, here `nil`.

In `components/tui/src/psi/tui/app/support.clj`, `build-init` only performs
backend introspection when `query-fn` is non-nil:

```clojure
introspected (when query-fn
               (query-fn [... :psi.agent-session/builtin-command-specs]))
```

With a `nil` `query-fn`, `introspected` is `nil`, so `:builtin-command-specs`
is never populated. Consequently:

- The `build-init` introspection path leaves `:builtin-command-specs` empty
  (the current helper then patches it with a post-hoc
  `(assoc state :builtin-command-specs …)`, masking that the introspection seam
  itself produced nothing — exactly the test-fidelity defect this task removes).
- `psi.tui.app.autocomplete` derives built-in slash candidates from
  `(:builtin-command-specs state)`; with an empty value it yields **no**
  built-in autocomplete candidates.
- Tests exercising `/`-triggered slash autocomplete render no built-in
  candidates, so they either pass vacuously or fail to cover the real
  single-sourced built-in command surface (task 205).

This is a test-fidelity defect: the autocomplete tests do not exercise the
production behaviour they appear to assert.

## Scope

In scope:

- Drive the TUI test `init-state` helper through the **production `query-fn`
  seam**: pass a stub `query-fn` returning representative built-in command
  specs (Configurable-Response Nullable) instead of `nil`, so the real
  `build-init` introspection path populates `:builtin-command-specs`. This
  asserts only state the production path can actually produce.
- The stub `query-fn` returns a map keyed by the introspection query keys; its
  `:psi.agent-session/builtin-command-specs` entry has the same data shape the
  resolver yields (`[{:name ... :description ...} ...]`), so the test reflects
  production.
- Update the existing built-in autocomplete tests (those whose subject is the
  built-in slash-command surface) to source candidates from the seam-produced
  state; leave tests whose subject is unrelated (history, extension commands,
  skills order, template dedup) unchanged. See *Test scope* below for the
  decidable criterion.
- Make the `init-state` helper parameterize the built-in specs *fed to the
  stub `query-fn`* (a per-case option, e.g. `:builtin-command-specs`, that the
  helper routes into the stub's returned map), so per-case built-in surfaces
  (the collision case's `resume`, the empty case's `[]`) are produced by the
  real `build-init` introspection path — **not** by a post-hoc
  `(assoc state :builtin-command-specs ...)` after `make-init`. The seam is the
  single mechanism for every built-in-surface test; only the *input* to the
  stub varies per case.

Out of scope:

- Changing `build-init`/`make-init` production behaviour. (The production path
  passes a real `query-fn` and is unaffected; the test exercises the same seam
  with a stub `query-fn`.)
- Any change to the built-in command surface itself or the resolver.

## Context

- The single-source built-in slash-command surface was introduced in task 205
  (`commands.builtin-specs/builtin-command-specs` + the
  `:psi.agent-session/builtin-command-specs` resolver). TUI builds autocomplete
  from that resolver in production.
- The fix must keep the stub `query-fn`'s returned specs consistent with the
  resolver's data shape so the test continues to reflect production behaviour
  rather than drifting.

## Constraints

- Test-only change; no production code behaviour change.
- Use the production `query-fn` seam (a stub `query-fn`) as the **single**
  mechanism for every built-in-surface test, never a post-hoc
  `(assoc state :builtin-command-specs ...)` after `make-init` — this
  prohibition is **unconditional** and applies to all built-in-surface cases
  (default, collision, empty), not just the default surface. Per-case built-in
  specs (e.g. the collision case's `resume`, the empty case's `[]`) are
  supplied as *input to the stub* `query-fn` (via an `init-state` option the
  helper routes into the stub's returned map), so the real `build-init`
  introspection path still produces `:builtin-command-specs`. The test must
  assert only state that the real `build-init`/introspection path can produce —
  a `nil` `query-fn` produces an empty surface by design, so tests covering
  built-in autocomplete must supply a `query-fn`.
- The stub `query-fn`'s returned specs must match the resolver's
  `{:name :description}` shape, so the seed reflects production rather than
  drifting.

### Stub `query-fn` contract

`build-init` calls `query-fn` once with a 7-key query vector
(`:psi.agent-session/prompt-templates`, `:psi.agent-session/skills`,
`:psi.agent-session/extension-summary`, `:psi.agent-session/session-id`,
`:psi.agent-session/session-file`, `:psi.extension/command-names`,
`:psi.agent-session/builtin-command-specs`) and treats the **return value as a
map keyed by those query keys** (`(:psi.agent-session/builtin-command-specs
data)`); absent keys default to `[]`/`{}` via `or`.

- The stub `query-fn` need only populate
  `:psi.agent-session/builtin-command-specs` in its returned map. It is **not**
  required to populate the other six keys; `build-init` already defaults them.
  The stub may return a map containing only that key.
- The fixture (`builtin-command-specs` value) must contain at minimum the three
  built-in names the autocomplete test asserts on: `help`, `status`, `quit`
  (each as `{:name "…" :description "…"}`). Additional representative specs are
  permitted but not required.

### Single-source drift (acknowledged, structurally bounded)

doc/architecture.md mandates built-in command identity live in exactly one
place (`builtin-command-specs` in `psi.agent-session.commands.builtin-specs`),
with all UI surfaces as pure projections holding no hardcoded built-in lists.
The test stub's representative specs are a literal in the TUI test namespace,
because `components/tui/deps.edn` has no `agent-session` dependency and so the
test cannot reference the canonical table.

This is bounded and acceptable:

- It is **test fixture data**, not a production UI command list — production
  still derives the entire surface from the resolver via a real `query-fn`. No
  production projection gains a hardcoded list.
- The stub feeds the *same* `query-fn` seam the resolver feeds in production;
  it stands in for the backend, mirroring (not duplicating into a parallel
  production path) the resolver's data shape.
- Drift risk is limited to test fixture staleness, which surfaces as failing
  or vacuous autocomplete assertions — not as a production command-surface
  inconsistency. The fixture is intentionally small and representative, not an
  exhaustive mirror of the table.

## Acceptance

- The TUI test `init-state` helper supplies a stub `query-fn` to
  `make-init`/`build-init` so the produced state has non-empty
  `:builtin-command-specs` via the real introspection path. No built-in-surface
  test performs a post-hoc `(assoc state :builtin-command-specs ...)` after
  `make-init` for **any** case (default, collision, or empty); per-case specs
  are fed to the stub `query-fn` instead (see *Test scope*). The default stub
  `query-fn` returns a map whose `:psi.agent-session/builtin-command-specs`
  entry contains at minimum `help`, `status`, and `quit` specs.
- A test exercising `/`-triggered slash autocomplete renders built-in
  candidates and asserts that all three of `/help`, `/status`, and `/quit`
  appear in the candidate values, failing if the stub `query-fn` returned no
  built-in specs.
- An empty-surface case still yields no built-in candidates — confirming the
  surface is sourced from the seam, not a hardcoded UI list. The empty case is
  produced through the seam (the helper feeds `[]` to the stub `query-fn`), not
  by a post-hoc `(assoc state :builtin-command-specs [])`. "Empty surface"
  means the stub `query-fn` returns a map with `[]` under
  `:psi.agent-session/builtin-command-specs` (or a `nil` `query-fn`, which
  build-init skips entirely); it does **not** mean the `query-fn` returns a
  bare `[]`.
- `bb test` (TUI component) passes.

### Test scope

"Built-in autocomplete tests" — the tests whose subject is the built-in
slash-command surface — must source candidates from the seam-produced state for
**every** case, including per-case (non-default) built-in surfaces. Per-case
specs are supplied as input to the stub `query-fn` (via the `init-state`
helper's per-case option), never by a post-hoc
`(assoc state :builtin-command-specs ...)`:

- `autocomplete-slash-includes-backend-builtin-commands-test` — the positive
  built-in surface assertion uses the default stub specs; the empty-surface
  assertion feeds `[]` to the stub `query-fn` (not a direct
  `(assoc ... :builtin-command-specs [])`).
- `autocomplete-slash-dedupes-builtin-template-collision-test` — the built-in/
  template and built-in/extension collision cases feed their per-case specs
  (e.g. `[{:name "resume" …}]`) to the stub `query-fn`, so the collision
  surface is produced by the real `build-init` path rather than set directly.

Tests whose subject is unrelated to the built-in surface remain unchanged even
though they call `init-state`: history navigation, skills canonical-order,
extension-command inclusion, and the leading-`/` open test. These set or assert
only their own concern; the default fixture's built-in candidates are
incidental to them.

# Plan — 208 fix TUI test `init-state` empty `:builtin-command-specs`

## Approach

Drive the TUI test `init-state` helper through the **production `query-fn`
seam** (a stub `query-fn` returning representative built-in command specs),
replacing both the current `(app/make-init nil …)` nil-query-fn call **and**
the post-hoc `(assoc state :builtin-command-specs …)` injections. The design
mandates the stub `query-fn` seam as the *single* mechanism producing
`:builtin-command-specs` for **every** built-in-surface case (default,
collision, empty); only the *input fed to the stub* varies per case.

### Verified mechanics (grounded against source, 2026-06-02)

- `components/tui/src/psi/tui/app/support.clj:209` `build-init` calls
  `query-fn` once with the 7-key query vector
  (`prompt-templates`, `skills`, `extension-summary`, `session-id`,
  `session-file`, `:psi.extension/command-names`,
  `:psi.agent-session/builtin-command-specs`) **only when `query-fn` is
  non-nil** (`(when query-fn …)`), then treats the result as a **map keyed by
  query keys**: `:builtin-command-specs (vec (:psi.agent-session/builtin-command-specs introspected))`
  (line 237), defaulting the other six keys via `or`.
- `components/tui/src/psi/tui/app.clj:252` `ensure-init-arg-contract!` accepts
  `query-fn` that is `nil` **or** `ifn?` — a stub fn is contract-valid.
- The test file `components/tui/test/psi/tui/app_input_selector_test.clj`
  currently (master, the defect) builds via `(app/make-init nil ui-read-fn …)`
  then `(assoc state :builtin-command-specs (vec builtin-specs))` (lines 43–64),
  and the named built-in tests further `(assoc (init-state) :builtin-command-specs …)`
  per case (lines 188–227). Both patterns are the forbidden post-hoc `assoc`.

### Key decisions

1. **Stub `query-fn` lives in `init-state`.** `init-state` constructs a stub
   `query-fn` of one arg (the query vector) that returns a map whose
   `:psi.agent-session/builtin-command-specs` entry is the per-case
   `builtin-specs` (default `sample-builtin-command-specs`, overridable via the
   `:builtin-command-specs` opt the helper already accepts). The stub need only
   populate that one key (design "Stub `query-fn` contract"); `build-init`
   defaults the other six.
2. **Pass the stub as `make-init`'s first arg.** Replace `(app/make-init nil …)`
   with `(app/make-init query-fn …)`. Remove the trailing
   `(assoc state :builtin-command-specs (vec builtin-specs))` — the real
   `build-init` introspection now populates the slot.
3. **Per-case surfaces via the helper opt, not post-hoc assoc.** The collision
   case calls `(init-state {:builtin-command-specs [{:name "resume" …}]})`; the
   empty case calls `(init-state {:builtin-command-specs []})`. Both flow
   through the stub `query-fn` → `build-init`. Delete the
   `(assoc (init-state) :builtin-command-specs …)` from these tests.
4. **Default fixture membership.** `sample-builtin-command-specs` must contain
   at minimum `help`, `status`, `quit` (already does); the additional
   representative specs (reload-models, speed, …) may remain as benign extra
   fixture data, but carry no asserted regression intent — the strengthened
   positive assertion (Decision 5) covers only `/help`/`/status`/`/quit` per
   design Acceptance/Test scope, so no step asserts a previously-missing
   command. Keeping or trimming the extras is immaterial to acceptance.
5. **Positive assertion strengthened to all three.** The slash autocomplete
   built-in test must assert `/help` **and** `/status` **and** `/quit` all
   appear (design Acceptance + "Stub contract"). Currently the positive test
   overrides specs to a 3-item set lacking `status`; switch it to the default
   fixture `(init-state)` (which has all three) — design Test scope decides this
   positive branch "uses the default stub specs", so there is no per-case-input
   alternative.
6. **Unrelated tests unchanged.** History, skills-order, extension-command,
   and leading-`/` tests keep calling `(init-state)` / `(assoc (init-state) :skills …)`
   etc.; the default fixture's built-in candidates are incidental to them. They
   must not be rewired.
7. **Test-only.** No edit to `build-init`/`make-init`/`support.clj` or any
   production file. `refresh-extension-command-names-folds-builtin-specs-test`
   already drives a stub `query-fn` directly and is unaffected.

## Risks

- **R1 — empty-surface shape.** `build-init` reads the result as a map;
  feeding the stub `[]` means the stub returns `{:psi.agent-session/builtin-command-specs []}`,
  not a bare `[]`. If `init-state` naively passed the opt as the whole stub
  return, the empty case would break. Mitigation: the stub always returns a map
  keyed by `:psi.agent-session/builtin-command-specs`; `[]` is the *value*.
- **R2 — incidental candidate breakage.** Premise corrected: unrelated tests
  already receive the full default built-in surface today — the current helper
  ends with `(assoc state :builtin-command-specs (vec builtin-specs))`
  defaulting to the full `sample-builtin-command-specs`, so `(init-state)` is
  *already* non-empty (not "previously empty unless they assoc'd"). This change
  moves *how* the default surface is produced (post-hoc `assoc` → stub
  `query-fn` seam), not *whether* it is present. The candidate set unrelated
  tests see is therefore unchanged by the rewire. Verify none assert *absence*
  of a default built-in name or an exact candidate-count that the built-ins
  would inflate. (Skim shows none do — they assert presence of their own
  concern or exact skill ordering for the `/skill:` prefix which excludes plain
  built-ins.)
- **R3 — `query-fn` slot now non-nil in state.** `build-init` stores
  `:query-fn query-fn` (line 238). Tests that previously had `:query-fn nil`
  now have the stub. Verify no test asserts `:query-fn` is nil. (Skim: none.)

## Slice order

1. **Slice 1 — rewire `init-state` onto the stub `query-fn` seam.** Construct
   the stub `query-fn` inside `init-state`; pass it to `make-init`; remove the
   post-hoc `assoc`. (Default + per-case surfaces both produced via the seam.)
2. **Slice 2 — convert the per-case built-in tests to the helper opt.** Replace
   `(assoc (init-state) :builtin-command-specs …)` in
   `autocomplete-slash-includes-backend-builtin-commands-test` (positive +
   empty) and `autocomplete-slash-dedupes-builtin-template-collision-test`
   (template + extension collision) with `(init-state {:builtin-command-specs …})`;
   the positive branch uses plain `(init-state)` (default fixture). Strengthen
   the positive assertion to all three of `/help`/`/status`/`/quit`.
3. **Slice 3 — verify.** Run the TUI component test suite + clj-kondo; confirm
   no unrelated test regressed (R2/R3); update changelog only if user-visible
   (it is not — test-only).

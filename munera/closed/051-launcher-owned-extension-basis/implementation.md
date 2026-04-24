2026-04-24 — Slice 1

- Added pure launcher planning namespace `bases/main/src/psi/launcher.clj`.
- Added babashka launcher entrypoint `bb/psi.clj`.
- Launcher now consumes `--cwd` and `--launcher-debug` and forwards remaining args to `psi.main` unchanged.
- Initial handoff uses `clojure -M -m psi.main ...` from the selected cwd so startup no longer depends on a user-defined `:psi` alias for repo-local invocation.
- Added focused launcher tests for arg separation, cwd resolution, command construction, and launch-plan shaping.
- Full startup basis synthesis and manifest participation remain for later slices.

2026-04-24 — Slice 2 / Slice 3 foundation

- Added launcher-side manifest read/merge/default-expansion namespace `bases/main/src/psi/launcher/extensions.clj`.
- Added an explicit psi-owned extension catalog with deterministic `:psi/init` mappings and explicit development vs installed source-policy slots.
- Added launcher-owned installed defaults for recognized psi-owned extension libs using one explicit git source identity plus one explicit default installed psi version.
- Implemented pure manifest read, project-over-user merge, coordinate-family validation, psi-owned default expansion, and deterministic init inference.
- Added focused tests proving minimal recognized psi-owned syntax expands, explicit fields override defaults, unrecognized libs do not receive psi-owned defaults, and incoherent entries fail before basis synthesis.

2026-04-24 — Slice 4 / Slice 5 / Slice 6 / Slice 7 progression

- Extended launcher planning so startup basis construction now materializes through `-Sdeps` using psi self-basis plus expanded manifest deps.
- Made launcher policy explicit in code shape with separate development vs installed materialization rules.
- Added launcher debug reporting for cwd, manifest presence, merged lib keys, psi-owned default usage, inferred init usage, and basis summary.
- Added `.bbin/metadata.edn` to provide a bbin-installable `psi` command surface.
- Migrated top-level/operator docs to present `psi` as the canonical startup command.
- Updated Emacs/TUI-facing docs and defaults toward launcher-owned startup usage.
- Updated runtime-facing usage comments so launcher-owned startup is the primary story and repo-local `clojure -M:run ...` flows are explicitly development/non-canonical.
- Added `doc/extensions.md` alignment so extension docs now describe launcher-owned startup basis construction and psi-owned defaulting consistently.
- Proved a local `bbin`-installed launcher command can execute the launcher path and emit the canonical debug summary when invoked from a cwd without project manifests.
- During that proof, fixed launcher handling for a leading `--` argument separator, fixed `bb/psi.clj` launcher-root derivation, fixed absolute-cwd resolution, and stripped runtime-only manifest metadata (`:psi/init`, `:psi/enabled`) from launch-time basis deps before `-Sdeps` handoff.
- Local frictionless `bbin install . --as psi-launcher-auto` now succeeds using repo metadata, and the resulting installed command was exercised successfully against the launcher path with `--cwd`, `--launcher-debug`, and `--rpc-edn`.
- Remaining packaging gap is now specifically about the final remote/canonical install path (`bbin install io.github.hugoduncan/psi --as psi`), not local metadata-driven installation behavior.
- Remote install proof was attempted against `io.github.hugoduncan/psi`, `https://github.com/hugoduncan/psi.git`, and `git@github.com:hugoduncan/psi.git`.
- All three remote installs resolved to published commit `2ce48fb08fc9263552ce769a4ee750acecfb0450`, not the current launcher branch work.
- The generated remote wrapper used stale main opts (`["-m" "hugoduncan.psi"]` / similarly incorrect inferred values for raw URL forms), and execution failed because that published commit does not yet contain the launcher entrypoint/package metadata.
- Conclusion: remote canonical install is blocked on publication/merge of the launcher work to the remotely installed ref, not on additional local launcher behavior fixes.

2026-04-24 — Munera task review

Review outcome: changes requested before closure.

What is strong:
- The implementation matches the core design direction: launcher-owned startup basis construction now happens before `psi.main` starts.
- The launcher/runtime boundary is shaped well: launch-time dependency realization lives in the launcher, while runtime introspection and post-startup behavior remain runtime-owned.
- The explicit psi-owned extension catalog in `psi.launcher.extensions` is a good fit for the design requirement that defaults and `:psi/init` inference be deterministic, explicit, and versioned.
- The code shape is mostly simple and local: small pure planning/expansion functions with focused tests.

Review findings to address:
1. Installed-mode launcher policy is not actually selected by the babashka entrypoint.
   - `bb/psi.clj` currently returns `:development` unconditionally from `default-policy`.
   - The lower-level launcher code supports distinct `:development` and `:installed` policies, but the top-level launcher entrypoint does not yet implement a real installed-mode selection story.
   - Before task closure, either make policy selection real and explicit or narrow the surface/docs so the current behavior is accurately represented.

2. Operator-facing docs overstate the status of the canonical remote `bbin` install path.
   - `README.md` and `doc/cli.md` currently present `bbin install io.github.hugoduncan/psi --as psi` as the canonical working path.
   - The implementation notes above establish that the remote install still resolves to an older published ref that predates launcher packaging work.
   - Until publication catches up, docs should not present the remote path as already proven-working without caveat.

3. Launcher debug reporting underreports psi-owned default usage.
   - `manifest-state` currently infers `:defaulted-libs` from whether the pre-expansion dep entry had no coordinate family.
   - That misses partially explicit psi-owned entries such as `{psi/mementum {:git/sha "override-sha"}}`, where launcher defaults still supply `:git/url`, `:deps/root`, and possibly `:psi/init`.
   - The debug surface should derive default/inference reporting from the actual expansion results, not from a pre-expansion heuristic.

4. Task artifact hygiene needs cleanup.
   - `steps.md` currently contains duplicated stale sections for slices 5/6/7 and leaves review checkpoints inconsistent with the actual implementation state.
   - Before closure, task steps should be made coherent and extended with follow-up review-fix items.

Closure recommendation:
- Do not close task 051 yet.
- First fix launcher policy selection, align docs with real remote packaging status, make launcher debug reporting authoritative, and clean up task steps/review bookkeeping.

2026-04-24 — Review-fix follow-up

- Replaced the placeholder babashka launcher top-level policy selection in `bb/psi.clj` with an explicit launcher policy contract driven by `PSI_LAUNCHER_POLICY`:
  - default policy is now `installed`
  - `PSI_LAUNCHER_POLICY=development` opts into repo-local development materialization
  - invalid values now fail clearly
- Narrowed operator-facing install docs to stop overstating the remote package path while publication is still catching up:
  - `README.md` now presents `bbin install . --as psi` as the current proven path
  - `doc/cli.md` now distinguishes the current proven local install path from the intended remote canonical path after publication
  - docs now also describe the explicit launcher policy boundary for contributor/repo-local flows
- Made launcher debug/default reporting authoritative by routing manifest-state reporting through an expansion report produced by the actual expansion pipeline:
  - added `expand-entry-report`
  - added `manifest-expansion-report`
  - `manifest-state` now derives `:defaulted-libs` and `:inferred-init-libs` from expansion results rather than pre-expansion coordinate heuristics
- Added focused launcher tests covering:
  - minimal psi-owned entries reporting defaults and inferred init
  - partially explicit psi-owned entries still reporting launcher-supplied defaults and inferred init
  - explicit `:psi/init` suppressing inferred-init reporting
  - launcher manifest-state reporting driven by expansion results
- Investigated launcher-focused test execution:
  - current Kaocha `--focus` usage against these namespaces skipped all tests because `--focus` targets Kaocha ids/testables rather than raw namespace symbols in this setup
  - used direct `clojure.test/run-tests` execution through the `:test-paths` alias instead
  - successful focused verification command:
    - `clojure -M:test-paths -e "(require 'psi.launcher-test 'psi.launcher.extensions-test 'clojure.test) (let [result (clojure.test/run-tests 'psi.launcher-test 'psi.launcher.extensions-test)] (println result) (System/exit (if (clojure.test/successful? result) 0 1)))"`
  - verification result: `13 tests, 39 assertions, 0 failures, 0 errors`
- Remaining open closure gap is still remote publication/package proof for `bbin install io.github.hugoduncan/psi --as psi`; local/install/docs/policy/debug follow-up fixes are now in place.

2026-04-24 — PR #42 CI follow-up

- PR #42 was still failing in GitHub Actions in the `Clojure Tests` job, specifically the tmux-backed integration test `psi.tui.tmux-integration-harness-test/tui-tmux-harness-basic-scenario-test`.
- The observed GH failure shape was a startup timeout followed by a missing tmux server snapshot, which initially looked like a flaky TUI/tmux startup issue.
- Investigation showed the more direct cause was launcher startup failure in the CI-installed `psi` shim path:
  - the integration-job shim invokes `bb bb/psi.clj -- ...`
  - `bb/psi.clj` now depends on `psi.launcher`
  - `psi.launcher` lives under `bases/main/src`, which was not on babashka's classpath from `bb.edn`
- Added `bases/main/src` to the top-level `bb.edn` `:paths` so `bb bb/psi.clj ...` can load `psi.launcher` in ordinary repo-local and CI shim execution.
- After that fix, direct launcher invocation became runnable (`bb bb/psi.clj --help` reached the launcher path rather than failing namespace resolution), but installed-mode default policy immediately exposed a second problem for repo/CI shim usage:
  - installed policy materializes psi self-basis as git deps
  - local repo/CI shim execution should use the development realization policy instead
- Updated the CI integration-job shim to set `PSI_LAUNCHER_POLICY=development` explicitly, both in the generated `psi` wrapper and in the job environment.
- Tightened the shim smoke check to fail fast instead of swallowing launcher startup problems:
  - changed `... >/dev/null || true` to a plain launcher invocation so CI will stop immediately if the shim cannot start.
- Local verification after the fixes:
  - `clojure -M:test --focus psi.tui.tmux-integration-harness-test --skip-meta foo` → passes (`1 tests, 1 assertions, 0 failures`)
  - `bb bb/psi.clj --help` now resolves launcher code instead of failing with `Could not locate psi/launcher...`
- Net result: the PR failure was not a tmux harness logic regression; it was a launcher-classpath plus policy-selection mismatch in the CI shim path.

2026-04-24 — Remote canonical install proof now succeeds

- Re-ran the final canonical remote packaging proof after the launcher work landed on `master`.
- `bbin install io.github.hugoduncan/psi --as psi-remote-proof-051` now resolves merged commit `527dc2a5d600df9a8adeead9bda825993419c465`.
- Verified the installed command starts successfully and exercises the canonical launcher path:
  - `psi-remote-proof-051 --help` reaches the runtime launcher path and starts psi successfully
  - `psi-remote-proof-051 --launcher-debug --cwd /Users/duncan/projects/hugoduncan/psi/fix-extensions /quit` emits launcher debug output and launches psi with the expected startup basis
- The remote-installed launcher debug output showed:
  - policy `installed`
  - startup basis materialized from the remote gitlibs checkout under `~/.gitlibs/libs/io.github.hugoduncan/psi/527dc2a5...`
  - expected built-in extensions loaded, including `workflow-loader`
- Confirmed the installed remote ref matches current `origin/master`:
  - local `git rev-parse origin/master` → `527dc2a5d600df9a8adeead9bda825993419c465`
  - remote-installed launcher basis also resolved `527dc2a5d600df9a8adeead9bda825993419c465`
- This clears the final external blocker recorded in `steps.md`.
- Task 051 is now complete by its own definition of done.

Initialized from user request on 2026-04-30.

Coordination note

- This is a narrow refactor task.
- It should preserve current prompt contribution behavior while relocating large protocol text artifacts out of code and into resources.
- It is adjacent to prompt-lifecycle cleanup work, but should not be widened into a generic prompt-resource system unless a tiny shared helper is the simplest way to avoid duplication.

Initial observations

- `extensions/mementum/src/extensions/mementum.clj` currently embeds the full protocol body in `mementum-lambda` and prepends `engage-prefix` outside lambda mode.
- `extensions/munera/src/extensions/munera.clj` currently embeds the full protocol body in `munera-lambda` and uses the same mode-sensitive prefixing pattern.
- Both extensions currently register prompt contributions directly during `init` using fixed ids/sections/priorities.
- Resource-local prompt bodies appear to be the natural artifact boundary for this change.

Execution notes

- Added extension-local resources:
  - `extensions/mementum/resources/extensions/mementum/protocol.txt`
  - `extensions/munera/resources/extensions/munera/protocol.txt`
- Kept `engage-prefix`, prompt-mode branching, contribution ids, sections, priorities, and `:enabled true` unchanged in code.
- Added one small local helper per namespace to read the protocol resource via `clojure.java.io/resource` + `slurp` with `:encoding "UTF-8"`.
- Missing resources now fail fast with `ex-info` carrying `{:extension ... :resource-path ...}` and naming the resource path in the exception message.
- Updated root `deps.edn` aliases so the new extension resource directories are on the classpath where these extensions are loaded, and so the new focused extension test directories are included in test aliases.
- Added focused tests:
  - `extensions/mementum/test/extensions/mementum_test.clj`
  - `extensions/munera/test/extensions/munera_test.clj`
- The focused tests assert:
  - lambda-mode registration content equals exactly the resource body
  - prose-mode registration content equals exactly `(str engage-prefix protocol-body)`
  - contribution metadata remains unchanged
  - missing-resource failure throws explicit `ExceptionInfo` with resource path surfaced

Verification

- Focused verification passed with:
  - `clojure -Sdeps '{:paths ["extensions/mementum/src" "extensions/mementum/resources" "extensions/mementum/test" "extensions/munera/src" "extensions/munera/resources" "extensions/munera/test" "components/extension-test-helpers/src"]}' -e "(require 'extensions.mementum-test 'extensions.munera-test)(let [result (clojure.test/run-tests 'extensions.mementum-test 'extensions.munera-test)] (shutdown-agents) (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))"`
- Result: `Ran 6 tests containing 12 assertions. 0 failures, 0 errors.`

Notes

- Attempting to use the repo `:test` alias directly for this focused verification hit the project's Kaocha/`-M:test` entrypoint rather than plain `clojure.main`, so the explicit `-Sdeps` command above was used for a narrow proof without widening the task.

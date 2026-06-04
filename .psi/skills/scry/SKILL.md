---
name: scry
description: >
  Use scry to run Clojure tests through the REPL/in-process API for structured
  inspection and through the CLI for final verification, process status, and
  failure EDN files. Trigger when debugging Clojure test failures, when an agent
  needs expected/actual assertion data, per-test output, stack traces, or CLI
  exit semantics without scraping terminal output, or when working in a project
  that depends on scry.
lambda: "λclj_tests. (run(repl/in_process) → inspect({:summary :pass? :results :failures})) ∨ (run(cli) → verify(exit_code ∧ .scry-results)) ∧ avoid(scrape_terminal_output)"
metadata:
  tags: ["clojure", "testing", "repl", "cli", "ci", "ai-agents", "structured-results"]
  language: "clojure"
---

# Scry

Use `scry` to run Clojure tests and inspect structured results instead of scraping human-oriented terminal output.

Use the REPL/in-process API while iterating: it keeps the latest result available in the process and supports targeted structured inspection. Use the CLI for final verification and CI-style checks: it reports process status, live progress, summaries, and failure EDN files.

## When to use this skill

Use this skill when:

- You are in a Clojure project that has `scry` available on the classpath.
- You need to know whether tests passed and which test vars failed.
- You need failure details such as `:expected`, `:actual`, `:message`, `:file`, `:line`, testing contexts, or stack traces.
- You need stdout/stderr captured for a targeted single test var.
- You are debugging interactively from a REPL and want machine-readable results.
- You need command-line exit semantics or failure files for final verification.

Do **not** scrape terminal output if `scry` can provide structured result maps or EDN result files.

## Result shape

The top-level result shape is:

```clojure
{:summary {:test 0
           :pass 0
           :fail 0
           :error 0
           :duration-ms 0.0
           :var-count 0
           :fail-var-count 0}
 :pass? true
 :results []
 :failures []}
```

`:results` is the canonical formatted collection. `:failures` is a compatibility collection containing failing/erroring entries when the selected format includes it.

Default result detail follows invocation intent:

- Suite/multi scope (`(scry/run)`, multiple namespaces, or multiple vars): compact failing/erroring entries with `:assertion-summary`; no `:assertions`, `:out`, or `:err` by default.
- Single namespace scope (`{:namespaces ['my.project-test]}`): all executed vars, including passing vars, with all assertion details; no output keys by default.
- Single var scope (`{:vars [#'my.project-test/specific-test]}`): one entry with all assertion details and `:out`/`:err`.

## REPL / in-process workflow

Prefer REPL/in-process runs for interactive debugging and iteration:

```clojure
(require '[scry.core :as scry])

(scry/run)
```

`scry/run` stores the most recent result, so after a run you can inspect it interactively:

```clojure
(scry/last-result)
(:pass? (scry/last-result))
(:summary (scry/last-result))
(:results (scry/last-result))
(scry/failures)
(scry/failed-test 'my.project-test/failing-test)
(scry/output 'my.project-test/failing-test)
(println (scry/report-string (scry/last-result)))
```

## Targeted REPL runs

Run discovered tests using defaults:

```clojure
(scry/run)
```

Run tests from explicit directories:

```clojure
(scry/run {:dirs ["test"]})
```

Run namespaces matching a pattern:

```clojure
(scry/run {:ns-pattern #".*-test$"})
```

Run explicit namespaces:

```clojure
(scry/run {:namespaces ['my.project-test]})
```

Run explicit vars:

```clojure
(scry/run {:vars [#'my.project-test/specific-test]})
```

## Command-line workflow

Prefer the CLI for final verification, shell scripts, and CI-style status. The CLI uses the same structured result model but adds process exit behavior, live per-var progress, summaries, and `.scry-results/` failure files.

Core entry points:

```sh
clojure -M:test -m scry.cli
clojure -X:test scry.cli/run
```

Focused core selectors:

```sh
clojure -M:test -m scry.cli --var my.project-test/specific-test
clojure -X:test scry.cli/run :vars '[my.project-test/specific-test]'
```

Useful namespace/directory examples:

```sh
clojure -M:test -m scry.cli --dir test --ns-pattern '.*-test$'
clojure -M:test -m scry.cli --namespace my.project-test
clojure -X:test scry.cli/run :dirs '["test"]' :namespaces '[my.project-test]'
```

CLI contract:

- Prints `.` to stdout for each passing test var.
- Prints failing/erroring unqualified var names to stderr.
- Prints a summary after the run.
- Clears and recreates `.scry-results/` at run start.
- Writes namespace-prefixed `.edn` files under `.scry-results/` for failing/erroring vars.
- Leaves `.scry-results/` empty on passing runs.
- Exits non-zero for failures, errors, unknown status, runner/argument errors, or zero executable tests.

Do not scrape progress text for details. For `-m` runs, use the process exit code and summary for status, then inspect `.scry-results/*.edn` for failing/erroring var details. For `-X` runs, inspect the returned outcome map on success; on non-zero outcomes catch/read the thrown `ex-info` data, especially `:exit-code`, `:summary`, `:error`, and `:outcome`.

Optional Kaocha CLI mode requires the optional adapter on the classpath:

```sh
clojure -M:test:kaocha -m scry.cli --runner kaocha --suite unit
clojure -X:test:kaocha scry.cli/run :runner :kaocha :suite :unit
```

Kaocha CLI mode is optional; core users do not need the Kaocha adapter. As with the adapter API, Kaocha-captured stdout/stderr are preserved as merged `:out` with empty `:err` in result files unless the adapter supplies separate streams.

## Custom formatting

Configure top-level keys, entry keys, assertion inclusion, and output inclusion independently by scope:

```clojure
(scry/run
 {:vars [#'my.project-test/specific-test]
  :result-format
  {:var {:top-level-keys [:summary :pass? :results]
         :entry-keys [:var :status]
         :assertions? true
         :output? false}}})
```

`:assertions?` and `:output?` are authoritative gates: true adds those keys, false removes them, even if `:entry-keys` says otherwise.

## Interpreting failures

Detailed failing entries have this shape:

```clojure
{:var 'my.project-test/failing-test
 :ns 'my.project-test
 :status :fail ;; or :error
 :assertions [{:type :fail
               :message "expected equality"
               :expected '(= 1 2)
               :actual '(not (= 1 2))
               :file "project_test.clj"
               :line 42
               :contexts ["outer testing" "inner testing"]}]
 :out "captured stdout"
 :err "captured stderr"}
```

Error assertions also include `:stacktrace`.

Debugging loop:

1. Run `(scry/run)` and check `:pass?`.
2. If false, inspect `(scry/failures)` or `(:results (scry/last-result))` rather than rerunning with noisier output.
3. Use `:var`, `:file`, and `:line` to locate the failing test.
4. Use assertion `:expected`, `:actual`, `:contexts`, and `:stacktrace` to understand the failure when assertion details are included.
5. Rerun a targeted namespace or var when more detail/output is useful.
6. Make the smallest fix and rerun the targeted namespace or var when possible.
7. Before handoff or CI-style confidence, run the appropriate CLI command and inspect structured artifacts if it fails.

## Kaocha REPL adapter

If the project uses the optional Kaocha adapter, require and run it from the REPL:

```clojure
(require '[scry.kaocha :as k])

(k/run)                              ;; loads tests.edn when present
(k/run {:suites [:unit :integration]})
(k/run {:suite :unit})               ;; single-suite convenience
(k/run {:config full-kaocha-config}) ;; full config override
```

The adapter returns the same scoped result model as `scry.core/run`, currently in suite scope by default. Without `:config`, it loads the current project's `tests.edn` when present and otherwise uses the fallback synthetic `:unit` suite. With `:config`, the supplied Kaocha config is authoritative; scry applies suite selection and quiet runtime defaults but does not merge fallback paths into it.

Suite selectors match exact configured suite ids first, then unique string/name fallback. Use `:suite` for one selector; plural `:suites` must be a non-empty collection. Conflicts, unknown selectors, and ambiguous fallback selectors throw `ex-info`.

Caveat: Kaocha's capture-output plugin merges stdout and stderr into one stream. `scry.kaocha` puts the combined output in `:out` and leaves `:err` empty.

## Agent rules

- Prefer structured `scry` results over parsing console text.
- Use REPL/in-process runs for interactive debugging and targeted iteration.
- Use CLI runs for final verification and CI-style process status.
- Prefer `:results` as canonical; use `scry/failures` for failing/erroring entries.
- For CLI failures, inspect `.scry-results/*.edn` (`-m`) or returned/thrown structured data (`-X`) instead of scraping progress text.
- Preserve normal `clojure.test` semantics; `scry.clojure-test` uses a local fixture-preserving loop around `clojure.test/test-var` to retain normal `:once`/`:each` fixture grouping and ordering while owning per-var output capture.
- Use targeted `:namespaces` or `:vars` runs while iterating on a specific failure.
- After changing public behavior, update user-facing docs and tests.

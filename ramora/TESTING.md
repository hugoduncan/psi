# Testing Guide

## Test Timeouts

⚠️ **Extended timeout required**: The test suite has significant JVM startup time (~20+ seconds) due to expensive library loading:

- **timbre** (~5-6 seconds) - logging library from statecharts
- **pathom3** (~4 seconds) - EQL query engine
- **charm.clj** (~10 seconds) - TUI library with JLine3

**Recommended timeouts:**
- CI/automated systems: 60+ seconds
- Local development: 30+ seconds
- IDEs/editors: Adjust timeout settings accordingly

## Running Tests

```bash
# Unit tests (long startup, then fast execution)
bb clojure:test:unit

# Component tests (isolated, but still slow startup)  
bb test:components

# Extensions tests
bb clojure:test:extensions

# Integration tests (slow, requires tmux; skips locally with warning if tmux is missing)
bb clojure:test:integration

# All tests
bb test
```

## Structured Scry Runs

Psi includes `org.hugoduncan/scry` and `org.hugoduncan/scry-kaocha` on the test
classpath. The default `bb clojure:test:*` tasks run the configured Kaocha suites
through Scry first, preserving suite semantics while making structured failure
artifacts the default diagnostic path. They fall back to the previous raw Kaocha
runner only if the Scry runner itself cannot execute.

Use focused Scry CLI or REPL runs when you need machine-readable assertion data
instead of scraping terminal output:

```bash
# Focus one namespace with CLI exit semantics
bb clojure:test:scry --namespace psi.session-state.state-test

# Equivalent direct CLI invocation
clojure -M:test-paths -m scry.cli --namespace psi.session-state.state-test
```

From a warm project REPL started with the default project nREPL command:

```clojure
(require '[scry.core :as scry])

(scry/run {:namespaces ['psi.session-state.state-test]})
(:pass? (scry/last-result))
(scry/failures)
```

Use `bb clojure:test:*` for Scry-first CI-style suite verification; use
`bb clojure:test:scry` or the REPL API for targeted structured inspection and
iteration.

## Performance Notes

The startup cost is incurred once per JVM instance. Using a warm REPL or nREPL connection can amortize this cost across multiple test runs.

When iterating in a long-lived REPL/nREPL process, `temp-cwd` and
`temp-session-root` shutdown-hook cleanup only runs when that JVM exits.
Temporary directories with the `psi-agent-session-test-` and
`psi-agent-session-store-` prefixes may therefore remain under the OS temp
directory until the REPL is restarted or the prefixes are swept manually;
short-lived `bb test` CLI runs exit the JVM after the run and trigger the
cleanup promptly.

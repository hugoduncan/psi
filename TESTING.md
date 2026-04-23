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

## Performance Notes

The startup cost is incurred once per JVM instance. Using a warm REPL or nREPL connection can amortize this cost across multiple test runs.
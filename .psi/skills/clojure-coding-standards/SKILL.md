---
name: clojure-coding-standards
description: Comprehensive Clojure coding standards including function formatting, testing patterns, REPL workflows, dependency management, and code organization. Use whenever writing Clojure or babashka code, or tests, reviewing Clojure or babashka projects, or setting up Clojure or babashka development workflows.
lambda: "λlang∈{clj bb}. {fmt test repl deps org} → standards"
license: MIT
metadata:
  version: "1.0.0"
  tags: ["babashka", "clojure", "coding-standards", "testing", "repl"]
  language: "clojure+babashka"
allowed-tools: bash read clojure_edit clojure_edit_replace_sexpr clojure_lsp
---

# Clojure Coding Standards

Comprehensive Clojure coding standards for maintainable, readable code.

## Core Formatting Rules

### Function Structure
- **Doc strings** always come **before** the argument vector
- **File size limit**: 800 lines maximum per file
- **No lazy sequences** unless explicitly required

```clojure
(defn process-data
  "Processes the input data according to business rules.
   Returns processed map with validation results."
  [data options]
  (-> data
      validate-input
      (transform-with options)
      add-metadata))
```

### Architecture

- Use explicit arguments instead, not dynamic vars.
- Prefer not to use multimethods.  Use something that does use global mutable state.
- Only use `declere` for mutual recursion
- Do not use `in-ns` in production code.
- Do not use `try` `catch` exception handling for flow control
- Do not shadow locals, vars or functions
- Break dependency cycles architecturally, not use requiring-resolve
- Vars (e.g. functions) do not need to be public to be tested; tests can use the #' idiom.
- Prefer clojure idioms to java interop

λx.prefer(compose(transducers))∧when(working_with(sequences),thread(sequence_functions))

### Dependencies

Find available versions:
```bash
clojure -T:deps find-versions :lib io.github.user/repo
```

For git-based dependencies:
```bash
clojure -T:deps git-resolve-tags :lib io.github.user/repo
```

## Testing Standards

### Test Organization
- **Fast tests by default** - slow tests in separate profile
- **No docstrings** in `deftest` forms
- **Start each test** with intention comment
- **BDD-style testing forms** that read like specifications

### Test Structure Template
```clojure
(deftest add-research-messages-test
  ;; Tests the add-research-messages function for proper message handling
  ;; and metadata updates according to the research workflow contract
  (testing "add-research-messages"
    (testing "appends new research messages"
      (is (= expected-messages
             (get-in result [:messages]))))
    (testing "updates the :updated-at field"
      (is (inst? (get result :updated-at))))))
```

### Testing Best Practices
- **Filesystem fixtures**: Use test-resources in test-specific sub-directories
- **Informative assertions**: Write assertions that provide debugging info when they fail
- **No use-fixtures**: Prefer `(with-xxx [args] ...)` macros for setup/teardown
- **Zero tolerance**: Fix failing/flaky tests immediately
- **Isolation**: Each test should be independent
- **Deterministic**: tests should be deterministic and repeatable. no sleeps.
- No test only code paths in production.
- Test boundary contracts. Testing implementation details makes tests brittle, and production code harder to change.

Prefer testing-without-mocks.

## REPL Workflow

### Discovery & Connection
```bash
# Discover running nREPL servers
clj-nrepl-eval --discover-ports

# Start nREPL server
clojure -M:nrepl:dev

# Evaluate code
clj-nrepl-eval -p <PORT> "(+ 1 2 3)"
```

### REPL Management
```clojure
;; Sync dependencies after deps.edn changes
(do (require 'clojure.repl.deps)
    (clojure.repl.deps/sync-deps))

;; Remove var binding completely
(ns-unmap *ns* 'my-var)

;; Reload namespace
(require 'my.ns :reload)
```

### Linting
```bash
clj-kondo --lint src
```

## Code Formatting

### Parenthesis Repair
**NEVER manually fix parenthesis errors.**

Use the tool:
```bash
clj-paren-repair path/to/file1.clj path/to/file2.clj
```

The tool automatically:
- Fixes delimiters
- Formats code with cljfmt
- Reports any issues

## Comments & Documentation

### Comment Hierarchy
- **Section separators**: Use `;;;` prefix (no decorative lines)
- **Function docs**: Public functions get docstrings
- **Inline comments**: Focus on WHY, not WHAT

### Required Comments
- **Business logic**: Domain rules and requirements
- **Non-obvious algorithms**: Complex implementations
- **Edge cases**: Assumptions and limitations
- **TODOs/FIXMEs**: Known issues

### Never Comment
- Self-explanatory code
- Function names restated
- Stale information

### Docstring Format
```clojure
(defn complex-calculation
  "Calculates the adjusted risk score based on market volatility.

   Returns a map with :score and :confidence-level keys.
   Throws ExceptionInfo if market-data is invalid."
  [market-data user-preferences]
  ;; Implementation here
  )
```

## Error Reporting

For command-line error reports on stderr:
```bash
clojure -M:stderr-report
```

## Project Organization

### File Structure
- Keep related functions together
- Separate pure functions from side effects
- Group by domain, not by technical layer

### Namespace Guidelines
- Clear, descriptive namespace names
- Avoid deeply nested namespaces
- Use consistent prefixes for project namespaces

## Quick Reference Commands

```bash
# Lint codebase
clj-kondo --lint src

# Fix formatting issues
clj-paren-repair src/**/*.clj

# Start development REPL
clojure -M:nrepl:dev

# Run fast tests
clojure -M:test

# Run all tests (including slow ones)
clojure -M:test:integration
```

## Integration with Editors

### Preferred Tools
- Leverage `clojure_lsp` for diagnostics and refactoring

### REPL Integration
- Keep REPL connection active during development
- Evaluate forms as you write them
- Use REPL for exploratory programming

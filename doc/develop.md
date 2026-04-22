# Development Guide

## Prerequisites

Install `pre-commit`:

```bash
# preferred
pipx install pre-commit

# or on Homebrew-based systems
brew install pre-commit
```

> **Note:** macOS can ship a broken Python 2 `pre-commit` stub at
> `/usr/local/bin/pre-commit`. A pipx- or Homebrew-installed version on
> your PATH should take precedence.

## Setting up the git hooks

After cloning, install the pre-commit hooks once:

```bash
pre-commit install
```

## Pre-commit hooks

### `cljfmt-fix`

Runs `cljfmt fix` on every staged Clojure file (`.clj`, `.cljs`, `.cljc`)
before each commit.

- Files that are reformatted are automatically restaged.
- The hook exits with a non-zero status when it reformats anything, so
  pre-commit reports what changed. Re-run `git commit` to proceed.
- `cljfmt` must be on your `PATH` (e.g. installed via
  [bbin](https://github.com/babashka/bbin): `bbin install io.github.weavejester/cljfmt`).

#### Manual run

```bash
# Run against all files in the repo
pre-commit run --all-files

# Run only the cljfmt hook
pre-commit run cljfmt-fix

# Run against specific files
pre-commit run cljfmt-fix --files src/foo/bar.clj
```

### `clj-kondo-lint`

Runs `clj-kondo --lint` on every staged Clojure file before each commit.
The commit is blocked if there are any warnings or errors.

- Uses `--cache false` to avoid JVM file-lock contention when pre-commit
  parallelises the hook across multiple files.
- Macro aliases for Pathom3, Guardrails, Malli, Promesa, and Potemkin are
  declared in the root `.clj-kondo/config.edn` so individual-file linting
  works without a full classpath scan.
- `clj-kondo` must be on your `PATH` (e.g. installed via
  [bbin](https://github.com/babashka/bbin): `bbin install clj-kondo/clj-kondo`).

#### Manual run

```bash
# Run only the clj-kondo hook
pre-commit run clj-kondo-lint

# Run against specific files
pre-commit run clj-kondo-lint --files src/foo/bar.clj
```

## Formatting

Check formatting without modifying files:

```bash
bb fmt:check
```

Fix formatting across the whole repo:

```bash
cljfmt fix bb.edn deps.edn .lsp/config.edn .psi/startup-prompts.edn \
  components extensions spec test tests.edn extensions/tests.edn
```

## Linting

```bash
bb lint
```

## Commit checks

Project-local commit checks can be wired through `.psi/commit-checks.edn` and
run bb tasks that fail with a non-zero exit when a check should block follow-up.

This repo defines:

```bash
bb commit-check:rama-cc
bb commit-check:file-lengths
bb commit-check:dispatch-architecture
```

### `commit-check:rama-cc`

Runs:

```bash
rama-cc --threshold 21 --fail-above 20 components/ bases/
```

This task exits with the same exit code returned by the shell command, even
when `rama-cc` reports zero matched files.

### `commit-check:file-lengths`

Scans `components/` and `bases/` for files under `src/` or `test/` and fails if
any exceed 800 lines.

When it fails it prints the matching files to stderr and exits non-zero.

### `commit-check:dispatch-architecture`

Runs a psi-specific dispatch architecture check for `agent-session`.

Current behavior:
- fails on dispatch effect parity drift:
  - emitted `:effect/type` missing from `dispatch_schema.clj`
  - emitted `:effect/type` missing from `dispatch_effects.clj`
  - schema-declared effect without executor
  - executor without schema declaration
- reports advisory warnings for:
  - direct side-effect candidates inside `dispatch_handlers/`
  - direct canonical `(:state* ctx)` writes outside a small allowlist of
    infrastructure namespaces

This task is intentionally narrow and project-specific so we can prove its
usefulness before broadening scope or upstreaming ideas.

## CI

The GitHub Actions workflow (`.github/workflows/ci.yml`) runs on:
- manual trigger (`workflow_dispatch`)
- push to `master`
- pull request targeting `master`

### Jobs

```
check (fmt + lint)
├── clojure-test
└── emacs-test
```

`check` runs first. `clojure-test` and `emacs-test` run in parallel only
if `check` passes.

| Job | Tasks |
|---|---|
| `check` | `bb fmt:check`, `bb lint` |
| `clojure-test` | `bb clojure:test` (unit + extensions) |
| `emacs-test` | `bb emacs:check` (byte-compile + ERT) |

Maven and Clojure deps (`~/.m2`, `~/.gitlibs`, `~/.clojure`) are cached
and keyed on `deps.edn` + `bb.edn` to speed up subsequent runs.

## Tests

```bash
# All tests
bb test

# Clojure unit tests only
bb clojure:test:unit

# Clojure extension tests only
bb clojure:test:extensions

# Emacs frontend tests
bb emacs:check
```

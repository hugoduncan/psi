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

## Cutting a release

### Prerequisites

- Write access to `https://github.com/hugoduncan/psi` (push to `master` + tags).
- `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` (deploy token) set as GitHub Actions
  secrets on the repo (`Settings → Secrets → Actions`).
- Local working tree clean, on `master`, up to date with origin.
- `CHANGELOG.md` has a non-empty `## [Unreleased]` section
  (`bb changelog:check` to verify).

### Procedure

```bash
bb release
```

This single command:
1. Asserts clean tree + on `master`.
2. Computes `PATCH = (git rev-list HEAD --count) + 1`.
3. Stamps `CHANGELOG.md`: `[Unreleased]` → `[MAJOR.MINOR.PATCH] - YYYY-MM-DD`,
   prepends a fresh `[Unreleased]`, and updates the comparison link footer.
4. Writes `{:version "MAJOR.MINOR.PATCH"}` to `bases/main/resources/psi/version.edn`.
5. Commits `"release: vMAJOR.MINOR.PATCH"` and tags `vMAJOR.MINOR.PATCH`.
6. Resets `version.edn` to `{:version "unreleased"}` and commits
   `"release: post-vMAJOR.MINOR.PATCH reset version to unreleased"`.
7. Pushes `master` + tags to origin.

Pushing the tag triggers `.github/workflows/release.yml`, which:
- Re-runs fmt/lint/tests.
- Builds and deploys the library jar to Clojars (`io.github.hugoduncan/psi`).
- Smoke-tests the `:jar` launcher policy against the deployed Clojars artifact
  (retries up to 8×30s for propagation).
- Builds the uberjar.
- Creates a GitHub Release with the changelog body and jar assets attached.

### Partial-failure recovery

`bb release` and `bb release:tag` are re-entrant:

| Failure point | Recovery |
|---|---|
| Died after `stamp-changelog!`, before `git commit` | Re-run detects stamped changelog, resumes from commit |
| Died after tag, before version reset commit | Re-run detects tag + un-reset version resource, completes reset |
| Died after version reset, before push | `bb release` detects local tag not on origin, goes straight to push |
| Push failed (network) | Re-run `bb release` — detects local tag not on origin, retries push |

If the GH Actions release job fails after Clojars deploy but before GH Release
creation, re-pushing the tag is not safe (tag already exists). Instead:
1. Fix the issue (e.g. changelog section missing for the version).
2. Manually trigger the release workflow via `workflow_dispatch` on the tag, or
3. Manually run `bb build:jar` + create the GH Release via `gh release create`.

### Verifying a release

After the workflow completes:

```bash
# Verify Clojars artifact
clojure -Sdeps '{:deps {io.github.hugoduncan/psi {:mvn/version "X.Y.Z"}}}' \
  -M -m psi.main --version

# Verify bbin install
bbin install io.github.hugoduncan/psi --as psi --git/tag vX.Y.Z
psi --version
```

### Debugging Clojars deploy without a full release

`bb build:lib` and `bb deploy` can be run standalone against an already-stamped
version resource for debugging:

```bash
# 1. Temporarily stamp the version resource (do NOT commit)
echo '{:version "0.1.9999"}' > bases/main/resources/psi/version.edn

# 2. Build the library jar
bb build:lib   # → target/psi-0.1.9999.jar

# 3. Deploy to Clojars (requires CLOJARS_USERNAME + CLOJARS_PASSWORD in env)
CLOJARS_USERNAME=you CLOJARS_PASSWORD=token bb deploy

# 4. Restore the version resource
echo '{:version "unreleased"}' > bases/main/resources/psi/version.edn
```

`bb deploy` auto-invokes `bb build:lib` if the jar is absent, so steps 2 and 3
can be combined as just `bb deploy`.

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

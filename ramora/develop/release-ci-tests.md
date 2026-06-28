# Development Guide — Release, CI, and Tests

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

For a workflow-only validation run with no local release/tag/push side effects:

```bash
bb release --dry-run
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
- Builds and deploys the library jar to Clojars (`org.hugoduncan/psi`).
- Smoke-tests the `:jar` launcher policy against the deployed Clojars artifact
  (retries up to 8×30s for propagation).
- Builds the uberjar.
- Creates a GitHub Release with the changelog body and jar assets attached.

The same workflow also supports manual dry-run testing via `workflow_dispatch`
without publishing anything publicly. `bb release --dry-run` now does exactly that:
it dispatches the `Release` workflow in non-publishing mode and performs no local
release stamping, tagging, commits, or pushes.

If you need to run it manually via GitHub Actions → `Release` → `Run workflow`, use:
- `publish = false` to validate the release build path without external publication
- optional `ref` to test a branch/commit instead of the current ref
- optional `release_version` to force a specific version label for the dry-run

Dry-run mode now stamps `bases/main/resources/psi/version.edn` inside the runner,
builds the library jar, installs that jar into the runner's local Maven repo,
smoke-tests both the `:jar` policy and the released `bbin` launcher entrypoint
against that locally installed artifact, builds the uberjar, and still skips
external publication steps such as Clojars deploy, changelog extraction, and
GitHub Release creation.

The released jar now carries jar-owned runtime dependency metadata at
`psi/release-deps.edn`. Release launcher startup under `:jar` policy reads that
artifact-owned payload to construct psi's shipped runtime basis, while the jar
itself carries the packaged psi-owned runtime source/resource trees. User and
project extension manifests remain additive over that base.

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
clojure -Sdeps '{:deps {org.hugoduncan/psi {:mvn/version "X.Y.Z"}}}' \
  -M -m psi.main --version

# Verify bbin install
bbin install org.hugoduncan/psi --as psi --mvn/version X.Y.Z
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

# Focused structured clojure.test inspection with Scry
bb clojure:test:scry --namespace psi.session-state.state-test

# Emacs frontend tests
bb emacs:check
```

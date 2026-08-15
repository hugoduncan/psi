# Implementation steps — 252-unresolved-var-lint-warning

Concrete checklist for the implementation slice. Read design.md + plan.md first.
Treat this file as the active surface; tick items as they complete, noting shas/decisions.

## Slice 0 — Baseline & facility ground truth

- [x] Reproduce the baseline: `bb lint` from repo root → expect exactly
      `extensions/dev-http/test/extensions/dev_http_test.clj:572:5` (`http-client/get`)
      and `:737:5` (`http-client/post`), `errors: 0, warnings: 2`
- [x] Record pre-change root config state: `.clj-kondo/config.edn`
      `:linters :unresolved-symbol :exclude` is exactly `[(malli.core/=>)]`
- [x] Re-confirm facility in scratch (temp project, http-kit 2.8.0 + clj-kondo
      2025.09.19 + imports-dir config `{:lint-as {org.httpkit.client/defreq clojure.core/def}}`):
      jar analysis + cache-driven lint resolves `get`/`post`; a bogus
      `http-client/definitely-not-a-var` still warns (negative control). If lint-as
      misbehaves, fall back to the `:macroexpand` hook for `org.httpkit.client/defreq`
      and record the choice in implementation.md

## Slice 1 — Registration in the http-kit import

- [x] In `.gitignore`, replace `**/.clj-kondo/imports/` with:
      ```
      **/.clj-kondo/imports/*
      !.clj-kondo/imports/http-kit/
      !.clj-kondo/imports/http-kit/**
      ```
- [x] Verify ignore semantics: `git check-ignore -v .clj-kondo/imports/http-kit/http-kit/config.edn`
      → matches the negation rule (not ignored); `git check-ignore -v .clj-kondo/imports/metosin/malli/config.edn`
      → still ignored; `git status` shows the http-kit import files as untracked
- [x] Extend `.clj-kondo/imports/http-kit/http-kit/config.edn` with
      `:lint-as {org.httpkit.client/defreq clojure.core/def}`, keeping the existing
      `:hooks {:analyze-call {org.httpkit.server/with-channel …}}` entry
- [x] Confirm `httpkit/with_channel.clj` is present under
      `.clj-kondo/imports/http-kit/http-kit/` (the config.edn hook reference requires it)

## Slice 2 — Cache rebuild

- [x] Regenerate the http-kit ns analysis cache with the registration (from repo root):
      `clojure -M:lint --lint ~/.m2/repository/http-kit/http-kit/2.8.0/http-kit-2.8.0.jar --dependencies`
      (findings suppressed by `--dependencies`; cache is written)
- [x] Confirm the cache now registers the verbs:
      `grep -o '~\$\(get\|post\|request\)' .clj-kondo/.cache/v1/clj/org.httpkit.client.transit.json`
      → contains `~$get` and `~$post`

## Slice 3 — AC1 verification

- [x] `bb lint` → zero Unresolved var warnings for the dev-http test file (covers both
      line 572 `http-client/get` and line 737 `http-client/post`), `errors: 0`,
      `warnings: 0`, and no new warnings anywhere in the repo
- [x] Negative control (proves analysis-level resolution, not suppression): temporarily
      add `(defn- bogus [] @(http-client/definitely-not-a-var))` to the test ns →
      `bb lint` flags it as unresolved; remove the probe → `bb lint` clean again
- [x] Cross-check with the dev-loop command: `clojure -M:lint` reports the same clean
      result as `bb lint`

## Slice 4 — AC2, hygiene, commit

- [x] AC2 localization: `git diff .clj-kondo/config.edn` → no changes; root
      `:unresolved-symbol :exclude` remains exactly `[(malli.core/=>)]`
- [x] `git status` shows only intended files: `.gitignore`,
      `.clj-kondo/imports/http-kit/http-kit/config.edn`,
      `.clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj`
      (nothing else, no root-config edits, no CHANGELOG entry — not user-facing per
      AGENTS.md)
- [x] Commit with symbol prefix, e.g. `⚒ 252: register http-kit defreq vars for clj-kondo (lint-as)`

## Slice 5 — Implementation-review follow-ups (2026-08-15)

- [ ] Close design-step 8's provenance-grep requirement with the cache-format
      finding: a clj-kondo 2025.09.19 `--dependencies`-built cache (rebuilt
      in-repo and fresh `--cache-dir` scratch, both verified) records only the
      internal path `org/httpkit/client.clj` — no jar path/version — so the
      mandated `grep ~:filename` for the 2.8.0 jar cannot succeed and was never
      added to slice 2. Record the adopted guard in design.md/design-steps.md
      (explicit pinned-jar rebuild command + slice-2 verb-set grep as functional
      proxy) and amend design-steps.md item 8, which is ticked [x] although its
      required steps.md amendment is absent
- [ ] Restore byte-fidelity of the tracked import dir: re-copy
      `.clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj` verbatim
      from the 2.8.0 jar's `clj-kondo.exports` (committed copy differs in
      indentation only), so plan.md decision 2's "identical, verified" holds and
      R5's "--copy-configs at pinned 2.8.0 yields no diff" is true; or record the
      drift as intentional in implementation.md
- [ ] Extend AC1's exercise-capability inventory (design-step 9) with the
      pre-commit surface: `.pre-commit-hooks/clj-kondo-lint.sh` lints individual
      staged files with the native (unpinned) clj-kondo binary, `--cache false`,
      no `--dependencies` → the http-kit jar is never analyzed there (verified:
      dev-http test file clean with and without the lint-as config), so
      pre-commit can neither exercise the fix nor regress it; add it to design.md
      AC1/Context alongside the CI note

# Implementation steps — 252-unresolved-var-lint-warning

Concrete checklist for the implementation slice. Read design.md + plan.md first.
Treat this file as the active surface; tick items as they complete, noting shas/decisions.

## Slice 0 — Baseline & facility ground truth

- [ ] Reproduce the baseline: `bb lint` from repo root → expect exactly
      `extensions/dev-http/test/extensions/dev_http_test.clj:572:5` (`http-client/get`)
      and `:737:5` (`http-client/post`), `errors: 0, warnings: 2`
- [ ] Record pre-change root config state: `.clj-kondo/config.edn`
      `:linters :unresolved-symbol :exclude` is exactly `[(malli.core/=>)]`
- [ ] Re-confirm facility in scratch (temp project, http-kit 2.8.0 + clj-kondo
      2025.09.19 + imports-dir config `{:lint-as {org.httpkit.client/defreq clojure.core/def}}`):
      jar analysis + cache-driven lint resolves `get`/`post`; a bogus
      `http-client/definitely-not-a-var` still warns (negative control). If lint-as
      misbehaves, fall back to the `:macroexpand` hook for `org.httpkit.client/defreq`
      and record the choice in implementation.md

## Slice 1 — Registration in the http-kit import

- [ ] In `.gitignore`, replace `**/.clj-kondo/imports/` with:
      ```
      **/.clj-kondo/imports/*
      !.clj-kondo/imports/http-kit/
      !.clj-kondo/imports/http-kit/**
      ```
- [ ] Verify ignore semantics: `git check-ignore -v .clj-kondo/imports/http-kit/http-kit/config.edn`
      → matches the negation rule (not ignored); `git check-ignore -v .clj-kondo/imports/metosin/malli/config.edn`
      → still ignored; `git status` shows the http-kit import files as untracked
- [ ] Extend `.clj-kondo/imports/http-kit/http-kit/config.edn` with
      `:lint-as {org.httpkit.client/defreq clojure.core/def}`, keeping the existing
      `:hooks {:analyze-call {org.httpkit.server/with-channel …}}` entry
- [ ] Confirm `httpkit/with_channel.clj` is present under
      `.clj-kondo/imports/http-kit/http-kit/` (the config.edn hook reference requires it)

## Slice 2 — Cache rebuild

- [ ] Regenerate the http-kit ns analysis cache with the registration (from repo root):
      `clojure -M:lint --lint ~/.m2/repository/http-kit/http-kit/2.8.0/http-kit-2.8.0.jar --dependencies`
      (findings suppressed by `--dependencies`; cache is written)
- [ ] Confirm the cache now registers the verbs:
      `grep -o '~\$\(get\|post\|request\)' .clj-kondo/.cache/v1/clj/org.httpkit.client.transit.json`
      → contains `~$get` and `~$post`

## Slice 3 — AC1 verification

- [ ] `bb lint` → zero Unresolved var warnings for the dev-http test file (covers both
      line 572 `http-client/get` and line 737 `http-client/post`), `errors: 0`,
      `warnings: 0`, and no new warnings anywhere in the repo
- [ ] Negative control (proves analysis-level resolution, not suppression): temporarily
      add `(defn- bogus [] @(http-client/definitely-not-a-var))` to the test ns →
      `bb lint` flags it as unresolved; remove the probe → `bb lint` clean again
- [ ] Cross-check with the dev-loop command: `clojure -M:lint` reports the same clean
      result as `bb lint`

## Slice 4 — AC2, hygiene, commit

- [ ] AC2 localization: `git diff .clj-kondo/config.edn` → no changes; root
      `:unresolved-symbol :exclude` remains exactly `[(malli.core/=>)]`
- [ ] `git status` shows only intended files: `.gitignore`,
      `.clj-kondo/imports/http-kit/http-kit/config.edn`,
      `.clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj`
      (nothing else, no root-config edits, no CHANGELOG entry — not user-facing per
      AGENTS.md)
- [ ] Commit with symbol prefix, e.g. `⚒ 252: register http-kit defreq vars for clj-kondo (lint-as)`

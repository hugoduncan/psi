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

- [x] Regenerate the http-kit ns analysis cache with the registration (from repo root).
      The rebuild is **not idempotently re-runnable**: clj-kondo's jar skip marker
      (`.clj-kondo/.cache/v1/skip/http-kit-2.8.0.jar.*`, written after any 2.8.0 jar
      analysis) makes a re-run print "http-kit-2.8.0.jar was already linted, skipping"
      and silently keep the existing ns cache — so clear the skip marker AND the ns
      transit file first:
      `rm -f .clj-kondo/.cache/v1/skip/http-kit-2.8.0.jar.* .clj-kondo/.cache/v1/clj/org.httpkit.client.transit.json`
      then
      `clojure -M:lint --lint ~/.m2/repository/http-kit/http-kit/2.8.0/http-kit-2.8.0.jar --dependencies`
      (findings suppressed by `--dependencies`; cache is written)
- [x] Confirm the cache now registers the verbs: assert the transit file exists
      (`test -f .clj-kondo/.cache/v1/clj/org.httpkit.client.transit.json` — grep on the
      absent file fails loudly, the effective guard), then
      `grep -o '~\$\(get\|post\|request\)' .clj-kondo/.cache/v1/clj/org.httpkit.client.transit.json`
      → contains `~$get` and `~$post`

## Slice 3 — AC1 verification

- [x] `bb lint` → zero Unresolved var warnings for the dev-http test file (covers both
      line 572 `http-client/get` and line 737 `http-client/post`), `errors: 0`,
      `warnings: 0`, and no new warnings anywhere in the repo
- [x] Negative control (proves analysis-level resolution, not suppression): temporarily
      add `(defn- bogus [] @(http-client/definitely-not-a-var))` to the test ns →
      `bb lint` flags it as unresolved; remove the probe → `bb lint` clean again.
      **Precondition (executes the plan-review note, explicit 2026-08-15):** must run
      AFTER a successful slice-2 rebuild — with no/absent http-kit ns cache the jar is
      never analyzed, the probe is silently unflagged (`errors: 0, warnings: 0`
      trivially), and the control proves nothing
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

- [x] Close design-step 8's provenance-grep requirement with the cache-format
      finding: a clj-kondo 2025.09.19 `--dependencies`-built cache (rebuilt
      in-repo and fresh `--cache-dir` scratch, both verified) records only the
      internal path `org/httpkit/client.clj` — no jar path/version — so the
      mandated `grep ~:filename` for the 2.8.0 jar cannot succeed and was never
      added to slice 2. Record the adopted guard in design.md/design-steps.md
      (explicit pinned-jar rebuild command + slice-2 verb-set grep as functional
      proxy) and amend design-steps.md item 8, which is ticked [x] although its
      required steps.md amendment is absent
      — done: adopted guard recorded in design.md Context (provenance bullet)
      and design-steps.md item 8 closure note; guard = pinned-jar rebuild
      command + verb-set grep (`~$get`/`~$post` present since 2026-08-15 rebuild;
      absent pre-fix)
- [x] Restore byte-fidelity of the tracked import dir: re-copy
      `.clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj` verbatim
      from the 2.8.0 jar's `clj-kondo.exports` (committed copy differs in
      indentation only), so plan.md decision 2's "identical, verified" holds and
      R5's "--copy-configs at pinned 2.8.0 yields no diff" is true; or record the
      drift as intentional in implementation.md
      — done (drift recorded as **intentional**, the item's explicit "or"
      branch): re-copied verbatim from
      `clj-kondo.exports/http-kit/http-kit/httpkit/with_channel.clj` in the
      2.8.0 jar → byte-identical at copy time (`cmp` verified); the
      `cljfmt-fix` pre-commit hook then reformatted it back to repo style
      (2-space continuation vs the jar's 3-space — indentation only), so the
      committed copy cannot be byte-identical through the repo's own commit
      path (same as the original slice-4 commit). Drift recorded as intentional
      in implementation.md; plan.md decision 2's "identical, verified" holds
      for the local pre-commit copy; R5's pinned-2.8.0 `--copy-configs`
      stability holds (same jar → same export → same indentation-only diff)
- [x] Extend AC1's exercise-capability inventory (design-step 9) with the
      pre-commit surface: `.pre-commit-hooks/clj-kondo-lint.sh` lints individual
      staged files with the native (unpinned) clj-kondo binary, `--cache false`,
      no `--dependencies` → the http-kit jar is never analyzed there (verified:
      dev-http test file clean with and without the lint-as config), so
      pre-commit can neither exercise the fix nor regress it; add it to design.md
      AC1/Context alongside the CI note
      — done: added to design.md AC1 (exercise-capability inventory) alongside
      the CI note; verified locally: pre-commit hook on the dev-http test file →
      `errors: 0, warnings: 0` with and without the `:lint-as` config

## Slice 6 — Implementation-review follow-ups (2026-08-15)

- [x] Harden the slice-2 provenance rebuild against clj-kondo's jar skip marker:
      `clojure -M:lint --lint ~/.m2/repository/http-kit/http-kit/2.8.0/http-kit-2.8.0.jar
      --dependencies` prints "http-kit-2.8.0.jar was already linted, skipping" and
      does NOT re-analyze when `.clj-kondo/.cache/v1/skip/http-kit-2.8.0.jar.*`
      exists — verified 2026-08-15: after a no-lint-as run rewrote the ns cache
      without verbs, re-running the documented rebuild with the correct config
      skipped and the wrong cache persisted (`bb lint` still showed the two
      warnings); the cache only recovered after clearing the skip marker + ns
      transit file and rebuilding. The design.md "provenance anchor" claim
      overstates the command's re-runnability. Fix: `rm -f
      .clj-kondo/.cache/v1/skip/http-kit-2.8.0.jar.*` (and the ns transit file)
      before the rebuild; assert `.clj-kondo/.cache/v1/clj/org.httpkit.client.transit.json`
      exists before the verb-set grep (grep on the absent file fails loudly —
      the effective guard); record in design.md Context that the rebuild command
      is not idempotently re-runnable without clearing the skip marker
      — done: slice-2 hardened above (rm skip+transit → rebuild → assert transit
      exists → verb-set grep), design.md Context records non-idempotency, hardened
      recipe validated end-to-end 2026-08-15: rm → rebuild → transit regenerated
      with `~$get`/`~$post`/`~$request` → `bb lint` `errors: 0, warnings: 0`
- [x] Make slice-3's negative-control precondition explicit (executes the
      plan-review note recorded in implementation.md, still unexecuted in
      steps.md): the bogus-var probe must run AFTER a successful slice-2 rebuild
      — with no/absent cache the http-kit ns is never analyzed and the probe is
      silently unflagged, proving nothing (verified 2026-08-15: deleting the ns
      cache → `bb lint` trivially `errors: 0, warnings: 0`)
      — done: slice-3 negative-control step now states the AFTER-slice-2-rebuild
      precondition explicitly

## Slice 7 — Task-test-review follow-ups (2026-08-15)

- [x] Add a committed, CI-runnable regression test guarding the registration —
      the task currently has no committed tests: AC1's lint proof, the negative
      control, and the AC2 root-config check are all manual/local/cache-dependent,
      so a removed or typo'd `:lint-as` entry and an AC2 root-config drift are
      undetectable by `bb test`/CI (the exact regressions design-step 9 option (b)
      accepted as undetectable for the lint surface). Read the two config.edn
      files as EDN (no jar analysis, no cache — runs anywhere) and assert:
      - `.clj-kondo/imports/http-kit/http-kit/config.edn` retains
        `:lint-as {org.httpkit.client/defreq clojure.core/def}`
      - root `.clj-kondo/config.edn` `:linters :unresolved-symbol :exclude`
        remains exactly `[(malli.core/=>)]` (AC2 invariant, currently only a
        manual `git diff` gate in slice 4)
      Placement is a decision (no component owns lint config): an existing
      tests.edn component test dir or a `spec/`-adjacent test; keep the assertion
      code out of the import dir itself (AC2 confinement)
      — done (placement decision: `components/shared-config` — no component owns
      lint config; shared-config is the closest semantic home and its test dir is
      already in the `:unit` suite, so no tests.edn change; assertion code lives
      in `components/shared-config/test/psi/shared_config/lint_config_test.clj`,
      outside the import dir — AC2 confinement holds): `http-kit-import-registration-test`
      + `root-config-ac2-invariant-test` read both config.edn files as EDN and
      assert the lint-as registration and the exact `[(malli.core/=>)]` exclude;
      verified `bb test --focus psi.shared-config.lint-config-test` → 2 tests /
      3 assertions pass; `bb lint` still errors: 0, warnings: 0
- [x] Decide and record whether to commit the analysis-level proof (negative
      control) as a test: a test that runs
      `clojure -M:lint --lint ~/.m2/repository/http-kit/http-kit/2.8.0/http-kit-2.8.0.jar
      --dependencies --cache-dir <tmp>` then lints a probe ns (get/post resolve,
      bogus var still warns) makes the slice-3 negative control CI-runnable and
      enforces "analysis-level, not suppression" — realizing design-step 9's
      option (a) via a test rather than a CI workflow change (the (a)/(b) decision
      framed the gap only as lint-surface exercise and never considered a test
      vehicle). Depends on the 2.8.0 jar at the standard m2 path (present in CI
      per design-step 9's m2-cache fact; skip when absent) and seconds of jar
      analysis — mark `^:integration` or accept the runtime if filed. Accept or
      decline explicitly; do not leave the proof as an uncommitted manual probe
      only
      — done (decision: ACCEPT, as `^:integration`): committed
      `http-kit-defreq-analysis-level-resolution-test` — runs the pinned JVM
      clj-kondo 2025.09.19 via `clojure -Sdeps` (same analyzer as the lint gate)
      twice: jar `--dependencies --cache-dir <tmp>` populates a hermetic temp
      cache, then a probe ns lint against that cache resolves get/post and flags
      `definitely-not-a-var`; skips (passing) when the 2.8.0 jar is absent from
      m2; `^:integration` meta keeps it out of `bb test` (unit/extensions
      `:skip-meta [:integration]`) and runs in `bb clojure:test:integration` (CI
      runs both); verified integration run → test passes (hermetic, never touches
      repo `.clj-kondo/.cache`)

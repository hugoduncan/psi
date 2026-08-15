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

## Slice 8 — Task-test-review follow-ups (2026-08-15)

- [x] Strengthen `http-kit-import-registration-test`'s hook assertion: the
      server-side with-channel preservation check is only
      `(is (contains? (:hooks cfg) :analyze-call))` — a removed or changed
      `:analyze-call` mapping passes. The design's mechanism explicitly requires
      the existing server-side hook to be kept alongside the lint-as entry.
      Assert exact match:
      `(= {:analyze-call {org.httpkit.server/with-channel httpkit.with-channel/with-channel}} (:hooks cfg))`
      (current config.edn matches this shape exactly)
      — done: hook assertion is now an exact match on `(:hooks cfg)`
      (quoted map literal — unquoted would ClassNotFound on the class-resolving
      compiler), verified by the unit suite (6 assertions pass)
- [x] Extend the AC2 guard to root `:lint-as`: `root-config-ac2-invariant-test`
      asserts only `:unresolved-symbol :exclude` stays exactly
      `[(malli.core/=>)]` — plan.md decision 1's no-root-mirror choice (unlike
      the malli/promesa convention the root config's own comment documents)
      would be silently violated if `org.httpkit.client/defreq` were added to
      root `:lint-as`; that drift is undetectable today. Assert
      `org.httpkit.client/defreq` ∉ (keys (:lint-as root-config))
      — done: `root-config-ac2-invariant-test` now asserts
      `(not (contains? (:lint-as cfg) 'org.httpkit.client/defreq))` alongside
      the exclude invariant
- [x] Harden the `clj-kondo-main` subprocess (skill infra-dep criterion —
      injectable/nullable, no hang): (a) the `clojure` CLI binary is neither
      injectable nor nullable — a missing binary errors the `^:integration`
      test, unlike the jar-absent path which skips; skip (passing) when
      `clojure` is not on PATH (e.g. `shell/sh "which" "clojure"`), mirroring
      the jar check; (b) `clojure.java.shell/sh` has NO `:timeout` support
      (verified in the 1.12 source — unknown opts silently ignored), so a hung
      subprocess (e.g. cold `-Sdeps` dep download stall) blocks the suite
      indefinitely — switch to a timeout-capable runner (e.g.
      `clojure.java.process` or `Process/waitFor` with a timeout) or record the
      accepted hang risk explicitly
      — done (both): (a) `clojure-bin` (via `shell/sh "which" "clojure"`) is
      checked first in the skip guard — `clojure` absent on PATH skips passing,
      mirroring the jar-absent skip; (b) `clj-kondo-main` now uses
      ProcessBuilder + `waitFor(120s)` with concurrent stream draining
      (non-daemon future threads terminated via destroyForcibly + stream drain
      in a finally); a hung subprocess kills the process and throws loudly
      instead of blocking the suite
- [x] Guard clj-kondo version-pin drift: `clj-kondo-deps` hardcodes
      "2025.09.19" while deps.edn `:lint` `:extra-deps` pins it; on a clj-kondo
      bump the integration test silently keeps proving the OLD analyzer (R4's
      manual re-verification gives no failure signal). Assert the test's pinned
      version equals deps.edn's `:lint :extra-deps` clj-kondo version (or
      derive it), so the drift fails loudly instead of re-proving a stale pin
      — done (derived): `clj-kondo-version` is read from deps.edn
      `[:aliases :lint :extra-deps 'clj-kondo/clj-kondo :mvn/version]` (the
      aliases live under `:aliases` at the top level) and `clj-kondo-deps` is
      formatted from it — no separate hardcoded version; `clj-kondo-pin-sourced-from-deps-edn-test`
      asserts the pin exists, so a removed/bumped entry fails loudly in `:unit`

## Slice 9 — Task-test-review follow-ups (2026-08-15)

- [x] Derive the http-kit jar path from deps.edn (mirror of the slice-8 clj-kondo
      pin derivation, same silent-stale-pin failure mode left open for http-kit):
      `http-kit-jar` hardcodes the 2.8.0 path
      (`~/.m2/repository/http-kit/http-kit/2.8.0/http-kit-2.8.0.jar`), so R4's
      http-kit bump re-verification gets no failure signal — on a bump the 2.8.0
      jar either stays in m2 (the proof silently re-proves the OLD jar) or is
      absent (the proof silently skips). Read
      `[:deps 'http-kit/http-kit :mvn/version]` from root deps.edn (the pin
      source; currently 2.8.0) and format `http-kit-jar` from it; add a unit
      assertion (like `clj-kondo-pin-sourced-from-deps-edn-test`) that the pin
      exists, so a removed/bumped entry fails loudly in `:unit`
      — done: `http-kit-version` derived from deps.edn `[:deps
      'http-kit/http-kit :mvn/version]`, `http-kit-jar` formatted from it
      (verified: nested-CWD load resolves version 2.8.0 and the standard m2
      path); `http-kit-pin-sourced-from-deps-edn-test` added — 4 unit tests /
      9 assertions pass
- [x] Widen the AC2 root-config guard to AC2's general clause ("root
      `.clj-kondo/config.edn` gains no http-client entries"): today
      `root-config-ac2-invariant-test` asserts only `:unresolved-symbol :exclude`
      exactness and `org.httpkit.client/defreq` ∉ root `:lint-as` — so e.g.
      `org.httpkit.client/get` added to root `:lint-as`, or a root
      `:hooks :analyze-call` entry for an http-kit var, passes while violating
      AC2. Assert no `org.httpkit.client`-prefixed symbol occurs anywhere in the
      parsed root config (walk the EDN for symbols whose namespace is
      `org.httpkit.client`, covering `:lint-as`, `:hooks`, `:namespaces`, …), or
      scope the guard explicitly to AC2's exact wording and record why the
      narrower guard is the intended invariant
      — done (wider branch — the EDN walk): `http-client-entries` returns every
      symbol/keyword in the parsed root config whose namespace is
      `org.httpkit.client`; `root-config-ac2-invariant-test` asserts it is
      empty, covering `:lint-as`, `:hooks`, `:namespaces`, and any other
      symbol/keyword-bearing spot
- [x] Make the `^:integration` skip visible: `(is (str "skipped: " reason))` is
      always-truthy — clojure.test prints nothing for a passing truthy `is`, so a
      skip (jar absent, or clojure not on PATH) is indistinguishable from a real
      pass and the m2-cache-dependent CI guarantee vanishes with zero signal.
      Print the reason to *out* (e.g. `(println "SKIP task-252 analysis-level
      proof:" reason)`) before the truthy assert so runner output records why the
      proof did not run
      — done: `(println "SKIP task-252 analysis-level proof:" reason)` precedes
      the truthy assert; integration run (jar present + clojure on PATH)
      produces no SKIP line, confirming the proof ran
- [x] Make `repo-root` injectable/nullable (skill infra-dep criterion): it is
      `(.getCanonicalPath (io/file "."))` — an ambient CWD dependency, neither
      injectable nor nullable; a run from a non-root CWD (editor/nrepl runner,
      component dir) fails with confusing file-not-found on the config reads.
      Derive repo root by walking up from the test file's own source path (or
      from `user.dir`) until `deps.edn` is found, or make it overridable via a
      system property, failing with a clear message when no root is found
      — done (walk up + property override): `find-repo-root` walks up from
      user.dir until a dir containing BOTH `deps.edn` and `bb.edn` (components/
      extensions carry their own deps.edn, so plain deps.edn presence stops at
      the component — verified failing case; bb.edn lives only at the repo
      root); overridable via `psi.lint-config-test.repo-root`; clear ex-info
      when no root found. Verified: nested CWD (`components/shared-config`)
      resolves the repo root + 2.8.0 jar; property override from /tmp resolves
      the root

## Slice 10 — Task-test-review follow-ups (2026-08-15)

- [x] Cover AC1's literal acceptance surface (the ACTUAL dev-http test file, lines 572/737) in the `^:integration` proof — today `http-kit-defreq-analysis-level-resolution-test` proves the mechanism on a synthetic probe only; a regression in the real file (an added `http-client/delete` call, a changed alias, a removed require) is undetectable by any test. Validated recipe (hermetic, CI-runnable, /tmp, 2026-08-15): after the existing jar `--dependencies --cache-dir <tmp>` step, lint `extensions/dev-http/test/extensions/dev_http_test.clj` against the same cache and assert the output contains neither `Unresolved var: http-client/get` nor `Unresolved var: http-client/post` (verified: clean with the registration). A discriminating control is required — an empty cache is trivially clean (design-step 9) and `--config '{:lint-as {}}'` does NOT disable the imports-dir config (auto-merges regardless; verified — verbs still registered), so build a second hermetic cache from the same jar with `--config-dir <empty-tmp-dir>` (clj-kondo 2025.09.19 NPEs `config_dir is null` without it) and assert the real-file lint against THAT cache DOES report the two warnings at 572/737 (`errors: 0, warnings: 2`; the no-reg transit carries `~$request` but not `~$get`/`~$post` — the slice-2 verb-set proxy, which may substitute for the second arm)
      — done: `http-kit-defreq-analysis-level-resolution-test` extended with both arms — (1) real-file lint against the registration cache: exit 0, no get/post unresolved; (2) no-reg cache via `--config-dir <empty-tmp-dir>` (mkdir'd empty dir; NPE avoided): transit carries `~$request` but not `~$get`/`~$post` (verb-set proxy), real-file lint against it reports both warnings + `errors: 0, warnings: 2` (exact baseline shape). Verified: focused integration run → 1 test / 17 assertions pass; unit suite skips it (`^:integration`)

- [x] Guard the `.gitignore` negation that keeps the http-kit import dir TRACKED (plan.md decision 2 / slice 1 / slice-4 change set) — nothing tests it: `http-kit-import-registration-test` reads config.edn from disk, so if the negation lines are removed (restoring `**/.clj-kondo/imports/`) the file still exists locally, the unit test passes, and the registration silently drops out of future commits. Add a unit assertion (same `lint_config_test.clj` ns; read `.gitignore` as text — no subprocess, runs anywhere) that `.gitignore` contains the negation set `**/.clj-kondo/imports/*`, `!.clj-kondo/imports/http-kit/`, `!.clj-kondo/imports/http-kit/**` (verified present, lines 4-6, 2026-08-15)
      — done: `gitignore-http-kit-import-tracking-test` added — splits `.gitignore` into lines and asserts each of the three negation lines is present verbatim (no subprocess; runs anywhere); unit suite → 5 tests / 12 assertions pass

## Slice 11 — Task-test-review follow-ups (2026-08-15)

- [x] Guard the with-channel hook implementation file — the committed change set
      (slice-4 list) and the design mechanism ("keep the existing server-side
      hook") include `.clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj`,
      and `http-kit-import-registration-test` asserts config.edn's `:hooks
      :analyze-call` reference exactly, but NO test asserts the impl file exists
      or that its ns/var match the config reference. The repo has zero
      `with-channel` call sites (rg: only lint_config_test.clj and the
      .clj-kondo config mention the symbol), so neither `bb lint` nor the
      `^:integration` proof (jar arm never fires analyze-call — no calls
      analyzed; probe/real-file have no with-channel calls) ever loads the
      `httpkit.with-channel` namespace — a deleted or renamed hook impl is
      undetectable by `bb test`/CI. Add a unit assertion (same
      `lint_config_test.clj` ns) that the file exists and its `(ns
      httpkit.with-channel)` + `(defn with-channel …)` match the config.edn
      `:hooks :analyze-call` reference (read as text/EDN — no subprocess, runs
      anywhere; do not put assertion code in the import dir itself — AC2)
      — done: `with-channel-hook-impl-guard-test` — asserts the config.edn
      `:analyze-call` value is exactly `httpkit.with-channel/with-channel`,
      the impl file exists, and the file's parsed forms contain `(ns
      httpkit.with-channel …)` and `(defn with-channel …)` (read-string of the
      impl wrapped in a vector — no subprocess); unit suite → 6 tests / 18
      assertions pass
- [x] Guard the `extensions/dev-http/deps.edn` http-kit pin: design.md Context
      cites "http-kit 2.8.0 (root `deps.edn` + `extensions/dev-http/deps.edn`)"
      as the R4 re-verification set, but `http-kit-pin-sourced-from-deps-edn-test`
      derives the version from ROOT deps.edn only — a drift in the extension pin
      (the classpath the dev-http extension actually runs against, e.g. a bump
      to 2.9.0 while root stays 2.8.0) yields zero signal from any test. Extend
      the pin test to read `extensions/dev-http/deps.edn` and assert its
      `http-kit/http-kit :mvn/version` equals the root-derived `http-kit-version`
      — done: `http-kit-pin-sourced-from-deps-edn-test` gained a second
      testing block — reads `extensions/dev-http/deps.edn` `[:deps
      'http-kit/http-kit]`, asserts the pin exists and equals the
      root-derived `http-kit-version` (2.8.0)
- [x] Make the clojure CLI infra dep injectable (slice-8 residue — skill
      infra-dep criterion `injectable(d) ∧ nullable(d)`): slice-8 made the
      subprocess NULLABLE (skip when `clojure` absent via the `clojure-bin`
      `which` guard) but `clj-kondo-main` still hardcodes `"clojure"` in the
      ProcessBuilder vector, so (a) the binary is not injectable/overridable
      (unlike repo-root, which gained a property override) and (b) the guard and
      the executed binary can disagree — `clojure-bin` is resolved at ns-load
      via `which`, the exec resolves `"clojure"` from PATH at run time, so a
      PATH mutation between load and run (or a shell function shadowing) makes
      the guard prove a binary other than the one executed. Use the derived
      `clojure-bin` in the ProcessBuilder command vector (nil-safe given the
      skip guard), or mirror the `psi.lint-config-test.repo-root` property
      override pattern for the binary
      — done (both branches): `clojure-bin` is now overridable via the
      `psi.lint-config-test.clojure-bin` system property (injectable, mirror of
      the repo-root override) and otherwise derived from PATH via `which`
      (nullable); `clj-kondo-main`'s ProcessBuilder vector uses the SAME
      resolved `clojure-bin` value that feeds the skip guard, so the guard can
      never prove a binary other than the one executed; a defensive nil guard
      throws a clear ex-info instead of a ProcessBuilder NPE (verified via
      alter-var-root → clear ex-info; `ns-unmap`+`intern` creates a NEW var so
      the compiled fn still sees the old value — the alter-var-root check is
      the valid one). Verified: default `which`-derived
      `/opt/homebrew/bin/clojure`; property override `/nonexistent/clojure`
      observed at ns-load; override with the real binary → integration suite
      31 tests / 168 assertions pass (no SKIP line — proof ran)

## Slice 12 — Task-test-review follow-ups (2026-08-15)

- [ ] Make the http-kit jar path injectable (skill infra-dep criterion — `injectable(d) ∧ nullable(d)`): `http-kit-jar` is derived from `user.home` + `http-kit-version` with no override, unlike `repo-root` (`psi.lint-config-test.repo-root`) and `clojure-bin` (`psi.lint-config-test.clojure-bin`), which both gained property overrides — a non-standard m2 repo (e.g. `-Dmaven.repo.local`, a CI with a different home layout) makes the `^:integration` proof silently skip (jar "absent" at the derived path) with no way to point the test at the real jar. Add a `psi.lint-config-test.http-kit-jar` property override (or derive from an m2-repo property), mirroring the clojure-bin pattern; keep the skip guard (nullable) unchanged.
- [ ] Assert the .gitignore negation ORDER, not just presence: `gitignore-http-kit-import-tracking-test` verifies the three lines exist verbatim, but gitignore is last-match-wins — if the ignore-all `**/.clj-kondo/imports/*` were moved BELOW the negation lines, git would re-ignore the http-kit import dir (the registration silently drops out of future commits) while all three lines still exist and the test passes. Assert the ignore-all pattern's line index precedes both negation lines (line reads only — no subprocess, runs anywhere).
- [ ] Clean up the integration test's temp dir: `http-kit-defreq-analysis-level-resolution-test` creates `tmp` (createTempFile → delete → mkdirs) holding `cache-dir`, `no-reg-dir`, `empty-config`, and `probe.clj`, but never removes it — every local and CI integration run leaks a cache directory under /tmp. Delete the tree in a `finally` (recursive delete — `.deleteOnExit` only removes empty dirs), or at minimum delete the probe file and record the accepted leak.

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

- [x] Cover AC1's literal acceptance surface (the ACTUAL dev-http test file, lines 572/737) in the `^:integration` proof — today `http-kit-defreq-analysis-level-resolution-test` proves the mechanism on a synthetic probe only; a regression in the real file (a changed alias, a removed require, a call to a var OUTSIDE the registered defreq verb set — NOT an added `http-client/delete` call, which is itself registered and resolves; see the slice-13 correction) is undetectable by any test. Validated recipe (hermetic, CI-runnable, /tmp, 2026-08-15): after the existing jar `--dependencies --cache-dir <tmp>` step, lint `extensions/dev-http/test/extensions/dev_http_test.clj` against the same cache and assert the output contains neither `Unresolved var: http-client/get` nor `Unresolved var: http-client/post` (verified: clean with the registration). A discriminating control is required — an empty cache is trivially clean (design-step 9) and `--config '{:lint-as {}}'` does NOT disable the imports-dir config (auto-merges regardless; verified — verbs still registered), so build a second hermetic cache from the same jar with `--config-dir <empty-tmp-dir>` (clj-kondo 2025.09.19 NPEs `config_dir is null` without it) and assert the real-file lint against THAT cache DOES report the two warnings at 572/737 (`errors: 0, warnings: 2`; the no-reg transit carries `~$request` but not `~$get`/`~$post` — the slice-2 verb-set proxy, which may substitute for the second arm)
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

- [x] Make the http-kit jar path injectable (skill infra-dep criterion — `injectable(d) ∧ nullable(d)`): `http-kit-jar` is derived from `user.home` + `http-kit-version` with no override, unlike `repo-root` (`psi.lint-config-test.repo-root`) and `clojure-bin` (`psi.lint-config-test.clojure-bin`), which both gained property overrides — a non-standard m2 repo (e.g. `-Dmaven.repo.local`, a CI with a different home layout) makes the `^:integration` proof silently skip (jar "absent" at the derived path) with no way to point the test at the real jar. Add a `psi.lint-config-test.http-kit-jar` property override (or derive from an m2-repo property), mirroring the clojure-bin pattern; keep the skip guard (nullable) unchanged.
      — done: `http-kit-jar` is now `(or (not-empty (System/getProperty "psi.lint-config-test.http-kit-jar")) (str user.home "/.m2/repository/http-kit/http-kit/" http-kit-version "/http-kit-" http-kit-version ".jar"))` — property override (injectable, mirror of the repo-root/clojure-bin props) + derived default (nullable, skip guard unchanged). Verified: override to a nonexistent path → proof skips (1 assertion, skip reason = overridden path); override to the real 2.8.0 jar → full 17-assertion proof runs, no SKIP; default derivation unchanged (integration suite 31 tests / 168 assertions, no SKIP)
- [x] Assert the .gitignore negation ORDER, not just presence: `gitignore-http-kit-import-tracking-test` verifies the three lines exist verbatim, but gitignore is last-match-wins — if the ignore-all `**/.clj-kondo/imports/*` were moved BELOW the negation lines, git would re-ignore the http-kit import dir (the registration silently drops out of future commits) while all three lines still exist and the test passes. Assert the ignore-all pattern's line index precedes both negation lines (line reads only — no subprocess, runs anywhere).
      — done: `gitignore-http-kit-import-tracking-test` now also asserts, per negation line, that the ignore-all line index (line 4) precedes it (lines 5-6) — `(is (< ignore-idx neg-idx))` for both `!.clj-kondo/imports/http-kit/` and `!.clj-kondo/imports/http-kit/**`, with clear line-number messages; presence doseq retained. Unit suite → 6 tests / 23 assertions pass (was 18)
- [x] Clean up the integration test's temp dir: `http-kit-defreq-analysis-level-resolution-test` creates `tmp` (createTempFile → delete → mkdirs) holding `cache-dir`, `no-reg-dir`, `empty-config`, and `probe.clj`, but never removes it — every local and CI integration run leaks a cache directory under /tmp. Delete the tree in a `finally` (recursive delete — `.deleteOnExit` only removes empty dirs), or at minimum delete the probe file and record the accepted leak.
      — done (recursive delete): added `delete-recursively!` (`.exists`/`.isDirectory`/`.listFiles` walk + `.delete`) and wrapped the proof body in `try … (finally (delete-recursively! tmp))` — the hermetic cache tree (`cache-dir`, `no-reg-dir`, `empty-config`, `probe.clj`) is removed after every run, passing or failing. Verified: integration run creates no `/tmp/ck252*` dir (9 pre-existing leaked dirs from prior runs untouched — they predate the fix)

## Slice 13 — Task-test-review follow-ups (2026-08-15)

- [x] Guard AC1's acceptance-surface wiring: no test asserts the `:lint` alias still lints `extensions` — `http-kit-defreq-analysis-level-resolution-test` invokes `clj-kondo.main` directly with explicit `--lint` args (bypassing deps.edn `[:aliases :lint :main-opts]`), and `bb.edn` `lint` is a trivial `clojure -M:lint` wrapper, so a narrowing change that drops `extensions` (or renames the dev-http path) from the alias's path set silently removes the two warnings from the acceptance surface while every test still passes. Add a unit test (same `lint_config_test.clj` ns; read deps.edn as EDN — runs anywhere, no subprocess) asserting `[:aliases :lint :main-opts]` contains `"extensions"` (and `"bb.edn"`), mirroring the pin-derivation tests
      — done: `lint-alias-lints-extensions-test` added — reads deps.edn `[:aliases :lint :main-opts]` as EDN (no subprocess, runs anywhere), asserts it contains both `"extensions"` (the dev-http test file lives under it) and `"bb.edn"`, mirroring the pin-derivation tests; a narrowing change that drops `extensions` from the alias's path set now fails loudly in `:unit`. Unit suite → 7 tests / 26 assertions pass
- [x] Add a `git check-ignore` ground-truth guard for the tracking negation: `gitignore-http-kit-import-tracking-test` proves the three lines exist verbatim in the right order but never runs git, so git interpreting the patterns differently (a shadowing re-ignore elsewhere, a pattern-semantics drift, a typo git reads differently than the text test) passes while the import dir silently drops out of commits. Git is available in CI; add a `^:integration` assertion (or extend the existing proof) running `git check-ignore` on `.clj-kondo/imports/http-kit/http-kit/config.edn` (must NOT be ignored — exit 1) and the sibling `.clj-kondo/imports/metosin/malli/config.edn` (must be ignored — exit 0), matching slice-1's manual verification (verified locally 2026-08-15: http-kit exit 1 / not ignored; malli exit 0, matched by `.gitignore:4`)
      — done: `^:integration` `gitignore-http-kit-tracking-ground-truth-test` added — runs `git check-ignore -v` from the repo root (via `shell/sh` `:dir`; git-absent → visible SKIP, mirroring the clojure-bin nullable pattern): http-kit config.edn exits non-zero (NOT ignored — the negation works), malli sibling exits zero and its `-v` output carries `.gitignore:` (the ignore-all rule still applies); verifies git's own interpretation matches the text test, so a pattern-semantics drift or shadowing re-ignore fails loudly. Verified: integration run → both assertions pass (no SKIP)
- [x] Correct the overstated real-file-arm claim in `http-kit-defreq-analysis-level-resolution-test`'s docstring ("an added http-client/delete call … fails here") and the identical slice-10 steps.md note: `delete` is part of the defreq-registered full verb set (`:lint-as org.httpkit.client/defreq clojure.core/def` registers get/delete/head/post/put/options/patch/propfind/proppatch/lock/unlock/report/acl/copy/move; verified: local cache transit carries `~$delete` alongside `~$get`/`~$post`/`~$request`), so an added `http-client/delete` call RESOLVES and the arm does NOT fail — the arm actually guards require/alias changes and calls to vars OUTSIDE the registered set (e.g. `definitely-not-a-var`). Restate the claim so future readers don't trust a false safety property
      — done: the real-file testing string now states the arm guards require/alias changes and calls to vars OUTSIDE the registered defreq set (e.g. `definitely-not-a-var`), and explicitly notes an added `http-client/delete` call does NOT fail here (delete is registered; `~$delete` present in the local cache transit alongside `~$get`/`~$post`/`~$request`); the identical slice-10 steps.md note was corrected in place (same restated claim)

## Slice 14 — Task-test-review follow-ups (2026-08-15)

- [x] Make the git binary infra dep injectable + guard/exec-agreed (skill
      infra-dep criterion — `injectable(d) ∧ nullable(d)`; mirror of the
      slice-11 clojure-bin fix): `^:integration
      gitignore-http-kit-tracking-ground-truth-test` skips when `which git`
      fails (nullable ✓) but has no `psi.lint-config-test.git-bin` property
      override (injectable ✗ — clojure-bin/repo-root/http-kit-jar all gained
      overrides), and the exec re-resolves the literal `"git"` from PATH at run
      time while the guard resolved `which git` at test time — a PATH mutation
      or shell-function shadowing between guard and exec makes the guard prove
      a binary other than the one executed (the exact disagreement slice 11
      eliminated for clojure-bin). Derive `git-bin` once (property override +
      `which`), use the SAME value in the skip guard and the `check-ignore`
      invocation; optionally bound the subprocess (shell/sh has no :timeout,
      the same gap slice 8 closed for clj-kondo-main)
      — done: `git-bin` derived once (`psi.lint-config-test.git-bin` property
      override, mirror of the clojure-bin override; else `which git`); the same
      resolved `git-bin` feeds the skip guard and the `check-ignore` invocation;
      the subprocess is bounded via a shared `run-bounded` helper (ProcessBuilder
      + waitFor(120s) — refactored out of clj-kondo-main, so clj-kondo and git
      both use the timeout-capable runner; shell/sh has no :timeout). Verified:
      default `which`-derived git → ground-truth test passes (no SKIP);
      `psi.lint-config-test.git-bin` override with the real binary → 171
      assertions, both proofs ran
- [x] Make the pinned clj-kondo jar infra dep nullable + injectable (skill
      infra-dep criterion — `injectable(d) ∧ nullable(d)`; mirror of the
      slice-9/12 http-kit jar pattern): `http-kit-defreq-analysis-level-resolution-test`
      skips when `clojure` is off PATH or the http-kit jar is absent, but the
      clj-kondo jar resolved via `-Sdeps` (`clj-kondo-deps`, version derived
      from deps.edn) is never checked — if `clj-kondo-<version>.jar` is absent
      from m2 (fresh machine, offline run, non-standard `:mvn/local-repo`),
      the subprocess attempts a network download and fails loudly (or hangs up
      to the 120s timeout) instead of a visible SKIP, and no property points
      the proof at an alternative jar/coordinates. Derive the jar path from
      `clj-kondo-version` + user.home (mirror of `http-kit-jar`), add a
      `psi.lint-config-test.clj-kondo-jar` property override, and add an
      existence check to the skip guard (visible SKIP, mirroring the http-kit
      jar arm)
      — done: `clj-kondo-jar` derived from user.home + `clj-kondo-version`
      (mirror of `http-kit-jar`), overridable via
      `psi.lint-config-test.clj-kondo-jar`, and an existence check added to the
      proof's skip guard (visible SKIP, mirroring the http-kit jar arm).
      Verified: default derivation resolves
      `~/.m2/repository/clj-kondo/clj-kondo/2025.09.19/clj-kondo-2025.09.19.jar`
      (present); override to a nonexistent path → proof skips (integration
      suite 171 → 155 assertions — the 17-assertion proof collapsed to the
      1-assertion skip); no override → 171 assertions, no SKIP
- [x] Guard the bb.edn `lint` task wrapper (AC1's local proof surface): AC1
      verification is `bb lint` ≡ `clojure -M:lint`, and
      `lint-alias-lints-extensions-test` guards only deps.edn's `:lint
      :main-opts` — nothing guards bb.edn's `lint` task (`:task (shell
      "clojure -M:lint")`, bb.edn:242-244). If the wrapper drifts — e.g. adds
      `--cache false` (with no cache the two warnings vanish — exactly
      design-step 9's masking), adds `--config` overrides, or switches to the
      native clj-kondo binary — the local AC1 gate becomes trivially clean
      while every test still passes. Add a unit test (same `lint_config_test.clj`
      ns; read bb.edn as EDN — no subprocess, runs anywhere) asserting the
      `lint` task's shell command invokes `clojure -M:lint` without
      `--cache false`/`--config` overrides
      — done: `bb-edn-lint-task-wrapper-test` added — reads bb.edn as EDN and
      asserts the `lint` task (keyed by the symbol `lint`, bb.edn task names
      are symbols) is EXACTLY `(shell "clojure -M:lint")`, so any drift (cache
      disabling, config override, native-binary switch) fails loudly in `:unit`
- [x] Guard the tests.edn suite wiring that makes the `^:integration` tests
      RUN (the entire CI-detectable regression surface for this task): the two
      proofs (analysis-level + git ground truth) execute only because
      tests.edn's `:integration` suite lists `components/shared-config/test`
      with `:focus-meta [:integration]`, and the 7 unit invariants run only
      because the `:unit` suite lists it too — nothing tests tests.edn, so
      dropping the path (or changing `:focus-meta`/`:skip-meta`) silently
      disables every guard with zero signal, the same silent-drift class the
      task already guards for `.gitignore`/lint-alias/pins. Add a unit test
      reading tests.edn as EDN asserting `components/shared-config/test` ∈
      `:unit` `:test-paths` ∧ `:integration` `:test-paths`, and `:integration`
      `:focus-meta` retains `[:integration]`
      — done: `tests-edn-suite-wiring-test` added — reads tests.edn as EDN via
      the `#kaocha/v1` tag reader (extended `read-edn` with opts), asserts
      `components/shared-config/test` ∈ `:unit` `:test-paths` ∧ `:integration`
      `:test-paths`, `:integration` `:focus-meta` = `[:integration]`, and
      `:unit` `:skip-meta` = `[:integration]` (named in the follow-up's
      rationale — the ^:integration proofs stay out of `bb test`)

## Slice 15 — Task-test-review follow-ups (2026-08-15)

- [x] Make the `^:integration` skip visible through kaocha's output capture:
      slice-9's "make the skip visible" mechanism is DEFEATED — tests.edn sets
      `:capture-output? true`, and kaocha 1.91.1392's capture-output plugin
      (kaocha/plugin/capture_output.cljc: init-capture rebinds System/out+err to
      a per-test buffer; kaocha/report.clj shows the buffer only in the FAILURE
      report) swallows the `(println "SKIP task-252 …")` lines on the passing
      (skipped) test. Verified 2026-08-15: forced-skip run
      (`-J-Dpsi.lint-config-test.http-kit-jar=/nonexistent/http-kit.jar` +
      `--focus integration --focus psi.shared-config.lint-config-test`) →
      proof collapsed 171→155 assertions with ZERO signal — grep for "SKIP"
      finds nothing, so a jar/clojure/git-absent skip is indistinguishable from
      a real pass in runner output (the exact gap slice 9 set out to close; the
      scry/bb.kaocha-runner path prints kaocha's process stdout, but the
      println never reaches it under capture). Fix: set `:capture-output? false`
      at tests.edn TOP level — kaocha 1.91.1392 honors it top-level only
      (kaocha/config.clj normalize destructures `capture-output?` from the
      root config; per-suite `:capture-output?` is dropped, verified in the jar
      source), or pass `--no-capture-output` in the bb.edn suite tasks. Verify:
      (a) forced-skip integration run now prints
      `SKIP task-252 analysis-level proof: …` and `SKIP task-252 git
      check-ignore ground truth: …` (verified with the `--no-capture-output`
      CLI flag: the SKIP line appears mid-dots); (b) `bb test` (unit) and
      `bb clojure:test:integration` still pass and their output volume is
      acceptable with capture off; (c) scry's structured runner still records
      results. Then guard the invariant: extend `tests-edn-suite-wiring-test`
      (or add a unit test) asserting the chosen capture setting
      (`:capture-output? false` at top level, or the task-level flag), so the
      visible-skip invariant itself is guarded like the other tests.edn wiring
      — done (tests.edn top-level `:capture-output? false` chosen — single
      source of truth, honored by every kaocha invocation incl. scry's
      in-process runner via config/load-config; comment added in tests.edn).
      TWO additional discoveries required for (a) to actually hold on the
      primary runner path: (1) scry's in-process kaocha adapter binds *out*/*
      err* to a discarding writer around api/run (scry/kaocha.clj), so a plain
      `(println …)` NEVER reaches runner output even with capture off — the
      two skip sites now write the reason to System/out directly via a shared
      `report-skip!` helper (untouched while capture is off; reaches the
      runner's captured process stdout on both the scry and fallback paths);
      (2) the git ground-truth skip guard gained a nonexistent-binary arm
      (mirror of the http-kit/clj-kondo jar arms) so a stale override/which
      result is a visible SKIP, not a loud subprocess error. Verified: (a)
      forced-skip scry-path run prints `SKIP task-252 analysis-level proof:
      /nonexistent/http-kit.jar not present` (JAVA_OPTS property override —
      `-J-D` after `-m` lands in kaocha argv, not the JVM) and `SKIP task-252
      git check-ignore ground truth: /nonexistent/git not present`; (b) full
      `bb clojure:test:integration` 32 tests / 171 assertions exit 0 (no SKIP
      — both proofs ran), unit suite 2693 passed / 1 failed — pre-existing
      environmental `workflow-delegate-review-step-live-test` (unknown model
      deepseek/deepseek-v4-flash; fails identically with capture on), output
      volume acceptable (unit 56 lines, integration 2 lines, extensions 5
      lines); (c) scry still records .scry-results EDN on failure (verified
      via the unit failure). Guard: `tests-edn-suite-wiring-test` now asserts
      top-level `:capture-output?` is false (root config only — suites carry
      no capture setting). `bb lint` errors: 0 warnings: 0; `bb fmt:check`
      clean; unit suite 9 tests / 39 assertions pass
- [x] Guard the deps.edn `:lint` alias `:main-opts` against cache-disabling /
      config-override flags: `lint-alias-lints-extensions-test` (slice 13)
      asserts only path presence ("extensions", "bb.edn") — adding `--cache
      false` (the exact design-step-9 masking class: with no cache the two
      warnings vanish), `--config`/`--config-dir` overrides, or
      `--dependencies` to the alias's `:main-opts` silently makes AC1
      trivially clean while every test passes. `bb-edn-lint-task-wrapper-test`
      (slice 14) closes this for the bb.edn WRAPPER only (`(shell "clojure
      -M:lint")` exact), not for the alias itself. Fix: extend
      `lint-alias-lints-extensions-test` (or add a test, same ns — read
      deps.edn as EDN, no subprocess, runs anywhere) asserting `:main-opts`
      contains NONE of `"--cache"`, `"--config"`, `"--config-dir"`,
      `"--dependencies"` (verified 2026-08-15: current `:main-opts` is
      `["-m" "clj-kondo.main" "--lint" "bb.edn" "deps.edn" ".lsp/config.edn"
      ".psi/startup-prompts.edn" "bases" "components" "extensions" "spec"
      "tests.edn" "extensions/tests.edn"]` — no such flags)
      — done: `lint-alias-lints-extensions-test` gained a testing block
      asserting none of the four flags appears in `:main-opts` (doseq over
      `["--cache" "--config" "--config-dir" "--dependencies"]`; exact element
      membership — the flags are clj-kondo CLI tokens, never legitimate path
      values); unit suite 9 tests / 39 assertions pass

## Slice 16 — Task-test-review follow-ups (2026-08-15)

- [x] Close the clj-kondo jar guard/exec disagreement (skill infra-dep
      criterion — the clj-kondo artifact is only guard-injectable, not
      exec-injectable): `clj-kondo-main` executes
      `clojure -Sdeps '{:deps {clj-kondo/clj-kondo {:mvn/version …}}} -M -m
      clj-kondo.main` — the subprocess resolves the artifact via mvn
      coordinates from the Clojure CLI's own local repo (default
      `~/.m2/repository`, or `:mvn/local-repo` from deps.edn/CLJ_CONFIG),
      while the `^:integration` skip guard checks `clj-kondo-jar`, a path
      derived from `user.home` (+ the `psi.lint-config-test.clj-kondo-jar`
      override) that is NEVER passed to the subprocess. So the override
      cannot redirect execution (a valid jar at a custom path passes the
      guard but the subprocess still resolves/downloads from the default m2;
      a jar present only under a different local repo the CLI uses skips
      needlessly) — unlike `http-kit-jar`, which IS the `--lint` argument
      and therefore truly exec-effective. Fix: derive `:mvn/local-repo` for
      the `-Sdeps` map from the guarded `clj-kondo-jar` path (strip the
      `clj-kondo/clj-kondo/{version}/clj-kondo-{version}.jar` suffix → repo
      root), so guard and exec agree on the exact artifact; verify the
      override-to-custom-path case and the default case both resolve the
      guarded jar (e.g. assert the subprocess `-Spath` output contains the
      guarded jar path)
      — done: `clj-kondo-deps` (now a fn) emits `:mvn/local-repo` derived by
      `clj-kondo-local-repo` from the guarded `clj-kondo-jar` (strip the
      standard-m2 suffix; `psi.lint-config-test.clj-kondo-local-repo` property
      escape hatch for layouts the strip can't handle; a non-m2-layout jar
      path with no override throws a clear ex-info naming the property —
      verified: `/tmp/ck252-nonm2-layout.jar` → loud ExceptionInfo, no silent
      wrong-artifact/download). The `^:integration` proof gained a `-Spath`
      arm asserting the resolved classpath contains the guarded `clj-kondo-jar`
      (guard/exec agreement on the EXACT artifact). Verified: default case →
      integration 32 tests / 174 assertions, no SKIP; override to a custom
      m2-layout path (`/tmp/ck252-localrepo/...jar`) → 174 assertions, no SKIP
      (the -Spath arm would fail if the subprocess still resolved from default
      m2); consistent local-repo property + jar override → 174 assertions;
      non-m2-layout jar → 1 error, the clear ex-info
- [x] Assert the http-kit import files are TRACKED in the git index, not just
      not-ignored: `gitignore-http-kit-import-tracking-test` (text lines) and
      `^:integration gitignore-http-kit-tracking-ground-truth-test`
      (`git check-ignore` exit 1 = not ignored) prove the negation rules
      work, but "not ignored" ≠ "tracked" — a `git rm --cached` of
      `.clj-kondo/imports/http-kit/http-kit/config.edn` (+ the hook impl)
      keeps every existing guard green (all read from disk; check-ignore
      still exits 1) while silently dropping the registration from future
      commits. Add an `^:integration` assertion (same ground-truth test, or
      extend it) running `git ls-files --error-unmatch
      .clj-kondo/imports/http-kit/http-kit/config.edn
      .clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj` from
      repo root → exit 0 (tracked); git-absent → visible SKIP via
      `report-skip!`, mirroring the existing skip arms
      — done: `gitignore-http-kit-tracking-ground-truth-test` gained a third
      arm — `git ls-files --error-unmatch` on config.edn + with_channel.clj
      from repo root (run-bounded, git-bin-resolved, existing skip arms
      unchanged) → exit 0 only when BOTH are in the index, so a
      `git rm --cached` fails loudly in `:integration` instead of silently
      dropping the registration from future commits. Verified: both files
      tracked (exit 0); integration 32 tests / 174 assertions, no SKIP

## Slice 17 — Implementation-review follow-ups (2026-08-15)

- [x] Amend design.md AC1/Context's CI-scope framing (design-step 9 doc drift,
      found 2026-08-15): AC1 still documents option (b) — "AC1 verification is
      **local-only**", "the negative-control probe (analysis-level proof) is
      inherently a temporary local source edit, **never run in CI**", "CI …
      cannot exercise the registration" — but slices 7-16 committed the
      analysis-level proof (negative control + real-file AC1 arm) as
      `^:integration` tests (`http-kit-defreq-analysis-level-resolution-test`,
      `gitignore-http-kit-tracking-ground-truth-test` in
      `components/shared-config/test/psi/shared_config/lint_config_test.clj`)
      that CI runs via `bb clojure:test:integration` (ci.yml:166) — design-step
      9 option (a) realized via a test vehicle (implementation.md slice-7
      note). Retain the still-true lint-surface nuance (CI `bb lint` itself has
      no cache → the http-kit jar is never analyzed → trivially clean), but
      correct the blanket local-only / never-in-CI / cannot-exercise statements
      and reference the committed integration proof as the CI-enforceable
      regression surface
      — done: design.md AC1 rewritten — header drops "local-only"; the
      cache-dependent lint-surface nuance is retained and scoped to the `bb
      lint`/pre-commit surfaces only; the "temporary local source edit, never
      run in CI" and blanket "cannot exercise the registration" claims are
      corrected with the committed `^:integration` test vehicle
      (`http-kit-defreq-analysis-level-resolution-test` +
      `gitignore-http-kit-tracking-ground-truth-test`, run via
      `bb clojure:test:integration` in CI) named as the CI-enforceable
      regression surface
- [x] Reconcile plan.md decision 3 / R3's CI-scope note with the amended
      design.md: R3 currently says "AC1 is local-only — CI `bb lint` has no
      cache and never analyzes the jar, so it is trivially clean … (option (b)
      chosen; committed config + local verification, no CI workflow change)" —
      the same drift as design.md AC1. Update to record the test-vehicle option
      (a) (CI runs the analysis-level proof via `bb clojure:test:integration`)
      while keeping the `bb lint`-surface claim intact
      — done: plan.md decision 3 + R3 amended — both now scope the
      local/cache-dependent claim to the `bb lint` surface itself and record
      the committed `^:integration` test vehicle (option (a) via tests, slices
      7-16, run via `bb clojure:test:integration`) as the CI-enforceable
      regression surface; the "option (b) chosen" / "no CI workflow change
      … local verification" framing is replaced

## Slice 18 — Implementation-review follow-ups (2026-08-15)

- [x] Reconcile design.md Context's "used in exactly one repo file (rg over
      components/ + extensions/)" claim (Context, "used in exactly one repo
      file" bullet) with the committed test file: slices 7-16 committed
      `components/shared-config/test/psi/shared_config/lint_config_test.clj`,
      which references `org.httpkit.client` symbols (`:lint-as` registration
      assertion, the `http-client-entries` EDN-walk predicate, the probe ns) —
      within the claim's own stated rg scope there are now TWO files
      referencing the ns (verified 2026-08-15: rg over components/ +
      extensions/ matches dev-http test + lint_config_test). The claim's
      intent (the dev-http test file is the only *usage* of the http-kit
      client) still holds, but the literal "exactly one repo file" wording is
      stale doc drift introduced by the test additions, never reconciled.
      Amend to scope the claim to call sites / runtime usage, noting the
      committed test file references the symbols as config-assertion data,
      not usage
      — done: design.md Context bullet amended — the claim now reads "used at
      exactly one runtime call site in the repo" (require @16, get/post @572/
      @737), and explicitly notes the committed regression test references
      `org.httpkit.client` symbols only as config-assertion data (the `:lint-as`
      registration assertion, the `http-client-entries` EDN-walk predicate, the
      probe ns), never as runtime usage
- [x] Guard the with-channel hook's transformation SEMANTICS, not just its
      existence/signature: `with-channel-hook-impl-guard-test` (slice 11)
      asserts the impl file exists and carries `(ns httpkit.with-channel)` +
      `(defn with-channel …)`, but a semantically-changed transformation body
      (still a valid ns/defn — e.g. a no-op rewrite returning the node
      unchanged) passes every guard while silently mis-analyzing with-channel
      calls; the repo has zero with-channel call sites (slice-11 fact), so
      nothing exercises the hook and the drift is undetectable. Add a
      whitespace/indentation-normalized compare of the tracked impl against
      the pinned 2.8.0 jar's `clj-kondo.exports/http-kit/http-kit/httpkit/with_channel.clj`
      — the `^:integration` analysis-level proof already has the jar path +
      read machinery (strip whitespace/indentation so the documented cljfmt
      indentation drift, slice 5, stays green; jar-absent → visible SKIP via
      `report-skip!`, mirroring the existing skip arms). Verified 2026-08-15:
      the current tracked impl is semantically identical to the jar export
      modulo whitespace (whitespace-stripped diff clean), so the guard passes
      today
      — done: new `^:integration` `with-channel-hook-semantics-guard-test` —
      reads `clj-kondo.exports/http-kit/http-kit/httpkit/with_channel.clj`
      from the pinned http-kit jar (ZipFile in-process, entry absent → fails
      loudly) and compares it against the tracked impl with
      `normalize-whitespace` (collapse runs to a single space — preserves token
      boundaries, unlike full whitespace removal, while tolerating the
      slice-5 cljfmt indentation drift; jar-absent → visible SKIP via
      `report-skip!`, mirroring the existing skip arms; jar path already
      injectable/nullable via `psi.lint-config-test.http-kit-jar` / the
      derived m2 path). Verified: integration suite 33 tests / 177 assertions
      pass (was 32/174; +1 test +3 assertions); negative check — a simulated
      no-op rewrite (`{:node node}` early return) produces different
      normalized content, so semantic drift fails loudly while the documented
      indentation drift stays green; `bb lint` errors: 0, warnings: 0;
      `bb fmt:check` clean

## Slice 19 — Implementation-review follow-ups (2026-08-15)

- [x] Reconcile implementation.md's stale AC1 CI-framing with the realized
      option (a)-via-test-vehicle (slices 7-17): (1) the "Design review context
      (re-pass — architectural fit)" note still claims "AC1 proof surface is
      CI-enforced" — self-flagged as superseded ("correct or strike it") in the
      design-step-9 amendment note and confirmed overstated there, but never
      corrected/struck: CI's `bb lint` is trivially clean (no cache, no
      `--dependencies` → the http-kit jar is never analyzed), so the claim
      misleads about what CI exercises. (2) the "Implementation slice —
      executed (slices 0-4 complete)" entry still records "AC1 scoped
      local-only (option b — no CI workflow change; committed config + local
      verification accepted)" — the opposite of the committed `^:integration`
      test vehicle (http-kit-defreq-analysis-level-resolution-test +
      gitignore-http-kit-tracking-ground-truth-test, run via
      `bb clojure:test:integration` in CI). Slice 17 reconciled design.md and
      plan.md only; implementation.md's own two records were never touched.
      — done: both implementation.md records corrected in place — (1) the
      architectural-fit note's "AC1 proof surface is CI-enforced" claim
      struck/corrected: CI `bb lint` is trivially clean (no cache, no
      `--dependencies` → the http-kit jar is never analyzed), so the lint
      surface itself is NOT CI-enforced; the CI-enforceable regression surface
      is the committed `^:integration` test vehicle (slices 7-18, three tests);
      the retained true part (CI clj-kondo binary only `--version`-checked;
      pinned JVM clj-kondo is the effective lint-gate analyzer) kept; (2) the
      "Implementation slice — executed" record's "AC1 scoped local-only (option
      b — no CI workflow change; committed config + local verification
      accepted)" corrected to the realized option (a)-via-test-vehicle (three
      named `^:integration` tests, run via `bb clojure:test:integration` in
      CI), local-only scope retained only for the cache-dependent `bb lint`
      surface itself; the AC1 bullet header's "(local-only, design-step 9)"
      reframed to match. Doc-only reconciliation — no code/test changes
- [x] Amend design.md AC1's "slices 7-16" range / two-test enumeration:
      "design-step 9 option (a) realized in slices 7-16: [the two named
      `^:integration` tests] … are `^:integration`" is now a non-exhaustive
      enumeration — slice 18 added a third `^:integration` test
      (`with-channel-hook-semantics-guard-test`) to the same
      `lint_config_test.clj` file, so the range (7-16) and the "are
      `^:integration`" framing predate the file's current shape. Extend the
      range or re-scope the enumeration as non-exhaustive (mirrored in
      plan.md decision 3 / R3's "slices 7-16" references).
      — done: design.md AC1's enumeration re-scoped — "slices 7-18;
      non-exhaustive (the file's `^:integration` set may grow with later
      review slices)" with `with-channel-hook-semantics-guard-test` added to
      the named list; design.md Context's "slices 7-16" reference and plan.md
      decision 3 / R3's "slices 7-16" references mirrored (slices 7-18, with
      the third test named in decision 3). Doc-only reconciliation

## Slice 20 — Implementation-review follow-ups (2026-08-15)

- [x] Narrow the semantics guard's whitespace blind spot: `normalize-whitespace`
      in `with-channel-hook-semantics-guard-test` collapses runs of whitespace
      in the RAW TEXT — including whitespace INSIDE string literals — so a
      semantic change confined to literal spacing (e.g. the error message
      `"No request or channel provided"` → `"No  request or channel provided"`;
      verified 2026-08-15: `normalize-whitespace` returns equal strings for the
      two forms) passes while the guard's stated property is "any semantic
      change fails loudly". Fix: compare parsed forms structurally (read-string
      both sides and compare, ignoring top-level whitespace by construction), or
      strip only indentation/line-structure whitespace (per-line trim preserves
      intra-literal spacing), or record the accepted gap explicitly in the
      testing string
      — done (first branch — parsed-form structural compare): `normalize-whitespace`
      removed and replaced by `parse-forms` (read-string both sides wrapped in a
      vector — top-level whitespace/indentation vanishes by construction, so the
      documented slice-5 cljfmt drift stays green; string-literal contents
      survive exactly, so a literal-spacing change is a different parsed value).
      Verified 2026-08-15: parsed-form compare is indentation-insensitive,
      string-literal-spacing-sensitive (`"No request"` vs `"No  request"` →
      different), and token-change-sensitive. `normalize-whitespace` deleted
      (unused after the switch). Integration suite 33 tests / 176 assertions,
      no SKIP (was 177 — the redundant inner `(is (some? jar-export))` dropped
      with the nil-guard restructure below); `bb lint` errors: 0, warnings: 0;
      `bb fmt:check` clean
- [x] Make `with-channel-hook-semantics-guard-test` fail cleanly when the jar
      export entry is missing: when `.getEntry` returns nil, `jar-export` is
      nil — the `(is (some? jar-export))` fails but the subsequent
      `(normalize-whitespace jar-export)` throws an NPE (verified 2026-08-15:
      "Cannot invoke Object.toString() because s is null"), so the missing-entry
      case reports a clojure.test ERROR instead of the clean assertion failure
      it deserves. Wrap the equality in a `when-let`/nil guard (or assert
      `some?` and return early) so the missing-entry and mismatch cases both
      fail as plain assertion failures
      — done (nil guard): the parsed-form equality is wrapped in
      `(when (some? jar-export) …)` after the outer `(is (some? jar-export)
      …)` — a missing entry fails as the single plain assertion failure and
      skips the equality, never reaching the nil-deref. Verified 2026-08-15
      with a fabricated jar lacking the export entry (`psi.lint-config-test.http-kit-jar`
      override → `jar cf` with only a dummy.txt): the focused test reports
      `FAIL … expected: (some? jar-export), actual: (not (some? nil))` — no
      ERROR, no NPE (the fake jar also broke the sibling analysis-level proof
      in the same focused run, expected — the full suite with the real jar is
      green below)
- [x] Include out/err in `run-bounded`'s timeout ex-info: the 120s-bound path
      throws `{:cmd cmd}` only, discarding the partial stdout/stderr drained in
      the finally — a hung subprocess (cold `-Sdeps` stall, network fetch) that
      hits the bound surfaces with zero context about where it stalled, and the
      drained streams are only reachable by re-running with debugging. Capture
      the drained `@out-f`/`@err-f` (or a bounded prefix) into the ex-info data
      before throwing so the timeout diagnostic carries the partial output
      — done: the timeout branch now destroys the process FIRST (killing it
      closes the pipe streams so the draining futures complete — derefing
      before the kill would block, the streams stay open while the process
      lives), then captures the drained `@out-f`/`@err-f` via a 500 ms bounded
      deref (3-arg `deref`, `::unavailable` fallback, InterruptedException →
      `<stdout/stderr interrupted>`) into the ex-info `{:cmd … :out … :err …}`;
      the finally drain is unchanged (idempotent — the process is already dead
      on the timeout path). Verified 2026-08-15 (process-timeout-ms
      alter-var-root'd to 2 s): `sh -c "echo partial-output-here; sleep 30"` →
      timeout ex-info carries `:out "partial-output-here\n"` and `:err ""`

## Slice 21 — Implementation-review follow-ups (2026-08-15)

- [x] Bound the success-path stream drain in `run-bounded`
      (`components/shared-config/test/psi/shared_config/lint_config_test.clj`):
      the 120s bound covers `(.waitFor proc process-timeout-ms …)` ONLY — the
      success branch returns `{:exit … :out @out-f :err @err-f}` with unbounded
      derefs, so a subprocess that exits while a descendant still holds the
      stdout/stderr pipe open (classic grandchild scenario — e.g. the `clojure`
      CLI spawning a JVM that spawns a helper) never EOFs the slurp and the
      suite hangs indefinitely despite the documented bound (the exact hang
      class slice 8 set out to eliminate; slices 14/16/20 hardened the timeout
      path only — kill-then-bounded-drain — and the docstring's "a hung
      subprocess … blocks the suite indefinitely" is only partially true).
      Fix: bound the drain on the success path too (e.g. bounded 3-arg deref
      with a loud failure, or drain via `onExit`/a watchdog), so the whole
      subprocess interaction — not just the process lifetime — is bounded.
      Verify: simulate a pipe-holding grandchild (e.g.
      `sh -c "echo out; (sleep 300) & wait"` with a reduced
      process-timeout-ms) → suite fails loudly instead of hanging.
      — done: the success path now uses the same bounded 3-arg deref
      (`drain`, 500 ms) and throws a loud ex-info — distinct message
      "subprocess exited but its stdout/stderr did not close within the drain
      bound — a descendant process is holding the pipe open" — with the
      undrained `:out`/`:err` marked `:unavailable`, when the streams do not
      close past the bound; the `finally` drain is bounded too (previously
      unbounded `@out-f`/`@err-f` guarded by `.isAlive` — a completed future
      returns instantly, so the bound only bites on the pathological path).
      Verified 2026-08-15 via scratch (process-timeout-ms alter-var-root'd to
      5 s): `sh -c "sleep 300 & echo done"` (parent exits, descendant holds
      the pipe — the success-path case the unbounded deref hung on) → loud
      ex-info in ~2 s (`:out :unavailable`), NO hang; `sh -c "echo
      partial-out; sleep 30"` → timeout path still fails loudly at the bound
      with `:out "partial-out\n"` captured (slice-20 behavior intact); normal
      `echo hello` → `{:exit 0 :out "hello\n"}` in 7 ms. Stray `sleep 300`
      cleaned up after verification.
- [x] Bind `*read-eval*` false in the read-string-based compares
      (`parse-forms` in `with-channel-hook-semantics-guard-test` and the
      `read-string` in `with-channel-hook-impl-guard-test`): verified
      2026-08-15 that `*read-eval*` defaults to `true` and
      `(read-string "#=(+ 1 2)")` → `3` — the guards whose purpose is to
      detect semantic drift in the tracked hook impl vs the pinned jar export
      would themselves EXECUTE a `#=` reader-eval form on either side (a
      drifted/malicious export or impl silently runs code during the
      integration run instead of being compared); `#?` reader conditionals
      additionally throw "Conditional read not allowed", a confusing failure
      for a structural-compare guard. Fix: `(binding [*read-eval* false]
      (read-string …))` — makes `#=` throw loudly instead of evaluating — and
      pass `:read-cond :allow` if conditionals should compare rather than
      error. Verify: `(binding [*read-eval* false] (read-string "#=(+ 1 2)"))`
      throws; a fabricated `#=`-bearing jar export (via the
      `psi.lint-config-test.http-kit-jar` override) fails loudly with no
      evaluation side effect.
      — done (both sites): `parse-forms` and the impl-guard read both wrap
      `(binding [*read-eval* false] (read-string {:read-cond :preserve} …))`.
      `:read-cond :preserve` chosen over the follow-up's suggested `:allow`:
      `:allow` reads only the current platform's branch, silently dropping the
      others from the compare — a blind spot of the exact class this guard
      exists to close — while `:preserve` keeps the full conditional as a
      reader-conditional form, so all branches compare structurally and a
      conditional introduction reads instead of erroring. Verified 2026-08-15:
      `(binding [*read-eval* false] (read-string "#=(+ 1 2)"))` throws
      "EvalReader not allowed when *read-eval* is false" (no evaluation);
      `(parse-forms "#?(:clj 1 :cljs 2)")` → `[#?(:clj 1 :cljs 2)]` (parsed
      structurally).

## Slice 22 — Implementation-review follow-ups (2026-08-15)

- [x] Reconcile the committed test file with the repo's own file-length gate:
      resolved by splitting (slice 23) — `lint_config_test.clj` (804 lines, over
      the 800 gate) split into three files: `lint_config_test.clj` (unit
      invariants, 244), `lint_config_test_support.clj` (shared fixtures, 375;
      ns ends in -support so kaocha's .*-test$ pattern never runs it) and
      `lint_config_integration_test.clj` (^:integration proofs, 246). No
      forwarding vars — fixtures are defined once in the support ns and :refer'd.
      `bb commit-check:file-lengths` passes at the committed state; the sibling
      251 split was NOT double-implemented (251 remains design-only — its
      limit-raise proposal is a separate human-reviewed policy question, and the
      delegation explicitly directed splitting)
- [x] Reuse the shared `psi.test-support.repo-root` instead of the
      component-local `find-repo-root`/`repo-root` copy (skill
      reusable-existing-pattern flag): `bases/main/test/psi/test_support/repo_root.clj`
      exists precisely to "replace component-local copies so future fixes land
      in one place" (its docstring), and bases/main/test is on BOTH the :unit
      and :integration suite classpaths (tests.edn) — verified requirable from
      the shared-config test ns. The committed `lint_config_test.clj`
      re-implements it with a different root marker (deps.edn+bb.edn vs the
      shared doc/custom-providers.md) plus the `psi.lint-config-test.repo-root`
      property override (slice-9 made the LOCAL copy injectable/nullable but
      never checked for the shared existing pattern). Actionable: either
      require `psi.test-support.repo-root` from the test ns — extending the
      shared ns with the property override and the deps.edn+bb.edn marker (or
      a marker option) so the override/root logic lands in one place for all
      consumers — or record the deliberate divergence (why the shared helper's
      marker + no-override contract is insufficient here) in implementation.md.
      Note: the concurrent 251 split carries the SAME local copy into
      `lint_config_test_support.clj`, so the reuse decision must apply to the
      support ns too, not just the current file
      — done (REUSE branch — supersedes the slice-23 divergence note): the
      shared helper `bases/main/test/psi/test_support/repo_root.clj` gained
      three backward-compatible opts — `:markers` (coll of repo-relative
      marker paths, default `[["doc" "custom-providers.md"]]` — the no-arg
      call is byte-for-byte the old behavior), `:prop` (system property that
      overrides the walk entirely, injectability per the skill infra-dep
      criterion), and `:required?` (fail-loud ex-info when the walk exhausts
      without finding all markers, instead of silently returning the fs root);
      the local `find-repo-root`/`repo-root` copy in
      `lint_config_test_support.clj` is DELETED — `repo-root` is now
      `(str (test-repo-root/repo-root {:markers [["deps.edn"] ["bb.edn"]]
      :prop "psi.lint-config-test.repo-root" :required? true}))`, so the
      deps.edn+bb.edn marker set and the property override land in the SHARED
      helper (one definition site, applying to the support ns AND the current
      file per the note). Also fixed a latent walk bug the extension surfaced:
      the old loop's terminal `(= dir (.getParentFile dir))` never triggers on
      macOS (parent of `/` is nil), so a never-found walk recurred into nil —
      the new loop returns the fs root at the nil-parent boundary and lets
      `:required?` throw there. Verified: unit 9/39, integration 33/176 (both
      proofs ran, no SKIP), ai user-models-test 20/139 + agent-session
      workflow-async-path-test 9/53 (the two existing shared-helper consumers,
      unchanged contract), nested-CWD + property-override + fail-loud all
      exercised via clojure -e; `bb lint` errors: 0 warnings: 0; `bb fmt:check`
      clean

## Slice 24 — Implementation-review follow-ups (2026-08-15)

- [x] Make `with-channel-hook-impl-guard-test` fail cleanly on a deleted impl
      file: the guard's stated purpose is detecting "a deleted or renamed hook
      impl", but `(slurp impl-file)` runs in the let binding BEFORE any
      assertion — including `(is (.exists impl-file) …)` — so the deletion case
      throws FileNotFoundException and clojure.test reports an ERROR with the
      exists assertion unreachable (verified 2026-08-15: moved
      with_channel.clj aside → focused unit run "8 passed, 0 failed, 1
      errored", no exists-assertion message). This is the same class of defect
      slice 20 fixed in the integration test (jar-entry nil → NPE → ERROR,
      nil-guarded to a plain assertion failure): read the impl only when it
      exists (nil-guard / `when` the exists check, then parse) so a deleted or
      renamed impl fails as a clean assertion FAIL with its message, never an
      ERROR. Same parallel shape (mention, lower priority — covered by the
      ls-files index arm): `http-kit-import-registration-test`'s `read-edn`
      slurp on config.edn would likewise ERROR on whole-file deletion before
      any assertion; entry-removal (file present) already fails cleanly
      — done: the slurp/parse moved OUT of the let binding into a
      `(when (.exists impl-file) …)` guard placed AFTER the exists assertion
      (mirror of slice 20's `when (some? jar-export)` — the exists check fails
      cleanly first, the dependent ns/defn assertions run only when the file
      is present). Verified 2026-08-15 with the impl moved aside: focused run
      → `1 passed, 1 failed, 0 errored` — the ref assertion still passes, the
      exists assertion fails cleanly with its message, NO ERROR/FileNotFound
      (was "0 passed, 0 failed, 1 errored" pre-fix). The read-edn parallel
      shape was deliberately NOT hardened (reviewer's own lower-priority
      framing): whole-file deletion of config.edn is covered by the
      ^:integration `git ls-files --error-unmatch` index arm
      (gitignore-http-kit-tracking-ground-truth-test) — recorded in
      implementation.md
- [x] Fix the `.gitignore` order assertion's first-occurrence blind spot:
      `gitignore-http-kit-import-tracking-test`'s `index-of` uses
      `(first (keep-indexed …))` — the FIRST matching line — so a duplicate
      `**/.clj-kondo/imports/*` line added BELOW the negation lines (gitignore
      is last-match-wins: git re-ignores the http-kit import dir and the
      registration silently drops out of future commits) passes the unit guard
      while all three lines still exist in the right first-occurrence order.
      The `^:integration` check-ignore ground-truth arm is the backstop, but
      the unit guard's own order property is incomplete. Use the LAST index for
      the ignore-all pattern (or assert no occurrence after the negations) so
      the ordering invariant is complete in `:unit`
      — done (LAST-index branch): the let now binds both `index-of` (first,
      used for the negations) and `last-index-of`; the ordering check uses
      `(last-index-of ignore-all)` against each negation's first occurrence,
      so any ignore-all line after the first negation fails the unit guard.
      Verified 2026-08-15: duplicate `**/.clj-kondo/imports/*` appended below
      the negations → focused run `6 passed, 2 failed, 0 errored` with
      "**/.clj-kondo/imports/* (last occurrence, line N) precedes …" messages
      (was 8 passed pre-fix — the blind spot); safe duplicate above the
      negations still passes (last occurrence precedes the negations); clean
      .gitignore → 8 passed

## Slice 25 — Implementation-review follow-ups (2026-08-15)

- [x] Reconcile design.md/plan.md test-file references with the slice-23 split:
      design.md Context ("The committed regression test
      (`components/shared-config/test/psi/shared_config/lint_config_test.clj`,
      slices 7-18)"), design.md AC1 ("… in
      `components/shared-config/test/psi/shared_config/lint_config_test.clj`
      are `^:integration` and run in CI via `bb clojure:test:integration`"),
      and plan.md decision 3 ("`^:integration` in
      `components/shared-config/test/psi/shared_config/lint_config_test.clj`")
      all name the single pre-split file, but slice 23 (e2deda747) split it
      into `lint_config_test.clj` (9 unit invariants),
      `lint_config_test_support.clj` (shared fixtures — incl. the
      `http-client-entries` EDN-walk predicate), and
      `lint_config_integration_test.clj` (the three `^:integration` proofs).
      The last design.md/plan.md doc reconciliation (slice 19, d58d26d93)
      predates the split, so the references are stale doc drift of the exact
      class slices 17-19 existed to reconcile; they were never updated.
      Update the three references to the actual files (the named
      `^:integration` tests live in `lint_config_integration_test.clj`; the
      EDN-walk predicate lives in `lint_config_test_support.clj`).
      — done: design.md Context now names the split file set
      (`lint_config_test.clj` — unit invariants,
      `lint_config_test_support.clj` — shared fixtures incl. the
      `http-client-entries` EDN-walk predicate,
      `lint_config_integration_test.clj` — the `^:integration` proofs incl.
      the probe ns); design.md AC1 and plan.md decision 3 now point the three
      `^:integration` tests at
      `lint_config_integration_test.clj`; AC1's "the file's `^:integration`
      set" non-exhaustive note disambiguated to "the integration test file's"
- [x] Reconcile design.md AC1's mechanism description for
      `with-channel-hook-semantics-guard-test`: AC1 describes it as "tracked
      hook impl vs pinned 2.8.0 jar export, whitespace-normalized", but slice
      20 (50d37873b) replaced `normalize-whitespace` with `parse-forms`
      (parsed-form structural compare — whitespace/indentation-insensitive by
      construction, string-literal-spacing-sensitive; verified in
      `lint_config_integration_test.clj`). The wording describes the REMOVED
      mechanism and misleads about the guard's actual blind-spot profile (a
      literal-spacing change now FAILS loudly, whereas the whitespace-
      collapsing compare was blind to it — slice-20's rationale). Update AC1
      to name the parsed-form compare.
      — done: design.md AC1 now reads "parsed-form structural compare" (the
      slice-20 `parse-forms` mechanism) instead of "whitespace-normalized";
      the slice-5 cljfmt indentation-drift tolerance is implied by the
      parsed-form compare (indentation-insensitive by construction)

## Slice 26 — Implementation-review follow-ups (2026-08-15)

- [x] Harden `run-bounded`'s drain against an exceptionally-completed slurp
      future: `drain` in `lint_config_test_support.clj` catches only
      `InterruptedException`, but `(deref f 500 ::unavailable)` on a future
      whose `slurp` THREW rethrows the wrapped `ExecutionException`
      (deref does not distinguish an exceptional completion from a timeout).
      The throw path is the kill paths — the timeout branch and the finally
      both `destroyForcibly` the process before draining, and a read on a
      forcibly-killed process's stream can throw an IOException
      (platform/timing-dependent: macOS EOF'd cleanly in a 2026-08-15
      scratch, but Linux/Windows paths can throw) — so the designed timeout
      ex-info carrying `:out`/`:err` (slice 20) and the loud no-hang failure
      (slice 21) can be bypassed by an unexpected ExecutionException with no
      captured output. Catch the future's exception in `drain` (return a
      marker like `::unavailable` or the message, or fold it into the
      ex-info) so every path yields the designed failure shape
      — done: `drain` now catches `ExecutionException` (alongside
      `InterruptedException`) and returns a `{::drain-error "label: message"}`
      marker carrying the exception message; the ex-info construction passes
      the marker through in `:out`/`:err` (the diagnostic shows WHY the drain
      failed, not just `:unavailable`); the success path's failure check uses
      a `drain-failed?` predicate (`::unavailable` ∨ map — the only map value
      drain returns is the error marker), so a read error on the success path
      throws the loud no-hang ex-info (message generalized to "could not be
      drained … a descendant process is holding the pipe open or the stream
      read failed") instead of returning the marker as bogus content.
      Verified: normal path `{:exit 0 :out "hello\n"}` unchanged; a throwing
      slurp future (IOException "Stream closed") → `{::drain-error "stdout:
      java.io.IOException: Stream closed"}` with `drain-failed?` true (no
      escape); timeout path (bound reduced to 2s vs `sleep 300`) still kills
      and throws the ex-info with partial `:out "partial-out\n"` captured
- [x] Resolve the dead typo'd `.gitignore` line 3
      (`**/.clj-konde/imports.claude/`): the pattern matches nothing
      (`.clj-konde` ≠ `.clj-kondo`; `imports.claude` ≠ `imports`) — it is a
      pre-existing typo (d3acaca096, 2026-04-09) sitting immediately above
      this task's tracking rules (lines 4-6) in the exact file the slice-4
      change set includes, and no task file or guard references it (the
      gitignore guards cover lines 4-6 only). Likely intended as
      `**/.clj-kondo/imports/` (the pre-task line this task replaced) or a
      Claude-imports ignore — fix the typo or delete the dead line after
      confirming intent
      — done (DELETE branch — intent confirmed via git history): traced the
      line's full history — d15150a5c added `.clj-konde/imports` (intent:
      ignore `.clj-kondo/imports`, typo'd from the start); 0bf814fd (commit
      message "exclude .claude/") MANGLED it into `.clj-konde/imports.claude/`
      instead of adding a `.claude/` line; d3acaca096 made it recursive and
      added the CORRECT `**/.clj-kondo/imports/` + `**/.clj-kondo/.cache/`
      (the clj-kondo-imports intent realized there, and now by this task's
      negation set lines 4-6). The `.claude` intent: `.claude/settings.local.json`
      is excluded by the user's GLOBAL gitignore
      (`~/.gitignore_global:1:.claude/settings.local.json`), and
      `.claude/CLAUDE.md` is intentionally TRACKED — so a repo-level `.claude/`
      rule would be a new policy (blanket-ignoring future shared .claude
      content), not the original intent. Both underlying intents are realized
      elsewhere; the line matches nothing (`find . -path "*clj-konde*"` → ∅).
      Deleted the dead line — behavior-preserving, no new policy; the gitignore
      unit guard (presence + LAST-ignore-all-ordering of lines 4-6) is
      index-based, not absolute-line-number-based, so it stays green (9 unit
      tests / 39 assertions pass)

## Slice 27 — Implementation-review follow-ups (2026-08-15)

- [x] Reuse the shared `parse-forms` fixture in `with-channel-hook-impl-guard-test`
      instead of inlining its exact implementation: `lint_config_test_support.clj`'s
      ns contract is "each fixture is DEFINED here once and :refer'd into the test
      namespaces — no forwarding vars", but the unit test's `when (.exists impl-file)`
      branch re-implements `(binding [*read-eval* false] (read-string {:read-cond
      :preserve} (str "[" (slurp impl-file) "]")))` — byte-for-byte the body of the
      shared `parse-forms` fixture (same *read-eval* false + :read-cond :preserve
      hardening, slice 21), which the unit ns does NOT :refer. The inline copy means
      the unit suite never exercises the shared fixture on this path, and a future
      `parse-forms` hardening (or a regression in it) diverges silently between the
      two sites. Fix: add `parse-forms` to the `:refer` list in
      `lint_config_test.clj` and replace the inline binding/read-string with
      `(parse-forms (slurp impl-file))`; the parsed-form ns/defn assertions are
      unchanged (verified identical semantics).
      — done: `parse-forms` added to the `:refer` list in `lint_config_test.clj`;
      the inline binding/read-string in `with-channel-hook-impl-guard-test` is
      replaced with `(parse-forms (slurp impl-file))` (the shared fixture's body
      is byte-for-byte the inline copy — *read-eval* false + :read-cond :preserve —
      so semantics are identical, and the unit suite now exercises the SAME
      parse-forms the ^:integration semantics guard relies on; a future hardening
      or regression cannot diverge between the two sites); docstring updated to
      name the shared fixture. Verified: unit suite → 9 tests / 39 assertions pass
      (unchanged), integration 33/176 no SKIP
- [x] Consolidate the task's `delete-recursively!` copy into shared test support,
      or record the deliberate divergence: slice-12 added it to
      `lint_config_test_support.clj`, making it one of SEVEN local copies of a
      repo-wide repeated private pattern — pre-existing copies in
      `components/tui/test/psi/tui/test_harness/tmux_rehydration.clj`,
      `components/history/test/psi/history/git_test.clj`,
      `components/history/test/psi/history/git_worktree_test.clj`,
      `extensions/work-on/test/extensions/work_on_command_test.clj`,
      `components/agent-session/test/psi/agent_session/test_support.clj` (public,
      component-scoped), and
      `components/agent-session/test/psi/agent_session/tool_output_integration_test.clj`
      (private). Slice-22's repo-root consolidation set the precedent (shared
      `psi.test-support` ns on both :unit and :integration classpaths is the natural
      home — e.g. `psi.test-support.fs/delete-recursively!` or an extension of the
      existing shared ns — with the seven copies migrating to it). The review skill's
      reusable-existing-pattern flag applies: a new copy of a repeated pattern with a
      shared home already established.
      — done (CONSOLIDATION branch): new shared
      `bases/main/test/psi/test_support/fs.clj` (`psi.test-support.fs/delete-recursively!`)
      — bases/main/test is on the :unit/:extensions/:integration suite classpaths via
      the :test-paths alias (the same reachability psi.test-support.repo-root relies
      on). All SEVEN copies migrated: tui tmux_rehydration (private defn- deleted,
      calls → test-fs/), history git_test + git_worktree_test (private defn- deleted,
      4 + 5 call sites incl. the with-null-context macro template → test-fs/),
      work-on work_on_command_test (local defn + its agent-session-classpath
      rationale docstring deleted, call → test-fs/), agent-session test_support
      (public fixture now delegates to the shared helper — external callers
      query_graph_test / task_artifact_content_resolver_test unchanged via
      test-support/delete-recursively!), agent-session tool_output_integration_test
      (private defn- deleted, call → test-fs/), shared-config lint_config_test_support
      (the task's copy now delegates, mirroring the slice-22 repo-root delegation).
      The shared implementation is the behavioral superset of the seven copies:
      nil-safe (tui's guard), String/File via io/file conversion (history/
      agent-session/work-on's (File. (str path)) + tui's (io/file f)), an .exists
      guard (harmless no-op for tool-output's always-exists path), and a
      delete-children-first recursive walk returning nil (shared-config's contract).
      Caveat recorded in implementation.md: the work-on extension's STANDALONE
      deps.edn `:test` alias (extensions/work-on/deps.edn) does not include
      bases/main/test — the extension test now requires psi.test-support.fs only
      under the repo-level test commands (bb clojure:test:extensions runs
      `-M:test-paths`, which includes bases/main/test); CI never uses the standalone
      alias. Verified: focused unit runs — shared-config 9/39, history 57/169,
      agent-session (tool-output/query-graph/task-artifact) 16/94, work-on
      extensions 11/61; integration 33/176 no SKIP (both proofs ran); tui harness +
      shared ns compile (`require` check); `bb lint` errors: 0 warnings: 0;
      `bb fmt:check` clean; `bb commit-check:file-lengths` exit 0
- [x] Treat the interrupted-drain marker as a failed drain in `run-bounded` (or
      record why not): `drain` returns `(str "<" label " interrupted>")` on
      InterruptedException — a STRING — and `drain-failed?`
      (`(= ::unavailable x)` ∨ `(map? x)`, support ns line 275) does not match it,
      unlike the ExecutionException marker (`{::drain-error …}`) and `::unavailable`,
      which both fail. So on the success path an interrupted drain silently returns
      `{:exit 0 :out "<stdout interrupted>"}` (the marker accepted as real output),
      and on the timeout path the marker passes through as `:out`/`:err` — the two
      exceptional-drain paths are handled asymmetrically, inconsistent with slice-26's
      designed invariant "every path yields the designed failure shape". Fix: return
      a keyword/map marker for InterruptedException too (e.g. `::interrupted`, or a
      `{::drain-error "label: interrupted"}`-shaped marker) so `drain-failed?` catches
      it on both paths — or record the accepted gap (the main test thread is rarely
      interrupted, so the marker-as-content path is nearly unreachable).
      — done (fix branch — the `{::drain-error …}` map marker, consistent with the
      slice-26 ExecutionException marker): `drain`'s InterruptedException catch now
      returns `{::drain-error (str label ": interrupted")}` — `drain-failed?`'s
      `(map? x)` arm catches it on the success path (loud no-hang ex-info, message
      generalized by slice-26 already) and the timeout path passes the marker through
      in `:out`/`:err` exactly like the ExecutionException marker, so the two
      exceptional-drain paths are now symmetric under the designed invariant;
      `run-bounded`'s docstring updated to state the interrupted case. Verified:
      `drain-failed?` on the old `"<stdout interrupted>"` string → false (the gap),
      on the new `{::drain-error "stdout: interrupted"}` map → true (closed); unit
      suite → 9 tests / 39 assertions pass

## Slice 28 — Implementation-review follow-ups (2026-08-15)

- [x] Guard the CI execution chain that makes the regression surface
      CI-enforceable — ci.yml's steps and bb.edn's clojure:test:integration
      task are the only unguarded links: tests-edn-suite-wiring-test (slice
      14) guards tests.edn's :integration suite and bb-edn-lint-task-wrapper-test
      guards bb.edn's lint task, but nothing guards ci.yml's "Lint" step
      (`run: bb lint`, ci.yml:89) or "Run Clojure integration tests" step
      (`run: bb clojure:test:integration`, ci.yml:166) — the outer links that
      actually execute the lint gate and the three ^:integration proofs
      (design.md AC1 names `bb clojure:test:integration` as the CI-enforceable
      regression surface) — nor bb.edn's clojure:test:integration task
      (`(System/exit (run-scry-kaocha-suite! "integration"
      ["--focus" "integration"]))`, bb.edn:307-309), so a dropped/renamed CI
      step or a task drift to another suite/focus silently disables the entire
      CI regression surface while every existing guard stays green — the exact
      silent-drift class the task already closed for .gitignore / lint-alias /
      bb.edn lint wrapper / tests.edn. Verified 2026-08-15: zero tests read
      ci.yml anywhere in the repo (rg over components/ + bases/ +
      extensions/). Fix: add unit assertions (same lint_config_test.clj ns —
      read ci.yml as text like the .gitignore test, and bb.edn as EDN — no
      subprocess, runs anywhere) asserting ci.yml contains the `bb lint` Lint
      step and the `bb clojure:test:integration` integration step, and that
      bb.edn's clojure:test:integration task still invokes
      run-scry-kaocha-suite! with suite id "integration" (a drift to a
      different suite/focus would silently stop the proofs from running)
      — done: new `ci-execution-chain-guard-test` + private `ci-run-steps`
      helper in lint_config_test.clj — `ci-run-steps` parses ci.yml lines
      into a step-name → run-command map (every `- name: X` line paired with
      the next `run: Y`; line-based like the .gitignore test, no YAML parser,
      no subprocess, runs anywhere); the test asserts the Lint step runs
      `bb lint` (ci.yml:89) and the Run Clojure integration tests step runs
      `bb clojure:test:integration` (ci.yml:166), then asserts bb.edn's
      clojure:test:integration task structurally: `System/exit`-wrapped
      `(run-scry-kaocha-suite! "integration" ["--focus" "integration"])`
      (suite id "integration" + the integration focus preserved — a drift to
      another suite/focus fails loudly in `:unit`). Unit suite → 10 tests /
      48 assertions pass (was 9/39)
- [x] Single-source the byte-identical `which-*` resolution in
      lint_config_test_support.clj (reusable-existing-pattern flag — the same
      class slice 27 closed for parse-forms): `which-clojure-bin`
      (lines 181-186) and `which-git-bin` (lines 208-213) are structurally
      identical `(some-> (shell/sh "which" X) (as-> r (when (zero? (:exit r))
      (str/trim (:out r)))))` differing only in the binary name, and the ns's
      own contract is "each fixture is DEFINED here once" — a future hardening
      (quoting, error handling) or a regression would diverge silently between
      the two sites. Fix: extract a single private `which-bin` helper taking
      the binary name, with `which-clojure-bin`/`which-git-bin` delegating to
      it; behavior unchanged (same which → trim → nil-on-nonzero contract).
      — done: private `which-bin` extracted in lint_config_test_support.clj
      (the which → trim → nil-on-nonzero contract defined once); both
      `which-clojure-bin` and `which-git-bin` now delegate to it
      (`(which-bin "clojure")` / `(which-bin "git")`) — behavior unchanged
      (same which resolution, same nil-on-nonzero); the unit suite exercises
      both delegation paths via clojure-bin/git-bin (integration proofs ran,
      no SKIP — both binaries resolved through the single helper). Unit
      suite → 10 tests / 48 assertions pass

## Slice 29 — Implementation-review follow-ups (2026-08-15)

- [ ] Harden `ci-execution-chain-guard-test`'s task-args read against a dropped
      focus-args drift (ERROR → clean-FAIL class, slices 20/24): the
      clojure:test:integration guard asserts the run-scry-kaocha-suite! args via
      `(nth call 2)` — a drift that DROPS the `["--focus" "integration"]` args
      (e.g. `(run-scry-kaocha-suite! "integration")`, a two-element call) makes
      `(nth call 2)` throw IndexOutOfBoundsException (verified: `(nth
      '(run-scry-kaocha-suite! "integration") 2)` → IndexOutOfBoundsException),
      so the guard built to catch exactly that drift surfaces as a clojure.test
      ERROR with no assertion message instead of the plain FAIL — the exact
      ERROR-vs-FAIL class slices 20 (jar-entry nil → NPE → ERROR, nil-guarded)
      and 24 (slurp-before-exists → ERROR, when-guarded) closed elsewhere. Fix:
      `(is (= ["--focus" "integration"] (nth call 2 nil)))` — an out-of-bounds
      read yields nil and FAILs cleanly with the assertion message.
- [ ] Make `gitignore-http-kit-import-tracking-test`'s ordering assertion fail
      cleanly when a negation line is MISSING (same ERROR-vs-FAIL class): the
      presence `(is (some? neg-idx) …)` FAILs first, but the next
      `(is (< ignore-idx neg-idx) …)` then evaluates `(< 4 nil)` →
      NullPointerException (verified) — so the exact regression the test guards
      (a negation line removed) reports 1 FAIL + 1 ERROR, the ERROR masking the
      intended clean signal (slice-24 standard: "reports exactly ONE plain
      assertion FAIL with its message, never an ERROR"). Fix:
      `(is (and neg-idx (< ignore-idx neg-idx)) …)` so a missing negation line
      is a single clean FAIL.
- [ ] Guard the tracked-side slurp in `with-channel-hook-semantics-guard-test`
      against a deleted tracked impl (ERROR-vs-FAIL class; slice 24 hardened
      only the UNIT impl-guard): the ^:integration semantics guard slurps
      `.clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj`
      unconditionally in the let binding — a deleted worktree file (still in
      the git index, so the `git ls-files --error-unmatch` arm keeps passing —
      it checks the index, not worktree presence) throws FileNotFoundException
      → clojure.test ERROR, while the unit impl-guard reports the same drift as
      a clean FAIL. Mirror slice-24's shape: exists-guard the tracked slurp
      (assert existence, then read/compare only under `when (.exists …)`) so
      the integration suite FAILs cleanly on deletion instead of ERRORing
      redundantly on top of the unit guard's clean signal.

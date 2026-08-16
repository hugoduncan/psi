(ns psi.shared-config.lint-config-integration-test
  "^:integration proofs for the task-252 http-kit clj-kondo lint-config fix
  (split from lint-config-test to keep every file under the file-length gate).

  These tests need external infrastructure (the pinned http-kit jar, the pinned
  JVM clj-kondo artifact, the clojure CLI, and/or git) and are skipped — with a
  visible SKIP line via report-skip!, never a silent pass — when that
  infrastructure is absent. They run in CI via `bb clojure:test:integration`
  (tests.edn :integration suite, :focus-meta [:integration]) and are excluded
  from `bb test` via the :unit suite's :skip-meta [:integration].

  Fixtures are :refer'd from psi.shared-config.lint-config-test-support (the
  single definition site — no forwarding vars)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.shared-config.lint-config-test-support
    :refer [clj-kondo-deps
            clj-kondo-jar
            clj-kondo-main
            clojure-bin
            git-bin
            http-kit-jar
            parseable?
            repo-root
            run-bounded
            skip!]]
   [psi.test-support.fs :as test-fs])
  (:import
   [java.util.zip ZipFile]))

(defn- valid-zip?
  "True when the file opens as a zip archive (slice-30 follow-up): the
  semantics guard's skip guard previously checked only (.exists …), so a
  truncated/corrupt jar (partial m2 download, or a
  psi.lint-config-test.http-kit-jar override pointing at a non-jar file)
  passed the guard and (ZipFile. …) threw an uncaught ZipException →
  clojure.test ERROR with no assertion message. Opening the archive here —
  catching IOException, the ZipException superclass (also covers a directory
  at the path) — turns the corrupt-jar case into a visible SKIP via
  report-skip!, mirroring the missing-jar arm, never an uncaught exception."
  [f]
  (try
    (with-open [_ (ZipFile. (io/file f))] true)
    (catch java.io.IOException _ false)))

(deftest ^:integration with-channel-hook-semantics-guard-test
  (testing "the tracked with-channel hook impl is semantically identical to the
            pinned 2.8.0 jar's clj-kondo.exports export (slice-18 follow-up):
            with-channel-hook-impl-guard-test asserts the impl file exists and
            carries (ns httpkit.with-channel) + (defn with-channel …), but a
            semantically-changed transformation body (still a valid ns/defn —
            e.g. a no-op rewrite returning the node unchanged) passes every
            guard while silently mis-analyzing with-channel calls; the repo has
            zero with-channel call sites (slice-11 fact), so nothing exercises
            the hook and the drift is undetectable. This test reads the export
            from the pinned http-kit jar (the source of truth — same artifact
            the analysis-level proof lints) and compares it against the tracked
            impl as parsed forms (see parse-forms), so the documented cljfmt
            indentation drift (slice 5) stays green while any semantic change —
            including string-literal spacing (slice-20 follow-up: the previous
            whitespace-collapsing compare was blind to it) — fails loudly.
            Jar-absent → visible SKIP via report-skip!, mirroring the existing
            skip arms (http-kit jar is already injectable/nullable via
            psi.lint-config-test.http-kit-jar / the derived m2 path). A
            corrupt/truncated jar (or a jar-path override pointing at a
            non-jar file) also SKIPs visibly — the .exists check alone passes
            a corrupt jar, and the ZipFile open would throw an uncaught
            ZipException → clojure.test ERROR (slice-30 follow-up)."
    (if-let [reason (cond
                      (not (.exists (io/file http-kit-jar)))
                      (str http-kit-jar " not present")

                      (not (valid-zip? http-kit-jar))
                      (str http-kit-jar " is not a valid zip archive")

                      :else nil)]
      (is (skip! "with-channel hook semantics" reason))
      (let [jar-entry   "clj-kondo.exports/http-kit/http-kit/httpkit/with_channel.clj"
            tracked-rel ".clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj"
            tracked-file (io/file repo-root tracked-rel)
            jar-export (try
                         ;; slice-35 follow-up: the entry slurp must NOT throw
                         ;; inside the let binding — a valid-zip-with-corrupt-
                         ;; entry (bit rot, partial overwrite, bad artifact
                         ;; copy — the slice-30 valid-zip? arm validates only
                         ;; the CONTAINER, so this passes the guard) can make
                         ;; the entry slurp throw ZipException (inflate error)
                         ;; → clojure.test ERROR before any assertion. Guarded
                         ;; read → nil on IOException (the ZipException
                         ;; superclass) → the some? assertion below fails
                         ;; cleanly, never an uncaught exception.
                         (with-open [zf (ZipFile. (io/file http-kit-jar))]
                           (when-let [entry (.getEntry zf jar-entry)]
                             (slurp (.getInputStream zf entry))))
                         (catch java.io.IOException _ nil))]
        (is (some? jar-export)
            (str "the pinned http-kit jar contains the clj-kondo.exports export "
                 jar-entry))
        (testing "tracked impl file exists (slice-29 follow-up: the ^:integration
                  ls-files index arm checks the INDEX, not worktree presence, so
                  a deleted worktree file still passes it — the tracked-side
                  slurp previously ran unconditionally in the let binding and
                  threw FileNotFoundException → clojure.test ERROR on deletion,
                  while the unit impl-guard reports the same drift as a clean
                  FAIL. Mirror slice-24's shape: assert existence first, read/
                  compare only under `when` — a deleted tracked impl is a single
                  plain assertion FAIL here, never an ERROR)"
          (is (.isFile tracked-file) (str tracked-rel " is a regular file")))
        (testing "tracked impl is semantically identical to the jar export
                  (parsed-form compare — whitespace/indentation-insensitive,
                  string-literal-sensitive; see parse-forms)"
          (when (and (some? jar-export) (.isFile tracked-file))
            ;; slice-42 follow-up: `.isFile` (not `.exists`) in the guard and
            ;; the exists assertion above — .exists returns true for a
            ;; DIRECTORY, so a directory at the tracked path would pass an
            ;; exists-guard and the tracked-side slurp below would throw
            ;; FileNotFoundException "(Is a directory)" outside any try →
            ;; clojure.test ERROR (the ERROR-vs-FAIL class closed for
            ;; deletion/parse elsewhere, still open at the slurp boundary).
            ;; .isFile is false for both missing AND directory — closes both
            ;; classes in one predicate, so the slurp never sees a directory.
            ;; nil-guards (slice-20 + slice-29 follow-ups): the some? and
            ;; exists assertions above fail cleanly when the jar entry is
            ;; missing or the tracked impl is deleted; without the guards the
            ;; equality would throw an NPE/FileNotFoundException and surface as
            ;; a clojure.test ERROR instead of the plain assertion failures it
            ;; deserves. slice-34 follow-up: a PRESENT-but-unparseable tracked
            ;; impl (bad merge, hand-edit truncation, encoding corruption — the
            ;; file exists, so the exists-guard passes) would make parse-forms'
            ;; read-string throw inside the `when` → ERROR with no assertion
            ;; message (the unit impl-guard is the same class); the shared
            ;; parseable? fixture (guarded parse, nil on unparseable — single
            ;; definition site in the support ns) turns the corruption class
            ;; into this clean assertion FAIL, never an ERROR. slice-35
            ;; follow-up: the JAR-EXPORT side is the mirror blind spot
            ;; slice-34 explicitly deferred ("the integration site's jar-export
            ;; side keeps the direct parse-forms call — pinned-jar data,
            ;; zip-validated by the slice-30 valid-zip? arm") — a valid-zip-
            ;; with-corrupt-entry whose entry slurps to garbage-but-slurpable
            ;; content (verified 2026-08-16: byte flipped in the deflate
            ;; stream → the direct (parse-forms jar-export) threw "Unmatched
            ;; delimiter: )" → clojure.test ERROR) makes the direct parse
            ;; throw at the equality. Parse jar-export through the shared
            ;; parseable? fixture and assert some? (clean FAIL with message)
            ;; before the equality, mirroring the tracked-forms guard directly
            ;; above — never an uncaught exception.
            (let [export-forms (parseable? jar-export)]
              (testing "jar export parses as Clojure forms"
                (is (some? export-forms)
                    (str "jar export " jar-entry " parses as Clojure forms")))
              (when export-forms
                (let [tracked-forms (parseable? (slurp tracked-file))]
                  (testing "tracked impl parses as Clojure forms"
                    (is (some? tracked-forms)
                        (str tracked-rel " parses as Clojure forms")))
                  (when tracked-forms
                    (is (= export-forms tracked-forms)
                        "tracked with_channel.clj differs from the pinned jar export")))))))))))

(deftest ^:integration http-kit-import-config-jar-export-guard-test
  (testing "the tracked import config.edn equals the pinned 2.8.0 jar's
            clj-kondo.exports export minus the tracked-only :lint-as additive
            diff (slice-40 + slice-41 follow-ups): the tracked import dir holds
            TWO files — config.edn (the ACTUAL mechanism: the :lint-as defreq
            registration AND the :hooks :analyze-call with-channel mapping) and
            httpkit/with_channel.clj (the hook impl). Only the impl has the
            ^:integration jar-export comparison
            (with-channel-hook-semantics-guard-test); config.edn is asserted
            ONLY against hardcoded values
            (http-kit-import-registration-test's exact :lint-as / :hooks
            equalities read the UNCHANGED tracked copy, so they stay green on
            any bump-induced drift). On an http-kit version bump where the
            jar's export config.edn changes (hook renamed, new hook/entry
            added, a NEW top-level key), the tracked copy silently diverges:
            the unit assertions keep passing (they assert the tracked copy,
            not the jar), the impl semantics guard keeps passing (the impl
            file at the same path is unchanged), and the hook is never
            exercised → silent drift, exactly the slice-18 class. This test
            reads the jar's export config.edn through a guarded read
            (slice-35 IOException-guard shape), parses it as EDN through a
            guarded parse (clean FAIL, never an ERROR, mirror of the
            parseable? shape), and asserts the tracked config.edn equals the
            export minus the tracked-only :lint-as additive diff — strictly
            stronger than the slice-40 :hooks-only equality (covers :hooks
            AND any other top-level key, since :hooks is a subset of the full
            map) while preserving the additive-diff semantics: the tracked-
            only :lint-as stays the unit test's exact-equality assertion, and
            an export-side :lint-as of its own FAILs loudly, demanding the
            reconciliation the R4 bump set requires. Jar-absent /
            corrupt-jar → visible SKIP via skip!, mirroring the sibling
            guards."
    (if-let [reason (cond
                      (not (.exists (io/file http-kit-jar)))
                      (str http-kit-jar " not present")

                      (not (valid-zip? http-kit-jar))
                      (str http-kit-jar " is not a valid zip archive")

                      :else nil)]
      (is (skip! "http-kit import config jar-export guard" reason))
      (let [jar-entry    "clj-kondo.exports/http-kit/http-kit/config.edn"
            tracked-rel  ".clj-kondo/imports/http-kit/http-kit/config.edn"
            tracked-file (io/file repo-root tracked-rel)
            jar-export   (try
                           ;; slice-35 IOException-guard shape: a valid-zip-with-
                           ;; corrupt-entry (or any entry-read failure) returns
                           ;; nil → the some? assertion below fails cleanly,
                           ;; never an uncaught ZipException → clojure.test
                           ;; ERROR.
                           (with-open [zf (ZipFile. (io/file http-kit-jar))]
                             (when-let [entry (.getEntry zf jar-entry)]
                               (slurp (.getInputStream zf entry))))
                           (catch java.io.IOException _ nil))]
        (is (some? jar-export)
            (str "the pinned http-kit jar contains the clj-kondo.exports export "
                 jar-entry))
        (testing "jar export parses as EDN (guarded parse — clean FAIL, never an
                  ERROR, mirror of the parseable? shape)"
          (let [export-config (try (edn/read-string jar-export)
                                   (catch Exception _ nil))]
            (is (some? export-config)
                (str "jar export " jar-entry " parses as EDN"))
            (when (some? export-config)
              (testing "tracked config.edn exists (mirror of the tracked impl
                        exists-guard in the sibling semantics test)"
                (is (.exists tracked-file) (str tracked-rel " exists")))
              (testing "tracked config.edn equals the jar export minus the
                        tracked-only :lint-as additive diff (the :lint-as defreq
                        registration is the tracked-only additive — the unit
                        test asserts it exactly; an export-side :lint-as or any
                        other NEW top-level export key FAILs here, demanding
                        reconciliation)"
                (when (.exists tracked-file)
                  (let [tracked-config (try (edn/read-string (slurp tracked-file))
                                            (catch Exception _ nil))]
                    (testing "tracked config.edn parses as EDN"
                      (is (some? tracked-config) (str tracked-rel " parses as EDN")))
                    (when (some? tracked-config)
                      (is (= export-config (dissoc tracked-config :lint-as))
                          (str tracked-rel " differs from the pinned jar "
                               "export " jar-entry " beyond the tracked-only "
                               ":lint-as additive diff")))))))))))))

(deftest ^:integration gitignore-http-kit-tracking-ground-truth-test
  (testing "git's own interpretation of the tracking negation matches the text
            test (slice-13 follow-up): gitignore-http-kit-import-tracking-test
            proves the three lines exist verbatim in the right order but never
            runs git, so git interpreting the patterns differently (a shadowing
            re-ignore elsewhere, a pattern-semantics drift, a typo git reads
            differently than the text test) passes while the import dir silently
            drops out of commits. Ground truth — the slice-1 manual check:
            http-kit config.edn must NOT be ignored (exit 1) and the malli
            sibling must be ignored (exit 0). Slice-14: the git binary is an
            injectable/nullable infra dep (psi.lint-config-test.git-bin
            override, else `which git`) and the SAME resolved git-bin feeds
            both the skip guard and the executed subprocess (bounded via
            run-bounded — shell/sh has no :timeout). Slice-15: the skip guard
            also skips on a nonexistent git-bin (mirror of the http-kit/
            clj-kondo jar arms) so a stale override or which result is a
            visible SKIP, not a loud subprocess error — and the SKIP reason
            reaches runner output via report-skip!."
    (if-let [reason (cond
                      (nil? git-bin)
                      "git not on PATH"

                      (not (.exists (io/file git-bin)))
                      (str git-bin " not present")

                      :else nil)]
      (is (skip! "git check-ignore ground truth" reason))
      (let [http-kit-rel ".clj-kondo/imports/http-kit/http-kit/config.edn"
            malli-rel    ".clj-kondo/imports/metosin/malli/config.edn"
            check        (fn [rel]
                           (run-bounded [git-bin "check-ignore" "-v" rel]))]
        (testing "http-kit import config is NOT ignored (tracked — the negation works)"
          (let [{:keys [exit out err]} (check http-kit-rel)]
            (is (not (zero? exit))
                (str "git check-ignore " http-kit-rel " exits non-zero (not ignored)"
                     "; out: " (str/trim out) " err: " (str/trim err)))))
        (testing "malli sibling import config IS ignored (the ignore-all rule still applies)"
          (let [{:keys [exit out]} (check malli-rel)]
            (is (zero? exit)
                (str "git check-ignore " malli-rel " exits zero (ignored)"
                     "; out: " (str/trim out)))
            (is (str/includes? out ".gitignore:")
                "match comes from .gitignore (matched rule reported in -v output)")))
        (testing "the http-kit import files are TRACKED in the git index, not
                  just not-ignored (slice-16 follow-up): the text test proves
                  the negation lines and the check-ignore arm above prove git
                  does not ignore them, but \"not ignored\" ≠ \"tracked\" — a
                  `git rm --cached` of the import config.edn (+ the hook impl)
                  keeps every existing guard green (all read from disk;
                  check-ignore still exits 1) while silently dropping the
                  registration from future commits. git ls-files --error-unmatch
                  exits 0 only when every listed path is in the index."
          (let [{:keys [exit out err]}
                (run-bounded [git-bin "ls-files" "--error-unmatch"
                              ".clj-kondo/imports/http-kit/http-kit/config.edn"
                              ".clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj"])]
            (is (zero? exit)
                (str "both http-kit import files are tracked in the git index"
                     "; out: " (str/trim out) " err: " (str/trim err)))))))))

(deftest ^:integration http-kit-defreq-analysis-level-resolution-test
  (testing "defreq verbs resolve via cache-driven analysis: probe + real AC1 file
            (extensions/dev-http/test/extensions/dev_http_test.clj, lines 572/737)
            clean with the registration cache; real file warns against a
            no-registration cache (discriminating control, slice-10 follow-up)"
    (if-let [reason (or (cond
                          (nil? clojure-bin)
                          "clojure CLI binary not on PATH (clj-kondo subprocess cannot run)"

                          ;; slice-34 follow-up: the .exists arm mirrors the
                          ;; git-bin guard (slice-15: both nil? and .exists
                          ;; arms) and the http-kit/clj-kondo jar arms — a
                          ;; stale psi.lint-config-test.clojure-bin override
                          ;; (the documented injection use case — an
                          ;; editor/nrepl runner whose CLI path went stale) or
                          ;; a `which clojure` result pointing at a
                          ;; since-deleted binary passed the guard, then
                          ;; ProcessBuilder.start threw IOException
                          ;; (FileNotFound wrapped) BEFORE run-bounded's try →
                          ;; clojure.test ERROR with no assertion message.
                          ;; Visible SKIP, mirroring the git-bin arm's message
                          ;; shape.
                          (not (.exists (io/file clojure-bin)))
                          (str clojure-bin " not present")

                          (not (.exists (io/file http-kit-jar)))
                          (str http-kit-jar " not present")

                          (not (.exists (io/file clj-kondo-jar)))
                          (str clj-kondo-jar " not present (pinned clj-kondo artifact)"))

                        ;; slice-33 follow-up: clj-kondo-local-repo throws a clear
                        ;; ex-info when the guarded jar path is not in the standard
                        ;; m2 layout and no local-repo property is set — the throw
                        ;; fired inside clj-kondo-deps while building the
                        ;; -Sdeps/-Spath command vectors AFTER this skip guard had
                        ;; passed, so a valid jar at a non-m2-layout override path
                        ;; surfaced as a clojure.test ERROR with no assertion
                        ;; message (slice-16 recorded that error as accepted,
                        ;; predating the ERROR-vs-FAIL/SKIP standard slices
                        ;; 20/24/29/30/31/32; slice-30's corrupt-jar override →
                        ;; visible SKIP via valid-zip? is the direct precedent for
                        ;; a bad jar-path override, and a non-standard-m2 layout
                        ;; is exactly the documented use case for the jar override
                        ;; — a CI home with a custom artifact layout). Fold the
                        ;; derivation into the skip guard via `or`: the cond's
                        ;; :else nil falls through to the try, which returns nil
                        ;; on success (proceed) or the derivation ex-message as
                        ;; the visible SKIP reason (mirror of the valid-zip?
                        ;; arm) — evaluated ONCE, never a clojure.test ERROR. The
                        ;; body's later (clj-kondo-deps) calls (the -Spath arm
                        ;; and clj-kondo-main) are then guaranteed to succeed.
                        (try (clj-kondo-deps) nil
                             (catch clojure.lang.ExceptionInfo e
                               (ex-message e))))]
      (is (skip! "analysis-level proof" reason))
      (let [tmp          (doto (java.io.File/createTempFile "ck252" "")
                           (.delete)
                           (.mkdirs))
            cache-dir    (str (io/file tmp "cache"))
            no-reg-dir   (str (io/file tmp "no-reg-cache"))
            empty-config (doto (io/file tmp "empty-config") (.mkdirs))
            probe        (io/file tmp "probe.clj")
            ;; AC1's literal acceptance surface (design.md AC1 / design-step 9):
            ;; the ACTUAL dev-http test file, not just a synthetic probe.
            real-file    "extensions/dev-http/test/extensions/dev_http_test.clj"]
        (try
          (testing "the -Sdeps map resolves the EXACT guarded clj-kondo jar
                    (slice-16 guard/exec agreement): clj-kondo-main resolves the
                    artifact via the -Sdeps map, which now carries
                    :mvn/local-repo derived from the guarded clj-kondo-jar path
                    (see clj-kondo-local-repo) — previously the subprocess used
                    the Clojure CLI's own local repo while the skip guard
                    checked a path never passed to it, so a custom-path
                    override could pass the guard yet execute a different
                    artifact or trigger a download. -Spath must therefore
                    contain the guarded jar."
            (let [{:keys [exit out]}
                  (run-bounded [clojure-bin "-Sdeps" (clj-kondo-deps) "-Spath"])]
              (is (zero? exit) (str "-Spath succeeds: " out))
              (is (str/includes? out clj-kondo-jar)
                  (str "-Spath resolves the guarded jar " clj-kondo-jar))))
          (spit probe
                (str "(ns probe\n"
                     "  (:require [org.httpkit.client :as http-client]))\n"
                     "\n"
                     "(defn exercise []\n"
                     "  @(http-client/get \"http://example.com\")\n"
                     "  @(http-client/post \"http://example.com\")\n"
                     "  @(http-client/definitely-not-a-var))\n"))
          (testing "jar analysis populates a hermetic cache (repo imports config active)"
            (let [{:keys [exit]} (clj-kondo-main "--lint" http-kit-jar
                                                 "--dependencies"
                                                 "--cache-dir" cache-dir)]
              (is (zero? exit))
              (is (.exists (io/file cache-dir "v1/clj/org.httpkit.client.transit.json")))))
          (testing "probe lint resolves get/post and flags the bogus var"
            (let [{:keys [exit out]}
                  (clj-kondo-main "--lint" (str probe) "--cache-dir" cache-dir)]
              (is (not (zero? exit)) "findings present ⇒ non-zero exit")
              (is (not (str/includes? out "Unresolved var: http-client/get")))
              (is (not (str/includes? out "Unresolved var: http-client/post")))
              (is (str/includes? out "Unresolved var: http-client/definitely-not-a-var"))))
          (testing "real AC1 file lints clean against the registration cache
                    (guards require/alias changes and calls to vars OUTSIDE the
                    registered defreq verb set — e.g. definitely-not-a-var. It
                    does NOT guard added calls to registered verbs: delete is in
                    the full defreq set and resolves, so an added
                    http-client/delete call does not fail here — slice-13
                    correction)"
            (let [{:keys [exit out]}
                  (clj-kondo-main "--lint" real-file "--cache-dir" cache-dir)]
              (is (zero? exit) (str "real-file lint clean: " out))
              (is (not (str/includes? out "Unresolved var: http-client/get")))
              (is (not (str/includes? out "Unresolved var: http-client/post")))))
          (testing "discriminating control: no-reg cache (--config-dir empty, so the
                    repo imports config is NOT merged; clj-kondo 2025.09.19 NPEs
                    config_dir is null without --config-dir) carries the slice-2
                    verb-set proxy — ~$request but not ~$get/~$post"
            (let [{:keys [exit]} (clj-kondo-main "--lint" http-kit-jar
                                                 "--dependencies"
                                                 "--config-dir" (str empty-config)
                                                 "--cache-dir" no-reg-dir)]
              (is (zero? exit))
              (let [transit-file (io/file no-reg-dir
                                          "v1/clj/org.httpkit.client.transit.json")]
                ;; slice-30 follow-up: the slurp previously ran unconditionally
                ;; — a failed jar --dependencies analysis (e.g. corrupt jar —
                ;; the .exists skip guard passes it; the subprocess exits
                ;; non-zero and never writes the transit) threw
                ;; FileNotFoundException → clojure.test ERROR, masking the
                ;; clean FAIL the exit assertion already reported. Mirror the
                ;; reg-cache arm's exists guard (slice-2 pattern): assert
                ;; existence (clean FAIL), then read only under `when`, so a
                ;; failed jar analysis surfaces as plain assertion FAILs,
                ;; never an uncaught exception.
                (is (.exists transit-file)
                    "no-reg transit exists (jar --dependencies analysis wrote the cache)")
                (when (.exists transit-file)
                  (let [transit (slurp transit-file)]
                    (is (str/includes? transit "~$request")
                        "no-reg cache still carries the plain defn request")
                    (is (not (str/includes? transit "~$get"))
                        "no-reg cache carries NO defreq-generated get")
                    (is (not (str/includes? transit "~$post"))
                        "no-reg cache carries NO defreq-generated post"))))))
          (testing "real AC1 file reports the two warnings against the no-reg cache
                    (proves the clean arm above is registration-driven, not
                    trivially clean — an empty cache is trivially clean per
                    design-step 9, and --config '{:lint-as {}}' auto-merges the
                    imports config regardless)"
            (let [{:keys [exit out]}
                  (clj-kondo-main "--lint" real-file "--cache-dir" no-reg-dir)]
              (is (not (zero? exit)) "findings present ⇒ non-zero exit")
              (is (str/includes? out "Unresolved var: http-client/get")
                  "no-reg cache ⇒ line 572 get warning")
              (is (str/includes? out "Unresolved var: http-client/post")
                  "no-reg cache ⇒ line 737 post warning")
              (is (str/includes? out "errors: 0, warnings: 2")
                  "no-reg cache ⇒ exactly the two warnings (AC1 baseline shape)")))
          (finally
            ;; slice-36 follow-up (item 3, branch (a)): the cleanup calls the
            ;; SHARED psi.test-support.fs helper directly — the support ns's
            ;; delegation wrapper was a forwarding var by its own
            ;; "No forwarding vars" contract and is deleted (the single
            ;; definition site is then literally true).
            (test-fs/delete-recursively! tmp)
            ;; slice-36 follow-up (item 2): the shared helper ignores
            ;; `.delete`'s boolean return (false when the file/dir is in use
            ;; — e.g. a platform/timing-dependent handle held by the clj-kondo
            ;; JVM subprocess, or a child deletion failure), so a failed
            ;; cleanup would silently leak the /tmp/ck252* hermetic cache tree
            ;; while the proof still passes — the exact leak class slice 12
            ;; set out to eliminate (its addressing note records 9 pre-existing
            ;; leaked dirs; the fix removed the *always*-leak but made the
            ;; *occasional*-leak invisible). Assert the tree is gone (clean
            ;; FAIL with message) so a failed cleanup is a visible test
            ;; failure, never a silent recurrence.
            (is (not (.exists (io/file tmp)))
                (str "temp tree cleaned up: " tmp " removed"))))))))

(ns psi.shared-config.lint-config-test
  "Regression tests for repo-level clj-kondo lint-config invariants (task 252).

  The lint surface itself cannot guard these: CI `bb lint` runs with no
  `.clj-kondo/.cache` and never analyzes the http-kit jar, so a removed or
  typo'd `:lint-as` entry (the `defreq` registration) and an AC2 root-config
  drift are undetectable by `bb test`/CI. These tests read the config files as
  EDN only — no jar analysis, no cache — so they run anywhere.

  Hosted in shared-config: no component owns lint config (the follow-up's
  placement decision); shared-config is the closest semantic home and its test
  dir is already in the :unit suite, so no tests.edn change is needed. The
  assertion code deliberately lives outside `.clj-kondo/imports/` (AC2
  confinement).

  Unit invariants only (no subprocesses): the three ^:integration proofs live
  in psi.shared-config.lint-config-integration-test, and every shared fixture
  is :refer'd from psi.shared-config.lint-config-test-support (the single
  definition site — no forwarding vars)."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.shared-config.lint-config-test-support
    :refer [http-client-entries
            http-kit-version
            parseable?
            read-edn
            repo-root]]))

(defn- ci-run-steps
  "Parse the GitHub Actions steps from ci.yml lines into a map of
  step-name → run-command. Every `- name: X` line is paired with the NEXT
  `run: Y` line (the step's command); steps without a run (e.g. `uses:`-only)
  are skipped. Mirrors how the .gitignore test reads lines — no YAML parser,
  no subprocess — so it runs anywhere."
  [lines]
  (let [acc (reduce (fn [{:keys [pending] :as acc} line]
                      (let [t (str/trim line)]
                        (cond
                          (str/starts-with? t "- name: ")
                          (assoc acc :pending (subs t (count "- name: ")))
                          (and pending (str/starts-with? t "run: "))
                          (-> acc
                              (assoc :pending nil)
                              (update :steps assoc pending (subs t (count "run: "))))
                          :else acc)))
                    {:steps {} :pending nil}
                    lines)]
    (:steps acc)))

(deftest http-kit-import-registration-test
  (testing "http-kit import config retains the defreq :lint-as registration"
    (let [cfg (read-edn ".clj-kondo/imports/http-kit/http-kit/config.edn")]
      (is (= '{org.httpkit.client/defreq clojure.core/def} (:lint-as cfg)))
      (testing "server-side with-channel hook is preserved exactly alongside it
                (the design mechanism requires the existing hook be kept)"
        (is (= '{:analyze-call {org.httpkit.server/with-channel
                                httpkit.with-channel/with-channel}}
               (:hooks cfg)))))))

(deftest with-channel-hook-impl-guard-test
  (testing "the with-channel hook implementation file exists and matches the
            config.edn :hooks :analyze-call reference (slice-11 follow-up):
            the repo has zero with-channel call sites and neither bb lint nor
            the ^:integration proof ever loads the httpkit.with-channel
            namespace (the jar arm never fires analyze-call — no calls are
            analyzed), so a deleted or renamed hook impl is otherwise
            undetectable by bb test/CI. Reads the impl through the shared
            parse-forms fixture (*read-eval* false so a `#=` reader-eval form
            throws instead of evaluating, :read-cond :preserve — slice-21) —
            no subprocess — and the assertion code lives outside the import
            dir (AC2 confinement). Slice-27: the parse uses the :refer'd
            shared fixture rather than an inline copy, so the unit suite
            exercises the same parse-forms the ^:integration semantics guard
            relies on (a future hardening — or regression — cannot diverge
            between the two sites)."
    (let [impl-rel  ".clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj"
          impl-file (io/file repo-root impl-rel)
          cfg       (read-edn ".clj-kondo/imports/http-kit/http-kit/config.edn")
          ref       (get-in cfg [:hooks :analyze-call 'org.httpkit.server/with-channel])]
      (testing "config.edn :analyze-call maps with-channel to httpkit.with-channel/with-channel"
        (is (= 'httpkit.with-channel/with-channel ref)))
      (testing "impl file exists (member of the slice-4 tracked change set)"
        (is (.exists impl-file) (str impl-rel " exists")))
      ;; slice-24 follow-up: the slurp must NOT run in a let binding before the
      ;; exists assertion — a deleted/renamed impl would otherwise throw
      ;; FileNotFoundException at read time and surface as a clojure.test ERROR
      ;; with the exists assertion unreachable (same class of defect slice 20
      ;; fixed in the ^:integration semantics guard: missing-jar-entry nil →
      ;; NPE → ERROR, nil-guarded to a plain assertion failure). The exists
      ;; check above fails cleanly; the parse (and the ns/defn assertions that
      ;; depend on it) is guarded by `when`, so the deletion case reports
      ;; exactly ONE plain assertion FAIL with its message, never an ERROR.
      ;; slice-34 follow-up: a PRESENT-but-unparseable impl (bad merge,
      ;; hand-edit truncation, encoding corruption — the file exists, so the
      ;; exists-guard passes) would make parse-forms' read-string throw inside
      ;; the `when` → ERROR with no assertion message; the shared parseable?
      ;; fixture (guarded parse, nil on unparseable — single definition site in
      ;; the support ns) turns the corruption class into this clean assertion
      ;; FAIL, never an ERROR.
      (when (.exists impl-file)
        (let [forms (parseable? (slurp impl-file))]
          (testing "impl parses as Clojure forms"
            (is (some? forms)
                (str impl-rel " parses as Clojure forms")))
          (when forms
            (testing "impl ns is httpkit.with-channel — matches the reference's namespace"
              (is (some (fn [f] (and (seq? f) (= 'ns (first f))
                                     (= 'httpkit.with-channel (second f))))
                        forms)))
            (testing "impl defines (defn with-channel …) — matches the reference's var"
              (is (some (fn [f] (and (seq? f) (= 'defn (first f))
                                     (= 'with-channel (second f))))
                        forms)))))))))

(deftest root-config-ac2-invariant-test
  (testing "root config keeps AC2 invariants (no http-client drift)"
    (let [cfg-file (io/file repo-root ".clj-kondo/config.edn")]
      ;; slice-31 follow-up: the read-edn slurp must NOT run in the let
      ;; binding before an exists assertion — a deleted root config throws
      ;; FileNotFoundException → clojure.test ERROR with no assertion message
      ;; (the ERROR-vs-FAIL standard slices 20/24/29/30 closed elsewhere).
      ;; Slice-24's decline of the parallel shape applied ONLY to the import
      ;; config.edn (covered by the git ls-files --error-unmatch index arm,
      ;; which checks .clj-kondo/imports/); the ROOT config has no backstop —
      ;; no ^:integration test reads it, and its deletion is a SILENT drift
      ;; (clj-kondo falls back to defaults: the malli `=>` exclude and every
      ;; AC2 invariant vanish with zero signal from bb lint/CI), so the only
      ;; guard ERRORing instead of clean-FAILing is worse here, not better.
      ;; Assert existence first (clean FAIL with message), then read/assert
      ;; only under `when` (mirror of slice-24's shape).
      (testing "root config exists"
        (is (.exists cfg-file) ".clj-kondo/config.edn exists"))
      (when (.exists cfg-file)
        (let [cfg (read-edn ".clj-kondo/config.edn")]
          (testing ":unresolved-symbol :exclude remains exactly [(malli.core/=>)]"
            (is (= '[(malli.core/=>)]
                   (get-in cfg [:linters :unresolved-symbol :exclude]))))
          (testing ":lint-as does not mirror the http-kit defreq registration
                    (plan.md decision 1's no-root-mirror choice; defreq is never
                    invoked in-repo, unlike the malli/promesa mirror convention)"
            (is (not (contains? (:lint-as cfg) 'org.httpkit.client/defreq))))
          (testing "no org.httpkit.client entry anywhere in the root config
                    (AC2's general 'gains no http-client entries' clause — the EDN
                    walk covers :lint-as, :hooks, :namespaces, and any other
                    symbol/keyword-bearing spot, so e.g. a root :hooks :analyze-call
                    entry for an http-kit var fails here)"
            (is (empty? (http-client-entries cfg))
                "root config carries no http-client symbol or keyword")))))))

(deftest clj-kondo-pin-sourced-from-deps-edn-test
  (testing "the analysis-level proof's clj-kondo version is derived from deps.edn
            :lint :extra-deps, so a clj-kondo bump fails loudly instead of
            silently re-proving a stale pin"
    (let [pin (get-in (read-edn "deps.edn")
                      [:aliases :lint :extra-deps 'clj-kondo/clj-kondo])]
      (is (some? pin) "deps.edn :lint :extra-deps pins clj-kondo/clj-kondo")
      (is (some? (:mvn/version pin))))))

(deftest http-kit-pin-sourced-from-deps-edn-test
  (testing "the analysis-level proof's http-kit jar is derived from deps.edn
            :deps, so an http-kit bump fails loudly instead of silently
            re-proving a stale jar or silently skipping (mirror of the
            clj-kondo pin derivation)"
    (let [pin (get-in (read-edn "deps.edn") [:deps 'http-kit/http-kit])]
      (is (some? pin) "deps.edn :deps pins http-kit/http-kit")
      (is (some? (:mvn/version pin))))
    (testing "the extension pin — the classpath dev-http actually runs against —
              matches the root-derived pin (slice-11 follow-up): a drift in
              extensions/dev-http/deps.edn (e.g. a bump to 2.9.0 while root
              stays 2.8.0) otherwise yields zero signal from any test, since
              http-kit-pin-sourced-from-deps-edn-test and the ^:integration
              proof both derive from ROOT deps.edn only"
      (let [ext-pin (get-in (read-edn "extensions/dev-http/deps.edn")
                            [:deps 'http-kit/http-kit])]
        (is (some? ext-pin) "extensions/dev-http/deps.edn pins http-kit/http-kit")
        (is (= http-kit-version (:mvn/version ext-pin))
            (str "extension pin " (:mvn/version ext-pin)
                 " equals root-derived http-kit-version " http-kit-version))))))

(deftest lint-alias-lints-extensions-test
  (testing "the :lint alias still lints the AC1 acceptance surface (slice-13
            follow-up): http-kit-defreq-analysis-level-resolution-test invokes
            clj-kondo.main directly with explicit --lint args (bypassing
            deps.edn [:aliases :lint :main-opts]) and bb.edn's lint task is a
            trivial `clojure -M:lint` wrapper, so a narrowing change that drops
            \"extensions\" (or renames the dev-http path) from the alias's path
            set would silently remove the two warnings from the acceptance
            surface while every test still passes. Read deps.edn as EDN — runs
            anywhere, no subprocess (mirror of the pin-derivation tests)."
    (let [main-opts (get-in (read-edn "deps.edn") [:aliases :lint :main-opts])]
      (is (some? main-opts) "deps.edn :lint alias has :main-opts")
      (is (some #{"extensions"} main-opts)
          ":lint :main-opts contains \"extensions\" (the dev-http test file lives under it)")
      (is (some #{"bb.edn"} main-opts)
          ":lint :main-opts contains \"bb.edn\"")
      (testing "no cache-disabling / config-override flags (slice-15 follow-up:
                lint-alias-lints-extensions-test's path presence assertions are
                blind to a drift that makes AC1 trivially clean — adding
                --cache false (with no cache the two warnings vanish — exactly
                design-step 9's masking), --config/--config-dir overrides, or
                --dependencies to the alias's :main-opts silently disables the
                lint proof while every other test still passes. bb-edn-lint-task-wrapper-test
                closes this for the bb.edn WRAPPER only ((shell \"clojure -M:lint\")
                exact), not for the alias itself)"
        (doseq [flag ["--cache" "--config" "--config-dir" "--dependencies"]]
          (is (not (some #{flag} main-opts))
              (str ":lint :main-opts must not contain " flag " (masks the AC1 warnings)")))))))

(deftest gitignore-http-kit-import-tracking-test
  (testing ".gitignore keeps the http-kit import dir TRACKED (plan.md decision 2
            / slice 1 / slice-4 change set): the negation lines that exempt
            `.clj-kondo/imports/http-kit/**` from the ignore-all rule.
            Nothing else tests this — `http-kit-import-registration-test` reads
            config.edn from disk, so if the negation is removed (restoring
            `**/.clj-kondo/imports/`) the file still exists locally, the unit
            suite passes, and the registration silently drops out of future
            commits. Read as text — no subprocess — so it runs anywhere."
    (let [ignore-all "**/.clj-kondo/imports/*"
          negations ["!.clj-kondo/imports/http-kit/"
                     "!.clj-kondo/imports/http-kit/**"]
          lines      (str/split-lines (slurp (io/file repo-root ".gitignore")))
          index-of   (fn [pattern] (first (keep-indexed
                                           (fn [i l] (when (= pattern l) i))
                                           lines)))
          last-index-of (fn [pattern] (last (keep-indexed
                                             (fn [i l] (when (= pattern l) i))
                                             lines)))]
      (testing "all three tracking lines are present verbatim"
        (doseq [pattern (into [ignore-all] negations)]
          (is (some #{pattern} lines)
              (str ".gitignore contains the tracking line " pattern))))
      (testing "ignore-all precedes both negations (gitignore is last-match-wins:
                if `**/.clj-kondo/imports/*` moved BELOW the negation lines, git
                would re-ignore the http-kit import dir — the registration
                silently drops out of future commits — while all three lines
                still exist and a presence-only test passes). Slice-24: the
                ordering uses the LAST ignore-all occurrence — a duplicate
                `**/.clj-kondo/imports/*` added BELOW the negations re-ignores
                the http-kit dir under last-match-wins while the FIRST
                occurrence is still above them, so a first-occurrence compare
                passes the guard while git silently drops the registration"
        (let [ignore-idx (last-index-of ignore-all)]
          (is (some? ignore-idx) "ignore-all pattern present (index found)")
          (doseq [negation negations]
            (let [neg-idx (index-of negation)]
              (is (some? neg-idx) (str "negation line present (index found): " negation))
              ;; slice-29 follow-up: when the negation line is MISSING the
              ;; presence assertion above FAILs first, but the ordering
              ;; assertion then evaluates `(< ignore-idx nil)` →
              ;; NullPointerException, so the exact regression this test
              ;; guards (a negation line removed) reports 1 FAIL + 1 ERROR —
              ;; the ERROR masking the intended clean signal (slice-24
              ;; standard: exactly ONE plain assertion FAIL with its message,
              ;; never an ERROR). `(and neg-idx …)` short-circuits on the
              ;; missing line (nil is falsy) into a single clean FAIL; the
              ;; message is nil-guarded too (`(inc nil)` in the message would
              ;; itself throw on the failure path). Slice-32: the symmetric
              ;; mirror blind spot — a MISSING ignore-all line (deleted/
              ;; renamed `**/.clj-kondo/imports/*`, the drift slice 24's
              ;; last-occurrence ordering was built to catch) leaves the
              ;; negations as no-ops (malli and the whole import dir fall
              ;; back to TRACKED — an untracked malli config.edn would enter
              ;; commits) while `(< ignore-idx neg-idx)` would evaluate
              ;; `(< nil N)` → NullPointerException. `(and ignore-idx …)`
              ;; short-circuits exactly like slice-29's negation arm; the
              ;; message's `(inc ignore-idx)` is nil-guarded likewise. The
              ;; ^:integration check-ignore malli arm backstops the drift
              ;; (malli no longer ignored → its exit-0 assertion fails), but
              ;; the unit ERROR-vs-FAIL standard applies regardless.
              (is (and ignore-idx neg-idx (< ignore-idx neg-idx))
                  (str ignore-all
                       (when ignore-idx
                         (str " (last occurrence, line " (inc ignore-idx) ")"))
                       " precedes " negation
                       (when neg-idx (str " (line " (inc neg-idx) ")")))))))))))

(deftest bb-edn-lint-task-wrapper-test
  (testing "bb.edn's lint task remains the plain `clojure -M:lint` wrapper
            (slice-14 follow-up): AC1's local proof surface is `bb lint` ≡
            `clojure -M:lint`, and lint-alias-lints-extensions-test guards only
            deps.edn's :lint :main-opts — nothing guards bb.edn's lint task
            (:tasks lint, bb.edn:242-244). If the wrapper drifts — e.g. adds
            --cache false (with no cache the two warnings vanish — exactly
            design-step 9's masking), adds --config overrides, or switches to
            the native clj-kondo binary — the local AC1 gate becomes trivially
            clean while every other test still passes. Read bb.edn as EDN — no
            subprocess, runs anywhere. (bb.edn task names are symbols, so the
            entry's key is `lint`, not :lint.)"
    (let [bb   (read-edn "bb.edn")
          task (:task (get-in bb [:tasks 'lint]))]
      (is (some? task) "bb.edn defines a :tasks lint entry")
      (testing "the task is the exact trivial shell wrapper (no --cache false,
                no --config override, no native-binary switch)"
        (is (= '(shell "clojure -M:lint") task)
            (str "lint task drifts from `(shell \"clojure -M:lint\")`: "
                 (pr-str task)))))))

(deftest tests-edn-suite-wiring-test
  (testing "tests.edn keeps the shared-config test dir wired into the suites
            that RUN the task-252 guards (slice-14 follow-up): the
            ^:integration proofs (analysis-level + git ground truth) execute
            only because the :integration suite lists
            components/shared-config/test with :focus-meta [:integration], and
            the unit invariants run only because the :unit suite lists it too
            (and skips the ^:integration tests via :skip-meta) — nothing tests
            tests.edn, so dropping the path (or changing :focus-meta/
            :skip-meta) silently disables every guard with zero signal, the
            same silent-drift class the task already guards for .gitignore /
            lint-alias / pins. Read tests.edn as EDN with the #kaocha/v1 tag
            reader — no subprocess, runs anywhere."
    (let [cfg    (read-edn "tests.edn" {:readers {'kaocha/v1 identity}})
          suites (:tests cfg)
          by-id  (fn [id] (first (filter #(= id (:id %)) suites)))
          unit   (by-id :unit)
          intg   (by-id :integration)]
      (is (some? unit) "tests.edn has a :unit suite")
      (is (some? intg) "tests.edn has an :integration suite")
      (testing ":capture-output? is false at TOP level (slice-15 follow-up:
                the ^:integration SKIP lines — the visible-skip mechanism from
                slice 9 — are swallowed by kaocha's capture-output plugin while
                :capture-output? is true; kaocha 1.91.1392 honors
                :capture-output? at the root config only, so a per-suite
                setting would be silently dropped. The tests.edn :unit and
                :integration suites carry no capture setting — the root value
                is the only one that takes effect)"
        (is (false? (:capture-output? cfg))
            "tests.edn top-level :capture-output? is false (SKIP lines reach runner output)"))
      (testing ":unit suite lists components/shared-config/test (runs the unit
                invariants) and skips the ^:integration tests"
        (is (some #{"components/shared-config/test"} (:test-paths unit))
            ":unit :test-paths contains components/shared-config/test")
        (is (= [:integration] (:skip-meta unit))
            ":unit :skip-meta retains [:integration] — the ^:integration proofs stay out of bb test"))
      (testing ":integration suite lists components/shared-config/test with
                :focus-meta [:integration] (the ^:integration proofs actually run)"
        (is (some #{"components/shared-config/test"} (:test-paths intg))
            ":integration :test-paths contains components/shared-config/test")
        (is (= [:integration] (:focus-meta intg))
            ":integration :focus-meta retains [:integration]")))))

(deftest ci-execution-chain-guard-test
  (testing "ci.yml + bb.edn still execute the lint gate and the ^:integration
            proofs (slice-28 follow-up): tests-edn-suite-wiring-test guards
            tests.edn's :integration suite and bb-edn-lint-task-wrapper-test
            guards bb.edn's lint task, but nothing guards ci.yml's Lint step
            (`run: bb lint`, ci.yml:89) or its Run Clojure integration tests
            step (`run: bb clojure:test:integration`, ci.yml:166) — the outer
            links that actually execute the lint gate and the three
            ^:integration proofs (design.md AC1 names
            `bb clojure:test:integration` as the CI-enforceable regression
            surface) — nor bb.edn's clojure:test:integration task
            (bb.edn:307-309), so a dropped/renamed CI step or a task drift to
            another suite/focus silently disables the entire CI regression
            surface while every existing guard stays green — the exact
            silent-drift class the task already closed for .gitignore /
            lint-alias / bb.edn lint wrapper / tests.edn. Read ci.yml as text
            (line-paired like the .gitignore test) and bb.edn as EDN — no
            subprocess, runs anywhere."
    (let [ci-file (io/file repo-root ".github/workflows/ci.yml")]
      ;; slice-31 follow-up: the slurp must NOT run in the let binding before
      ;; an exists assertion — a deleted ci.yml (the extreme of the exact
      ;; drift this test guards: "a dropped/renamed CI step … silently
      ;; disables the entire CI regression surface") would otherwise throw
      ;; FileNotFoundException at read time and surface as a clojure.test
      ;; ERROR with no assertion message (the ERROR-vs-FAIL standard slices
      ;; 20/24/29/30 closed elsewhere). Unlike the .gitignore slurp
      ;; (^:integration check-ignore malli arm backstop) and the import
      ;; config.edn read-edn (ls-files index arm backstop, slice-24's recorded
      ;; decline), NO ^:integration proof reads ci.yml — there is no backstop,
      ;; so the exists assertion below is the ONLY guard against whole-file
      ;; deletion: clean FAIL with its message, then split/parse only under
      ;; `when` (mirror of slice-24's shape).
      (testing "ci.yml exists"
        (is (.exists ci-file) ".github/workflows/ci.yml exists"))
      (when (.exists ci-file)
        (let [steps (ci-run-steps
                     (str/split-lines (slurp ci-file)))]
          (testing "Lint step runs `bb lint` (the local AC1 gate — ci.yml:89)"
            (is (= "bb lint" (get steps "Lint"))
                "ci.yml Lint step runs `bb lint`"))
          (testing "Run Clojure integration tests step runs
                    `bb clojure:test:integration` (the CI-enforceable
                    regression surface — the three ^:integration proofs run
                    there; ci.yml:166)"
            (is (= "bb clojure:test:integration"
                   (get steps "Run Clojure integration tests"))
                "ci.yml integration step runs `bb clojure:test:integration`")))))
    (testing "bb.edn clojure:test:integration task still routes to
              run-scry-kaocha-suite! with the integration suite and focus — a
              drift to another suite/focus (or a dropped task) would silently
              stop the proofs from running while the tests.edn wiring stays
              green (bb.edn:307-309)"
      (let [task (:task (get-in (read-edn "bb.edn") [:tasks 'clojure:test:integration]))
            call (second task)]   ; (System/exit (run-scry-kaocha-suite! "integration" ["--focus" "integration"]))
        (is (some? task) "bb.edn defines a :tasks clojure:test:integration entry")
        (is (seq? task) "the task is a call form")
        (is (= 'System/exit (first task))
            "task wraps the call in System/exit (scry exit-code propagation)")
        (is (seq? call) "the System/exit argument is a call form")
        (is (= 'run-scry-kaocha-suite! (first call))
            "clojure:test:integration invokes run-scry-kaocha-suite!")
        (is (= "integration" (second call))
            "run-scry-kaocha-suite! receives the integration suite id")
        ;; slice-29 follow-up: `(nth call 2)` on a drift that DROPS the focus
        ;; args (e.g. a two-element (run-scry-kaocha-suite! "integration"))
        ;; throws IndexOutOfBoundsException → clojure.test ERROR with no
        ;; assertion message — the exact regression this guard exists to catch
        ;; surfacing as the exact ERROR-vs-FAIL class slices 20/24 closed
        ;; elsewhere. `(nth call 2 nil)` reads out-of-bounds as nil and FAILs
        ;; cleanly with the assertion message (the ERROR-vs-FAIL standard:
        ;; exactly ONE plain assertion FAIL, never an ERROR).
        (is (= ["--focus" "integration"] (nth call 2 nil))
            "run-scry-kaocha-suite! keeps the --focus integration args")))))


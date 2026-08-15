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
            read-edn
            repo-root]]))
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
            undetectable by bb test/CI. Reads the impl as text with
            *read-eval* false (a `#=` reader-eval form throws instead of
            evaluating — slice-21) — no subprocess — and the assertion code
            lives outside the import dir (AC2 confinement)."
    (let [impl-rel  ".clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj"
          impl-file (io/file repo-root impl-rel)
          cfg       (read-edn ".clj-kondo/imports/http-kit/http-kit/config.edn")
          ref       (get-in cfg [:hooks :analyze-call 'org.httpkit.server/with-channel])
          forms     (binding [*read-eval* false]
                      (read-string {:read-cond :preserve}
                                   (str "[" (slurp impl-file) "]")))]
      (testing "config.edn :analyze-call maps with-channel to httpkit.with-channel/with-channel"
        (is (= 'httpkit.with-channel/with-channel ref)))
      (testing "impl file exists (member of the slice-4 tracked change set)"
        (is (.exists impl-file) (str impl-rel " exists")))
      (testing "impl ns is httpkit.with-channel — matches the reference's namespace"
        (is (some (fn [f] (and (seq? f) (= 'ns (first f))
                               (= 'httpkit.with-channel (second f))))
                  forms)))
      (testing "impl defines (defn with-channel …) — matches the reference's var"
        (is (some (fn [f] (and (seq? f) (= 'defn (first f))
                               (= 'with-channel (second f))))
                  forms))))))

(deftest root-config-ac2-invariant-test
  (testing "root config keeps AC2 invariants (no http-client drift)"
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
            "root config carries no http-client symbol or keyword")))))

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
                                           lines)))]
      (testing "all three tracking lines are present verbatim"
        (doseq [pattern (into [ignore-all] negations)]
          (is (some #{pattern} lines)
              (str ".gitignore contains the tracking line " pattern))))
      (testing "ignore-all precedes both negations (gitignore is last-match-wins:
                if `**/.clj-kondo/imports/*` moved BELOW the negation lines, git
                would re-ignore the http-kit import dir — the registration
                silently drops out of future commits — while all three lines
                still exist and a presence-only test passes)"
        (let [ignore-idx (index-of ignore-all)]
          (is (some? ignore-idx) "ignore-all pattern present (index found)")
          (doseq [negation negations]
            (let [neg-idx (index-of negation)]
              (is (some? neg-idx) (str "negation line present (index found): " negation))
              (is (< ignore-idx neg-idx)
                  (str ignore-all " (line " (inc ignore-idx) ") precedes "
                       negation " (line " (inc neg-idx) ")")))))))))

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


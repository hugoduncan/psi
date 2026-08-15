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
  confinement)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]))

(def repo-root
  "Canonical repo root; tests run from the repo root via bb test / CI."
  (.getCanonicalPath (io/file ".")))

(def http-kit-jar
  "Pinned http-kit 2.8.0 jar at the standard m2 path (present in CI per the
  m2 cache; the design-step 9 m2-cache fact)."
  (str (System/getProperty "user.home")
       "/.m2/repository/http-kit/http-kit/2.8.0/http-kit-2.8.0.jar"))

(def clj-kondo-deps
  "Pinned JVM clj-kondo 2025.09.19 (the :lint alias version) via -Sdeps,
  so the proof uses the same analyzer as the repo lint gate."
  "{:deps {clj-kondo/clj-kondo {:mvn/version \"2025.09.19\"}}}")

(defn- read-edn
  "Read a repo-relative EDN file."
  [rel-path]
  (edn/read-string (slurp (io/file repo-root rel-path))))

(defn- clj-kondo-main
  "Run the pinned clj-kondo as a subprocess from the repo root with the given
  clj-kondo args; returns the shell/sh result map."
  [& args]
  (apply shell/sh "clojure" "-Sdeps" clj-kondo-deps "-M" "-m" "clj-kondo.main"
         (concat args [:dir repo-root])))

(deftest http-kit-import-registration-test
  (testing "http-kit import config retains the defreq :lint-as registration"
    (let [cfg (read-edn ".clj-kondo/imports/http-kit/http-kit/config.edn")]
      (is (= '{org.httpkit.client/defreq clojure.core/def} (:lint-as cfg)))
      (testing "server-side with-channel hook is preserved alongside it"
        (is (contains? (:hooks cfg) :analyze-call))))))

(deftest root-config-ac2-invariant-test
  (testing "root :unresolved-symbol :exclude remains exactly [(malli.core/=>)] (AC2)"
    (let [cfg (read-edn ".clj-kondo/config.edn")]
      (is (= '[(malli.core/=>)]
             (get-in cfg [:linters :unresolved-symbol :exclude]))))))

(deftest ^:integration http-kit-defreq-analysis-level-resolution-test
  (testing "defreq verbs resolve and a bogus var still warns via cache-driven analysis"
    (if-not (.exists (io/file http-kit-jar))
      (is (str "skipped: " http-kit-jar " not present"))
      (let [tmp       (doto (java.io.File/createTempFile "ck252" "")
                        (.delete)
                        (.mkdirs))
            cache-dir (str (io/file tmp "cache"))
            probe     (io/file tmp "probe.clj")]
        (spit probe
              (str "(ns probe\n"
                   "  (:require [org.httpkit.client :as http-client]))\n"
                   "\n"
                   "(defn exercise []\n"
                   "  @(http-client/get \"http://example.com\")\n"
                   "  @(http-client/post \"http://example.com\")\n"
                   "  @(http-client/definitely-not-a-var))\n"))
        (testing "jar analysis populates a hermetic cache"
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
            (is (str/includes? out "Unresolved var: http-client/definitely-not-a-var"))))))))

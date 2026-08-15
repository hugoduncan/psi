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
   [clojure.test :refer [deftest testing is]])
  (:import
   [java.util.concurrent TimeUnit]))

(def repo-root
  "Canonical repo root; tests run from the repo root via bb test / CI."
  (.getCanonicalPath (io/file ".")))

(def http-kit-jar
  "Pinned http-kit 2.8.0 jar at the standard m2 path (present in CI per the
  m2 cache; the design-step 9 m2-cache fact)."
  (str (System/getProperty "user.home")
       "/.m2/repository/http-kit/http-kit/2.8.0/http-kit-2.8.0.jar"))

(defn- read-edn
  "Read a repo-relative EDN file."
  [rel-path]
  (edn/read-string (slurp (io/file repo-root rel-path))))

(def clj-kondo-version
  "Pinned clj-kondo version for the analysis-level proof: derived from deps.edn
  `:lint :extra-deps` (the source of truth for the lint gate's analyzer), so a
  clj-kondo bump fails loudly here instead of silently re-proving a stale pin."
  (get-in (read-edn "deps.edn")
          [:aliases :lint :extra-deps 'clj-kondo/clj-kondo :mvn/version]))

(def clj-kondo-deps
  "Pinned JVM clj-kondo via -Sdeps, so the proof uses the same analyzer as the
  repo lint gate (see clj-kondo-version; derived, never hardcoded separately)."
  (format "{:deps {clj-kondo/clj-kondo {:mvn/version \"%s\"}}}" clj-kondo-version))

(def clojure-bin
  "Path to the clojure CLI binary, or nil when not on PATH (mirrors the
  jar-absent skip: a missing binary would otherwise error the ^:integration
  test rather than skip)."
  (some-> (shell/sh "which" "clojure")
          (as-> r (when (zero? (:exit r)) (str/trim (:out r))))))

(def ^:private process-timeout-ms
  "Upper bound (ms) for the pinned-clj-kondo subprocess. clojure.java.shell/sh
  has NO :timeout support (unknown opts silently ignored — verified in the 1.12
  source), so without a bounded runner a hung subprocess (e.g. a cold -Sdeps
  dep download stall) would block the suite indefinitely."
  120000)

(defn- clj-kondo-main
  "Run the pinned clj-kondo as a subprocess from the repo root with the given
  clj-kondo args; returns shell/sh-shaped {:exit :out :err}. Timeout-bounded
  via ProcessBuilder + waitFor(ms) (see process-timeout-ms); on timeout the
  process is killed and the test fails loudly rather than hanging the suite."
  [& args]
  (let [pb (doto (ProcessBuilder. (into ["clojure" "-Sdeps" clj-kondo-deps
                                         "-M" "-m" "clj-kondo.main"] args))
             (.directory (io/file repo-root)))
        proc (.start pb)
        out-f (future (slurp (.getInputStream proc)))
        err-f (future (slurp (.getErrorStream proc)))]
    (try
      (if-not (.waitFor proc process-timeout-ms TimeUnit/MILLISECONDS)
        (throw (ex-info (str "clj-kondo subprocess exceeded " process-timeout-ms
                             " ms and was killed: " (pr-str args))
                        {:args args}))
        {:exit (.exitValue proc) :out @out-f :err @err-f})
      (finally
        (when (.isAlive proc)
          (.destroyForcibly proc)
          ;; drain the streams so the (non-daemon) future threads terminate
          @out-f @err-f)))))

(deftest http-kit-import-registration-test
  (testing "http-kit import config retains the defreq :lint-as registration"
    (let [cfg (read-edn ".clj-kondo/imports/http-kit/http-kit/config.edn")]
      (is (= '{org.httpkit.client/defreq clojure.core/def} (:lint-as cfg)))
      (testing "server-side with-channel hook is preserved exactly alongside it
                (the design mechanism requires the existing hook be kept)"
        (is (= '{:analyze-call {org.httpkit.server/with-channel
                                httpkit.with-channel/with-channel}}
               (:hooks cfg)))))))

(deftest root-config-ac2-invariant-test
  (testing "root config keeps AC2 invariants (no http-client drift)"
    (let [cfg (read-edn ".clj-kondo/config.edn")]
      (testing ":unresolved-symbol :exclude remains exactly [(malli.core/=>)]"
        (is (= '[(malli.core/=>)]
               (get-in cfg [:linters :unresolved-symbol :exclude]))))
      (testing ":lint-as does not mirror the http-kit defreq registration
                (plan.md decision 1's no-root-mirror choice; defreq is never
                invoked in-repo, unlike the malli/promesa mirror convention)"
        (is (not (contains? (:lint-as cfg) 'org.httpkit.client/defreq)))))))

(deftest clj-kondo-pin-sourced-from-deps-edn-test
  (testing "the analysis-level proof's clj-kondo version is derived from deps.edn
            :lint :extra-deps, so a clj-kondo bump fails loudly instead of
            silently re-proving a stale pin"
    (let [pin (get-in (read-edn "deps.edn")
                      [:aliases :lint :extra-deps 'clj-kondo/clj-kondo])]
      (is (some? pin) "deps.edn :lint :extra-deps pins clj-kondo/clj-kondo")
      (is (some? (:mvn/version pin))))))

(deftest ^:integration http-kit-defreq-analysis-level-resolution-test
  (testing "defreq verbs resolve and a bogus var still warns via cache-driven analysis"
    (if-let [reason (cond
                      (nil? clojure-bin)
                      "clojure CLI binary not on PATH (clj-kondo subprocess cannot run)"

                      (not (.exists (io/file http-kit-jar)))
                      (str http-kit-jar " not present")

                      :else nil)]
      (is (str "skipped: " reason))
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

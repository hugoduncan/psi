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

(def ^:private repo-root-prop
  "System property that overrides repo-root (e.g. when running from an
  editor/nrepl CWD that is not under the repo)."
  "psi.lint-config-test.repo-root")

(defn- find-repo-root
  "Walk up from `start` (a dir path string) until the psi project root is
  found — a directory containing BOTH deps.edn and bb.edn. Components and
  extensions carry their own deps.edn, so plain deps.edn presence is not a
  root marker (walking up from a nested CWD would stop at the component);
  bb.edn lives only at the repo root. Return the canonical path, or nil."
  [start]
  (loop [dir (io/file start)]
    (when dir
      (if (and (.exists (io/file dir "deps.edn"))
               (.exists (io/file dir "bb.edn")))
        (.getCanonicalPath dir)
        (recur (.getParentFile dir))))))

(def repo-root
  "Canonical repo root. Derived by walking up from user.dir until the psi
  project root is found (deps.edn + bb.edn, see find-repo-root), so the tests
  run correctly from the repo root (bb test / CI) and from nested CWDs
  (editor/nrepl runner in a component dir); overridable via the
  psi.lint-config-test.repo-root system property; fails with a clear message
  when no root is found."
  (or (not-empty (System/getProperty repo-root-prop))
      (find-repo-root (System/getProperty "user.dir"))
      (throw (ex-info (str "Could not locate the repo root: no deps.edn+bb.edn "
                           "pair found walking up from user.dir. Run from the "
                           "repo root or set the psi.lint-config-test.repo-root "
                           "system property.")
                      {:user.dir (System/getProperty "user.dir")}))))

(defn- read-edn
  "Read a repo-relative EDN file."
  [rel-path]
  (edn/read-string (slurp (io/file repo-root rel-path))))

(def http-kit-version
  "Pinned http-kit version for the analysis-level proof: derived from deps.edn
  :deps (the pin source; currently 2.8.0), so an http-kit bump fails loudly
  instead of silently re-proving a stale jar (R4's re-verification gap)."
  (get-in (read-edn "deps.edn") [:deps 'http-kit/http-kit :mvn/version]))

(def http-kit-jar
  "Pinned http-kit jar at the standard m2 path (present in CI per the m2 cache;
  the design-step 9 m2-cache fact). Formatted from http-kit-version so a bump
  re-targets the jar; a removed/renamed pin fails loudly in :unit instead of
  silently re-proving the old jar or silently skipping."
  (str (System/getProperty "user.home")
       "/.m2/repository/http-kit/http-kit/" http-kit-version
       "/http-kit-" http-kit-version ".jar"))

(defn- http-client-entries
  "Every symbol/keyword in the parsed EDN whose namespace is
  org.httpkit.client — AC2's \"root config gains no http-client entries\" clause,
  walked over the whole tree (:lint-as, :hooks, :namespaces, …)."
  [form]
  (filter (fn [x]
            (and (or (symbol? x) (keyword? x))
                 (= "org.httpkit.client" (namespace x))))
          (tree-seq coll? seq form)))

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
      (is (some? (:mvn/version pin))))))

(deftest gitignore-http-kit-import-tracking-test
  (testing ".gitignore keeps the http-kit import dir TRACKED (plan.md decision 2
            / slice 1 / slice-4 change set): the negation lines that exempt
            `.clj-kondo/imports/http-kit/**` from the ignore-all rule.
            Nothing else tests this — `http-kit-import-registration-test` reads
            config.edn from disk, so if the negation is removed (restoring
            `**/.clj-kondo/imports/`) the file still exists locally, the unit
            suite passes, and the registration silently drops out of future
            commits. Read as text — no subprocess — so it runs anywhere."
    (let [patterns ["**/.clj-kondo/imports/*"
                    "!.clj-kondo/imports/http-kit/"
                    "!.clj-kondo/imports/http-kit/**"]
          lines    (str/split-lines (slurp (io/file repo-root ".gitignore")))]
      (doseq [pattern patterns]
        (is (some #{pattern} lines)
            (str ".gitignore contains the tracking-negation line " pattern))))))

(deftest ^:integration http-kit-defreq-analysis-level-resolution-test
  (testing "defreq verbs resolve via cache-driven analysis: probe + real AC1 file
            (extensions/dev-http/test/extensions/dev_http_test.clj, lines 572/737)
            clean with the registration cache; real file warns against a
            no-registration cache (discriminating control, slice-10 follow-up)"
    (if-let [reason (cond
                      (nil? clojure-bin)
                      "clojure CLI binary not on PATH (clj-kondo subprocess cannot run)"

                      (not (.exists (io/file http-kit-jar)))
                      (str http-kit-jar " not present")

                      :else nil)]
      (do (println "SKIP task-252 analysis-level proof:" reason)
          (is (str "skipped: " reason)))
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
                  (a regression in the real file — an added http-client/delete
                  call, a changed alias, a removed require — fails here)"
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
            (let [transit (slurp (io/file no-reg-dir
                                          "v1/clj/org.httpkit.client.transit.json"))]
              (is (str/includes? transit "~$request")
                  "no-reg cache still carries the plain defn request")
              (is (not (str/includes? transit "~$get"))
                  "no-reg cache carries NO defreq-generated get")
              (is (not (str/includes? transit "~$post"))
                  "no-reg cache carries NO defreq-generated post"))))
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
                "no-reg cache ⇒ exactly the two warnings (AC1 baseline shape)")))))))

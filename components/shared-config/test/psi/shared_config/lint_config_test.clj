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
  "Read a repo-relative EDN file, optionally with edn/read-string opts (e.g.
  {:readers {'kaocha/v1 identity}} for tests.edn's tagged literal)."
  ([rel-path]
   (edn/read-string (slurp (io/file repo-root rel-path))))
  ([rel-path opts]
   (edn/read-string opts (slurp (io/file repo-root rel-path)))))

(def http-kit-version
  "Pinned http-kit version for the analysis-level proof: derived from deps.edn
  :deps (the pin source; currently 2.8.0), so an http-kit bump fails loudly
  instead of silently re-proving a stale jar (R4's re-verification gap)."
  (get-in (read-edn "deps.edn") [:deps 'http-kit/http-kit :mvn/version]))

(def ^:private http-kit-jar-prop
  "System property that overrides the http-kit jar path (mirror of
  psi.lint-config-test.repo-root and psi.lint-config-test.clojure-bin), making
  the m2-repo dependency injectable per the skill infra-dep criterion — e.g. a
  non-standard m2 layout (-Dmaven.repo.local) or a CI home that keeps the
  pinned jar at a different path."
  "psi.lint-config-test.http-kit-jar")

(def http-kit-jar
  "Pinned http-kit jar at the standard m2 path (present in CI per the m2 cache;
  the design-step 9 m2-cache fact). Formatted from http-kit-version so a bump
  re-targets the jar; a removed/renamed pin fails loudly in :unit instead of
  silently re-proving the old jar or silently skipping. Overridable via the
  psi.lint-config-test.http-kit-jar system property (injectable — mirror of the
  clojure-bin override, slice-12 follow-up); otherwise derived from user.home +
  http-kit-version (nullable — a jar absent at the derived path skips the
  ^:integration test via the existing skip guard)."
  (or (not-empty (System/getProperty http-kit-jar-prop))
      (str (System/getProperty "user.home")
           "/.m2/repository/http-kit/http-kit/" http-kit-version
           "/http-kit-" http-kit-version ".jar")))

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

(def ^:private clj-kondo-jar-prop
  "System property that overrides the pinned clj-kondo jar path (mirror of
  psi.lint-config-test.http-kit-jar), making the m2-repo dependency injectable
  per the skill infra-dep criterion — e.g. a non-standard m2 layout
  (-Dmaven.repo.local) or a CI home that keeps the pinned artifact at a
  different path."
  "psi.lint-config-test.clj-kondo-jar")

(def clj-kondo-jar
  "Pinned clj-kondo jar at the standard m2 path — the artifact the -Sdeps proof
  executes (formatted from clj-kondo-version so a bump re-targets the jar).
  Overridable via the psi.lint-config-test.clj-kondo-jar system property
  (injectable — mirror of the http-kit-jar override); otherwise derived from
  user.home + clj-kondo-version (nullable — a jar absent at the derived path
  skips the ^:integration test via the skip guard instead of attempting a
  network download or hanging up to the subprocess timeout)."
  (or (not-empty (System/getProperty clj-kondo-jar-prop))
      (str (System/getProperty "user.home")
           "/.m2/repository/clj-kondo/clj-kondo/" clj-kondo-version
           "/clj-kondo-" clj-kondo-version ".jar")))

(def ^:private clojure-bin-prop
  "System property that overrides the clojure CLI binary (mirror of
  psi.lint-config-test.repo-root), making the infra dep injectable per the
  skill infra-dep criterion — e.g. from an editor/nrepl runner whose PATH
  differs from the invoking shell's."
  "psi.lint-config-test.clojure-bin")

(defn- which-clojure-bin
  "Resolve the clojure CLI binary from PATH, or nil when not on PATH."
  []
  (some-> (shell/sh "which" "clojure")
          (as-> r (when (zero? (:exit r)) (str/trim (:out r))))))

(def clojure-bin
  "Path to the clojure CLI binary used by the analysis-level proof, or nil when
  unavailable. Overridable via the psi.lint-config-test.clojure-bin system
  property (injectable — mirror of the repo-root override); otherwise derived
  from PATH via `which clojure` (nullable — a missing binary skips the
  ^:integration test instead of erroring). The SAME resolved value feeds both
  the skip guard and the executed subprocess, so the guard can never prove a
  binary other than the one executed (slice-11 follow-up: previously the guard
  resolved PATH at ns-load while the ProcessBuilder re-resolved the literal
  \"clojure\" from PATH at run time, so a PATH mutation or shell function
  shadowing between load and run made the guard prove a different binary)."
  (or (not-empty (System/getProperty clojure-bin-prop))
      (which-clojure-bin)))

(def ^:private git-bin-prop
  "System property that overrides the git binary (mirror of
  psi.lint-config-test.clojure-bin), making the infra dep injectable per the
  skill infra-dep criterion — e.g. from an editor/nrepl runner whose PATH
  differs from the invoking shell's."
  "psi.lint-config-test.git-bin")

(defn- which-git-bin
  "Resolve the git binary from PATH, or nil when not on PATH."
  []
  (some-> (shell/sh "which" "git")
          (as-> r (when (zero? (:exit r)) (str/trim (:out r))))))

(def git-bin
  "Path to the git binary used by the git check-ignore ground-truth proof, or
  nil when unavailable. Overridable via the psi.lint-config-test.git-bin system
  property (injectable — mirror of the repo-root/clojure-bin overrides);
  otherwise derived from PATH via `which git` (nullable — a missing binary
  skips the ^:integration test instead of erroring). The SAME resolved value
  feeds both the skip guard and the executed subprocess, so the guard can never
  prove a binary other than the one executed (slice-14 follow-up: previously
  the guard resolved PATH at test time via `which git` while shell/sh
  re-resolved the literal \"git\" from PATH at run time — a PATH mutation or
  shell-function shadowing between guard and exec made the guard prove a
  different binary, the exact disagreement slice 11 eliminated for
  clojure-bin)."
  (or (not-empty (System/getProperty git-bin-prop))
      (which-git-bin)))

(def ^:private process-timeout-ms
  "Upper bound (ms) for the pinned-clj-kondo subprocess. clojure.java.shell/sh
  has NO :timeout support (unknown opts silently ignored — verified in the 1.12
  source), so without a bounded runner a hung subprocess (e.g. a cold -Sdeps
  dep download stall) would block the suite indefinitely."
  120000)

(defn- run-bounded
  "Run a subprocess with the given command vector from the repo root; returns
  shell/sh-shaped {:exit :out :err}. Timeout-bounded via ProcessBuilder +
  waitFor(ms) (see process-timeout-ms); on timeout the process is killed and
  the test fails loudly rather than hanging the suite (shell/sh has no
  :timeout — unknown opts silently ignored, verified in the 1.12 source)."
  [cmd]
  (let [pb (doto (ProcessBuilder. cmd)
             (.directory (io/file repo-root)))
        proc (.start pb)
        out-f (future (slurp (.getInputStream proc)))
        err-f (future (slurp (.getErrorStream proc)))]
    (try
      (if-not (.waitFor proc process-timeout-ms TimeUnit/MILLISECONDS)
        (throw (ex-info (str "subprocess exceeded " process-timeout-ms
                             " ms and was killed: " (pr-str cmd))
                        {:cmd cmd}))
        {:exit (.exitValue proc) :out @out-f :err @err-f})
      (finally
        (when (.isAlive proc)
          (.destroyForcibly proc)
          ;; drain the streams so the (non-daemon) future threads terminate
          @out-f @err-f)))))

(defn- clj-kondo-main
  "Run the pinned clj-kondo as a subprocess from the repo root with the given
  clj-kondo args; returns shell/sh-shaped {:exit :out :err} (see run-bounded
  for the timeout bound)."
  [& args]
  (when (nil? clojure-bin)
    (throw (ex-info (str "clojure-bin is nil — cannot run the clj-kondo "
                         "subprocess. Put clojure on PATH or set the "
                         clojure-bin-prop " system property.")
                    {})))
  (run-bounded (into [clojure-bin "-Sdeps" clj-kondo-deps
                      "-M" "-m" "clj-kondo.main"] args)))

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
            undetectable by bb test/CI. Reads the impl as text/EDN — no
            subprocess — and the assertion code lives outside the import dir
            (AC2 confinement)."
    (let [impl-rel  ".clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj"
          impl-file (io/file repo-root impl-rel)
          cfg       (read-edn ".clj-kondo/imports/http-kit/http-kit/config.edn")
          ref       (get-in cfg [:hooks :analyze-call 'org.httpkit.server/with-channel])
          forms     (read-string (str "[" (slurp impl-file) "]"))]
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
          ":lint :main-opts contains \"bb.edn\""))))

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
    (let [suites (:tests (read-edn "tests.edn" {:readers {'kaocha/v1 identity}}))
          by-id  (fn [id] (first (filter #(= id (:id %)) suites)))
          unit   (by-id :unit)
          intg   (by-id :integration)]
      (is (some? unit) "tests.edn has a :unit suite")
      (is (some? intg) "tests.edn has an :integration suite")
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
            run-bounded — shell/sh has no :timeout)."
    (if-let [reason (when (nil? git-bin) "git not on PATH")]
      (do (println "SKIP task-252 git check-ignore ground truth:" reason)
          (is (str "skipped: " reason)))
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
                "match comes from .gitignore (matched rule reported in -v output)")))))))

(defn- delete-recursively!
  "Recursively delete a file/dir tree. clojure.java.io offers no recursive
  delete and `.deleteOnExit` only removes empty dirs, so without this every
  integration run leaks the temp cache tree under /tmp (slice-12 follow-up).
  Returns nil."
  [f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [child (.listFiles f)]
        (delete-recursively! child)))
    (.delete f))
  nil)

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

                      (not (.exists (io/file clj-kondo-jar)))
                      (str clj-kondo-jar " not present (pinned clj-kondo artifact)")

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
        (try
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
                  "no-reg cache ⇒ exactly the two warnings (AC1 baseline shape)")))
          (finally
            (delete-recursively! tmp)))))))

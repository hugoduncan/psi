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
   [java.util.concurrent TimeUnit]
   [java.util.zip ZipFile]))

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

(def ^:private clj-kondo-local-repo-prop
  "System property that overrides the m2 local-repo root used to resolve the
  pinned clj-kondo artifact (mirror of psi.lint-config-test.clj-kondo-jar),
  for a non-standard m2 layout the suffix-strip derivation cannot handle."
  "psi.lint-config-test.clj-kondo-local-repo")

(defn- clj-kondo-local-repo
  "m2 local-repo root for the -Sdeps map. Overridable via the
  psi.lint-config-test.clj-kondo-local-repo system property; otherwise derived
  from the guarded clj-kondo-jar path by stripping the
  `clj-kondo/clj-kondo/{version}/clj-kondo-{version}.jar` suffix (the standard
  m2 layout the jar derivation/override follows), so the -Sdeps subprocess
  resolves the EXACT guarded artifact — guard and exec agree (slice-16
  follow-up: previously the subprocess resolved the artifact from the Clojure
  CLI's own local repo while the skip guard checked clj-kondo-jar, a path
  never passed to the subprocess, so a custom-path override could pass the
  guard yet execute a different artifact or trigger a network download).
  Throws a clear ex-info when the jar path is not in the standard m2 layout
  and no property override is set — such a path cannot be resolved by mvn
  coordinates anyway, so a loud failure beats a silent wrong-artifact or
  download."
  []
  (or (not-empty (System/getProperty clj-kondo-local-repo-prop))
      (let [jar    clj-kondo-jar
            suffix (str "/clj-kondo/clj-kondo/" clj-kondo-version
                        "/clj-kondo-" clj-kondo-version ".jar")]
        (if (str/ends-with? jar suffix)
          (subs jar 0 (- (count jar) (count suffix)))
          (throw (ex-info (str "Cannot derive the m2 local-repo root from "
                               "clj-kondo-jar " jar ": expected the standard "
                               "m2 layout …" suffix ". Set the "
                               clj-kondo-local-repo-prop " system property "
                               "for a non-standard layout.")
                          {:clj-kondo-jar jar :expected-suffix suffix}))))))

(defn- clj-kondo-deps
  "Pinned JVM clj-kondo via -Sdeps — with :mvn/local-repo derived from the
  guarded clj-kondo-jar path (see clj-kondo-local-repo), so the subprocess
  executes the exact artifact the skip guard checked; the proof therefore uses
  the same analyzer as the repo lint gate (see clj-kondo-version; derived,
  never hardcoded separately)."
  []
  (format "{:deps {clj-kondo/clj-kondo {:mvn/version \"%s\"}} :mvn/local-repo \"%s\"}"
          clj-kondo-version (clj-kondo-local-repo)))

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
  :timeout — unknown opts silently ignored, verified in the 1.12 source). The
  timeout ex-info carries the partial stdout/stderr drained after the kill, so
  a hung subprocess (e.g. a cold -Sdeps dep download stall) surfaces where it
  stalled instead of reporting zero context (slice-20 follow-up: the ex-info
  previously carried {:cmd cmd} only and discarded the drained streams).

  The stream drain is bounded on EVERY path — success included (slice-21
  follow-up): the waitFor bound covers the process LIFETIME only, so a
  subprocess that exits while a descendant still holds the stdout/stderr pipe
  open (classic grandchild scenario — e.g. the clojure CLI spawning a JVM that
  spawns a helper) never EOFs the slurp; an unbounded deref on the success
  path (or in the finally) would then hang the suite indefinitely despite the
  documented bound. The success path therefore uses the same bounded deref
  and fails loudly — with the undrained :out/:err marked :unavailable — when
  the streams do not close within the drain bound."
  [cmd]
  (let [pb (doto (ProcessBuilder. cmd)
             (.directory (io/file repo-root)))
        proc (.start pb)
        out-f (future (slurp (.getInputStream proc)))
        err-f (future (slurp (.getErrorStream proc)))
        drain (fn [f label]
                (try (deref f 500 ::unavailable)
                     (catch InterruptedException _
                       (str "<" label " interrupted>"))))]
    (try
      (if-not (.waitFor proc process-timeout-ms TimeUnit/MILLISECONDS)
        (let [;; kill the process first so the pipe streams close and the
              ;; draining futures complete; then capture whatever partial
              ;; output was produced before the bound was hit. (Derefing
              ;; before the kill would block — the streams stay open while
              ;; the process lives.)
              _           (when (.isAlive proc) (.destroyForcibly proc))
              partial-out (drain out-f "stdout")
              partial-err (drain err-f "stderr")]
          (throw (ex-info (str "subprocess exceeded " process-timeout-ms
                               " ms and was killed: " (pr-str cmd))
                          {:cmd cmd
                           :out (if (= ::unavailable partial-out)
                                  :unavailable partial-out)
                           :err (if (= ::unavailable partial-err)
                                  :unavailable partial-err)})))
        (let [out (drain out-f "stdout")
              err (drain err-f "stderr")]
          (when (or (= ::unavailable out) (= ::unavailable err))
            (throw (ex-info (str "subprocess exited but its stdout/stderr did "
                                 "not close within the drain bound — a "
                                 "descendant process is holding the pipe open: "
                                 (pr-str cmd))
                            {:cmd cmd
                             :out (if (= ::unavailable out) :unavailable out)
                             :err (if (= ::unavailable err) :unavailable err)})))
          {:exit (.exitValue proc) :out out :err err}))
      (finally
        (when (.isAlive proc)
          (.destroyForcibly proc))
        ;; bounded drain so the finally can never hang the suite either
        ;; (slice-21: the previously-unbounded @out-f/@err-f here would block
        ;; forever on the pipe-holding-descendant path; a completed future
        ;; returns instantly, so the bound only bites in the pathological case)
        (drain out-f "stdout")
        (drain err-f "stderr")))))

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
  (run-bounded (into [clojure-bin "-Sdeps" (clj-kondo-deps)
                      "-M" "-m" "clj-kondo.main"] args)))

(defn- report-skip!
  "Report a skipped ^:integration proof visibly in runner output.

  A plain (println …) is swallowed on every runner path when the proof
  skip-passes: kaocha's capture-output plugin buffers per-test output (shown
  only in the failure report) and scry's in-process runner additionally binds
  *out* to a discarding writer around api/run. Writing directly to System/out
  reaches the runner's captured process stdout on both paths — it is untouched
  while tests.edn keeps top-level :capture-output? false (slice-15 fix) — so a
  jar/clojure/git-absent skip is distinguishable from a real pass in runner
  output (slice-9's visible-skip mechanism, restored)."
  [label reason]
  (.println System/out (str "SKIP task-252 " label ": " reason)))

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

(defn- parse-forms
  "Parse a Clojure source string into a vector of its top-level forms. Used to
  compare the tracked with-channel hook impl against the jar export
  structurally: whitespace/indentation differences vanish by construction (the
  documented cljfmt indentation drift, slice 5 — the repo's pre-commit hook
  reformats continuation indentation to repo style, 2-space vs the jar's
  3-space, so a byte compare can never pass through the repo's own commit
  path), while every semantic difference survives — including string-literal
  contents, which read-string preserves exactly (a spacing change inside a
  string literal is a different parsed value, unlike a whitespace-collapsing
  text compare).

  Binds *read-eval* false (slice-21 follow-up): a drifted/malicious `#=`
  reader-eval form on either side of the compare would otherwise EXECUTE
  during the read instead of being compared — this guard's purpose is to
  detect semantic drift in the tracked impl vs the pinned jar export, so `#=`
  now throws loudly instead of evaluating. Passes :read-cond :preserve so
  reader conditionals (`#?`/`#?@`) COMPARE structurally on all branches
  rather than erroring with \"Conditional read not allowed\" — :allow would
  read only the current platform's branch and silently drop the others from
  the compare, a blind spot of the exact class this guard exists to close."
  [s]
  (binding [*read-eval* false]
    (read-string {:read-cond :preserve} (str "[" s "]"))))

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
            psi.lint-config-test.http-kit-jar / the derived m2 path)."
    (if-let [reason (when-not (.exists (io/file http-kit-jar))
                      (str http-kit-jar " not present"))]
      (do (report-skip! "with-channel hook semantics" reason)
          (is (str "skipped: " reason)))
      (let [jar-entry "clj-kondo.exports/http-kit/http-kit/httpkit/with_channel.clj"
            tracked   (slurp (io/file repo-root
                                      ".clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj"))
            jar-export (with-open [zf (ZipFile. (io/file http-kit-jar))]
                         (when-let [entry (.getEntry zf jar-entry)]
                           (slurp (.getInputStream zf entry))))]
        (is (some? jar-export)
            (str "the pinned http-kit jar contains the clj-kondo.exports export "
                 jar-entry))
        (testing "tracked impl is semantically identical to the jar export
                  (parsed-form compare — whitespace/indentation-insensitive,
                  string-literal-sensitive; see parse-forms)"
          (when (some? jar-export)
            ;; nil-guard (slice-20 follow-up): the some? assertion above fails
            ;; cleanly when the jar entry is missing; without this guard the
            ;; equality would throw an NPE on nil jar-export and surface as a
            ;; clojure.test ERROR instead of the plain assertion failure it
            ;; deserves.
            (is (= (parse-forms jar-export) (parse-forms tracked))
                "tracked with_channel.clj differs from the pinned jar export")))))))

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
      (do (report-skip! "git check-ignore ground truth" reason)
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
      (do (report-skip! "analysis-level proof" reason)
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

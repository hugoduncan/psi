(ns psi.shared-config.lint-config-test-support
  "Shared fixtures for the task-252 clj-kondo lint-config regression tests
  (task 252, split from lint-config-test to keep every file under the
  file-length gate).

  Single-sources every fixture the lint-config test namespaces share: repo-root
  discovery (with the psi.lint-config-test.repo-root override), EDN reading
  (plain read-edn + the guarded read-edn-or-nil — slice-45 consolidation),
  the pinned http-kit/clj-kondo versions and jar paths (each overridable via
  its own system property, mirroring repo-root), the pinned-JVM-clj-kondo
  subprocess runner (timeout-bounded, see run-bounded; a start failure is
  converted into the loud ex-info with :cmd — slice-45), the visible SKIP
  reporter for jar/clojure/git-absent ^:integration arms, and the parsed-form
  compare used by both hook guards (plus its guarded parseable? variant for
  present-but-unparseable tracked input). The analysis-level proof's recursive
  temp-dir cleanup is NOT a fixture here — it calls the shared
  psi.test-support.fs/delete-recursively! directly (slice-36 follow-up: the
  previous delegation wrapper was a forwarding var by this ns's own
  definition; deleting it makes the no-forwarding-vars contract literally
  true). Kept outside the .clj-kondo/imports/ dir
  (AC2 confinement); the ns ends in -support, not -test, so kaocha's .*-test$
  ns-pattern never runs it as a suite.

  No forwarding vars: each fixture is DEFINED here once and :refer'd into the
  test namespaces (lint-config-test — unit invariants, and
  lint-config-integration-test — ^:integration proofs)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [psi.test-support.repo-root :as test-repo-root])
  (:import
   [java.util.concurrent TimeUnit]))

(def ^:private repo-root-prop
  "System property that overrides repo-root (e.g. when running from an
  editor/nrepl CWD that is not under the repo). Passed to the shared
  psi.test-support.repo-root helper's :prop option (slice-22 follow-up: the
  local find-repo-root/repo-root copy is removed — the shared helper now
  accepts the deps.edn+bb.edn marker set and this property override, so the
  override/root logic lands in one place for all consumers)."
  "psi.lint-config-test.repo-root")

(def repo-root
  "Canonical repo root. Derived via the SHARED psi.test-support.repo-root
  helper (slice-22 follow-up: the component-local find-repo-root/repo-root
  copy is deleted; the shared helper now accepts the deps.edn+bb.edn marker
  set and this ns's property override, so the override/root logic lands in
  one place for all consumers). The deps.edn+bb.edn pair is the psi repo-root
  marker (components/extensions carry their own deps.edn, so plain deps.edn
  presence is not a root marker — verified failing case; bb.edn lives only at
  the repo root). Overridable via the psi.lint-config-test.repo-root system
  property; fails with a clear message when no root is found (:required? on
  the shared helper preserves the local copy's fail-loud behavior)."
  (str (test-repo-root/repo-root
        {:markers  [["deps.edn"] ["bb.edn"]]
         :prop     repo-root-prop
         :required? true})))

(defn read-edn
  "Read a repo-relative EDN file, optionally with edn/read-string opts (e.g.
  {:readers {'kaocha/v1 identity}} for tests.edn's tagged literal)."
  ([rel-path]
   (edn/read-string (slurp (io/file repo-root rel-path))))
  ([rel-path opts]
   (edn/read-string opts (slurp (io/file repo-root rel-path)))))

(defn read-edn-or-nil
  "Guarded read of a repo-relative EDN file via the shared read-edn fixture:
  returns the parsed EDN, or nil when the file is absent/unreadable/unparseable
  (slice-45 follow-up): the `(try (read-edn rel) (catch Exception _ nil))`
  guarded-read shape was inlined at FOUR unit sites
  (http-kit-import-registration-test, with-channel-hook-impl-guard-test,
  root-config-ac2-invariant-test, http-kit-pin-sourced-from-deps-edn-test's
  extension block) and the ^:integration jar-export guard used a DIVERGENT raw
  `(try (edn/read-string (slurp tracked-file)) (catch Exception _ nil))`
  (lint_config_integration_test.clj) that bypassed the shared read-edn fixture
  entirely — no repo-root resolution, no opts, no future hardening — the exact
  duplicated-shape class slices 27/28/32 consolidated (parse-forms inline copy
  → parseable?, which-* byte-identical copies → which-bin, 3× skip-reporting
  tail → skip!) under this ns's 'each fixture is DEFINED here once' contract.
  Single definition site (mirror of parseable?): a future hardening of the
  guarded-read shape (reader opts, *read-eval* binding, error capture) or a
  regression lands in one place, and the integration site inherits read-edn's
  repo-root resolution + any hardening."
  [rel-path]
  (try (read-edn rel-path)
       (catch Exception _ nil)))

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

(defn http-client-entries
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

(defn clj-kondo-deps
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

(defn- which-bin
  "Resolve a binary from PATH via `which`, or nil when not on PATH. Single
  definition site for the which → trim → nil-on-nonzero contract (slice-28
  follow-up): which-clojure-bin and which-git-bin were byte-identical copies
  differing only in the binary name, so a future hardening (quoting, error
  handling) or a regression would diverge silently between the two sites."
  [bin]
  (some-> (shell/sh "which" bin)
          (as-> r (when (zero? (:exit r)) (str/trim (:out r))))))

(defn- which-clojure-bin
  "Resolve the clojure CLI binary from PATH, or nil when not on PATH."
  []
  (which-bin "clojure"))

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
  (which-bin "git"))

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

(defn run-bounded
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
  the streams do not close within the drain bound.

  The drain also catches an exceptionally-completed slurp future (slice-26
  follow-up): deref does not distinguish an exceptional completion from a
  timeout, so a future whose slurp THREW (e.g. an IOException reading a
  forcibly-killed process's stream on the kill paths — platform/timing-
  dependent) rethrows the wrapped ExecutionException, which would bypass the
  designed failure shapes — the slice-20 timeout ex-info carrying :out/:err
  and the slice-21 loud no-hang failure — with no captured output. drain
  therefore catches ExecutionException and returns a {::drain-error …} marker
  carrying the exception message; the ex-info construction passes the marker
  through in :out/:err (the diagnostic shows WHY the drain failed, not just
  :unavailable) and the success path's failure check treats the marker as a
  failed drain, so every path yields the designed failure shape. An
  interrupted drain returns the same {::drain-error …} marker (slice-27
  follow-up: previously a \"<label interrupted>\" STRING that escaped
  drain-failed? and silently passed as real output/content on both paths —
  asymmetric with the ExecutionException marker, which both paths treat as a
  failure); the marker is caught by drain-failed? on every path.

  A process-START failure is converted into the loud ex-info carrying :cmd and
  the exception message (slice-45 follow-up): .start sits INSIDE the try, so
  the wrong-format sub-class of the slice-43 EACCES/directory closure — a
  present-but-wrong-format binary at either injection seam (chmod +x TEXT
  file, wrong-arch binary, corrupted binary with the exec bit set — the
  .isFile/.canExecute skip-guard arms are access-mode only and pass it) —
  surfaces with context on every platform, never a bare uncaught clojure.test
  ERROR with no assertion message. (Linux CI JVM throws IOException \"error=8,
  Exec format error\" at .start; macOS .start succeeds and the subprocess
  exits 126/127 → clean assertion FAILs, the platform divergence the skip
  guards cannot pre-check.)"
  [cmd]
  (let [pb (doto (ProcessBuilder. cmd)
             (.directory (io/file repo-root)))
        drain-failed? (fn [x] (or (= ::unavailable x) (map? x)))
        drain (fn [f label]
                (try (deref f 500 ::unavailable)
                     ;; slice-27 follow-up: InterruptedException returns the
                     ;; SAME {::drain-error …} map marker as ExecutionException
                     ;; (previously a "<label interrupted>" STRING, which
                     ;; drain-failed? — ::unavailable ∨ map? — did not match,
                     ;; so an interrupted drain silently passed as real output
                     ;; on the success path and as :out/:err content on the
                     ;; timeout path, asymmetrical with slice-26's designed
                     ;; invariant that every exceptional drain yields the
                     ;; failure shape). The map marker is caught by drain-failed?
                     ;; on both paths.
                     (catch InterruptedException _
                       {::drain-error (str label ": interrupted")})
                     (catch java.util.concurrent.ExecutionException e
                       {::drain-error (str label ": " (ex-message e))})))]
    (try
      ;; slice-45 follow-up: (.start pb) moved INSIDE the try — a
      ;; present-but-wrong-format binary at either injection seam (a chmod +x
      ;; TEXT file, wrong-arch binary, corrupted binary with the exec bit set
      ;; — the .isFile/.canExecute skip-guard arms are access-mode only:
      ;; .canExecute returns true for any exec-bit regular file, verified
      ;; 2026-08-16) previously threw IOException at .start, OUTSIDE the try →
      ;; an uncaught clojure.test ERROR with no assertion message, the
      ;; exec-format sub-class of the slice-43 EACCES/directory closure:
      ;; Linux CI JVM throws "error=8, Exec format error" (ENOEXEC) while
      ;; macOS .start succeeds and the subprocess exits 126/127 → clean FAIL —
      ;; the platform divergence slice-43 named only EACCES/directory and
      ;; recorded the move-.start-inside-the-try fallback that was never
      ;; taken. The outer catch converts the start IOException into the loud
      ;; ex-info carrying :cmd (+ the exception message), so the wrong-format
      ;; class surfaces with context on every platform, never a bare uncaught
      ;; ERROR.
      (let [proc (.start pb)
            out-f (future (slurp (.getInputStream proc)))
            err-f (future (slurp (.getErrorStream proc)))]
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
              (when (or (drain-failed? out) (drain-failed? err))
                (throw (ex-info (str "subprocess exited but its stdout/stderr could "
                                     "not be drained within the bound — a descendant "
                                     "process is holding the pipe open or the stream "
                                     "read failed: " (pr-str cmd))
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
            (drain err-f "stderr"))))
      (catch java.io.IOException e
        (throw (ex-info (str "failed to start subprocess: " (pr-str cmd)
                             " — " (.getMessage e))
                        {:cmd cmd
                         :message (.getMessage e)}
                        e))))))

(defn clj-kondo-main
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

(defn report-skip!
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

(defn skip!
  "Report a skipped ^:integration proof and return the reason (slice-32
  follow-up): the three ^:integration proofs previously copy-pasted
  `(do (report-skip! label reason) (is (str \"skipped: \" reason)))`,
  differing only in the label — the support ns's 'each fixture is DEFINED
  here once' contract (the slice-27/28 consolidation standard: parse-forms
  inline copy → :refer'd fixture, which-* byte-identical copies → single
  which-bin) applies. The skip arms are now single-sourced as
  `(is (skip! label reason))`: the visible SKIP line still reaches runner
  output via report-skip! (slice-15 mechanism) and the truthy reason string
  drives the passing assertion — no clojure.test dependency in this ns."
  [label reason]
  (report-skip! label reason)
  reason)

(defn parse-forms
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

(defn parseable?
  "Parse a Clojure source string into its top-level forms via the shared
  parse-forms fixture (the *read-eval*-false / :read-cond :preserve hardening
  applies), or return nil when the input does not parse (slice-34 follow-up):
  both with_channel.clj guard sites — with-channel-hook-impl-guard-test (unit)
  and with-channel-hook-semantics-guard-test (integration) — guard DELETION
  via exists-assertions (slices 24/29), but a present-but-unparseable tracked
  impl (bad merge, hand-edit truncation, encoding corruption — the file
  exists, so both exists-guards pass) made parse-forms' read-string throw
  (\"Unmatched delimiter: ]\" / \"Unexpected EOF\") inside the `when` →
  clojure.test ERROR with no assertion message. The guarded parse turns the
  corruption class into a clean assertion FAIL (`(is (some? …))` on the
  result), never an uncaught exception — the ERROR-vs-FAIL/SKIP standard
  slices 20/24/29/30/31/32/33 established. Single definition site (mirror of
  the skip!/parse-forms consolidation): both guard sites use the same helper,
  so a future hardening or regression cannot diverge between them."
  [s]
  (try
    (parse-forms s)
    (catch Exception _ nil)))


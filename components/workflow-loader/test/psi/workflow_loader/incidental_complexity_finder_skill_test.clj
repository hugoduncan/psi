(ns psi.workflow-loader.incidental-complexity-finder-skill-test
  "Content-lock + executable-determinism tests for the incidental-complexity-finder
   skill (task 204, Deliverable 1). Split out of workflow-definitions-test to keep
   that file under the length limit; these tests are skill-focused and share no
   workflow-loader fixtures."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.prompt-assets.skills :as skills]))

(defn- incidental-complexity-finder-skill
  "Load the incidental-complexity-finder skill from the real .psi/skills dir."
  []
  (let [skills-dir (str (io/file (System/getProperty "user.dir") ".psi/skills"))
        {:keys [skills diagnostics]}
        (skills/load-skills-from-dir skills-dir :project true)
        skill (first (filter #(= "incidental-complexity-finder" (:name %)) skills))
        skill-diagnostics (filter #(re-find #"incidental-complexity-finder" (str (:path %)))
                                  diagnostics)]
    {:skill skill :skill-diagnostics skill-diagnostics}))

(deftest incidental-complexity-finder-skill-registers-test
  (testing "the incidental-complexity-finder skill is discoverable and loads with no diagnostics"
    (let [{:keys [skill skill-diagnostics]} (incidental-complexity-finder-skill)]
      (is (some? skill) "incidental-complexity-finder skill is discovered")
      (is (seq (:description skill)) "skill has a description for progressive disclosure")
      (is (empty? skill-diagnostics)
          "skill loads without warnings or errors"))))

(defn- extract-jq-recipe
  "Extract the join/gap fenced jq recipe (the one beginning with `jq -n`) from
   the SKILL.md body, returning the recipe body without the ``` fences."
  [skill-md]
  (some->> (re-seq #"(?s)```\n(jq -n.*?)\n```" skill-md)
           first
           second))

(deftest incidental-complexity-finder-skill-content-lock-test
  ;; TR1 (test review): the skill's central behaviours (the design's
  ;; Deliverable-1 acceptance) were untested — the registration test asserts
  ;; only discovery + a non-empty description. A skill whose recipe body is
  ;; empty, paraphrased, or regressed to the pre-F2 `(ns, var, arity)` key would
  ;; pass that test green. Lock the SKILL.md content on the behaviours it exists
  ;; to encode (mirroring how the workflow tests anchor on prompt substrings).
  (let [{:keys [skill]} (incidental-complexity-finder-skill)
        body (slurp (io/file (:file-path skill)))]
    (testing "skill carries a loadable SKILL.md body"
      (is (some? (:file-path skill)) "skill exposes its SKILL.md file-path")
      (is (seq body) "SKILL.md body is non-empty"))
    (testing "encodes the gap method and the qualification thresholds"
      (is (.contains body "gap = lcc-total / max(cc, 1)")
          "documents the gap = lcc-total / max(cc, 1) method")
      (is (re-find #"lcc-total\s*≥\s*5\.0" body)
          "states the lcc-total ≥ 5.0 threshold")
      (is (re-find #"gap\s*≥\s*2\.0" body)
          "states the gap ≥ 2.0 threshold"))
    (testing "states the single-executable-unit scope (false-positive guard)"
      (is (.contains body "exactly one executable unit")
          "scopes selection to exactly one executable unit")
      (is (re-find #"(?s)High cc alone is not a\s+target" body)
          "encodes the high-cc-alone false-positive guard"))
    (testing "encodes the A1 unmatched-row drop rule (never default cc=1)"
      (is (re-find #"(?s)no matching `cc` row is dropped" body)
          "an unmatched local row is dropped")
      (is (re-find #"(?s)\*\*never\*\* defaulted to `cc = 1`" body)
          "an unmatched local row is never defaulted to cc=1"))
    (testing "encodes the F2 (ns, var, arity, line)/@line join key"
      (is (.contains body "(ns, var, arity, line)")
          "documents the (ns, var, arity, line) join key (F2)")
      (is (.contains body "@line")
          "documents the @line key suffix that makes the join key unique"))
    (testing "frontmatter lambda join key matches the F2 (ns, var, arity, line) key (F5)"
      ;; F5: the one-line behavioural summary in the frontmatter lambda is a
      ;; join-key statement; it must track the F2/F3/F4 (ns, var, arity, line)
      ;; key, not regress to the pre-F2 join(ns,var,arity).
      (is (.contains body "join(ns,var,arity,line)")
          "frontmatter lambda joins on (ns, var, arity, line)")
      (is (not (re-find #"join\(ns,var,arity\)" body))
          "frontmatter lambda does not regress to the pre-F2 join(ns,var,arity) key"))
    (testing "encodes the step-5 top-5 essential-vs-incidental judgment guard (TR3)"
      ;; The design's core discriminator (Locked decisions 1/2/9). Without the
      ;; top-5 judgment procedure the skill degenerates to the `gordian
      ;; complexity` ranking it exists *not* to be — the locked high-cc-alone
      ;; string above is the rationale, not this procedure.
      (is (.contains body "top 5 qualifying units by `gap`")
          "instructs reading the top 5 qualifying units by gap")
      (is (.contains body "Reject as **essential**")
          "encodes the essential-complexity rejection (false positives)")
      (is (.contains body "Choose the first unit (highest `gap`) that passes the guard")
          "chooses the first guard-passing unit by gap")
      (is (.contains body "If none of the top 5 pass")
          "reports no target when none of the top 5 pass the guard"))
    (testing "encodes the step-6 evidence + coverage-hint emission (TR3)"
      ;; The design's first acceptance is "produces a target + evidence", and
      ;; the coverage hint is a named required emitted field.
      (is (.contains body "coverage hint")
          "emits a coverage hint with the chosen target")
      (is (re-find #"(?s)sibling test namespace exists for the target" body)
          "coverage hint reports whether a sibling test namespace exists")
      (is (re-find #"(?s)any test references the target `var`" body)
          "coverage hint reports whether any test references the target var"))))

(defn- local-unit-json
  "A synthetic `local`-lens unit JSON object for a null-arity (defmethod-style)
   var `x/f`, distinguished only by `line`/`lcc-total`."
  [line lcc-total]
  (str "{\"ns\":\"x\",\"var\":\"f\",\"arity\":null,\"line\":" line ",\"end-line\":" (+ line 10) ","
       "\"lcc-total\":" lcc-total ",\"flow-burden\":1,\"state-burden\":1,\"shape-burden\":1,"
       "\"abstraction-burden\":1,\"dependency-burden\":1,\"working-set\":1,\"file\":\"x.clj\",\"findings\":[]}"))

(defn- cc-unit-json
  "A synthetic `complexity`-lens unit JSON object for null-arity var `x/f`."
  [line cc]
  (str "{\"ns\":\"x\",\"var\":\"f\",\"arity\":null,\"line\":" line ",\"cc\":" cc "}"))

(defn- named-local-unit-json
  "A synthetic `local`-lens unit JSON for a named null-arity var `<ns>/<var>`,
   distinguished by `line`/`lcc-total`. Used by the filter/drop coverage tests
   (TR6) to feed identifiable units whose presence/absence is unambiguous."
  [unit-ns unit-var line lcc-total]
  (str "{\"ns\":\"" unit-ns "\",\"var\":\"" unit-var "\",\"arity\":null,\"line\":" line ","
       "\"end-line\":" (+ line 10) ",\"lcc-total\":" lcc-total ",\"flow-burden\":1,"
       "\"state-burden\":1,\"shape-burden\":1,\"abstraction-burden\":1,\"dependency-burden\":1,"
       "\"working-set\":1,\"file\":\"" unit-ns ".clj\",\"findings\":[]}"))

(defn- named-cc-unit-json
  "A synthetic `complexity`-lens unit JSON for named null-arity var `<ns>/<var>`."
  [unit-ns unit-var line cc]
  (str "{\"ns\":\"" unit-ns "\",\"var\":\"" unit-var "\",\"arity\":null,\"line\":" line ",\"cc\":" cc "}"))

(defn- run-jq-recipe
  "Run the SKILL.md jq recipe over the given `local`/`cc` unit-JSON sequences,
   returning {:exit :out :err}. Rewrites the recipe's hard-coded /tmp paths to
   per-call temp fixtures so emit order is fully controlled by the caller."
  [recipe local-units cc-units]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "icf-recipe-test-" (System/nanoTime)))
        _ (.mkdirs dir)
        loc-f (io/file dir "icf-local.json")
        cc-f (io/file dir "icf-cc.json")
        recipe' (-> recipe
                    (str/replace "/tmp/icf-local.json" (.getAbsolutePath loc-f))
                    (str/replace "/tmp/icf-cc.json" (.getAbsolutePath cc-f)))]
    (try
      (spit loc-f (str "{\"units\":[" (str/join "," local-units) "]}"))
      (spit cc-f (str "{\"units\":[" (str/join "," cc-units) "]}"))
      (shell/sh "bash" "-c" recipe')
      (finally
        (doseq [f (.listFiles dir)] (.delete f))
        (.delete dir)))))

(deftest incidental-complexity-finder-recipe-determinism-test
  ;; TR1 (executable lock for the F2 determinism fix the prior passes deferred):
  ;; run the SKILL.md's embedded jq recipe over a synthetic input with two
  ;; same-named null-arity (defmethod-style) units that differ only by `line`,
  ;; and assert the join is lossless — each unit keeps its own distinct `cc`.
  ;; A regress to the pre-F2 `(ns, var, arity)` key collapses both onto one
  ;; `from_entries` slot (last-wins), so one unit would inherit the other's cc.
  (let [{:keys [skill]} (incidental-complexity-finder-skill)
        body (slurp (io/file (:file-path skill)))
        recipe (extract-jq-recipe body)
        ;; two execute-effect!-style null-arity units sharing (ns, var, arity)
        ;; but distinct lines and distinct cc/burden, both above threshold.
        line-10-local (local-unit-json 10 "30.0")
        line-40-local (local-unit-json 40 "60.0")
        line-10-cc (cc-unit-json 10 3)
        line-40-cc (cc-unit-json 40 4)]
    (is (some? recipe) "the join/gap jq recipe is extractable from SKILL.md")
    (if (try (zero? (:exit (shell/sh "jq" "--version"))) (catch Exception _ false))
      (do
        (testing "the join is lossless — each null-arity unit keeps its own cc"
          (let [{:keys [exit out err]}
                (run-jq-recipe recipe [line-10-local line-40-local] [line-10-cc line-40-cc])]
            (is (zero? exit) (str "recipe runs cleanly; stderr: " err))
            ;; line 10 unit (cc 3) and line 40 unit (cc 4) both survive
            ;; with their OWN cc — losslessly keyed by (ns, var, arity, line).
            (is (re-find #"\"line\":\s*10" out)
                "the line-10 null-arity unit survives the join")
            (is (re-find #"\"line\":\s*40" out)
                "the line-40 null-arity unit survives the join")
            (is (re-find #"\"cc\":\s*3\b" out)
                "the line-10 unit keeps its own cc=3 (not collapsed last-wins)")
            (is (re-find #"\"cc\":\s*4\b" out)
                "the line-40 unit keeps its own cc=4 (not collapsed last-wins)")))
        (testing "the join is order-independent — reversing emit order yields identical output (TR4)"
          ;; The F2/A1 core claim is that the join is deterministic w.r.t. emit
          ;; order: under the pre-F2 `(ns, var, arity)` key, `from_entries` is
          ;; last-wins, so swapping the two null-arity units' emit order would
          ;; swap which cc each inherits. Run the recipe with both inputs'
          ;; emit order reversed and assert byte-identical output to the forward
          ;; run — losslessness alone (above) does not prove this.
          (let [forward (run-jq-recipe recipe
                                       [line-10-local line-40-local]
                                       [line-10-cc line-40-cc])
                reversed (run-jq-recipe recipe
                                        [line-40-local line-10-local]
                                        [line-40-cc line-10-cc])]
            (is (zero? (:exit forward)) "forward-order recipe runs cleanly")
            (is (zero? (:exit reversed)) "reversed-order recipe runs cleanly")
            (is (= (:out forward) (:out reversed))
                "reversing the emit order of both inputs yields identical output"))))
      (testing "jq unavailable — determinism asserted structurally on the recipe key"
        ;; Fallback when jq is absent: lock that the recipe keys on @line (the
        ;; unique key) on BOTH the $ccmap build and the $loc gap_key, so the
        ;; from_entries map cannot collapse same-named null-arity units.
        (is (= 2 (count (re-seq #"\+ \"@\" \+ \(\.line\|tostring\)" recipe)))
            "recipe keys both the cc map and the local gap_key on @line")))))

(deftest incidental-complexity-finder-recipe-filter-and-drop-test
  ;; TR6 (test review pass 4): the selector recipe's qualification filter
  ;; (`lcc-total >= 5.0 and gap >= 2.0`) and A1 unmatched-row drop rule were
  ;; locked only as SKILL.md prose substrings, never exercised. Run the embedded
  ;; jq recipe over inputs that hit both branches and assert behaviour:
  ;;   (a) filter — only the above-threshold unit survives;
  ;;   (b) drop — an unmatched `local` row is absent (dropped), not defaulted to
  ;;       cc=1 (which A1 explicitly forbids; defaulting would inflate gap into
  ;;       false qualification).
  ;; A regress (>= → > threshold typo, or defaulting unmatched rows to cc=1)
  ;; would pass the prose-only locks above but fail here.
  (let [{:keys [skill]} (incidental-complexity-finder-skill)
        body (slurp (io/file (:file-path skill)))
        recipe (extract-jq-recipe body)]
    (is (some? recipe) "the join/gap jq recipe is extractable from SKILL.md")
    (when (try (zero? (:exit (shell/sh "jq" "--version"))) (catch Exception _ false))
      (testing "qualification filter — only units with lcc-total >= 5.0 and gap >= 2.0 survive"
        ;; keep/qual:  lcc 30.0, cc 4  -> gap 7.5  -> qualifies
        ;; drop/lowgap: lcc 30.0, cc 20 -> gap 1.5  -> fails gap >= 2.0
        ;; drop/lowlcc: lcc 4.0,  cc 1  -> gap 4.0 but lcc < 5.0 -> fails
        (let [{:keys [exit out err]}
              (run-jq-recipe recipe
                             [(named-local-unit-json "keep" "qual" 10 "30.0")
                              (named-local-unit-json "drop" "lowgap" 20 "30.0")
                              (named-local-unit-json "drop" "lowlcc" 30 "4.0")]
                             [(named-cc-unit-json "keep" "qual" 10 4)
                              (named-cc-unit-json "drop" "lowgap" 20 20)
                              (named-cc-unit-json "drop" "lowlcc" 30 1)])]
          (is (zero? exit) (str "recipe runs cleanly; stderr: " err))
          (is (re-find #"\"var\":\s*\"qual\"" out)
              "the above-threshold unit (lcc 30, gap 7.5) survives the filter")
          (is (not (re-find #"\"var\":\s*\"lowgap\"" out))
              "a unit failing gap >= 2.0 (gap 1.5) is filtered out")
          (is (not (re-find #"\"var\":\s*\"lowlcc\"" out))
              "a unit failing lcc-total >= 5.0 (lcc 4.0) is filtered out")))
      (testing "A1 drop rule — an unmatched `local` row is dropped, never defaulted to cc=1"
        ;; matched:   lcc 30.0, cc 4  -> gap 7.5  -> qualifies, survives.
        ;; unmatched: lcc 30.0, no cc row. If A1 were violated (defaulted cc=1),
        ;;            gap would be 30.0 and it would qualify + survive; A1 drops it.
        (let [{:keys [exit out err]}
              (run-jq-recipe recipe
                             [(named-local-unit-json "matched" "present" 10 "30.0")
                              (named-local-unit-json "unmatched" "absent" 20 "30.0")]
                             [(named-cc-unit-json "matched" "present" 10 4)])]
          (is (zero? exit) (str "recipe runs cleanly; stderr: " err))
          (is (re-find #"\"var\":\s*\"present\"" out)
              "a matched qualifying unit survives the inner join")
          (is (not (re-find #"\"var\":\s*\"absent\"" out))
              "an unmatched local row is dropped, not defaulted to cc=1 (A1)"))))))

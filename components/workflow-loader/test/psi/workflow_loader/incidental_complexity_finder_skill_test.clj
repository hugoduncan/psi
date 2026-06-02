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
          "documents the @line key suffix that makes the join key unique"))))

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
        local-json (str "{\"units\":["
                        "{\"ns\":\"x\",\"var\":\"f\",\"arity\":null,\"line\":10,\"end-line\":20,"
                        "\"lcc-total\":30.0,\"flow-burden\":1,\"state-burden\":1,\"shape-burden\":1,"
                        "\"abstraction-burden\":1,\"dependency-burden\":1,\"working-set\":1,\"file\":\"x.clj\",\"findings\":[]},"
                        "{\"ns\":\"x\",\"var\":\"f\",\"arity\":null,\"line\":40,\"end-line\":50,"
                        "\"lcc-total\":60.0,\"flow-burden\":1,\"state-burden\":1,\"shape-burden\":1,"
                        "\"abstraction-burden\":1,\"dependency-burden\":1,\"working-set\":1,\"file\":\"x.clj\",\"findings\":[]}"
                        "]}")
        cc-json (str "{\"units\":["
                     "{\"ns\":\"x\",\"var\":\"f\",\"arity\":null,\"line\":10,\"cc\":3},"
                     "{\"ns\":\"x\",\"var\":\"f\",\"arity\":null,\"line\":40,\"cc\":4}"
                     "]}")]
    (is (some? recipe) "the join/gap jq recipe is extractable from SKILL.md")
    (if (try (zero? (:exit (shell/sh "jq" "--version"))) (catch Exception _ false))
      (let [dir (io/file (System/getProperty "java.io.tmpdir")
                         (str "icf-recipe-test-" (System/nanoTime)))
            _ (.mkdirs dir)
            loc-f (io/file dir "icf-local.json")
            cc-f (io/file dir "icf-cc.json")
            ;; the recipe reads /tmp/icf-local.json + /tmp/icf-cc.json; rewrite
            ;; those paths to the temp fixture files for the executable check.
            recipe' (-> recipe
                        (str/replace "/tmp/icf-local.json" (.getAbsolutePath loc-f))
                        (str/replace "/tmp/icf-cc.json" (.getAbsolutePath cc-f)))]
        (try
          (spit loc-f local-json)
          (spit cc-f cc-json)
          (let [{:keys [exit out err]} (shell/sh "bash" "-c" recipe')]
            (is (zero? exit) (str "recipe runs cleanly; stderr: " err))
            ;; line 10 unit (cc 3) and line 40 unit (cc 6/lcc 60) both survive
            ;; with their OWN cc — losslessly keyed by (ns, var, arity, line).
            (is (re-find #"\"line\":\s*10" out)
                "the line-10 null-arity unit survives the join")
            (is (re-find #"\"line\":\s*40" out)
                "the line-40 null-arity unit survives the join")
            (is (re-find #"\"cc\":\s*3\b" out)
                "the line-10 unit keeps its own cc=3 (not collapsed last-wins)")
            (is (re-find #"\"cc\":\s*4\b" out)
                "the line-40 unit keeps its own cc=4 (not collapsed last-wins)"))
          (finally
            (doseq [f (.listFiles dir)] (.delete f))
            (.delete dir))))
      (testing "jq unavailable — determinism asserted structurally on the recipe key"
        ;; Fallback when jq is absent: lock that the recipe keys on @line (the
        ;; unique key) on BOTH the $ccmap build and the $loc gap_key, so the
        ;; from_entries map cannot collapse same-named null-arity units.
        (is (= 2 (count (re-seq #"\+ \"@\" \+ \(\.line\|tostring\)" recipe)))
            "recipe keys both the cc map and the local gap_key on @line")))))

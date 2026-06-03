(ns psi.workflow-loader.incidental-complexity-finder-skill-test
  "Content-lock + executable-determinism tests for the incidental-complexity-finder
   skill (task 209, Deliverable 1). Split out of workflow-definitions-test to keep
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

(defn- jq-available?
  "Is the `jq` CLI available on this machine? Gates the executable recipe tests
   (which degrade to a structural fallback when jq is absent)."
  []
  (try (zero? (:exit (shell/sh "jq" "--version"))) (catch Exception _ false)))

(defn- bb-available?
  "Is the `bb` (babashka) CLI available on this machine? Gates the narrow
   integration test that runs the real `bb gordian` lenses against this repo."
  []
  (try (zero? (:exit (shell/sh "bb" "--version"))) (catch Exception _ false)))

(defn- skill-recipe
  "Slurp the loaded SKILL.md and extract its embedded join/gap jq recipe. Single
   helper for every executable recipe test (their shared body-slurp + recipe-
   extract preamble)."
  []
  (let [{:keys [skill]} (incidental-complexity-finder-skill)]
    (extract-jq-recipe (slurp (io/file (:file-path skill))))))

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
          "coverage hint reports whether any test references the target var"))
    (testing "encodes the step-1 two-lens invocation commands (TT-F)"
      ;; The design's Deliverable-1 step 1 ("Run both lenses in machine form")
      ;; names the two data-source commands that produce the jq recipe's inputs.
      ;; The recipe-execution tests rewrite the temp paths and never exercise
      ;; the producing commands; a regress (wrong subcommand, dropped `--json`,
      ;; or losing the selector-vs-baseline `--sort total`/bare distinction A5/A2
      ;; draws) would pass green while breaking the recipe inputs. TT-C already
      ;; enforces the symmetric lock for the *baseline* capture commands in the
      ;; workflow test.
      (is (.contains body "bb gordian local --sort total --json")
          "emits the selector `local` lens command with --sort total --json")
      (is (.contains body "bb gordian complexity --json")
          "emits the `complexity` lens command with --json"))
    (testing "recipe reads each lens's top-level `.units` array (TR22 fast-suite real-shape lock)"
      ;; TR22: the recipe's real-lens `.units`-shape assumption was guarded only
      ;; inside the slow `incidental-complexity-finder-real-lens-integration-test`
      ;; jq/bb-absent fallback; once that deftest is `^:integration`-tagged it no
      ;; longer runs in the fast `:unit` suite. Hoist the structural lock here so
      ;; a gordian JSON reshape (wrapping/renaming the top-level units array)
      ;; still fails the fast suite green.
      (is (.contains body "$cc[0].units")
          "recipe reads the complexity lens top-level units array")
      (is (.contains body "$loc[0].units")
          "recipe reads the local lens top-level units array"))))

(defn- named-local-unit-json
  "A synthetic `local`-lens unit JSON for a named null-arity var `<ns>/<var>`,
   distinguished by `line`/`lcc-total`. Used by the recipe coverage tests to
   feed identifiable units whose presence/absence is unambiguous."
  [unit-ns unit-var line lcc-total]
  (str "{\"ns\":\"" unit-ns "\",\"var\":\"" unit-var "\",\"arity\":null,\"line\":" line ","
       "\"end-line\":" (+ line 10) ",\"lcc-total\":" lcc-total ",\"flow-burden\":1,"
       "\"state-burden\":1,\"shape-burden\":1,\"abstraction-burden\":1,\"dependency-burden\":1,"
       "\"working-set\":1,\"file\":\"" unit-ns ".clj\",\"findings\":[]}"))

(defn- named-cc-unit-json
  "A synthetic `complexity`-lens unit JSON for named null-arity var `<ns>/<var>`."
  [unit-ns unit-var line cc]
  (str "{\"ns\":\"" unit-ns "\",\"var\":\"" unit-var "\",\"arity\":null,\"line\":" line ",\"cc\":" cc "}"))

(defn- run-recipe-over-lens-output
  "Write the two lens JSON payloads to the recipe's temp-file paths and run the
   rewritten recipe over them, returning {:exit :out :err}. Owns the temp-file
   ceremony (per-call temp dir, the recipe's hard-coded /tmp path rewrite, and
   cleanup) so emit order is fully controlled by the caller. Feeds the lens
   output verbatim — the real `bb gordian` payload already carries the top-level
   `.units` array the recipe reads; `run-jq-recipe` wraps synthetic units before
   delegating here."
  [recipe local-json cc-json]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "icf-recipe-test-" (System/nanoTime)))
        _ (.mkdirs dir)
        loc-f (io/file dir "icf-local.json")
        cc-f (io/file dir "icf-cc.json")
        recipe' (-> recipe
                    (str/replace "/tmp/icf-local.json" (.getAbsolutePath loc-f))
                    (str/replace "/tmp/icf-cc.json" (.getAbsolutePath cc-f)))]
    (try
      (spit loc-f local-json)
      (spit cc-f cc-json)
      (shell/sh "bash" "-c" recipe')
      (finally
        (doseq [f (.listFiles dir)] (.delete f))
        (.delete dir)))))

(defn- run-jq-recipe
  "Run the SKILL.md jq recipe over the given `local`/`cc` unit-JSON sequences,
   returning {:exit :out :err}. Wraps the synthetic units in the top-level
   `{\"units\":[…]}` envelope the recipe reads, then delegates the temp-file
   ceremony to `run-recipe-over-lens-output`."
  [recipe local-units cc-units]
  (run-recipe-over-lens-output
   recipe
   (str "{\"units\":[" (str/join "," local-units) "]}")
   (str "{\"units\":[" (str/join "," cc-units) "]}")))

(deftest incidental-complexity-finder-recipe-determinism-test
  ;; TR1 (executable lock for the F2 determinism fix the prior passes deferred):
  ;; run the SKILL.md's embedded jq recipe over a synthetic input with two
  ;; same-named null-arity (defmethod-style) units that differ only by `line`,
  ;; and assert the join is lossless — each unit keeps its own distinct `cc`.
  ;; A regress to the pre-F2 `(ns, var, arity)` key collapses both onto one
  ;; `from_entries` slot (last-wins), so one unit would inherit the other's cc.
  (let [recipe (skill-recipe)
        ;; two execute-effect!-style null-arity units sharing (ns, var, arity)
        ;; but distinct lines and distinct cc/burden, both above threshold.
        line-10-local (named-local-unit-json "x" "f" 10 "30.0")
        line-40-local (named-local-unit-json "x" "f" 40 "60.0")
        line-10-cc (named-cc-unit-json "x" "f" 10 3)
        line-40-cc (named-cc-unit-json "x" "f" 40 4)]
    (is (some? recipe) "the join/gap jq recipe is extractable from SKILL.md")
    (if (jq-available?)
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
  (let [recipe (skill-recipe)]
    (is (some? recipe) "the join/gap jq recipe is extractable from SKILL.md")
    (if (jq-available?)
      (do
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
                "an unmatched local row is dropped, not defaulted to cc=1 (A1)"))))
      (testing "jq unavailable — filter + drop asserted structurally on the recipe"
       ;; TR12 fallback (mirrors the determinism test): when jq is absent, lock
       ;; the recipe fragments each behaviour depends on so a regress fails green
       ;; whether or not jq is installed.
        (is (.contains recipe "select(.[\"lcc-total\"] >= 5.0 and .gap >= 2.0)")
            "recipe carries the lcc-total >= 5.0 and gap >= 2.0 qualification filter")
        (is (.contains recipe "select($ccmap[.gap_key] != null)")
            "recipe inner-joins on the local side, dropping unmatched local rows (A1)")))))

(deftest incidental-complexity-finder-recipe-ranking-and-cap-test
  ;; TR9 (test review pass 7): the selector recipe's gap-descending ranking
  ;; (`sort_by(-.gap)`) and top-5 cap (`.[0:5]`) are encoded only in the embedded
  ;; jq recipe and never exercised — every other skill test feeds <=3 units, just
  ;; enough for the join/determinism/filter/drop branches. Both are named
  ;; Deliverable-1 behaviours (design step 4 / Locked decision 2: "Rank
  ;; qualifying units by `gap`"; the step-5 guard reads "the top 5 qualifying
  ;; units by `gap`"). A regress to sort_by(.gap) (ascending) or a dropped/widened
  ;; slice passes every existing test green; the TR3 content-lock asserts only the
  ;; SKILL prose, which can drift from the executed recipe.
  (let [recipe (skill-recipe)
        gap-of (fn [out var-name]
                 ;; pull the "gap": <n> following this unit's var in the output.
                 (some-> (re-find (re-pattern (str "(?s)\"var\":\\s*\"" var-name
                                                   "\".*?\"gap\":\\s*([0-9.]+)"))
                                  out)
                         second
                         Double/parseDouble))]
    (is (some? recipe) "the join/gap jq recipe is extractable from SKILL.md")
    (if (jq-available?)
      (do
        (testing "gap-descending ranking — output gap values are strictly descending (sort_by(-.gap))"
        ;; Feed three qualifying units whose input emit order (lowmid, top, mid)
        ;; differs from their gap order (top > mid > lowmid). A regress to
        ;; sort_by(.gap) (ascending) would emit them lowmid < mid < top, failing
        ;; the strictly-descending assertion below.
        ;;   top:    lcc 100, cc 4 -> gap 25.0
        ;;   mid:    lcc 60,  cc 4 -> gap 15.0
        ;;   lowmid: lcc 30,  cc 4 -> gap 7.5
          (let [{:keys [exit out err]}
                (run-jq-recipe recipe
                               [(named-local-unit-json "rank" "lowmid" 30 "30.0")
                                (named-local-unit-json "rank" "top" 10 "100.0")
                                (named-local-unit-json "rank" "mid" 20 "60.0")]
                               [(named-cc-unit-json "rank" "lowmid" 30 4)
                                (named-cc-unit-json "rank" "top" 10 4)
                                (named-cc-unit-json "rank" "mid" 20 4)])
                ;; TR24: the descending check must be derived from the *output*
                ;; order, not a name-keyed lookup. `name-keyed-gaps` looks up each
                ;; unit's gap by var, so it is always [25.0 15.0 7.5] regardless of
                ;; serialized order — comparing it to its own descending sort can
                ;; never fail (tautology). `output-gaps` collects the `gap` values
                ;; in serialized appearance order, so a regress to sort_by(.gap)
                ;; yields [7.5 15.0 25.0] and fails the descending assertion below.
                name-keyed-gaps [(gap-of out "top") (gap-of out "mid") (gap-of out "lowmid")]
                output-gaps (mapv (comp Double/parseDouble second)
                                  (re-seq #"\"gap\":\s*([0-9.]+)" out))]
            (is (zero? exit) (str "recipe runs cleanly; stderr: " err))
            (is (every? some? name-keyed-gaps) "all three qualifying units survive with a gap")
            (is (= 3 (count output-gaps)) "all three qualifying units appear in the output")
            (is (= output-gaps (reverse (sort output-gaps)))
                "output gap values are in strictly descending order (sort_by(-.gap), not ascending)")
          ;; positional check: pin all three positions (top before mid before
          ;; lowmid), so a `top, lowmid, mid` misorder fails too — not just the
          ;; top/lowmid pair, which left the middle element unconstrained.
            (is (< (.indexOf out "\"var\": \"top\"")
                   (.indexOf out "\"var\": \"mid\""))
                "the highest-gap unit appears before the middle-gap unit in the output")
            (is (< (.indexOf out "\"var\": \"mid\"")
                   (.indexOf out "\"var\": \"lowmid\""))
                "the middle-gap unit appears before the lowest-gap unit in the output")))
        (testing "top-5 cap — exactly 5 qualifying units survive when more than 5 qualify (.[0:5])"
        ;; Feed 7 qualifying units (all lcc 50, cc 4 -> gap 12.5, all above
        ;; threshold) distinguished by var/line. The recipe slices `.[0:5]`, so
        ;; exactly 5 must survive. A regress dropping the slice (or `.[0:10]`)
        ;; would emit all 7.
          (let [local-units (for [i (range 7)]
                              (named-local-unit-json "cap" (str "u" i) (* 10 (inc i)) "50.0"))
                cc-units (for [i (range 7)]
                           (named-cc-unit-json "cap" (str "u" i) (* 10 (inc i)) 4))
                {:keys [exit out err]} (run-jq-recipe recipe local-units cc-units)
                survivors (count (re-seq #"\"ns\":\s*\"cap\"" out))]
            (is (zero? exit) (str "recipe runs cleanly; stderr: " err))
            (is (= 5 survivors)
                "exactly 5 of 7 qualifying units survive the top-5 cap (.[0:5])"))))
      (testing "jq unavailable — ranking + cap asserted structurally on the recipe"
       ;; TR12 fallback (mirrors the determinism test): when jq is absent, lock
       ;; the recipe fragments each behaviour depends on so a regress fails green
       ;; whether or not jq is installed.
        (is (.contains recipe "sort_by(-.gap)")
            "recipe ranks qualifying units gap-descending (sort_by(-.gap), not ascending)")
        (is (.contains recipe ".[0:5]")
            "recipe caps the ranked output at the top 5 units (.[0:5])")))))

(deftest incidental-complexity-finder-recipe-max-cc-guard-test
  ;; TR16 (test review pass 14): the recipe's `max(cc, 1)` divide-by-zero guard
  ;; (`gap: (.["lcc-total"] / ([$ccmap[.gap_key], 1] | max))`) is named by SKILL
  ;; §3 as a distinct A1 behaviour ("`max(cc, 1)` guards only the matched zero-cc
  ;; case"), but every other executable recipe test feeds cc >= 1, so the guard
  ;; is never exercised. A regress dropping `| max` (dividing by a bare cc 0)
  ;; would yield gap = null / a divide error for a matched zero-cc unit, yet pass
  ;; every existing test green. Feed a single matched unit with lcc above
  ;; threshold and cc 0 and assert it survives with gap = lcc-total (max(0,1)=1).
  (let [recipe (skill-recipe)]
    (is (some? recipe) "the join/gap jq recipe is extractable from SKILL.md")
    (if (jq-available?)
      (testing "max(cc, 1) guard — a matched zero-cc unit survives with gap = lcc-total"
        ;; matched unit: lcc 30.0, cc 0. With the `max(cc, 1)` guard the divisor
        ;; is 1, so gap = 30.0 (qualifies). Without the guard the divisor is 0,
        ;; yielding gap = null and failing the `gap >= 2.0` qualification filter,
        ;; so the unit would be dropped.
        (let [{:keys [exit out err]}
              (run-jq-recipe recipe
                             [(named-local-unit-json "zero" "cc" 10 "30.0")]
                             [(named-cc-unit-json "zero" "cc" 10 0)])]
          (is (zero? exit) (str "recipe runs cleanly; stderr: " err))
          (is (re-find #"\"var\":\s*\"cc\"" out)
              "the matched zero-cc unit survives the join + qualification filter")
          (is (re-find #"\"gap\":\s*30\b" out)
              "gap = lcc-total (30) for the zero-cc unit — max(cc, 1) divides by 1, not 0")))
      (testing "jq unavailable — max(cc, 1) guard asserted structurally on the recipe"
        ;; TR16 fallback (mirrors TR12): lock the recipe fragment the zero-cc
        ;; guard depends on so a regress fails green whether or not jq is present.
        (is (.contains recipe "[$ccmap[.gap_key], 1] | max")
            "recipe guards the gap divisor with max(cc, 1) (matched zero-cc case)")))))

(deftest incidental-complexity-finder-recipe-empty-qualification-test
  ;; TR17 (test review pass 14): Locked decision 2 ("A real early-stop exists when
  ;; nothing qualifies") and the recipe's `[]` emission are the machine signal
  ;; driving the workflow's early stop, but this is locked only as SKILL prose
  ;; (TR3) — no executable test asserts the recipe emits an empty result when the
  ;; qualification filter removes every candidate (the filter-and-drop test
  ;; always leaves >= 1 survivor). Feed only sub-threshold / unmatched units and
  ;; assert the recipe emits an empty result.
  (let [recipe (skill-recipe)]
    (is (some? recipe) "the join/gap jq recipe is extractable from SKILL.md")
    (if (jq-available?)
      (testing "empty qualification — the recipe emits [] when no unit qualifies (early-stop signal)"
        ;; sub-threshold matched unit (lcc 4.0 < 5.0) + an unmatched local row
        ;; (no cc, dropped by A1): nothing qualifies, so the recipe must emit [].
        (let [{:keys [exit out err]}
              (run-jq-recipe recipe
                             [(named-local-unit-json "sub" "threshold" 10 "4.0")
                              (named-local-unit-json "unmatched" "row" 20 "30.0")]
                             [(named-cc-unit-json "sub" "threshold" 10 1)])]
          (is (zero? exit) (str "recipe runs cleanly; stderr: " err))
          (is (= "[]" (str/trim out))
              "the recipe emits an empty result when nothing qualifies (early-stop signal)")
          (is (not (re-find #"\"var\":" out))
              "no surviving units appear in the empty-qualification output")))
      (testing "jq unavailable — empty-qualification path covered by the structural filter lock"
        ;; The empty case is the qualification filter removing every candidate;
        ;; no recipe fragment uniquely guards the empty result beyond that filter
        ;; (already structurally locked in the filter-and-drop test's fallback),
        ;; so this behaviour is jq-required. Assert the filter fragment is present
        ;; so a regress is still caught structurally.
        (is (.contains recipe "select(.[\"lcc-total\"] >= 5.0 and .gap >= 2.0)")
            "recipe carries the qualification filter whose empty result is the early-stop signal")))))

(deftest incidental-complexity-finder-recipe-boundary-inclusivity-test
  ;; TR18 (test review pass 15 — test-shaper): the qualification filter
  ;; `select(.["lcc-total"] >= 5.0 and .gap >= 2.0)` is exercised only well above
  ;; (lcc 30 / gap 7.5) and well below (gap 1.5, lcc 4.0) the thresholds, so the
  ;; inclusive `>=` boundary is unproven — a regress `>=` -> `>` (strict) passes
  ;; every existing test green. Feed units that sit EXACTLY on each boundary and
  ;; assert they survive the filter (inclusive `>=`, not strict `>`).
  (let [recipe (skill-recipe)]
    (is (some? recipe) "the join/gap jq recipe is extractable from SKILL.md")
    (if (jq-available?)
      (testing "qualification filter is inclusive at the exact thresholds (>=, not >)"
        ;; gapedge:  lcc 10.0, cc 5 -> gap exactly 2.0 (gap == 2.0 boundary)
        ;; lccedge:  lcc 5.0,  cc 1 -> lcc exactly 5.0, gap 5.0 (lcc == 5.0 boundary)
        ;; A strict `>` regress drops either boundary unit.
        (let [{:keys [exit out err]}
              (run-jq-recipe recipe
                             [(named-local-unit-json "edge" "gapedge" 10 "10.0")
                              (named-local-unit-json "edge" "lccedge" 20 "5.0")]
                             [(named-cc-unit-json "edge" "gapedge" 10 5)
                              (named-cc-unit-json "edge" "lccedge" 20 1)])]
          (is (zero? exit) (str "recipe runs cleanly; stderr: " err))
          (is (re-find #"\"var\":\s*\"gapedge\"" out)
              "a unit with gap exactly 2.0 survives the inclusive gap >= 2.0 filter")
          (is (re-find #"\"var\":\s*\"lccedge\"" out)
              "a unit with lcc-total exactly 5.0 survives the inclusive lcc-total >= 5.0 filter")))
      (testing "jq unavailable — inclusive boundary asserted structurally on the recipe"
        ;; The inclusive boundary is the `>=` operators in the qualification
        ;; filter; a strict `>` regress changes this fragment. Lock it.
        (is (.contains recipe "select(.[\"lcc-total\"] >= 5.0 and .gap >= 2.0)")
            "recipe carries the inclusive (>=) qualification filter at both thresholds")))))

(defn- evidence-local-unit-json
  "A synthetic `local`-lens unit JSON whose six burden dimensions carry DISTINCT
   values, so the projection-contract test can verify the recipe's per-dimension
   dash->underscore rename mapping precisely (e.g. flow-burden -> flow_burden)
   rather than collapsing every burden onto a shared value. `findings` carries
   two distinguishable entries so the projection's `findings` survival is real."
  [unit-ns unit-var line]
  (str "{\"ns\":\"" unit-ns "\",\"var\":\"" unit-var "\",\"arity\":null,\"line\":" line ","
       "\"end-line\":" (+ line 32) ",\"lcc-total\":30.0,\"flow-burden\":11,"
       "\"state-burden\":12,\"shape-burden\":13,\"abstraction-burden\":14,"
       "\"dependency-burden\":15,\"working-set\":16,\"file\":\"" unit-ns ".clj\","
       "\"findings\":[\"finding-a\",\"finding-b\"]}"))

(deftest incidental-complexity-finder-recipe-projection-contract-test
  ;; TR21 (test review pass 17 — test-shaper): the recipe ends with a
  ;; `map({...})` projection re-emitting the chosen target's evidence — the
  ;; design's named step-5 acceptance ("emit one chosen target with evidence:
  ;; … lcc-total with per-dimension burdens, cc, gap, the local findings, …"),
  ;; consumed verbatim by the workflow step-1 prompt to build the generated
  ;; task's evidence block. Every other recipe test asserts only
  ;; ns/var/line/cc/gap survival, so a regress dropping a projected field
  ;; (end_line, findings, a burden dimension) or mis-renaming one
  ;; (flow_burden -> flow-burden) passes green while silently degrading the
  ;; evidence the generated task is built from. Feed one qualifying matched unit
  ;; whose burden dimensions carry distinct values and assert the surviving
  ;; object carries every projected evidence key with its expected value.
  (let [recipe (skill-recipe)
        gap-key (fn [out k] (re-find (re-pattern (str "\"" k "\":\\s*[^,}\\]]+")) out))]
    (is (some? recipe) "the join/gap jq recipe is extractable from SKILL.md")
    (if (jq-available?)
      (testing "projection emits every evidence key with the recipe's dash->underscore rename"
        ;; one qualifying matched unit: lcc 30.0, cc 4 -> gap 7.5 (qualifies),
        ;; distinct burden values 11..16 so the rename mapping is unambiguous.
        (let [{:keys [exit out err]}
              (run-jq-recipe recipe
                             [(evidence-local-unit-json "proj" "target" 10)]
                             [(named-cc-unit-json "proj" "target" 10 4)])]
          (is (zero? exit) (str "recipe runs cleanly; stderr: " err))
          (is (re-find #"\"var\":\s*\"target\"" out)
              "the qualifying unit survives the recipe")
          ;; identity + location fields pass through unrenamed.
          (is (re-find #"\"ns\":\s*\"proj\"" out) "ns survives the projection")
          (is (re-find #"\"arity\":\s*null" out) "arity survives the projection")
          (is (re-find #"\"file\":\s*\"proj\.clj\"" out) "file survives the projection")
          (is (re-find #"\"line\":\s*10\b" out) "line survives the projection")
          ;; dash-keyed source fields are re-emitted under their underscore names
          ;; with the exact source value (locks the rename mapping per dimension).
          (is (re-find #"\"end_line\":\s*42\b" out)
              "end-line is re-emitted as end_line (= 42)")
          (is (re-find #"\"lcc_total\":\s*30" out)
              "lcc-total is re-emitted as lcc_total (= 30)")
          (is (re-find #"\"flow_burden\":\s*11\b" out)
              "flow-burden is re-emitted as flow_burden (= 11)")
          (is (re-find #"\"state_burden\":\s*12\b" out)
              "state-burden is re-emitted as state_burden (= 12)")
          (is (re-find #"\"shape_burden\":\s*13\b" out)
              "shape-burden is re-emitted as shape_burden (= 13)")
          (is (re-find #"\"abstraction_burden\":\s*14\b" out)
              "abstraction-burden is re-emitted as abstraction_burden (= 14)")
          (is (re-find #"\"dependency_burden\":\s*15\b" out)
              "dependency-burden is re-emitted as dependency_burden (= 15)")
          (is (re-find #"\"working_set\":\s*16\b" out)
              "working-set is re-emitted as working_set (= 16)")
          ;; the local findings survive verbatim, and cc/gap are present.
          (is (re-find #"finding-a" out) "the local findings survive the projection")
          (is (re-find #"finding-b" out) "every local finding survives the projection")
          (is (some? (gap-key out "cc")) "cc is present in the projection")
          (is (re-find #"\"gap\":\s*7\.5" out) "gap is present in the projection (= 7.5)")))
      (testing "jq unavailable — projection key names asserted structurally on the recipe"
        ;; TR21 fallback (mirrors TR12/16/17/18): lock each projected key name
        ;; verbatim so a dropped/mis-renamed field fails green whether or not jq
        ;; is installed. Identity/cc/gap pass through as bare jq shorthand; the
        ;; renamed dash-keyed fields appear as `<underscore>: .["<dash>"]`.
        (doseq [k ["ns" "var" "arity" "file" "line" "findings" "cc" "gap"]]
          (is (.contains recipe k)
              (str "recipe projection emits the " k " field")))
        (is (.contains recipe "end_line: .[\"end-line\"]")
            "recipe projects end-line as end_line")
        (is (.contains recipe "lcc_total: .[\"lcc-total\"]")
            "recipe projects lcc-total as lcc_total")
        (is (.contains recipe "flow_burden: .[\"flow-burden\"]")
            "recipe projects flow-burden as flow_burden")
        (is (.contains recipe "state_burden: .[\"state-burden\"]")
            "recipe projects state-burden as state_burden")
        (is (.contains recipe "shape_burden: .[\"shape-burden\"]")
            "recipe projects shape-burden as shape_burden")
        (is (.contains recipe "abstraction_burden: .[\"abstraction-burden\"]")
            "recipe projects abstraction-burden as abstraction_burden")
        (is (.contains recipe "dependency_burden: .[\"dependency-burden\"]")
            "recipe projects dependency-burden as dependency_burden")
        (is (.contains recipe "working_set: .[\"working-set\"]")
            "recipe projects working-set as working_set")))))

(deftest ^:integration incidental-complexity-finder-real-lens-integration-test
  ;; TR22 (test review pass 30 — test-shaper): tagged `^:integration` so this
  ;; slow real-lens test (it spawns the real `bb gordian local`/`complexity`
  ;; subprocesses against this repo, ~1.1s/1.3s per lens) moves to the
  ;; `:integration` suite and is skipped from the fast `:unit` suite
  ;; (`:skip-meta [:integration]`), mirroring every other slow real-system
  ;; boundary test. The fast-suite guard of the recipe's `.units`-shape
  ;; assumption is hoisted into `incidental-complexity-finder-skill-content-lock-test`
  ;; (the jq/bb-absent fallback below no longer runs in the fast suite).
  ;;
  ;; TT-L (test review pass 27 — task-test-review / testing-without-mocks
  ;; Narrow Integration Test). Every other recipe test runs the embedded jq
  ;; recipe over SYNTHETIC {"units":[…]} inputs, and TT-F locks only that
  ;; SKILL.md §1 NAMES the two lens commands as prose substrings — nothing
  ;; proves the recipe consumes the REAL `bb gordian` output shape (each lens's
  ;; top-level `.units` array carrying ns/var/arity/line/lcc-total/burdens/
  ;; findings for `local` and ns/var/arity/line/cc for `complexity`). A future
  ;; gordian JSON reshape (wrapped units, renamed burden, changed findings/line)
  ;; would keep every synthetic test green while the shipped skill silently
  ;; broke. This is the design's FIRST acceptance criterion ("produces a target
  ;; + evidence when run against this repository"; Deliverable 1 step 5 / Locked
  ;; decision 1): exercise the recipe against the real lenses and assert
  ;; STRUCTURE, not a specific target — the live top-5 drifts as code changes,
  ;; so a unit-specific assertion would be flaky.
  (let [recipe (skill-recipe)]
    (is (some? recipe) "the join/gap jq recipe is extractable from SKILL.md")
    (if (and (bb-available?) (jq-available?))
      (let [repo-dir (System/getProperty "user.dir")
            local (shell/sh "bb" "gordian" "local" "--sort" "total" "--json" :dir repo-dir)
            cc (shell/sh "bb" "gordian" "complexity" "--json" :dir repo-dir)
            ;; CI hardening: a `bb` invocation can prepend a non-JSON preamble
            ;; line (e.g. a timbre log line) to stdout before the JSON, which
            ;; breaks jq's whole-file `--slurpfile`. This test validates the
            ;; recipe consumes the real `.units` SHAPE, not babashka's stdout
            ;; hygiene, so slice each lens payload from its first `{`.
            from-first-brace (fn [s] (if-let [i (str/index-of s "{")] (subs s i) s))
            local-json (from-first-brace (:out local))
            cc-json (from-first-brace (:out cc))]
        (testing "the real lenses emit the top-level `.units` shape the recipe reads"
          (is (zero? (:exit local))
              (str "bb gordian local --sort total --json runs; stderr: " (:err local)))
          (is (zero? (:exit cc))
              (str "bb gordian complexity --json runs; stderr: " (:err cc)))
          (is (.contains (:out local) "\"units\"")
              "the local lens emits a top-level units array")
          (is (.contains (:out cc) "\"units\"")
              "the complexity lens emits a top-level units array"))
        (testing "the recipe consumes the real lens output and emits a structurally-valid result"
          (let [{:keys [exit out err]} (run-recipe-over-lens-output recipe local-json cc-json)
                trimmed (str/trim out)]
            (is (zero? exit)
                (str "recipe runs cleanly over the real lens output; stderr: " err))
            ;; a JSON array (possibly `[]` — a vacuously-valid no-target result).
            (is (str/starts-with? trimmed "[") "recipe emits a JSON array")
            (is (str/ends-with? trimmed "]") "recipe emits a JSON array")
            ;; structure not target: validate via jq that every result element
            ;; carries the projected evidence keys (robust to multi-element
            ;; output and the live top-5 drifting; `[]` passes vacuously).
            (let [{vexit :exit vout :out}
                  (shell/sh "jq" "-e"
                            (str "type == \"array\" and all(.[]; "
                                 "has(\"ns\") and has(\"var\") and has(\"gap\") and "
                                 "has(\"cc\") and has(\"lcc_total\") and has(\"findings\"))")
                            :in out)]
              (is (zero? vexit)
                  (str "each result element carries the projected evidence keys; "
                       "jq said: " (str/trim vout)))))))
      (testing "bb or jq unavailable — recipe structurally reads each lens's top-level `.units`"
        ;; Fallback when the real lenses can't run (mirrors the jq-absent
        ;; fallbacks elsewhere): lock that the recipe reads each lens's top-level
        ;; `.units` array — the real-shape assumption this integration test
        ;; proves when bb/jq are present.
        (is (.contains recipe "$cc[0].units")
            "recipe reads the complexity lens top-level units array")
        (is (.contains recipe "$loc[0].units")
            "recipe reads the local lens top-level units array")))))

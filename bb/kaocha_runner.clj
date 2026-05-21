(ns bb.kaocha-runner
  "Babashka wrapper for Kaocha test runs.

   Uses the documentation reporter so test names are visible, then scans
   the output for ERROR/FAIL markers.  This catches the case where
   StackOverflowError in a test corrupts Kaocha's result tree, preventing
   Kaocha from computing totals and exiting non-zero."
  (:refer-clojure :exclude [run!])
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn run!
  "Run Kaocha with the given args, using documentation reporter.
   Returns exit code: 0 on success, 1 on detected errors/failures."
  [args]
  (let [base-args ["clojure" "-M:test"
                   "--reporter" "kaocha.report/documentation"
                   "--no-color"]
        all-args  (into base-args args)
        result    (p/process all-args
                             {:out :string
                              :err :string
                              :exit-fn identity})
        _         @result
        exit-code (:exit @result)
        out       (:out @result)
        err       (:err @result)
        out-lines (str/split-lines out)
        ;; Scan for ERROR markers in documentation reporter output
        ;; Format: "  test-name ERROR"
        error-lines   (filter #(re-find #"\bERROR\s*$" %) out-lines)
        fail-lines    (filter #(re-find #"\bFAIL\s*$" %) out-lines)
        error-count   (count error-lines)
        fail-count    (count fail-lines)
        has-problems? (or (pos? exit-code)
                          (pos? error-count)
                          (pos? fail-count))]
    ;; Print the output
    (print out)
    (when (seq err) (binding [*out* *err*] (print err)))
    (flush)

    ;; Print our summary
    (println)
    (if has-problems?
      (do
        (println "╔══════════════════════════════════════════════╗")
        (printf  "║  %d errors, %d failures (kaocha exit: %d)     ║%n"
                 error-count fail-count exit-code)
        (println "╚══════════════════════════════════════════════╝")
        (when (seq error-lines)
          (println)
          (println "Tests with errors:")
          (doseq [line error-lines]
            (println "  " (str/trim line))))
        (when (seq fail-lines)
          (println)
          (println "Tests with failures:")
          (doseq [line fail-lines]
            (println "  " (str/trim line))))
        1)
      (do
        (println "✅ All tests passed")
        0))))

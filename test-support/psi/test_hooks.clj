(ns psi.test-hooks
  "Kaocha hooks for test-suite-wide setup and error surfacing.

   The post-summary hook emits a clearly-delimited summary block so
   errors and failures are always visible.

   Known issue: StackOverflowError in a test can corrupt Kaocha's result
   tree, preventing result/totals and post-summary from completing in the
   full suite.  In that case the bb task wrapper detects errors via the
   documentation reporter output."
  (:require [kaocha.result :as result]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :info)

(defn pre-run
  "Keep test logging at :info so dependency debug noise stays suppressed."
  [test-plan]
  (timbre/set-min-level! :info)
  test-plan)

(defn post-summary
  "Emit an explicit error/failure summary that's easy to find in output.
   Wraps totals computation in try/catch — when the result tree is corrupt
   (e.g. from StackOverflowError), prints a warning.

   Never calls System/exit: this hook runs inside `kaocha.api/run`, which may
   itself be invoked in-process (e.g. by Scry's structured runner). Exiting the
   JVM here would kill the enclosing runner before it can emit its own summary
   or write structured results. Exit-code responsibility belongs to the runner
   (Scry's CLI / the bb task wrapper), which already fails non-zero on
   errors/failures."
  [result]
  (try
    (let [totals   (result/totals (:kaocha.result/tests result))
          errors   (:kaocha.result/error totals 0)
          failures (:kaocha.result/fail totals 0)
          tests    (:kaocha.result/count totals 0)
          pass     (:kaocha.result/pass totals 0)]
      (println)
      (if (pos? (+ errors failures))
        (do
          (println "╔══════════════════════════════════════════╗")
          (printf  "║  %d tests, %d pass, %d failures, %d errors  ║%n" tests pass failures errors)
          (println "╚══════════════════════════════════════════╝")
          (flush))
        (printf "✅ %d tests, %d pass, 0 failures, 0 errors%n" tests pass))
      result)
    (catch Throwable t
      (println)
      (println "╔═══════════════════════════════════════════════════════╗")
      (println "║  ⚠ Test result tree corrupt — cannot compute totals  ║")
      (printf  "║  %s: %s%n" (.getSimpleName (class t)) (or (.getMessage t) "(no message)"))
      (println "║  Likely cause: StackOverflowError in a test.         ║")
      (println "║  Treat this run as FAILED.                           ║")
      (println "╚═══════════════════════════════════════════════════════╝")
      (flush)
      result)))

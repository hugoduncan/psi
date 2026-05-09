(ns psi.test-hooks
  "Kaocha hooks for test-suite-wide setup."
  (:require [taoensso.timbre :as timbre]))

(timbre/set-min-level! :info)

(defn pre-run
  "Keep test logging at :info so dependency debug noise stays suppressed."
  [test-plan]
  (timbre/set-min-level! :info)
  test-plan)

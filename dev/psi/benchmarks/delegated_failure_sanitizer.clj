(ns psi.benchmarks.delegated-failure-sanitizer
  (:require
   [psi.workflow-runtime.delegated-failure :as delegated-failure]))

(def ^:private delimiter-count 128000)
(def ^:private sample-count 3)
(def ^:private maximum-separator-overhead-ratio 1.14)

(defn- late-separator-input
  [suffix]
  (str (apply str (repeat delimiter-count " ")) suffix))

(defn- elapsed-nanos
  [input expected]
  (let [started-at (System/nanoTime)
        sanitized (delegated-failure/sanitize-component input)]
    (when-not (= expected sanitized)
      (throw (ex-info "Unexpected sanitizer output"
                      {:expected expected
                       :actual sanitized})))
    (- (System/nanoTime) started-at)))

(defn- median
  [values]
  (nth (sort values) (quot (count values) 2)))

(defn- benchmark-samples
  []
  (reduce
   (fn [samples _]
     ;; Interleave equal-size inputs so JVM and host variation affects each case
     ;; similarly. The no-separator control isolates common linear scan cost.
     (reduce (fn [samples [label suffix expected]]
               (update samples label conj
                       (elapsed-nanos (late-separator-input suffix) expected)))
             samples
             [[:control "tail" "tail"]
              [:slash "/tail" "[PATH_REDACTED]"]
              [:backslash "\\tail" "\\tail"]]))
   {:control [] :slash [] :backslash []}
   (range sample-count)))

(defn- separator-results
  []
  (let [medians (update-vals (benchmark-samples) median)
        control-nanos (:control medians)]
    (mapv (fn [label]
            {:separator label
             :control-ms (/ control-nanos 1e6)
             :separator-ms (/ (get medians label) 1e6)
             :overhead-ratio (/ (double (get medians label)) control-nanos)})
          [:slash :backslash])))

(defn -main
  [& _]
  ;; Warm the complete public sanitizer path before collecting interleaved
  ;; medians. This opt-in benchmark is intentionally absent from unit suites.
  (dotimes [_ 3]
    (delegated-failure/sanitize-component
     (str (apply str (repeat 2000 " ")) "/tail")))
  (let [results (separator-results)]
    (doseq [{:keys [separator control-ms separator-ms overhead-ratio]} results]
      (println (format "%s: control %.1f ms, separator %.1f ms (%.3fx)"
                       (name separator)
                       control-ms
                       separator-ms
                       overhead-ratio)))
    (when-let [failures
               (seq (filter #(> (:overhead-ratio %)
                                maximum-separator-overhead-ratio)
                            results))]
      (throw
       (ex-info "Delegated-failure sanitizer separator overhead exceeded calibrated bound"
                {:maximum-overhead-ratio maximum-separator-overhead-ratio
                 :failures failures})))))

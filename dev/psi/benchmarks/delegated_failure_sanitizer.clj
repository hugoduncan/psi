(ns psi.benchmarks.delegated-failure-sanitizer
  (:require
   [psi.workflow-runtime.delegated-failure :as delegated-failure]))

(def ^:private small-run-count 4000)
(def ^:private large-run-count (* 4 small-run-count))
(def ^:private maximum-scaling-ratio 8.0)

(defn- late-separator-input
  [run-count separator]
  (str (apply str (repeat run-count "x ")) separator "tail"))

(defn- elapsed-nanos
  [input]
  (let [started-at (System/nanoTime)
        sanitized (delegated-failure/sanitize-component input)]
    (when-not (= 512 (delegated-failure/code-point-count sanitized))
      (throw (ex-info "Unexpected sanitizer output bound"
                      {:code-point-count
                       (delegated-failure/code-point-count sanitized)})))
    (- (System/nanoTime) started-at)))

(defn- fastest-elapsed-nanos
  [input]
  (apply min (repeatedly 2 #(elapsed-nanos input))))

(defn- separator-scaling
  [separator]
  (let [small-input (late-separator-input small-run-count separator)
        large-input (late-separator-input large-run-count separator)
        small-nanos (fastest-elapsed-nanos small-input)
        large-nanos (fastest-elapsed-nanos large-input)]
    {:separator separator
     :small-ms (/ small-nanos 1e6)
     :large-ms (/ large-nanos 1e6)
     :ratio (/ (double large-nanos) small-nanos)}))

(defn -main
  [& _]
  ;; Warm the complete public sanitizer path before comparing 4x input growth.
  (dotimes [_ 2]
    (delegated-failure/sanitize-component
     (late-separator-input 1000 "/")))
  (let [results (mapv separator-scaling ["/" "\\"])]
    (doseq [{:keys [separator small-ms large-ms ratio]} results]
      (println (format "%s: %.1f ms -> %.1f ms (%.2fx)"
                       (pr-str separator)
                       small-ms
                       large-ms
                       ratio)))
    (when-let [failures (seq (filter #(> (:ratio %) maximum-scaling-ratio)
                                     results))]
      (throw (ex-info "Delegated-failure sanitizer scaling exceeded calibrated bound"
                      {:maximum-ratio maximum-scaling-ratio
                       :failures failures})))))

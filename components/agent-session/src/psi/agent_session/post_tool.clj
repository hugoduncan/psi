(ns psi.agent-session.post-tool
  "Ctx-owned post-tool processor registry and additive result composition."
  (:require [clojure.string :as str]
            [psi.agent-session.dispatch :as dispatch]
            [psi.state-kernel.dispatch :as kernel]))

(defn create-registry []
  {:state (atom {:processors []
                 :telemetry []})})

(defn- now []
  (java.time.Instant/now))

(defn processors-in [ctx]
  (:processors @(:state (:post-tool-registry ctx))))

(defn processor-count-in [ctx]
  (count (processors-in ctx)))

(defn recent-telemetry-in [ctx]
  (:telemetry @(:state (:post-tool-registry ctx))))

(defn telemetry-counts-in [ctx]
  (let [events (recent-telemetry-in ctx)]
    {:processed-count (count (filter #(= :success (:status %)) events))
     :timeout-count   (count (filter #(= :timeout (:status %)) events))
     :error-count     (count (filter #(= :error (:status %)) events))}))

(defn register-processor-in!
  [ctx {:keys [name ext-path match timeout-ms handler]}]
  (let [entry {:name name
               :ext-path ext-path
               :tools (set (:tools match))
               :timeout-ms (long (or timeout-ms 500))
               :handler handler}]
    (swap! (:state (:post-tool-registry ctx)) update :processors conj entry)
    entry))

(defn- matching-processors [ctx tool-name]
  (filterv #(contains? (:tools %) tool-name) (processors-in ctx)))

(defn- append-content [content suffix]
  (cond
    (nil? suffix) content
    (str/blank? suffix) content
    (string? content) (str content suffix)
    :else content))

(defn- merge-details [details m]
  (cond
    (nil? m) details
    (map? details) (merge details m)
    (nil? details) m
    :else details))

(defn apply-contribution [result contribution]
  (cond-> result
    (:content/append contribution)
    (update :content append-content (:content/append contribution))

    (:details/merge contribution)
    (update :details merge-details (:details/merge contribution))

    (:enrichments contribution)
    (update :enrichments (fnil into []) (:enrichments contribution))))

(defn- record-telemetry! [ctx event]
  (swap! (:state (:post-tool-registry ctx)) update :telemetry conj (assoc event :timestamp (now))))

(defn- run-with-timeout [timeout-ms f]
  (let [fut (future (f))
        result (deref fut timeout-ms ::timeout)]
    (if (= ::timeout result)
      (do (future-cancel fut) ::timeout)
      result)))

(defn run-post-tool-processing-direct-in!
  [ctx {:keys [tool-name tool-call-id tool-result dispatch-id] :as input}]
  (let [processors (matching-processors ctx tool-name)]
    (reduce
     (fn [result {:keys [name timeout-ms handler]}]
       (let [started (System/nanoTime)
             outcome (try
                       (run-with-timeout timeout-ms #(handler (kernel/assoc-dispatch-id input dispatch-id)))
                       (catch Throwable t t))
             duration-ms (long (/ (- (System/nanoTime) started) 1000000))]
         (cond
           (= ::timeout outcome)
           (do (record-telemetry! ctx {:tool-call-id tool-call-id
                                       :tool-name tool-name
                                       :processor-name name
                                       :status :timeout
                                       :duration-ms timeout-ms})
               result)

           (instance? Throwable outcome)
           (do (record-telemetry! ctx {:tool-call-id tool-call-id
                                       :tool-name tool-name
                                       :processor-name name
                                       :status :error
                                       :duration-ms duration-ms})
               result)

           (nil? outcome)
           (do (record-telemetry! ctx {:tool-call-id tool-call-id
                                       :tool-name tool-name
                                       :processor-name name
                                       :status :success
                                       :duration-ms duration-ms})
               result)

           :else
           (do (record-telemetry! ctx {:tool-call-id tool-call-id
                                       :tool-name tool-name
                                       :processor-name name
                                       :status :success
                                       :duration-ms duration-ms})
               (apply-contribution result outcome)))))
     (update tool-result :enrichments #(vec (or % [])))
     processors)))

(defn run-post-tool-processing-in!
  [ctx {:keys [session-id] :as input}]
  (if (and session-id (kernel/handler-entry :session/post-tool-run))
    (dispatch/dispatch! ctx :session/post-tool-run input {:origin :core})
    (run-post-tool-processing-direct-in! ctx input)))

(defn project-processor [processor]
  (select-keys processor [:name :ext-path :tools :timeout-ms]))

(defn projected-processors-in [ctx]
  (mapv project-processor (processors-in ctx)))

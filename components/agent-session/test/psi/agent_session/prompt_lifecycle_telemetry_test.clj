(ns psi.agent-session.prompt-lifecycle-telemetry-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.turn]
   [psi.turn-runtime.core]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest prompt-lifecycle-terminal-provider-error-emits-one-finished-telemetry-event-test
  ;; Terminal provider failures are already finalized at the provider boundary;
  ;; prompt finish must not duplicate the compatibility terminal telemetry event.
  (let [[ctx session-id] (create-session-context {:persist? false})
        reg             (:extension-registry ctx)
        seen            (atom [])]
    (ext/register-extension-in! reg "/ext/provider-telemetry")
    (ext/register-handler-in! reg "/ext/provider-telemetry" "provider_request_finished" #(swap! seen conj %))
    (with-redefs [psi.turn-runtime.core/execute-live-turn!
                  (fn [_ai-ctx _ctx _sid {:keys [turn-id ai-model]}]
                    {:turn-id turn-id
                     :model ai-model
                     :ai-options {}
                     :turn-ctx nil
                     :assistant-message {:role "assistant"
                                         :content [{:type :error :text "bad request"}]
                                         :stop-reason :error
                                         :error-message "bad request"
                                         :http-status 400
                                         :timestamp (java.time.Instant/now)}})]
      (let [result (psi.agent-session.turn/prompt-execution-result-in! ctx session-id "trigger terminal provider error")]
        (is (= :error (:execution-result/stop-reason result)))
        (is (= :non-retryable (get-in result [:execution-result/retry-outcome :failure-reason])))))
    (is (= 1 (count @seen)))
    (is (= ["provider_request_finished"] (mapv :type @seen)))
    (is (= [:failed] (mapv :status @seen)))
    (is (= [true] (mapv :final? @seen)))
    (is (= [:non-retryable] (mapv :failure-reason @seen)))))

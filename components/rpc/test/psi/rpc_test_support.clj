(ns psi.rpc-test-support
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.rpc :as rpc]
   [psi.agent-session.test-support :as test-support]))

(defn assistant-msg->execution-result
  "Convert an assistant message map into the shaped execution-result map
   expected by the new prompt lifecycle."
  [session-id assistant-msg]
  (let [tool-calls (vec (filter #(= :tool-call (:type %)) (or (:content assistant-msg) [])))]
    {:execution-result/turn-id             (str (java.util.UUID/randomUUID))
     :execution-result/session-id          session-id
     :execution-result/prepared-request-id nil
     :execution-result/assistant-message   assistant-msg
     :execution-result/usage              (:usage assistant-msg)
     :execution-result/provider-captures  {:request-captures [] :response-captures []}
     :execution-result/turn-outcome       (cond
                                            (= :error (:stop-reason assistant-msg)) :turn.outcome/error
                                            (seq tool-calls) :turn.outcome/tool-use
                                            :else :turn.outcome/stop)
     :execution-result/tool-calls         tool-calls
     :execution-result/error-message      (:error-message assistant-msg)
     :execution-result/http-status        (:http-status assistant-msg)
     :execution-result/stop-reason        (or (:stop-reason assistant-msg) :stop)}))

(defn ok-execution-result
  ([session-id]
   (ok-execution-result session-id []))
  ([session-id content]
   (assistant-msg->execution-result
    session-id
    {:role "assistant"
     :content (vec content)
     :stop-reason :stop})))

(defn run-loop
  "Run a stdio loop."
  ([input handler]
   (run-loop input handler (atom {}) 0))
  ([input handler state]
   (run-loop input handler state 0))
  ([input handler state wait-ms]
   (let [out (java.io.StringWriter.)
         err (java.io.StringWriter.)]
     (rpc/run-stdio-loop! {:in              (java.io.StringReader. input)
                           :out             out
                           :err             err
                           :state           state
                           :request-handler handler})
     (when (pos? wait-ms)
       (Thread/sleep wait-ms))
     {:out-lines (->> (str/split-lines (str out))
                      (remove str/blank?)
                      vec)
      :err-text  (str err)
      :state     @state})))

(defn parse-frames [lines]
  (mapv edn/read-string lines))

(defn enqueue-active-dialog!
  "Directly place a dialog into the active slot of canonical ui-state.
   Bypasses the dispatch layer so tests can control dialog-id precisely."
  [ctx dialog]
  (ss/update-state-value-in! ctx (ss/state-path :ui-state)
                             #(assoc-in % [:dialog-queue :active] dialog)))

(defn active-dialog-in
  "Read the active dialog from canonical ui-state."
  [ctx]
  (get-in (ss/get-state-value-in ctx (ss/state-path :ui-state))
          [:dialog-queue :active]))

(defn create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- normalize-execute-prepared-request-fn
  [f]
  (fn [ai-ctx ctx session-id prepared-request progress-queue]
    (let [result (f ai-ctx ctx session-id prepared-request progress-queue)]
      (if (contains? result :execution-result/session-id)
        result
        (assistant-msg->execution-result session-id result)))))

(defn make-handler
  "Build an RPC request handler."
  [ctx state]
  (let [execute-fn (:execute-prepared-request-fn @state)
        ctx*       (cond-> ctx
                     execute-fn
                     (assoc :execute-prepared-request-fn
                            (normalize-execute-prepared-request-fn execute-fn)))]
    (rpc/make-session-request-handler
     ctx*
     (select-keys @state [:rpc-ai-model
                          :on-new-session!
                          :sync-on-git-head-change?]))))

(defn stream-body
  [s]
  (java.io.ByteArrayInputStream. (.getBytes s "UTF-8")))

(def openai-chatgpt-test-token
  "aaa.eyJodHRwczovL2FwaS5vcGVuYWkuY29tL2F1dGgiOnsiY2hhdGdwdF9hY2NvdW50X2lkIjoiYWNjX3Rlc3QifX0.bbb")


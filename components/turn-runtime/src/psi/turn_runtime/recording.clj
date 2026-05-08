(ns psi.turn-runtime.recording
  "Lower prepared-turn response classification and recording decision helpers.")

(defn extract-tool-calls
  [assistant-msg]
  (filter #(= :tool-call (:type %)) (:content assistant-msg)))

(defn classify-assistant-message
  [assistant-msg]
  (let [tool-calls (vec (extract-tool-calls assistant-msg))]
    (cond
      (= :error (:stop-reason assistant-msg))
      {:turn/outcome :turn.outcome/error
       :assistant-message assistant-msg
       :tool-calls tool-calls}

      (seq tool-calls)
      {:turn/outcome :turn.outcome/tool-use
       :assistant-message assistant-msg
       :tool-calls tool-calls}

      :else
      {:turn/outcome :turn.outcome/stop
       :assistant-message assistant-msg
       :tool-calls tool-calls})))

(defn build-recording-decision
  [execution-result]
  (let [{:keys [turn/outcome assistant-message tool-calls] :as classified}
        (classify-assistant-message (:execution-result/assistant-message execution-result))
        turn-id    (:execution-result/turn-id execution-result)
        next-event (if (= :turn.outcome/tool-use outcome)
                     :session/prompt-continue
                     :session/prompt-finish)]
    {:turn-id           turn-id
     :turn-outcome      outcome
     :tool-calls        tool-calls
     :assistant-message assistant-message
     :next-event        next-event
     :classified        classified}))

(defn execution-usage-tokens
  [execution-result]
  (let [usage (:execution-result/usage execution-result)]
    (when (map? usage)
      (let [total (or (:total-tokens usage)
                      (+ (or (:input-tokens usage) 0)
                         (or (:output-tokens usage) 0)
                         (or (:cache-read-tokens usage) 0)
                         (or (:cache-write-tokens usage) 0)))]
        (when (and (number? total) (pos? total))
          total)))))

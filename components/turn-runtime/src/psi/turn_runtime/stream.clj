(ns psi.turn-runtime.stream
  "Shared turn streaming helpers used by live turn execution."
  (:require
   [psi.ai.core :as ai]
   [psi.turn-statechart.core :as turn-sc])
  (:import
   (java.util.concurrent Future)))

(def llm-stream-idle-timeout-ms 1200000)
(def llm-stream-wait-poll-ms 250)

(defn now-ms []
  (System/currentTimeMillis))

(defn do-stream!
  "Invoke the appropriate streaming fn depending on whether ai-ctx is provided."
  [ai-ctx ai-conv ai-model ai-options consume-fn]
  (if ai-ctx
    (ai/stream-response-in ai-ctx ai-conv ai-model ai-options consume-fn)
    (ai/stream-response ai-conv ai-model ai-options consume-fn)))

(defn chain-callbacks
  [& callbacks]
  (let [callbacks* (vec (keep #(when (fn? %) %) callbacks))]
    (when (seq callbacks*)
      (fn [payload]
        (doseq [cb callbacks*]
          (try (cb payload) (catch Exception _ nil)))))))

(defn wait-for-turn-result
  "Wait for `done-p` with an idle timeout that resets on any stream progress.
   When `abort-pred` is provided, return `::aborted` once it reports true."
  [done-p last-progress-ms {:keys [idle-timeout-ms wait-poll-ms abort-pred]}]
  (let [poll-ms    (max 1 (long (or wait-poll-ms llm-stream-wait-poll-ms)))
        timeout-ms (max 1 (long (or idle-timeout-ms llm-stream-idle-timeout-ms)))
        aborted?   (fn [] (boolean (when abort-pred (abort-pred))))]
    (loop []
      (let [result  (deref done-p poll-ms ::pending)
            aborted (aborted?)]
        (cond
          aborted ::aborted
          (not= ::pending result) result
          (>= (- (now-ms) @last-progress-ms) timeout-ms) ::timeout
          :else (recur))))))

(defn cancelled-stream-handle?
  [stream-handle]
  (boolean
   (or (:cancelled? stream-handle)
       (some-> (:future stream-handle) future-cancelled?)
       (some-> (:future stream-handle) ^Future .isCancelled))))

(defn cancel-stream-handle!
  [stream-handle]
  (when-let [f (:future stream-handle)]
    (future-cancel f))
  (assoc stream-handle :cancelled? true))

(defn mark-turn-stream-handle!
  [turn-ctx stream-handle]
  (swap! (:turn-data turn-ctx) assoc :stream-handle stream-handle)
  stream-handle)

(defn abort-turn!
  [turn-ctx]
  (let [td @(:turn-data turn-ctx)
        stream-handle (:stream-handle td)]
    (when stream-handle
      (swap! (:turn-data turn-ctx) assoc :stream-handle (cancel-stream-handle! stream-handle)))
    (when-not (:final-message td)
      (turn-sc/send-event! turn-ctx :turn/error {:stop-reason :aborted
                                                 :error-message "Aborted"}))
    :aborted))

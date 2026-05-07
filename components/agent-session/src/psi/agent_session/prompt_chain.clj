(ns psi.agent-session.prompt-chain
  "Prompt lifecycle continuation helpers.

   Transitional runtime boundary: runtime-only tool execution for prompt
   continuation. Higher-level next-turn orchestration is expressed through
   dispatch-visible continuation events."
  (:require
   [psi.turn-runtime.tool-args :as tool-args]
   [psi.agent-session.dispatch :as dispatch]))

(defn run-prompt-tools!
  [ctx session-id execution-result progress-queue]
  (let [tool-calls (:execution-result/tool-calls execution-result)]
    (doseq [tool-call tool-calls]
      (dispatch/dispatch! ctx :session/tool-run
                          {:session-id     session-id
                           :tool-call      tool-call
                           :parsed-args    (tool-args/parse-args (:arguments tool-call))
                           :progress-queue progress-queue}
                          {:origin :core}))
    {:continued? (boolean (seq tool-calls))
     :tool-call-count (count tool-calls)}))

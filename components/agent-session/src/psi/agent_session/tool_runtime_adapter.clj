(ns psi.agent-session.tool-runtime-adapter
  "Session-owned adaptation from generic tool-runtime APIs to dispatch,
   telemetry, post-tool processing, output accounting, and progress emission."
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.psi-tool :as psi-tool]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.tool-output :as tool-output]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.session-state.state :as session]
   [psi.tool-runtime.args :as tool-args]
   [psi.tool-runtime.batch :as tool-runtime.batch]
   [psi.tool-runtime.core :as tool-runtime]
   [psi.turn-runtime.accumulator :as accum]))

(defn- emit-tool-lifecycle!
  [ctx session-id lifecycle-event]
  (dispatch/dispatch! ctx
                      :session/tool-lifecycle-event
                      {:session-id session-id :entry lifecycle-event}
                      {:origin :core})
  (when-let [reg (:extension-registry ctx)]
    (case (:event-kind lifecycle-event)
      :tool-start
      (ext/dispatch-in reg "tool_call"
                       {:type         "tool_call"
                        :tool-name    (:tool-name lifecycle-event)
                        :tool-call-id (:tool-call-id lifecycle-event)
                        :input        (:parsed-args lifecycle-event)})
      :tool-result
      (ext/dispatch-in reg "tool_result"
                       {:type         "tool_result"
                        :tool-name    (:tool-name lifecycle-event)
                        :tool-call-id (:tool-call-id lifecycle-event)
                        :input        (:parsed-args lifecycle-event)
                        :content      (:content lifecycle-event)
                        :details      (:details lifecycle-event)
                        :is-error     (boolean (:is-error lifecycle-event))})
      nil))
  lifecycle-event)

(defn- record-tool-output-stat!
  [ctx session-id {:keys [stat context-bytes-added limit-hit?]}]
  (sa/record-tool-output-stat-in! ctx session-id stat context-bytes-added limit-hit?))

(defn- apply-post-tool-processing
  [ctx session-id tool-call args tool-result]
  (post-tool/run-post-tool-processing-in!
   ctx
   {:session-id    session-id
    :tool-name     (:name tool-call)
    :tool-call-id  (:id tool-call)
    :tool-args     args
    :tool-result   tool-result
    :worktree-path (session/session-worktree-path-in ctx session-id)}))

(defn- on-tool-event
  [ctx session-id progress-queue event]
  (accum/emit-progress! progress-queue
                        (emit-tool-lifecycle! ctx session-id event)))

(defn execution-services
  [ctx session-id progress-queue]
  (let [overrides (:tool-output-overrides (session/get-session-data-in ctx session-id))]
    {:execute-tool      (fn [tool-name args opts]
                          (dispatch/dispatch! ctx
                                              :session/tool-execute
                                              {:session-id session-id
                                               :tool-name tool-name
                                               :args args
                                               :opts opts}
                                              {:origin :core}))
     :post-process      (fn [tool-call args raw-tool-result]
                          (apply-post-tool-processing ctx session-id tool-call args raw-tool-result))
     :effective-policy  (fn [tool-name]
                          (tool-output/effective-policy (or overrides {}) tool-name))
     :telemetry-args-fn (fn [tool-name args]
                          (if (= "psi-tool" tool-name)
                            (psi-tool/telemetry-args args)
                            args))
     :execute-opts      {:cwd          (session/session-worktree-path-in ctx session-id)
                         :overrides    overrides
                         :session-id   session-id}
     :tool-def-fn       (fn [tool-name]
                          (#'tool-plan/find-tool-def ctx session-id tool-name))
     :on-event          (partial on-tool-event ctx session-id progress-queue)}))

(defn execute-tool-call!
  [ctx session-id tool-call progress-queue]
  (tool-runtime/execute-tool-call!
   (assoc-in (execution-services ctx session-id progress-queue)
             [:execute-opts :tool-call-id]
             (:id tool-call))
   tool-call
   (:parsed-args tool-call)))

(defn record-tool-call-result!
  [ctx session-id shaped-result progress-queue]
  (tool-runtime/record-tool-call-result!
   {:on-event            (partial on-tool-event ctx session-id progress-queue)
    :record-output-stat! (partial record-tool-output-stat! ctx session-id)
    :on-agent-end!       (fn [tool-call tool-result is-error?]
                           (dispatch/dispatch! ctx :session/tool-agent-end
                                               {:session-id session-id
                                                :tool-call tool-call
                                                :result tool-result
                                                :is-error? is-error?}
                                               {:origin :core}))
    :record-result!      (fn [result-message]
                           (dispatch/dispatch! ctx :session/tool-agent-record-result
                                               {:session-id session-id
                                                :tool-result-msg result-message}
                                               {:origin :core}))}
   shaped-result))

(defn execute-tool-call-prepared!
  [ctx session-id tool-call parsed-args progress-queue]
  (dispatch/dispatch! ctx :session/tool-agent-start
                      {:session-id session-id
                       :tool-call  (assoc tool-call :parsed-args parsed-args)}
                      {:origin :core})
  (tool-runtime/execute-tool-call-prepared!
   (assoc-in (execution-services ctx session-id progress-queue)
             [:execute-opts :tool-call-id]
             (:id tool-call))
   tool-call
   parsed-args))

(defn record-tool-call-prepared-result!
  [ctx session-id shaped-result progress-queue]
  (record-tool-call-result! ctx session-id shaped-result progress-queue))

(defn run-tool-call!
  [ctx session-id tool-call progress-queue]
  (dispatch/dispatch! ctx :session/tool-run
                      {:session-id     session-id
                       :tool-call      tool-call
                       :parsed-args    (or (:parsed-args tool-call)
                                           (tool-args/parse-args (:arguments tool-call)))
                       :progress-queue progress-queue}
                      {:origin :core}))

(defn run-tool-calls!
  [ctx session-id tool-calls progress-queue]
  (tool-runtime.batch/run-tool-calls!
   {:executor          (:tool-batch-executor ctx)
    :run-one!          (fn [tool-call parsed-args]
                         (dispatch/dispatch! ctx :session/tool-run
                                             {:session-id     session-id
                                              :tool-call      tool-call
                                              :parsed-args    parsed-args
                                              :progress-queue progress-queue}
                                             {:origin :core}))
    :execute-prepared! (fn [tool-call parsed-args]
                         (dispatch/dispatch! ctx :session/tool-execute-prepared
                                             {:session-id     session-id
                                              :tool-call      tool-call
                                              :parsed-args    parsed-args
                                              :progress-queue progress-queue}
                                             {:origin :core}))
    :record-result!    (fn [shaped-result]
                         (dispatch/dispatch! ctx :session/tool-record-result
                                             {:session-id     session-id
                                              :shaped-result  shaped-result
                                              :progress-queue progress-queue}
                                             {:origin :core}))}
   tool-calls))

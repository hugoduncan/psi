(ns psi.agent-session.mutations.tools
  (:require
   [clojure.walk :as walk]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-core.core :as agent]
   [psi.agent-session.dispatch :as dispatch]
   [psi.session-state.state :as ss]
   [psi.agent-session.tool-plan :as tool-plan]))

(pco/defmutation add-tool
  "Add a tool to the active agent tool set if its :name is not already present."
  [_ {:keys [psi/agent-session-ctx session-id tool]}]
  {::pco/op-name 'psi.extension/add-tool
   ::pco/params  [:psi/agent-session-ctx :session-id :tool]
   ::pco/output  [:psi.tool/added?
                  :psi.tool/count]}
  (let [{:keys [added? count]}
        (or (dispatch/dispatch! agent-session-ctx
                                :session/add-tool
                                {:session-id session-id :tool tool}
                                {:origin :mutations})
            {:added? false :count 0})]
    {:psi.tool/added? (boolean added?)
     :psi.tool/count  (or count 0)}))

(pco/defmutation set-active-tools
  "Replace the agent's active tool set with the named subset."
  [_ {:keys [psi/agent-session-ctx session-id tool-names]}]
  {::pco/op-name 'psi.extension/set-active-tools
   ::pco/params  [:psi/agent-session-ctx :session-id :tool-names]
   ::pco/output  [:psi.tool/count
                  :psi.tool/names]}
  (let [agent-ctx      (ss/agent-ctx-in agent-session-ctx session-id)
        current-tools  (:tools (agent/get-data-in agent-ctx))
        by-name        (into {} (map (juxt :name identity)) current-tools)
        selected-tools (vec (keep by-name tool-names))]
    (dispatch/dispatch! agent-session-ctx
                        :session/set-active-tools
                        {:session-id session-id :tool-maps selected-tools}
                        {:origin :mutations})
    {:psi.tool/count (count selected-tools)
     :psi.tool/names (mapv :name selected-tools)}))

(defn- run-tool-mutation-in!
  "Execute a single tool call and normalize result attrs for mutation payloads."
  [ctx session-id tool-name args]
  (when-not (map? args)
    (throw (ex-info "Tool args must be a map"
                    {:tool tool-name
                     :args args})))
  (let [step-id         (keyword (str "tool-" tool-name "-" (java.util.UUID/randomUUID)))
        normalized-args (walk/stringify-keys args)
        result          (tool-plan/run-tool-plan-step-in! ctx session-id step-id tool-name normalized-args)]
    {:psi.extension.tool/name     tool-name
     :psi.extension.tool/content  (:content result)
     :psi.extension.tool/is-error (boolean (:is-error result))
     :psi.extension.tool/details  (:details result)
     :psi.extension.tool/result   result}))

(defn- run-tool-plan-mutation-payload
  [ctx session-id steps stop-on-error?]
  (let [plan-opts   (cond-> {:steps steps}
                      (some? stop-on-error?) (assoc :stop-on-error? stop-on-error?))
        {:keys [succeeded? step-count completed-count failed-step-id results result-by-id error]}
        (tool-plan/run-tool-plan-in! ctx session-id plan-opts)]
    {:psi.extension.tool-plan/succeeded?      succeeded?
     :psi.extension.tool-plan/step-count      step-count
     :psi.extension.tool-plan/completed-count completed-count
     :psi.extension.tool-plan/failed-step-id  failed-step-id
     :psi.extension.tool-plan/results         results
     :psi.extension.tool-plan/result-by-id    result-by-id
     :psi.extension.tool-plan/error           error}))

(pco/defmutation run-read-tool
  "Read a file via the runtime tool executor."
  [_ {:keys [psi/agent-session-ctx session-id path offset limit]}]
  {::pco/op-name 'psi.extension.tool/read
   ::pco/params  [:psi/agent-session-ctx :session-id :path]
   ::pco/output  [:psi.extension.tool/name
                  :psi.extension.tool/content
                  :psi.extension.tool/is-error
                  :psi.extension.tool/details
                  :psi.extension.tool/result]}
  (run-tool-mutation-in!
   agent-session-ctx
   session-id
   "read"
   (cond-> {:path path}
     (some? offset) (assoc :offset offset)
     (some? limit)  (assoc :limit limit))))

(pco/defmutation run-bash-tool
  "Run a bash command via the runtime tool executor."
  [_ {:keys [psi/agent-session-ctx session-id command timeout]}]
  {::pco/op-name 'psi.extension.tool/bash
   ::pco/params  [:psi/agent-session-ctx :session-id :command]
   ::pco/output  [:psi.extension.tool/name
                  :psi.extension.tool/content
                  :psi.extension.tool/is-error
                  :psi.extension.tool/details
                  :psi.extension.tool/result]}
  (run-tool-mutation-in!
   agent-session-ctx
   session-id
   "bash"
   (cond-> {:command command}
     (some? timeout) (assoc :timeout timeout))))

(pco/defmutation run-write-tool
  "Write a file via the runtime tool executor."
  [_ {:keys [psi/agent-session-ctx session-id path content]}]
  {::pco/op-name 'psi.extension.tool/write
   ::pco/params  [:psi/agent-session-ctx :session-id :path :content]
   ::pco/output  [:psi.extension.tool/name
                  :psi.extension.tool/content
                  :psi.extension.tool/is-error
                  :psi.extension.tool/details
                  :psi.extension.tool/result]}
  (run-tool-mutation-in!
   agent-session-ctx
   session-id
   "write"
   {:path path
    :content content}))

(pco/defmutation run-update-tool
  "Apply a text update to a file via the runtime tool executor."
  [_ {:keys [psi/agent-session-ctx session-id path oldText newText]}]
  {::pco/op-name 'psi.extension.tool/update
   ::pco/params  [:psi/agent-session-ctx :session-id :path :oldText :newText]
   ::pco/output  [:psi.extension.tool/name
                  :psi.extension.tool/content
                  :psi.extension.tool/is-error
                  :psi.extension.tool/details
                  :psi.extension.tool/result]}
  (run-tool-mutation-in!
   agent-session-ctx
   session-id
   "edit"
   {:path path
    :oldText oldText
    :newText newText}))

(pco/defmutation run-tool-plan
  "Execute a data-driven sequential tool plan."
  [_ {:keys [psi/agent-session-ctx session-id steps stop-on-error?]}]
  {::pco/op-name 'psi.extension/run-tool-plan
   ::pco/params  [:psi/agent-session-ctx :session-id :steps]
   ::pco/output  [:psi.extension.tool-plan/succeeded?
                  :psi.extension.tool-plan/step-count
                  :psi.extension.tool-plan/completed-count
                  :psi.extension.tool-plan/failed-step-id
                  :psi.extension.tool-plan/results
                  :psi.extension.tool-plan/result-by-id
                  :psi.extension.tool-plan/error]}
  (run-tool-plan-mutation-payload agent-session-ctx session-id steps stop-on-error?))

(pco/defmutation run-chain-tool
  "Execute a chained multi-step tool plan."
  [_ {:keys [psi/agent-session-ctx session-id steps stop-on-error?]}]
  {::pco/op-name 'psi.extension.tool/chain
   ::pco/params  [:psi/agent-session-ctx :session-id :steps]
   ::pco/output  [:psi.extension.tool-plan/succeeded?
                  :psi.extension.tool-plan/step-count
                  :psi.extension.tool-plan/completed-count
                  :psi.extension.tool-plan/failed-step-id
                  :psi.extension.tool-plan/results
                  :psi.extension.tool-plan/result-by-id
                  :psi.extension.tool-plan/error]}
  (run-tool-plan-mutation-payload agent-session-ctx session-id steps stop-on-error?))

(def all-mutations
  [add-tool
   set-active-tools
   run-read-tool
   run-bash-tool
   run-write-tool
   run-update-tool
   run-tool-plan
   run-chain-tool])

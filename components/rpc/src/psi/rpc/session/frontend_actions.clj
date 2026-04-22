(ns psi.rpc.session.frontend-actions
  "Frontend action result handlers for RPC session workflows."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.app-runtime.navigation :as navigation]
   [psi.app-runtime.ui-actions :as ui-actions]
   [psi.rpc.session.command-pickers :as command-pickers]
   [psi.rpc.session.command-results :as command-results]
   [psi.rpc.session.emit :as emit]
   [psi.rpc.transport :refer [response-frame]]))

(def ^:private frontend-action-result-op "frontend_action_result")

(defn- accepted-frame
  ([request]
   (accepted-frame request {:accepted true}))
  ([request data]
   (response-frame (:id request) frontend-action-result-op true data)))

(defn- emit-navigation!
  [ctx state emit! nav]
  (emit/emit-navigation-result! emit! ctx state nav)
  nav)

(defn- resume-session-navigation
  [ctx state session-id value]
  (when (and (string? value)
             (.exists (io/file value)))
    (navigation/resume-session-result ctx state session-id value)))

(defn- select-session-navigation
  [ctx state session-id value]
  (when (map? value)
    (case (:action/kind value)
      :switch-session
      (when-let [selected-session-id (:action/session-id value)]
        (when-not (str/blank? selected-session-id)
          (navigation/switch-session-result ctx state session-id selected-session-id)))

      :fork-session
      (when-let [entry-id (:action/entry-id value)]
        (when-not (str/blank? entry-id)
          (navigation/fork-session-result ctx state session-id entry-id)))

      nil)))

(defn- handle-submitted-action!
  [{:keys [ctx state session-id resolve-model emit! action-key value]}]
  (case action-key
    :select-resume-session
    (when-let [nav (resume-session-navigation ctx state session-id value)]
      (emit-navigation! ctx state emit! nav))

    :select-session
    (when-let [nav (select-session-navigation ctx state session-id value)]
      (emit-navigation! ctx state emit! nav))

    :select-model
    (command-pickers/handle-model-selection! ctx session-id resolve-model emit! value)

    :select-thinking-level
    (command-pickers/handle-thinking-level-selection! ctx session-id emit! value)

    nil))

(defn handle-frontend-action-result!
  [{:keys [ctx request params state session-id resolve-model]}]
  (let [{:ui.result/keys [request-id action-key status value message]}
        (ui-actions/action-result {:request-id (:request-id params)
                                   :action-name (:action-name params)
                                   :ui-action (:ui/action params)
                                   :status (:status params)
                                   :value (:value params)
                                   :error-message (:error-message params)})
        emit! (emit/make-request-emitter (:emit-frame! request) state (:id request))]
    (case status
      :cancelled
      (do
        (command-results/emit-text-command-result! emit! message)
        (accepted-frame request))

      :failed
      (do
        (emit/emit-command-result! emit! {:type "error"
                                          :message message})
        (accepted-frame request))

      (do
        (when-not (handle-submitted-action! {:ctx ctx
                                             :state state
                                             :session-id session-id
                                             :resolve-model resolve-model
                                             :emit! emit!
                                             :action-key action-key
                                             :value value})
          (emit/emit-session-snapshots! emit! ctx state session-id))
        (accepted-frame request {:accepted true
                                 :request-id request-id})))))

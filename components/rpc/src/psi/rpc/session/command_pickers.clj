(ns psi.rpc.session.command-pickers
  (:require
   [psi.agent-session.core :as session]
   [psi.ai.model-registry :as model-registry]
   [psi.app-runtime.ui-actions :as ui-actions]
   [psi.rpc.session.command-results :as command-results]
   [psi.rpc.session.emit :as emit]))

(defn handle-picker-command!
  [request-id emit! trimmed]
  (case trimmed
    "/model"
    (emit/emit-frontend-action-request!
     emit!
     request-id
     (ui-actions/model-picker-action
      (->> (model-registry/all-models-seq)
           (sort-by (juxt :provider :id))
           (mapv (fn [m]
                   {:provider  (name (:provider m))
                    :id        (:id m)
                    :reasoning (boolean (:supports-reasoning m))})))))

    "/thinking"
    (emit/emit-frontend-action-request!
     emit!
     request-id
     (ui-actions/thinking-picker-action))))

(defn handle-model-selection!
  [ctx session-id resolve-model emit! value]
  (when-let [{:keys [provider id]} value]
    (when-let [resolved (resolve-model ctx provider id)]
      (if (:runtime/unsupported? resolved)
        (emit/emit-command-result! emit!
                                   {:type "unsupported_model"
                                    :message (model-registry/unsupported-runtime-model-message resolved)
                                    :provider (name (:provider resolved))
                                    :model-id (:id resolved)})
        (let [model (model-registry/persistable-model resolved)]
          (session/set-model-in! ctx session-id model)
          (command-results/emit-text-command-result! emit!
                                                     (str "✓ Model set to " (:provider model) " " (:id model))))))))

(defn handle-thinking-level-selection!
  [ctx session-id emit! value]
  (when-let [level-str value]
    (let [level  (keyword level-str)
          result (session/set-thinking-level-in! ctx session-id level)]
      (command-results/emit-text-command-result! emit!
                                                 (str "✓ Thinking level set to "
                                                      (name (:thinking-level result)))))))

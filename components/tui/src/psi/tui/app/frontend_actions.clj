(ns psi.tui.app.frontend-actions
  (:require
   [charm.components.text-input :as text-input]
   [charm.message :as msg]
   [psi.app-runtime.ui-actions :as ui-actions]
   [psi.tui.app.shared :as shared]
   [psi.tui.app.support :as support]
   [psi.tui.session-selector :as session-selector]))

(defn frontend-action-dialog
  [ui-action]
  {:frontend-action? true
   :kind             :select
   :title            (:ui/prompt ui-action)
   :options          (mapv (fn [item]
                             {:label       (:ui.item/label item)
                              :description (some-> (:ui.item/meta item) :description)
                              :value       (:ui.item/value item)})
                           (:ui/items ui-action))})

(defn open-frontend-action-selector
  [state mode ui-action request-id]
  [(-> state
       (assoc :phase :selecting-session
              :frontend-action/request-id request-id
              :frontend-action/ui-action ui-action
              :session-selector (assoc (session-selector/session-selector-init-from-action
                                        (:cwd state)
                                        (:current-session-file state)
                                        (:focus-session-id state)
                                        ui-action)
                                       :frontend-action? true)
              :session-selector-mode mode)
       (shared/set-input-model (text-input/reset (:input state))))
   nil])

(defn clear-frontend-action-state
  [state]
  (-> state
      (dissoc :frontend-action/request-id
              :frontend-action/ui-action
              :frontend-action/dialog)
      (assoc :dialog-selected-index nil
             :dialog-input-text nil
             :session-selector nil
             :session-selector-mode nil)
      (cond-> (= :selecting-session (:phase state))
        (assoc :phase :idle))))

(defn open-frontend-action
  [state {:keys [request-id] ui-action :ui/action}]
  (case (keyword (:ui/action-name ui-action))
    :select-session
    (open-frontend-action-selector state :tree ui-action request-id)

    :select-resume-session
    (open-frontend-action-selector state :resume ui-action request-id)

    (:select-model :select-thinking-level)
    [(-> state
         clear-frontend-action-state
         (assoc :frontend-action/request-id request-id
                :frontend-action/ui-action ui-action
                :frontend-action/dialog (frontend-action-dialog ui-action)
                :dialog-selected-index nil
                :dialog-input-text nil)
         (shared/set-input-model (text-input/reset (:input state))))
     nil]

    [(-> state
         clear-frontend-action-state
         (update :messages conj {:role :assistant
                                 :text (str "Unsupported frontend action: " (:ui/action-name ui-action))}))
     nil]))

(defn apply-frontend-action-result
  [state action-result handle-dispatch-result]
  (let [state'  (-> state
                    clear-frontend-action-state
                    (assoc :last-frontend-action-result action-result))
        handler (:frontend-action-handler-fn! state)
        result  (when handler (handler action-result))]
    (if result
      (handle-dispatch-result state' result)
      [state' nil])))

(defn submit-frontend-action
  [state ui-action value handle-dispatch-result]
  (apply-frontend-action-result
   state
   (ui-actions/action-result {:request-id (:frontend-action/request-id state)
                              :action-name (name (:ui/action-name ui-action))
                              :ui-action ui-action
                              :status :submitted
                              :value value})
   handle-dispatch-result))

(defn cancel-frontend-action
  [state ui-action handle-dispatch-result]
  (apply-frontend-action-result
   state
   (ui-actions/action-result {:request-id (:frontend-action/request-id state)
                              :action-name (name (:ui/action-name ui-action))
                              :ui-action ui-action
                              :status :cancelled})
   handle-dispatch-result))

(defn handle-frontend-action-dialog-key
  [state m handle-dispatch-result]
  (when-let [dialog (:frontend-action/dialog state)]
    (cond
      (msg/key-match? m "escape")
      (cancel-frontend-action state (:frontend-action/ui-action state) handle-dispatch-result)

      (msg/key-match? m "enter")
      (when-let [value (support/selected-dialog-value state dialog)]
        (submit-frontend-action state (:frontend-action/ui-action state) value handle-dispatch-result))

      (and (= :select (:kind dialog)) (msg/key-match? m "up"))
      (support/move-dialog-selection state dialog -1)

      (and (= :select (:kind dialog)) (msg/key-match? m "down"))
      (support/move-dialog-selection state dialog 1)

      :else nil)))

(ns psi.agent-session.extensions.runtime-ui
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.session-state.state :as ss]))

(def extension-ui-allowed-events
  #{:session/ui-request-dialog
    :session/ui-set-widget
    :session/ui-clear-widget
    :session/ui-set-widget-spec
    :session/ui-clear-widget-spec
    :session/ui-set-status
    :session/ui-clear-status
    :session/ui-notify
    :session/ui-register-tool-renderer
    :session/ui-register-message-renderer
    :session/ui-set-tools-expanded})

(defn ^:no-doc extension-ui-context
  [ctx session-id current-session-data ext-path]
  (let [ext-id         (str ext-path)
        extension-meta {:origin :extension
                        :ext-id  ext-id}
        ext-dispatch!  (fn [event data]
                         (dispatch/dispatch! ctx event data extension-meta))
        ui-headless?   (fn []
                         (= :headless (:ui-type (current-session-data))))
        await-dialog   (fn [fallback request]
                         (if (ui-headless?)
                           fallback
                           (some-> (ext-dispatch! :session/ui-request-dialog request)
                                   deref)))
        ext-ui-payload (fn [data]
                         (assoc data
                                :session-id session-id
                                :extension-id ext-id))]
    {:confirm
     (fn [title message]
       (or (await-dialog false {:kind    :confirm
                                :ext-id  ext-id
                                :title   title
                                :message message})
           false))

     :select
     (fn [title options]
       (await-dialog nil {:kind    :select
                          :ext-id  ext-id
                          :title   title
                          :options options}))

     :input
     (fn [title & [placeholder]]
       (await-dialog nil {:kind        :input
                          :ext-id      ext-id
                          :title       title
                          :placeholder placeholder}))

     :set-widget
     (fn [widget-id placement content]
       (ext-dispatch! :session/ui-set-widget
                      (ext-ui-payload {:widget-id widget-id
                                       :placement placement
                                       :content   content})))

     :clear-widget
     (fn [widget-id]
       (ext-dispatch! :session/ui-clear-widget
                      (ext-ui-payload {:widget-id widget-id})))

     :set-widget-spec
     (fn [spec]
       (let [{:keys [accepted? errors]}
             (ext-dispatch! :session/ui-set-widget-spec
                            (ext-ui-payload {:spec spec}))]
         (when-not accepted?
           {:errors errors})))

     :clear-widget-spec
     (fn [widget-id]
       (ext-dispatch! :session/ui-clear-widget-spec
                      (ext-ui-payload {:widget-id widget-id})))

     :set-status
     (fn [text]
       (ext-dispatch! :session/ui-set-status
                      (ext-ui-payload {:text text})))

     :clear-status
     (fn []
       (ext-dispatch! :session/ui-clear-status
                      (ext-ui-payload {})))

     :notify
     (fn [message level]
       (ext-dispatch! :session/ui-notify
                      (ext-ui-payload {:message message
                                       :level   level})))

     :register-tool-renderer
     (fn [tool-name render-call-fn render-result-fn]
       (ext-dispatch! :session/ui-register-tool-renderer
                      (ext-ui-payload {:tool-name        tool-name
                                       :render-call-fn   render-call-fn
                                       :render-result-fn render-result-fn})))

     :register-message-renderer
     (fn [custom-type render-fn]
       (ext-dispatch! :session/ui-register-message-renderer
                      (ext-ui-payload {:custom-type custom-type
                                       :render-fn   render-fn})))

     :get-tools-expanded
     (fn []
       (boolean (get (ss/get-state-value-in ctx (ss/state-path :ui-state))
                     :tools-expanded? false)))

     :set-tools-expanded
     (fn [expanded?]
       (ext-dispatch! :session/ui-set-tools-expanded
                      {:session-id session-id
                       :expanded?  expanded?}))}))

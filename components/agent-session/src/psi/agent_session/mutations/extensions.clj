(ns psi.agent-session.mutations.extensions
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.command-registry.registry :as command-registry]
   [psi.tool-registry.registry :as tool-registry]))

(pco/defmutation register-tool
  "Register an extension-owned tool into the extension registry."
  [_ {:keys [psi/agent-session-ctx ext-path tool]}]
  {::pco/op-name 'psi.extension/register-tool
   ::pco/params  [:psi/agent-session-ctx :ext-path :tool]
   ::pco/output  [:psi.extension/path
                  :psi.extension/tool-names]}
  (let [reg (:extension-registry agent-session-ctx)]
    (tool-registry/register-tool-in! reg ext-path tool)
    {:psi.extension/path       ext-path
     :psi.extension/tool-names (vec (tool-registry/tool-names-in reg))}))

(pco/defmutation register-command
  "Register an extension-owned command into the extension registry."
  [_ {:keys [psi/agent-session-ctx ext-path name opts]}]
  {::pco/op-name 'psi.extension/register-command
   ::pco/params  [:psi/agent-session-ctx :ext-path :name :opts]
   ::pco/output  [:psi.extension/path
                  :psi.extension/command-names]}
  (let [reg (:extension-registry agent-session-ctx)]
    (command-registry/register-command-in! reg ext-path (assoc opts :name name))
    {:psi.extension/path          ext-path
     :psi.extension/command-names (vec (command-registry/command-names-in reg))}))

(pco/defmutation register-handler
  "Register an extension-owned event handler into the extension registry."
  [_ {:keys [psi/agent-session-ctx ext-path event-name handler-fn]}]
  {::pco/op-name 'psi.extension/register-handler
   ::pco/params  [:psi/agent-session-ctx :ext-path :event-name :handler-fn]
   ::pco/output  [:psi.extension/path
                  :psi.extension/handler-count]}
  (let [reg (:extension-registry agent-session-ctx)]
    (ext/register-handler-in! reg ext-path event-name handler-fn)
    {:psi.extension/path          ext-path
     :psi.extension/handler-count (ext/handler-count-in reg)}))

(pco/defmutation register-flag
  "Register an extension-owned flag into the extension registry."
  [_ {:keys [psi/agent-session-ctx ext-path name opts]}]
  {::pco/op-name 'psi.extension/register-flag
   ::pco/params  [:psi/agent-session-ctx :ext-path :name :opts]
   ::pco/output  [:psi.extension/path
                  :psi.extension/flag-names]}
  (let [reg (:extension-registry agent-session-ctx)]
    (ext/register-flag-in! reg ext-path (assoc opts :name name))
    {:psi.extension/path       ext-path
     :psi.extension/flag-names (vec (ext/flag-names-in reg))}))

(pco/defmutation set-allowed-events
  "Set the explicit allowed event set for an extension."
  [_ {:keys [psi/agent-session-ctx ext-path allowed-events]}]
  {::pco/op-name 'psi.extension/set-allowed-events
   ::pco/params  [:psi/agent-session-ctx :ext-path :allowed-events]
   ::pco/output  [:psi.extension/path
                  :psi.extension/allowed-events]}
  (let [reg         (:extension-registry agent-session-ctx)
        allowed-set (set (or allowed-events #{}))]
    (ext/set-allowed-events-in! reg ext-path allowed-set)
    {:psi.extension/path           ext-path
     :psi.extension/allowed-events (vec (sort allowed-set))}))

(pco/defmutation register-shortcut
  "Register an extension-owned keyboard shortcut into the extension registry."
  [_ {:keys [psi/agent-session-ctx ext-path key opts]}]
  {::pco/op-name 'psi.extension/register-shortcut
   ::pco/params  [:psi/agent-session-ctx :ext-path :key :opts]
   ::pco/output  [:psi.extension/path]}
  (let [reg (:extension-registry agent-session-ctx)]
    (ext/register-shortcut-in! reg ext-path (assoc opts :key key))
    {:psi.extension/path ext-path}))

(pco/defmutation add-extension
  "Load an extension into the current session by path."
  [_ {:keys [psi/agent-session-ctx session-id path]}]
  {::pco/op-name 'psi.extension/add-extension
   ::pco/params  [:psi/agent-session-ctx :session-id :path]
   ::pco/output  [:psi.extension/loaded?
                  :psi.extension/path
                  :psi.extension/error]}
  (let [{:keys [loaded? error]} (ext-rt/add-extension-in! agent-session-ctx session-id path)]
    {:psi.extension/loaded? loaded?
     :psi.extension/path    path
     :psi.extension/error   error}))

(defn- reconcile-background-jobs!
  [agent-session-ctx session-id]
  (when agent-session-ctx
    (bg-rt/reconcile-and-emit-background-job-terminals-in! agent-session-ctx session-id)
    (future
      (dotimes [_ 12]
        (Thread/sleep 100)
        (try
          (bg-rt/reconcile-and-emit-background-job-terminals-in! agent-session-ctx session-id)
          (catch Exception _ nil))))))

(pco/defmutation notify
  "Emit an extension-authored UI-visible notification that is excluded from future LLM-visible conversation assembly."
  [_ {:keys [psi/agent-session-ctx session-id role content custom-type]}]
  {::pco/op-name 'psi.extension/notify
   ::pco/params  [:psi/agent-session-ctx :session-id :content]
   ::pco/output  [:psi.extension/message]}
  (let [msg (ext-rt/notify-extension-in! agent-session-ctx
                                         session-id
                                         (or role "assistant")
                                         (or content "")
                                         custom-type)]
    (reconcile-background-jobs! agent-session-ctx session-id)
    {:psi.extension/message msg}))

(pco/defmutation append-message
  "Append an extension-authored synthetic message that becomes part of future LLM-visible conversation assembly."
  [_ {:keys [psi/agent-session-ctx session-id role content]}]
  {::pco/op-name 'psi.extension/append-message
   ::pco/params  [:psi/agent-session-ctx :session-id :role :content]
   ::pco/output  [:psi.extension/message]}
  (let [role* (or role "assistant")]
    (when-not (#{{"assistant" "user"} "assistant" "user"} role*)
      (throw (ex-info "append-message requires role \"assistant\" or \"user\""
                      {:role role*})))
    (let [msg (ext-rt/append-extension-message-in! agent-session-ctx
                                                   session-id
                                                   role*
                                                   (or content ""))]
      (reconcile-background-jobs! agent-session-ctx session-id)
      {:psi.extension/message msg})))

(pco/defmutation schedule-event
  "Schedule a delayed extension event dispatch."
  [_ {:keys [psi/agent-session-ctx session-id delay-ms event-name payload]}]
  {::pco/op-name 'psi.extension/schedule-event
   ::pco/params  [:psi/agent-session-ctx :session-id :delay-ms :event-name]
   ::pco/output  [:psi.extension/scheduled?
                  :psi.extension/event-name
                  :psi.extension/delay-ms]}
  (let [result (dispatch/dispatch! agent-session-ctx
                                   :session/schedule-extension-event
                                   {:session-id session-id
                                    :delay-ms delay-ms
                                    :event-name event-name
                                    :payload payload}
                                   {:origin :mutations})]
    {:psi.extension/scheduled? (boolean (:scheduled? result))
     :psi.extension/event-name (:event-name result)
     :psi.extension/delay-ms   (:delay-ms result)}))

(pco/defmutation inject-mid-system-message
  "Append an extension-authored mid-conversation system instruction."
  [_ {:keys [psi/agent-session-ctx session-id text source ext-path]}]
  {::pco/op-name 'psi.extension/inject-mid-system-message
   ::pco/params  [:psi/agent-session-ctx :session-id :text]
   ::pco/output  [:psi.extension/ok?
                  :psi.extension/error
                  :psi.extension/reason]}
  (let [effective-source (or source ext-path :extension)
        result           (dispatch/dispatch! agent-session-ctx
                                             :session/inject-mid-system-message
                                             {:session-id session-id
                                              :text text
                                              :source effective-source}
                                             {:origin :mutations})]
    {:psi.extension/ok?    (boolean (:ok result))
     :psi.extension/error  (:error result)
     :psi.extension/reason (:reason result)}))

(def all-mutations
  [register-tool
   register-command
   register-handler
   register-flag
   set-allowed-events
   register-shortcut
   add-extension
   notify
   append-message
   schedule-event
   inject-mid-system-message])

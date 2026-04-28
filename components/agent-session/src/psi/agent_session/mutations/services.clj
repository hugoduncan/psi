(ns psi.agent-session.mutations.services
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.service-protocol :as service-protocol]
   [psi.agent-session.services :as services]))

(pco/defmutation register-post-tool-processor
  "Register an extension-owned post-tool processor."
  [_ {:keys [psi/agent-session-ctx ext-path name match timeout-ms handler]}]
  {::pco/op-name 'psi.extension/register-post-tool-processor
   ::pco/params  [:psi/agent-session-ctx :ext-path :name :match :timeout-ms :handler]
   ::pco/output  [:psi.extension/path]}
  (post-tool/register-processor-in!
   agent-session-ctx
   {:name name :ext-path ext-path :match match :timeout-ms timeout-ms :handler handler})
  {:psi.extension/path ext-path})

(pco/defmutation ensure-service
  "Ensure an extension-owned managed service exists."
  [_ {:keys [psi/agent-session-ctx ext-path key type spec]}]
  {::pco/op-name 'psi.extension/ensure-service
   ::pco/params  [:psi/agent-session-ctx :ext-path :key :type :spec]
   ::pco/output  [:psi.extension/path]}
  (services/ensure-service-in!
   agent-session-ctx
   {:key key :type type :spec spec :ext-path ext-path})
  {:psi.extension/path ext-path})

(pco/defmutation stop-service
  "Stop an extension-owned managed service by key."
  [_ {:keys [psi/agent-session-ctx ext-path key]}]
  {::pco/op-name 'psi.extension/stop-service
   ::pco/params  [:psi/agent-session-ctx :ext-path :key]
   ::pco/output  [:psi.extension/path]}
  (services/stop-service-in! agent-session-ctx key)
  {:psi.extension/path ext-path})

(pco/defmutation service-request
  "Send a correlated request to an extension-owned managed service."
  [_ {:keys [psi/agent-session-ctx ext-path key request-id payload timeout-ms dispatch-id]}]
  {::pco/op-name 'psi.extension/service-request
   ::pco/params  [:psi/agent-session-ctx :ext-path :key :request-id :payload :timeout-ms :dispatch-id]
   ::pco/output  [:psi.extension/path
                  :psi.extension.service/service-key
                  :psi.extension.service/request-id
                  :psi.extension.service/payload
                  :psi.extension.service/timeout-ms
                  :psi.extension.service/response]}
  (let [result (service-protocol/send-service-request!
                agent-session-ctx key
                {:request-id request-id
                 :payload payload
                 :timeout-ms timeout-ms}
                {:dispatch-id dispatch-id})]
    {:psi.extension/path                ext-path
     :psi.extension.service/service-key (:service-key result)
     :psi.extension.service/request-id  (:request-id result)
     :psi.extension.service/payload     (:payload result)
     :psi.extension.service/timeout-ms  (:timeout-ms result)
     :psi.extension.service/response    (:response result)}))

(pco/defmutation service-notify
  "Send a notification to an extension-owned managed service."
  [_ {:keys [psi/agent-session-ctx ext-path key payload dispatch-id]}]
  {::pco/op-name 'psi.extension/service-notify
   ::pco/params  [:psi/agent-session-ctx :ext-path :key :payload :dispatch-id]
   ::pco/output  [:psi.extension/path
                  :psi.extension.service/service-key
                  :psi.extension.service/payload]}
  (let [result (service-protocol/send-service-notification!
                agent-session-ctx key payload {:dispatch-id dispatch-id})]
    {:psi.extension/path                ext-path
     :psi.extension.service/service-key (:service-key result)
     :psi.extension.service/payload     (:payload result)}))

(def all-mutations
  [register-post-tool-processor
   ensure-service
   stop-service
   service-request
   service-notify])

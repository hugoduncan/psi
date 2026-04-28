(ns psi.agent-session.resolvers.services
  "Pathom3 resolvers for managed services, post-tool processors, and telemetry."
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.services :as services]))

(def ^:private service-notification-output
  [:psi.service.notification/payload])

(def ^:private service-output
  [:psi.service/id
   :psi.service/key
   :psi.service/type
   :psi.service/status
   :psi.service/command
   :psi.service/cwd
   :psi.service/transport
   :psi.service/ext-path
   :psi.service/pid
   :psi.service/started-at
   :psi.service/stopped-at
   :psi.service/restart-count
   :psi.service/last-error
   :psi.service/notification-count
   {:psi.service/notifications service-notification-output}])

(def ^:private processor-output
  [:psi.post-tool-processor/name
   :psi.post-tool-processor/ext-path
   :psi.post-tool-processor/tools
   :psi.post-tool-processor/timeout-ms])

(def ^:private telemetry-output
  [:psi.post-tool/tool-call-id
   :psi.post-tool/tool-name
   :psi.post-tool/processor-name
   :psi.post-tool/status
   :psi.post-tool/duration-ms
   :psi.post-tool/timestamp])

(def ^:private dispatch-trace-output
  [:psi.dispatch-trace/trace-kind
   :psi.dispatch-trace/dispatch-id
   :psi.dispatch-trace/session-id
   :psi.dispatch-trace/event-type
   :psi.dispatch-trace/event-data
   :psi.dispatch-trace/origin
   :psi.dispatch-trace/replaying?
   :psi.dispatch-trace/blocked?
   :psi.dispatch-trace/block-reason
   :psi.dispatch-trace/validation-error
   :psi.dispatch-trace/interceptor-id
   :psi.dispatch-trace/result
   :psi.dispatch-trace/effects
   :psi.dispatch-trace/effect-type
   :psi.dispatch-trace/effect
   :psi.dispatch-trace/tool-call-id
   :psi.dispatch-trace/service-key
   :psi.dispatch-trace/request-id
   :psi.dispatch-trace/method
   :psi.dispatch-trace/payload
   :psi.dispatch-trace/response
   :psi.dispatch-trace/is-error
   :psi.dispatch-trace/error-message
   :psi.dispatch-trace/timestamp])

(defn- service-notification->eql [msg]
  {:psi.service.notification/payload (:payload msg)})

(defn- service->eql [svc]
  (let [notifications* (:notifications-atom svc)
        notifications  (if notifications*
                         (vec (or @notifications* []))
                         [])]
    {:psi.service/id                 (:id svc)
     :psi.service/key                (:key svc)
     :psi.service/type               (:type svc)
     :psi.service/status             (:status svc)
     :psi.service/command            (:command svc)
     :psi.service/cwd                (:cwd svc)
     :psi.service/transport          (:transport svc)
     :psi.service/ext-path           (:ext-path svc)
     :psi.service/pid                (:pid svc)
     :psi.service/started-at         (:started-at svc)
     :psi.service/stopped-at         (:stopped-at svc)
     :psi.service/restart-count      (:restart-count svc)
     :psi.service/last-error         (:last-error svc)
     :psi.service/notification-count (count notifications)
     :psi.service/notifications      (mapv service-notification->eql notifications)}))

(defn- processor->eql [p]
  {:psi.post-tool-processor/name       (:name p)
   :psi.post-tool-processor/ext-path   (:ext-path p)
   :psi.post-tool-processor/tools      (vec (:tools p))
   :psi.post-tool-processor/timeout-ms (:timeout-ms p)})

(defn- telemetry->eql [e]
  {:psi.post-tool/tool-call-id   (:tool-call-id e)
   :psi.post-tool/tool-name      (:tool-name e)
   :psi.post-tool/processor-name (:processor-name e)
   :psi.post-tool/status         (:status e)
   :psi.post-tool/duration-ms    (:duration-ms e)
   :psi.post-tool/timestamp      (:timestamp e)})

(defn- dispatch-trace->eql [e]
  {:psi.dispatch-trace/trace-kind       (:trace/kind e)
   :psi.dispatch-trace/dispatch-id      (:dispatch-id e)
   :psi.dispatch-trace/session-id       (:session-id e)
   :psi.dispatch-trace/event-type       (:event-type e)
   :psi.dispatch-trace/event-data       (:event-data e)
   :psi.dispatch-trace/origin           (:origin e)
   :psi.dispatch-trace/replaying?       (:replaying? e)
   :psi.dispatch-trace/blocked?         (:blocked? e)
   :psi.dispatch-trace/block-reason     (:block-reason e)
   :psi.dispatch-trace/validation-error (:validation-error e)
   :psi.dispatch-trace/interceptor-id   (:interceptor-id e)
   :psi.dispatch-trace/result           (:result e)
   :psi.dispatch-trace/effects          (:effects e)
   :psi.dispatch-trace/effect-type      (:effect-type e)
   :psi.dispatch-trace/effect           (:effect e)
   :psi.dispatch-trace/tool-call-id     (:tool-call-id e)
   :psi.dispatch-trace/service-key      (:service-key e)
   :psi.dispatch-trace/request-id       (:request-id e)
   :psi.dispatch-trace/method           (:method e)
   :psi.dispatch-trace/payload          (:payload e)
   :psi.dispatch-trace/response         (:response e)
   :psi.dispatch-trace/is-error         (:is-error e)
   :psi.dispatch-trace/error-message    (:error-message e)
   :psi.dispatch-trace/timestamp        (:timestamp e)})

(pco/defresolver services-resolver
  [entity]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.service/count
                 :psi.service/keys
                 {:psi.service/services service-output}]}
  (let [ctx      (:psi/agent-session-ctx entity)
        services (mapv #(service->eql (services/service-in ctx (:key %)))
                       (services/projected-services-in ctx))]
    {:psi.service/count    (services/service-count-in ctx)
     :psi.service/keys     (vec (services/service-keys-in ctx))
     :psi.service/services services}))

(pco/defresolver post-tool-processors-resolver
  [entity]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.post-tool-processor/count
                 {:psi.post-tool-processors processor-output}]}
  (let [ctx (:psi/agent-session-ctx entity)]
    {:psi.post-tool-processor/count (post-tool/processor-count-in ctx)
     :psi.post-tool-processors      (mapv processor->eql (post-tool/projected-processors-in ctx))}))

(pco/defresolver post-tool-telemetry-resolver
  [entity]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.post-tool/processed-count
                 :psi.post-tool/timeout-count
                 :psi.post-tool/error-count
                 {:psi.post-tool/recent-events telemetry-output}]}
  (let [ctx    (:psi/agent-session-ctx entity)
        counts (post-tool/telemetry-counts-in ctx)]
    {:psi.post-tool/processed-count (:processed-count counts)
     :psi.post-tool/timeout-count   (:timeout-count counts)
     :psi.post-tool/error-count     (:error-count counts)
     :psi.post-tool/recent-events   (mapv telemetry->eql (post-tool/recent-telemetry-in ctx))}))

(pco/defresolver dispatch-trace-resolver
  [_entity]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.dispatch-trace/count
                 {:psi.dispatch-trace/recent dispatch-trace-output}]}
  (let [entries (dispatch/dispatch-trace-entries)]
    {:psi.dispatch-trace/count  (count entries)
     :psi.dispatch-trace/recent (mapv dispatch-trace->eql entries)}))

(pco/defresolver dispatch-trace-by-id-resolver
  [entity]
  {::pco/input  [:psi/agent-session-ctx :psi.dispatch-trace/dispatch-id]
   ::pco/output [{:psi.dispatch-trace/by-id dispatch-trace-output}]}
  (let [dispatch-id (:psi.dispatch-trace/dispatch-id entity)
        entries     (->> (dispatch/dispatch-trace-entries)
                         (filter #(= dispatch-id (:dispatch-id %)))
                         vec)]
    {:psi.dispatch-trace/by-id (mapv dispatch-trace->eql entries)}))

(def resolvers
  [services-resolver
   post-tool-processors-resolver
   post-tool-telemetry-resolver
   dispatch-trace-resolver
   dispatch-trace-by-id-resolver])

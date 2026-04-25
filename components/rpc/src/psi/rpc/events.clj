(ns psi.rpc.events
  "RPC event topics and payload projection helpers."
  (:require
   [clojure.string :as str]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.message-text :as message-text]
   [psi.agent-session.session-state :as ss]
   [psi.app-runtime.context :as app-context]
   [psi.app-runtime.context-summary :as context-summary]
   [psi.app-runtime.footer :as footer]
   [psi.app-runtime.projections :as projections]
   [psi.app-runtime.session-summary :as session-summary]
   [psi.rpc.state :as rpc.state]
   [psi.rpc.transport :refer [default-session-id-in event-frame protocol-version]]))

(defn ui-snapshot
  [ctx]
  (projections/extension-ui-snapshot ctx))

(defn session->handshake-server-info
  ([ctx]
   (session->handshake-server-info ctx (default-session-id-in ctx)))
  ([ctx session-id]
   (let [sd (ss/get-session-data-in ctx session-id)]
     {:protocol-version protocol-version
      :features         ["eql-graph" "eql-memory"]
      :session-id       (:session-id sd)
      :model-id         (get-in sd [:model :id])
      :thinking-level   (some-> (:thinking-level sd) name)})))

(def event-topics
  #{"session/updated"
    "session/resumed"
    "session/rehydrated"
    "context/updated"
    "assistant/delta"
    "assistant/thinking-delta"
    "assistant/message"
    "tool/start"
    "tool/executing"
    "tool/update"
    "tool/result"
    "ui/dialog-requested"
    "ui/frontend-action-requested"
    "ui/widgets-updated"
    "ui/widget-specs-updated"
    "ui/status-updated"
    "ui/notification"
    "footer/updated"
    "command-result"
    "error"})

(def ^:private required-event-payload-keys
  {"session/updated" #{:session-id :phase :is-streaming :is-compacting :pending-message-count :retry-attempt :interrupt-pending}
   "session/resumed" #{:session-id :session-file :message-count}
   "session/rehydrated" #{:messages :tool-calls :tool-order}
   "context/updated" #{:active-session-id :sessions}
   "assistant/delta" #{:text}
   "assistant/thinking-delta" #{:text}
   "assistant/message" #{:role :content}
   "tool/start" #{:tool-id :tool-name}
   "tool/executing" #{:tool-id :tool-name}
   "tool/update" #{:tool-id :tool-name :content :result-text :is-error}
   "tool/result" #{:tool-id :tool-name :content :result-text :is-error}
   "ui/dialog-requested" #{:dialog-id :kind :title}
   "ui/frontend-action-requested" #{:request-id :ui/action}
   "ui/widgets-updated" #{:widgets}
   "ui/widget-specs-updated" #{}
   "ui/status-updated" #{:statuses}
   "ui/notification" #{:id :message :level}
   "footer/updated" #{:path-line :stats-line}
   "command-result" #{:type}
   "error" #{:error-code :error-message}})

(defn- topic-subscribed?
  [state topic]
  (let [subs (rpc.state/subscribed-topics state)]
    (or (empty? subs)
        (contains? subs topic))))

(defn- next-event-seq!
  [state]
  (rpc.state/next-event-seq! state))

(defn emit-event!
  [emit-frame! state {:keys [event data id]}]
  (when (and (contains? event-topics event)
             (topic-subscribed? state event))
    (let [required (get required-event-payload-keys event #{})
          payload  (or data {})
          missing  (seq (remove #(contains? payload %) required))]
      (if missing
        (emit-frame! (event-frame {:event "error"
                                   :id id
                                   :seq (next-event-seq! state)
                                   :ts (java.time.Instant/now)
                                   :data {:error-code "protocol/invalid-event-payload"
                                          :error-message "missing required event payload keys"
                                          :event event
                                          :missing-keys (vec missing)}}))
        (emit-frame! (event-frame {:event event
                                   :data payload
                                   :id id
                                   :seq (next-event-seq! state)
                                   :ts (java.time.Instant/now)}))))))

(defn- normalize-level [lvl]
  (cond
    (keyword? lvl) (name lvl)
    (string? lvl)  lvl
    :else          "info"))

(defn session-updated-payload
  ([ctx]
   (session-updated-payload ctx (default-session-id-in ctx)))
  ([ctx session-id]
   (let [summary (session-summary/session-summary ctx session-id)
         command-names (ext/command-names-in (:extension-registry ctx))
         prompt-templates (:prompt-templates (ss/get-session-data-in ctx session-id))]
     (assoc (select-keys summary [:session-id
                                  :session-file
                                  :session-name
                                  :session-display-name
                                  :phase
                                  :is-streaming
                                  :is-compacting
                                  :pending-message-count
                                  :retry-attempt
                                  :interrupt-pending
                                  :model-provider
                                  :model-id
                                  :model-reasoning
                                  :thinking-level
                                  :effective-reasoning-effort
                                  :header-model-label
                                  :status-session-line])
            :extension-command-names (vec (or command-names []))
            :prompt-templates (vec (or prompt-templates []))))))

(defn focus-session-id
  [state]
  (rpc.state/focus-session-id state))

(defn set-focus-session-id!
  [state session-id]
  (rpc.state/set-focus-session-id! state session-id))

(defn- widget->rpc-widget
  [widget]
  {:placement    (some-> (:widget/placement widget) name)
   :extension-id (:widget/extension-id widget)
   :widget-id    (:widget/widget-id widget)
   :content-lines (:widget/content-lines widget)})

(defn context-updated-payload
  ([ctx state session-id]
   (context-updated-payload ctx state (focus-session-id state) session-id))
  ([ctx _state active-session-id session-id]
   (let [snapshot (app-context/context-snapshot ctx active-session-id session-id)
         widget   (context-summary/context-widget snapshot)]
     (cond-> snapshot
       true (assoc :session-tree-widget (widget->rpc-widget widget))))))

(def footer-query footer/footer-query)

(defn- footer-event-payload
  [model]
  (merge (:footer/lines model)
         {:usage-parts              (get-in model [:footer/usage :parts])
          :model-text               (get-in model [:footer/model :text])
          :session-activity-line    (get-in model [:footer/session-activity :line])
          :session-activity-buckets (get-in model [:footer/session-activity :buckets])}))

(defn footer-updated-payload
  [ctx session-id]
  (let [model (footer/footer-model ctx session-id)]
    (footer-event-payload model)))

(defn progress-event->rpc-event
  [progress-event]
  (let [k (:event-kind progress-event)]
    (case k
      :text-delta
      {:event "assistant/delta"
       :data  {:session-id (:session-id progress-event)
               :text       (or (:text progress-event) "")}}

      :thinking-delta
      {:event "assistant/thinking-delta"
       :data  {:session-id (:session-id progress-event)
               :text       (or (:text progress-event) "")}}

      :tool-start
      {:event "tool/start"
       :data  (cond-> {:session-id (:session-id progress-event)
                       :tool-id    (:tool-id progress-event)
                       :tool-name  (:tool-name progress-event)}
                (some? (:arguments progress-event))   (assoc :arguments (:arguments progress-event))
                (some? (:parsed-args progress-event)) (assoc :parsed-args (:parsed-args progress-event)))}

      :tool-executing
      {:event "tool/executing"
       :data  (cond-> {:session-id (:session-id progress-event)
                       :tool-id    (:tool-id progress-event)
                       :tool-name  (:tool-name progress-event)}
                (some? (:arguments progress-event))   (assoc :arguments (:arguments progress-event))
                (some? (:parsed-args progress-event)) (assoc :parsed-args (:parsed-args progress-event)))}

      :tool-execution-update
      {:event "tool/update"
       :data  {:session-id  (:session-id progress-event)
               :tool-id     (:tool-id progress-event)
               :tool-name   (:tool-name progress-event)
               :content     (or (:content progress-event) [])
               :result-text (or (:result-text progress-event) "")
               :details     (:details progress-event)
               :is-error    (boolean (:is-error progress-event))}}

      :tool-result
      {:event "tool/result"
       :data  {:session-id  (:session-id progress-event)
               :tool-id     (:tool-id progress-event)
               :tool-name   (:tool-name progress-event)
               :content     (or (:content progress-event) [])
               :result-text (or (:result-text progress-event) "")
               :details     (:details progress-event)
               :is-error    (boolean (:is-error progress-event))}}

      nil)))

(defn emit-progress-queue!
  [progress-q emit!]
  (loop []
    (when-let [evt (.poll progress-q)]
      (when-let [{:keys [event data]} (progress-event->rpc-event evt)]
        (emit! event data))
      (recur))))

(defn ui-snapshot->events
  [previous current]
  (let [events []
        events (if (and (not= (:active-dialog previous) (:active-dialog current))
                        (map? (:active-dialog current)))
                 (conj events {:event "ui/dialog-requested"
                               :data (let [d (:active-dialog current)]
                                       (cond-> {:dialog-id (:id d)
                                                :kind      (some-> (:kind d) name)
                                                :title     (:title d)}
                                         (contains? d :message) (assoc :message (:message d))
                                         (contains? d :options) (assoc :options (:options d))
                                         (contains? d :placeholder) (assoc :placeholder (:placeholder d))))})
                 events)
        events (if (not= (:widgets previous) (:widgets current))
                 (conj events {:event "ui/widgets-updated"
                               :data  {:widgets (or (:widgets current) [])}})
                 events)
        events (if (not= (:widget-specs previous) (:widget-specs current))
                 (conj events {:event "ui/widget-specs-updated" :data {}})
                 events)
        events (if (not= (:statuses previous) (:statuses current))
                 (conj events {:event "ui/status-updated"
                               :data  {:statuses (or (:statuses current) [])}})
                 events)
        previous-notes (into {} (map (juxt :id identity) (or (:visible-notifications previous) [])))
        current-notes  (into {} (map (juxt :id identity) (or (:visible-notifications current) [])))
        new-notes      (remove #(contains? previous-notes (:id %)) (vals current-notes))]
    (reduce (fn [acc n]
              (conj acc {:event "ui/notification"
                         :data  {:id           (:id n)
                                 :extension-id (:extension-id n)
                                 :message      (:message n)
                                 :level        (normalize-level (:level n))}}))
            events
            new-notes)))

(def ^:private extension-ui-topics
  #{"ui/dialog-requested"
    "ui/widgets-updated"
    "ui/widget-specs-updated"
    "ui/status-updated"
    "ui/notification"})

(defn extension-ui-topic?
  [topic]
  (contains? extension-ui-topics topic))

(defn emit-ui-snapshot-events!
  [emit-frame! state previous current]
  (doseq [{:keys [event data]} (ui-snapshot->events (or previous {}) (or current {}))]
    (emit-event! emit-frame! state {:event event :data data})))

(defn assistant-content-text
  [content]
  (or (message-text/content-display-text content)
      (message-text/content-text content)))

(defn external-message->assistant-payload
  [session-id message]
  (let [content (or (:content message) [])
        text    (or (:text message)
                    (assistant-content-text content))]
    (cond-> {:session-id session-id
             :role       (or (:role message) "assistant")
             :content    content}
      (and (string? text) (not (str/blank? text))) (assoc :text text)
      (contains? message :custom-type) (assoc :custom-type (:custom-type message))
      (contains? message :stop-reason) (assoc :stop-reason (:stop-reason message))
      (contains? message :error-message) (assoc :error-message (:error-message message))
      (contains? message :usage) (assoc :usage (:usage message)))))

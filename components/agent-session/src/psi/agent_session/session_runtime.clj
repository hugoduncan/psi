(ns psi.agent-session.session-runtime
  "Per-session runtime allocation helpers.

   Creates fresh agent-core + session-statechart runtime handles for a
   logical session. Each session gets its own runtime state; handles are not
   transferred between sessions."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.statechart :as sc]
   [psi.session-persistence.core :as persistence]
   [psi.tool-registry.defs :as tool-defs]))

(defn telemetry-state []
  {:tool-output-stats {:calls []
                       :aggregates {:total-context-bytes 0
                                    :by-tool {}
                                    :limit-hits-by-tool {}}}
   :tool-call-attempts []
   :tool-lifecycle-events []
   :provider-requests []
   :provider-replies []
   :provider-events []})

(defn persistence-state
  ([]
   (persistence-state nil false))
  ([session-file flushed?]
   (persistence/persistence-state {:session-file session-file
                                   :flushed? flushed?})))

(defn turn-state [] {:ctx nil})

(defn- agent-initial-state
  [session-data messages agent-initial resolved-tool-defs]
  (merge
   (cond-> {:system-prompt   (or (:system-prompt session-data) "")
            :thinking-level  (or (:thinking-level session-data) :off)
            :tools           (tool-defs/normalize-tool-defs
                              (or resolved-tool-defs []))
            :messages        (vec (or messages []))
            :steering-queue  []
            :follow-up-queue []}
     (:model session-data) (assoc :model (:model session-data)))
   (or agent-initial {})))

(defn create-runtime!
  "Allocate fresh runtime handles for `session-id` and start both statecharts.

   Requires `ctx` to contain `:session-actions-fn` and `:sc-env`.

   Options:
   - :session-data session data map used to seed the runtime agent state
   - :messages     agent transcript messages to preload into agent-core
   - :agent-initial additional agent-core initial state overrides"
  [ctx session-id {:keys [session-data messages agent-initial resolved-tool-defs]}]
  (let [actions-fn    (or (:session-actions-fn ctx)
                          (throw (ex-info "Missing :session-actions-fn on ctx"
                                          {:session-id session-id})))
        sc-session-id (java.util.UUID/randomUUID)
        agent-ctx     (agent/create-context)
        initial       (agent-initial-state session-data messages agent-initial resolved-tool-defs)]
    (sc/start-session! (:sc-env ctx) sc-session-id
                       {:ctx        ctx
                        :session-id session-id
                        :actions-fn actions-fn
                        :config     (:config ctx)})
    (agent/create-agent-in! agent-ctx initial)
    {:agent-ctx agent-ctx
     :sc-session-id sc-session-id}))

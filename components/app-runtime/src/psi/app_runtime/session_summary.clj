(ns psi.app-runtime.session-summary
  "Adapter-neutral session summary projection shared across interactive UIs."
  (:require
   [clojure.string :as str]
   [psi.agent-session.message-text :as message-text]
   [psi.app-runtime.retry-display :as retry-display]
   [psi.session-persistence.core :as persist]
   [psi.session-state.state :as ss]))

(def ^:private thinking-level->reasoning-effort
  {:off nil
   :minimal "minimal"
   :low "low"
   :medium "medium"
   :high "high"
   :xhigh "xhigh"})

(defn- string-value
  [x]
  (when (string? x)
    x))

(defn- safe-count
  [x]
  (cond
    (nil? x)        0
    (map? x)        (count x)
    (set? x)        (count x)
    (sequential? x) (count x)
    :else           0))

(defn effective-reasoning-effort
  [model thinking-level]
  (when (:reasoning model)
    (get thinking-level->reasoning-effort thinking-level "medium")))

(defn model-label
  [{:keys [provider id reasoning thinking-level effective-reasoning-effort]}]
  (let [model-label    (or (string-value id) "")
        provider-label (or (string-value provider) "")
        thinking-label (or (string-value effective-reasoning-effort)
                           (some-> thinking-level name)
                           "off")]
    (when (seq model-label)
      (if (seq provider-label)
        (if reasoning
          (str "(" provider-label ") " model-label " • thinking " thinking-label)
          (str "(" provider-label ") " model-label))
        (if reasoning
          (str model-label " • thinking " thinking-label)
          model-label)))))

(defn session-summary
  [ctx session-id]
  (let [sd                    (ss/get-session-data-in ctx session-id)
        model                 (:model sd)
        thinking-level        (:thinking-level sd)
        effective-effort      (effective-reasoning-effort model thinking-level)
        journal-messages      (persist/messages-from-entries-in ctx session-id)
        session-display-name  (message-text/session-display-name (:session-name sd) journal-messages)
        pending-message-count (+ (safe-count (:steering-messages sd))
                                 (safe-count (:follow-up-messages sd)))
        retry                 (:retry sd)
        now-ms                (.toEpochMilli (java.time.Instant/now))
        summary               {:session-id                 (:session-id sd)
                               :session-file               (:session-file sd)
                               :session-name               (:session-name sd)
                               :session-display-name       session-display-name
                               :phase                      (some-> (ss/sc-phase-in ctx (:session-id sd)) name)
                               :is-streaming               (boolean (:is-streaming sd))
                               :is-compacting              (boolean (:is-compacting sd))
                               :pending-message-count      pending-message-count
                               :retry-attempt              (or (:retry-attempt sd) 0)
                               :retry                      retry
                               :retry-deadline-ms          (:retry-deadline-ms sd)
                               :interrupt-pending          (boolean (:interrupt-pending sd))
                               :model-provider             (:provider model)
                               :model-id                   (:id model)
                               :model-reasoning            (boolean (:reasoning model))
                               :thinking-level             (some-> thinking-level name)
                               :effective-reasoning-effort effective-effort}]
    (assoc summary
           :header-model-label
           (model-label {:provider                   (:model-provider summary)
                         :id                         (:model-id summary)
                         :reasoning                  (:model-reasoning summary)
                         :thinking-level             thinking-level
                         :effective-reasoning-effort effective-effort})
           :status-session-line
           (str/trim
            (str "session: " (:session-id summary)
                 " phase:" (or (:phase summary) "unknown")
                 " streaming:" (if (:is-streaming summary) "yes" "no")
                 " compacting:" (if (:is-compacting summary) "yes" "no")
                 " pending:" (:pending-message-count summary)
                 " retry:" (:retry-attempt summary)
                 (or (retry-display/retry-summary-fragment retry now-ms) ""))))))

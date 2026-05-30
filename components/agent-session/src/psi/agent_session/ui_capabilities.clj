(ns psi.agent-session.ui-capabilities
  "Runtime UI capability/action model and provider normalization."
  (:require
   [clojure.string :as str]))

(def make-visible-capability :psi.ui.capability/make-visible)
(def make-visible-action-id :psi.ui.action/make-visible)

(def no-provider-reason :psi.ui.unavailable.reason/no-provider)
(def no-attached-ui-reason :psi.ui.unavailable.reason/no-attached-ui)
(def unsupported-capability-reason :psi.ui.unavailable.reason/unsupported-capability)
(def provider-error-reason :psi.ui.unavailable.reason/provider-error)

(def supported-invocation-kinds
  #{:emacs-command :ui-event :bash-command :mutation})

(def ui-attrs
  [:psi.ui/type
   :psi.ui/available?
   :psi.ui/capabilities
   :psi.ui/actions
   :psi.ui/make-visible-action
   :psi.ui/diagnostic])

(defn make-visible-action
  "Construct the standard available make-visible descriptor."
  [invocation]
  {:psi.ui.action/id make-visible-action-id
   :psi.ui.action/capability make-visible-capability
   :psi.ui.action/label "Show Psi UI"
   :psi.ui.action/description "Bring the active Psi UI to the foreground."
   :psi.ui.action/available? true
   :psi.ui.action/invocation invocation})

(defn unavailable-make-visible-action
  "Construct the standard unavailable make-visible descriptor."
  [reason message]
  {:psi.ui.action/id make-visible-action-id
   :psi.ui.action/capability make-visible-capability
   :psi.ui.action/label "Show Psi UI"
   :psi.ui.action/description "Bring the active Psi UI to the foreground."
   :psi.ui.action/available? false
   :psi.ui.action/unavailable-reason reason
   :psi.ui.action/unavailable-message message})

(defn emacs-make-visible-provider
  "Provider for an attached Emacs UI that can show its active Psi buffer."
  [_ctx]
  {:psi.ui/type :emacs
   :psi.ui/available? true
   :psi.ui/capabilities [make-visible-capability]
   :psi.ui/actions [(make-visible-action
                     {:psi.ui.invocation/kind :emacs-command
                      :psi.ui.invocation/command "psi-emacs-show-active"})]})

(defn emacs-rpc-provider
  "Provider for an Emacs RPC connection with late-bound active session state.

   active-session-id-fn is called at query time. When it returns nil or blank,
   the connection is present but has no usable active Psi UI state, so the
   provider reports no-attached rather than advertising a stale make-visible
   action. When it returns a session id, the descriptor includes that id as
   invocation correlation data."
  [active-session-id-fn]
  (fn [_ctx]
    (if-let [session-id (not-empty (str (or (active-session-id-fn) "")))]
      {:psi.ui/type :emacs
       :psi.ui/available? true
       :psi.ui/capabilities [make-visible-capability]
       :psi.ui/actions [(make-visible-action
                         {:psi.ui.invocation/kind :emacs-command
                          :psi.ui.invocation/command "psi-emacs-show-active"
                          :psi.ui.invocation/session-id session-id})]}
      {:psi.ui/type :emacs
       :psi.ui/available? false
       :psi.ui/capabilities []
       :psi.ui/actions []})))

(defn unsupported-attached-provider
  "Provider for an attached UI that has no make-visible mechanism."
  [ui-type]
  (fn [_ctx]
    {:psi.ui/type ui-type
     :psi.ui/available? true
     :psi.ui/capabilities []
     :psi.ui/actions []}))

(defn no-attached-provider
  "Provider for a frontend connection with no usable active UI state."
  ([ui-type]
   (fn [_ctx]
     {:psi.ui/type ui-type
      :psi.ui/available? false
      :psi.ui/capabilities []
      :psi.ui/actions []})))

(defn install-provider!
  "Install or replace the active UI capability provider for ctx."
  [ctx provider-fn]
  (reset! (:ui-capability-provider* ctx) provider-fn)
  nil)

(defn clear-provider!
  "Clear the active UI capability provider for ctx."
  [ctx]
  (reset! (:ui-capability-provider* ctx) nil)
  nil)

(defn provider
  [ctx]
  (some-> ctx :ui-capability-provider* deref))

(defn- namespaced-as?
  [kw ns-name]
  (and (keyword? kw) (= ns-name (namespace kw))))

(defn- bounded-string?
  [x]
  (and (string? x) (<= (count x) 512)))

(defn- non-empty-string?
  [x]
  (and (string? x) (not (str/blank? x))))

(defn serializable-value?
  "True when x is pure EDN-like data suitable for EQL results."
  [x]
  (cond
    (nil? x) true
    (or (string? x) (keyword? x) (symbol? x) (number? x) (boolean? x)) true
    (map? x) (and (every? serializable-value? (keys x))
                  (every? serializable-value? (vals x)))
    (vector? x) (every? serializable-value? x)
    (set? x) (every? serializable-value? x)
    (sequential? x) (every? serializable-value? x)
    :else false))

(defn- valid-emacs-command? [inv]
  (and (non-empty-string? (:psi.ui.invocation/command inv))
       (or (not (contains? inv :psi.ui.invocation/args))
           (and (vector? (:psi.ui.invocation/args inv))
                (serializable-value? (:psi.ui.invocation/args inv))))
       (or (not (contains? inv :psi.ui.invocation/session-id))
           (serializable-value? (:psi.ui.invocation/session-id inv)))
       (or (not (contains? inv :psi.ui.invocation/runtime-id))
           (serializable-value? (:psi.ui.invocation/runtime-id inv)))))

(defn- valid-ui-event? [inv]
  (and (keyword? (:psi.ui.invocation/event inv))
       (some? (namespace (:psi.ui.invocation/event inv)))
       (or (not (contains? inv :psi.ui.invocation/payload))
           (and (map? (:psi.ui.invocation/payload inv))
                (serializable-value? (:psi.ui.invocation/payload inv))))))

(defn- valid-bash-command? [inv]
  (let [argv (:psi.ui.invocation/argv inv)
        env (:psi.ui.invocation/env inv)]
    (and (vector? argv)
         (seq argv)
         (every? non-empty-string? argv)
         (or (nil? env)
             (and (map? env)
                  (every? string? (keys env))
                  (every? string? (vals env)))))))

(defn- valid-mutation? [inv]
  (let [mutation (:psi.ui.invocation/mutation inv)
        params (:psi.ui.invocation/params inv)]
    (and (qualified-symbol? mutation)
         (or (nil? params)
             (and (map? params) (serializable-value? params))))))

(defn valid-invocation?
  [inv]
  (and (map? inv)
       (serializable-value? inv)
       (case (:psi.ui.invocation/kind inv)
         :emacs-command (valid-emacs-command? inv)
         :ui-event (valid-ui-event? inv)
         :bash-command (valid-bash-command? inv)
         :mutation (valid-mutation? inv)
         false)))

(defn- valid-unavailable-reason? [reason]
  (namespaced-as? reason "psi.ui.unavailable.reason"))

(defn- valid-action? [action]
  (and (map? action)
       (serializable-value? action)
       (namespaced-as? (:psi.ui.action/id action) "psi.ui.action")
       (namespaced-as? (:psi.ui.action/capability action) "psi.ui.capability")
       (bounded-string? (:psi.ui.action/label action))
       (bounded-string? (:psi.ui.action/description action))
       (boolean? (:psi.ui.action/available? action))
       (if (:psi.ui.action/available? action)
         (valid-invocation? (:psi.ui.action/invocation action))
         (and (valid-unavailable-reason? (:psi.ui.action/unavailable-reason action))
              (bounded-string? (:psi.ui.action/unavailable-message action))))))

(defn- duplicate-action-ids?
  [actions]
  (let [ids (map :psi.ui.action/id actions)]
    (not= (count ids) (count (set ids)))))

(defn- diagnostic-source
  [x]
  (if (instance? Throwable x)
    (str (some-> x class .getName) ": " (.getMessage ^Throwable x))
    (str x)))

(defn- redact-diagnostic-text
  [text]
  (-> text
      ;; Stacktrace frames can expose local source paths and implementation details.
      (str/replace #"(?m)\s+at\s+[A-Za-z0-9_.$/<>-]+\([^)]*\)" " [STACKTRACE_REDACTED]")
      ;; Frontend/runtime object printed forms are not serialisable UI data.
      (str/replace #"#<[^>]*>" "[OBJECT_REDACTED]")
      (str/replace #"#object\[[^\]]*\]" "[OBJECT_REDACTED]")
      ;; Secret-bearing paths should not be surfaced through graph diagnostics.
      (str/replace #"(?i)(?:~|/)[^\s,;]*?(?:secret|token|password|credential|\.ssh|id_rsa)[^\s,;]*" "[PATH_REDACTED]")
      ;; Common inline key/value secret forms.
      (str/replace #"(?i)\b([A-Za-z0-9_.-]*(?:token|secret|password|api[-_]?key|credential)[A-Za-z0-9_.-]*\s*[:=]\s*)\S+" "$1[REDACTED]")
      ;; Common bearer/API-token-looking values even when the key is omitted.
      (str/replace #"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+" "Bearer [REDACTED]")
      (str/replace #"\b(?:sk|pk)-[A-Za-z0-9_-]{12,}\b" "[REDACTED_TOKEN]")
      (str/replace #"[\r\n\t]+" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn- diagnostic-text
  [x]
  (let [redacted (redact-diagnostic-text (diagnostic-source x))]
    (subs redacted 0 (min 512 (count redacted)))))

(defn provider-error-result
  ([message]
   (provider-error-result message nil))
  ([message diagnostic]
   {:psi.ui/type nil
    :psi.ui/available? false
    :psi.ui/capabilities []
    :psi.ui/actions []
    :psi.ui/make-visible-action (unavailable-make-visible-action
                                 provider-error-reason
                                 "The active UI capability provider returned invalid data.")
    :psi.ui/diagnostic (or diagnostic (diagnostic-text message))}))

(defn missing-provider-result
  []
  {:psi.ui/type nil
   :psi.ui/available? false
   :psi.ui/capabilities []
   :psi.ui/actions []
   :psi.ui/make-visible-action (unavailable-make-visible-action
                                no-provider-reason
                                "No UI capability provider is installed.")
   :psi.ui/diagnostic nil})

(defn normalize-provider-result
  "Normalize and validate raw provider data into root-queryable UI attrs."
  [raw]
  (try
    (let [ui-type (:psi.ui/type raw)
          available? (:psi.ui/available? raw false)
          capabilities (vec (or (:psi.ui/capabilities raw) []))
          raw-actions (vec (or (:psi.ui/actions raw) []))]
      (cond
        (not (map? raw))
        (provider-error-result "provider did not return a map")

        (and (some? ui-type) (not (keyword? ui-type)))
        (provider-error-result "provider returned invalid :psi.ui/type")

        (not (boolean? available?))
        (provider-error-result "provider returned invalid :psi.ui/available?")

        (not (and (vector? capabilities)
                  (every? #(namespaced-as? % "psi.ui.capability") capabilities)))
        (provider-error-result "provider returned invalid :psi.ui/capabilities")

        (not (and (vector? raw-actions) (every? valid-action? raw-actions)))
        (provider-error-result "provider returned invalid :psi.ui/actions")

        (duplicate-action-ids? raw-actions)
        (provider-error-result "provider returned duplicate UI action ids")

        :else
        (let [capability-set (set capabilities)
              actions (filterv :psi.ui.action/available? raw-actions)
              make-visible-actions (filterv #(= make-visible-action-id (:psi.ui.action/id %)) actions)
              incoherent-unavailable? (and (false? available?)
                                           (or (seq capabilities) (seq actions)))
              incoherent-action? (some #(not (contains? capability-set (:psi.ui.action/capability %))) actions)]
          (cond
            incoherent-unavailable?
            (provider-error-result "provider returned unavailable UI with capabilities or available actions")

            incoherent-action?
            (provider-error-result "provider returned an available action without its capability")

            (and (contains? capability-set make-visible-capability)
                 (not= 1 (count make-visible-actions)))
            (provider-error-result "provider returned incoherent make-visible capability/action data")

            :else
            (let [make-visible (if (= 1 (count make-visible-actions))
                                 (first make-visible-actions)
                                 (unavailable-make-visible-action
                                  (if available? unsupported-capability-reason no-attached-ui-reason)
                                  (if available?
                                    "The attached UI does not support making itself visible."
                                    "No attached UI adapter can make itself visible.")))]
              {:psi.ui/type ui-type
               :psi.ui/available? available?
               :psi.ui/capabilities capabilities
               :psi.ui/actions actions
               :psi.ui/make-visible-action make-visible
               :psi.ui/diagnostic nil})))))
    (catch Throwable t
      (provider-error-result "provider normalization failed" (diagnostic-text t)))))

(defn resolve-ui
  "Call the current provider at query time and normalize the result."
  [ctx]
  (if-let [provider-fn (provider ctx)]
    (try
      (normalize-provider-result (provider-fn ctx))
      (catch Throwable t
        (provider-error-result "provider threw" (diagnostic-text t))))
    (missing-provider-result)))

(ns psi.extension-test-helpers.nullable-api
  "Nullable (in-memory) ExtensionAPI fixture for extension tests.

   Purpose:
   - Provide a realistic API map for extension `init` fns
   - Record registrations and runtime calls for assertions
   - Avoid mocks/spies in favor of nullable infrastructure"
  (:require
   [clojure.string :as str]))

(defn create-state
  "Create mutable in-memory state used by the nullable API."
  []
  (atom {:queries              []
         :mutations            []
         :commands             {}
         :tools                {}
         :handlers             {}
         :flags                {}
         :flag-values          {}
         :shortcuts            {}
         :notifications        []
         :log-lines            []
         :widgets              {}
         :widget-specs         {}
         :cleared-widgets      []
         :cleared-widget-specs []
         :status-lines         []
         :entries              []
         :messages             []
         :scheduled-events     []
         :workflow-types       {}
         :workflows            {}
         :prompt-contributions {}
         :post-tool-processors []
         :services             []
         :service-requests     []
         :service-notifications []}))

(defn- workflow-seq
  [state ext-path]
  (->> (vals (:workflows @state))
       (filter (fn [wf]
                 (or (nil? ext-path)
                     (= ext-path (:psi.extension/path wf)))))
       vec))

(defn- default-query-fn
  [state {:keys [path model system-prompt ui-type]} q]
  (swap! state update :queries conj q)
  (cond
    ;; Session model
    (= q [:psi.agent-session/model])
    {:psi.agent-session/model
     (or model
         {:provider :anthropic
          :id       "claude-sonnet-4-6"})}

    ;; Session system prompt
    (= q [:psi.agent-session/system-prompt])
    {:psi.agent-session/system-prompt system-prompt}

    ;; Session UI type
    (= q [:psi.agent-session/ui-type])
    {:psi.agent-session/ui-type (or ui-type :console)}

    ;; Extension workflows
    (and (vector? q)
         (some (fn [x]
                 (and (map? x)
                      (contains? x :psi.extension/workflows)))
               q))
    {:psi.extension/workflows (workflow-seq state path)}

    ;; Extension prompt contributions
    (= q [:psi.extension/prompt-contributions])
    {:psi.extension/prompt-contributions (->> (:prompt-contributions @state)
                                              vals
                                              vec)}

    :else
    {}))

(defn- ensure-flag-default! [state name flag]
  (when (and (contains? flag :default)
             (not (contains? (:flag-values @state) name)))
    (swap! state assoc-in [:flag-values name] (:default flag))))

(defn- workflow-created-response [id job-id]
  (cond-> {:psi.extension.workflow/created? true
           :psi.extension.workflow/id id}
    job-id (assoc :psi.extension.background-job/id job-id)))

(defn- prompt-contribution-count [state]
  (count (:prompt-contributions @state)))

(defn- prompt-contribution-key [params]
  [(:ext-path params) (str (:id params))])

(defn- register-command! [state params]
  (let [name (:name params)
        opts (:opts params)]
    (swap! state assoc-in [:commands name] (assoc opts :name name))
    {:psi.extension/registered-command? true}))

(defn- register-handler! [state params]
  (let [event-name (:event-name params)
        handler-fn (:handler-fn params)]
    (swap! state update-in [:handlers event-name] (fnil conj []) handler-fn)
    {:psi.extension/registered-handler? true}))

(defn- register-tool! [state params]
  (let [tool (:tool params)]
    (swap! state assoc-in [:tools (:name tool)] tool)
    {:psi.extension/registered-tool? true}))

(defn- register-flag! [state params]
  (let [name (or (:name params) (get-in params [:opts :name]))
        opts (:opts params)
        flag (assoc opts :name name)]
    (swap! state assoc-in [:flags name] flag)
    (ensure-flag-default! state name flag)
    {:psi.extension/registered-flag? true}))

(defn- register-shortcut! [state params]
  (let [key (:key params)
        opts (:opts params)]
    (swap! state assoc-in [:shortcuts key] (assoc opts :key key))
    {:psi.extension/registered-shortcut? true}))

(defn- register-workflow-type! [state params]
  (let [type (:type params)]
    (swap! state assoc-in [:workflow-types type] params)
    {:psi.extension.workflow/registered? true}))

(defn- create-workflow! [state params]
  (let [id     (str (:id params))
        wf     {:psi.extension/path                   (:ext-path params)
                :psi.extension.workflow/id            id
                :psi.extension.workflow/type          (:type params)
                :psi.extension.workflow/phase         :idle
                :psi.extension.workflow/running?      false
                :psi.extension.workflow/done?         false
                :psi.extension.workflow/error?        false
                :psi.extension.workflow/error-message nil
                :psi.extension.workflow/input         (:input params)
                :psi.extension.workflow/meta          (:meta params)
                :psi.extension.workflow/data          {}
                :psi.extension.workflow/result        nil
                :psi.extension.workflow/elapsed-ms    0
                :psi.extension.workflow/started-at    nil}
        job-id (when (true? (:track-background-job? params))
                 (str "job-" id))]
    (swap! state assoc-in [:workflows id] wf)
    (workflow-created-response id job-id)))

(defn- send-workflow-event! [_state params]
  (let [job-id (when (true? (:track-background-job? params))
                 (str "job-" (:id params) "-cont"))]
    (cond-> {:psi.extension.workflow/event-accepted? true}
      job-id (assoc :psi.extension.background-job/id job-id))))

(defn- remove-workflow! [state params]
  (let [id       (str (:id params))
        removed? (contains? (:workflows @state) id)]
    (swap! state update :workflows dissoc id)
    {:psi.extension.workflow/removed? removed?}))

(defn- register-prompt-contribution! [state params]
  (let [id    (str (:id params))
        key   [(:ext-path params) id]
        value (merge {:id id
                      :ext-path (:ext-path params)}
                     (:contribution params))]
    (swap! state assoc-in [:prompt-contributions key] value)
    {:psi.extension.prompt-contribution/registered? true
     :psi.extension.prompt-contribution/id id
     :psi.extension.prompt-contribution/count (prompt-contribution-count state)}))

(defn- update-prompt-contribution! [state params]
  (let [id      (str (:id params))
        key     (prompt-contribution-key params)
        current (get-in @state [:prompt-contributions key])]
    (if current
      (do
        (swap! state update-in [:prompt-contributions key] merge (:patch params))
        {:psi.extension.prompt-contribution/updated? true
         :psi.extension.prompt-contribution/id id
         :psi.extension.prompt-contribution/count (prompt-contribution-count state)})
      {:psi.extension.prompt-contribution/updated? false
       :psi.extension.prompt-contribution/id id
       :psi.extension.prompt-contribution/count (prompt-contribution-count state)})))

(defn- unregister-prompt-contribution! [state params]
  (let [id      (str (:id params))
        key     (prompt-contribution-key params)
        existed? (contains? (:prompt-contributions @state) key)]
    (swap! state update :prompt-contributions dissoc key)
    {:psi.extension.prompt-contribution/removed? existed?
     :psi.extension.prompt-contribution/id id
     :psi.extension.prompt-contribution/count (prompt-contribution-count state)}))

(defn- append-entry! [state params]
  (swap! state update :entries conj params)
  {:psi.extension/entry-appended? true})

(defn- notify! [state params]
  (swap! state update :messages conj (assoc params :custom-type (or (:custom-type params) "extension-notification")))
  {:psi.extension/message params})

(defn- append-message! [state params]
  (swap! state update :messages conj params)
  {:psi.extension/message params})

(defn- send-prompt! [state params]
  (swap! state update :messages conj (assoc params :role "user" :custom-type "extension-prompt"))
  {:psi.extension/prompt-accepted? true
   :psi.extension/prompt-delivery :prompt})

(defn- schedule-event! [state params]
  (swap! state update :scheduled-events conj params)
  {:psi.extension/scheduled? true
   :psi.extension/event-name (:event-name params)
   :psi.extension/delay-ms (:delay-ms params)})

(defn- register-post-tool-processor! [state params]
  (let [entry {:name (:name params)
               :ext-path (:ext-path params)
               :tools (get-in params [:match :tools])
               :timeout-ms (:timeout-ms params)}]
    (swap! state update :post-tool-processors conj entry)
    {:psi.extension/registered-post-tool-processor? true}))

(defn- ensure-service! [state params]
  (let [entry {:key (:key params)
               :type (:type params)
               :spec (:spec params)}]
    (swap! state update :services conj entry)
    {:psi.extension/service-ensured? true}))

(defn- stop-service! [state params]
  (swap! state update :services (fn [svc] (vec (remove #(= (:key %) (:key params)) svc))))
  {:psi.extension/service-stopped? true})

(defn- service-request! [state params]
  (swap! state update :service-requests (fnil conj []) params)
  {:psi.extension.service/service-key (:key params)
   :psi.extension.service/request-id (:request-id params)
   :psi.extension.service/payload (:payload params)
   :psi.extension.service/timeout-ms (:timeout-ms params)
   :psi.extension.service/response (:response params)
   :response (:response params)})

(defn- service-notify! [state params]
  (swap! state update :service-notifications (fnil conj []) params)
  {:psi.extension.service/service-key (:key params)
   :psi.extension.service/payload (:payload params)})

(def ^:private mutation-handlers
  {'psi.extension/register-command register-command!
   'psi.extension/register-handler register-handler!
   'psi.extension/register-tool register-tool!
   'psi.extension/register-flag register-flag!
   'psi.extension/register-shortcut register-shortcut!
   'psi.extension.workflow/register-type register-workflow-type!
   'psi.extension.workflow/create create-workflow!
   'psi.extension.workflow/send-event send-workflow-event!
   'psi.extension.workflow/remove remove-workflow!
   'psi.extension.workflow/abort (fn [_state _params] {:psi.extension.workflow/aborted? true})
   'psi.extension/register-prompt-contribution register-prompt-contribution!
   'psi.extension/update-prompt-contribution update-prompt-contribution!
   'psi.extension/unregister-prompt-contribution unregister-prompt-contribution!
   'psi.extension/append-entry append-entry!
   'psi.extension/notify notify!
   'psi.extension/append-message append-message!
   'psi.extension/send-prompt send-prompt!
   'psi.extension/schedule-event schedule-event!
   'psi.extension/register-post-tool-processor register-post-tool-processor!
   'psi.extension/ensure-service ensure-service!
   'psi.extension/stop-service stop-service!
   'psi.extension/service-request service-request!
   'psi.extension/service-notify service-notify!})

(defn- default-mutate-fn
  [state _opts op params]
  (swap! state update :mutations conj {:op op :params params})
  (if-let [handler (get mutation-handlers op)]
    (handler state params)
    {}))

(defn- add-tool! [state tool]
  (swap! state assoc-in [:tools (:name tool)] tool)
  tool)

(defn- add-command! [state name opts]
  (let [cmd (assoc opts :name name)]
    (swap! state assoc-in [:commands name] cmd)
    cmd))

(defn- add-flag! [state name opts]
  (let [flag (assoc opts :name name)]
    (swap! state assoc-in [:flags name] flag)
    (ensure-flag-default! state name flag)
    flag))

(defn- add-shortcut! [state key opts]
  (let [shortcut (assoc opts :key key)]
    (swap! state assoc-in [:shortcuts key] shortcut)
    shortcut))

(defn- add-handler! [state event-name handler-fn]
  (swap! state update-in [:handlers event-name] (fnil conj []) handler-fn)
  handler-fn)

(defn- create-ui-api [state]
  {:notify            (fn [text level]
                        (swap! state update :notifications conj {:text text :level level}))
   :set-widget        (fn [id position lines]
                        (swap! state assoc-in [:widgets id]
                               {:id id :position position :lines lines}))
   :clear-widget      (fn [id]
                        (swap! state update :widgets dissoc id)
                        (swap! state update :cleared-widgets conj id))
   :set-widget-spec   (fn [spec]
                        (swap! state assoc-in [:widget-specs (:id spec)] spec))
   :clear-widget-spec (fn [id]
                        (swap! state update :widget-specs dissoc id)
                        (swap! state update :cleared-widget-specs conj id))
   :set-status        (fn [text]
                        (swap! state update :status-lines conj (str/trim (or text ""))))})

(defn ^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
  create-nullable-extension-api
  "Create {:api .. :state atom} for extension tests.

   Options:
   - :path             extension path (default /test/extension.clj)
   - :model            value returned for [:psi.agent-session/model]
   - :system-prompt    value returned for [:psi.agent-session/system-prompt]
   - :query-fn         optional override
   - :mutate-fn        optional override
   - :get-api-key-fn   optional override"
  ([] (create-nullable-extension-api {}))
  ([{:keys [path query-fn mutate-fn get-api-key-fn]
     :as   opts}]
   (let [path*       (or path "/test/extension.clj")
         opts*       (assoc opts :path path*)
         state       (create-state)
         query*      (or query-fn (fn [q] (default-query-fn state opts* q)))
         mutate-base (or mutate-fn (fn [op params] (default-mutate-fn state opts* op params)))
         mutate*     (fn [op params]
                       (let [ext-op?  (and (symbol? op)
                                           (let [ns* (namespace op)]
                                             (or (= "psi.extension" ns*)
                                                 (and (string? ns*)
                                                      (str/starts-with? ns* "psi.extension.")))))
                             params' (if (and ext-op?
                                              (map? params)
                                              (not (contains? params :ext-path)))
                                       (assoc params :ext-path path*)
                                       params)]
                         (mutate-base op params')))
         get-key*    (or get-api-key-fn (fn [_provider] nil))
         api         {:path path*
                      :query query*
                      :query-session (fn [session-id eql-query]
                                       (query* {:session-id session-id
                                                :query eql-query}))
                      :mutate mutate*
                      :mutate-session (fn [session-id op params]
                                        (mutate* op (assoc (or params {}) :session-id session-id)))
                      :create-session (fn [{:keys [session-name worktree-path system-prompt thinking-level] :as opts}]
                                        (mutate* 'psi.extension/create-session
                                                 (cond-> {:session-name session-name
                                                          :worktree-path worktree-path}
                                                   (contains? opts :system-prompt) (assoc :system-prompt system-prompt)
                                                   (contains? opts :thinking-level) (assoc :thinking-level thinking-level))))
                      :switch-session (fn [session-id]
                                        (mutate* 'psi.extension/switch-session {:session-id session-id}))
                      :set-worktree-path (fn [session-id worktree-path]
                                           (mutate* 'psi.extension/set-worktree-path
                                                    {:session-id session-id
                                                     :worktree-path worktree-path}))
                      :notify (fn [content & [{:keys [role custom-type] :as opts}]]
                                (let [params (cond-> {:content content}
                                               (contains? opts :role) (assoc :role role)
                                               (contains? opts :custom-type) (assoc :custom-type custom-type))]
                                  (mutate* 'psi.extension/notify params)))
                      :append-message (fn [role content]
                                        (mutate* 'psi.extension/append-message
                                                 {:role role
                                                  :content content}))
                      :register-post-tool-processor (fn [{:keys [name match timeout-ms handler]}]
                                                      (mutate* 'psi.extension/register-post-tool-processor
                                                               {:name name
                                                                :match match
                                                                :timeout-ms timeout-ms
                                                                :handler handler}))
                      :ensure-service (fn [{:keys [key type spec]}]
                                        (mutate* 'psi.extension/ensure-service
                                                 {:key key :type type :spec spec}))
                      :stop-service (fn [key]
                                      (mutate* 'psi.extension/stop-service {:key key}))
                      :service-request (fn [opts]
                                         (mutate* 'psi.extension/service-request opts))
                      :service-notify (fn [opts]
                                        (mutate* 'psi.extension/service-notify opts))
                      :list-services (fn []
                                       (mapv (fn [svc]
                                               {:psi.service/key (:key svc)
                                                :psi.service/type (:type svc)
                                                :psi.service/status :running
                                                :psi.service/command (get-in svc [:spec :command])
                                                :psi.service/cwd (get-in svc [:spec :cwd])
                                                :psi.service/transport (get-in svc [:spec :transport])})
                                             (:services @state)))
                      :get-api-key get-key*
                      :ui-type (or (:ui-type opts*) :console)
                      :register-tool (fn [tool] (add-tool! state tool))
                      :register-command (fn [name opts] (add-command! state name opts))
                      :register-flag (fn [name opts] (add-flag! state name opts))
                      :register-shortcut (fn [key opts] (add-shortcut! state key opts))
                      :on (fn [event-name handler-fn] (add-handler! state event-name handler-fn))
                      :get-flag (fn [name] (get-in @state [:flag-values name]))
                      :register-prompt-contribution
                      (fn [id contribution]
                        (mutate* 'psi.extension/register-prompt-contribution
                                 {:id id :contribution contribution}))
                      :update-prompt-contribution
                      (fn [id patch]
                        (mutate* 'psi.extension/update-prompt-contribution
                                 {:id id :patch patch}))
                      :unregister-prompt-contribution
                      (fn [id]
                        (mutate* 'psi.extension/unregister-prompt-contribution
                                 {:id id}))
                      :list-prompt-contributions
                      (fn []
                        (let [all (:psi.extension/prompt-contributions
                                   (query* [:psi.extension/prompt-contributions]))]
                          (->> all
                               (filter #(= path* (:ext-path %)))
                               vec)))
                      :log (fn [text] (swap! state update :log-lines conj text))
                      :ui (create-ui-api state)}]
     {:api api
      :state state})))

(defn ^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
  drain-log!
  "Return all log lines captured since last drain, clearing the buffer.
   Works with nullable API state atoms."
  [state]
  (let [lines (:log-lines @state)]
    (swap! state assoc :log-lines [])
    (str/join "\n" lines)))

(defmacro ^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
  with-log-str
  "Evaluate BODY, return log lines captured in STATE as a single string."
  [state & body]
  `(do
     (swap! ~state assoc :log-lines [])
     ~@body
     (drain-log! ~state)))

(defmacro ^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
  with-user-dir
  "Temporarily set java user.dir while evaluating body."
  [dir & body]
  `(let [orig# (System/getProperty "user.dir")]
     (try
       (System/setProperty "user.dir" (str ~dir))
       ~@body
       (finally
         (System/setProperty "user.dir" orig#)))))


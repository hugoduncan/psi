(ns psi.agent-session.extensions.api
  "Extension runtime API construction."
  (:require
   [clojure.string :as str]))

(def ^:private services-query
  [{:psi.service/services [:psi.service/key
                           :psi.service/status
                           :psi.service/command
                           :psi.service/cwd
                           :psi.service/transport
                           :psi.service/ext-path
                           :psi.service/notification-count]}])

(defn- runtime-not-initialized
  [action]
  (throw (ex-info "Extension runtime not initialized" {:action action})))

(defn- extension-op?
  [op-sym]
  (and (symbol? op-sym)
       (let [ns* (namespace op-sym)]
         (or (= "psi.extension" ns*)
             (and (string? ns*)
                  (str/starts-with? ns* "psi.extension."))))))

(defn- with-ext-path
  [ext-path params]
  (if (and (map? params)
           (not (contains? params :ext-path)))
    (assoc params :ext-path ext-path)
    params))

(defn- mutate-extension-op
  [mutate-fn ext-path op params]
  (mutate-fn op (with-ext-path ext-path params)))

(defn- mutate-ext-or-local
  [mutate-fn ext-path op params fallback-fn]
  (if mutate-fn
    (mutate-extension-op mutate-fn ext-path op params)
    (fallback-fn)))

(defn- service-request-params
  [{:keys [key request-id payload timeout-ms dispatch-id] :as opts}]
  (cond-> {:key        key
           :request-id request-id
           :payload    payload}
    (contains? opts :timeout-ms)  (assoc :timeout-ms timeout-ms)
    (contains? opts :dispatch-id) (assoc :dispatch-id dispatch-id)))

(defn- service-notify-params
  [{:keys [key payload dispatch-id] :as opts}]
  (cond-> {:key     key
           :payload payload}
    (contains? opts :dispatch-id) (assoc :dispatch-id dispatch-id)))

(defn- create-session-params
  [{:keys [session-name worktree-path system-prompt thinking-level] :as opts}]
  (cond-> {:session-name session-name
           :worktree-path worktree-path}
    (contains? opts :system-prompt)  (assoc :system-prompt system-prompt)
    (contains? opts :thinking-level) (assoc :thinking-level thinking-level)))

(defn- normalize-prompt-contribution
  [c]
  {:id         (or (:id c)
                   (:psi.extension.prompt-contribution/id c))
   :ext-path   (or (:ext-path c)
                   (:psi.extension.prompt-contribution/ext-path c))
   :section    (or (:section c)
                   (:psi.extension.prompt-contribution/section c))
   :content    (or (:content c)
                   (:psi.extension.prompt-contribution/content c))
   :priority   (or (:priority c)
                   (:psi.extension.prompt-contribution/priority c))
   :enabled    (if (contains? c :enabled)
                 (:enabled c)
                 (:psi.extension.prompt-contribution/enabled c))
   :created-at (or (:created-at c)
                   (:psi.extension.prompt-contribution/created-at c))
   :updated-at (or (:updated-at c)
                   (:psi.extension.prompt-contribution/updated-at c))})

(defn- list-extension-prompt-contributions
  [query-fn ext-path]
  (let [all (if query-fn
              (:psi.extension/prompt-contributions
               (query-fn [:psi.extension/prompt-contributions]))
              [])]
    (->> all
         (map normalize-prompt-contribution)
         (filter #(= ext-path (:ext-path %)))
         vec)))

(defn create-extension-api
  "Build the ExtensionAPI map for an extension at `ext-path`.
   `ops` supplies registry-local registration and event-bus functions."
  [{:keys [register-handler-in!
           register-tool-in!
           register-command-in!
           register-shortcut-in!
           register-flag-in!
           set-allowed-events-in!
           get-flag-in
           bus-emit-in!
           bus-on-in!]}
   reg
   ext-path
   {:keys [mutate-fn query-fn get-api-key-fn ui-type-fn ui-context-fn service-fn log-fn]}]
  (let [mutate-local
        (fn [op params fallback-fn]
          (mutate-ext-or-local mutate-fn ext-path op params fallback-fn))
        mutate-ext-required
        (fn [action op params]
          (if mutate-fn
            (mutate-extension-op mutate-fn ext-path op params)
            (runtime-not-initialized action)))
        mutate-required
        (fn [action op params]
          (if mutate-fn
            (mutate-fn op params)
            (runtime-not-initialized action)))
        runtime-query
        (fn [action eql-query]
          (if query-fn
            (query-fn eql-query)
            (runtime-not-initialized action)))
        runtime-mutate
        (fn [op-sym params]
          (mutate-required :mutate
                           op-sym
                           (if (extension-op? op-sym)
                             (with-ext-path ext-path params)
                             params)))
        runtime-log
        (fn [text]
          (if log-fn
            (log-fn text)
            (binding [*out* *err*]
              (println text))))
        on!
        (fn [event-name handler-fn]
          (mutate-local 'psi.extension/register-handler
                        {:event-name event-name
                         :handler-fn handler-fn}
                        #(register-handler-in! reg ext-path event-name handler-fn)))
        register-tool!
        (fn [tool]
          (mutate-local 'psi.extension/register-tool
                        {:tool tool}
                        #(register-tool-in! reg ext-path tool)))
        register-command!
        (fn [name opts]
          (mutate-local 'psi.extension/register-command
                        {:name name
                         :opts opts}
                        #(register-command-in! reg ext-path (assoc opts :name name))))
        register-shortcut!
        (fn [key opts]
          (mutate-local 'psi.extension/register-shortcut
                        {:key  key
                         :opts opts}
                        #(register-shortcut-in! reg ext-path (assoc opts :key key))))
        register-flag!
        (fn [name opts]
          (mutate-local 'psi.extension/register-flag
                        {:name name
                         :opts opts}
                        #(register-flag-in! reg ext-path (assoc opts :name name))))
        set-allowed-events!
        (fn [allowed-events]
          (mutate-local 'psi.extension/set-allowed-events
                        {:allowed-events (vec allowed-events)}
                        #(set-allowed-events-in! reg ext-path allowed-events)))
        get-flag
        (fn [name]
          (get-flag-in reg name))
        register-post-tool-processor!
        (fn [{:keys [name match timeout-ms handler]}]
          (mutate-ext-required :register-post-tool-processor
                               'psi.extension/register-post-tool-processor
                               {:name       name
                                :match      match
                                :timeout-ms timeout-ms
                                :handler    handler}))
        ensure-service!
        (fn [{:keys [key type spec]}]
          (mutate-ext-required :ensure-service
                               'psi.extension/ensure-service
                               {:key  key
                                :type type
                                :spec spec}))
        stop-service!
        (fn [key]
          (mutate-ext-required :stop-service
                               'psi.extension/stop-service
                               {:key key}))
        service-request!
        (fn [opts]
          (mutate-ext-required :service-request
                               'psi.extension/service-request
                               (service-request-params opts)))
        service-notify!
        (fn [opts]
          (mutate-ext-required :service-notify
                               'psi.extension/service-notify
                               (service-notify-params opts)))
        list-services
        (fn []
          (:psi.service/services
           (runtime-query :list-services services-query)))
        get-service
        (fn [service-key]
          (if service-fn
            (service-fn service-key)
            (some #(when (= service-key (:psi.service/key %)) %)
                  (list-services))))
        register-prompt-contribution!
        (letfn [(register-prompt-contribution*
                  ([id contribution]
                   (mutate-ext-required :register-prompt-contribution
                                        'psi.extension/register-prompt-contribution
                                        {:id           id
                                         :contribution contribution}))
                  ([contribution]
                   (let [id* (or (:id contribution)
                                 (:psi.extension.prompt-contribution/id contribution))]
                     (when-not id*
                       (throw (ex-info "register-prompt-contribution requires :id"
                                       {:contribution contribution})))
                     (register-prompt-contribution* id* (dissoc contribution :id :psi.extension.prompt-contribution/id)))))]
          register-prompt-contribution*)
        update-prompt-contribution!
        (fn [id patch]
          (mutate-ext-required :update-prompt-contribution
                               'psi.extension/update-prompt-contribution
                               {:id    id
                                :patch patch}))
        unregister-prompt-contribution!
        (fn [id]
          (mutate-ext-required :unregister-prompt-contribution
                               'psi.extension/unregister-prompt-contribution
                               {:id id}))
        list-prompt-contributions
        (fn []
          (list-extension-prompt-contributions query-fn ext-path))
        query
        (fn [eql-query]
          (runtime-query :query eql-query))
        query-session
        (fn [session-id eql-query]
          (if query-fn
            (query-fn {:session-id session-id
                       :query eql-query})
            (runtime-not-initialized :query-session)))
        mutate-session
        (fn [session-id op-sym params]
          (if mutate-fn
            (mutate-fn op-sym (assoc (or params {}) :session-id session-id))
            (runtime-not-initialized :mutate-session)))
        create-session!
        (fn [opts]
          (mutate-required :create-session
                           'psi.extension/create-session
                           (create-session-params opts)))
        switch-session!
        (fn [session-id]
          (mutate-required :switch-session
                           'psi.extension/switch-session
                           {:session-id session-id}))
        set-worktree-path!
        (fn [session-id worktree-path]
          (mutate-required :set-worktree-path
                           'psi.extension/set-worktree-path
                           {:session-id session-id
                            :worktree-path worktree-path}))
        notify!
        (fn [content & [{:keys [role custom-type] :as opts}]]
          (let [params (cond-> {:content content}
                         (contains? opts :role) (assoc :role role)
                         (contains? opts :custom-type) (assoc :custom-type custom-type))]
            (mutate-required :notify
                             'psi.extension/notify
                             params)))
        append-message!
        (fn [role content]
          (mutate-required :append-message
                           'psi.extension/append-message
                           {:role role
                            :content content}))
        get-api-key
        (fn [provider]
          (if get-api-key-fn
            (get-api-key-fn provider)
            (runtime-not-initialized :get-api-key)))
        events
        {:emit (fn [channel data] (bus-emit-in! reg channel data))
         :on   (fn [channel handler] (bus-on-in! reg channel handler))}
        ui-type
        (when ui-type-fn
          (ui-type-fn))
        ui
        (when ui-context-fn
          (ui-context-fn ext-path))]
    {:path                           ext-path
     :on                             on!
     :register-tool                  register-tool!
     :register-command               register-command!
     :register-shortcut              register-shortcut!
     :register-flag                  register-flag!
     :set-allowed-events             set-allowed-events!
     :get-flag                       get-flag
     :register-post-tool-processor   register-post-tool-processor!
     :ensure-service                 ensure-service!
     :stop-service                   stop-service!
     :service-request                service-request!
     :service-notify                 service-notify!
     :list-services                  list-services
     :get-service                    get-service
     :register-prompt-contribution   register-prompt-contribution!
     :update-prompt-contribution     update-prompt-contribution!
     :unregister-prompt-contribution unregister-prompt-contribution!
     :list-prompt-contributions      list-prompt-contributions
     :query                          query
     :query-session                  query-session
     :mutate                         runtime-mutate
     :mutate-session                 mutate-session
     :create-session                 create-session!
     :switch-session                 switch-session!
     :set-worktree-path              set-worktree-path!
     :notify                         notify!
     :append-message                 append-message!
     :get-api-key                    get-api-key
     :events                         events
     :ui-type                        ui-type
     :ui                             ui
     :log                            runtime-log}))

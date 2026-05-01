(ns psi.agent-session.psi-tool
  "psi-tool runtime contract, validation, query/eval/reload execution, and output shaping."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [psi.agent-core.core :as agent]
   [psi.agent-session.extension-runtime :as extension-runtime]
   [psi.agent-session.extensions :as extensions]
   [psi.agent-session.project-nrepl-ops :as project-nrepl-ops]
   [psi.agent-session.psi-tool-scheduler :as psi-tool-scheduler]
   [psi.agent-session.psi-tool-workflow :as psi-tool-workflow]
   [psi.agent-session.session-state :as session-state]
   [psi.agent-session.tool-output :as tool-output]
   [psi.query.core :as query]))

(def psi-tool
  {:name        "psi-tool"
   :label       "Psi Tool"
   :description (str "Execute a live psi runtime operation. Canonical requests use `action` with one of: "
                     "`query`, `eval`, `reload-code`, `project-repl`, `workflow`, or `scheduler`. `query` executes an EQL query against the live session graph; "
                     "`eval` evaluates an in-process Clojure form in a named already-loaded namespace; "
                     "`reload-code` reloads already loaded namespaces by explicit namespace list or worktree scope; "
                     "`project-repl` controls the managed project REPL with explicit `op` values `status|start|attach|stop|eval|interrupt`; "
                     "`workflow` manages deterministic workflow definitions and runs with explicit `op` values `list-definitions|create-run|execute-run|read-run|list-runs|resume-run|cancel-run`; "
                     "`scheduler` manages one-shot delayed work for the invoking session with explicit `op` values `create|list|cancel`, including delayed same-session prompts and delayed fresh top-level session creation. "
                     "Legacy query-only calls of the form `{query: ...}` remain accepted only as a compatibility alias for `action: \"query\"`. "
                     "Optional `entity` seeds root attributes for explicit query targeting, e.g. entity {:psi.agent-session/session-id \"sid\"}.")
   :parameters  {:type       "object"
                 :properties {:action        {:type "string" :enum ["query" "eval" "reload-code" "project-repl" "workflow" "scheduler"]
                                              :description "Canonical psi-tool operation discriminator."}
                              :query         {:type "string" :description "For `action: \"query\"`: EQL query vector as EDN string, e.g. \"[:psi.agent-session/phase :psi.agent-session/session-id]\""}
                              :entity        {:type "string" :description "For `action: \"query\"`: optional EDN root entity map to seed the query, e.g. \"{:psi.agent-session/session-id \\\"sid\\\"}\" for explicit session targeting."}
                              :ns            {:type "string" :description "For `action: \"eval\"`: already loaded namespace string in which to evaluate `form`."}
                              :form          {:type "string" :description "For `action: \"eval\"`: Clojure form string using full Clojure reader syntax (quote, deref, anon-fn, var) read with *read-eval* false and evaluated in the named namespace."}
                              :namespaces    {:type "array" :items {:type "string"}
                                              :description "For `action: \"reload-code\"` namespace mode: ordered vector of already loaded namespace names to reload."}
                              :worktree-path {:type "string" :description "For `action: \"reload-code\"` worktree mode, and `action: \"project-repl\"`: explicit absolute target worktree path. When absent, invoking session worktree may be used."}
                              :op            {:type "string" :enum ["status" "start" "attach" "stop" "eval" "interrupt"]
                                              :description "For `action: \"project-repl\"`: managed project REPL operation discriminator."}
                              :host          {:type "string" :description "For `action: \"project-repl\"`, `op: \"attach\"`: explicit attach host override."}
                              :port          {:type "integer" :description "For `action: \"project-repl\"`, `op: \"attach\"`: explicit attach port override."}
                              :code          {:type "string" :description "For `action: \"project-repl\"`, `op: \"eval\"`: Clojure code to evaluate in the managed project REPL."}
                              :definition-id {:type "string" :description "For `action: \"workflow\"`: registered workflow definition id."}
                              :definition    {:type "string" :description "For `action: \"workflow\"`: inline workflow definition as EDN."}
                              :workflow-input {:type "string" :description "For `action: \"workflow\"`: workflow input map as EDN."}
                              :run-id        {:type "string" :description "For `action: \"workflow\"`: workflow run id."}
                              :chain-name    {:type "string" :description "For `action: \"workflow\"`: legacy parameter, no longer used."}
                              :reason        {:type "string" :description "For `action: \"workflow\"`: optional cancel reason."}
                              :message       {:type "string" :description "For `action: \"scheduler\"`, `op: \"create\"`: prompt content to inject when the schedule fires."}
                              :kind          {:type "string" :enum ["message" "session"] :description "For `action: \"scheduler\"`, `op: \"create\"`: explicit scheduler kind (`message` or `session`)."}
                              :session-config {:description "For `action: \"scheduler\"`, `op: \"create\"`, `kind: \"session\"`: session config map or EDN string using the supported scheduler session-config subset."}
                              :label         {:type "string" :description "For `action: \"scheduler\"`: optional human-readable schedule label."}
                              :delay-ms      {:type "integer" :description "For `action: \"scheduler\"`, `op: \"create\"`: relative delay in milliseconds (1000ms to 24h)."}
                              :at            {:type "string" :description "For `action: \"scheduler\"`, `op: \"create\"`: absolute ISO-8601 UTC instant. Past instants fire immediately."}
                              :schedule-id   {:type "string" :description "For `action: \"scheduler\"`, `op: \"cancel\"`: schedule id to cancel."}}
                 :required   []}})

(defn- sanitize-psi-tool-result [result]
  (walk/postwalk
   (fn [x]
     (cond
       (map? x) (dissoc x :psi/agent-session-ctx :psi/memory-ctx :psi/recursion-ctx :psi/engine-ctx)
       (instance? Throwable x) (str x)
       (instance? java.time.temporal.TemporalAccessor x) (str x)
       :else x))
   result))

(defn- parse-edn-string [s]
  (binding [*read-eval* false]
    (edn/read-string s)))

(defn- read-clojure-form
  "Read a Clojure form string with full reader syntax (quote, deref, anon-fn, var)
   but with *read-eval* disabled to prevent #=() read-time eval."
  [s]
  (binding [*read-eval* false]
    (read-string s)))

(defn- psi-tool-action [{:strs [action query]}]
  (cond
    (some? action) action
    (some? query)  "query"
    :else          nil))

(def ^:private psi-tool-supported-actions ["query" "eval" "reload-code" "project-repl" "workflow" "scheduler"])
(def ^:private project-repl-supported-ops ["status" "start" "attach" "stop" "eval" "interrupt"])
(def ^:private workflow-supported-ops ["list-definitions" "create-run" "execute-run" "read-run" "list-runs" "resume-run" "cancel-run"])
(def ^:private scheduler-supported-ops ["create" "list" "cancel"])

(defn- validate-psi-tool-request
  [{:strs [query entity ns form namespaces worktree-path op host port code definition-id definition workflow-input run-id chain-name reason message kind session-config label delay-ms at schedule-id] :as args}]
  (let [effective-action (psi-tool-action args)]
    (cond
      (nil? effective-action)
      (throw (ex-info "psi-tool action is required unless using legacy query-only compatibility input"
                      {:phase :validate :supported-actions psi-tool-supported-actions}))

      (not (some #{effective-action} psi-tool-supported-actions))
      (throw (ex-info (str "Unknown psi-tool action: " effective-action)
                      {:phase :validate :action effective-action :supported-actions psi-tool-supported-actions}))

      (= effective-action "query")
      (do
        (when-not (string? query)
          (throw (ex-info "psi-tool query action requires `query`"
                          {:phase :validate :action effective-action})))
        {:action effective-action :query query :entity entity})

      (= effective-action "eval")
      (do
        (when-not (string? ns)
          (throw (ex-info "psi-tool eval action requires `ns`"
                          {:phase :validate :action effective-action})))
        (when-not (string? form)
          (throw (ex-info "psi-tool eval action requires `form`"
                          {:phase :validate :action effective-action})))
        {:action effective-action :ns ns :form form})

      (= effective-action "reload-code")
      (do
        (when (and (some? namespaces) (some? worktree-path))
          (throw (ex-info "psi-tool reload-code accepts exactly one targeting mode"
                          {:phase :validate :action effective-action})))
        {:action effective-action :namespaces namespaces :worktree-path worktree-path})

      (= effective-action "project-repl")
      (do
        (when-not (some #{op} project-repl-supported-ops)
          (throw (ex-info "psi-tool project-repl action requires valid `op`"
                          {:phase :validate :action effective-action :op op :supported-ops project-repl-supported-ops})))
        (when (and (= op "eval") (not (string? code)))
          (throw (ex-info "psi-tool project-repl eval requires `code`"
                          {:phase :validate :action effective-action :op op})))
        {:action effective-action :op op :worktree-path worktree-path :host host :port port :code code})

      (= effective-action "workflow")
      (do
        (when-not (some #{op} workflow-supported-ops)
          (throw (ex-info "psi-tool workflow action requires valid `op`"
                          {:phase :validate :action effective-action :op op :supported-ops workflow-supported-ops})))
        (when (and (= op "create-run") definition-id definition)
          (throw (ex-info "psi-tool workflow create-run accepts either `definition-id` or `definition`, not both"
                          {:phase :validate :action effective-action :op op})))
        (when (and (= op "create-run") (nil? definition-id) (nil? definition))
          (throw (ex-info "psi-tool workflow create-run requires `definition-id` or `definition`"
                          {:phase :validate :action effective-action :op op})))
        (when (and ((set ["execute-run" "read-run" "resume-run" "cancel-run"]) op)
                   (not (string? run-id)))
          (throw (ex-info "psi-tool workflow op requires `run-id`"
                          {:phase :validate :action effective-action :op op})))
        {:action effective-action :op op :definition-id definition-id :definition definition :workflow-input workflow-input :run-id run-id :chain-name chain-name :reason reason})

      (= effective-action "scheduler")
      (do
        (when-not (some #{op} scheduler-supported-ops)
          (throw (ex-info "psi-tool scheduler action requires valid `op`"
                          {:phase :validate :action effective-action :op op :supported-ops scheduler-supported-ops})))
        {:action effective-action :op op :message message :kind kind :session-config session-config :label label :delay-ms delay-ms :at at :schedule-id schedule-id}))))

(defn- sanitize-psi-tool-data [x] (sanitize-psi-tool-result x))
(defn- ordered-map [& kvs] (apply array-map kvs))

(defn- psi-tool-error-summary
  ([e] (psi-tool-error-summary nil e))
  ([default-phase e]
   {:message (or (ex-message e) (str e))
    :class   (.getName (class e))
    :phase   (or (:phase (ex-data e)) default-phase :execute)
    :data    (some-> (ex-data e) sanitize-psi-tool-data)}))

(defn- format-psi-tool-error [prefix e]
  {:content  (str prefix (or (ex-message e) (str e)))
   :is-error true})

(defn telemetry-args
  [{:strs [action query ns form namespaces worktree-path op host port code definition-id definition workflow-input run-id chain-name reason message kind session-config label delay-ms at schedule-id]}]
  (cond-> (ordered-map)
    action (assoc "action" action)
    query (assoc "query" query)
    ns (assoc "ns" ns)
    form (assoc "form" form)
    namespaces (assoc "namespaces" namespaces)
    worktree-path (assoc "worktree-path" worktree-path)
    op (assoc "op" op)
    host (assoc "host" host)
    port (assoc "port" port)
    code (assoc "code" code)
    definition-id (assoc "definition-id" definition-id)
    definition (assoc "definition" definition)
    workflow-input (assoc "workflow-input" workflow-input)
    run-id (assoc "run-id" run-id)
    chain-name (assoc "chain-name" chain-name)
    reason (assoc "reason" reason)
    message (assoc "message" message)
    kind (assoc "kind" kind)
    session-config (assoc "session-config" session-config)
    label (assoc "label" label)
    delay-ms (assoc "delay-ms" delay-ms)
    at (assoc "at" at)
    schedule-id (assoc "schedule-id" schedule-id)))

(defn- execute-psi-tool-query [query-fn {:keys [query entity]}]
  (let [q          (parse-edn-string query)
        entity-map (when (some? entity) (parse-edn-string entity))]
    (when-not (vector? q)
      (throw (ex-info "Query must be an EDN vector" {:input query})))
    (when (some? entity-map)
      (when-not (map? entity-map)
        (throw (ex-info "Entity must be an EDN map" {:input entity-map}))))
    (if (some? entity-map)
      (try (query-fn q entity-map)
           (catch clojure.lang.ArityException _
             (throw (ex-info "psi-tool query-fn does not support explicit entity seeding" {:entity entity-map}))))
      (query-fn q))))

(defn- serialize-psi-tool-output [{:keys [overrides tool-call-id]} output narrowing-hint]
  (let [policy     (tool-output/effective-policy (or overrides {}) "psi-tool")
        truncation (tool-output/head-truncate output policy)
        truncated? (:truncated truncation)
        spill-path (when truncated?
                     (tool-output/persist-truncated-output! "psi-tool" (or tool-call-id (str (java.util.UUID/randomUUID))) output))]
    (if truncated?
      {:content  (str "Output truncated (" (:total-lines truncation) " lines / " (:total-bytes truncation) " bytes). Full output: " spill-path ". " narrowing-hint "\n\n" (:content truncation))
       :is-error false
       :details  {:truncation truncation :full-output-path spill-path}}
      {:content (:content truncation) :is-error false :details nil})))

(defn- eval-namespace [ns-name]
  (or (some-> ns-name symbol find-ns)
      (throw (ex-info (str "Eval namespace is not loaded: " ns-name) {:phase :validate :ns ns-name}))))

(defn- canonical-file [path]
  (when (seq (str path))
    (try (.getCanonicalFile (io/file path))
         (catch Exception _ nil))))

(defn- canonical-path [path] (some-> path canonical-file .getAbsolutePath))
(defn- canonical-source-path-for-ns [ns-obj] (or (some-> ns-obj meta :file canonical-path) (some-> ns-obj meta :file io/resource .getFile canonical-path)))
(defn- loaded-namespace? [ns-name] (boolean (some-> ns-name symbol find-ns)))

(defn- validate-reload-namespaces [namespaces]
  (when-not (vector? namespaces)
    (throw (ex-info "psi-tool reload-code namespace mode requires `namespaces` vector" {:phase :validate :action "reload-code"})))
  (when (empty? namespaces)
    (throw (ex-info "psi-tool reload-code namespace mode requires a non-empty `namespaces` vector" {:phase :validate :action "reload-code"})))
  (doseq [ns-name namespaces]
    (when-not (and (string? ns-name) (not (str/blank? ns-name)))
      (throw (ex-info "psi-tool reload-code namespace mode requires non-blank namespace strings" {:phase :validate :action "reload-code" :namespaces namespaces}))))
  (when-not (= (count namespaces) (count (distinct namespaces)))
    (throw (ex-info "psi-tool reload-code namespace mode rejects duplicate namespace names" {:phase :validate :action "reload-code" :namespaces namespaces})))
  (doseq [ns-name namespaces]
    (when-not (loaded-namespace? ns-name)
      (throw (ex-info (str "Reload namespace is not loaded: " ns-name) {:phase :validate :action "reload-code" :namespace ns-name}))))
  namespaces)

(defn- absolute-directory-path! [worktree-path]
  (when-not (and (string? worktree-path) (not (str/blank? worktree-path)))
    (throw (ex-info "psi-tool reload-code worktree mode requires a non-blank absolute `worktree-path`" {:phase :validate :action "reload-code"})))
  (let [f (io/file worktree-path)]
    (when-not (.isAbsolute f)
      (throw (ex-info "psi-tool reload-code explicit worktree-path must be absolute" {:phase :validate :action "reload-code" :worktree-path worktree-path})))
    (when-not (.isDirectory f)
      (throw (ex-info "psi-tool reload-code explicit worktree-path must resolve to an existing directory" {:phase :validate :action "reload-code" :worktree-path worktree-path})))
    (.getAbsolutePath (.getCanonicalFile f))))

(defn- path-under-root? [root-path candidate-path]
  (let [root (canonical-file root-path)
        candidate (canonical-file candidate-path)]
    (when (and root candidate)
      (let [root-path* (.toPath root)
            child-path (.toPath candidate)]
        (.startsWith child-path root-path*)))))

(defn worktree-reload-candidates [worktree-path]
  (->> (all-ns)
       (map (fn [ns-obj] {:ns-name (str (ns-name ns-obj)) :source-path (canonical-source-path-for-ns ns-obj)}))
       (filter :source-path)
       (filter #(path-under-root? worktree-path (:source-path %)))
       (sort-by :ns-name)
       vec))

(defn- refresh-query-runtime! [ctx]
  (if-not ctx
    {:status :ok :summary "resolver and mutation registrations unchanged (no runtime ctx provided)"}
    (let [register-resolvers! (:register-resolvers-fn ctx)
          register-mutations! (:register-mutations-fn ctx)
          qctx (query/create-query-context)]
      (register-resolvers! qctx true)
      (register-mutations! qctx (:all-mutations ctx) true)
      {:status :ok :summary "resolver and mutation registrations refreshed"})))

(defn- refresh-live-tool-defs! [ctx session-id]
  (if-not (and ctx session-id)
    {:status :ok :summary "live tool definitions unchanged (no session runtime provided)"}
    (let [agent-ctx (session-state/agent-ctx-in ctx session-id)
          builtins  (or (:tool-defs (session-state/get-session-data-in ctx session-id)) [])
          ext-tools (extensions/all-tools-in (:extension-registry ctx))
          tool-defs (vec (concat builtins ext-tools))]
      (when agent-ctx
        (agent/set-tools-in! agent-ctx tool-defs))
      {:status :ok :summary (str "refreshed live tool defs (" (count tool-defs) " tools)")})))

(defn- install-summary [install-state]
  (let [entries (vals (get-in install-state [:psi.extensions/effective :entries-by-lib]))
        statuses (map :status entries)
        status-counts (frequencies statuses)
        restart-required (->> (get-in install-state [:psi.extensions/effective :entries-by-lib])
                              (keep (fn [[lib entry]] (when (= :restart-required (:status entry)) lib)))
                              vec)
        last-apply (:psi.extensions/last-apply install-state)
        diagnostics (vec (or (:psi.extensions/diagnostics install-state) []))]
    {:status (:status last-apply)
     :restart-required? (boolean (:restart-required? last-apply))
     :summary (:summary last-apply)
     :status-counts status-counts
     :restart-required-libs restart-required
     :diagnostic-count (count diagnostics)
     :diagnostics diagnostics}))

(defn- refresh-worktree-extensions! [ctx session-id worktree-path]
  (if-not (and ctx session-id)
    {:status :ok :summary "extension rediscovery skipped (no session runtime provided)" :loaded [] :errors [] :install nil}
    (let [result (extension-runtime/reload-extensions-in! ctx session-id [] worktree-path)
          install-state (:install-state result)
          install-report (some-> install-state install-summary)
          has-load-errors (seq (:errors result))
          install-error? (and install-report (nil? (:status install-report)))]
      {:status (if (or has-load-errors install-error?) :error :ok)
       :summary (str "reloaded extensions under worktree (loaded=" (count (:loaded result)) ", errors=" (count (:errors result))
                     (when install-report (str ", install-status=" (or (:status install-report) :invalid))) ")")
       :loaded (:loaded result)
       :errors (mapv sanitize-psi-tool-data (:errors result))
       :install (some-> install-report sanitize-psi-tool-data)})))

(defn- preserve-extension-registry-step [] {:status :ok :summary "preserved current extension registry without rediscovery"})
(defn- reload-namespace! [ns-name] (require (symbol ns-name) :reload))

(defn- execute-psi-tool-reload-report [{:keys [ctx session-id cwd]} {:keys [namespaces worktree-path]}]
  (let [started-at (System/nanoTime)
        namespace-mode? (some? namespaces)
        reload-mode (if namespace-mode? :namespaces :worktree)
        requested-nses (when namespace-mode? (validate-reload-namespaces namespaces))
        effective-path (when-not namespace-mode?
                         (cond
                           (some? worktree-path) (absolute-directory-path! worktree-path)
                           (some? cwd) (absolute-directory-path! cwd)
                           (and ctx session-id) (absolute-directory-path! (session-state/session-worktree-path-in ctx session-id))
                           :else (throw (ex-info "psi-tool reload-code worktree mode requires explicit worktree-path or invoking session worktree-path" {:phase :validate :action "reload-code"}))))
        worktree-source (when-not namespace-mode? (if (some? worktree-path) :explicit :session))
        candidates (if namespace-mode?
                     (mapv (fn [ns-name] {:ns-name ns-name}) requested-nses)
                     (let [matches (worktree-reload-candidates effective-path)]
                       (when (empty? matches)
                         (throw (ex-info "psi-tool reload-code worktree target is not reloadable in the current runtime" {:phase :validate :action "reload-code" :worktree-path effective-path})))
                       matches))
        reload-result (loop [remaining candidates reloaded []]
                        (if-let [{:keys [ns-name]} (first remaining)]
                          (let [step-result (try (reload-namespace! ns-name) {:ok? true}
                                                 (catch Exception e {:ok? false :error e}))]
                            (if (:ok? step-result)
                              (recur (rest remaining) (conj reloaded ns-name))
                              {:status :error :namespace-count (count reloaded) :namespaces reloaded
                               :summary (str "reload stopped after failure in " ns-name)
                               :error (assoc (psi-tool-error-summary :reload-code (:error step-result)) :namespace ns-name)}))
                          {:status :ok :namespace-count (count reloaded) :namespaces reloaded :summary (str "reloaded " (count reloaded) " namespaces")}))
        refresh-steps [(assoc (refresh-query-runtime! ctx) :step :resolver-registration-refresh)
                       {:step :mutation-registration-refresh :status :ok :summary "mutation registrations refreshed with query runtime"}
                       (assoc (refresh-live-tool-defs! ctx session-id) :step :live-tool-definition-refresh)
                       (assoc (if namespace-mode? (preserve-extension-registry-step) (refresh-worktree-extensions! ctx session-id effective-path)) :step :extension-refresh)]
        refresh-error (some #(when (= :error (:status %)) %) refresh-steps)
        graph-refresh {:status (if refresh-error :error :ok)
                       :summary (if refresh-error "graph/runtime refresh completed with errors" "graph/runtime refresh completed")
                       :steps refresh-steps
                       :error (when refresh-error {:message (:summary refresh-error) :class "clojure.lang.ExceptionInfo" :phase (:step refresh-error) :data (dissoc refresh-error :step :summary :status)})}
        duration-ms (long (/ (- (System/nanoTime) started-at) 1000000))]
    (cond-> {:psi-tool/action :reload-code
             :psi-tool/reload-mode reload-mode
             :psi-tool/code-reload reload-result
             :psi-tool/graph-refresh graph-refresh
             :psi-tool/duration-ms duration-ms
             :psi-tool/overall-status (if (and (= :ok (:status reload-result)) (= :ok (:status graph-refresh))) :ok :error)}
      namespace-mode? (assoc :psi-tool/namespaces-requested requested-nses)
      (not namespace-mode?) (assoc :psi-tool/worktree-path effective-path :psi-tool/worktree-source worktree-source))))

(defn truncation-visible-prefix [{:keys [action ns form namespaces worktree-path op code definition-id run-id schedule-id label kind]}]
  (case action
    "eval" (str "Eval action=eval ns=" ns " form=" form)
    "reload-code" (if namespaces
                    (str "Reload action=reload-code mode=namespaces namespaces=" (pr-str namespaces))
                    (str "Reload action=reload-code mode=worktree worktree-path=" worktree-path))
    "project-repl" (str "Project REPL action=project-repl op=" op
                        (when worktree-path (str " worktree-path=" worktree-path))
                        (when (= op "eval") (str " code=" code)))
    "workflow" (str "Workflow action=workflow op=" op
                    (when definition-id (str " definition-id=" definition-id))
                    (when run-id (str " run-id=" run-id)))
    "scheduler" (str "Scheduler action=scheduler op=" op
                     (when kind (str " kind=" kind))
                     (when schedule-id (str " schedule-id=" schedule-id))
                     (when label (str " label=" label)))
    nil))

(defn- execute-psi-tool-eval-report [{:keys [ns form]}]
  (let [started-at (System/nanoTime)]
    (try
      (let [target-ns (eval-namespace ns)
            form-value (read-clojure-form form)
            value (binding [*ns* target-ns] (eval form-value))
            safe-value (sanitize-psi-tool-data value)
            duration-ms (long (/ (- (System/nanoTime) started-at) 1000000))]
        {:psi-tool/action :eval :psi-tool/ns ns :psi-tool/value (pr-str safe-value) :psi-tool/value-type (some-> value class .getName) :psi-tool/duration-ms duration-ms})
      (catch Exception e
        {:psi-tool/action :eval :psi-tool/ns ns :psi-tool/duration-ms (long (/ (- (System/nanoTime) started-at) 1000000)) :psi-tool/error (psi-tool-error-summary :eval e)}))))

(defn- execute-psi-tool-project-repl-report [{:keys [ctx session-id]} {:keys [op] :as request}]
  (let [started-at (System/nanoTime)]
    (try
      (let [{:keys [worktree-path project-repl error]} (project-nrepl-ops/perform! ctx session-id request)
            duration-ms (long (/ (- (System/nanoTime) started-at) 1000000))]
        (cond-> {:psi-tool/action :project-repl :psi-tool/project-repl-op (keyword op) :psi-tool/worktree-path worktree-path :psi-tool/duration-ms duration-ms :psi-tool/overall-status (if error :error :ok)}
          project-repl (assoc :psi-tool/project-repl (sanitize-psi-tool-data project-repl))
          error (assoc :psi-tool/error (sanitize-psi-tool-data error))))
      (catch Exception e
        {:psi-tool/action :project-repl :psi-tool/project-repl-op (keyword op) :psi-tool/duration-ms (long (/ (- (System/nanoTime) started-at) 1000000)) :psi-tool/overall-status :error :psi-tool/error (psi-tool-error-summary :project-repl e)}))))

(defn- execute-psi-tool-workflow-report [runtime-opts request]
  (psi-tool-workflow/execute-psi-tool-workflow-report runtime-opts request))

(defn- execute-psi-tool-scheduler-report [runtime-opts request]
  (psi-tool-scheduler/execute-psi-tool-scheduler-report runtime-opts request))

(defn serialize-operation-output [opts request output narrowing-hint]
  (let [policy (tool-output/effective-policy (or (:overrides opts) {}) "psi-tool")
        truncation (tool-output/head-truncate output policy)]
    (if (and (:truncated truncation) (truncation-visible-prefix request))
      (serialize-psi-tool-output opts (str (truncation-visible-prefix request) "\n" output) narrowing-hint)
      (serialize-psi-tool-output opts output narrowing-hint))))

(defn make-psi-tool
  ([query-fn] (make-psi-tool query-fn nil))
  ([query-fn opts]
   (assoc psi-tool :execute
          (fn [args]
            (try
              (let [{:keys [action] :as request} (validate-psi-tool-request args)]
                (case action
                  "query" (let [result (execute-psi-tool-query query-fn request)
                                safe-result (sanitize-psi-tool-result result)
                                output (pr-str safe-result)]
                            (serialize-psi-tool-output opts output "Use a narrower query to reduce output size."))
                  "eval" (let [report (execute-psi-tool-eval-report request)
                               output (pr-str report)]
                           (assoc (serialize-operation-output opts request output "Use a smaller eval result to reduce output size.") :is-error (boolean (:psi-tool/error report))))
                  "reload-code" (let [report (execute-psi-tool-reload-report {:ctx (:ctx opts) :session-id (:session-id opts) :cwd (:cwd opts)} request)
                                      output (pr-str report)]
                                  (assoc (serialize-operation-output opts request output "Use a narrower reload scope to reduce output size.") :is-error (not= :ok (:psi-tool/overall-status report))))
                  "project-repl" (let [report (execute-psi-tool-project-repl-report {:ctx (:ctx opts) :session-id (:session-id opts)} request)
                                       output (pr-str report)]
                                   (assoc (serialize-operation-output opts request output "Use a narrower project REPL result to reduce output size.") :is-error (not= :ok (:psi-tool/overall-status report))))
                  "workflow" (let [report (execute-psi-tool-workflow-report {:ctx (:ctx opts) :session-id (:session-id opts)} request)
                                   safe-report (sanitize-psi-tool-data report)
                                   output (pr-str safe-report)]
                               (assoc (serialize-operation-output opts request output "Use a narrower workflow result to reduce output size.") :is-error (not= :ok (:psi-tool/overall-status safe-report))))
                  "scheduler" (let [report (execute-psi-tool-scheduler-report {:ctx (:ctx opts) :session-id (:session-id opts)} request)
                                    safe-report (sanitize-psi-tool-data report)
                                    output (pr-str safe-report)]
                                (assoc (serialize-operation-output opts request output "Use a narrower scheduler result to reduce output size.") :is-error (not= :ok (:psi-tool/overall-status safe-report))))))
              (catch StackOverflowError _
                {:content "EQL query error: result contains recursive data that overflowed printer stack" :is-error true})
              (catch Exception e
                (let [action (psi-tool-action args)]
                  (case action
                    "eval" {:content (pr-str {:psi-tool/action :eval :psi-tool/ns (get args "ns") :psi-tool/duration-ms 0 :psi-tool/error (psi-tool-error-summary :eval e)}) :is-error true}
                    "reload-code" {:content (pr-str {:psi-tool/action :reload-code :psi-tool/duration-ms 0 :psi-tool/overall-status :error :psi-tool/error (psi-tool-error-summary :reload-code e)}) :is-error true}
                    "project-repl" {:content (pr-str {:psi-tool/action :project-repl :psi-tool/project-repl-op (some-> (get args "op") keyword) :psi-tool/duration-ms 0 :psi-tool/overall-status :error :psi-tool/error (psi-tool-error-summary :project-repl e)}) :is-error true}
                    "workflow" {:content (pr-str {:psi-tool/action :workflow :psi-tool/workflow-op (some-> (get args "op") keyword) :psi-tool/duration-ms 0 :psi-tool/overall-status :error :psi-tool/error (psi-tool-error-summary :workflow e)}) :is-error true}
                    "scheduler" {:content (pr-str {:psi-tool/action :scheduler :psi-tool/scheduler-op (some-> (get args "op") keyword) :psi-tool/duration-ms 0 :psi-tool/overall-status :error :psi-tool/error (psi-tool-error-summary :scheduler e)}) :is-error true}
                    (format-psi-tool-error "EQL query error: " e)))))))))

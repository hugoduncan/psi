(ns psi.agent-session.psi-tool
  "psi-tool runtime contract, validation, query/eval/reload execution, and output shaping."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [psi.agent-core.core :as agent]
   [psi.ai.model-registry :as model-registry]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.extension-runtime :as extension-runtime]
   [psi.agent-session.extensions.runtime-eql :as runtime-eql]
   [psi.agent-session.resolvers :as session-resolvers]
   [psi.agent-session.workflow.runtime-state :as workflow-runtime-state]
   [psi.history.resolvers :as history-resolvers]
   [psi.state-kernel.dispatch :as kernel-dispatch]
   [psi.tool-registry.defs :as tool-defs]
   [psi.tool-registry.registry :as tool-registry]
   [psi.project-nrepl.ops :as project-nrepl-ops]
   [psi.agent-session.psi-tool-operation :as psi-tool-operation]
   [psi.agent-session.psi-tool-validate :as psi-tool-validate]
   [psi.agent-session.psi-tool-scheduler :as psi-tool-scheduler]
   [psi.agent-session.psi-tool-workflow :as psi-tool-workflow]
   [psi.session-state.state :as session-state]
   [psi.agent-session.tool-output :as tool-output]
   [psi.tool-runtime.call-summary :as call-summary]))

(def psi-tool
  {:name               "psi-tool"
   :label              "Psi Tool"
   :description        (str "Execute a live psi runtime operation. Canonical requests use `action` with one of: "
                            "`query`, `eval`, `mutate`, `reload-code`, `project-repl`, `workflow`, or `scheduler`. `query` executes an EQL query against the live session graph; "
                            "`eval` evaluates an in-process Clojure form in a named already-loaded namespace; "
                            "`reload-code` reloads already loaded namespaces by explicit namespace list or worktree scope; omit `worktree-path` to use the invoking session worktree when available; "
                            "`project-repl` controls the managed project REPL with explicit `op` values `status|start|attach|stop|eval|interrupt`; "
                            "`workflow` manages deterministic workflow definitions and runs with explicit `op` values `list-definitions|create-run|execute-run|read-run|list-runs|resume-run|cancel-run`; "
                            "`scheduler` manages one-shot delayed work for the invoking session with explicit `op` values `create|list|cancel`, including delayed same-session prompts and delayed fresh top-level session creation. "
                            "`operation` lists and invokes registered deterministic operations with explicit `op` values `list|invoke`; `list` returns each operation's id and description, `invoke` runs `operation-id` with EDN-map `args` and returns the tagged result. "
                            "Legacy query-only calls of the form `{query: ...}` remain accepted only as a compatibility alias for `action: \"query\"`. "
                            "Optional `entity` seeds root attributes for explicit query targeting, e.g. entity {:psi.agent-session/session-id \"sid\"}.")
   :lambda-description "λaction. runtime(query ∨ eval[ψ,in-process] ∨ reload-code ∨ project-repl[worktree,nrepl]) → {graph ∨ value ∨ reload-report ∨ project-repl-report}"
   :format-request     call-summary/psi-tool-format-request
   :parameters         {:type       "object"
                        :properties {:action        {:type "string" :enum ["query" "eval" "mutate" "reload-code" "project-repl" "workflow" "scheduler" "operation"]
                                                     :description "Canonical psi-tool operation discriminator."}
                                     :query         {:type "string" :description "For `action: \"query\"`: EQL query vector as EDN string, e.g. \"[:psi.agent-session/phase :psi.agent-session/session-id]\""}
                                     :entity        {:type "string" :description "For `action: \"query\"`: optional EDN root entity map to seed the query, e.g. \"{:psi.agent-session/session-id \\\"sid\\\"}\" for explicit session targeting."}
                                     :mutation      {:type "string" :description "For `action: \"mutate\"`: qualified mutation symbol string, e.g. \"psi.extension/close-session\"."}
                                     :params        {:description "For `action: \"mutate\"`: mutation params map/object. String-keyed maps may be normalized to keyword keys at the top level."}
                                     :ns            {:type "string" :description "For `action: \"eval\"`: already loaded namespace string in which to evaluate `form`."}
                                     :form          {:type "string" :description "For `action: \"eval\"`: Clojure form string using full Clojure reader syntax (quote, deref, anon-fn, var) read with *read-eval* false and evaluated in the named namespace."}
                                     :namespaces    {:type "array" :items {:type "string"}
                                                     :description "For `action: \"reload-code\"` namespace mode: ordered vector of already loaded namespace names to reload."}
                                     :worktree-path {:type "string" :description "For `action: \"reload-code\"` worktree mode, and `action: \"project-repl\"`: explicit absolute target worktree path. When absent, invoking session worktree may be used; for psi self-development, that session worktree is the canonical reload target."}
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
                                     :schedule-id   {:type "string" :description "For `action: \"scheduler\"`, `op: \"cancel\"`: schedule id to cancel."}
                                     :operation-id  {:type "string" :description "For `action: \"operation\"`, `op: \"invoke\"`: deterministic operation id to invoke, e.g. \"github/find-issue\"."}
                                     :args          {:type "string" :description "For `action: \"operation\"`, `op: \"invoke\"`: operation arguments as an EDN map string, e.g. \"{:issue 42}\". Defaults to {} when absent. Ignored for `op: \"list\"`."}}
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

(def ^:private psi-tool-action psi-tool-validate/psi-tool-action)
(def ^:private validate-psi-tool-request psi-tool-validate/validate-psi-tool-request)

(defn- sanitize-psi-tool-data [x] (sanitize-psi-tool-result x))
(defn- ordered-map [& kvs] (apply array-map kvs))

(defn- throwable-chain
  [e]
  (take-while some? (iterate ex-cause e)))

(defn- reload-issue-diagnostic
  [e]
  (let [messages  (map #(or (ex-message %) (str %)) (throwable-chain e))
        cyclic?   (some #(str/includes? % "Cyclic load dependency") messages)
        data-seq  (keep ex-data (throwable-chain e))
        data      (apply merge data-seq)
        compiler? (or (instance? clojure.lang.Compiler$CompilerException e)
                      (contains? data :clojure.error/phase)
                      (contains? data :clojure.error/source)
                      (contains? data :clojure.error/line)
                      (contains? data :clojure.error/column))]
    (cond-> {}
      compiler?
      (assoc :issue :compile-error
             :summary "Reloaded source failed to compile in the running process")

      (:clojure.error/source data)
      (assoc :source (:clojure.error/source data))

      (:clojure.error/line data)
      (assoc :line (:clojure.error/line data))

      (:clojure.error/column data)
      (assoc :column (:clojure.error/column data))

      cyclic?
      (assoc :issue :cyclic-load-dependency
             :summary "Reload hit a cyclic load dependency in the live process"
             :hint (str "This often means reload order matters for the namespaces involved. "
                        "If you are reloading psi-tool itself, reload any newly depended-on namespaces first, "
                        "then reload psi.agent-session.psi-tool. For broader changes, prefer a small dependency-first "
                        "namespace reload before a larger worktree reload.")))))

(defn- psi-tool-error-summary
  ([e] (psi-tool-error-summary nil e))
  ([default-phase e]
   (cond-> {:message (or (ex-message e) (str e))
            :class   (.getName (class e))
            :phase   (or (:phase (ex-data e)) default-phase :execute)
            :data    (some-> (ex-data e) sanitize-psi-tool-data)}
     (= :reload-code (or (:phase (ex-data e)) default-phase))
     (assoc :reload-diagnostic (reload-issue-diagnostic e)))))

(defn- format-psi-tool-error [prefix e]
  {:content  (str prefix (or (ex-message e) (str e)))
   :is-error true})

(defn telemetry-args
  [{:strs [action query mutation params ns form namespaces worktree-path op host port code definition-id definition workflow-input run-id chain-name reason message kind session-config label delay-ms at schedule-id operation-id args]}]
  (cond-> (ordered-map)
    action (assoc "action" action)
    query (assoc "query" query)
    mutation (assoc "mutation" mutation)
    params (assoc "params" params)
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
    schedule-id (assoc "schedule-id" schedule-id)
    operation-id (assoc "operation-id" operation-id)
    args (assoc "args" args)))

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

(defn- normalize-top-level-string-keys [m]
  (into {}
        (map (fn [[k v]]
               [(if (string? k) (keyword k) k) v]))
        m))

(defn- parse-qualified-mutation-symbol [mutation]
  (let [op-sym (try
                 (parse-edn-string mutation)
                 (catch Exception _
                   ::invalid))]
    (when-not (qualified-symbol? op-sym)
      (throw (ex-info (str "Invalid psi-tool mutation symbol: " mutation)
                      {:phase :validate :mutation mutation})))
    op-sym))

(defn- mutation-op-name [mutation]
  (or (some-> mutation :config ::pco/op-name)
      (some-> mutation meta ::pco/op-name)))

(defn- registered-mutation-syms [ctx]
  (->> (concat (or (some-> ctx :all-mutations-atom deref) (:all-mutations ctx))
               history-resolvers/all-mutations)
       (keep mutation-op-name)
       set))

(defn- validate-mutation-params [mutation params]
  (cond
    (nil? params) {}
    (map? params) (normalize-top-level-string-keys params)
    :else (throw (ex-info "psi-tool mutate requires `params` to be a map"
                          {:phase :validate
                           :mutation mutation
                           :params-type (keyword (cond
                                                   (string? params) "string"
                                                   (vector? params) "vector"
                                                   (sequential? params) "sequential"
                                                   :else (.getSimpleName (class params))))}))))

(defn- execute-psi-tool-mutate-report [{:keys [ctx session-id]} {:keys [mutation params]}]
  (let [started-at (System/nanoTime)
        op-sym     (parse-qualified-mutation-symbol mutation)
        params*    (validate-mutation-params mutation params)]
    (when-not ctx
      (throw (ex-info "psi-tool mutate requires runtime context"
                      {:phase :validate :action "mutate" :mutation mutation})))
    (when-not session-id
      (throw (ex-info "psi-tool mutate requires invoking session-id"
                      {:phase :validate :action "mutate" :mutation mutation})))
    (when-not (contains? (registered-mutation-syms ctx) op-sym)
      (throw (ex-info (str "Unknown psi-tool mutation: " mutation)
                      {:phase :validate :mutation mutation})))
    (try
      (let [result (runtime-eql/run-extension-mutation-in! ctx session-id op-sym params*)
            duration-ms (long (/ (- (System/nanoTime) started-at) 1000000))]
        {:psi-tool/action :mutate
         :psi-tool/mutation op-sym
         :psi-tool/duration-ms duration-ms
         :psi-tool/overall-status :ok
         :psi-tool/result (sanitize-psi-tool-data result)})
      (catch Exception e
        {:psi-tool/action :mutate
         :psi-tool/mutation op-sym
         :psi-tool/duration-ms (long (/ (- (System/nanoTime) started-at) 1000000))
         :psi-tool/overall-status :error
         :psi-tool/error (psi-tool-error-summary :mutation e)}))))

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

(declare absolute-directory-path!)

(defn- canonical-file [path]
  (when (seq (str path))
    (try (.getCanonicalFile (io/file path))
         (catch Exception _ nil))))

(defn- canonical-path [path] (some-> path canonical-file .getAbsolutePath))

(defn- namespace-resource-paths [ns-or-name]
  (let [base (cond
               (instance? clojure.lang.Namespace ns-or-name) (some-> ns-or-name ns-name str)
               (string? ns-or-name) ns-or-name
               :else nil)
        base (some-> base (str/replace "." "/") (str/replace "-" "_"))]
    (when base
      [(str base ".clj")
       (str base ".cljc")
       (str base ".cljs")])))

(defn- canonical-source-path-for-ns [ns-obj]
  (or (some-> ns-obj meta :file canonical-path)
      (some->> (namespace-resource-paths ns-obj)
               (keep #(some-> % io/resource .getFile canonical-path))
               first)))

(defn- worktree-src-dirs [worktree-path]
  (->> (file-seq (io/file worktree-path))
       (filter #(.isDirectory ^java.io.File %))
       (filter #(= "src" (.getName ^java.io.File %)))
       (map #(.getAbsolutePath (.getCanonicalFile ^java.io.File %)))
       sort
       vec))

(defn- target-source-path-for-ns [worktree-path ns-name]
  (let [matches (->> (for [src-dir (worktree-src-dirs worktree-path)
                           rel-path (namespace-resource-paths ns-name)
                           :let [f (io/file src-dir rel-path)]
                           :when (.isFile ^java.io.File f)]
                       (.getAbsolutePath (.getCanonicalFile ^java.io.File f)))
                     distinct
                     sort
                     vec)]
    (cond
      (= 1 (count matches))
      (first matches)

      (empty? matches)
      nil

      :else
      (throw (ex-info (str "Reload namespace resolves to multiple source files under target worktree: " ns-name)
                      {:phase :validate
                       :action "reload-code"
                       :namespace ns-name
                       :worktree-path worktree-path
                       :target-source-paths matches})))))

(defn- reload-warning [ns-name loaded-source-path target-source-path]
  (when (and loaded-source-path target-source-path (not= loaded-source-path target-source-path))
    {:type :warning
     :namespace ns-name
     :message (str "Reload namespace source path differs from target worktree source: " ns-name)
     :loaded-source-path loaded-source-path
     :target-source-path target-source-path}))

(defn- reload-target-for-namespace! [ns-name worktree-path]
  (let [loaded-source-path (some-> ns-name symbol find-ns canonical-source-path-for-ns)
        target-source-path (target-source-path-for-ns worktree-path ns-name)]
    (when-not target-source-path
      (throw (ex-info (str "Reload namespace source path is not present under target worktree: " ns-name)
                      {:phase :validate
                       :action "reload-code"
                       :namespace ns-name
                       :worktree-path worktree-path
                       :loaded-source-path loaded-source-path})))
    {:ns-name ns-name
     :loaded-source-path loaded-source-path
     :target-source-path target-source-path
     :warning (reload-warning ns-name loaded-source-path target-source-path)}))

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
  namespaces)

(defn- validate-reload-namespace-targeting! [namespaces worktree-path]
  (when (some? worktree-path)
    (absolute-directory-path! worktree-path))
  (when (and (some? namespaces) (some? worktree-path))
    (mapv #(reload-target-for-namespace! % worktree-path) namespaces)))

(defn- absolute-directory-path! [worktree-path]
  (when-not (and (string? worktree-path) (not (str/blank? worktree-path)))
    (throw (ex-info "psi-tool reload-code worktree mode requires a non-blank absolute `worktree-path`" {:phase :validate :action "reload-code"})))
  (let [f (io/file worktree-path)]
    (when-not (.isAbsolute f)
      (throw (ex-info "psi-tool reload-code explicit worktree-path must be absolute" {:phase :validate :action "reload-code" :worktree-path worktree-path})))
    (when-not (.isDirectory f)
      (throw (ex-info "psi-tool reload-code explicit worktree-path must resolve to an existing directory" {:phase :validate :action "reload-code" :worktree-path worktree-path})))
    (.getAbsolutePath (.getCanonicalFile f))))

(defn worktree-reload-candidates [worktree-path]
  (->> (all-ns)
       (map (fn [ns-obj]
              (let [ns-name (str (ns-name ns-obj))
                    loaded-source-path (canonical-source-path-for-ns ns-obj)
                    target-source-path (target-source-path-for-ns worktree-path ns-name)]
                (when target-source-path
                  {:ns-name ns-name
                   :loaded-source-path loaded-source-path
                   :target-source-path target-source-path
                   :warning (reload-warning ns-name loaded-source-path target-source-path)}))))
       (keep identity)
       (sort-by :ns-name)
       vec))

(defn- refresh-query-runtime! [ctx]
  ;; Agent-session and agent-core both cache Pathom env snapshots in defonce
  ;; atoms. Those envs capture resolver vars and must be dropped so the next
  ;; query rebuilds from the freshly reloaded namespaces.
  (session-resolvers/invalidate-query-env!)
  (agent/invalidate-query-env!)
  (if-not ctx
    {:status :ok :summary "invalidated cached query envs (no runtime ctx provided)"}
    {:status :ok :summary "invalidated cached agent-session and agent-core query envs"}))

(defn- refresh-all-mutations!
  "Reset the ctx :all-mutations-atom to the live value of
   psi.agent-session.mutations/all-mutations, making new mutations from
   reloaded namespaces visible to extension EQL and tool-plan per-request qctx."
  [ctx]
  (if-not ctx
    {:status :ok :summary "mutation registrations unchanged (no runtime ctx provided)"}
    (if-let [a (:all-mutations-atom ctx)]
      (if-let [v (resolve 'psi.agent-session.mutations/all-mutations)]
        (let [fresh @v]
          (reset! a fresh)
          {:status :ok
           :summary (str "all-mutations-atom refreshed from live namespace var (" (count fresh) " mutations)")
           :mutation-count (count fresh)})
        {:status :ok
         :summary "all-mutations-atom unchanged (psi.agent-session.mutations/all-mutations var not resolved)"})
      {:status :ok :summary "all-mutations-atom unchanged (ctx has no :all-mutations-atom)"})))

(defn- refresh-live-tool-defs! [ctx session-id]
  (if-not (and ctx session-id)
    {:status :ok :summary "live tool definitions unchanged (no session runtime provided)"}
    (let [agent-ctx  (session-state/agent-ctx-in ctx session-id)
          sd         (session-state/get-session-data-in ctx session-id)
          ;; Rebuild tool-source from agent data + extension registry
          base-tools (or (session-state/agent-tool-source-in ctx session-id) [])
          ext-tools  (tool-registry/all-tools-in (:extension-registry ctx))
          tool-source (into (vec base-tools) ext-tools)
          tool-defs  (tool-defs/resolve-tool-defs tool-source (:tool-ids sd))]
      (when agent-ctx
        (agent/set-tools-in! agent-ctx tool-defs))
      {:status :ok :summary (str "refreshed live tool defs (" (count tool-defs) " tools)")})))

(defn- refresh-dispatch-handlers! [ctx]
  ;; The state-kernel handler registry is a long-lived defonce atom keyed to
  ;; function values registered during context creation. Re-register handlers so
  ;; dispatch resolves to the current vars after reload.
  (kernel-dispatch/clear-handlers!)
  (if-not ctx
    {:status :ok :summary "dispatch handlers reset (no runtime ctx provided)"}
    (do
      ((requiring-resolve 'psi.agent-session.dispatch-handlers/register-all!) ctx)
      {:status :ok
       :summary (str "re-registered dispatch handlers (" (count (kernel-dispatch/registered-event-types)) " events)")
       :event-count (count (kernel-dispatch/registered-event-types))})))

(defn- maybe-refresh-built-in-workflow! [ctx session-id]
  ;; Built-in workflow keeps long-lived defonce runtime state, command/tool
  ;; registration, prompt contribution registration, and loaded definitions.
  ;; Re-initialize it when it is already active so those surfaces point at the
  ;; freshly reloaded vars.
  (let [workflow-state @workflow-runtime-state/state
        initialized?   (and workflow-state
                            (contains? workflow-state :api))]
    (if-not initialized?
      {:status :ok :summary "built-in workflow runtime state unchanged (not initialized)"}
      (if-not (and ctx session-id)
        {:status :ok :summary "built-in workflow runtime state unchanged (no session runtime provided)"}
        (let [result ((requiring-resolve 'psi.agent-session.workflow.bootstrap/init-built-in!) ctx session-id)]
          {:status :ok
           :summary (str "reinitialized built-in workflow runtime state (" (count (:loaded-definitions result)) " definitions)")
           :definition-count (count (:loaded-definitions result))})))))

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

(defn- reload-model-registry-step! [worktree-path]
  (let [effective-path (or worktree-path (System/getProperty "user.dir"))]
    (model-registry/init! {:user-models-path    (model-registry/default-user-models-path)
                           :project-models-path (str effective-path "/.psi/models.edn")})
    {:status :ok
     :summary (str "reinitialized model registry for worktree " effective-path)
     :worktree-path effective-path
     :model-count (count (model-registry/all-models-seq))
     :load-error (model-registry/get-load-error)}))

(defn reload-namespace!
  ([worktree-path ns-name]
   (if-let [target-source-path (target-source-path-for-ns worktree-path ns-name)]
     (reload-namespace! worktree-path ns-name target-source-path)
     (throw (ex-info (str "Reload namespace source path is not present under target worktree: " ns-name)
                     {:phase :validate
                      :action "reload-code"
                      :namespace ns-name
                      :worktree-path worktree-path}))))
  ([worktree-path ns-name target-source-path]
   (load-file target-source-path)
   (when ((set ["psi.ai.models" "psi.ai.model-registry"]) ns-name)
     (reload-model-registry-step! worktree-path))))

(defn- execute-psi-tool-reload-report [{:keys [ctx session-id cwd]} {:keys [namespaces worktree-path]}]
  (let [started-at (System/nanoTime)
        namespace-mode? (some? namespaces)
        reload-mode (if namespace-mode? :namespaces :worktree)
        requested-nses (when namespace-mode? (validate-reload-namespaces namespaces))
        session-derived-path (cond
                               (and ctx session-id) (absolute-directory-path! (session-state/session-worktree-path-in ctx session-id))
                               (some? cwd) (absolute-directory-path! cwd)
                               :else nil)
        effective-path (if namespace-mode?
                         (cond
                           (some? worktree-path) (absolute-directory-path! worktree-path)
                           (some? session-derived-path) session-derived-path
                           :else (throw (ex-info "psi-tool reload-code namespace mode requires explicit worktree-path or invoking session worktree-path" {:phase :validate :action "reload-code"})))
                         (cond
                           (some? worktree-path) (absolute-directory-path! worktree-path)
                           (some? session-derived-path) session-derived-path
                           :else (throw (ex-info "psi-tool reload-code worktree mode requires explicit worktree-path or invoking session worktree-path" {:phase :validate :action "reload-code"}))))
        namespace-targets (when namespace-mode? (validate-reload-namespace-targeting! requested-nses effective-path))
        worktree-source (if (some? worktree-path) :explicit :session)
        candidates (if namespace-mode?
                     namespace-targets
                     (let [matches (worktree-reload-candidates effective-path)]
                       (when (empty? matches)
                         (throw (ex-info "psi-tool reload-code worktree target is not reloadable in the current runtime" {:phase :validate :action "reload-code" :worktree-path effective-path})))
                       matches))
        reload-result (loop [remaining candidates reloaded [] warnings []]
                        (if-let [{:keys [ns-name target-source-path warning]} (first remaining)]
                          (let [step-result (try (reload-namespace! effective-path ns-name target-source-path) {:ok? true}
                                                 (catch Exception e {:ok? false :error e}))
                                warnings' (cond-> warnings warning (conj warning))]
                            (if (:ok? step-result)
                              (recur (rest remaining) (conj reloaded ns-name) warnings')
                              {:status :error :namespace-count (count reloaded) :namespaces reloaded
                               :warnings warnings'
                               :summary (str "reload stopped after failure in " ns-name)
                               :error (assoc (psi-tool-error-summary :reload-code (:error step-result)) :namespace ns-name)}))
                          {:status :ok :namespace-count (count reloaded) :namespaces reloaded :warnings warnings :summary (str "reloaded " (count reloaded) " namespaces")}))
        refresh-steps [(assoc (refresh-query-runtime! ctx) :step :resolver-registration-refresh)
                       (assoc (refresh-all-mutations! ctx) :step :mutation-registration-refresh)
                       (assoc (refresh-live-tool-defs! ctx session-id) :step :live-tool-definition-refresh)
                       (assoc (refresh-dispatch-handlers! ctx) :step :dispatch-handler-refresh)
                       (assoc (maybe-refresh-built-in-workflow! ctx session-id) :step :built-in-workflow-refresh)
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
             :psi-tool/overall-status (if (and (= :ok (:status reload-result)) (= :ok (:status graph-refresh))) :ok :error)
             :psi-tool/worktree-path effective-path
             :psi-tool/worktree-source worktree-source}
      namespace-mode? (assoc :psi-tool/namespaces-requested requested-nses))))

(defn truncation-visible-prefix [{:keys [action mutation ns form namespaces worktree-path op code definition-id run-id schedule-id label kind operation-id]}]
  (case action
    "eval" (str "Eval action=eval ns=" ns " form=" form)
    "mutate" (str "Mutate action=mutate mutation=" mutation)
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
    "operation" (str "Operation action=operation op=" op
                     (when operation-id (str " operation-id=" operation-id)))
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

(defn- execute-psi-tool-operation-report [runtime-opts request]
  (psi-tool-operation/execute-psi-tool-operation-report runtime-opts request))

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
                  "mutate" (let [report (execute-psi-tool-mutate-report {:ctx (:ctx opts) :session-id (:session-id opts)} request)
                                 safe-report (sanitize-psi-tool-data report)
                                 output (pr-str safe-report)]
                             (assoc (serialize-operation-output opts request output "Use a narrower mutation result to reduce output size.") :is-error (not= :ok (:psi-tool/overall-status safe-report))))
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
                                (assoc (serialize-operation-output opts request output "Use a narrower scheduler result to reduce output size.") :is-error (not= :ok (:psi-tool/overall-status safe-report))))
                  "operation" (let [report (execute-psi-tool-operation-report {:ctx (:ctx opts) :session-id (:session-id opts)} request)
                                    safe-report (sanitize-psi-tool-data report)
                                    output (pr-str safe-report)]
                                (assoc (serialize-operation-output opts request output "Use a narrower operation result to reduce output size.") :is-error (not= :ok (:psi-tool/overall-status safe-report))))))
              (catch StackOverflowError _
                {:content "EQL query error: result contains recursive data that overflowed printer stack" :is-error true})
              (catch Exception e
                (let [action (psi-tool-action args)]
                  (case action
                    "eval" {:content (pr-str {:psi-tool/action :eval :psi-tool/ns (get args "ns") :psi-tool/duration-ms 0 :psi-tool/error (psi-tool-error-summary :eval e)}) :is-error true}
                    "mutate" {:content (pr-str {:psi-tool/action :mutate
                                                :psi-tool/mutation (try
                                                                     (some-> (get args "mutation") parse-qualified-mutation-symbol)
                                                                     (catch Exception _ nil))
                                                :psi-tool/duration-ms 0
                                                :psi-tool/overall-status :error
                                                :psi-tool/error (psi-tool-error-summary :mutation e)})
                              :is-error true}
                    "reload-code" (let [worktree-path (or (try
                                                            (some-> (get args "worktree-path") absolute-directory-path!)
                                                            (catch Exception _ nil))
                                                          (when-let [sid (:session-id opts)]
                                                            (try
                                                              (some-> (:ctx opts) (session-state/session-worktree-path-in sid) absolute-directory-path!)
                                                              (catch Exception _ nil)))
                                                          (try
                                                            (some-> (:cwd opts) absolute-directory-path!)
                                                            (catch Exception _ nil)))
                                        worktree-source (cond
                                                          (get args "worktree-path") :explicit
                                                          (or (:session-id opts) (:cwd opts)) :session
                                                          :else nil)]
                                    {:content (pr-str (cond-> {:psi-tool/action :reload-code
                                                               :psi-tool/duration-ms 0
                                                               :psi-tool/overall-status :error
                                                               :psi-tool/error (psi-tool-error-summary :reload-code e)}
                                                        worktree-path (assoc :psi-tool/worktree-path worktree-path)
                                                        worktree-source (assoc :psi-tool/worktree-source worktree-source)))
                                     :is-error true})
                    "project-repl" {:content (pr-str {:psi-tool/action :project-repl :psi-tool/project-repl-op (some-> (get args "op") keyword) :psi-tool/duration-ms 0 :psi-tool/overall-status :error :psi-tool/error (psi-tool-error-summary :project-repl e)}) :is-error true}
                    "workflow" {:content (pr-str {:psi-tool/action :workflow :psi-tool/workflow-op (some-> (get args "op") keyword) :psi-tool/duration-ms 0 :psi-tool/overall-status :error :psi-tool/error (psi-tool-error-summary :workflow e)}) :is-error true}
                    "scheduler" {:content (pr-str {:psi-tool/action :scheduler :psi-tool/scheduler-op (some-> (get args "op") keyword) :psi-tool/duration-ms 0 :psi-tool/overall-status :error :psi-tool/error (psi-tool-error-summary :scheduler e)}) :is-error true}
                    "operation" {:content (pr-str {:psi-tool/action :operation :psi-tool/operation-op (some-> (get args "op") keyword) :psi-tool/duration-ms 0 :psi-tool/overall-status :error :psi-tool/error (psi-tool-error-summary :operation e)}) :is-error true}
                    (format-psi-tool-error "EQL query error: " e)))))))))

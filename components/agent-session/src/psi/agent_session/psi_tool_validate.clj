(ns psi.agent-session.psi-tool-validate
  "psi-tool request validation: action resolution, per-action parameter checks,
   and normalization into the executed request map."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]))

(defn- parse-edn-string [s]
  (binding [*read-eval* false]
    (edn/read-string s)))

(defn psi-tool-action [{:strs [action query]}]
  (cond
    (some? action) action
    (some? query)  "query"
    :else          nil))

(def ^:private psi-tool-supported-actions ["query" "eval" "mutate" "reload-code" "project-repl" "workflow" "scheduler" "operation"])
(def ^:private project-repl-supported-ops ["status" "start" "attach" "stop" "eval" "interrupt"])
(def ^:private workflow-supported-ops ["list-definitions" "create-run" "execute-run" "read-run" "list-runs" "resume-run" "cancel-run"])
(def ^:private scheduler-supported-ops ["create" "list" "cancel"])
(def ^:private operation-supported-ops ["list" "invoke"])

(defn- parse-operation-args-string
  "Parse the `operation` action `args` param as an EDN map (default `{}`)."
  [args-string]
  (if (some? args-string)
    (let [parsed (parse-edn-string args-string)]
      (when-not (map? parsed)
        (throw (ex-info "psi-tool operation args must be an EDN map"
                        {:phase :validate :action "operation" :op "invoke"})))
      parsed)
    {}))

(defn validate-psi-tool-request
  [{:strs [query entity mutation params ns form namespaces worktree-path op host port code definition-id definition workflow-input run-id chain-name reason message kind session-config label delay-ms at schedule-id operation-id] :as args}]
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

      (= effective-action "mutate")
      (do
        (when (contains? args "entity")
          (throw (ex-info "psi-tool mutate does not support `entity`; pass targeting data in `params`"
                          {:phase :validate :action effective-action :mutation mutation})))
        (when-not (string? mutation)
          (throw (ex-info "psi-tool mutate action requires `mutation`"
                          {:phase :validate :action effective-action})))
        {:action effective-action :mutation mutation :params params})

      (= effective-action "reload-code")
      {:action effective-action :namespaces namespaces :worktree-path worktree-path}

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
        {:action effective-action :op op :message message :kind kind :session-config session-config :label label :delay-ms delay-ms :at at :schedule-id schedule-id})

      (= effective-action "operation")
      (do
        (when-not (some #{op} operation-supported-ops)
          (throw (ex-info "psi-tool operation action requires valid `op`"
                          {:phase :validate :action effective-action :op op :supported-ops operation-supported-ops})))
        (if (= op "invoke")
          (do
            (when-not (and (string? operation-id) (not (str/blank? operation-id)))
              (throw (ex-info "psi-tool operation invoke requires `operation-id`"
                              {:phase :validate :action effective-action :op op})))
            {:action effective-action :op op :operation-id operation-id
             :args (parse-operation-args-string (get args "args"))})
          ;; op "list": args/operation-id ignored, args not parsed (D5)
          {:action effective-action :op op})))))

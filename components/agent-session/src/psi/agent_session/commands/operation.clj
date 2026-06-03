(ns psi.agent-session.commands.operation
  "Deterministic-operation slash-command handling: `/operations` (list) and
   `/operation <id> {edn-args}` (invoke).

   Thin adapter over the shared `deterministic-operation-action` mechanism; owns
   only text rendering and command-argument parsing."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [psi.agent-session.deterministic-operation-action :as op-action]))

(defn format-operations
  "Render the deterministic-operation listing as text: one
   `\"<id> — <description>\"` line per operation (sorted by the helper).
   Empty registry → explicit message."
  [ctx]
  (let [operations (op-action/list-operations ctx)]
    (if (seq operations)
      (str/join "\n"
                (map (fn [{:keys [id description]}]
                       (str id " — " description))
                     operations))
      "No deterministic operations registered.")))

(defn- render-operation-result
  "Render a tagged result as text: `:status` line first, remaining top-level
   keys sorted ascending by `pr-str`, one `\"<key> <value>\"` line per key (D2)."
  [result]
  (let [projected (op-action/project-result result)
        ordered-keys (cons :status
                           (sort-by pr-str (remove #{:status} (keys projected))))]
    (str/join "\n"
              (map (fn [k] (str (pr-str k) " " (get projected k)))
                   ordered-keys))))

(defn- parse-operation-command-args
  "Parse the EDN-map `args` text for the `/operation` command. Blank → `{}`.
   Returns `{:ok args}` or `{:error message}`."
  [args-text]
  (if (str/blank? args-text)
    {:ok {}}
    (let [parsed (try
                   (binding [*read-eval* false]
                     {:ok (edn/read-string args-text)})
                   (catch Exception e
                     {:error (str "Could not parse args as EDN: "
                                  (or (ex-message e) (str e)))}))]
      (cond
        (:error parsed) parsed
        (map? (:ok parsed)) parsed
        :else {:error "Operation args must be an EDN map."}))))

(defn dispatch-command
  "Dispatch `/operation <id> {edn-args}` (decision #11)."
  [ctx session-id trimmed]
  (let [tail (str/replace trimmed #"^/operation\s*" "")
        [id args-text] (str/split tail #"\s+" 2)]
    (if (str/blank? id)
      {:type :text :message "Usage: /operation <id> {edn-args}"}
      (let [parsed-args (parse-operation-command-args args-text)]
        (if (:error parsed-args)
          {:type :text :message (:error parsed-args)}
          (try
            (let [result (op-action/invoke-operation ctx session-id id (:ok parsed-args))]
              {:type :text :message (render-operation-result result)})
            (catch clojure.lang.ExceptionInfo e
              (case (:type (ex-data e))
                :missing-deterministic-operation
                {:type :text :message (str "Unknown deterministic operation: " id)}
                :malformed-operation-result
                {:type :text :message (str "Operation " id " returned a malformed result.")}
                (throw e)))))))))

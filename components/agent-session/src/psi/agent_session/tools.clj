(ns psi.agent-session.tools
  "Built-in tool implementations: read, bash, edit, write.

   Each tool returns {:content string :is-error boolean}.
   Errors throw ex-info so the executor can catch and report them."
  (:require
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.tool-path :as tool-path]))

;; ============================================================
;; Tool schemas (for agent registration)
;; ============================================================

(def read-tool
  {:name        "read"
   :label       "Read"
   :description "Read the contents of a file. Returns the file text."
   :parameters  (pr-str {:type       "object"
                         :properties {:path {:type "string" :description "File path to read"}}
                         :required   ["path"]})})

(def bash-tool
  {:name        "bash"
   :label       "Bash"
   :description "Execute a bash command. Returns stdout and stderr combined."
   :parameters  (pr-str {:type       "object"
                         :properties {:command {:type "string" :description "Bash command to run"}
                                      :timeout {:type "integer" :description "Timeout in seconds (default 30)"}}
                         :required   ["command"]})})

(def edit-tool
  {:name        "edit"
   :label       "Edit"
   :description "Replace exact text in a file. oldText must match exactly."
   :parameters  (pr-str {:type       "object"
                         :properties {:path    {:type "string" :description "File path"}
                                      :oldText {:type "string" :description "Exact text to find"}
                                      :newText {:type "string" :description "Replacement text"}}
                         :required   ["path" "oldText" "newText"]})})

(def write-tool
  {:name        "write"
   :label       "Write"
   :description "Write content to a file, creating it if it does not exist."
   :parameters  (pr-str {:type       "object"
                         :properties {:path    {:type "string" :description "File path"}
                                      :content {:type "string" :description "Content to write"}}
                         :required   ["path" "content"]})})

(def eql-query-tool
  {:name        "eql_query"
   :label       "EQL Query"
   :description "Execute an EQL query against the live session graph. Returns session state, tool info, extension status, and more. Input is an EDN vector, e.g. [:psi.agent-session/phase :psi.agent-session/model]"
   :parameters  (pr-str {:type       "object"
                         :properties {:query {:type "string" :description "EQL query vector as EDN string, e.g. \"[:psi.agent-session/phase :psi.agent-session/session-id]\""}}
                         :required   ["query"]})})

(def all-tool-schemas
  [read-tool bash-tool edit-tool write-tool eql-query-tool])

;; ============================================================
;; Tool implementations
;; ============================================================

(defn- resolve-path
  "Resolve a path against an optional cwd. Delegates to tool-path for
   normalization (strip @, unicode spaces, tilde expansion) and cwd resolution."
  ^java.io.File [cwd path]
  (let [expanded (tool-path/expand-path (str path))]
    (tool-path/resolve-to-cwd cwd expanded)))

(defn- slurp-file
  ([path] (slurp-file nil path))
  ([cwd path]
   (let [f (resolve-path cwd path)]
     (when-not (.exists f)
       (throw (ex-info (str "File not found: " (.getPath f)) {:path (.getPath f)})))
     (slurp f))))

(defn execute-read
  "Read a file and return its contents.
   Accepts optional :cwd in opts to resolve relative paths."
  ([args] (execute-read args nil))
  ([{:strs [path]} {:keys [cwd]}]
   (let [content (slurp-file cwd path)]
     {:content  content
      :is-error false})))

(defn execute-bash
  "Run a shell command via babashka.process, returning combined stdout+stderr.
   Stdin is bound to /dev/null so tools like rg don't misdetect a readable
   pipe and search stdin instead of the working directory.
   Accepts optional :cwd in opts to set the working directory."
  ([args] (execute-bash args nil))
  ([{:strs [command]} {:keys [cwd]}]
   (let [result (proc/shell (cond-> {:out      :string
                                     :err      :string
                                     :continue true
                                     :in       (java.io.File. "/dev/null")}
                              cwd (assoc :dir cwd))
                            "bash" "-c" command)
         out    (str (:out result) (:err result))]
     {:content  (if (str/blank? out) "[no output]" out)
      :is-error (not= 0 (:exit result))})))

(defn execute-edit
  "Replace oldText with newText in a file.
   Accepts optional :cwd in opts to resolve relative paths."
  ([args] (execute-edit args nil))
  ([{:strs [path oldText newText]} {:keys [cwd]}]
   (let [f       (resolve-path cwd path)
         fpath   (.getPath f)
         content (slurp-file cwd path)]
     (when-not (str/includes? content oldText)
       (throw (ex-info "oldText not found in file"
                       {:path fpath :oldText (subs oldText 0 (min 80 (count oldText)))})))
     (let [updated (str/replace-first content oldText newText)]
       (spit f updated)
       {:content  (str "Edited " fpath)
        :is-error false}))))

(defn execute-write
  "Write content to a file (creates parent dirs if needed).
   Accepts optional :cwd in opts to resolve relative paths."
  ([args] (execute-write args nil))
  ([{:strs [path content]} {:keys [cwd]}]
   (let [f     (resolve-path cwd path)
         fpath (.getPath f)]
     (io/make-parents f)
     (spit f content)
     {:content  (str "Wrote " fpath)
      :is-error false})))

(defn make-eql-query-tool
  "Create an eql_query tool with an :execute fn that closes over `query-fn`.
   `query-fn` should be (fn [eql-query-vec] -> result-map), typically
   `(partial resolvers/query-in ctx)` or `(fn [q] (session/query-in ctx q))`."
  [query-fn]
  (assoc eql-query-tool
         :execute
         (fn [{:strs [query]}]
           (try
             (let [q (binding [*read-eval* false]
                       (read-string query))]
               (when-not (vector? q)
                 (throw (ex-info "Query must be an EDN vector" {:input query})))
               (let [result (query-fn q)]
                 {:content  (pr-str result)
                  :is-error false}))
             (catch Exception e
               {:content  (str "EQL query error: " (ex-message e))
                :is-error true})))))

(def all-tools
  "Built-in tool definitions including execution fns.
   Use this when registering tools into agent state.
   Note: eql_query is excluded — it requires a session context.
   Use `make-eql-query-tool` to create it with a query-fn."
  [{:name        (:name read-tool)
    :label       (:label read-tool)
    :description (:description read-tool)
    :parameters  (:parameters read-tool)
    :execute     execute-read}
   {:name        (:name bash-tool)
    :label       (:label bash-tool)
    :description (:description bash-tool)
    :parameters  (:parameters bash-tool)
    :execute     execute-bash}
   {:name        (:name edit-tool)
    :label       (:label edit-tool)
    :description (:description edit-tool)
    :parameters  (:parameters edit-tool)
    :execute     execute-edit}
   {:name        (:name write-tool)
    :label       (:label write-tool)
    :description (:description write-tool)
    :parameters  (:parameters write-tool)
    :execute     execute-write}])

;; ============================================================
;; CWD-scoped tools
;; ============================================================

(defn make-tools-with-cwd
  "Return the four standard tool maps (read, bash, edit, write) with :execute
   fns that resolve relative paths and run commands in `cwd`.

   This is the preferred way for extensions/sub-agents to get tools scoped
   to a specific working directory without redefining tool wrappers."
  [cwd]
  (let [opts {:cwd cwd}]
    [{:name        (:name read-tool)
      :label       (:label read-tool)
      :description (:description read-tool)
      :parameters  (:parameters read-tool)
      :execute     (fn [args] (execute-read args opts))}
     {:name        (:name bash-tool)
      :label       (:label bash-tool)
      :description (:description bash-tool)
      :parameters  (:parameters bash-tool)
      :execute     (fn [args] (execute-bash args opts))}
     {:name        (:name edit-tool)
      :label       (:label edit-tool)
      :description (:description edit-tool)
      :parameters  (:parameters edit-tool)
      :execute     (fn [args] (execute-edit args opts))}
     {:name        (:name write-tool)
      :label       (:label write-tool)
      :description (:description write-tool)
      :parameters  (:parameters write-tool)
      :execute     (fn [args] (execute-write args opts))}]))

;; ============================================================
;; Dispatch
;; ============================================================

(defn execute-tool
  "Dispatch a tool call by name. Returns {:content string :is-error boolean}.
  Throws ex-info for unknown tools.
  Note: eql_query is not dispatched here — it requires a session context
  and is handled via the tool registry's :execute fn."
  [tool-name args-map]
  (case tool-name
    "read"  (execute-read args-map)
    "bash"  (execute-bash args-map)
    "edit"  (execute-edit args-map)
    "write" (execute-write args-map)
    (throw (ex-info (str "Unknown tool: " tool-name) {:tool tool-name}))))

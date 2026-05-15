(ns psi.tool-runtime.call-summary
  "Canonical compact tool-call header summary formatting.

   Tool definitions may supply `:format-request` as an executable formatter fn.
   This namespace owns safe invocation, normalization, fallback, and shared
   built-in formatter helpers used by runtime and UI-adjacent code."
  (:require
   [clojure.string :as str]
   [psi.tool-runtime.args :as tool-args]
   [taoensso.timbre :as timbre]))

(defn- tool-arg-get
  [args & keys]
  (when (map? args)
    (some #(get args %) keys)))

(defn- tool-int
  [v]
  (cond
    (integer? v) v
    (number? v)  (long v)
    (string? v)  (try (Long/parseLong v) (catch Exception _ nil))
    :else        nil))

(defn single-line
  [text]
  (-> (str (or text ""))
      (str/replace #"[\r\n\t]+" " ")
      (str/replace #" +" " ")
      str/trim))

(defn truncate
  [text max-chars]
  (let [line  (single-line text)
        limit (max 1 (or max-chars 1))]
    (if (> (count line) limit)
      (str (subs line 0 (max 0 (dec limit))) "…")
      line)))

(defn parse-args
  [{:keys [parsed-args arguments]}]
  (cond
    (map? parsed-args) parsed-args
    (map? arguments)   arguments
    (string? arguments) (tool-args/parse-args arguments)
    :else {}))

(defn fallback-summary
  [{:keys [tool tool-name]}]
  (or (some-> tool-name str not-empty)
      (some-> (:name tool) str not-empty)
      (some-> (:label tool) str not-empty)
      "tool"))

(defn read-line-range-suffix
  [args]
  (let [offset* (tool-int (tool-arg-get args "offset" :offset))
        limit*  (tool-int (tool-arg-get args "limit" :limit))
        offset  (or offset* (when limit* 1))]
    (cond
      (and offset limit* (pos? limit*))
      (format ":%d:%d" offset (+ offset (dec limit*)))

      offset
      (format ":%d" offset)

      :else
      "")))

(defn edit-line-range-suffix
  [args details & {:keys [old-text-keys]}]
  (let [first-changed* (or (get details :firstChangedLine)
                           (get details :first-changed-line)
                           (get details "firstChangedLine")
                           (get details "first-changed-line"))
        first-changed  (tool-int first-changed*)
        old-text       (apply tool-arg-get args (or old-text-keys ["oldText" :oldText "old_text"]))
        span           (when (string? old-text)
                         (max 1 (count (str/split-lines old-text))))]
    (cond
      (and first-changed span (> span 1))
      (format ":%d:%d" first-changed (+ first-changed (dec span)))

      first-changed
      (format ":%d" first-changed)

      :else
      "")))

(defn read-format-request
  [{:keys [parsed-args arguments]}]
  (let [args (parse-args {:parsed-args parsed-args :arguments arguments})
        path (tool-arg-get args "path" :path)]
    (str "read "
         (if (seq (str path))
           (str path (read-line-range-suffix args))
           "…"))))

(defn edit-format-request
  [{:keys [parsed-args arguments details]}]
  (let [args (parse-args {:parsed-args parsed-args :arguments arguments})
        path (tool-arg-get args "path" :path)]
    (str "edit "
         (if (seq (str path))
           (str path (edit-line-range-suffix args details))
           "…"))))

(defn edit-clj-format-request
  [{:keys [parsed-args arguments details]}]
  (let [args (parse-args {:parsed-args parsed-args :arguments arguments})
        path (tool-arg-get args "filename" :filename)]
    (str "edit-clj "
         (if (seq (str path))
           (str path (edit-line-range-suffix args details :old-text-keys ["old-string" :old-string]))
           "…"))))

(defn write-format-request
  [{:keys [parsed-args arguments]}]
  (let [args (parse-args {:parsed-args parsed-args :arguments arguments})
        path (tool-arg-get args "path" :path)]
    (str "write " (if (seq (str path)) path "…"))))

(defn bash-format-request
  [{:keys [parsed-args arguments]}]
  (let [args    (parse-args {:parsed-args parsed-args :arguments arguments})
        command (tool-arg-get args "command" :command)]
    (str "$ " (if (seq (str command)) command "…"))))

(defn text-key-format-request
  [tool-label text-key]
  (fn [{:keys [parsed-args arguments]}]
    (let [args  (parse-args {:parsed-args parsed-args :arguments arguments})
          value (tool-arg-get args text-key (keyword text-key))]
      (str tool-label " " (if (seq (str value)) (truncate value 64) "…")))))

(defn- vector-summary
  [v]
  (when (sequential? v)
    (->> v (map str) (str/join ","))))

(defn- psi-tool-action
  [args]
  (or (tool-arg-get args "action" :action)
      (when (some? (tool-arg-get args "query" :query)) "query")
      "…"))

(defn psi-tool-format-request
  [{:keys [parsed-args arguments]}]
  (let [args   (parse-args {:parsed-args parsed-args :arguments arguments})
        action (psi-tool-action args)
        detail (case action
                 "query"       (or (some-> (tool-arg-get args "query" :query) (truncate 56))
                                   (some-> (tool-arg-get args "entity" :entity) (truncate 56)))
                 "eval"        (or (some-> (tool-arg-get args "ns" :ns) (truncate 24))
                                   (some-> (tool-arg-get args "form" :form) (truncate 52)))
                 "mutate"      (or (some-> (tool-arg-get args "mutation" :mutation) (truncate 56))
                                   (some-> (tool-arg-get args "params" :params) pr-str (truncate 56)))
                 "reload-code" (or (some-> (tool-arg-get args "namespaces" :namespaces) vector-summary (truncate 56))
                                   (some-> (tool-arg-get args "worktree-path" :worktree-path) (truncate 56)))
                 "project-repl" (or (some-> (tool-arg-get args "op" :op) (#(str "op=" %)) (truncate 18))
                                    (some-> (tool-arg-get args "code" :code) (truncate 56))
                                    (some-> (tool-arg-get args "worktree-path" :worktree-path) (truncate 56)))
                 "workflow"    (or (some-> (tool-arg-get args "op" :op) (#(str "op=" %)) (truncate 24))
                                   (some-> (tool-arg-get args "definition-id" :definition-id) (truncate 52))
                                   (some-> (tool-arg-get args "run-id" :run-id) (truncate 52)))
                 "scheduler"   (or (some-> (tool-arg-get args "op" :op) (#(str "op=" %)) (truncate 24))
                                   (some-> (tool-arg-get args "label" :label) (truncate 52))
                                   (some-> (tool-arg-get args "schedule-id" :schedule-id) (truncate 52))
                                   (some-> (tool-arg-get args "message" :message) (truncate 52)))
                 nil)
        header (str "psi-tool " action (when (seq detail) (str " " detail)))]
    (truncate header 80)))

(defn delegate-format-request
  [{:keys [parsed-args arguments]}]
  (let [args   (parse-args {:parsed-args parsed-args :arguments arguments})
        action (or (tool-arg-get args "action" :action)
                   (when (some? (tool-arg-get args "workflow" :workflow)) "run")
                   "list")
        detail (case action
                 "run"      (or (some-> (tool-arg-get args "workflow" :workflow) (truncate 60))
                                (some-> (tool-arg-get args "name" :name) (truncate 60))
                                (some-> (tool-arg-get args "prompt" :prompt) (truncate 56)))
                 "continue" (or (some-> (tool-arg-get args "id" :id) (truncate 56))
                                (some-> (tool-arg-get args "prompt" :prompt) (truncate 56)))
                 "remove"   (some-> (tool-arg-get args "id" :id) (truncate 58))
                 nil)
        header (str "delegate " action (when (seq detail) (str " " detail)))]
    (truncate header 80)))

(defn work-on-format-request
  [{:keys [parsed-args arguments]}]
  (let [args          (parse-args {:parsed-args parsed-args :arguments arguments})
        description   (tool-arg-get args "description" :description)
        base-branch   (tool-arg-get args "base_branch" :base_branch "base-branch" :base-branch)
        description*  (cond
                        (not (seq (str description))) "…"
                        base-branch                   (truncate description 44)
                        :else                         (truncate description 72))
        header        (if (and (not= description* "…") (seq (str base-branch)))
                        (str "work-on " description* " from " (truncate base-branch 20))
                        (str "work-on " description*))]
    (truncate header 80)))

(def ^:private legacy-formatters
  {"read"     read-format-request
   "edit"     edit-format-request
   "edit-clj" edit-clj-format-request
   "write"    write-format-request
   "bash"     bash-format-request
   "psi-tool" psi-tool-format-request
   "delegate" delegate-format-request
   "work-on"  work-on-format-request})

(defn formatter
  [{:keys [tool tool-name]}]
  (or (:format-request tool)
      (get legacy-formatters (some-> tool-name str not-empty))
      (get legacy-formatters (some-> (:name tool) str not-empty))))

(defn format-call-summary
  [{:keys [tool-name] :as input}]
  (let [base-input (assoc input :parsed-args (parse-args input))
        fallback   (fallback-summary base-input)]
    (if-let [format-request (formatter base-input)]
      (try
        (let [formatted (format-request base-input)]
          (if (string? formatted)
            (let [line (single-line formatted)]
              (if (str/blank? line) fallback line))
            fallback))
        (catch Exception e
          (timbre/warn "Tool call-summary formatter failed for" tool-name "- falling back:" (ex-message e))
          fallback))
      fallback)))

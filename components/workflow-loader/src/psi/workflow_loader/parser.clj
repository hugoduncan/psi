(ns psi.workflow-loader.parser
  "Parse workflow definition files by explicit file kind.

   Supported authored file kinds:
   - `.md`  — single-step markdown workflow with required frontmatter + body
   - `.edn` — multi-step target-authored workflow definition

   Parsers return normalized authored maps or `{:error ...}`."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [psi.prompt-assets.prompt-templates :as pt]))

(def ^:private allowed-md-frontmatter-keys
  #{:name
    :description
    :tools
    :skills
    :model
    :thinking-level
    :response-mode
    :temperature
    :logprobs
    :top-logprobs
    :prompt-component-selection})

(def ^:private md-session-option-keys
  [:tools
   :skills
   :model
   :thinking-level
   :response-mode
   :temperature
   :logprobs
   :top-logprobs
   :prompt-component-selection])

(defn- invalid
  [message]
  {:error message})

(defn- trim-non-empty-string
  [x]
  (some-> x str str/trim not-empty))

(defn- parse-edn-string
  [raw]
  (try
    {:ok (edn/read-string raw)}
    (catch Exception e
      (invalid (str "Invalid EDN workflow definition: " (.getMessage e))))))

(defn- unknown-frontmatter-keys
  [frontmatter]
  (->> (keys frontmatter)
       (remove allowed-md-frontmatter-keys)
       vec
       not-empty))

(defn- unsupported-frontmatter-error
  [frontmatter]
  (when-let [unknown-keys (unknown-frontmatter-keys frontmatter)]
    (invalid (str "Unsupported markdown workflow frontmatter keys: "
                  (pr-str unknown-keys)))))

(defn- prompt-mode-error
  [frontmatter]
  (when (contains? frontmatter :prompt-mode)
    (invalid "Unsupported markdown workflow frontmatter key: :prompt-mode")))

(defn- body-starts-with-edn-map?
  [body]
  (str/starts-with? (str/triml (or body "")) "{"))

(defn- single-step-frontmatter
  [frontmatter]
  (reduce (fn [acc key]
            (if (contains? frontmatter key)
              (assoc acc key (get frontmatter key))
              acc))
          {}
          md-session-option-keys))

(defn parse-markdown-workflow-file
  "Parse a single-step markdown workflow file.

   Returns:
   {:workflow-kind :single-step-markdown
    :name string
    :description string
    :session-config map
    :body string}

   Returns `{:error ...}` for invalid frontmatter/body authoring."
  [raw]
  (let [{:keys [frontmatter body]} (pt/extract-frontmatter (or raw ""))
        name (trim-non-empty-string (:name frontmatter))
        description (trim-non-empty-string (:description frontmatter))
        body-text (some-> body str/trim not-empty)]
    (cond
      (nil? name)
      (invalid "Missing required frontmatter key: name")

      (nil? description)
      (invalid "Missing required frontmatter key: description")

      (prompt-mode-error frontmatter)
      (prompt-mode-error frontmatter)

      (unsupported-frontmatter-error frontmatter)
      (unsupported-frontmatter-error frontmatter)

      (nil? body-text)
      (invalid "Standalone markdown workflow body must not be empty")

      (body-starts-with-edn-map? body-text)
      (invalid "Markdown workflow body must not begin with an EDN workflow definition block")

      :else
      {:workflow-kind :single-step-markdown
       :name name
       :description description
       :session-config (single-step-frontmatter frontmatter)
       :body body-text})))

(defn parse-edn-workflow-file
  "Parse a multi-step EDN workflow file.

   Returns:
   {:workflow-kind :multi-step-edn
    :config map}

   Returns `{:error ...}` on invalid EDN or non-map root."
  [raw]
  (let [{config :ok error :error} (parse-edn-string (or raw ""))]
    (cond
      error
      {:error error}

      (not (map? config))
      (invalid "Workflow EDN file must contain a map root")

      :else
      {:workflow-kind :multi-step-edn
       :config config})))

(defn parse-workflow-file
  "Parse a workflow file by extension.

   `file-kind` must be `:md` or `:edn`."
  [file-kind raw]
  (case file-kind
    :md (parse-markdown-workflow-file raw)
    :edn (parse-edn-workflow-file raw)
    (invalid (str "Unsupported workflow file kind: " (pr-str file-kind)))))
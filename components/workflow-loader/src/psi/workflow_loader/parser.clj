(ns psi.workflow-loader.parser
  "Parse workflow definition files by explicit file kind.

   Supported authored file kinds:
   - `.md`  — single-step markdown workflow with required frontmatter + body
   - `.edn` — multi-step target-authored workflow definition

   Parsers return normalized authored maps or `{:error ...}`."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [psi.prompt-assets.prompt-templates :as pt]
   [psi.workflow-registry.session-profile-names :as profile-names]))

(def ^:private allowed-md-frontmatter-keys
  #{:name
    :description
    :tools
    :skills
    :model
    :thinking-level
    :session-profile
    :response-mode
    :temperature
    :logprobs
    :top-logprobs
    :prompt-component-selection
    :vars})

(def ^:private md-session-option-keys
  [:tools
   :skills
   :model
   :thinking-level
   :session-profile
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

(defn- normalize-session-profile
  [value]
  (cond
    (keyword? value)
    value

    (string? value)
    (or (profile-names/normalize-profile-name-token value) value)

    :else
    value))

(defn- session-option-value
  [frontmatter key]
  (let [value (get frontmatter key)]
    (case key
      :session-profile (normalize-session-profile value)
      value)))

(defn- single-step-frontmatter
  [frontmatter]
  (reduce (fn [acc key]
            (if (contains? frontmatter key)
              (assoc acc key (session-option-value frontmatter key))
              acc))
          {}
          md-session-option-keys))

(defn- strip-yaml-single-quotes
  "Strip surrounding YAML single-quote delimiters if present.

   Scope: only the outer `'...'` delimiters are removed. Interior `''` escape
   sequences (YAML's way of encoding a literal single-quote inside a
   single-quoted string) are NOT unescaped. This is safe in practice because
   `vars:` values use EDN syntax where string literals use double-quotes, so
   interior single-quotes never appear."
  [s]
  (if (and (str/starts-with? s "'") (str/ends-with? s "'") (> (count s) 1))
    (subs s 1 (dec (count s)))
    s))

(defn- parse-vars-frontmatter
  "Parse the `vars:` frontmatter value (a scalar EDN string) into a map.
   Returns {:ok map} or {:error string}."
  [raw-vars]
  (if (nil? raw-vars)
    {:ok nil}
    (let [edn-str (strip-yaml-single-quotes raw-vars)
          {parsed :ok parse-error :error} (parse-edn-string edn-str)]
      (cond
        parse-error
        (invalid (str "Invalid `vars:` frontmatter: " parse-error))

        (not (map? parsed))
        (invalid "Invalid `vars:` frontmatter: value must be an EDN map")

        :else
        (let [bad-froms (->> (vals parsed)
                             (map :from)
                             (remove #{:workflow-input :workflow-original})
                             seq)]
          (if bad-froms
            (invalid (str "Invalid `vars:` frontmatter: unsupported :from values "
                          (pr-str (vec bad-froms))
                          "; allowed: :workflow-input, :workflow-original"))
            {:ok parsed}))))))

(defn parse-markdown-workflow-file
  "Parse a single-step markdown workflow file.

   Returns:
   {:workflow-kind :single-step-markdown
    :name string
    :description string
    :session-config map
    :body string
    :vars map-or-nil}

   Returns `{:error ...}` for invalid frontmatter/body authoring."
  [raw]
  (let [{:keys [frontmatter body]} (pt/extract-frontmatter (or raw ""))
        name (trim-non-empty-string (:name frontmatter))
        description (trim-non-empty-string (:description frontmatter))
        body-text (some-> body str/trim not-empty)
        {parsed-vars :ok vars-error :error} (parse-vars-frontmatter (:vars frontmatter))]
    (cond
      (nil? name)
      (invalid "Missing required frontmatter key: name")

      (nil? description)
      (invalid "Missing required frontmatter key: description")

      (prompt-mode-error frontmatter)
      (prompt-mode-error frontmatter)

      (unsupported-frontmatter-error frontmatter)
      (unsupported-frontmatter-error frontmatter)

      vars-error
      (invalid vars-error)

      (nil? body-text)
      (invalid "Standalone markdown workflow body must not be empty")

      (body-starts-with-edn-map? body-text)
      (invalid "Markdown workflow body must not begin with an EDN workflow definition block")

      :else
      {:workflow-kind :single-step-markdown
       :name name
       :description description
       :session-config (single-step-frontmatter frontmatter)
       :body body-text
       :vars parsed-vars})))

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

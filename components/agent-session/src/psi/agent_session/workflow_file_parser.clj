(ns psi.agent-session.workflow-file-parser
  "Parse unified workflow definition files.

   File format:
   1. YAML frontmatter (--- fenced) — name, description
   2. Optional EDN config block — first non-whitespace char is `{`
   3. Body text — system prompt (single-step) or framing prompt (multi-step)

   Returns {:name :description :config :body} on success,
   {:error ...} on failure."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [psi.prompt-assets.prompt-templates :as pt]))

(defn- parse-edn-prefix
  "When `text` starts with `{` (after trimming leading whitespace),
   read the first EDN form and return {:config <map> :remainder <string>}.
   Returns nil when text does not start with `{`."
  [text]
  (let [trimmed (str/triml text)]
    (when (str/starts-with? trimmed "{")
      (let [rdr (java.io.PushbackReader. (java.io.StringReader. trimmed))
            config (edn/read rdr)]
        (when (map? config)
          (let [consumed (- (count trimmed)
                            (let [sb (StringBuilder.)]
                              (loop []
                                (let [ch (.read rdr)]
                                  (if (neg? ch)
                                    (count (str sb))
                                    (do (.append sb (char ch))
                                        (recur)))))))
                remainder (str/trim (subs trimmed consumed))]
            {:config config
             :remainder remainder}))))))

(defn parse-workflow-file
  "Parse a unified workflow definition file.

   Returns a map with:
   - :name        — workflow name (from frontmatter, required)
   - :description — human-readable description (from frontmatter, required)
   - :config      — EDN config map or nil
   - :body        — prompt/system-prompt text or nil

   Returns {:error <string>} when the file cannot be parsed."
  [raw]
  (let [{:keys [frontmatter body]} (pt/extract-frontmatter (or raw ""))
        name        (some-> (:name frontmatter) str str/trim not-empty)
        description (some-> (:description frontmatter) str str/trim not-empty)]
    (cond
      (nil? name)
      {:error "Missing required frontmatter key: name"}

      (nil? description)
      {:error "Missing required frontmatter key: description"}

      :else
      (let [edn-result (when (seq body)
                         (try
                           (parse-edn-prefix body)
                           (catch Exception e
                             {:parse-error (str "Invalid EDN config: " (.getMessage e))})))]
        (cond
          (:parse-error edn-result)
          {:error (:parse-error edn-result)}

          edn-result
          {:name        name
           :description description
           :config      (:config edn-result)
           :body        (not-empty (:remainder edn-result))}

          :else
          {:name        name
           :description description
           :config      nil
           :body        (not-empty (str/trim (or body "")))})))))

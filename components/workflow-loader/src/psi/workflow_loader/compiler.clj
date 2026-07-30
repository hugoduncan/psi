(ns psi.workflow-loader.compiler
  "Compile parsed workflow file data into canonical target-authored workflow definitions."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.workflow-loader.parser :as parser]
   [psi.workflow-registry.definition :as workflow-definition]))

(def ^:private prompt-defining-step-keys
  #{:contributions :system-prompt})

(def ^:private markdown-session-config-keys
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

;;; Standard var source specs auto-wired for any .md workflow body.
(def ^:private standard-vars
  {"input"    {:from :workflow-input :path [:input]}
   "original" {:from :workflow-original}})

;;; Pattern matching {{varname}} tokens: leading letter, then letters/digits/underscores/hyphens.
(def ^:private template-var-pattern
  #"\{\{([a-zA-Z][a-zA-Z0-9_-]*)\}\}")

(defn- invalid
  [message]
  {:error message})

(defn- target-authored-config?
  [config]
  (and (map? config)
       (vector? (:steps config))
       (every? map? (:steps config))
       (every? #(contains? % :type) (:steps config))))

(defn- scan-template-vars
  "Return the set of var names referenced as {{varname}} tokens in body."
  [body]
  (->> (re-seq template-var-pattern body)
       (map second)
       set))

(defn- markdown-body->contribution
  "Produce a template contribution for body, auto-wiring standard vars and
   merging declared-vars. Throws ex-info on unknown {{varname}} tokens."
  ([body] (markdown-body->contribution body nil))
  ([body declared-vars]
   (let [referenced-names (scan-template-vars body)
         merged-vars (merge declared-vars standard-vars)
         wired-vars (select-keys merged-vars referenced-names)
         unknown (seq (remove (set (keys merged-vars)) referenced-names))]
     (when unknown
       (throw (ex-info (str "Unknown {{varname}} tokens in workflow body: "
                            (pr-str (sort unknown))
                            ". Declare them in the `vars:` frontmatter or use"
                            " standard vars {{input}} or {{original}}.")
                       {:unknown-vars (sort unknown)})))
     [{:type :template
       :text body
       :vars wired-vars}])))

(defn- markdown-session-step
  [{:keys [session-config body vars]}]
  (merge {:name "step"
          :type :session
          :contributions (markdown-body->contribution body vars)}
         session-config))

(defn- compile-markdown-workflow-file
  [{:keys [name description advertise source-path] :as parsed}]
  {:definition {:definition-id name
                :name name
                :summary description
                :description description
                :advertise advertise
                :steps [(markdown-session-step parsed)]
                :workflow-file-meta (cond-> {:file-kind :md}
                                      source-path (assoc :source-path source-path))}})

(defn- slurp-workflow-file
  [path]
  (try
    {:ok (slurp path)}
    (catch Exception e
      (invalid (str "Failed to read referenced prompt workflow `"
                    path
                    "`: "
                    (.getMessage e))))))

(defn- file-kind-from-path
  [path]
  (cond
    (str/ends-with? path ".md") :md
    (str/ends-with? path ".edn") :edn
    :else nil))

(defn- relative-prompt-workflow-path?
  [prompt-workflow]
  (and (string? prompt-workflow)
       (not (str/blank? prompt-workflow))
       (let [f (io/file prompt-workflow)]
         (and (not (.isAbsolute f))
              (not-any? #{".."} (str/split prompt-workflow #"/"))))))

(defn- resolve-prompt-workflow-path
  [workflow-path prompt-workflow]
  (.getCanonicalPath
   (io/file (.getParentFile (io/file workflow-path)) prompt-workflow)))

(defn- read-prompt-workflow
  [workflow-path prompt-workflow]
  (let [resolved-path (resolve-prompt-workflow-path workflow-path prompt-workflow)]
    (cond
      (not= :md (file-kind-from-path resolved-path))
      (invalid (str "`:prompt-workflow` must reference a .md file, got `"
                    prompt-workflow
                    "`"))

      (not (.exists (io/file resolved-path)))
      (invalid (str "Referenced prompt workflow file not found: " (pr-str prompt-workflow)))

      :else
      (let [{raw :ok read-error :error} (slurp-workflow-file resolved-path)]
        (if read-error
          {:error (:error read-error)}
          (let [parsed (parser/parse-workflow-file :md raw)]
            (if (:error parsed)
              (invalid (str "Referenced prompt workflow `"
                            prompt-workflow
                            "` is invalid: "
                            (:error parsed)))
              {:ok (assoc parsed :source-path resolved-path)})))))))

(defn- prompt-source-conflict?
  [step]
  (some #(contains? step %) prompt-defining-step-keys))

(defn- merge-markdown-session-config
  "Merge session-config keys from markdown-session-config into step,
   giving step-level keys precedence (step wins on conflict)."
  [step markdown-session-config]
  (merge (select-keys markdown-session-config markdown-session-config-keys) step))

(defn- compile-prompt-workflow-step
  [workflow-path step]
  (let [prompt-workflow (:prompt-workflow step)]
    (cond
      (not= :session (:type step))
      (invalid "`:prompt-workflow` is allowed only on `:session` steps")

      (not (string? prompt-workflow))
      (invalid "`:prompt-workflow` must be a relative .md file string")

      (not (relative-prompt-workflow-path? prompt-workflow))
      (invalid "`:prompt-workflow` must be a relative .md path within the consuming workflow directory")

      (prompt-source-conflict? step)
      (invalid "`:prompt-workflow` cannot be combined with another authored prompt source")

      :else
      (let [{referenced :ok reference-error :error}
            (read-prompt-workflow workflow-path prompt-workflow)]
        (if reference-error
          {:error reference-error}
          {:ok (-> step
                   (dissoc :prompt-workflow)
                   (merge-markdown-session-config (:session-config referenced))
                   (assoc :contributions (markdown-body->contribution (:body referenced)
                                                                      (:vars referenced))))})))))

(defn- compile-prompt-group
  "Resolve one authored prompt-group's body into `:contributions` (task 226).

   Within a group the body is `:prompt-workflow` XOR `:contributions` (mirrors
   the step-level rule): both => error, neither => error. Per-prompt session
   config is out of scope; session config is shared at the step level, so a
   group's `:prompt-workflow` contributes only its markdown body."
  [workflow-path group]
  (let [has-prompt-workflow? (contains? group :prompt-workflow)
        has-contributions? (contains? group :contributions)]
    (cond
      (and has-prompt-workflow? has-contributions?)
      (invalid "A prompt-group must define `:prompt-workflow` XOR `:contributions`, not both")

      (and (not has-prompt-workflow?) (not has-contributions?))
      (invalid "A prompt-group must define `:prompt-workflow` or `:contributions`")

      has-prompt-workflow?
      (let [prompt-workflow (:prompt-workflow group)]
        (cond
          (not (string? prompt-workflow))
          (invalid "A prompt-group `:prompt-workflow` must be a relative .md file string")

          (not (relative-prompt-workflow-path? prompt-workflow))
          (invalid "A prompt-group `:prompt-workflow` must be a relative .md path within the consuming workflow directory")

          :else
          (let [{referenced :ok reference-error :error}
                (read-prompt-workflow workflow-path prompt-workflow)]
            (if reference-error
              {:error reference-error}
              {:ok (-> group
                       (dissoc :prompt-workflow)
                       (assoc :contributions (markdown-body->contribution (:body referenced)
                                                                          (:vars referenced))))}))))

      :else
      {:ok group})))

(defn- compile-prompts-step
  "Resolve an authored multi-prompt `:prompts` session step (task 226).

   Validates step-level precedence (`:prompts` excludes a step-level
   `:contributions`/`:system-prompt`/`:prompt-workflow` prompt source) and
   resolves each prompt-group's body into `:contributions`."
  [workflow-path step]
  (cond
    (not= :session (:type step))
    (invalid "`:prompts` is allowed only on `:session` steps")

    (prompt-source-conflict? step)
    (invalid "`:prompts` cannot be combined with a step-level `:contributions`/`:system-prompt` prompt source")

    (contains? step :prompt-workflow)
    (invalid "`:prompts` cannot be combined with a step-level `:prompt-workflow`")

    :else
    (loop [remaining (:prompts step)
           compiled []]
      (if (empty? remaining)
        {:ok (assoc step :prompts compiled)}
        (let [{compiled-group :ok group-error :error}
              (compile-prompt-group workflow-path (first remaining))]
          (if group-error
            {:error group-error}
            (recur (rest remaining) (conj compiled compiled-group))))))))

(defn- compile-edn-steps
  [workflow-path steps]
  (loop [remaining steps
         compiled []]
    (if (empty? remaining)
      {:ok compiled}
      (let [step (first remaining)]
        (cond
          (contains? step :prompts)
          (let [{compiled-step :ok step-error :error}
                (compile-prompts-step workflow-path step)]
            (if step-error
              {:error step-error}
              (recur (rest remaining) (conj compiled compiled-step))))

          (contains? step :prompt-workflow)
          (let [{compiled-step :ok step-error :error}
                (compile-prompt-workflow-step workflow-path step)]
            (if step-error
              {:error step-error}
              (recur (rest remaining) (conj compiled compiled-step))))

          :else
          (recur (rest remaining) (conj compiled step)))))))

(defn- compile-edn-workflow-file
  [{:keys [config source-path]}]
  (cond
    (not (target-authored-config? config))
    {:error "Workflow EDN files must define target-authored `{:steps [...]}` config"}

    (not (string? (:name config)))
    {:error "Workflow EDN files must define top-level `:name` as a string"}

    (str/blank? (:name config))
    {:error "Workflow EDN files must define top-level `:name` as a non-blank string"}

    (not (string? (:description config)))
    {:error "Workflow EDN files must define top-level `:description` as a string"}

    (str/blank? (:description config))
    {:error "Workflow EDN files must define top-level `:description` as a non-blank string"}

    :else
    ;; `:advertise` passes through implicitly from `config`: when authored it is
    ;; carried verbatim; when absent it stays absent (nil). This is deliberately
    ;; asymmetric with the markdown path, where the parser coerces `:advertise`
    ;; to an explicit boolean (default `true`) before compilation. Behaviour is
    ;; identical because the prompt-contribution filter keys on `false?`, so nil
    ;; and true both advertise.
    (let [{compiled-steps :ok step-error :error}
          (compile-edn-steps source-path (:steps config))
          workflow-definition (cond-> (-> config
                                          (assoc :steps compiled-steps
                                                 :definition-id (or (:definition-id config)
                                                                    (:name config)))
                                          (update :workflow-file-meta #(merge {:file-kind :edn} %)))
                                source-path (update :workflow-file-meta assoc :source-path source-path))]
      (cond
        step-error
        {:error step-error}

        (not (workflow-definition/target-authored-workflow-definition? workflow-definition))
        {:error "Target-authored workflow file must define `{:steps [...]}`"}

        :else
        {:definition workflow-definition}))))

(defn compile-workflow-file
  "Compile a parsed workflow file into a canonical target-authored workflow definition.

   Returns {:definition <map>} on success, {:error <string>} on failure."
  [{:keys [workflow-kind error] :as parsed}]
  (try
    (cond
      error
      {:error error}

      (= workflow-kind :single-step-markdown)
      (compile-markdown-workflow-file parsed)

      (= workflow-kind :multi-step-edn)
      (compile-edn-workflow-file parsed)

      :else
      {:error "Unknown parsed workflow kind"})
    (catch clojure.lang.ExceptionInfo e
      {:error (.getMessage e)})))

(defn compile-workflow-files
  "Compile a seq of parsed workflow files into canonical definitions.
   Returns {:definitions [<def> ...] :errors [{:name ... :error ...} ...]}."
  [parsed-files]
  (reduce (fn [acc parsed]
            (let [{:keys [definition error]} (compile-workflow-file parsed)]
              (if error
                (update acc :errors conj {:name (:name parsed)
                                          :error error
                                          :source-path (:source-path parsed)})
                (update acc :definitions conj definition))))
          {:definitions [] :errors []}
          parsed-files))

(defn validate-step-references
  [_definitions]
  {:valid? true})

(defn validate-no-name-collisions
  [definitions]
  (let [freqs (frequencies (map :name definitions))
        dups (into [] (comp (filter #(> (val %) 1)) (map key)) freqs)]
    (if (seq dups)
      {:valid? false :duplicates dups}
      {:valid? true})))

(defn validate-judge-routing
  [_definitions]
  {:valid? true})

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
   :response-mode
   :temperature
   :logprobs
   :top-logprobs
   :prompt-component-selection])

(defn- invalid
  [message]
  {:error message})

(defn- target-authored-config?
  [config]
  (and (map? config)
       (vector? (:steps config))
       (every? map? (:steps config))
       (every? #(contains? % :type) (:steps config))))

(defn- markdown-body->contribution
  [body]
  [{:type :template
    :text body
    :vars {}}])

(defn- markdown-session-step
  [{:keys [session-config body]}]
  (cond-> {:name "step"
           :type :session
           :contributions (markdown-body->contribution body)}
    true (merge session-config)))

(defn- compile-markdown-workflow-file
  [{:keys [name description source-path] :as parsed}]
  {:definition (cond-> {:definition-id name
                        :name name
                        :summary description
                        :description description
                        :steps [(markdown-session-step parsed)]}
                 source-path (assoc :workflow-file-meta {:source-path source-path
                                                         :file-kind :md}))})

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

(defn- resolve-prompt-workflow-path
  [workflow-path prompt-workflow]
  (.getCanonicalPath
   (io/file (.getParentFile (io/file workflow-path)) prompt-workflow)))

(defn- read-prompt-workflow
  [workflow-path prompt-workflow]
  (let [resolved-path (resolve-prompt-workflow-path workflow-path prompt-workflow)
        file-kind (file-kind-from-path resolved-path)]
    (cond
      (nil? file-kind)
      (invalid (str "`:prompt-workflow` must reference a .md file: " (pr-str prompt-workflow)))

      (not= :md file-kind)
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
  [step markdown-session-config]
  (reduce (fn [acc key]
            (if (contains? acc key)
              acc
              (if (contains? markdown-session-config key)
                (assoc acc key (get markdown-session-config key))
                acc)))
          step
          markdown-session-config-keys))

(defn- compile-prompt-workflow-step
  [workflow-path step]
  (let [prompt-workflow (:prompt-workflow step)]
    (cond
      (not= :session (:type step))
      (invalid "`:prompt-workflow` is allowed only on `:session` steps")

      (not (string? prompt-workflow))
      (invalid "`:prompt-workflow` must be a relative .md file string")

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
                   (assoc :contributions (markdown-body->contribution (:body referenced))))})))))

(defn- compile-edn-steps
  [workflow-path steps]
  (loop [remaining steps
         compiled []]
    (if (empty? remaining)
      {:ok compiled}
      (let [step (first remaining)]
        (if (contains? step :prompt-workflow)
          (let [{compiled-step :ok step-error :error}
                (compile-prompt-workflow-step workflow-path step)]
            (if step-error
              {:error step-error}
              (recur (rest remaining) (conj compiled compiled-step))))
          (recur (rest remaining) (conj compiled step)))))))

(defn- compile-edn-workflow-file
  [{:keys [config source-path]}]
  (cond
    (not (target-authored-config? config))
    {:error "Workflow EDN files must define target-authored `{:steps [...]}` config"}

    :else
    (let [{compiled-steps :ok step-error :error}
          (compile-edn-steps source-path (:steps config))
          workflow-definition (cond-> (assoc config :steps compiled-steps)
                                source-path (update :workflow-file-meta #(merge {:source-path source-path
                                                                                 :file-kind :edn}
                                                                                %)))]
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
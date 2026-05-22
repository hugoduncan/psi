(ns psi.prompt-assets.skills
  "Skill discovery, parsing, validation, progressive disclosure, and invocation.

   Skills are self-contained capability packages the agent loads on-demand.
   A skill provides specialised workflows, setup instructions, helper scripts,
   and reference documentation for specific tasks.

   Implements the Agent Skills standard (https://agentskills.io/specification).

   Architecture
   ────────────
   Discovery loads skills from ordered sources (first-discovered wins):
     1. Global skills:  ~/.psi/agent/skills/
     2. Project skills: .psi/skills/
     3. Additional paths: CLI --skill <path>

   Discovery rules within each directory:
     - Direct .md children in the directory root
     - Recursive SKILL.md files under subdirectories

   Progressive disclosure:
     - Only name + description appear in the system prompt
     - Full SKILL.md content is loaded on-demand (read tool or /skill:name)
     - Skills with disable-model-invocation: true are hidden from the prompt
       but remain invocable via /skill:name

   Nullable pattern
   ────────────────
   All functions are pure or take explicit paths/data.
   No global state — skill registries live in the session data atom."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.prompt-assets.prompt-templates :as pt]
   [psi.skill-registry.registry :as skill-registry]))

;; ============================================================
;; Config
;; ============================================================

(def default-config
  {:global-skills-dirs  [(str (System/getProperty "user.home") "/.psi/agent/skills")]
   :project-skills-dirs [".psi/skills"]
   :name-max-length     64
   :description-max-length 1024
   :compatibility-max-length 500})

;; ============================================================
;; Frontmatter Extraction
;; ============================================================

;; Re-use the YAML frontmatter parser from prompt-templates
(def extract-frontmatter pt/extract-frontmatter)

;; ============================================================
;; Validation
;; ============================================================

(def ^:private name-pattern #"^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")

(defn validate-name
  "Validate a skill name per Agent Skills spec.
   Returns a map {:warnings [String] :errors [String]}.
   Name rules: lowercase a-z, 0-9, hyphens only.
   No leading/trailing hyphens, no consecutive hyphens.
   Must match parent directory name."
  [name parent-dir-name config]
  (let [warnings (cond-> []
                   (and name (> (count name) (:name-max-length config)))
                   (conj (str "Name exceeds " (:name-max-length config) " characters"))

                   (and name (not (re-matches name-pattern name)))
                   (conj "Name contains invalid characters (must be lowercase a-z, 0-9, hyphens only)")

                   (and name (str/includes? name "--"))
                   (conj "Name has consecutive hyphens")

                   (and name parent-dir-name (not= name parent-dir-name))
                   (conj (str "Name \"" name "\" doesn't match parent directory \"" parent-dir-name "\"")))]
    {:warnings warnings
     :errors   []}))

(defn validate-description
  "Validate a skill description.
   Missing description is fatal (only fatal validation error)."
  [description config]
  (cond
    (or (nil? description) (str/blank? description))
    {:warnings [] :errors ["Missing description"]}

    (> (count description) (:description-max-length config))
    {:warnings [(str "Description exceeds " (:description-max-length config) " characters")]
     :errors   []}

    :else
    {:warnings [] :errors []}))

(defn validate-skill
  "Validate a parsed skill. Returns {:valid Boolean :warnings [String] :errors [String]}.
   Only missing description is fatal."
  [parsed-skill config]
  (let [name-result (validate-name (:name parsed-skill) (:parent-dir-name parsed-skill) config)
        desc-result (validate-description (:description parsed-skill) config)
        all-warnings (into (:warnings name-result) (:warnings desc-result))
        all-errors   (into (:errors name-result) (:errors desc-result))]
    {:valid    (empty? all-errors)
     :warnings all-warnings
     :errors   all-errors}))

;; ============================================================
;; Parsing
;; ============================================================

(defn parse-skill-file
  "Parse a SKILL.md or .md skill file at `path`.
   Returns a parsed skill map or nil if the file doesn't exist.
   The returned map has raw frontmatter fields plus :parent-dir-name."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (let [raw                    (slurp f)
            {:keys [frontmatter
                    body]}         (extract-frontmatter raw)
            skill-dir              (.getParentFile f)
            parent-dir-name        (.getName skill-dir)
            name                   (or (:name frontmatter) parent-dir-name)
            description            (:description frontmatter)
            disable-model-invocation (= "true"
                                        (str (get frontmatter :disable-model-invocation)))]
        {:name                     name
         :description              description
         :lambda-description       (:lambda frontmatter)
         :parent-dir-name          parent-dir-name
         :file-path                (.getAbsolutePath f)
         :base-dir                 (.getAbsolutePath skill-dir)
         :license                  (:license frontmatter)
         :compatibility            (:compatibility frontmatter)
         :allowed-tools            (when-let [at (:allowed-tools frontmatter)]
                                     (str/split (str/trim at) #"\s+"))
         :disable-model-invocation disable-model-invocation
         :body                     body}))))

;; ============================================================
;; Skill construction (from parsed + validated)
;; ============================================================

(defn ->skill
  "Convert a parsed skill map + source into the canonical Skill map
   stored in session data."
  [parsed source]
  (cond-> {:name                     (:name parsed)
           :description              (:description parsed)
           :file-path                (:file-path parsed)
           :base-dir                 (:base-dir parsed)
           :source                   source
           :disable-model-invocation (:disable-model-invocation parsed)}
    (:lambda-description parsed) (assoc :lambda-description (:lambda-description parsed))))

;; ============================================================
;; Discovery — directory scanning
;; ============================================================

(defn- skill-file?
  "True if this is a loadable skill file."
  [^java.io.File f]
  (and (.isFile f)
       (str/ends-with? (.getName f) ".md")))

(defn- load-skill-from-file
  "Load and validate a single skill file. Returns {:skill Skill :diagnostics []}."
  [path source config]
  (if-let [parsed (parse-skill-file path)]
    (let [validation (validate-skill parsed config)]
      (if (:valid validation)
        {:skill       (->skill parsed source)
         :diagnostics (mapv (fn [w] {:type :warning :message w :path path})
                            (:warnings validation))}
        {:skill       nil
         :diagnostics (into (mapv (fn [e] {:type :error :message e :path path})
                                  (:errors validation))
                            (mapv (fn [w] {:type :warning :message w :path path})
                                  (:warnings validation)))}))
    {:skill nil :diagnostics []}))

(defn load-skills-from-dir
  "Load skills from a directory.

   Discovery rules:
     - Direct .md children in the root (when include-root-files? is true)
     - Recursive SKILL.md under subdirectories"
  ([dir source] (load-skills-from-dir dir source true))
  ([dir source include-root-files?]
   (load-skills-from-dir dir source include-root-files? (merge default-config {})))
  ([dir source include-root-files? config]
   (let [d (io/file dir)]
     (if-not (and (.exists d) (.isDirectory d))
       {:skills [] :diagnostics []}
       (let [entries (.listFiles d)
             results
             (for [^java.io.File entry entries
                   :when (not (str/starts-with? (.getName entry) "."))
                   :when (not= "node_modules" (.getName entry))]
               (cond
                 ;; Directory: recurse looking for SKILL.md
                 (.isDirectory entry)
                 (let [skill-md (io/file entry "SKILL.md")]
                   (if (.exists skill-md)
                     ;; Found SKILL.md in this subdir.
                     ;; Recurse into child *directories* only (not the same dir)
                     ;; to find nested skills without re-loading this SKILL.md.
                     (let [result    (load-skill-from-file
                                      (.getAbsolutePath skill-md) source config)
                           children  (.listFiles entry)
                           sub-results
                           (for [^java.io.File child children
                                 :when (.isDirectory child)
                                 :when (not (str/starts-with? (.getName child) "."))
                                 :when (not= "node_modules" (.getName child))]
                             (load-skills-from-dir
                              (.getAbsolutePath child) source false config))]
                       {:skills      (into (if (:skill result) [(:skill result)] [])
                                           (mapcat :skills sub-results))
                        :diagnostics (into (:diagnostics result)
                                           (mapcat :diagnostics sub-results))})
                     ;; No SKILL.md — keep recursing
                     (load-skills-from-dir (.getAbsolutePath entry) source false config)))

                 ;; Direct .md file in root
                 (and include-root-files? (skill-file? entry))
                 (load-skill-from-file (.getAbsolutePath entry) source config)

                 ;; SKILL.md in a non-root directory
                 (and (not include-root-files?)
                      (= "SKILL.md" (.getName entry))
                      (.isFile entry))
                 (load-skill-from-file (.getAbsolutePath entry) source config)

                 :else nil))
             flat (remove nil? results)]
         {:skills      (vec (mapcat #(if (:skill %) [(:skill %)] (:skills %)) flat))
          :diagnostics (vec (mapcat #(or (:diagnostics %) []) flat))})))))

;; ============================================================
;; Discovery — multi-source
;; ============================================================

(defn discover-skills
  "Discover skills from all configured sources.
   Returns {:skills [Skill] :diagnostics [Diagnostic]}.

   First-discovered name wins; collisions produce a :collision diagnostic.

   `opts` keys:
     :global-skills-dirs  — seq of global skill directories
     :project-skills-dirs — seq of project skill directories
     :extra-paths         — seq of additional file/directory paths
     :disabled            — if true, only load extra-paths (--no-skills)
     :config              — validation config overrides"
  ([] (discover-skills {}))
  ([opts]
   (let [config     (merge default-config (:config opts))
         skill-map  (atom {})
         all-diags  (atom [])
         collisions (atom [])

         add-skills!
         (fn [{:keys [skills diagnostics]}]
           (swap! all-diags into diagnostics)
           (doseq [skill skills]
             (if-let [existing (get @skill-map (:name skill))]
               (swap! collisions conj
                      {:type    :collision
                       :message (str "Skill name collision: '" (:name skill)
                                     "' already loaded from " (:file-path existing))
                       :path    (:file-path skill)})
               (swap! skill-map assoc (:name skill) skill))))]

     ;; 1. Global skills (unless disabled)
     (when-not (:disabled opts)
       (doseq [dir (or (:global-skills-dirs opts) (:global-skills-dirs default-config))]
         (add-skills! (load-skills-from-dir dir :user true config))))

     ;; 2. Project skills (unless disabled)
     (when-not (:disabled opts)
       (doseq [dir (or (:project-skills-dirs opts) (:project-skills-dirs default-config))]
         (add-skills! (load-skills-from-dir dir :project true config))))

     ;; 3. Extra paths (always loaded, even when disabled)
     (doseq [raw-path (:extra-paths opts)]
       (let [f (io/file raw-path)]
         (cond
           (not (.exists f))
           (swap! all-diags conj {:type :warning :message "Skill path does not exist" :path raw-path})

           (.isDirectory f)
           (add-skills! (load-skills-from-dir raw-path :path true config))

           (and (.isFile f) (str/ends-with? (.getName f) ".md"))
           (add-skills! (let [result (load-skill-from-file raw-path :path config)]
                          {:skills      (if (:skill result) [(:skill result)] [])
                           :diagnostics (:diagnostics result)}))

           :else
           (swap! all-diags conj {:type :warning :message "Skill path is not a markdown file" :path raw-path}))))

     {:skills      (vec (vals @skill-map))
      :diagnostics (into (vec @all-diags) @collisions)})))

;; ============================================================
;; Progressive Disclosure — system prompt formatting
;; ============================================================

(defn- escape-xml
  "Escape XML special characters in a string."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn format-skills-for-prompt
  "Format skills for inclusion in a system prompt.
   Uses XML format per Agent Skills standard.

   Skills with disable-model-invocation=true are excluded from the prompt
   (they can only be invoked explicitly via /skill:name commands)."
  [skills]
  (let [visible (remove :disable-model-invocation (skill-registry/all-skills skills))]
    (if (empty? visible)
      ""
      (let [lines (into
                   ["\n\nThe following skills provide specialized instructions for specific tasks."
                    "Use the read tool to load a skill's file when the task matches its description."
                    "When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands."
                    ""
                    "<available_skills>"]
                   (mapcat (fn [skill]
                             ["  <skill>"
                              (str "    <name>" (escape-xml (:name skill)) "</name>")
                              (str "    <description>" (escape-xml (:description skill)) "</description>")
                              (str "    <location>" (escape-xml (:file-path skill)) "</location>")
                              "  </skill>"])
                           visible))]
        (str/join "\n" (conj lines "</available_skills>"))))))

(defn format-skills-for-prompt-lambda
  "Format skills for lambda-mode system prompt.
   Uses compact lambda notation. Skills with a :lambda-description
   frontmatter field use that; otherwise falls back to name → description."
  [skills]
  (let [visible (remove :disable-model-invocation (skill-registry/all-skills skills))]
    (when (seq visible)
      (str "\n\nλ skills. match(task, description) → read(file) | resolve(relative_path, parent(file))\n"
           (str/join "\n"
                     (map (fn [skill]
                            (if-let [ld (:lambda-description skill)]
                              (str "  " (:name skill) " → " ld " @ " (:file-path skill))
                              (str "  " (:name skill) " → " (:description skill) " @ " (:file-path skill))))
                          visible))))))

;;; Invocation — /skill:name expansion

(defn parse-skill-command
  "Parse /skill:name args into {:skill-name String :args-text String}.
   Returns nil if text doesn't match /skill:name pattern."
  [text]
  (when (and (string? text) (str/starts-with? text "/skill:"))
    (let [after-prefix (subs text 7)  ;; after "/skill:"
          space-idx    (str/index-of after-prefix " ")]
      (if space-idx
        {:skill-name (subs after-prefix 0 space-idx)
         :args-text  (str/trim (subs after-prefix (inc space-idx)))}
        {:skill-name after-prefix
         :args-text  ""}))))

(defn find-skill
  "Find a skill by name in `skills` vector. Returns nil if not found."
  [skills name]
  (skill-registry/find-skill skills name))

(defn invoke-skill
  "Expand a /skill:name command.
   Returns {:content String :skill-name String} on match, or nil.
   Reads the full SKILL.md content and wraps it in <skill> XML.

   Note: unlike progressive disclosure (prompt uses only name+description),
   invocation loads the entire SKILL.md file content."
  [skills text]
  (when-let [{:keys [skill-name args-text]} (parse-skill-command text)]
    (when-let [skill (find-skill skills skill-name)]
      (let [content (try (slurp (:file-path skill)) (catch Exception _ nil))]
        (when content
          {:content    (str "<skill name=\"" (escape-xml skill-name)
                            "\" location=\"" (escape-xml (:file-path skill))
                            "\">\n" content "\n</skill>\n\n" args-text)
           :skill-name skill-name})))))

;; ============================================================
;; Introspection
;; ============================================================

(defn skill-summary
  "Return an introspection summary of all skills."
  [skills]
  (let [ordered-skills (skill-registry/all-skills skills)]
    {:skill-count       (count ordered-skills)
     :visible-count     (count (remove :disable-model-invocation ordered-skills))
     :hidden-count      (count (filter :disable-model-invocation ordered-skills))
     :skills            (mapv (fn [s]
                                {:name                     (:name s)
                                 :description              (:description s)
                                 :source                   (:source s)
                                 :disable-model-invocation (:disable-model-invocation s)})
                              ordered-skills)}))

(defn skill-names
  "Return a vector of skill name strings."
  [skills]
  (skill-registry/skill-names skills))

(defn skills-by-source
  "Group skills by their source with each source group in canonical skill-name order."
  [skills]
  (group-by :source (skill-registry/all-skills skills)))

(defn visible-skills
  "Return skills that are available to the model (not disabled)."
  [skills]
  (vec (remove :disable-model-invocation (skill-registry/all-skills skills))))

(defn hidden-skills
  "Return skills with disable-model-invocation=true."
  [skills]
  (vec (filter :disable-model-invocation (skill-registry/all-skills skills))))

(defn enrich-skill
  "Add derived fields to a Skill map for introspection."
  [skill]
  (assoc skill
         :is-available-to-model (not (:disable-model-invocation skill))))

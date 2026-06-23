(ns psi.prompt-assets.skills
  "Skill discovery, parsing, validation, progressive disclosure, and invocation.

   Skills are self-contained capability packages the agent loads on-demand.
   A skill provides specialised workflows, setup instructions, helper scripts,
   and reference documentation for specific tasks.

   Implements the Agent Skills standard (https://agentskills.io/specification).

   Architecture
   ────────────
   Discovery loads skills from explicit precedence classes:
     1. Additional paths: CLI --skill <path>
     2. Project skills: .psi/skills/
     3. Global skills:  ~/.psi/agent/skills/
     4. Built-in packaged skills: psi jar resources materialized under ~/.psi/agent/

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
   [psi.skill-registry.registry :as skill-registry]
   [psi.version :as version])
  (:import
   (java.io File)
   (java.nio.file Files Path StandardCopyOption)
   (java.nio.file.attribute FileAttribute)
   (java.security MessageDigest)))

;; ============================================================
;; Config
;; ============================================================

(def default-config
  {:global-skills-dirs  [(str (System/getProperty "user.home") "/.psi/agent/skills")]
   :project-skills-dirs [".psi/skills"]
   :built-in-cache-dir  (str (System/getProperty "user.home") "/.psi/agent/built-in-skills")
   :built-in-resource-root "psi/skills"
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

;; Re-use the shared boolean-frontmatter coercion from prompt-templates
(def frontmatter-flag pt/frontmatter-flag)

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
            disable-model-invocation (frontmatter-flag
                                      (get frontmatter :disable-model-invocation) false)
            advertise                (frontmatter-flag
                                      (get frontmatter :advertise) true)]
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
         :advertise                advertise
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
           :disable-model-invocation (:disable-model-invocation parsed)
           :advertise                (:advertise parsed)}
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
;; Built-in packaged skill materialization
;; ============================================================

(defn- canonical-file-path
  [path]
  (.getCanonicalPath (io/file path)))

(defn- bytes->hex
  [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- sha256-hex
  [s]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (str s) "UTF-8"))
    (bytes->hex (.digest digest))))

(defn- built-in-snapshot-id
  [resource-root resource-paths]
  (subs (sha256-hex (str (version/version-string)
                         "|"
                         resource-root
                         "|"
                         (str/join "|" resource-paths)))
        0
        16))

(declare built-in-skill-resource-paths)

(defn built-in-snapshot-dir
  "Return the deterministic snapshot directory path for packaged built-in skills."
  ([] (built-in-snapshot-dir {}))
  ([opts]
   (let [config         (merge default-config (:config opts))
         cache-dir      (:built-in-cache-dir config)
         resource-root  (:built-in-resource-root config)
         resource-paths (or (:resource-paths-override opts)
                            (built-in-skill-resource-paths opts))]
     (str (io/file cache-dir (built-in-snapshot-id resource-root resource-paths))))))

(defn built-in-skill-resource-paths
  "Enumerate packaged built-in SKILL.md resource paths under the configured resource root."
  ([] (built-in-skill-resource-paths {}))
  ([opts]
   (let [config        (merge default-config (:config opts))
         resource-root (:built-in-resource-root config)
         root-url      (io/resource resource-root)]
     (cond
       (nil? root-url)
       []

       (= "file" (.getProtocol root-url))
       (let [root-file (io/file root-url)
             root-path (.toPath root-file)]
         (->> (file-seq root-file)
              (filter #(.isFile ^File %))
              (filter #(= "SKILL.md" (.getName ^File %)))
              (map (fn [^File file]
                     (str resource-root "/"
                          (.toString (.relativize root-path (.toPath file))))))
              sort
              vec))

       (= "jar" (.getProtocol root-url))
       (let [jar-path (-> (.getPath root-url)
                          (str/split #"!")
                          first
                          (str/replace-first #"^file:" ""))]
         (with-open [jar (java.util.jar.JarFile. jar-path)]
           (->> (enumeration-seq (.entries jar))
                (map #(.getName ^java.util.jar.JarEntry %))
                (filter #(str/starts-with? % (str resource-root "/")))
                (filter #(str/ends-with? % "/SKILL.md"))
                sort
                vec)))

       :else
       []))))

(defn- ensure-parent-dirs!
  [path]
  (Files/createDirectories (.getParent ^Path path) (make-array FileAttribute 0))
  path)

(defn materialize-built-in-skills!
  "Materialize packaged built-in skill resources into a deterministic readable snapshot.

   Returns {:dir path :resource-paths [...] :reused? boolean}."
  ([] (materialize-built-in-skills! {}))
  ([opts]
   (let [resource-paths (built-in-skill-resource-paths opts)
         snapshot-dir   (built-in-snapshot-dir opts)
         snapshot-path  (.toPath (io/file snapshot-dir))
         reused?        (.exists (io/file snapshot-dir))]
     (when-not reused?
       (Files/createDirectories snapshot-path (make-array FileAttribute 0))
       (doseq [resource-path resource-paths]
         (when-let [resource-url (io/resource resource-path)]
           (let [resource-root (:built-in-resource-root (merge default-config (:config opts)))
                 relative-path (subs resource-path (inc (count resource-root)))
                 target-path   (.resolve snapshot-path relative-path)]
             (ensure-parent-dirs! target-path)
             (with-open [in (io/input-stream resource-url)]
               (Files/copy in target-path (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))))))
     {:dir snapshot-dir
      :resource-paths resource-paths
      :reused? reused?})))

(defn built-in-skills-discovery
  "Load materialized packaged built-in skills as ordinary file-backed skills."
  ([] (built-in-skills-discovery {}))
  ([opts]
   (let [{:keys [dir] :as materialized} (materialize-built-in-skills! opts)
         loaded (load-skills-from-dir dir :built-in true (merge default-config (:config opts)))]
     (assoc loaded :materialization materialized))))

;; ============================================================
;; Discovery — multi-source
;; ============================================================

(def ^:private source-precedence
  {:built-in 0
   :user 1
   :project 2
   :path 3})

(defn- candidate-container-order
  [candidate]
  (long (or (:container-order candidate) Long/MAX_VALUE)))

(defn- candidate-sort-key
  [candidate]
  [(- (get source-precedence (:source candidate) -1))
   (candidate-container-order candidate)
   (canonical-file-path (:file-path candidate))])

(defn- winning-candidate
  [candidates]
  (first (sort-by candidate-sort-key candidates)))

(defn- collision-diagnostic
  [winner shadowed]
  {:type :collision
   :message (str "Skill name collision: '" (:name winner) "' winner="
                 (:source winner) " " (:file-path winner)
                 "; shadowed=" (:source shadowed) " " (:file-path shadowed))
   :path (:file-path shadowed)
   :winner {:name (:name winner)
            :source (:source winner)
            :path (:file-path winner)}
   :shadowed {:name (:name shadowed)
              :source (:source shadowed)
              :path (:file-path shadowed)}})

(defn- load-path-candidates
  [raw-path config]
  (let [f (io/file raw-path)]
    (cond
      (not (.exists f))
      {:skills [] :diagnostics [{:type :warning :message "Skill path does not exist" :path raw-path}]}

      (.isDirectory f)
      (load-skills-from-dir raw-path :path true config)

      (and (.isFile f) (str/ends-with? (.getName f) ".md"))
      (let [result (load-skill-from-file raw-path :path config)]
        {:skills (if-let [skill (:skill result)] [skill] [])
         :diagnostics (:diagnostics result)})

      :else
      {:skills [] :diagnostics [{:type :warning :message "Skill path is not a markdown file" :path raw-path}]})))

(defn discover-skills
  "Discover skills from all configured sources.
   Returns {:skills [Skill] :diagnostics [Diagnostic]}.

   Canonical collision winner selection is explicit and precedence-aware:
   :path > :project > :user > :built-in.
   Within the same source class, earlier configured source-container order wins,
   then lexicographically earlier canonical absolute skill file path.

   `opts` keys:
     :global-skills-dirs  — seq of global skill directories
     :project-skills-dirs — seq of project skill directories
     :extra-paths         — seq of additional file/directory paths
     :disabled            — if true, only load extra-paths (--no-skills)
     :config              — validation config overrides"
  ([] (discover-skills {}))
  ([opts]
   (let [config (merge default-config (:config opts))
         global-dirs (or (:global-skills-dirs opts) (:global-skills-dirs default-config))
         project-dirs (or (:project-skills-dirs opts) (:project-skills-dirs default-config))
         source-results (concat
                         (when-not (:disabled opts)
                           [{:container-order 0
                             :result (built-in-skills-discovery opts)}])
                         (when-not (:disabled opts)
                           (map-indexed (fn [idx dir]
                                          {:container-order idx
                                           :result (load-skills-from-dir dir :user true config)})
                                        global-dirs))
                         (when-not (:disabled opts)
                           (map-indexed (fn [idx dir]
                                          {:container-order idx
                                           :result (load-skills-from-dir dir :project true config)})
                                        project-dirs))
                         (map-indexed (fn [idx raw-path]
                                        {:container-order idx
                                         :result (load-path-candidates raw-path config)})
                                      (:extra-paths opts)))
         diagnostics (vec (mapcat (comp :diagnostics :result) source-results))
         candidates-by-name
         (reduce (fn [acc {:keys [container-order result]}]
                   (reduce (fn [acc2 skill]
                             (update acc2 (:name skill) (fnil conj [])
                                     (assoc skill :container-order container-order)))
                           acc
                           (:skills result)))
                 {}
                 source-results)
         winners (->> candidates-by-name
                      vals
                      (map winning-candidate)
                      skill-registry/all-skills)
         collisions (->> candidates-by-name
                         vals
                         (mapcat (fn [candidates]
                                   (let [winner (winning-candidate candidates)]
                                     (for [candidate candidates
                                           :when (not= (:file-path candidate) (:file-path winner))]
                                       (collision-diagnostic winner candidate)))))
                         vec)]
     {:skills winners
      :diagnostics (into diagnostics collisions)})))

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

   Skills with disable-model-invocation=true or advertise: false are excluded
   from the prompt (they can only be invoked explicitly via /skill:name
   commands or read directly by a workflow step)."
  [skills]
  (let [visible (skill-registry/visible-skills skills)]
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
  (let [visible (skill-registry/visible-skills skills)]
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
     :visible-count     (count (skill-registry/visible-skills skills))
     :hidden-count      (count (skill-registry/hidden-skills skills))
     :skills            (mapv (fn [s]
                                {:name                     (:name s)
                                 :description              (:description s)
                                 :source                   (:source s)
                                 :disable-model-invocation (:disable-model-invocation s)
                                 :advertise                (:advertise s)})
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
  "Return skills that appear in the model's system context (not `prompt-hidden?`)."
  [skills]
  (skill-registry/visible-skills skills))

(defn hidden-skills
  "Return skills excluded from the model's system context (those `prompt-hidden?`)."
  [skills]
  (skill-registry/hidden-skills skills))

(defn enrich-skill
  "Add derived fields to a Skill map for introspection.

   `:is-available-to-model` reflects system-context visibility: a skill carrying
   `advertise: false` (or `disable-model-invocation`) is not surfaced to the
   model and so is reported as unavailable to it."
  [skill]
  (assoc skill
         :is-available-to-model (not (skill-registry/prompt-hidden? skill))))

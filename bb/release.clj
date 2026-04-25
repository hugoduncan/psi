(ns bb.release
  (:require
   [babashka.process :as process]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Git helpers
;; ---------------------------------------------------------------------------

(defn- git!
  "Run a git command, return trimmed stdout. Throws on non-zero exit."
  [& args]
  (-> (apply process/shell {:out :string :err :string} "git" args)
      :out
      str/trim))

(defn- git-ok?
  "Run a git command, return true on zero exit."
  [& args]
  (-> (apply process/shell {:continue true :out :string :err :string} "git" args)
      :exit
      zero?))

(defn- assert-clean-tree!
  []
  (let [status (git! "status" "--porcelain")]
    (when-not (str/blank? status)
      (throw (ex-info "Working tree is not clean — commit or stash changes first."
                      {:status status})))))

(defn- assert-on-master!
  []
  (let [branch (git! "rev-parse" "--abbrev-ref" "HEAD")]
    (when-not (= "master" branch)
      (throw (ex-info (str "Must release from master branch (currently on '" branch "').")
                      {:branch branch})))))

(defn- git-count-revs
  []
  (-> (git! "rev-list" "HEAD" "--count")
      Long/parseLong))

(defn- tag-exists?
  [tag]
  (git-ok? "rev-parse" "--verify" (str "refs/tags/" tag)))

;; ---------------------------------------------------------------------------
;; Version helpers
;; ---------------------------------------------------------------------------

(defn- read-version-edn
  []
  (edn/read-string (slurp "version.edn")))

(defn- compose-version
  [{:keys [major minor]} patch]
  (str major "." minor "." patch))

(defn- version-resource-path
  []
  "bases/main/resources/psi/version.edn")

(defn- write-version-resource!
  [version-str]
  (spit (version-resource-path) (str "{:version " (pr-str version-str) "}\n")))

;; ---------------------------------------------------------------------------
;; Changelog helpers
;; ---------------------------------------------------------------------------

(defn- read-changelog
  []
  (slurp "CHANGELOG.md"))

(defn- unreleased-section
  "Return the body lines of the [Unreleased] section, or nil if empty/absent."
  [changelog]
  (let [lines  (str/split-lines changelog)
        start  (->> lines
                    (map-indexed vector)
                    (some (fn [[i line]]
                            (when (re-matches #"^## \[Unreleased\].*" line) i))))
        _      (when-not start
                 (throw (ex-info "No [Unreleased] section found in CHANGELOG.md." {})))
        body   (->> (drop (inc start) lines)
                    (take-while #(not (re-matches #"^## \[.*\].*" %)))
                    (drop-while str/blank?)
                    (reverse)
                    (drop-while str/blank?)
                    (reverse))]
    (when (seq body)
      (str/join "\n" body))))

(defn- stamp-changelog!
  "Replace [Unreleased] with [version] - date and prepend a fresh [Unreleased]."
  [version-str date-str]
  (let [changelog (read-changelog)
        stamped   (str/replace-first
                   changelog
                   #"(?m)^## \[Unreleased\]"
                   (str "## [Unreleased]\n\n## [" version-str "] - " date-str))]
    (spit "CHANGELOG.md" stamped)))

;; ---------------------------------------------------------------------------
;; Partial-failure recovery
;; ---------------------------------------------------------------------------

(defn- post-tag-reset-needed?
  "True if the tag exists but the version resource still shows the release
   version — i.e. the reset commit was not yet made."
  [tag version-str]
  (and (tag-exists? tag)
       (= version-str
          (-> (version-resource-path) slurp edn/read-string :version))))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn release!
  "Cut a release: stamp changelog, bake version, commit, tag, reset to unreleased.

   PATCH = (git rev-list HEAD --count) + 1  — pre-compensates for the release commit."
  [_args]
  (assert-clean-tree!)
  (assert-on-master!)

  (let [version-base (read-version-edn)
        patch        (inc (git-count-revs))
        version-str  (compose-version version-base patch)
        tag          (str "v" version-str)
        date-str     (.format
                      (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd")
                      (java.time.LocalDate/now))]

    (println (str "Releasing " tag " ..."))

    ;; Partial-failure recovery: tag exists but reset not yet done
    (when (post-tag-reset-needed? tag version-str)
      (println (str "  Tag " tag " already exists and version resource not yet reset."))
      (println "  Completing post-tag reset ...")
      (write-version-resource! "unreleased")
      (git! "add" (version-resource-path))
      (git! "commit" "-m" (str "release: post-" tag " reset version to unreleased"))
      (println "Done.")
      (System/exit 0))

    ;; Normal path: tag must not exist
    (when (tag-exists? tag)
      (throw (ex-info (str "Tag " tag " already exists.") {:tag tag})))

    ;; Assert changelog has content to release
    (let [body (unreleased-section (read-changelog))]
      (when-not body
        (throw (ex-info "CHANGELOG.md [Unreleased] section is empty — nothing to release." {}))))

    ;; 1. Stamp changelog
    (println "  Stamping CHANGELOG.md ...")
    (stamp-changelog! version-str date-str)

    ;; 2. Bake version into resource
    (println (str "  Writing version resource: " version-str " ..."))
    (write-version-resource! version-str)

    ;; 3. Commit release
    (println "  Committing release ...")
    (git! "add" "CHANGELOG.md" (version-resource-path))
    (git! "commit" "-m" (str "release: " tag))

    ;; 4. Tag
    (println (str "  Tagging " tag " ..."))
    (git! "tag" tag)

    ;; 5. Reset version resource to unreleased
    (println "  Resetting version resource to unreleased ...")
    (write-version-resource! "unreleased")
    (git! "add" (version-resource-path))
    (git! "commit" "-m" (str "release: post-" tag " reset version to unreleased"))

    (println)
    (println "Done. Push with:")
    (println "  git push origin master --tags")))

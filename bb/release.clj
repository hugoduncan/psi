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

(def ^:private repo-url "https://github.com/hugoduncan/psi")

(defn- unreleased-link
  "Comparison link for [Unreleased] against a known previous tag."
  [prev-tag]
  (str "[Unreleased]: " repo-url "/compare/" prev-tag "...HEAD"))

(defn- version-link
  "Comparison link for a released version against its predecessor."
  [version-str prev-tag]
  (str "[" version-str "]: " repo-url "/compare/" prev-tag "...v" version-str))

(defn- previous-release-tag
  "Return the most recent vX.Y.Z tag reachable from HEAD, or nil."
  []
  (try
    (let [tag (str/trim (:out (process/shell {:out :string :err :string :continue true}
                                             "git" "describe" "--tags" "--abbrev=0"
                                             "--match" "v*" "HEAD^")))]
      (when-not (str/blank? tag) tag))
    (catch Exception _ nil)))

(defn- stamp-changelog!
  "Replace [Unreleased] with [version] - date, prepend a fresh [Unreleased],
   and update the comparison link footer."
  [version-str date-str]
  (let [changelog  (read-changelog)
        prev-tag   (previous-release-tag)
        ;; 1. Replace the section header
        stamped    (str/replace-first
                    changelog
                    #"(?m)^## \[Unreleased\]"
                    (str "## [Unreleased]\n\n## [" version-str "] - " date-str))
        ;; 2. Update or insert comparison links footer
        new-tag    (str "v" version-str)
        unrel-link (unreleased-link new-tag)
        ver-link   (when prev-tag (version-link version-str prev-tag))
        ;; Replace existing [Unreleased]: link if present, else append
        with-unrel (if (re-find #"(?m)^\[Unreleased\]:" stamped)
                     (str/replace stamped #"(?m)^\[Unreleased\]:.*" unrel-link)
                     (str (str/trimr stamped) "\n\n<!-- Comparison links -->\n" unrel-link "\n"))
        ;; Insert new version link after [Unreleased]: line
        with-ver   (if ver-link
                     (str/replace-first with-unrel
                                        #"(?m)^\[Unreleased\]:.*"
                                        (str unrel-link "\n" ver-link))
                     with-unrel)]
    (spit "CHANGELOG.md" with-ver)))

;; ---------------------------------------------------------------------------
;; Partial-failure recovery
;; ---------------------------------------------------------------------------

(defn- changelog-already-stamped?
  "True if CHANGELOG.md has already been stamped with version-str
   (i.e. ## [version-str] section exists) but the [Unreleased] section
   is now empty — indicating stamp completed but commit did not."
  [version-str]
  (let [changelog (read-changelog)]
    (boolean (re-find (re-pattern (str "(?m)^## \\[" (java.util.regex.Pattern/quote version-str) "\\]")) changelog))))

(defn- post-tag-reset-needed?
  "True if the tag exists but the version resource still shows the release
   version — i.e. the reset commit was not yet made."
  [tag version-str]
  (and (tag-exists? tag)
       (= version-str
          (-> (version-resource-path) slurp edn/read-string :version))))

(defn- post-tag-push-needed?
  "True if the tag exists locally but has not been pushed to origin."
  [tag]
  (and (tag-exists? tag)
       (not (git-ok? "ls-remote" "--exit-code" "--tags" "origin" (str "refs/tags/" tag)))))

(defn- latest-local-release-tag
  "Return the most recent vX.Y.Z tag pointing at HEAD, or nil.
   Used by release-and-push! to find the tag to push without recomputing
   the version from commit count (which changes after the release commits)."
  []
  (try
    (let [tag (str/trim (git! "describe" "--tags" "--exact-match" "HEAD"))]
      (when (re-matches #"v\d+\.\d+\.\d+" tag) tag))
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; Public entry points
;; ---------------------------------------------------------------------------

(defn check-changelog!
  "Assert CHANGELOG.md has a non-empty [Unreleased] section.
   Exits 0 on success, 1 with a message on failure."
  [_args]
  (let [body (unreleased-section (read-changelog))]
    (if body
      (println "CHANGELOG.md [Unreleased] section is present.")
      (do
        (binding [*out* *err*]
          (println "CHANGELOG.md [Unreleased] section is empty or missing.")
          (println "Add an entry under ## [Unreleased] before committing user-visible changes."))
        (System/exit 1)))))

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

    ;; Partial-failure recovery: changelog stamped but not yet committed
    ;; (process died between stamp-changelog! and git commit)
    (when (changelog-already-stamped? version-str)
      (println "  CHANGELOG.md already stamped — resuming from pre-commit state ...")
      (write-version-resource! version-str)
      (git! "add" "CHANGELOG.md" (version-resource-path))
      (git! "commit" "-m" (str "release: " tag))
      (git! "tag" tag)
      (write-version-resource! "unreleased")
      (git! "add" (version-resource-path))
      (git! "commit" "-m" (str "release: post-" tag " reset version to unreleased"))
      (println)
      (println "Done. Push with:")
      (println "  git push origin master --tags")
      (System/exit 0))

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

(defn push!
  "Push master + tags to origin. Intended as the second half of `bb release`."
  [_args]
  (println "Pushing master + tags to origin ...")
  (git! "push" "origin" "master" "--tags")
  (println "Done."))

(defn release-and-push!
  "Cut a release (stamp changelog, commit, tag) then push to origin.
   Equivalent to: bb release:tag && git push origin master --tags

   Partial-failure recovery: if a vX.Y.Z tag points at HEAD but has not been
   pushed (e.g. prior network failure), skips re-tagging and goes straight to
   push.  Uses git describe rather than recomputing the version from commit
   count, which would be wrong after the two release commits have been made."
  [_args]
  (if-let [tag (latest-local-release-tag)]
    (if (post-tag-push-needed? tag)
      (do
        (println (str "  Tag " tag " exists locally but not on origin — retrying push ..."))
        (push! nil))
      (do
        (println (str "  Tag " tag " already on origin — nothing to do."))
        (println "If you intended a new release, ensure CHANGELOG.md has new entries.")))
    (do
      (release! nil)
      (push! nil))))

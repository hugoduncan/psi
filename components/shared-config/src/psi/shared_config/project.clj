(ns psi.shared-config.project
  "Layered project AgentSession preferences.

   Stored at:
   - shared: <cwd>/.psi/project.edn
   - local:  <cwd>/.psi/project.local.edn

   Effective project preferences are computed by deep-merging shared then local,
   so local values take precedence.

   Shape:
   {:version 1
    :agent-session {:model-provider string?
                    :model-id string?
                    :thinking-level keyword?
                    :prompt-mode keyword?
                    :nucleus-prelude-override string?
                    ...}}"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def ^:private current-version 1)

(def ^:private default-prefs
  {:version current-version
   :agent-session {}})

(defn project-preferences-file
  [cwd]
  (io/file (str cwd) ".psi" "project.edn"))

(defn project-local-preferences-file
  [cwd]
  (io/file (str cwd) ".psi" "project.local.edn"))

(defn deep-merge
  [& maps]
  (apply merge-with (fn [a b]
                      (if (and (map? a) (map? b))
                        (deep-merge a b)
                        b))
         maps))

(defn- warn-malformed-file!
  [file e]
  (binding [*out* *err*]
    (println (str "WARNING: ignoring malformed project preferences file "
                  (.getAbsolutePath file)
                  ": "
                  (.getMessage e)))))

(defn- read-preferences-file*
  [file]
  (try
    (if (and (.exists file) (.isFile file))
      (let [v (edn/read-string (slurp file))]
        (if (map? v)
          v
          (do
            (warn-malformed-file! file (ex-info "expected EDN map" {:value-type (type v)}))
            nil)))
      nil)
    (catch Exception e
      (warn-malformed-file! file e)
      nil)))

(defn read-shared-preferences
  "Best-effort read of <cwd>/.psi/project.edn. Returns nil on missing/invalid file."
  [cwd]
  (read-preferences-file* (project-preferences-file cwd)))

(defn read-local-preferences
  "Best-effort read of <cwd>/.psi/project.local.edn. Returns nil on missing/invalid file."
  [cwd]
  (read-preferences-file* (project-local-preferences-file cwd)))

(defn read-preferences
  "Best-effort layered read.

   Effective preferences are computed as:
     defaults < shared project.edn < local project.local.edn

   Missing or malformed files are ignored with a warning."
  [cwd]
  (deep-merge default-prefs
              (or (read-shared-preferences cwd) {})
              (or (read-local-preferences cwd) {})))

(defn- write-preferences!
  [file prefs]
  (let [parent (.getParentFile file)]
    (when parent
      (.mkdirs parent))
    (spit file (pr-str prefs))
    prefs))

(defn update-agent-session!
  "Merge `m` into the local project override layer (:agent-session in
   <cwd>/.psi/project.local.edn) and write to disk.

   Existing malformed local config is treated as empty input with a warning."
  [cwd m]
  (let [local-file (project-local-preferences-file cwd)
        prefs      (deep-merge default-prefs
                               (or (read-local-preferences cwd) {}))]
    (write-preferences! local-file (update prefs :agent-session merge m))))

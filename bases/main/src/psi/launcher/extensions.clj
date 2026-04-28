(ns psi.launcher.extensions
  "Launcher-side extension manifest reading, merging, and psi-owned default expansion."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def default-installed-psi-version
  "Explicit launcher-owned default psi version identity for installed mode."
  "11f9c9f582dbe3a9fb1689ffcfd9f00c52bf2f6a")

(def default-psi-git-url
  "Explicit launcher-owned psi source identity for installed mode."
  "https://github.com/hugoduncan/psi.git")

(def psi-mvn-lib
  "Maven group/artifact for the psi library jar published to Clojars."
  'org.hugoduncan/psi)

(def psi-owned-extension-catalog
  {'psi/auto-session-name
   {:psi/init 'extensions.auto-session-name/init
    :source-policies
    {:development {:local/root "extensions/auto-session-name"}
     :installed   {:local/root "extensions/auto-session-name"}
     :jar         {:mvn/version :psi/release-version}}}

   'psi/commit-checks
   {:psi/init 'extensions.commit-checks/init
    :source-policies
    {:development {:local/root "extensions/commit-checks"}
     :installed   {:local/root "extensions/commit-checks"}
     :jar         {:mvn/version :psi/release-version}}}

   'psi/hello-ext
   {:psi/init 'extensions.hello-ext/init
    :source-policies
    {:development {:local/root "extensions/hello-ext"}
     :installed   {:local/root "extensions/hello-ext"}
     :jar         {:mvn/version :psi/release-version}}}

   'psi/mcp-tasks-run
   {:psi/init 'extensions.mcp-tasks-run/init
    :source-policies
    {:development {:local/root "extensions/mcp-tasks-run"}
     :installed   {:local/root "extensions/mcp-tasks-run"}
     :jar         {:mvn/version :psi/release-version}}}

   'psi/mementum
   {:psi/init 'extensions.mementum/init
    :source-policies
    {:development {:local/root "extensions/mementum"}
     :installed   {:local/root "extensions/mementum"}
     :jar         {:mvn/version :psi/release-version}}}

   'psi/munera
   {:psi/init 'extensions.munera/init
    :source-policies
    {:development {:local/root "extensions/munera"}
     :installed   {:local/root "extensions/munera"}
     :jar         {:mvn/version :psi/release-version}}}

   'psi/plan-state-learning
   {:psi/init 'extensions.plan-state-learning/init
    :source-policies
    {:development {:local/root "extensions/plan-state-learning"}
     :installed   {:local/root "extensions/plan-state-learning"}
     :jar         {:mvn/version :psi/release-version}}}

   'psi/work-on
   {:psi/init 'extensions.work-on/init
    :source-policies
    {:development {:local/root "extensions/work-on"}
     :installed   {:local/root "extensions/work-on"}
     :jar         {:mvn/version :psi/release-version}}}

   'psi/workflow-loader
   {:psi/init 'extensions.workflow-loader/init
    :source-policies
    {:development {:local/root "extensions/workflow-loader"}
     :installed   {:local/root "extensions/workflow-loader"}
     :jar         {:mvn/version :psi/release-version}}}})

(defn coordinate-family
  [dep]
  (let [families (cond-> #{}
                   (contains? dep :local/root) (conj :local)
                   (or (contains? dep :git/url)
                       (contains? dep :git/sha)
                       (contains? dep :git/tag)) (conj :git)
                   (contains? dep :mvn/version) (conj :mvn))]
    (when (= 1 (count families))
      (first families))))

(defn- assert-manifest-shape!
  [value file]
  (when-not (map? value)
    (throw (ex-info "Extension manifest must be a map"
                    {:stage :manifest-read
                     :file file
                     :problem :manifest-not-map})))
  (when-not (map? (:deps value))
    (throw (ex-info "Extension manifest :deps must be a map"
                    {:stage :manifest-read
                     :file file
                     :problem :deps-not-map})))
  value)

(defn read-manifest-file
  [file]
  (let [file (io/file file)]
    (if-not (.exists ^java.io.File file)
      {:deps {}}
      (try
        (-> (slurp file)
            edn/read-string
            (assert-manifest-shape! (.getAbsolutePath ^java.io.File file))
            (update :deps #(or % {})))
        (catch clojure.lang.ExceptionInfo e
          (throw e))
        (catch Exception e
          (throw (ex-info "Failed to read extension manifest"
                          {:stage :manifest-read
                           :file (.getAbsolutePath ^java.io.File file)}
                          e)))))))

(defn merge-manifests
  [user-manifest project-manifest]
  {:deps (merge (:deps user-manifest) (:deps project-manifest))})

(defn catalog-entry
  [lib]
  (get psi-owned-extension-catalog lib))

(defn recognized-psi-owned-lib?
  [lib]
  (contains? psi-owned-extension-catalog lib))

(defn- validate-coordinate-family!
  [lib dep]
  (let [family (coordinate-family dep)]
    (when-not family
      (throw (ex-info "Dependency entry must declare exactly one coordinate family"
                      {:stage :validation
                       :lib lib
                       :dep dep})))
    (case family
      :local
      (when-not (:local/root dep)
        (throw (ex-info "Local dependency entry requires :local/root"
                        {:stage :validation :lib lib :dep dep})))

      :git
      (when-not (and (:git/url dep)
                     (or (:git/sha dep) (:git/tag dep)))
        (throw (ex-info "Git dependency entry requires :git/url and one of :git/sha or :git/tag"
                        {:stage :validation :lib lib :dep dep})))

      :mvn
      (when-not (:mvn/version dep)
        (throw (ex-info "Maven dependency entry requires :mvn/version"
                        {:stage :validation :lib lib :dep dep}))))
    dep))

(defn- selected-policy-defaults!
  [lib policy]
  (let [entry    (catalog-entry lib)
        defaults (get-in entry [:source-policies policy])]
    (when-not entry
      (throw (ex-info "Minimal psi-owned syntax is only valid for recognized psi-owned libs"
                      {:stage :default-expansion
                       :lib lib})))
    (when-not (:psi/init entry)
      (throw (ex-info "Psi-owned catalog entry is missing :psi/init"
                      {:stage :init-inference
                       :lib lib})))
    (when-not defaults
      (throw (ex-info "Psi-owned catalog entry is missing source policy defaults"
                      {:stage :default-expansion
                       :lib lib
                       :policy policy})))
    (validate-coordinate-family! lib defaults)))

(defn expand-entry
  ([lib dep]
   (expand-entry lib dep {:policy :installed}))
  ([lib dep {:keys [policy] :or {policy :installed}}]
   (when-not (map? dep)
     (throw (ex-info "Dependency entry must be a map"
                     {:stage :validation
                      :lib lib
                      :dep dep})))
   (if-let [entry (catalog-entry lib)]
     (let [defaults        (selected-policy-defaults! lib policy)
           explicit-family (coordinate-family dep)
           default-family  (coordinate-family defaults)
           merged          (cond-> dep
                             (or (nil? explicit-family)
                                 (= explicit-family default-family))
                             (#(merge defaults %))
                             true
                             (assoc :psi/init (or (:psi/init dep) (:psi/init entry))))]
       (validate-coordinate-family! lib merged))
     (validate-coordinate-family! lib dep))))

(defn expand-entry-report
  ([lib dep]
   (expand-entry-report lib dep {:policy :installed}))
  ([lib dep {:keys [policy] :or {policy :installed}}]
   (let [expanded (expand-entry lib dep {:policy policy})]
     (if-let [_entry (catalog-entry lib)]
       (let [defaults           (selected-policy-defaults! lib policy)
             defaulted-fields   (->> defaults
                                     (keep (fn [[k v]]
                                             (when (and (not= k :psi/init)
                                                        (not (contains? dep k))
                                                        (= v (get expanded k)))
                                               k)))
                                     vec)
             inferred-init?     (and (not (contains? dep :psi/init))
                                     (contains? expanded :psi/init))]
         {:expanded expanded
          :defaulted-fields defaulted-fields
          :defaulted? (boolean (seq defaulted-fields))
          :inferred-init? inferred-init?})
       {:expanded expanded
        :defaulted-fields []
        :defaulted? false
        :inferred-init? false}))))

(defn manifest-expansion-report
  ([manifest]
   (manifest-expansion-report manifest {:policy :installed}))
  ([manifest {:keys [policy] :or {policy :installed}}]
   (let [reports (into {}
                       (map (fn [[lib dep]]
                              [lib (expand-entry-report lib dep {:policy policy})]))
                       (:deps manifest))]
     {:expanded-manifest {:deps (into {}
                                      (map (fn [[lib {:keys [expanded]}]]
                                             [lib expanded]))
                                      reports)}
      :defaulted-libs (->> reports
                           (keep (fn [[lib {:keys [defaulted?]}]]
                                   (when defaulted? lib)))
                           vec)
      :inferred-init-libs (->> reports
                               (keep (fn [[lib {:keys [inferred-init?]}]]
                                       (when inferred-init? lib)))
                               vec)
      :reports reports})))

(defn expand-manifest
  ([manifest]
   (expand-manifest manifest {:policy :installed}))
  ([manifest {:keys [policy] :or {policy :installed}}]
   (:expanded-manifest (manifest-expansion-report manifest {:policy policy}))))

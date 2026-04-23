(ns psi.agent-session.extension-installs
  "Extension install manifest reading, effective-state projection, diagnostics,
   and conservative apply-state tracking.

   Slice one intentionally separates manifest/config introspection from general
   dependency realization. Runtime apply currently supports manifest-backed
   `:local/root` extension activation and reports `:restart-required` for git
   and mvn extension deps."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [psi.agent-session.session-state :as ss]))

(defn user-manifest-file
  []
  (io/file (System/getProperty "user.home") ".psi" "agent" "extensions.edn"))

(defn project-manifest-file
  [cwd]
  (io/file cwd ".psi" "extensions.edn"))

(defn- base-manifest
  []
  {:deps {}})

(defn- diagnostic
  [{:keys [severity category message libs init-var scopes source data]}]
  {:severity severity
   :category category
   :message message
   :libs (vec (or libs []))
   :init-var init-var
   :scopes (vec (or scopes []))
   :source source
   :data (or data {})})

(defn- read-edn-file
  [file]
  (edn/read-string (slurp file)))

(defn- read-manifest-file
  [file source]
  (if-not (.exists ^java.io.File file)
    {:manifest (base-manifest)
     :diagnostics []}
    (try
      (let [value (read-edn-file file)]
        (cond
          (not (map? value))
          {:manifest (base-manifest)
           :diagnostics [(diagnostic {:severity :error
                                      :category :malformed-entry
                                      :message (str "Extension manifest must be a map: " (.getAbsolutePath ^java.io.File file))
                                      :source source
                                      :data {:path (.getAbsolutePath ^java.io.File file)
                                             :problem :manifest-not-map}})]}

          (not (map? (:deps value)))
          {:manifest (base-manifest)
           :diagnostics [(diagnostic {:severity :error
                                      :category :malformed-entry
                                      :message (str "Extension manifest :deps must be a map: " (.getAbsolutePath ^java.io.File file))
                                      :source source
                                      :data {:path (.getAbsolutePath ^java.io.File file)
                                             :problem :deps-not-map}})]}

          :else
          {:manifest (update value :deps #(or % {}))
           :diagnostics []}))
      (catch Exception e
        {:manifest (base-manifest)
         :diagnostics [(diagnostic {:severity :error
                                    :category :malformed-entry
                                    :message (str "Failed to read extension manifest: " (ex-message e))
                                    :source source
                                    :data {:path (.getAbsolutePath ^java.io.File file)}})]}))))

(defn- psi-meta-keys
  [dep]
  (->> (keys dep)
       (filter #(and (keyword? %) (= "psi" (namespace %))))
       set))

(defn coordinate-family
  [dep]
  (let [families (cond-> #{}
                   (contains? dep :local/root) (conj :local)
                   (or (contains? dep :git/url)
                       (contains? dep :git/sha)) (conj :git)
                   (contains? dep :mvn/version) (conj :mvn))]
    (when (= 1 (count families))
      (first families))))

(defn extension-dep?
  [dep]
  (and (map? dep) (contains? dep :psi/init)))

(defn- malformed-extension-candidate?
  [dep]
  (let [psi-keys (psi-meta-keys dep)]
    (and (seq psi-keys)
         (not (contains? psi-keys :psi/init)))))

(defn- manifest-source
  [scope]
  (keyword (str (name scope) "-manifest")))

(defn- entry-diagnostics
  [scope lib dep]
  (let [coord-family (when (map? dep) (coordinate-family dep))
        extension?   (extension-dep? dep)
        malformed?   (and (map? dep) (malformed-extension-candidate? dep))]
    (vec
     (concat
      (when-not (map? dep)
        [(diagnostic {:severity :error
                      :category :malformed-entry
                      :message (str "Dependency entry must be a map for " lib)
                      :libs [lib]
                      :scopes [scope]
                      :source (manifest-source scope)})])
      (when malformed?
        [(diagnostic {:severity :error
                      :category :malformed-entry
                      :message (str "Extension metadata requires :psi/init for " lib)
                      :libs [lib]
                      :scopes [scope]
                      :source (manifest-source scope)})])
      (when (and extension? (nil? coord-family))
        [(diagnostic {:severity :error
                      :category :malformed-entry
                      :message (str "Extension dependency must declare exactly one coordinate family for " lib)
                      :libs [lib]
                      :init-var (:psi/init dep)
                      :scopes [scope]
                      :source (manifest-source scope)
                      :data {:dep dep}})])
      (when (and extension? (= coord-family :git) (not (contains? dep :git/sha)))
        [(diagnostic {:severity :error
                      :category :missing-git-sha
                      :message (str "Git extension dependency requires :git/sha for " lib)
                      :libs [lib]
                      :init-var (:psi/init dep)
                      :scopes [scope]
                      :source (manifest-source scope)})])
      (when (and (= scope :project) (= coord-family :local))
        [(diagnostic {:severity :warning
                      :category :project-local-root-nonreproducible
                      :message (str "Project extension dependency uses non-reproducible :local/root for " lib)
                      :libs [lib]
                      :init-var (:psi/init dep)
                      :scopes [scope]
                      :source :project-manifest})])))))

(defn- absolutize-local-root
  [base-dir dep]
  (if-let [local-root (:local/root dep)]
    (let [f (io/file local-root)]
      (if (.isAbsolute f)
        dep
        (assoc dep :local/root (.getAbsolutePath (io/file base-dir local-root)))))
    dep))

(defn- normalize-entry
  [lib scope dep source-manifests overridden? base-dir]
  (let [dep* (if (map? dep)
               (absolutize-local-root base-dir dep)
               dep)]
    [lib {:dep dep*
          :extension? (extension-dep? dep*)
          :support-dep? (not (extension-dep? dep*))
          :enabled? (boolean (if (extension-dep? dep*)
                               (get dep* :psi/enabled true)
                               false))
          :scope scope
          :source-manifests (vec source-manifests)
          :overridden? overridden?
          :effective? true
          :status (cond
                    (not (extension-dep? dep*)) :not-applicable
                    (false? (get dep* :psi/enabled true)) :disabled
                    :else :configured)
          :load-error nil
          :init-var (when (extension-dep? dep*) (:psi/init dep*))}]))

(defn- duplicate-init-diagnostics
  [entries-by-lib]
  (->> entries-by-lib
       (keep (fn [[lib {:keys [extension? init-var scope]}]]
               (when (and extension? init-var)
                 [init-var {:lib lib :scope scope}])))
       (group-by first)
       (keep (fn [[init-var claims]]
               (let [claimants (mapv second claims)
                     libs      (mapv :lib claimants)]
                 (when (> (count (set libs)) 1)
                   (diagnostic {:severity :error
                                :category :duplicate-init
                                :message (str "Duplicate :psi/init claim for " init-var)
                                :libs libs
                                :init-var init-var
                                :scopes (mapv :scope claimants)
                                :source :effective
                                :data {:claims claimants}})))))
       vec))

(defn compute-install-state
  [cwd]
  (let [{user-manifest :manifest user-diags :diagnostics}
        (read-manifest-file (user-manifest-file) :user-manifest)
        {project-manifest :manifest project-diags :diagnostics}
        (read-manifest-file (project-manifest-file cwd) :project-manifest)
        user-deps      (:deps user-manifest)
        project-deps   (:deps project-manifest)
        all-libs       (set/union (set (keys user-deps)) (set (keys project-deps)))
        user-base-dir  (.getParentFile ^java.io.File (user-manifest-file))
        project-base-dir (io/file cwd)
        entries-by-lib (into {}
                             (map (fn [lib]
                                    (if (contains? project-deps lib)
                                      (normalize-entry lib
                                                       :project
                                                       (get project-deps lib)
                                                       (cond-> [:project]
                                                         (contains? user-deps lib) (conj :user))
                                                       (boolean (contains? user-deps lib))
                                                       project-base-dir)
                                      (normalize-entry lib
                                                       :user
                                                       (get user-deps lib)
                                                       [:user]
                                                       false
                                                       user-base-dir))))
                             (sort all-libs))
        effective-raw-deps (into {}
                                 (map (fn [[lib {:keys [dep]}]] [lib dep]))
                                 entries-by-lib)
        effective-extension-deps (into {}
                                       (keep (fn [[lib {:keys [dep extension?]}]]
                                               (when extension? [lib dep])))
                                       entries-by-lib)
        entry-diags (vec (concat (mapcat (fn [[lib dep]] (entry-diagnostics :user lib dep)) user-deps)
                                 (mapcat (fn [[lib dep]] (entry-diagnostics :project lib dep)) project-deps)))
        dup-diags   (duplicate-init-diagnostics entries-by-lib)
        diagnostics (vec (concat user-diags project-diags entry-diags dup-diags))]
    {:psi.extensions/user-manifest user-manifest
     :psi.extensions/project-manifest project-manifest
     :psi.extensions/effective {:raw-deps effective-raw-deps
                                :extension-deps effective-extension-deps
                                :entries-by-lib entries-by-lib
                                :active? (boolean (seq effective-extension-deps))}
     :psi.extensions/diagnostics diagnostics}))

(defn extension-installs-state-in
  [ctx]
  (or (ss/get-state-value-in ctx (ss/state-path :extension-installs))
      {}))

(defn- init-ns->relative-candidates
  [init-var]
  (let [ns-name (some-> init-var namespace)
        rel     (some-> ns-name
                        (str/replace "." "/")
                        (str/replace "-" "_"))]
    (when rel
      [(str rel ".clj")
       (str rel ".cljc")])))

(defn- local-root-source-paths
  [local-root]
  (let [deps-file (io/file local-root "deps.edn")]
    (if (.exists deps-file)
      (try
        (let [deps (read-edn-file deps-file)
              paths (vec (filter string? (:paths deps)))]
          (if (seq paths) paths ["src"]))
        (catch Exception _
          ["src"]))
      ["src"])))

(defn manifest-extension-id
  [lib]
  (str "manifest:" lib))

(defn resolve-local-root-entry
  [lib {:keys [dep]}]
  (let [local-root (:local/root dep)
        init-var   (:psi/init dep)
        candidates (init-ns->relative-candidates init-var)]
    (when (and local-root init-var candidates)
      (let [root          (io/file local-root)
            source-paths  (local-root-source-paths local-root)
            files         (for [src source-paths
                                rel candidates]
                            (io/file root src rel))
            existing-file (some #(when (.exists ^java.io.File %) %) files)]
        (if existing-file
          {:lib lib
           :id (.getAbsolutePath ^java.io.File existing-file)
           :kind :path
           :path (.getAbsolutePath ^java.io.File existing-file)
           :init-var init-var}
          {:lib lib
           :id (manifest-extension-id lib)
           :kind :path
           :path nil
           :init-var init-var
           :error (str "Unable to resolve local extension source file for " lib
                       " from :local/root " local-root
                       " and :psi/init " init-var)})))))

(defn- non-local-effective-raw-deps
  [install-state]
  (->> (get-in install-state [:psi.extensions/effective :raw-deps])
       (keep (fn [[lib dep]]
               (when (and (map? dep)
                          (not= :local (coordinate-family dep)))
                 [lib dep])))
       (into {})))

(defn- safe-inprocess-deps-apply?
  [previous-install-state install-state]
  (let [previous-deps (non-local-effective-raw-deps previous-install-state)
        current-deps  (non-local-effective-raw-deps install-state)]
    (and (seq current-deps)
         (seq previous-deps)
         (every? (fn [[lib _dep]]
                   (contains? current-deps lib))
                 previous-deps))))

(defn activation-plan
  ([install-state]
   (activation-plan install-state nil))
  ([install-state previous-install-state]
   (let [entries-by-lib      (get-in install-state [:psi.extensions/effective :entries-by-lib])
         enabled-exts        (into {}
                                   (filter (fn [[_ {:keys [extension? enabled?]}]]
                                             (and extension? enabled?)))
                                   entries-by-lib)
         local-entries       (into {}
                                   (filter (fn [[_ {:keys [dep]}]]
                                             (= :local (coordinate-family dep))))
                                   enabled-exts)
         deps-extension-libs (into #{}
                                   (keep (fn [[lib {:keys [dep]}]]
                                           (when (not= :local (coordinate-family dep))
                                             lib)))
                                   enabled-exts)
         deps-to-realize     (non-local-effective-raw-deps install-state)
         deps-apply-safe?    (safe-inprocess-deps-apply? previous-install-state install-state)
         resolved            (mapv (fn [[lib entry]]
                                     (resolve-local-root-entry lib entry))
                                   local-entries)
         resolved-ok         (filterv :path resolved)
         resolved-fail       (filterv :error resolved)
         diagnostics         (mapv (fn [{:keys [lib init-var error]}]
                                     (diagnostic {:severity :error
                                                  :category :load-failure
                                                  :message error
                                                  :libs [lib]
                                                  :init-var init-var
                                                  :source :effective}))
                                   resolved-fail)]
     {:extension-paths (mapv :path resolved-ok)
      :path->lib (into {} (map (juxt :path :lib)) resolved-ok)
      :deps-to-realize deps-to-realize
      :deps-extension-libs deps-extension-libs
      :deps-apply-safe? deps-apply-safe?
      :restart-required-libs (if deps-apply-safe? #{} deps-extension-libs)
      :resolution-errors resolved-fail
      :diagnostics diagnostics})))

(defn- merge-entry-statuses
  [entries-by-lib plan reload-result]
  (let [loaded-ids         (set (:loaded reload-result))
        errors-by-path     (into {} (map (juxt :path :error)) (:errors reload-result))
        path->lib          (:path->lib plan)
        failed-libs        (into #{} (concat
                                      (map second path->lib)
                                      (map :lib (:resolution-errors plan))))
        deps-extension-libs (:deps-extension-libs plan)
        restart-required-libs (:restart-required-libs plan)
        deps-realized?     (boolean (:deps-realized? reload-result))]
    (into {}
          (map (fn [[entry-lib entry]]
                 (let [dep              (:dep entry)
                       extension?       (:extension? entry)
                       enabled?         (:enabled? entry)
                       coord-family     (when extension? (coordinate-family dep))
                       path             (some (fn [[p l]] (when (= l entry-lib) p)) path->lib)
                       resolution-error (some (fn [{:keys [lib error]}]
                                                (when (= lib entry-lib) error))
                                              (:resolution-errors plan))
                       manifest-id      (manifest-extension-id entry-lib)
                       load-error       (or (get errors-by-path path)
                                            (get errors-by-path manifest-id)
                                            resolution-error)
                       status           (cond
                                          (not extension?) :not-applicable
                                          (not enabled?) :disabled
                                          (= :local coord-family)
                                          (cond
                                            (contains? loaded-ids path) :loaded
                                            load-error :failed
                                            (contains? failed-libs entry-lib) :failed
                                            :else :configured)
                                          (contains? restart-required-libs entry-lib) :restart-required
                                          (contains? loaded-ids manifest-id) :loaded
                                          (and (contains? deps-extension-libs entry-lib)
                                               deps-realized?
                                               (not load-error)) :loaded
                                          load-error :failed
                                          :else :configured)]
                   [entry-lib (cond-> (assoc entry :status status)
                                load-error (assoc :load-error load-error))]))
               entries-by-lib))))

(defn finalize-apply-state
  [install-state plan reload-result]
  (let [entries-by-lib-map     (get-in install-state [:psi.extensions/effective :entries-by-lib])
        reload-errors          (mapv (fn [{:keys [path error]}]
                                       (diagnostic {:severity :error
                                                    :category :load-failure
                                                    :message error
                                                    :libs [(or (get (:path->lib plan) path)
                                                               (some (fn [[lib _]]
                                                                       (when (= path (manifest-extension-id lib))
                                                                         lib))
                                                                     entries-by-lib-map))]
                                                    :source :effective
                                                    :data {:path path}}))
                                     (:errors reload-result))
        deps-realize-error     (:deps-realize-error reload-result)
        deps-restart-required? (boolean (:deps-restart-required? reload-result))
        restart-required-libs  (cond-> (:restart-required-libs plan)
                                 deps-restart-required?
                                 (into (:deps-extension-libs plan)))
        deps-realize-diag      (when deps-realize-error
                                 (diagnostic {:severity :error
                                              :category :load-failure
                                              :message deps-realize-error
                                              :libs (sort (:deps-extension-libs plan))
                                              :source :effective}))
        diagnostics            (vec (concat (:psi.extensions/diagnostics install-state)
                                            (:diagnostics plan)
                                            reload-errors
                                            (when deps-realize-diag [deps-realize-diag])
                                            (when (seq restart-required-libs)
                                              [(diagnostic {:severity :info
                                                            :category :restart-required
                                                            :message (str "Restart required to realize non-local extension deps: "
                                                                          (pr-str (sort restart-required-libs)))
                                                            :libs (sort restart-required-libs)
                                                            :source :effective})])))
        has-errors?            (boolean (some #(= :error (:severity %)) diagnostics))
        reload-result*         (cond-> reload-result
                                 (seq restart-required-libs)
                                 (assoc :deps-realized? false))
        entries-by-lib         (merge-entry-statuses (get-in install-state [:psi.extensions/effective :entries-by-lib])
                                                     (assoc plan :restart-required-libs restart-required-libs)
                                                     reload-result*)
        status                 (cond
                                 has-errors? nil
                                 (seq restart-required-libs) :restart-required
                                 :else :applied)
        summary                (cond
                                 (= status :restart-required)
                                 "manifest-backed local extensions applied; restart required for remaining non-local extension deps"

                                 (and (= status :applied)
                                      (seq (:deps-extension-libs plan)))
                                 "manifest-backed extension install state applied, including non-local deps"

                                 (= status :applied)
                                 "manifest-backed extension install state applied"

                                 :else nil)
        last-apply             (when status
                                 {:status status
                                  :restart-required? (= status :restart-required)
                                  :summary summary
                                  :diagnostic-count (count diagnostics)
                                  :at (str (java.time.Instant/now))})]
    (-> install-state
        (assoc :psi.extensions/diagnostics diagnostics)
        (assoc-in [:psi.extensions/effective :entries-by-lib] entries-by-lib)
        (assoc :psi.extensions/last-apply last-apply))))

(defn persist-install-state-in!
  [ctx state]
  (ss/assoc-state-value-in! ctx (ss/state-path :extension-installs) state)
  state)

(defn apply-installs-in!
  "Compatibility wrapper for the manifest/install projection apply path.
   Computes install state, derives the activation plan, and persists a
   conservative last-apply result without executing extension reload. Runtime
   reload paths should prefer `activation-plan` + `finalize-apply-state`."
  [ctx cwd]
  (let [previous-state (extension-installs-state-in ctx)
        install-state  (compute-install-state cwd)
        plan           (activation-plan install-state previous-state)
        reload-result  {:loaded [] :errors [] :deps-realized? false}
        finalized      (finalize-apply-state install-state plan reload-result)
        persisted      (persist-install-state-in! ctx finalized)]
    {:state persisted
     :applied? (some? (get-in persisted [:psi.extensions/last-apply :status]))
     :status (get-in persisted [:psi.extensions/last-apply :status])
     :diagnostics (:psi.extensions/diagnostics persisted)}))

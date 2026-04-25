(ns psi.launcher
  "Launcher-side CLI parsing and startup-basis construction for psi startup."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.launcher.extensions :as extensions]
   [psi.version :as version]))

(defn- blank-path?
  [x]
  (or (nil? x)
      (str/blank? x)))

(defn- strip-launcher-separator
  [args]
  (if (= "--" (first args))
    (vec (rest args))
    (vec args)))

(defn parse-launcher-args
  "Split launcher-owned args from psi runtime args.

   Consumes:
   - --cwd <path>
   - --launcher-debug
   - --version

   Returns {:cwd string? :launcher-debug? boolean :version? boolean :psi-args [...] }.
   Throws ex-info on malformed launcher-owned args."
  [args]
  (loop [remaining (strip-launcher-separator args)
         parsed {:cwd nil
                 :launcher-debug? false
                 :version? false
                 :psi-args []}]
    (if-let [arg (first remaining)]
      (cond
        (= "--cwd" arg)
        (let [cwd (second remaining)]
          (when (blank-path? cwd)
            (throw (ex-info "Missing value for --cwd"
                            {:arg arg
                             :args remaining})))
          (recur (nnext remaining) (assoc parsed :cwd cwd)))

        (= "--launcher-debug" arg)
        (recur (next remaining) (assoc parsed :launcher-debug? true))

        (= "--version" arg)
        (recur (next remaining) (assoc parsed :version? true))

        :else
        (recur (next remaining) (update parsed :psi-args conj arg)))
      parsed)))

(defn resolve-effective-cwd
  "Resolve the launcher working directory. Relative overrides are resolved
   against the launcher process cwd."
  [{:keys [cwd]} process-cwd]
  (let [candidate (or cwd process-cwd)
        file (io/file candidate)]
    (if (.isAbsolute file)
      (.getAbsolutePath file)
      (.getAbsolutePath (io/file process-cwd candidate)))))

(defn user-manifest-path
  [home]
  (.getAbsolutePath (io/file home ".psi" "agent" "extensions.edn")))

(defn project-manifest-path
  [cwd]
  (.getAbsolutePath (io/file cwd ".psi" "extensions.edn")))

(defn- read-edn-file
  [file]
  (-> file slurp edn/read-string))

(defn- absolutize-local-root
  [base-dir dep]
  (if-let [local-root (:local/root dep)]
    (let [f (io/file local-root)]
      (if (.isAbsolute f)
        dep
        (assoc dep :local/root (.getAbsolutePath (io/file base-dir local-root)))))
    dep))

(defn repo-basis-config
  [launcher-root]
  (read-edn-file (io/file launcher-root "deps.edn")))

(defn- release-version
  "Return the baked version string, or nil if running unreleased."
  []
  (let [v (version/version-string)]
    (when (not= "unreleased" v) v)))

(defn- materialize-self-dep
  [launcher-root policy dep]
  (case policy
    (:development :installed)
    (absolutize-local-root launcher-root dep)

    :jar
    ;; jar policy: psi self-deps are bundled — replaced by the single mvn coord below
    dep

    (throw (ex-info "Unknown launcher policy"
                    {:stage :basis-construction
                     :policy policy}))))

(defn- psi-self-basis-from-repo-config
  [repo-config launcher-root policy]
  {:deps (into {}
               (map (fn [[lib dep]]
                      [lib (materialize-self-dep launcher-root policy dep)]))
               (:deps repo-config))
   :paths []})

(defn- psi-jar-basis
  "For :jar policy: single mvn coord replaces all psi local/root self-deps."
  [version]
  {:deps {extensions/psi-mvn-lib {:mvn/version version}
          'nrepl/nrepl            {:mvn/version "1.5.1"}}
   :paths []})

(defn psi-self-basis
  [launcher-root policy]
  (if (= :jar policy)
    (let [v (release-version)]
      (when-not v
        (throw (ex-info ":jar policy requires a stamped release version (not 'unreleased')"
                        {:stage :basis-construction :policy policy})))
      (psi-jar-basis v))
    (-> (repo-basis-config launcher-root)
        (psi-self-basis-from-repo-config launcher-root policy)
        (update :deps assoc 'nrepl/nrepl {:mvn/version "1.5.1"}))))

(defn manifest-state
  [launcher-root cwd policy]
  (let [home               (System/getProperty "user.home")
        user-path          (user-manifest-path home)
        project-path       (project-manifest-path cwd)
        user-manifest      (extensions/read-manifest-file user-path)
        project-manifest   (extensions/read-manifest-file project-path)
        merged-manifest    (extensions/merge-manifests user-manifest project-manifest)
        expansion-report   (extensions/manifest-expansion-report merged-manifest {:policy policy})
        expanded-manifest0 (:expanded-manifest expansion-report)
        expanded-manifest  (update expanded-manifest0 :deps
                                   (fn [deps]
                                     (into {}
                                           (map (fn [[lib dep]]
                                                  [lib (absolutize-local-root launcher-root dep)]))
                                           deps)))]
    {:user-path          user-path
     :project-path       project-path
     :user-present?      (.exists (io/file user-path))
     :project-present?   (.exists (io/file project-path))
     :user-manifest      user-manifest
     :project-manifest   project-manifest
     :merged-manifest    merged-manifest
     :expanded-manifest  expanded-manifest
     :defaulted-libs     (:defaulted-libs expansion-report)
     :inferred-init-libs (:inferred-init-libs expansion-report)}))

(defn- absolutize-local-root-dep
  [base-dir dep]
  (if-let [local-root (:local/root dep)]
    (let [f (io/file local-root)]
      (if (.isAbsolute f)
        dep
        (assoc dep :local/root (.getAbsolutePath (io/file base-dir local-root)))))
    dep))

(defn- basis-deps
  [dep-map]
  (into {}
        (map (fn [[lib dep]]
               [lib (dissoc dep :psi/init :psi/enabled)]))
        dep-map))

(defn- resolve-release-version-placeholder
  "Replace the :psi/release-version sentinel with the actual version string."
  [dep version]
  (if (= :psi/release-version (:mvn/version dep))
    (assoc dep :mvn/version version)
    dep))

(defn- materialize-manifest-dep
  [cwd policy dep]
  (case policy
    :development
    (absolutize-local-root-dep cwd dep)

    :installed
    (absolutize-local-root-dep cwd dep)

    :jar
    (let [version (release-version)]
      (when-not version
        (throw (ex-info ":jar policy requires a stamped release version (not 'unreleased')"
                        {:stage :basis-construction :policy policy})))
      (resolve-release-version-placeholder dep version))

    (throw (ex-info "Unknown launcher policy"
                    {:stage :basis-construction
                     :policy policy}))))

(defn startup-basis
  [launcher-root cwd policy]
  (let [self-basis       (update (psi-self-basis launcher-root policy) :deps basis-deps)
        manifest-info0   (manifest-state launcher-root cwd policy)
        expanded-deps    (->> (get-in manifest-info0 [:expanded-manifest :deps])
                              (map (fn [[lib dep]]
                                     [lib (-> (materialize-manifest-dep cwd policy dep)
                                              (dissoc :psi/init :psi/enabled))]))
                              (into {}))
        manifest-info    (assoc-in manifest-info0 [:expanded-manifest :deps] expanded-deps)]
    {:basis (update self-basis :deps merge expanded-deps)
     :manifest-info manifest-info
     :policy policy}))

(defn build-clojure-command
  "Build the clojure CLI argv used for launcher handoff."
  [{:keys [basis psi-args]}]
  (into ["clojure" "-Sdeps" (pr-str basis) "-M" "-m" "psi.main"] psi-args))

(defn launch-plan
  "Build a pure launcher execution plan from raw args, process cwd, launcher
   root, and explicit realization policy."
  [args process-cwd launcher-root policy]
  (let [parsed (parse-launcher-args args)
        cwd (resolve-effective-cwd parsed process-cwd)
        basis-state (startup-basis launcher-root cwd policy)]
    {:cwd cwd
     :launcher-root launcher-root
     :launcher-debug? (:launcher-debug? parsed)
     :version? (:version? parsed)
     :psi-args (:psi-args parsed)
     :policy policy
     :basis (:basis basis-state)
     :manifest-info (:manifest-info basis-state)
     :command (build-clojure-command {:basis (:basis basis-state)
                                      :psi-args (:psi-args parsed)})}))

(ns build
  "tools.build script for psi.

   Usage:
     clojure -T:build lib         # build target/psi-VERSION.jar (library jar for Clojars)
     clojure -T:build uber        # build target/psi.jar + target/psi wrapper (standalone)
     clojure -T:build deploy      # deploy library jar to Clojars
     clojure -T:build clean       # remove target/"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]))

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(def psi-lib   'org.hugoduncan/psi)
(def class-dir "target/classes")
(def jar-file  "target/psi.jar")
(def wrapper   "target/psi")

(def basis
  (delay (b/create-basis {:project "deps.edn" :aliases [:run]})))

;; ---------------------------------------------------------------------------
;; Version
;; ---------------------------------------------------------------------------

(defn- version-string
  []
  (-> "bases/main/resources/psi/version.edn"
      slurp
      edn/read-string
      :version
      (or "unreleased")))

;; ---------------------------------------------------------------------------
;; Source paths — mirror :run alias exactly
;; ---------------------------------------------------------------------------

(def src-dirs
  ["bases/main/src"
   "bases/main/resources"
   "components/app-runtime/src"
   "components/agent-session/src"
   "components/agent-core/src"
   "components/ai/src"
   "components/engine/src"
   "components/query/src"
   "components/tui/src"
   "components/rpc/src"
   "extensions/auto-session-name/src"
   "extensions/commit-checks/src"
   "extensions/hello-ext/src"
   "extensions/mcp-tasks-run/src"
   "extensions/mementum/src"
   "extensions/munera/src"
   "extensions/plan-state-learning/src"
   "extensions/work-on/src"
   "extensions/workflow-display/src"
   "extensions/workflow-loader/src"])

;; ---------------------------------------------------------------------------
;; Tasks
;; ---------------------------------------------------------------------------

(defn- lib-jar-file
  [version]
  (str "target/psi-" version ".jar"))

(defn clean
  "Remove the target/ directory."
  [_]
  (b/delete {:path "target"}))

(defn lib
  "Build a library jar for Clojars: target/psi-VERSION.jar.
   Sources + resources only — no AOT, no bundled deps."
  [_]
  (let [version (version-string)
        jar     (lib-jar-file version)]
    (println (str "Building library jar " jar " ..."))

    ;; 1. Clean
    (b/delete {:path "target"})

    ;; 2. Write pom.xml
    (println "  Writing pom.xml ...")
    (b/write-pom {:class-dir class-dir
                  :lib       psi-lib
                  :version   version
                  :basis     @basis
                  :src-dirs  src-dirs
                  :pom-data  [[:description "Psi — AI coding agent"]
                              [:url "https://github.com/hugoduncan/psi"]
                              [:licenses
                               [:license
                                [:name "Eclipse Public License 2.0"]
                                [:url "https://www.eclipse.org/legal/epl-2.0/"]]]
                              [:scm
                               [:url "https://github.com/hugoduncan/psi"]
                               [:connection "scm:git:https://github.com/hugoduncan/psi.git"]
                               [:developerConnection "scm:git:ssh://git@github.com/hugoduncan/psi.git"]]]})

    ;; 3. Copy sources + resources
    (println "  Copying sources ...")
    (b/copy-dir {:src-dirs   src-dirs
                 :target-dir class-dir})

    ;; 3b. Copy bb.edn so bbin can read :bbin/bin at install time
    (println "  Copying bb.edn ...")
    (b/copy-file {:src    "bb.edn"
                  :target (str class-dir "/bb.edn")})

    ;; 4. Build thin jar (no AOT)
    (println "  Assembling library jar ...")
    (b/jar {:class-dir class-dir
            :jar-file  jar})

    (println)
    (println (str "Built: " jar))
    (println (str "       " class-dir "/META-INF/maven/" (namespace psi-lib) "/" (name psi-lib) "/pom.xml"))
    jar))

(defn deploy
  "Deploy the library jar to Clojars.
   Builds the library jar first if it is not already present.
   Requires CLOJARS_USERNAME and CLOJARS_PASSWORD env vars."
  [opts]
  (let [version (version-string)
        jar     (lib-jar-file version)]
    (when (= "unreleased" version)
      (throw (ex-info "Cannot deploy: version resource is 'unreleased'. Run bb release:tag first."
                      {:version version})))
    (when-not (.exists (io/file jar))
      (println (str "Library jar not found — building first ..."))
      (lib opts))
    (println (str "Deploying " jar " to Clojars as " psi-lib " " version " ..."))
    ;; deps-deploy is loaded dynamically so :deploy alias must be active
    (let [deploy-fn (requiring-resolve 'deps-deploy.deps-deploy/deploy)]
      (deploy-fn {:installer :remote
                  :artifact  jar
                  :pom-file  (b/pom-path {:lib       psi-lib
                                          :class-dir class-dir})}))
    (println "Done.")))

(defn uber
  "Build target/psi.jar and target/psi wrapper script.

   Requires Java 22+. The wrapper passes --enable-native-access=ALL-UNNAMED
   which is required by jline-terminal-ffm (TUI dependency)."
  [_]
  (let [version (version-string)]
    (println (str "Building psi " version " ..."))

    ;; 1. Clean
    (b/delete {:path "target"})

    ;; 2. Copy all source + resource trees into class-dir
    (println "  Copying sources ...")
    (b/copy-dir {:src-dirs   src-dirs
                 :target-dir class-dir})

    ;; 3. AOT compile psi.main (has :gen-class) and psi.app-runtime (has :gen-class)
    ;;    Everything else loads dynamically at runtime.
    (println "  AOT compiling entry points ...")
    (b/compile-clj {:basis      @basis
                    :src-dirs   src-dirs
                    :class-dir  class-dir
                    :ns-compile '[psi.main psi.app-runtime]
                    :compile-opts {:direct-linking true}})

    ;; 4. Assemble uberjar
    (println "  Assembling uberjar ...")
    (b/uber {:class-dir  class-dir
             :basis      @basis
             :uber-file  jar-file
             :main       'psi.main
             :manifest  {"Implementation-Title"   "psi"
                         "Implementation-Version" version}
             ;; Merge duplicate service files (common with Clojure libs)
             :conflict-handlers {"^META-INF/services/.*" :append
                                 "^META-INF/.*\\.SF$"    :ignore
                                 "^META-INF/.*\\.DSA$"   :ignore
                                 "^META-INF/.*\\.RSA$"   :ignore}})

    ;; 5. Write wrapper script
    (println "  Writing wrapper script ...")
    (let [wrapper-file (io/file wrapper)]
      (spit wrapper-file
            (str/join "\n"
                      ["#!/usr/bin/env bash"
                       "# psi launcher wrapper — requires Java 22+"
                       "# Generated by bb build:jar"
                       (str "# Version: " version)
                       "set -euo pipefail"
                       "SCRIPT_DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\""
                       "exec java --enable-native-access=ALL-UNNAMED \\"
                       "     -jar \"${SCRIPT_DIR}/psi.jar\" \"$@\""
                       ""]))
      (.setExecutable wrapper-file true false))

    (println)
    (println (str "Built: " jar-file))
    (println (str "       " wrapper " (wrapper script)"))
    (println)
    (println "Run with:")
    (println (str "  java --enable-native-access=ALL-UNNAMED -jar " jar-file))
    (println (str "  " wrapper))))

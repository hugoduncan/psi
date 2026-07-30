(ns ^:integration psi.build-jar-smoke-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.build-manifest :as build-manifest]
   [psi.build-smoke-support :as support]))

(defn- jar-entries
  [jar-path]
  (let [{:keys [exit out err]} (shell/sh "jar" "tf" jar-path)]
    (when-not (zero? exit)
      (throw (ex-info "jar tf failed"
                      {:jar-path jar-path
                       :exit exit
                       :out out
                       :err err})))
    (->> (str/split-lines out)
         set)))

(defn- jar-read-entry
  [jar-path entry]
  (let [{:keys [exit out err]} (shell/sh "unzip" "-p" jar-path entry)]
    (when-not (zero? exit)
      (throw (ex-info "unzip -p failed"
                      {:jar-path jar-path
                       :entry entry
                       :exit exit
                       :out out
                       :err err})))
    out))

(defn- build-lib!
  []
  (let [{:keys [exit out err]} (shell/sh "clojure" "-T:build" "lib")]
    (when-not (zero? exit)
      (throw (ex-info "clojure -T:build lib failed"
                      {:exit exit
                       :out out
                       :err err})))
    {:out out :err err}))

(defn- version-string
  []
  (-> "bases/main/resources/psi/version.edn" slurp read-string :version))

(defn- lib-jar-path
  []
  (str "target/psi-" (version-string) ".jar"))

(deftest ^:integration build-lib-jar-includes-runtime-packaging-regression-entries
  (testing "the built library jar contains runtime namespaces required by installed psi"
    (support/with-build-lock
      (build-lib!)
      (let [entries (jar-entries (lib-jar-path))]
        (is (contains? entries "psi/main.clj"))
        (is (contains? entries "hugoduncan/psi.clj"))
        (is (contains? entries "psi/agent_session/dispatch.clj"))
        (is (contains? entries "psi/state_kernel/dispatch.clj"))
        (is (contains? entries "psi/edit_clj/extension.clj"))
        (is (contains? entries "psi/github/extension.clj"))
        (is (contains? entries "extensions/logprobs.clj"))
        (is (contains? entries build-manifest/release-deps-resource-path))))))

(deftest ^:integration build-lib-jar-embeds-release-deps-metadata
  (testing "the built library jar carries stable release deps metadata with external runtime deps"
    (support/with-build-lock
      (build-lib!)
      (let [jar-path (lib-jar-path)
            release-deps (-> (jar-read-entry jar-path build-manifest/release-deps-resource-path)
                             edn/read-string)]
        (is (= {:mvn/version "1.5.1"}
               (get-in release-deps [:deps 'nrepl/nrepl])))
        (is (= {:git/tag "v0.2.71"
                :git/sha "38e6823"}
               (get-in release-deps [:deps 'io.github.timokramer/charm.clj])))
        (is (nil? (get-in release-deps [:deps 'psi/main])))
        (is (nil? (get-in release-deps [:deps 'psi/tui])))))))

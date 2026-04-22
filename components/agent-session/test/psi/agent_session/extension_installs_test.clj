(ns psi.agent-session.extension-installs-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.extension-installs :as installs]
   [psi.agent-session.test-support :as test-support])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "psi-extension-installs-test-"
                                      (into-array FileAttribute []))))

(defn- manifest-file
  [root rel]
  (let [f (java.io.File. root rel)]
    (.mkdirs (.getParentFile f))
    f))

(deftest compute-install-state-merges-user-and-project-with-project-precedence
  (let [home (tmp-dir)
        cwd  (.getAbsolutePath (tmp-dir))]
    (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                  installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
      (spit (installs/user-manifest-file)
            (pr-str {:deps {'foo/ext {:git/url "https://example.com/foo"
                                      :git/sha "abc"
                                      :psi/init 'foo.ext/init}
                            'support/lib {:mvn/version "1.2.3"}}}))
      (spit (installs/project-manifest-file cwd)
            (pr-str {:deps {'foo/ext {:mvn/version "2.0.0"
                                      :psi/init 'foo.ext/init}
                            'bar/ext {:local/root "/tmp/bar"
                                      :psi/init 'bar.ext/init}}}))
      (let [state (installs/compute-install-state cwd)]
        (is (= {:mvn/version "2.0.0" :psi/init 'foo.ext/init}
               (get-in state [:psi.extensions/effective :raw-deps 'foo/ext])))
        (is (= :project
               (get-in state [:psi.extensions/effective :entries-by-lib 'foo/ext :scope])))
        (is (= [:project :user]
               (get-in state [:psi.extensions/effective :entries-by-lib 'foo/ext :source-manifests])))
        (is (false? (get-in state [:psi.extensions/effective :entries-by-lib 'support/lib :extension?])))
        (is (= :not-applicable
               (get-in state [:psi.extensions/effective :entries-by-lib 'support/lib :status])))))))

(deftest compute-install-state-emits-expected-diagnostics
  (let [cwd (.getAbsolutePath (tmp-dir))
        home (tmp-dir)]
    (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                  installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
      (spit (installs/user-manifest-file)
            (pr-str {:deps {'foo/ext {:git/url "https://example.com/foo"
                                      :psi/init 'shared.init/init}
                            'bar/ext {:mvn/version "1.0.0"
                                      :psi/init 'shared.init/init}}}))
      (spit (installs/project-manifest-file cwd)
            (pr-str {:deps {'dev/ext {:local/root "/tmp/dev"
                                      :psi/init 'dev.ext/init}
                            'broken/ext {:psi/enabled true}}}))
      (let [diags (:psi.extensions/diagnostics (installs/compute-install-state cwd))]
        (is (some #(= :missing-git-sha (:category %)) diags))
        (is (some #(= :duplicate-init (:category %)) diags))
        (is (some #(= :project-local-root-nonreproducible (:category %)) diags))
        (is (some #(= :malformed-entry (:category %)) diags))))))

(deftest apply-installs-in-persists-last-apply-state
  (let [cwd (test-support/temp-cwd)
        home (tmp-dir)
        [ctx _session-id] (test-support/create-test-session {:persist? false :cwd cwd})]
    (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                  installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
      (spit (installs/project-manifest-file cwd)
            (pr-str {:deps {'foo/ext {:mvn/version "1.0.0"
                                      :psi/init 'foo.ext/init}}}))
      (let [{:keys [state status applied?]} (installs/apply-installs-in! ctx cwd)]
        (is (true? applied?))
        (is (= :restart-required status))
        (is (= :restart-required (get-in state [:psi.extensions/last-apply :status])))
        (is (= state (installs/extension-installs-state-in ctx)))))))

(deftest activation-plan-allows-inprocess-non-local-apply-when-not-removing-prior-deps
  (let [install-state {:psi.extensions/effective {:raw-deps {'foo/ext {:mvn/version "1.1.0"
                                                                       :psi/init 'foo.ext/init}}
                                                  :entries-by-lib {'foo/ext {:dep {:mvn/version "1.1.0"
                                                                                   :psi/init 'foo.ext/init}
                                                                             :extension? true
                                                                             :enabled? true}}}}
        previous-state {:psi.extensions/effective {:raw-deps {'foo/ext {:mvn/version "1.0.0"
                                                                        :psi/init 'foo.ext/init}}}}
        plan (installs/activation-plan install-state previous-state)]
    (is (true? (:deps-apply-safe? plan)))
    (is (= {'foo/ext {:mvn/version "1.1.0" :psi/init 'foo.ext/init}}
           (:deps-to-realize plan)))
    (is (empty? (:restart-required-libs plan)))))

(deftest eql-query-exposes-extension-install-config
  (let [cwd (test-support/temp-cwd)
        home (tmp-dir)
        [ctx session-id] (test-support/create-test-session {:persist? false :cwd cwd})]
    (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                  installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
      (spit (installs/project-manifest-file cwd)
            (pr-str {:deps {'foo/ext {:mvn/version "1.0.0"
                                      :psi/init 'foo.ext/init
                                      :psi/enabled false}
                            'support/lib {:mvn/version "2.0.0"}}}))
      (let [result (session/query-in ctx session-id
                                     [:psi.extensions/user-manifest
                                      :psi.extensions/project-manifest
                                      :psi.extensions/effective
                                      :psi.extensions/diagnostics
                                      :psi.extensions/last-apply])]
        (is (= {:deps {'foo/ext {:mvn/version "1.0.0"
                                 :psi/init 'foo.ext/init
                                 :psi/enabled false}
                       'support/lib {:mvn/version "2.0.0"}}}
               (:psi.extensions/project-manifest result)))
        (is (= false
               (get-in result [:psi.extensions/effective :entries-by-lib 'foo/ext :enabled?])))
        (is (= :not-applicable
               (get-in result [:psi.extensions/effective :entries-by-lib 'support/lib :status])))
        (is (vector? (:psi.extensions/diagnostics result)))))))

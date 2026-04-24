(ns psi.agent-session.extension-manifest-activation-test
  (:require
   [clojure.java.io :as io]
   [clojure.repl.deps]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [psi.agent-session.extension-installs :as installs]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.test-support :as test-support])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "psi-extension-manifest-activation-test-"
                                      (into-array FileAttribute []))))

(defn- manifest-file
  [root rel]
  (let [f (io/file root rel)]
    (.mkdirs (.getParentFile f))
    f))

(defn- write-local-extension!
  [root ns-sym body]
  (let [src-dir  (io/file root "src")
        rel-path (str (-> (str ns-sym)
                          (clojure.string/replace "." "/")
                          (clojure.string/replace "-" "_"))
                      ".clj")
        f        (io/file src-dir rel-path)]
    (.mkdirs (.getParentFile f))
    (spit (io/file root "deps.edn") (pr-str {:paths ["src"]}))
    (spit f body)
    f))

(defn- define-runtime-extension-ns! [ns-sym cmd-name]
  (create-ns ns-sym)
  (binding [*ns* (the-ns ns-sym)]
    (clojure.core/refer 'clojure.core)
    (intern *ns* 'init
            (fn [api]
              ((:register-command api) cmd-name
                                       {:description (str "hello from " cmd-name)
                                        :handler (fn [_] nil)})))))

(deftest reload-extensions-loads-manifest-local-root-extension-test
  (let [cwd        (test-support/temp-cwd)
        home       (tmp-dir)
        ext-root   (tmp-dir)
        _          (write-local-extension!
                    ext-root
                    'psi.test-manifest-ext
                    "(ns psi.test-manifest-ext)\n\n(defn init [api]\n  ((:register-command api) \"manifest-hello\" {:description \"hello from manifest\" :handler (fn [_] nil)}))\n")]
    (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                  installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
      (spit (installs/project-manifest-file cwd)
            (pr-str {:deps {'psi/test-manifest-ext {:local/root (.getAbsolutePath ext-root)
                                                    :psi/init 'psi.test-manifest-ext/init}}}))
      (is (= {:lib 'psi/test-manifest-ext
              :id (.getAbsolutePath (io/file ext-root "src/psi/test_manifest_ext.clj"))
              :kind :path
              :path (.getAbsolutePath (io/file ext-root "src/psi/test_manifest_ext.clj"))
              :init-var 'psi.test-manifest-ext/init}
             (installs/resolve-local-root-entry
              'psi/test-manifest-ext
              {:dep {:local/root (.getAbsolutePath ext-root)
                     :psi/init 'psi.test-manifest-ext/init}}))))))

(deftest reload-extensions-loads-git-manifest-entry-via-init-var-when-deps-realized-test
  (let [cwd       (test-support/temp-cwd)
        home      (tmp-dir)
        ns-sym    'psi.test.remote-ext
        _         (define-runtime-extension-ns! ns-sym "remote-hello")
        [ctx sid] (test-support/create-test-session {:persist? false :cwd cwd})]
    (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                  installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))]
      (spit (installs/project-manifest-file cwd)
            (pr-str {:deps {'psi/test-remote-ext {:git/url "https://example.com/ext"
                                                  :git/sha "abc123"
                                                  :psi/init 'psi.test.remote-ext/init}}}))
      (installs/persist-install-state-in!
       ctx
       {:psi.extensions/effective
        {:raw-deps
         {'psi/test-remote-ext {:git/url "https://example.com/ext"
                                :git/sha "abc123"
                                :psi/init 'psi.test.remote-ext/init}}}})
      (let [result (with-redefs [clojure.repl.deps/sync-deps (fn [& _] :ok)]
                     (ext-rt/reload-extensions-in! ctx sid [] cwd))]
        (is (= :loaded
               (get-in result [:install-state :psi.extensions/effective :entries-by-lib 'psi/test-remote-ext :status])))
        (is (= :applied
               (get-in result [:install-state :psi.extensions/last-apply :status])))
        (is (some #(= "manifest:psi/test-remote-ext" %)
                  (map str (ext/extensions-in (:extension-registry ctx)))))))))

(deftest reload-extensions-realizes-non-local-deps-when-safe-test
  (let [cwd       (test-support/temp-cwd)
        home      (tmp-dir)
        ns-sym    'psi.test.remote-ext
        _         (define-runtime-extension-ns! ns-sym "remote-hello")
        [ctx sid] (test-support/create-test-session {:persist? false :cwd cwd})]
    (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                  installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))
                  clojure.repl.deps/sync-deps (fn [& _] :ok)]
      (spit (installs/project-manifest-file cwd)
            (pr-str {:deps {'psi/test-remote-ext {:git/url "https://example.com/ext"
                                                  :git/sha "abc123"
                                                  :psi/init 'psi.test.remote-ext/init}}}))
      (installs/persist-install-state-in!
       ctx
       {:psi.extensions/effective
        {:raw-deps
         {'psi/test-remote-ext {:git/url "https://example.com/ext"
                                :git/sha "abc123"
                                :psi/init 'psi.test.remote-ext/init}}}})
      (let [result (ext-rt/reload-extensions-in! ctx sid [] cwd)]
        (is (true? (:deps-realized? result)))
        (is (= :loaded
               (get-in result [:install-state :psi.extensions/effective :entries-by-lib 'psi/test-remote-ext :status])))
        (is (= :applied
               (get-in result [:install-state :psi.extensions/last-apply :status])))))))

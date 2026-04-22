(ns psi.agent-session.project-nrepl-extension-install-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.extension-installs :as installs]
   [psi.agent-session.project-nrepl-config]
   [psi.agent-session.project-nrepl-ops :as project-nrepl-ops]
   [psi.agent-session.project-nrepl-started]
   [psi.agent-session.test-support :as test-support]))

(defn- manifest-file [root rel]
  (let [f (io/file root rel)]
    (.mkdirs (.getParentFile f))
    f))

(defn- write-local-extension! [root ns-sym body]
  (let [src-dir  (io/file root "src")
        rel-path (str (-> (str ns-sym)
                          (str/replace "." "/")
                          (str/replace "-" "_"))
                      ".clj")
        f        (io/file src-dir rel-path)]
    (.mkdirs (.getParentFile f))
    (spit (io/file root "deps.edn") (pr-str {:paths ["src"]}))
    (spit f body)
    f))

(deftest project-nrepl-eval-proves-startup-install-state-and-reload-apply-test
  (testing "project nREPL can observe startup install state and invoke reload/apply for manifest-backed extensions"
    (let [cwd       (test-support/temp-cwd)
          home      (test-support/temp-cwd)
          ext-root  (test-support/temp-cwd)
          _         (write-local-extension! ext-root
                                            'psi.test.project-nrepl-ext
                                            "(ns psi.test.project-nrepl-ext)\n\n(defn init [api]\n  ((:register-command api) \"project-repl-hello\" {:description \"hello from project nrepl manifest\" :handler (fn [_] nil)}))\n")
          [ctx sid] (test-support/create-test-session {:persist? false :cwd cwd})]
      (with-redefs [installs/user-manifest-file (fn [] (manifest-file home ".psi/agent/extensions.edn"))
                    installs/project-manifest-file (fn [_] (manifest-file cwd ".psi/extensions.edn"))
                    psi.agent-session.project-nrepl-config/resolve-config (fn [_]
                                                                            {:project-nrepl {:start-command ["bb" "nrepl-server"]}})
                    psi.agent-session.project-nrepl-started/start-instance-in!
                    (fn [ctx worktree-path command-vector]
                      (require 'psi.agent-session.project-nrepl-runtime)
                      (let [ensure-instance! (resolve 'psi.agent-session.project-nrepl-runtime/ensure-instance-in!)
                            update-instance! (resolve 'psi.agent-session.project-nrepl-runtime/update-instance-in!)
                            instance-in      (resolve 'psi.agent-session.project-nrepl-runtime/instance-in)
                            client-session   (fn [req]
                                               (let [code (or (:code req) (get req :code) (get req "code"))]
                                                 (cond
                                                   (= code "(keys (get-in (psi.agent-session.extension-installs/extension-installs-state-in psi.agent-session.main/session-state) [:psi.extensions/effective :entries-by-lib]))")
                                                   [{:status #{"done"}
                                                     :value (pr-str (keys (get-in (installs/extension-installs-state-in ctx)
                                                                                  [:psi.extensions/effective :entries-by-lib])))}]

                                                   (= code "(get-in (psi.agent-session.core/reload-extension-installs-in! psi.agent-session.main/session-state \"SESSION-ID\") [:install-state :psi.extensions/last-apply :status])")
                                                   [{:status #{"done"}
                                                     :value (pr-str (get-in (session/reload-extension-installs-in! ctx sid)
                                                                            [:install-state :psi.extensions/last-apply :status]))}]

                                                   :else
                                                   [{:status #{"done"}
                                                     :value (pr-str :ok)}])))]
                        (ensure-instance! ctx {:worktree-path worktree-path
                                               :acquisition-mode :started
                                               :command-vector command-vector})
                        (update-instance! ctx worktree-path
                                          (fn [instance]
                                            (assoc instance
                                                   :lifecycle-state :ready
                                                   :readiness true
                                                   :active-session-id "project-repl-test-session"
                                                   :can-eval? true
                                                   :can-interrupt? true
                                                   :runtime-handle {:client-session client-session})))
                        (instance-in ctx worktree-path)))]
        (spit (installs/project-manifest-file cwd)
              (pr-str {:deps {'psi/test-project-nrepl-ext {:local/root ext-root
                                                           :psi/init 'psi.test.project-nrepl-ext/init}}}))
        (installs/persist-install-state-in! ctx (installs/compute-install-state cwd))
        (let [{:keys [project-repl]} (project-nrepl-ops/perform! ctx sid {:op "start" :worktree-path cwd})]
          (is (= :started (:status project-repl)))
          (let [{startup :project-repl}
                (project-nrepl-ops/perform! ctx sid {:op "eval"
                                                     :worktree-path cwd
                                                     :code "(keys (get-in (psi.agent-session.extension-installs/extension-installs-state-in psi.agent-session.main/session-state) [:psi.extensions/effective :entries-by-lib]))"})
                {reload :project-repl}
                (project-nrepl-ops/perform! ctx sid {:op "eval"
                                                     :worktree-path cwd
                                                     :code "(get-in (psi.agent-session.core/reload-extension-installs-in! psi.agent-session.main/session-state \"SESSION-ID\") [:install-state :psi.extensions/last-apply :status])"})]
            (is (= :ok (:status startup)))
            (is (= (pr-str '(psi/test-project-nrepl-ext)) (:value startup)))
            (is (= :ok (:status reload)))
            (is (= (pr-str :applied) (:value reload)))))))))

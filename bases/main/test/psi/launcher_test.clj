(ns psi.launcher-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.launcher :as launcher]
   [psi.launcher.extensions :as extensions]))

(deftest parse-launcher-args-test
  (testing "consumes launcher-owned flags and preserves psi arg order"
    (is (= {:cwd "/tmp/work"
            :launcher-debug? true
            :psi-args ["--tui" "--model" "gpt-5"]}
           (launcher/parse-launcher-args ["--launcher-debug"
                                          "--tui"
                                          "--cwd" "/tmp/work"
                                          "--model" "gpt-5"]))))
  (testing "unknown flags remain psi runtime args"
    (is (= {:cwd nil
            :launcher-debug? false
            :psi-args ["--rpc-edn" "--nrepl" "7777"]}
           (launcher/parse-launcher-args ["--rpc-edn" "--nrepl" "7777"]))))
  (testing "missing cwd value fails clearly"
    (let [ex (try
               (launcher/parse-launcher-args ["--cwd"])
               nil
               (catch clojure.lang.ExceptionInfo e
                 e))]
      (is (= "Missing value for --cwd" (ex-message ex)))
      (is (= "--cwd" (-> ex ex-data :arg))))))

(deftest resolve-effective-cwd-test
  (testing "defaults to process cwd"
    (is (= "/repo/project"
           (launcher/resolve-effective-cwd {:cwd nil} "/repo/project"))))
  (testing "uses absolute cwd override as-is"
    (is (= "/tmp/other"
           (launcher/resolve-effective-cwd {:cwd "/tmp/other"} "/repo/project"))))
  (testing "resolves relative cwd override against process cwd"
    (is (= "/repo/project/subdir"
           (launcher/resolve-effective-cwd {:cwd "subdir"} "/repo/project")))))

(deftest manifest-path-test
  (is (= "/home/alice/.psi/agent/extensions.edn"
         (launcher/user-manifest-path "/home/alice")))
  (is (= "/repo/project/.psi/extensions.edn"
         (launcher/project-manifest-path "/repo/project"))))

(deftest psi-self-basis-test
  (let [repo-config {:deps {'psi/main {:local/root "bases/main"}
                            'psi/app-runtime {:local/root "components/app-runtime"}
                            'org.clojure/clojure {:mvn/version "1.12.4"}}}]
    (testing "development policy absolutizes local roots"
      (is (= '{psi/main {:local/root "/repo/psi/bases/main"}
               psi/app-runtime {:local/root "/repo/psi/components/app-runtime"}
               org.clojure/clojure {:mvn/version "1.12.4"}
               nrepl/nrepl {:mvn/version "1.5.1"}}
             (:deps (with-redefs [launcher/repo-basis-config (constantly repo-config)]
                      (launcher/psi-self-basis "/repo/psi" :development))))))
    (testing "installed policy keeps repo component roots coherent via absolute local roots"
      (is (= '{psi/main {:local/root "/repo/psi/bases/main"}
               psi/app-runtime {:local/root "/repo/psi/components/app-runtime"}
               org.clojure/clojure {:mvn/version "1.12.4"}
               nrepl/nrepl {:mvn/version "1.5.1"}}
             (:deps (with-redefs [launcher/repo-basis-config (constantly repo-config)]
                      (launcher/psi-self-basis "/repo/psi" :installed))))))))

(deftest build-clojure-command-test
  (let [command (launcher/build-clojure-command {:basis {:deps {'foo/bar {:mvn/version "1.0.0"}}}
                                                 :psi-args ["--tui" "--model" "gpt-5"]})]
    (is (= ["clojure" "-Sdeps"] (subvec command 0 2)))
    (is (= '{:deps {foo/bar {:mvn/version "1.0.0"}}}
           (read-string (nth command 2))))
    (is (= ["-M" "-m" "psi.main" "--tui" "--model" "gpt-5"]
           (subvec command 3)))))

(deftest manifest-state-test
  (testing "manifest state reports defaulted and inferred libs from expansion results"
    (let [user-manifest {:deps {'psi/mementum {:local/root "user-root"}}}
          project-manifest {:deps {'psi/mementum {}
                                   'third-party/ext {:mvn/version "1.0.0"}}}]
      (with-redefs [launcher/user-manifest-path (constantly "/tmp/user.edn")
                    launcher/project-manifest-path (constantly "/tmp/project.edn")
                    extensions/read-manifest-file (fn [path]
                                                    (case path
                                                      "/tmp/user.edn" user-manifest
                                                      "/tmp/project.edn" project-manifest))]
        (is (= ['psi/mementum]
               (:defaulted-libs (launcher/manifest-state "/repo/psi" "/repo/project" :installed))))
        (is (= ['psi/mementum]
               (:inferred-init-libs (launcher/manifest-state "/repo/psi" "/repo/project" :installed))))))))

(deftest startup-basis-expands-recognized-psi-owned-minimal-manifest-entry-into-basis-deps
  (let [repo-config {:deps {'psi/main {:local/root "bases/main"}
                            'org.clojure/clojure {:mvn/version "1.12.4"}}}
        manifest-info {:user-path "/tmp/user.edn"
                       :project-path "/tmp/project/.psi/extensions.edn"
                       :user-present? false
                       :project-present? true
                       :user-manifest {:deps {}}
                       :project-manifest {:deps {'psi/workflow-loader {}}}
                       :merged-manifest {:deps {'psi/workflow-loader {}}}
                       :expanded-manifest {:deps {'psi/workflow-loader {:local/root "/repo/psi/extensions/workflow-loader"
                                                                        :psi/init 'extensions.workflow-loader/init}}}
                       :defaulted-libs ['psi/workflow-loader]
                       :inferred-init-libs ['psi/workflow-loader]}
        result (with-redefs [launcher/repo-basis-config (constantly repo-config)
                             launcher/manifest-state (fn [_ _ _] manifest-info)]
                 (launcher/startup-basis "/repo/psi" "/repo/project" :installed))]
    (is (= {:local/root "/repo/psi/extensions/workflow-loader"}
           (get-in result [:basis :deps 'psi/workflow-loader])))
    (is (= 'extensions.workflow-loader/init
           (get-in result [:manifest-info :expanded-manifest :deps 'psi/workflow-loader :psi/init])))))

(deftest launch-plan-test
  (let [basis-state {:basis {:deps {'foo/bar {:mvn/version "1.0.0"}}}
                     :manifest-info {:user-present? false
                                     :project-present? true
                                     :merged-manifest {:deps {'foo/bar {:mvn/version "1.0.0"}}}
                                     :defaulted-libs []
                                     :inferred-init-libs []}}
        plan (with-redefs [launcher/startup-basis (fn [_ _ _] basis-state)]
               (launcher/launch-plan ["--launcher-debug" "--rpc-edn"]
                                     "/repo/project"
                                     "/repo/psi"
                                     :development))]
    (is (= {:cwd "/repo/project"
            :launcher-root "/repo/psi"
            :launcher-debug? true
            :psi-args ["--rpc-edn"]
            :policy :development
            :basis {:deps {'foo/bar {:mvn/version "1.0.0"}}}
            :manifest-info {:user-present? false
                            :project-present? true
                            :merged-manifest {:deps {'foo/bar {:mvn/version "1.0.0"}}}
                            :defaulted-libs []
                            :inferred-init-libs []}}
           (dissoc plan :command)))
    (is (= ["clojure" "-Sdeps"] (subvec (:command plan) 0 2)))
    (is (= '{:deps {foo/bar {:mvn/version "1.0.0"}}}
           (read-string (nth (:command plan) 2))))
    (is (= ["-M" "-m" "psi.main" "--rpc-edn"]
           (subvec (:command plan) 3)))))

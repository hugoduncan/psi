(ns psi.launcher-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.launcher :as launcher]
   [psi.launcher.extensions :as extensions]))

(deftest parse-launcher-args-test
  (testing "consumes launcher-owned flags and preserves psi arg order"
    (is (= {:cwd "/tmp/work"
            :launcher-debug? true
            :version? false
            :psi-args ["--tui" "--model" "gpt-5"]}
           (launcher/parse-launcher-args ["--launcher-debug"
                                          "--tui"
                                          "--cwd" "/tmp/work"
                                          "--model" "gpt-5"]))))
  (testing "unknown flags remain psi runtime args"
    (is (= {:cwd nil
            :launcher-debug? false
            :version? false
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
                      (launcher/psi-self-basis "/repo/psi" :installed))))))
    (testing "jar policy emits single mvn coord for the release version"
      (is (= '{org.hugoduncan/psi {:mvn/version "0.1.42"}
               nrepl/nrepl {:mvn/version "1.5.1"}}
             (:deps (with-redefs [launcher/release-version (constantly "0.1.42")]
                      (launcher/psi-self-basis "/repo/psi" :jar))))))
    (testing "jar policy throws when version resource is unreleased"
      (let [ex (try
                 (with-redefs [launcher/release-version (constantly nil)]
                   (launcher/psi-self-basis "/repo/psi" :jar))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= :basis-construction (-> ex ex-data :stage)))))))

(deftest build-deps-clj-args-test
  (let [args (launcher/build-deps-clj-args {:basis-file "/tmp/psi-basis-123.edn"
                                            :psi-args   ["--tui" "--model" "gpt-5"]})]
    (is (= ["-Sdeps-file" "/tmp/psi-basis-123.edn" "-M" "-m" "psi.main" "--tui" "--model" "gpt-5"]
           args)))
  (testing "no psi-args"
    (is (= ["-Sdeps-file" "/tmp/psi-basis-123.edn" "-M" "-m" "psi.main"]
           (launcher/build-deps-clj-args {:basis-file "/tmp/psi-basis-123.edn"
                                          :psi-args   []})))))

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

(deftest startup-basis-jar-policy-omits-psi-owned-extensions
  (testing "jar policy drops psi-owned extensions from basis — already bundled in org.hugoduncan/psi"
    (let [manifest-info {:user-path "/tmp/user.edn"
                         :project-path "/tmp/project/.psi/extensions.edn"
                         :user-present? false
                         :project-present? true
                         :user-manifest {:deps {}}
                         :project-manifest {:deps {'psi/mementum {}
                                                   'psi/workflow-loader {}}}
                         :merged-manifest {:deps {'psi/mementum {}
                                                  'psi/workflow-loader {}}}
                         :expanded-manifest {:deps {'psi/mementum {:mvn/version :psi/release-version
                                                                   :psi/init 'extensions.mementum/init}
                                                    'psi/workflow-loader {:mvn/version :psi/release-version
                                                                          :psi/init 'extensions.workflow-loader/init}}}
                         :defaulted-libs ['psi/mementum 'psi/workflow-loader]
                         :inferred-init-libs ['psi/mementum 'psi/workflow-loader]}
          result (with-redefs [launcher/release-version (constantly "0.1.42")
                               launcher/manifest-state (fn [_ _ _] manifest-info)]
                   (launcher/startup-basis "/repo/psi" "/repo/project" :jar))]
      ;; psi self-dep is the single mvn coord
      (is (= {:mvn/version "0.1.42"}
             (get-in result [:basis :deps 'org.hugoduncan/psi])))
      ;; psi-owned extensions are NOT added as separate Maven artifacts
      (is (nil? (get-in result [:basis :deps 'psi/mementum])))
      (is (nil? (get-in result [:basis :deps 'psi/workflow-loader])))))
  (testing "jar policy retains third-party manifest deps in basis"
    (let [manifest-info {:user-path "/tmp/user.edn"
                         :project-path "/tmp/project/.psi/extensions.edn"
                         :user-present? false
                         :project-present? true
                         :user-manifest {:deps {}}
                         :project-manifest {:deps {'third-party/ext {:mvn/version "2.0.0"}}}
                         :merged-manifest {:deps {'third-party/ext {:mvn/version "2.0.0"}}}
                         :expanded-manifest {:deps {'third-party/ext {:mvn/version "2.0.0"}}}
                         :defaulted-libs []
                         :inferred-init-libs []}
          result (with-redefs [launcher/release-version (constantly "0.1.42")
                               launcher/manifest-state (fn [_ _ _] manifest-info)]
                   (launcher/startup-basis "/repo/psi" "/repo/project" :jar))]
      (is (= {:mvn/version "2.0.0"}
             (get-in result [:basis :deps 'third-party/ext])))))
  (testing "jar policy throws when version resource is unreleased"
    (let [manifest-info {:user-path "/tmp/user.edn"
                         :project-path "/tmp/project/.psi/extensions.edn"
                         :user-present? false
                         :project-present? false
                         :user-manifest {:deps {}}
                         :project-manifest {:deps {}}
                         :merged-manifest {:deps {}}
                         :expanded-manifest {:deps {}}
                         :defaulted-libs []
                         :inferred-init-libs []}
          ex (try
               (with-redefs [launcher/release-version (constantly nil)
                             launcher/manifest-state (fn [_ _ _] manifest-info)]
                 (launcher/startup-basis "/repo/psi" "/repo/project" :jar))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :basis-construction (-> ex ex-data :stage))))))

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
    ;; :psi/init is stripped from basis deps (not forwarded to clojure classpath)
    (is (= {:local/root "/repo/psi/extensions/workflow-loader"}
           (get-in result [:basis :deps 'psi/workflow-loader])))
    ;; :psi/init is also stripped from manifest-info expanded-manifest (same expanded-deps map)
    (is (nil? (get-in result [:manifest-info :expanded-manifest :deps 'psi/workflow-loader :psi/init])))))

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
            :version? false
            :psi-args ["--rpc-edn"]
            :policy :development
            :basis {:deps {'foo/bar {:mvn/version "1.0.0"}}}
            :basis-edn (pr-str {:deps {'foo/bar {:mvn/version "1.0.0"}}})
            :manifest-info {:user-present? false
                            :project-present? true
                            :merged-manifest {:deps {'foo/bar {:mvn/version "1.0.0"}}}
                            :defaulted-libs []
                            :inferred-init-libs []}}
           plan))
    (is (= {:deps {'foo/bar {:mvn/version "1.0.0"}}}
           (read-string (:basis-edn plan))))))

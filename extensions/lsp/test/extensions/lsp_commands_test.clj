(ns extensions.lsp-commands-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.lsp :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest init-registers-lsp-commands-test
  (testing "init registers lsp status/restart commands and notifies ui"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/lsp.clj"})
          api' (assoc api
                      :cwd-fn (fn [] "/repo")
                      :register-post-tool-processor (fn [spec]
                                                      ((:mutate api) 'psi.extension/register-post-tool-processor spec))
                      :ensure-service (fn [spec]
                                        ((:mutate api) 'psi.extension/ensure-service spec))
                      :service-request (fn [spec]
                                         ((:mutate api) 'psi.extension/service-request
                                                        (assoc spec :response {"result" {"capabilities" {"textDocumentSync" {"change" 2}}}})))
                      :service-notify (fn [spec]
                                        ((:mutate api) 'psi.extension/service-notify spec))
                      :get-service (fn [_] nil))]
      (sut/init api')
      (is (contains? (:commands @state) "lsp-status"))
      (is (contains? (:commands @state) "lsp-restart"))
      (is (= "lsp loaded"
             (-> @state :notifications last :text))))))

(deftest workspace-status-lines-test
  (testing "status lines summarize workspace service/init/document state"
    (reset! sut/state {:initialized-workspaces #{"/repo"}
                       :documents {"/repo/src/foo.clj" {:open? true :version 2 :text "x"}
                                   "/repo/src/foo/core.clj" {:open? true :version 1 :text "y"}}})
    (let [{:keys [api]} (nullable/create-nullable-extension-api
                         {:path "/test/lsp.clj"
                          :query-fn (fn [q]
                                      (if (= [{:psi.service/services [:psi.service/key
                                                                      :psi.service/status
                                                                      :psi.service/command
                                                                      :psi.service/cwd
                                                                      :psi.service/transport
                                                                      :psi.service/ext-path]}]
                                             q)
                                        {:psi.service/services [{:psi.service/key [:lsp "/repo"]
                                                                 :psi.service/status :running
                                                                 :psi.service/command ["clojure-lsp"]
                                                                 :psi.service/cwd "/repo"
                                                                 :psi.service/transport :stdio
                                                                 :psi.service/ext-path "/test/lsp.clj"}]}
                                        {}))})
          lines (sut/workspace-status-lines (assoc api :list-services (fn []
                                                                        (:psi.service/services
                                                                         ((:query api)
                                                                          [{:psi.service/services [:psi.service/key
                                                                                                   :psi.service/status
                                                                                                   :psi.service/command
                                                                                                   :psi.service/cwd
                                                                                                   :psi.service/transport
                                                                                                   :psi.service/ext-path]}]))))
                                            {:cwd "/repo"})]
      (is (= "LSP workspace: /repo" (first lines)))
      (is (some #(= "Initialized: true" %) lines))
      (is (some #(= "Service status: :running" %) lines))
      (is (some #(= "Tracked documents: 2" %) lines))
      (is (some #(re-find #"/repo/src/foo.clj" %) lines)))))

(deftest lsp-restart-command-test
  (testing "restart command stops old workspace service and ensures a new one"
    (reset! sut/state {:initialized-workspaces #{"/repo"}
                       :documents {"/repo/src/foo.clj" {:open? true :version 2 :text "x"}
                                   "/repo/src/foo/core.clj" {:open? true :version 1 :text "y"}}})
    (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/lsp.clj"})
          api' (assoc api
                      :register-post-tool-processor (fn [spec]
                                                      ((:mutate api) 'psi.extension/register-post-tool-processor spec))
                      :ensure-service (fn [spec]
                                        ((:mutate api) 'psi.extension/ensure-service spec))
                      :stop-service (fn [key]
                                      ((:mutate api) 'psi.extension/stop-service {:key key}))
                      :service-notify (fn [spec]
                                        ((:mutate api) 'psi.extension/service-notify spec)))
          printed (atom [])]
      (with-redefs [println (fn [& xs] (swap! printed conj (apply str xs)))]
        (sut/restart-workspace! api' {:cwd "/repo"})
        (is (= [{:key [:lsp "/repo"]
                 :type :subprocess
                 :spec {:command ["clojure-lsp"]
                        :cwd "/repo"
                        :transport :stdio
                        :protocol :json-rpc
                        :env nil}}]
               (:services @state)))
        (is (not (contains? (:initialized-workspaces @sut/state) "/repo")))
        (is (= {}
               (:documents @sut/state)))
        (is (some #(re-find #"Restarted LSP workspace /repo" %) @printed))))))

(deftest warm-start-current-workspace-test
  (testing "warm-start initializes the current workspace when service hooks are available"
    (reset! sut/state {:initialized-workspaces #{}
                       :document-diagnostic-support {}
                       :documents {}})
    (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/lsp.clj"})
          api' (assoc api
                      :cwd-fn (fn [] "/repo")
                      :ensure-service (fn [spec]
                                        ((:mutate api) 'psi.extension/ensure-service spec))
                      :service-request (fn [spec]
                                         ((:mutate api) 'psi.extension/service-request
                                                        (assoc spec :response {"result" {"capabilities" {"textDocumentSync" {"change" 2}}}})))
                      :service-notify (fn [spec]
                                        ((:mutate api) 'psi.extension/service-notify spec))
                      :get-service (fn [_] nil))]
      (sut/warm-start-current-workspace! api')
      (Thread/sleep 50)
      (is (= [{:key [:lsp "/repo"]
               :type :subprocess
               :spec {:command ["clojure-lsp"]
                      :cwd "/repo"
                      :transport :stdio
                      :protocol :json-rpc
                      :env nil}}]
             (:services @state)))
      (is (contains? (:initialized-workspaces @sut/state) "/repo")))))

(deftest lsp-status-command-handler-test
  (testing "registered lsp-status command prints current workspace status"
    (reset! sut/state {:initialized-workspaces #{"/repo"}
                       :document-diagnostic-support {}
                       :documents {"/repo/src/foo.clj" {:open? true :version 2 :text "x"}}})
    (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/lsp.clj"})
          api' (assoc api
                      :register-post-tool-processor (fn [spec]
                                                      ((:mutate api) 'psi.extension/register-post-tool-processor spec))
                      :list-services (fn []
                                       [{:psi.service/key [:lsp "/repo"]
                                         :psi.service/status :running
                                         :psi.service/command ["clojure-lsp"]
                                         :psi.service/cwd "/repo"
                                         :psi.service/transport :stdio
                                         :psi.service/ext-path "/test/lsp.clj"}]))
          printed (atom [])]
      (with-redefs [println (fn [& xs] (swap! printed conj (apply str xs)))]
        (sut/init (assoc api' :cwd-fn (fn [] "/repo")))
        ((get-in @state [:commands "lsp-status" :handler]) "")
        (is (some #(= "LSP workspace: /repo" %) @printed))
        (is (some #(= "Tracked documents: 1" %) @printed))))))

(deftest lsp-status-command-handler-explicit-path-test
  (testing "registered lsp-status command accepts an explicit path argument"
    (reset! sut/state {:initialized-workspaces #{"/repo"}
                       :documents {"/repo/src/foo.clj" {:open? true :version 2 :text "x"}}})
    (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/lsp.clj"})
          api' (assoc api
                      :register-post-tool-processor (fn [spec]
                                                      ((:mutate api) 'psi.extension/register-post-tool-processor spec))
                      :list-services (fn []
                                       [{:psi.service/key [:lsp "/repo"]
                                         :psi.service/status :running
                                         :psi.service/command ["clojure-lsp"]
                                         :psi.service/cwd "/repo"
                                         :psi.service/transport :stdio
                                         :psi.service/ext-path "/test/lsp.clj"}]))
          printed (atom [])]
      (with-redefs [println (fn [& xs] (swap! printed conj (apply str xs)))]
        (sut/init (assoc api' :cwd-fn (fn [] "/other")))
        ((get-in @state [:commands "lsp-status" :handler]) "/repo/.git")
        (is (some #(= "LSP workspace: /repo" %) @printed))))))

(deftest lsp-restart-command-handler-test
  (testing "registered lsp-restart command restarts current workspace and closes open docs"
    (reset! sut/state {:initialized-workspaces #{"/repo"}
                       :documents {"/repo/src/foo.clj" {:open? true :version 2 :text "x"}}})
    (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/lsp.clj"})
          api' (assoc api
                      :register-post-tool-processor (fn [spec]
                                                      ((:mutate api) 'psi.extension/register-post-tool-processor spec))
                      :ensure-service (fn [spec]
                                        ((:mutate api) 'psi.extension/ensure-service spec))
                      :stop-service (fn [key]
                                      ((:mutate api) 'psi.extension/stop-service {:key key}))
                      :service-notify (fn [spec]
                                        ((:mutate api) 'psi.extension/service-notify spec)))
          printed (atom [])]
      (with-redefs [println (fn [& xs] (swap! printed conj (apply str xs)))]
        (sut/init (assoc api' :cwd-fn (fn [] "/repo")))
        ((get-in @state [:commands "lsp-restart" :handler]) "")
        (is (= [{:key [:lsp "/repo"]
                 :type :subprocess
                 :spec {:command ["clojure-lsp"]
                        :cwd "/repo"
                        :transport :stdio
                        :protocol :json-rpc
                        :env nil}}]
               (:services @state)))
        (is (= "textDocument/didClose"
               (get-in @state [:service-notifications 0 :payload "method"])))
        (is (= {}
               (:documents @sut/state)))
        (is (some #(re-find #"Restarted LSP workspace /repo" %) @printed))))))

(deftest lsp-restart-command-handler-explicit-path-test
  (testing "registered lsp-restart command accepts an explicit path argument"
    (reset! sut/state {:initialized-workspaces #{"/repo"}
                       :documents {"/repo/src/foo.clj" {:open? true :version 2 :text "x"}}})
    (let [{:keys [api state]} (nullable/create-nullable-extension-api {:path "/test/lsp.clj"})
          api' (assoc api
                      :register-post-tool-processor (fn [spec]
                                                      ((:mutate api) 'psi.extension/register-post-tool-processor spec))
                      :ensure-service (fn [spec]
                                        ((:mutate api) 'psi.extension/ensure-service spec))
                      :stop-service (fn [key]
                                      ((:mutate api) 'psi.extension/stop-service {:key key}))
                      :service-notify (fn [spec]
                                        ((:mutate api) 'psi.extension/service-notify spec)))
          printed (atom [])]
      (with-redefs [println (fn [& xs] (swap! printed conj (apply str xs)))]
        (sut/init (assoc api' :cwd-fn (fn [] "/other")))
        ((get-in @state [:commands "lsp-restart" :handler]) "/repo/.git")
        (is (= [{:key [:lsp "/repo"]
                 :type :subprocess
                 :spec {:command ["clojure-lsp"]
                        :cwd "/repo"
                        :transport :stdio
                        :protocol :json-rpc
                        :env nil}}]
               (:services @state)))
        (is (some #(re-find #"Restarted LSP workspace /repo" %) @printed))))))

(ns psi.agent-session.extensions-api-test
  "Tests for the extension API surface — registration and mutation routing."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.mutations.extensions :as extension-mutations]
   [psi.agent-session.extensions :as ext]
   [psi.tool-registry.registry :as tool-registry]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.test-support :as test-support]))

(deftest extension-api-registration-test
  (testing "API :on registers handlers"
    (let [reg (ext/create-registry)
          _   (ext/register-extension-in! reg "/ext/test")
          api (ext/create-extension-api reg "/ext/test" {})]
      ((:on api) "session_switch" (fn [_] nil))
      (is (= 1 (ext/handler-count-in reg)))))

  (testing "API :register-tool registers tools"
    (let [reg (ext/create-registry)
          _   (ext/register-extension-in! reg "/ext/test")
          api (ext/create-extension-api reg "/ext/test" {})]
      ((:register-tool api) {:name "ext-tool" :label "ET" :description "test" :format-request (fn [_] "ext-tool")})
      (is (contains? (tool-registry/tool-names-in reg) "ext-tool"))))

  (testing "API :register-command registers commands"
    (let [reg (ext/create-registry)
          _   (ext/register-extension-in! reg "/ext/test")
          api (ext/create-extension-api reg "/ext/test" {})]
      ((:register-command api) "greet" {:handler (fn [_] nil) :description "Say hi"})
      (is (contains? (ext/command-names-in reg) "greet"))))

  (testing "API :register-operation delegates to deterministic operation runtime registration"
    (let [reg (ext/create-registry)
          _   (ext/register-extension-in! reg "/ext/test")
          calls (atom [])
          api (ext/create-extension-api reg "/ext/test"
                                        {:register-deterministic-operation-fn
                                         (fn [ext-path op]
                                           (swap! calls conj [ext-path op])
                                           {:id (:id op)})})]
      (is (= {:id "github/search-issues-by-label"}
             ((:register-operation api)
              {:id "github/search-issues-by-label"
               :handler (fn [_] {:status :ok :data {}})})))
      (is (= [["/ext/test" "github/search-issues-by-label"]]
             (mapv (fn [[ext-path op]] [ext-path (:id op)]) @calls)))))

  (testing "API :register-flag with default"
    (let [reg (ext/create-registry)
          _   (ext/register-extension-in! reg "/ext/test")
          api (ext/create-extension-api reg "/ext/test" {})]
      ((:register-flag api) "debug" {:type :boolean :default false})
      (is (= false ((:get-flag api) "debug")))))

  (testing "API :query delegates to runtime query fn"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          runtime-fns {:query-fn (fn [q] {:echo q})}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= {:echo [:x]} ((:query api) [:x])))))

  (testing "API :query can read runtime UI capabilities through real extension runtime fns"
    (let [[ctx session-id] (test-support/create-test-session {:persist? false
                                                              :ui-type :console})
          reg             (:extension-registry ctx)
          ext-path        "/ext/test"
          _               (ext/register-extension-in! reg ext-path)
          _               (ext/set-allowed-events-in! reg ext-path #{})
          runtime-fns     (runtime-fns/make-extension-runtime-fns ctx session-id ext-path)
          api             (ext/create-extension-api reg ext-path runtime-fns)]
      (is (= {:psi.ui/type :console
              :psi.ui/available? true
              :psi.ui/capabilities []
              :psi.ui/actions []
              :psi.ui/make-visible-action
              {:psi.ui.action/id :psi.ui.action/make-visible
               :psi.ui.action/capability :psi.ui.capability/make-visible
               :psi.ui.action/label "Show Psi UI"
               :psi.ui.action/description "Bring the active Psi UI to the foreground."
               :psi.ui.action/available? false
               :psi.ui.action/unavailable-reason :psi.ui.unavailable.reason/unsupported-capability
               :psi.ui.action/unavailable-message "The attached UI does not support making itself visible."}
              :psi.ui/diagnostic nil}
             ((:query api) [:psi.ui/type
                            :psi.ui/available?
                            :psi.ui/capabilities
                            :psi.ui/actions
                            :psi.ui/make-visible-action
                            :psi.ui/diagnostic])))))

  (testing "API :list-services delegates to runtime query fn"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          services    [{:psi.service/key [:svc "/repo"]
                        :psi.service/status :running}]
          runtime-fns {:query-fn (fn [q]
                                   (is (= [{:psi.service/services [:psi.service/key
                                                                   :psi.service/status
                                                                   :psi.service/command
                                                                   :psi.service/cwd
                                                                   :psi.service/transport
                                                                   :psi.service/ext-path
                                                                   :psi.service/notification-count]}]
                                          q))
                                   {:psi.service/services services})}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= services ((:list-services api))))))

  (testing "API :ui-type delegates to runtime ui-type fn"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          runtime-fns {:ui-type-fn (fn [] :emacs)}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= :emacs (:ui-type api)))))

  (testing "API :mutate delegates to runtime mutate fn"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          runtime-fns {:mutate-fn (fn [op params] {:op op :params params})}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= {:op 'psi.extension/test :params {:a 1 :ext-path "/ext/test"}}
             ((:mutate api) 'psi.extension/test {:a 1})))
      (is (= {:op 'psi.other/test :params {:a 1}}
             ((:mutate api) 'psi.other/test {:a 1})))
      (is (= {:op 'psi.extension/test :params {:a 1 :ext-path "/custom"}}
             ((:mutate api) 'psi.extension/test {:a 1 :ext-path "/custom"})))
      (is (= {:op 'psi.extension.workflow/create :params {:type :agent :ext-path "/ext/test"}}
             ((:mutate api) 'psi.extension.workflow/create {:type :agent})))))

  (testing "mid-system mutation declares optional provenance params"
    (is (= [:psi/agent-session-ctx :session-id :text :source :ext-path]
           (-> extension-mutations/inject-mid-system-message
               :config
               ::pco/params))))

  (testing "API mid-system helper delegates to runtime mutation and normalizes result"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          calls       (atom [])
          runtime-fns {:mutate-fn (fn [op params]
                                    (swap! calls conj {:op op :params params})
                                    {:psi.extension/ok? true})}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= {:ok true :error nil :reason nil}
             ((:inject-mid-system-message api) "Use shorter answers")))
      (is (= {:ok true :error nil :reason nil}
             ((:inject-mid-system-message api) "Use citations" {:source :trusted})))
      (is (= [{:op 'psi.extension/inject-mid-system-message
               :params {:text "Use shorter answers" :ext-path "/ext/test"}}
              {:op 'psi.extension/inject-mid-system-message
               :params {:text "Use citations" :source :trusted :ext-path "/ext/test"}}]
             @calls))))

  (testing "API session lifecycle helpers delegate to runtime mutate fn"
    (let [reg         (ext/create-registry)
          _           (ext/register-extension-in! reg "/ext/test")
          runtime-fns {:mutate-fn (fn [op params] {:op op :params params})}
          api         (ext/create-extension-api reg "/ext/test" runtime-fns)]
      (is (= {:op 'psi.extension/create-session
              :params {:session-name "feature"
                       :worktree-path "/repo/feature"
                       :system-prompt "prompt"}}
             ((:create-session api) {:session-name "feature"
                                     :worktree-path "/repo/feature"
                                     :system-prompt "prompt"})))
      (is (= {:op 'psi.extension/switch-session
              :params {:session-id "s1"}}
             ((:switch-session api) "s1")))
      (is (= {:op 'psi.extension/set-worktree-path
              :params {:session-id "s1"
                       :worktree-path "/repo/feature"}}
             ((:set-worktree-path api) "s1" "/repo/feature")))
      (is (= {:op 'psi.extension/notify
              :params {:content "done"}}
             ((:notify api) "done")))
      (is (= {:op 'psi.extension/notify
              :params {:content "done"
                       :role "assistant"
                       :custom-type "workflow-status"}}
             ((:notify api) "done" {:role "assistant" :custom-type "workflow-status"})))
      (is (= {:op 'psi.extension/append-message
              :params {:role "user"
                       :content "Please continue"}}
             ((:append-message api) "user" "Please continue"))))))

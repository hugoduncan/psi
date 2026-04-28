(ns psi.agent-session.workflow-step-prep-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.persistence :as persistence]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-runtime :as workflow-runtime]
   [psi.agent-session.workflow-step-prep :as workflow-step-prep]))

(defn- create-session-context
  []
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(deftest materialize-step-session-preload-test
  (testing "session preload materializes value and transcript entries from canonical sources"
    (let [[ctx session-id] (create-session-context)
          definition {:definition-id "preload-proof"
                      :name "preload-proof"
                      :step-order ["plan" "review"]
                      :steps {"plan" {:executor {:type :agent :profile "planner"}
                                      :prompt-template "$INPUT"
                                      :input-bindings {:input {:source :workflow-input :path [:input]}}
                                      :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                                      :retry-policy {:max-attempts 1 :retry-on #{}}}
                              "review" {:executor {:type :agent :profile "reviewer"}
                                        :prompt-template "$INPUT"
                                        :input-bindings {:input {:source :step-output :path ["plan" :outputs :text]}}
                                        :session-preload [{:kind :value
                                                           :role "user"
                                                           :binding {:source :workflow-input :path [:original]}}
                                                          {:kind :value
                                                           :role "assistant"
                                                           :binding {:source :step-output :path ["plan"]}}
                                                          {:kind :session-transcript
                                                           :step-id "plan"
                                                           :projection {:type :tail :turns 1 :tool-output false}}]
                                        :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                                        :retry-policy {:max-attempts 1 :retry-on #{}}}}}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state definition)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "preload-proof"
                                                                   :run-id "run-preload-proof"
                                                                   :workflow-input {:input "review"
                                                                                    :original "Original request"}})]
                       (-> s
                           (assoc-in [:workflows :runs "run-preload-proof" :step-runs "plan" :accepted-result]
                                     {:outcome :ok :outputs {:text "plan text"} :diagnostics {:summary "summary"}})
                           (assoc-in [:workflows :runs "run-preload-proof" :step-runs "plan" :attempts]
                                     [{:attempt-id "a1" :status :succeeded :execution-session-id session-id}])))))
          _ (persistence/append-entry-in! ctx session-id
                                          (persistence/message-entry
                                           {:role "user" :content "Build it"}))
          _ (persistence/append-entry-in! ctx session-id
                                          (persistence/message-entry
                                           {:role "assistant"
                                            :content [{:type :text :text "Reading"}
                                                      {:type :tool_use :id "t1" :name "read" :input {:path "x"}}]}))
          _ (persistence/append-entry-in! ctx session-id
                                          (persistence/message-entry
                                           {:role "tool"
                                            :content [{:type :tool_result :tool-use-id "t1" :content "ok"}]}))
          _ (persistence/append-entry-in! ctx session-id
                                          (persistence/message-entry
                                           {:role "assistant"
                                            :content [{:type :text :text "Done"}]}))
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-preload-proof")
          preload (workflow-step-prep/materialize-step-session-preload ctx workflow-run "review")]
      (is (= [{:role "user" :content "Original request"}
              {:role "assistant" :content (pr-str {:outcome :ok
                                                   :outputs {:text "plan text"}
                                                   :diagnostics {:summary "summary"}})}
              {:role "user" :content "Build it"}
              {:role "assistant" :content [{:type :text :text "Reading"}]}
              {:role "assistant" :content [{:type :text :text "Done"}]}]
             preload)))))

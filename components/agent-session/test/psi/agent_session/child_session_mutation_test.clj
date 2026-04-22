(ns psi.agent-session.child-session-mutation-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-core.core]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.prompt-runtime]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.query.core :as query]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest create-child-session-creates-runtime-and-no-parent-statechart-test
  (testing "create-child-session allocates child runtime handles without changing the parent phase"
    (let [[ctx session-id] (create-session-context {:persist? false})
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)

      (let [result   (mutate 'psi.extension/create-child-session
                             {:session-name "child"
                              :system-prompt "child prompt"
                              :developer-prompt "# Agent Profile: helper\n\nKeep it brief."
                              :developer-prompt-source :explicit
                              :tool-defs []
                              :thinking-level :off})
            child-id (:psi.agent-session/session-id result)
            child-sd (ss/get-session-data-in ctx child-id)]
        (is (string? child-id))
        (is (not= session-id child-id))
        (is (= :idle (ss/sc-phase-in ctx session-id)))
        (is (ss/idle-in? ctx child-id))
        (is (some? (ss/agent-ctx-in ctx child-id)))
        (is (some? (ss/sc-session-id-in ctx child-id)))
        (is (= :explicit (:developer-prompt-source child-sd)))
        (is (= "# Agent Profile: helper\n\nKeep it brief." (:developer-prompt child-sd)))
        (is (= :agent (:spawn-mode child-sd)))))))

(deftest create-child-session-preserves-preloaded-messages-and-cache-breakpoints-test
  (testing "create-child-session can seed child runtime conversation and cache policy"
    (let [[ctx session-id] (create-session-context {:persist? false})
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)

      (let [messages [{:role "user" :content [{:type :text :text "use the skill"}]}
                      {:role "assistant" :content [{:type :text :text "# Skill Body"}]}]
            result   (mutate 'psi.extension/create-child-session
                             {:session-name "child"
                              :tool-defs []
                              :cache-breakpoints #{:system :tools}
                              :preloaded-messages messages})
            child-id (:psi.agent-session/session-id result)
            child-sd (ss/get-session-data-in ctx child-id)
            agent-msgs (:messages (psi.agent-core.core/get-data-in (ss/agent-ctx-in ctx child-id)))]
        (is (= #{:system :tools} (:cache-breakpoints child-sd)))
        (is (= messages agent-msgs))))))

(deftest create-child-session-can-store-prompt-component-selection-test
  (testing "create-child-session can persist child prompt component controls"
    (let [[ctx session-id] (create-session-context {:persist? false})
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)

      (let [selection {:agents-md? false
                       :extension-prompt-contributions []
                       :tool-names []
                       :skill-names []
                       :components #{}}
            result    (mutate 'psi.extension/create-child-session
                              {:session-name "child"
                               :system-prompt "helper"
                               :tool-defs []
                               :prompt-component-selection selection
                               :cache-breakpoints #{}})
            child-id  (:psi.agent-session/session-id result)
            child-sd  (ss/get-session-data-in ctx child-id)]
        (is (= (assoc selection
                      :include-preamble? false
                      :include-context-files? false
                      :include-skills? false
                      :include-runtime-metadata? false)
               (:prompt-component-selection child-sd)))
        (is (= #{} (:cache-breakpoints child-sd)))))))

(deftest create-child-session-inherits-parent-prompt-contributions-by-default-test
  (testing "create-child-session preserves parent prompt contributions when no explicit restriction is supplied"
    (let [[ctx session-id] (create-session-context {:persist? false})
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (dispatch/dispatch! ctx :session/register-prompt-contribution
                          {:session-id session-id
                           :ext-path "/ext/work-on"
                           :id "work-on"
                           :contribution {:section "Extension Capabilities"
                                          :content "tool: /work-on"
                                          :enabled true}}
                          {:origin :test})
      (let [child-id (:psi.agent-session/session-id
                      (mutate 'psi.extension/create-child-session
                              {:session-name "child"
                               :tool-defs []}))
            child-sd (ss/get-session-data-in ctx child-id)]
        (is (= [{:id "work-on"
                 :ext-path "/ext/work-on"
                 :section "Extension Capabilities"
                 :content "tool: /work-on"
                 :enabled true}]
               (mapv #(select-keys % [:id :ext-path :section :content :enabled])
                     (:prompt-contributions child-sd))))
        (is (str/includes? (prompt-request/effective-system-prompt child-sd)
                           "tool: /work-on"))))))

(deftest create-child-session-selection-filters-extension-contributions-coherently-test
  (testing "child selection allowlists extension prompt contributions consistently"
    (let [child-sd {:base-system-prompt "base"
                    :developer-prompt nil
                    :prompt-component-selection {:extension-prompt-contributions ["/ext/a"]}
                    :prompt-contributions [{:id "a" :ext-path "/ext/a" :content "A" :enabled true}
                                           {:id "b" :ext-path "/ext/b" :content "B" :enabled true}]
                    :tool-defs []}]
      (is (str/includes? (prompt-request/effective-system-prompt child-sd) "A"))
      (is (not (str/includes? (prompt-request/effective-system-prompt child-sd) "B"))))))

(deftest create-child-session-selection-rebuilds-minimal-base-prompt-and-filters-tools-test
  (testing "child selection can rebuild a reduced base prompt and align prompt-visible tools"
    (let [[ctx session-id] (create-session-context {:persist? false})
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (dispatch/dispatch! ctx :session/set-system-prompt-build-opts
                          {:session-id session-id
                           :opts {:context-files [{:path "/AGENTS.md" :content "Context text"}]
                                  :skills [{:name "skill-a" :description "A"
                                            :file-path "/s/SKILL.md" :base-dir "/s"
                                            :source :user :disable-model-invocation false}]
                                  :selected-tools ["read" "bash"]}}
                          {:origin :test})
      (let [selection {:agents-md? false
                       :extension-prompt-contributions []
                       :tool-names ["read"]
                       :skill-names []
                       :components #{:skills}}
            child-id  (:psi.agent-session/session-id
                       (mutate 'psi.extension/create-child-session
                               {:session-name "child"
                                :tool-defs [{:name "read" :description "Read"}
                                            {:name "bash" :description "Bash"}]
                                :prompt-component-selection selection}))
            child-sd  (ss/get-session-data-in ctx child-id)
            provider  (prompt-request/build-provider-conversation child-sd [])]
        (is (= (assoc selection
                      :include-preamble? false
                      :include-context-files? false
                      :include-skills? true
                      :include-runtime-metadata? false)
               (:prompt-component-selection child-sd)))
        (is (= ["read"] (mapv :name (:tool-defs child-sd))))
        (is (= [] (:skills child-sd)))
        (is (not (str/includes? (:base-system-prompt child-sd) "Context text")))
        (is (= ["read"] (mapv :name (:tools provider))))))))

(deftest run-agent-loop-in-session-targets-child-session-test
  (testing "run-agent-loop-in-session executes against child session while parent is streaming"
    (let [[ctx session-id] (create-session-context {:persist? false})
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)

      (let [child-id (:psi.agent-session/session-id
                      (mutate 'psi.extension/create-child-session
                              {:session-name "child"
                               :system-prompt "child prompt"
                               :tool-defs []
                               :thinking-level :off}))]
        (with-redefs [psi.agent-session.prompt-runtime/execute-prepared-request!
                      (fn [_ai-ctx _ctx sid prepared _progress-queue]
                        {:execution-result/turn-id (:prepared-request/id prepared)
                         :execution-result/session-id sid
                         :execution-result/assistant-message {:role "assistant"
                                                              :content [{:type :text :text (str "reply for " sid)}]
                                                              :stop-reason :stop
                                                              :timestamp (java.time.Instant/now)}
                         :execution-result/turn-outcome :turn.outcome/stop
                         :execution-result/tool-calls []
                         :execution-result/stop-reason :stop})]
          ;; Force the parent session into :streaming. Child execution must still work.
          (dispatch/dispatch! ctx :session/prompt {:session-id session-id} {:origin :core})
          (is (= :streaming (ss/sc-phase-in ctx session-id)))
          (is (ss/idle-in? ctx child-id))
          (let [result (mutate 'psi.extension/run-agent-loop-in-session
                               {:session-id child-id
                                :prompt "run in child"})]
            (is (true? (:psi.agent-session/agent-run-ok? result)))
            (is (= (str "reply for " child-id)
                   (:psi.agent-session/agent-run-text result)))
            (is (= :streaming (ss/sc-phase-in ctx session-id)))
            (is (ss/idle-in? ctx child-id))))))))

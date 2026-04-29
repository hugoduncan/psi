(ns psi.agent-session.runtime-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [psi.ai.model-registry :as model-registry]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-runtime]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.test-support :as test-support]
   [psi.recursion.core :as recursion]))

(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        (model-registry/init! {})))))

(defn- write-temp-models! [config]
  (let [tmp (java.io.File/createTempFile "psi-test-models" ".edn")]
    (spit tmp (pr-str config))
    (.getAbsolutePath tmp)))

(def ^:private sync-payload
  {:activation {:query-env-built? true
                :memory-status :ready}
   :capability-graph {:status :stable
                      :node-count 3
                      :capability-ids [:cap/a :cap/b]}
   :capture {:changed? true}
   :history-sync {:imported-count 1
                  :memory-entry-count 42}})

(deftest register-extension-run-fn-routes-through-prompt-lifecycle-test
  (let [ctx              (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd               (session/new-session-in! ctx nil {})
        session-id       (:session-id sd)
        sync-calls       (atom [])]
    (dispatch/clear-event-log!)
    (with-redefs [runtime/safe-maybe-sync-on-git-head-change!
                  (fn [ctx sid]
                    (swap! sync-calls conj {:ctx ctx :session-id sid})
                    {:ok? true})
                  psi.agent-session.prompt-runtime/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "done"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (runtime/register-extension-run-fn-in! ctx session-id nil {:provider :anthropic :id "stub"})
      (let [run-fn @(:extension-run-fn-atom ctx)]
        (is (fn? run-fn))
        (run-fn "hello from extension" :test-source)
        (Thread/sleep 50)
        (let [entries   (dispatch/event-log-entries)
              messages  (->> (persist/all-entries-in ctx session-id)
                             (filter #(= :message (:kind %)))
                             (map #(get-in % [:data :message]))
                             vec)
              user-msg  (first messages)
              assistant (second messages)]
          (is (some #(= :session/set-model (:event-type %)) entries))
          (is (some #(= :session/prompt-submit (:event-type %)) entries))
          (is (some #(= :session/prompt-prepare-request (:event-type %)) entries))
          (is (some #(= :session/prompt-record-response (:event-type %)) entries))
          (is (some #(= :session/prompt-finish (:event-type %)) entries))
          (is (= 1 (count @sync-calls)))
          (is (= session-id (:session-id (first @sync-calls))))
          (is (= "user" (:role user-msg)))
          (is (= "hello from extension" (get-in user-msg [:content 0 :text])))
          (is (= "assistant" (:role assistant))))))))

(deftest safe-maybe-sync-on-git-head-change-triggers-recursion-hook-test
  (let [recursion-ctx       (recursion/create-context)
        maybe-sync-calls    (atom [])
        orchestration-calls (atom [])
        [ctx* session-id]   (test-support/make-session-ctx
                             {:agent-ctx {}
                              :session-data {:worktree-path "/tmp/psi-runtime-hook-test"
                                             :session-id "runtime-test-session"}})
        ctx                 (assoc ctx*
                                   :cwd "/tmp/psi-runtime-hook-test"
                                   :memory-ctx nil
                                   :recursion-ctx recursion-ctx
                                   :extension-registry {:state (atom {:registration-order []
                                                                      :extensions {}})})]
    (with-redefs [runtime/invoke-git-head-sync!
                  (fn [_opts]
                    (swap! maybe-sync-calls conj {:session-id session-id})
                    {:ok? true
                     :changed? true
                     :reason :head-changed
                     :head "sha-2"
                     :previous-head "sha-1"
                     :classification {:kind :commit-created
                                      :notify-extensions? true}
                     :sync sync-payload})
                  recursion/orchestrate-manual-trigger-in!
                  (fn [rctx trigger opts]
                    (swap! orchestration-calls conj {:rctx rctx
                                                     :trigger trigger
                                                     :opts opts})
                    {:ok? true
                     :phase :completed
                     :trigger-result {:result :accepted}})]
      (let [result (runtime/safe-maybe-sync-on-git-head-change! ctx session-id)
            call   (first @orchestration-calls)]
        (testing "sync returns the git-sync payload"
          (is (= "sha-2" (:head result))))

        (testing "git-head hook is called once"
          (is (= 1 (count @maybe-sync-calls))))

        (testing "changed head triggers recursion orchestration"
          (is (= 1 (count @orchestration-calls)))
          (is (= recursion-ctx (:rctx call)))
          (is (= :graph-changed (get-in call [:trigger :type])))
          (is (= "git-head-changed" (get-in call [:trigger :reason])))
          (is (= :approve (get-in call [:opts :approval-decision])))
          (is (true? (get-in call [:opts :system-state :memory-ready])))
          (is (= :stable (get-in call [:opts :graph-state :status]))))))))

(deftest safe-maybe-sync-on-git-head-change-without-sync-flag-skips-recursion-trigger-test
  (let [orchestration-calls (atom 0)
        extension-events    (atom [])
        [ctx* session-id]   (test-support/make-session-ctx
                             {:agent-ctx {}
                              :session-data {:worktree-path "/tmp/psi-runtime-unchanged"
                                             :session-id "runtime-unchanged"}})
        ctx                 (assoc ctx*
                                   :cwd "/tmp/psi-runtime-unchanged"
                                   :recursion-ctx (recursion/create-context)
                                   :extension-registry {:state (atom {:registration-order []
                                                                      :extensions {}})})]
    (with-redefs [runtime/invoke-git-head-sync!
                  (fn [_opts]
                    {:ok? true
                     :changed? false
                     :reason :head-unchanged
                     :head "sha-1"
                     :previous-head "sha-1"})
                  recursion/orchestrate-manual-trigger-in!
                  (fn [_ _ _]
                    (swap! orchestration-calls inc)
                    {:ok? true})
                  ext/dispatch-in
                  (fn [_ event-name payload]
                    (swap! extension-events conj {:name event-name :payload payload})
                    {:cancelled? false :override nil :results []})]
      (runtime/safe-maybe-sync-on-git-head-change! ctx session-id)
      (is (= 0 @orchestration-calls))
      (is (= [] @extension-events)))))

(deftest safe-maybe-sync-on-git-head-change-dispatches-extension-event-test
  (let [extension-events (atom [])
        sid              "runtime-event"
        ctx              {:cwd "/tmp/psi-runtime-event"
                          :recursion-ctx (recursion/create-context)
                          :extension-registry {:state (atom {:registration-order []
                                                             :extensions {}})}
                          :state* (atom {:agent-session {:sessions {sid {:data      {:worktree-path "/tmp/psi-runtime-event"
                                                                                     :session-id    sid}
                                                                         :agent-ctx {}}}}})}]
    (with-redefs [runtime/invoke-git-head-sync!
                  (fn [_opts]
                    {:ok? true
                     :changed? true
                     :reason :head-changed
                     :head "abcdef1234567890"
                     :previous-head "1111111111111111"
                     :classification {:kind :commit-created
                                      :notify-extensions? true
                                      :parent-count 1}
                     :sync sync-payload})
                  ext/dispatch-in
                  (fn [_ event-name payload]
                    (swap! extension-events conj {:name event-name :payload payload})
                    {:cancelled? false :override nil :results []})]
      (runtime/safe-maybe-sync-on-git-head-change! ctx sid)
      (is (= 1 (count @extension-events)))
      (let [{:keys [name payload]} (first @extension-events)]
        (is (= "git_commit_created" name))
        (is (= sid (:session-id payload)))
        (is (= "/tmp/psi-runtime-event" (:workspace-dir payload)))
        (is (= "/tmp/psi-runtime-event" (:cwd payload)))
        (is (= "abcdef1234567890" (:head payload)))
        (is (= "1111111111111111" (:previous-head payload)))
        (is (= "head-changed" (:reason payload)))
        (is (= :commit-created (get-in payload [:classification :kind])))
        (is (str/includes? (str (:timestamp payload)) "T"))))))

(deftest resolve-api-key-in-prefers-oauth-then-provider-registry-test
  (testing "custom provider auth is resolved from model registry when oauth is absent"
    (let [path (write-temp-models!
                {:version   1
                 :providers {"minimax"
                             {:base-url "https://api.minimax.io/anthropic"
                              :api      :anthropic-messages
                              :auth     {:api-key "minimax-inline-key"}
                              :models   [{:id "MiniMax-M2.7"}]}}})]
      (try
        (model-registry/init! {:user-models-path path})
        (is (= "minimax-inline-key"
               (runtime/resolve-api-key-in {} "sid" {:provider :minimax :id "MiniMax-M2.7"})))
        (finally
          (java.io.File/.delete (java.io.File. path))))))

  (testing "oauth for selected provider still wins over provider-registry auth"
    (let [path (write-temp-models!
                {:version   1
                 :providers {"minimax"
                             {:base-url "https://api.minimax.io/anthropic"
                              :api      :anthropic-messages
                              :auth     {:api-key "minimax-inline-key"}
                              :models   [{:id "MiniMax-M2.7"}]}}})
          oauth-ctx (oauth/create-null-context {:credentials {:minimax {:type :api-key :key "oauth-key"}}})]
      (try
        (model-registry/init! {:user-models-path path})
        (is (= "oauth-key"
               (runtime/resolve-api-key-in {:oauth-ctx oauth-ctx} "sid" {:provider :minimax :id "MiniMax-M2.7"})))
        (finally
          (java.io.File/.delete (java.io.File. path))))))

  (testing "built-in anthropic remains unresolved without oauth or registry auth"
    (model-registry/init! {})
    (is (nil? (runtime/resolve-api-key-in {} "sid" {:provider :anthropic :id "claude-sonnet-4-6"})))))

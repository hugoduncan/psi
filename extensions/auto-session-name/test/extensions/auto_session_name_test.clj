(ns extensions.auto-session-name-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.auto-session-name :as sut]
   [psi.ai.model-registry :as model-registry]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(def local-helper-config
  {:version   1
   :providers {"local-helper"
               {:base-url "http://localhost:11434/v1"
                :api      :openai-completions
                :auth     {:auth-header? false}
                :models   [{:id "fast-free"
                            :name "Fast Free Local"
                            :supports-text true
                            :locality :local
                            :latency-tier :low
                            :cost-tier :zero
                            :input-cost 0.0
                            :output-cost 0.0}]}}})

(defn- reset-state! []
  (reset! @#'sut/state
          {:turn-counts {}
           :helper-session-ids #{}
           :last-auto-name-by-session {}
           :turn-interval 2
           :delay-ms 250
           :log-fn nil
           :ui nil})
  (model-registry/init! {}))

(deftest helper-model-selection-request-test
  (testing "extension defines its own explicit resolver request"
    (is (= {:mode :resolve
            :required [{:criterion :supports-text
                        :match :true}
                       {:criterion :latency-tier
                        :equals :low}
                       {:criterion :cost-tier
                        :one-of [:zero :low]}]
            :strong-preferences [{:criterion :locality
                                  :equals :local}
                                 {:criterion :input-cost
                                  :prefer :lower}
                                 {:criterion :output-cost
                                  :prefer :lower}]
            :weak-preferences [{:criterion :same-provider-as-session
                                :prefer :context-match}]
            :context {:session-model {:provider :anthropic
                                      :id "claude-sonnet-4-6"}}}
           (#'sut/helper-model-selection-request
            {:psi.agent-session/model-provider "anthropic"
             :psi.agent-session/model-id "claude-sonnet-4-6"}))))

  (testing "missing source-session model still yields an explicit extension-owned request"
    (is (= {:mode :resolve
            :required [{:criterion :supports-text
                        :match :true}
                       {:criterion :latency-tier
                        :equals :low}
                       {:criterion :cost-tier
                        :one-of [:zero :low]}]
            :strong-preferences [{:criterion :locality
                                  :equals :local}
                                 {:criterion :input-cost
                                  :prefer :lower}
                                 {:criterion :output-cost
                                  :prefer :lower}]
            :weak-preferences [{:criterion :same-provider-as-session
                                :prefer :context-match}]
            :context {:session-model {:provider nil
                                      :id nil}}}
           (#'sut/helper-model-selection-request {})))))

(deftest init-registers-handlers-and-load-notification-test
  (testing "init registers handlers and emits load notification"
    (reset-state!)
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"})]
      (sut/init api)
      (is (= 1 (count (get-in @state [:handlers "session_turn_finished"]))))
      (is (= 1 (count (get-in @state [:handlers "auto_session_name/rename_checkpoint"]))))
      (is (= [{:text "auto-session-name loaded" :level :info}]
             (:notifications @state))))))

(deftest turn-finished-schedules-every-second-turn-test
  (testing "every second completed turn schedules a delayed checkpoint event"
    (reset-state!)
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (is (nil? (handler {:session-id "s1" :turn-id "t1"})))
        (is (= [] (:scheduled-events @state)))
        (is (nil? (handler {:session-id "s1" :turn-id "t2"})))
        (is (= [{:ext-path "/test/auto_session_name.clj"
                 :delay-ms 250
                 :event-name "auto_session_name/rename_checkpoint"
                 :payload {:session-id "s1"
                           :turn-count 2}}]
               (:scheduled-events @state)))))))

(deftest checkpoint-handler-renames-session-on-valid-helper-result-test
  (testing "checkpoint handler queries source session, runs helper child session, and renames original session"
    (reset-state!)
    (let [calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"
                                :query-fn (fn [req]
                                            (swap! calls conj [:query req])
                                            (cond
                                              (= {:session-id "s1"
                                                  :query [{:psi.agent-session/session-entries
                                                           [:psi.session-entry/kind
                                                            :psi.session-entry/data]}]}
                                                 req)
                                              {:psi.agent-session/session-entries
                                               [{:psi.session-entry/kind :message
                                                 :psi.session-entry/data {:message {:role "user"
                                                                                    :content [{:type :text :text "Fix footer rendering"}]}}}
                                                {:psi.session-entry/kind :message
                                                 :psi.session-entry/data {:message {:role "assistant"
                                                                                    :content [{:type :text :text "I will inspect the selector path."}]}}}]}

                                              (= {:session-id "s1"
                                                  :query [:psi.agent-session/session-name]}
                                                 req)
                                              {:psi.agent-session/session-name "initial"}

                                              (= {:session-id "s1"
                                                  :query [:psi.agent-session/model-provider
                                                          :psi.agent-session/model-id]}
                                                 req)
                                              {:psi.agent-session/model-provider "anthropic"
                                               :psi.agent-session/model-id "claude-sonnet-4-6"}

                                              :else {}))
                                :mutate-fn (fn [op params]
                                             (swap! calls conj [:mutate op params])
                                             (case op
                                               psi.extension/create-child-session {:psi.agent-session/session-id "child-1"}
                                               psi.extension/run-agent-loop-in-session {:psi.agent-session/agent-run-ok? true
                                                                                        :psi.agent-session/agent-run-text "Fix footer rendering"}
                                               psi.extension/set-session-name {:psi.agent-session/session-name (:name params)}
                                               psi.extension/schedule-event {:psi.extension/scheduled? true}
                                               psi.extension/close-session {:psi.agent-session/close-session-closed? true
                                                                            :psi.agent-session/close-session-id (:session-id params)}
                                               {}))})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "auto_session_name/rename_checkpoint"]))]
        (is (nil? (handler {:session-id "s1" :turn-count 2})))
        (is (= ['psi.extension/create-child-session
                'psi.extension/run-agent-loop-in-session
                'psi.extension/set-session-name
                'psi.extension/close-session]
               (->> @calls
                    (keep (fn [[kind op _]] (when (= kind :mutate) op)))
                    (remove #{'psi.extension/schedule-event})
                    vec)))
        (is (= {:session-name               "auto-session-name"
                :system-prompt              "Infer a concise session title from the supplied conversation excerpt. Return title text only. No explanation. No quotes. No markdown."
                :tool-defs                  []
                :thinking-level             :off
                :prompt-component-selection {:agents-md? false
                                             :extension-prompt-contributions []
                                             :tool-names []
                                             :skill-names []
                                             :components #{}}
                :cache-breakpoints          #{}}
               (some->> @calls
                        (keep (fn [[kind op params]]
                                (when (and (= kind :mutate)
                                           (= op 'psi.extension/create-child-session))
                                  (select-keys params [:session-name
                                                       :system-prompt
                                                       :tool-defs
                                                       :thinking-level
                                                       :prompt-component-selection
                                                       :cache-breakpoints]))))
                        first)))
        (is (= {:provider :openai
                :id "gpt-5.3-codex-spark"
                :name "GPT-5.3 Codex Spark"}
               (some->> @calls
                        (keep (fn [[kind op params]]
                                (when (and (= kind :mutate)
                                           (= op 'psi.extension/run-agent-loop-in-session))
                                  (select-keys (:model params) [:provider :id :name]))))
                        first)))
        ;; Helper session closed: removed from helper-session-ids
        (is (false? (contains? (:helper-session-ids @@#'sut/state) "child-1")))
        ;; close-session called with the correct child session-id
        (is (= "child-1"
               (some->> @calls
                        (keep (fn [[kind op params]]
                                (when (and (= kind :mutate)
                                           (= op 'psi.extension/close-session))
                                  (:session-id params))))
                        first)))))))

(deftest checkpoint-handler-prefers-local-helper-when-available-test
  (testing "extension-owned helper policy picks a qualifying local helper when available"
    (reset-state!)
    (let [path (java.io.File/createTempFile "psi-test-local-helper" ".edn")]
      (try
        (spit path (pr-str local-helper-config))
        (model-registry/init! {:user-models-path (.getAbsolutePath path)})
        (let [calls (atom [])
              {:keys [api state]} (nullable/create-nullable-extension-api
                                   {:path "/test/auto_session_name.clj"
                                    :query-fn (fn [req]
                                                (cond
                                                  (= {:session-id "s1"
                                                      :query [{:psi.agent-session/session-entries
                                                               [:psi.session-entry/kind
                                                                :psi.session-entry/data]}]}
                                                     req)
                                                  {:psi.agent-session/session-entries
                                                   [{:psi.session-entry/kind :message
                                                     :psi.session-entry/data {:message {:role "user"
                                                                                        :content [{:type :text :text "Fix footer rendering"}]}}}]}

                                                  (= {:session-id "s1"
                                                      :query [:psi.agent-session/session-name]}
                                                     req)
                                                  {:psi.agent-session/session-name "initial"}

                                                  (= {:session-id "s1"
                                                      :query [:psi.agent-session/model-provider
                                                              :psi.agent-session/model-id]}
                                                     req)
                                                  {:psi.agent-session/model-provider "anthropic"
                                                   :psi.agent-session/model-id "claude-sonnet-4-6"}

                                                  :else {}))
                                    :mutate-fn (fn [op params]
                                                 (swap! calls conj [op params])
                                                 (case op
                                                   psi.extension/create-child-session {:psi.agent-session/session-id "child-1"}
                                                   psi.extension/run-agent-loop-in-session {:psi.agent-session/agent-run-ok? true
                                                                                            :psi.agent-session/agent-run-text "Fix footer rendering"}
                                                   psi.extension/set-session-name {:psi.agent-session/session-name (:name params)}
                                                   psi.extension/close-session {:psi.agent-session/close-session-closed? true
                                                                                :psi.agent-session/close-session-id (:session-id params)}
                                                   {}))})]
          (sut/init api)
          (let [handler (first (get-in @state [:handlers "auto_session_name/rename_checkpoint"]))]
            (is (nil? (handler {:session-id "s1" :turn-count 2})))
            (is (= {:provider :local-helper
                    :id "fast-free"
                    :name "Fast Free Local"}
                   (some->> @calls
                            (keep (fn [[op params]]
                                    (when (= op 'psi.extension/run-agent-loop-in-session)
                                      (select-keys (:model params) [:provider :id :name]))))
                            first)))))
        (finally
          (.delete path))))))

(deftest checkpoint-handler-invalid-helper-result-does-not-rename-test
  (testing "invalid helper result does not rename and falls back to notification"
    (reset-state!)
    (let [calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"
                                :query-fn (fn [req]
                                            (cond
                                              (= {:session-id "s1"
                                                  :query [{:psi.agent-session/session-entries
                                                           [:psi.session-entry/kind
                                                            :psi.session-entry/data]}]}
                                                 req)
                                              {:psi.agent-session/session-entries
                                               [{:psi.session-entry/kind :message
                                                 :psi.session-entry/data {:message {:role "user"
                                                                                    :content [{:type :text :text "Fix footer rendering"}]}}}]}

                                              (= {:session-id "s1"
                                                  :query [:psi.agent-session/session-name]}
                                                 req)
                                              {:psi.agent-session/session-name "initial"}

                                              (= {:session-id "s1"
                                                  :query [:psi.agent-session/model-provider
                                                          :psi.agent-session/model-id]}
                                                 req)
                                              {:psi.agent-session/model-provider "anthropic"
                                               :psi.agent-session/model-id "claude-sonnet-4-6"}

                                              :else {}))
                                :mutate-fn (fn [op params]
                                             (swap! calls conj [op params])
                                             (case op
                                               psi.extension/create-child-session {:psi.agent-session/session-id "child-1"}
                                               psi.extension/run-agent-loop-in-session {:psi.agent-session/agent-run-ok? true
                                                                                        :psi.agent-session/agent-run-text ""}
                                               psi.extension/schedule-event {:psi.extension/scheduled? true}
                                               psi.extension/close-session {:psi.agent-session/close-session-closed? true
                                                                            :psi.agent-session/close-session-id (:session-id params)}
                                               {}))})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "auto_session_name/rename_checkpoint"]))]
        (is (nil? (handler {:session-id "s1" :turn-count 2})))
        (is (not-any? #(= 'psi.extension/set-session-name (first %)) @calls))
        (is (= {:text "auto-session-name: rename checkpoint for session s1 at turn 2"
                :level :info}
               (last (:notifications @state))))))))

(deftest stale-checkpoint-does-not-start-helper-test
  (testing "stale checkpoint does not create helper child session"
    (reset-state!)
    (swap! @#'sut/state assoc :turn-counts {"s1" 4})
    (let [calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"
                                :query-fn (fn [_] {})
                                :mutate-fn (fn [op params]
                                             (swap! calls conj [op params])
                                             {})})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "auto_session_name/rename_checkpoint"]))]
        (is (nil? (handler {:session-id "s1" :turn-count 2})))
        (is (not-any? #(= 'psi.extension/create-child-session (first %)) @calls))))))

(deftest session-advanced-during-helper-run-does-not-rename-test
  (testing "checkpoint dropped as stale after helper run when session advanced"
    (reset-state!)
    (swap! @#'sut/state assoc :turn-counts {"s1" 2})
    (let [calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"
                                :query-fn (fn [req]
                                            (cond
                                              (= {:session-id "s1"
                                                  :query [{:psi.agent-session/session-entries
                                                           [:psi.session-entry/kind
                                                            :psi.session-entry/data]}]}
                                                 req)
                                              {:psi.agent-session/session-entries
                                               [{:psi.session-entry/kind :message
                                                 :psi.session-entry/data {:message {:role "user"
                                                                                    :content [{:type :text :text "Fix footer rendering"}]}}}]}

                                              (= {:session-id "s1"
                                                  :query [:psi.agent-session/session-name]}
                                                 req)
                                              {:psi.agent-session/session-name "initial"}

                                              (= {:session-id "s1"
                                                  :query [:psi.agent-session/model-provider
                                                          :psi.agent-session/model-id]}
                                                 req)
                                              {:psi.agent-session/model-provider "anthropic"
                                               :psi.agent-session/model-id "claude-sonnet-4-6"}

                                              :else {}))
                                :mutate-fn (fn [op params]
                                             (swap! calls conj [op params])
                                             (case op
                                               psi.extension/create-child-session {:psi.agent-session/session-id "child-1"}
                                               psi.extension/run-agent-loop-in-session (do (swap! @#'sut/state assoc :turn-counts {"s1" 3})
                                                                                           {:psi.agent-session/agent-run-ok? true
                                                                                            :psi.agent-session/agent-run-text "Fix footer rendering"})
                                               psi.extension/close-session {:psi.agent-session/close-session-closed? true
                                                                            :psi.agent-session/close-session-id (:session-id params)}
                                               {}))})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "auto_session_name/rename_checkpoint"]))]
        (is (nil? (handler {:session-id "s1" :turn-count 2})))
        (is (not-any? #(= 'psi.extension/set-session-name (first %)) @calls))))))

(deftest manual-rename-blocks-auto-overwrite-test
  (testing "manual rename blocks auto overwrite when current name differs from last auto name"
    (reset-state!)
    (swap! @#'sut/state assoc :last-auto-name-by-session {"s1" "Old auto name"})
    (let [calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"
                                :query-fn (fn [req]
                                            (cond
                                              (= {:session-id "s1"
                                                  :query [{:psi.agent-session/session-entries
                                                           [:psi.session-entry/kind
                                                            :psi.session-entry/data]}]}
                                                 req)
                                              {:psi.agent-session/session-entries
                                               [{:psi.session-entry/kind :message
                                                 :psi.session-entry/data {:message {:role "user"
                                                                                    :content [{:type :text :text "Fix footer rendering"}]}}}]}

                                              (= {:session-id "s1"
                                                  :query [:psi.agent-session/session-name]}
                                                 req)
                                              {:psi.agent-session/session-name "Manual name"}

                                              (= {:session-id "s1"
                                                  :query [:psi.agent-session/model-provider
                                                          :psi.agent-session/model-id]}
                                                 req)
                                              {:psi.agent-session/model-provider "anthropic"
                                               :psi.agent-session/model-id "claude-sonnet-4-6"}

                                              :else {}))
                                :mutate-fn (fn [op params]
                                             (swap! calls conj [op params])
                                             (case op
                                               psi.extension/create-child-session {:psi.agent-session/session-id "child-1"}
                                               psi.extension/run-agent-loop-in-session {:psi.agent-session/agent-run-ok? true
                                                                                        :psi.agent-session/agent-run-text "Fix footer rendering"}
                                               psi.extension/close-session {:psi.agent-session/close-session-closed? true
                                                                            :psi.agent-session/close-session-id (:session-id params)}
                                               {}))})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "auto_session_name/rename_checkpoint"]))]
        (is (nil? (handler {:session-id "s1" :turn-count 2})))
        (is (not-any? #(= 'psi.extension/set-session-name (first %)) @calls))))))

(deftest unchanged-auto-owned-name-allows-update-test
  (testing "matching current auto-owned name still allows update and refreshes remembered auto name"
    (reset-state!)
    (swap! @#'sut/state assoc :last-auto-name-by-session {"s1" "Old auto name"})
    (let [calls (atom [])
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"
                                :query-fn (fn [req]
                                            (cond
                                              (= {:session-id "s1"
                                                  :query [{:psi.agent-session/session-entries
                                                           [:psi.session-entry/kind
                                                            :psi.session-entry/data]}]}
                                                 req)
                                              {:psi.agent-session/session-entries
                                               [{:psi.session-entry/kind :message
                                                 :psi.session-entry/data {:message {:role "user"
                                                                                    :content [{:type :text :text "Fix footer rendering"}]}}}]}

                                              (= {:session-id "s1"
                                                  :query [:psi.agent-session/session-name]}
                                                 req)
                                              {:psi.agent-session/session-name "Old auto name"}

                                              (= {:session-id "s1"
                                                  :query [:psi.agent-session/model-provider
                                                          :psi.agent-session/model-id]}
                                                 req)
                                              {:psi.agent-session/model-provider "anthropic"
                                               :psi.agent-session/model-id "claude-sonnet-4-6"}

                                              :else {}))
                                :mutate-fn (fn [op params]
                                             (swap! calls conj [op params])
                                             (case op
                                               psi.extension/create-child-session {:psi.agent-session/session-id "child-1"}
                                               psi.extension/run-agent-loop-in-session {:psi.agent-session/agent-run-ok? true
                                                                                        :psi.agent-session/agent-run-text "New inferred name"}
                                               psi.extension/set-session-name {:psi.agent-session/session-name (:name params)}
                                               psi.extension/close-session {:psi.agent-session/close-session-closed? true
                                                                            :psi.agent-session/close-session-id (:session-id params)}
                                               {}))})]
      (sut/init api)
      (let [handler (first (get-in @state [:handlers "auto_session_name/rename_checkpoint"]))]
        (is (nil? (handler {:session-id "s1" :turn-count 2})))
        (is (some #(= 'psi.extension/set-session-name (first %)) @calls))
        (is (= "New inferred name"
               (get-in @@#'sut/state [:last-auto-name-by-session "s1"])))))))

(deftest helper-session-turn-finished-is-ignored-test
  (testing "turn-finished events for helper child sessions do not schedule nested checkpoints"
    (reset-state!)
    (swap! @#'sut/state assoc :helper-session-ids #{"child-1"})
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/auto_session_name.clj"})]
      (sut/init api)
      (swap! @#'sut/state assoc :helper-session-ids #{"child-1"})
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))]
        (is (nil? (handler {:session-id "child-1" :turn-id "t1"})))
        (is (= [] (:scheduled-events @state)))))))

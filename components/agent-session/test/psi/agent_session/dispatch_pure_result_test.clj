(ns psi.agent-session.dispatch-pure-result-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.service-protocol]
   [psi.agent-session.services]))

(defn- clean-state [f]
  (dispatch/clear-handlers!)
  (dispatch/clear-event-log!)
  (dispatch/clear-dispatch-trace!)
  (dispatch/set-interceptors! nil)
  (try (f)
       (finally
         (dispatch/clear-handlers!)
         (dispatch/clear-event-log!)
         (dispatch/clear-dispatch-trace!)
         (dispatch/set-interceptors! nil))))

(use-fixtures :each clean-state)

(deftest pure-result-detection-test
  (testing "map with :db is a pure result"
    (is (true? (dispatch/pure-result? {:db {} :effects []}))))

  (testing "map with :root-state-update is a pure result"
    (is (true? (dispatch/pure-result? {:root-state-update identity}))))

  (testing "map with :effects is a pure result"
    (is (true? (dispatch/pure-result? {:effects []}))))

  (testing "map with both is a pure result"
    (is (true? (dispatch/pure-result? {:root-state-update identity :effects []}))))

  (testing "map with :return-key is a pure result"
    (is (true? (dispatch/pure-result? {:return-key [:agent-session :data]}))))

  (testing "nil is not a pure result"
    (is (false? (dispatch/pure-result? nil))))

  (testing "plain keyword is not a pure result"
    (is (false? (dispatch/pure-result? :ok))))

  (testing "map without session-update or effects is not a pure result"
    (is (false? (dispatch/pure-result? {:foo :bar})))))

(deftest pure-handler-apply-test
  (testing "pure handler session-update is applied via ctx fn"
    (let [session-data (atom {:is-streaming false})
          apply-fn (fn [_ctx f] (swap! session-data f))
          ctx {:apply-root-state-update-fn apply-fn}]
      (dispatch/register-handler!
       :go-streaming
       (fn [_ctx _data]
         {:root-state-update #(assoc % :is-streaming true)}))
      (dispatch/dispatch! ctx :go-streaming)
      (is (true? (:is-streaming @session-data)))))

  (testing "pure handler effects are executed via ctx fn"
    (let [seen-effects (atom [])
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          ctx {:execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :effects-only
       (fn [_ctx _data]
         {:effects [{:effect/type :notify} {:effect/type :log}]}))
      (dispatch/dispatch! ctx :effects-only)
      (is (= [{:effect/type :notify} {:effect/type :log}] @seen-effects))))

  (testing "pure handler can apply session update and execute effects"
    (let [session-data (atom {:retry-attempt 0})
          seen-effects (atom [])
          apply-fn (fn [_ctx f] (swap! session-data f))
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          ctx {:apply-root-state-update-fn apply-fn
               :execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :update-and-effect
       (fn [_ctx _data]
         {:root-state-update #(update % :retry-attempt inc)
          :effects [{:effect/type :schedule}]}))
      (dispatch/dispatch! ctx :update-and-effect)
      (is (= 1 (:retry-attempt @session-data)))
      (is (= [{:effect/type :schedule}] @seen-effects))))

  (testing "pure handler can return the first effect result when opted in"
    (let [execute-fn (fn [_ctx effect]
                       (case (:effect/type effect)
                         :runtime/tool-execute {:role "toolResult"
                                                :tool-call-id "call-1"
                                                :tool-name "read"
                                                :content [{:type :text :text "ok"}]}
                         :ignored))
          ctx {:execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :effect-result-test
       (fn [_ctx _data]
         {:effects [{:effect/type :runtime/tool-execute
                     :tool-name "read"
                     :args {}
                     :opts {}}
                    {:effect/type :ignored}]
          :return-effect-result? true}))
      (is (= {:role "toolResult"
              :tool-call-id "call-1"
              :tool-name "read"
              :content [{:type :text :text "ok"}]}
             (dispatch/dispatch! ctx :effect-result-test)))))

  (testing "replay suppresses pure handler effects while preserving state application"
    (let [session-data (atom {:retry-attempt 0})
          seen-effects (atom [])
          apply-fn (fn [_ctx f] (swap! session-data f))
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          ctx {:apply-root-state-update-fn apply-fn
               :execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :replay-update-and-effect
       (fn [_ctx _data]
         {:root-state-update #(update % :retry-attempt inc)
          :effects [{:effect/type :schedule}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :replay-update-and-effect nil {:replaying? true})))
      (is (= 1 (:retry-attempt @session-data)))
      (is (= [] @seen-effects))))

  (testing "replay suppresses effects-only pure handler execution"
    (let [seen-effects (atom [])
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          ctx {:execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :replay-effects-only
       (fn [_ctx _data]
         {:effects [{:effect/type :notify}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :replay-effects-only nil {:replaying? true})))
      (is (= [] @seen-effects))))

  (testing "retained event can be redispatched with replay semantics"
    (let [session-data (atom {:session-name "before"})
          seen-effects (atom [])
          apply-fn     (fn [_ctx f] (swap! session-data f))
          execute-fn   (fn [_ctx effect] (swap! seen-effects conj effect))
          ctx          {:apply-root-state-update-fn apply-fn
                        :execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :session/set-session-name
       (fn [_ctx {:keys [name]}]
         {:root-state-update #(assoc % :session-name name)
          :effects [{:effect/type :persist/journal-append-session-info-entry
                     :name name}]
          :return :ok}))
      (dispatch/dispatch! ctx :session/set-session-name {:name "after"})
      (let [entry (last (dispatch/event-log-entries))]
        (reset! session-data {:session-name "before"})
        (reset! seen-effects [])
        (is (= :ok (dispatch/replay-event-entry! ctx entry)))
        (is (= "after" (:session-name @session-data)))
        (is (= [] @seen-effects)))))

  (testing "replay-event-log! replays entries in order"
    (dispatch/clear-event-log!)
    (let [session-data (atom {:session-name "before"
                              :worktree-path "/repo/main"})
          seen-effects (atom [])
          apply-fn     (fn [_ctx f] (swap! session-data f))
          execute-fn   (fn [_ctx effect] (swap! seen-effects conj effect))
          ctx          {:apply-root-state-update-fn apply-fn
                        :execute-dispatch-effect-fn execute-fn}]
      (dispatch/register-handler!
       :session/set-session-name
       (fn [_ctx {:keys [name]}]
         {:root-state-update #(assoc % :session-name name)
          :effects [{:effect/type :persist/journal-append-session-info-entry
                     :name name}]
          :return :name-updated}))
      (dispatch/register-handler!
       :session/set-worktree-path
       (fn [_ctx {:keys [worktree-path]}]
         {:root-state-update #(assoc % :worktree-path worktree-path)
          :return :path-updated}))
      (dispatch/dispatch! ctx :session/set-session-name {:name "after"})
      (dispatch/dispatch! ctx :session/set-worktree-path {:worktree-path "/repo/feature"})
      (let [entries (dispatch/event-log-entries)]
        (reset! session-data {:session-name "before"
                              :worktree-path "/repo/main"})
        (reset! seen-effects [])
        (is (= [:name-updated :path-updated]
               (dispatch/replay-event-log! ctx entries)))
        (is (= {:session-name "after"
                :worktree-path "/repo/feature"}
               @session-data))
        (is (= [] @seen-effects)))))

  (testing "return-only pure handler result is not applied as state"
    (let [applied? (atom false)
          apply-fn (fn [_ctx _f] (reset! applied? true))
          ctx {:apply-root-state-update-fn apply-fn}]
      (dispatch/register-handler!
       :return-only
       (fn [_ctx _data] {:return :return-only-result}))
      (let [result (dispatch/dispatch! ctx :return-only)]
        (is (= :return-only-result result))
        (is (false? @applied?)))))

  (testing "pure handler without apply-fn on ctx does not crash"
    (dispatch/register-handler!
     :no-apply-fn
     (fn [_ctx _data]
       {:root-state-update #(assoc % :x true)}))
    (is (nil? (dispatch/dispatch! {} :no-apply-fn))))

  (testing "pure handler can return a post-update value via :return-key"
    (let [session-data (atom {:agent-session {:data {:session-name "before"}}})
          apply-fn     (fn [_ctx f] (swap! session-data f))
          read-fn      (fn [_ctx path] (get-in @session-data path))
          ctx          {:apply-root-state-update-fn apply-fn
                        :read-session-state-fn   read-fn}]
      (dispatch/register-handler!
       :return-key-test
       (fn [_ctx _data]
         {:root-state-update #(assoc-in % [:agent-session :data :session-name] "after")
          :return-key     [:agent-session :data]}))
      (is (= {:session-name "after"}
             (dispatch/dispatch! ctx :return-key-test)))))

  (testing "pure handler can apply a root-state update"
    (let [root-state (atom {:runtime {:nrepl nil}})
          apply-root-fn (fn [_ctx f] (swap! root-state f))
          ctx {:apply-root-state-update-fn apply-root-fn}]
      (dispatch/register-handler!
       :root-update-test
       (fn [_ctx _data]
         {:root-state-update #(assoc-in % [:runtime :nrepl] {:port 8888})}))
      (dispatch/dispatch! ctx :root-update-test)
      (is (= {:port 8888} (get-in @root-state [:runtime :nrepl])))))

  (testing "pure handler root-state-update can return a post-update value via :return-key"
    (let [root-state (atom {:agent-session {:data {:session-name "before"}}})
          apply-root-fn (fn [_ctx f] (swap! root-state f))
          read-fn (fn [_ctx path] (get-in @root-state path))
          ctx {:apply-root-state-update-fn apply-root-fn
               :read-session-state-fn read-fn}]
      (dispatch/register-handler!
       :root-update-return-key-test
       (fn [_ctx _data]
         {:root-state-update #(assoc-in % [:agent-session :data :session-name] "after")
          :return-key [:agent-session :data]}))
      (is (= {:session-name "after"}
             (dispatch/dispatch! ctx :root-update-return-key-test))))))

(deftest validation-interceptor-test
  (testing "validation hook can block dispatch after apply and before effects"
    (let [session-data (atom {:retry-attempt 0})
          seen-effects (atom [])
          apply-fn (fn [_ctx f] (swap! session-data f))
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          validate-fn (fn [_ctx _ictx] {:valid? false :reason :test-invalid})
          ctx {:apply-root-state-update-fn apply-fn
               :execute-dispatch-effect-fn execute-fn
               :validate-dispatch-result-fn validate-fn}]
      (dispatch/register-handler!
       :invalid-after-apply
       (fn [_ctx _data]
         {:root-state-update #(update % :retry-attempt inc)
          :effects [{:effect/type :schedule}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :invalid-after-apply)))
      (is (= 1 (:retry-attempt @session-data)))
      (is (= [] @seen-effects))
      (let [entry (last (dispatch/event-log-entries))]
        (is (true? (:blocked? entry)))
        (is (= :test-invalid (:block-reason entry))))))

  (testing "validation hook default false/nil result blocks dispatch"
    (let [seen-effects (atom [])
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          validate-fn (fn [_ctx _ictx] false)
          ctx {:execute-dispatch-effect-fn execute-fn
               :validate-dispatch-result-fn validate-fn}]
      (dispatch/register-handler!
       :invalid-effects-only
       (fn [_ctx _data]
         {:effects [{:effect/type :notify}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :invalid-effects-only)))
      (is (= [] @seen-effects))
      (let [entry (last (dispatch/event-log-entries))]
        (is (true? (:blocked? entry)))
        (is (= :validation-failed (:block-reason entry))))))

  (testing "validation hook truthy result allows effect execution"
    (let [seen-effects (atom [])
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          validate-fn (fn [_ctx _ictx] {:valid? true})
          ctx {:execute-dispatch-effect-fn execute-fn
               :validate-dispatch-result-fn validate-fn}]
      (dispatch/register-handler!
       :valid-effects-only
       (fn [_ctx _data]
         {:effects [{:effect/type :notify}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :valid-effects-only)))
      (is (= [{:effect/type :notify}] @seen-effects))))

  (testing "validator exception blocks effects with structured reason"
    (let [seen-effects (atom [])
          execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
          validate-fn (fn [_ctx _ictx] (throw (ex-info "validator boom" {})))
          ctx {:execute-dispatch-effect-fn execute-fn
               :validate-dispatch-result-fn validate-fn}]
      (dispatch/register-handler!
       :validator-throws
       (fn [_ctx _data]
         {:effects [{:effect/type :notify}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :validator-throws)))
      (is (= [] @seen-effects))
      (let [entry (last (dispatch/event-log-entries))]
        (is (true? (:blocked? entry)))
        (is (= :validator-exception (get-in entry [:block-reason :type])))))))

(deftest handler-exception-test
  (testing "handler exception is caught and returns nil"
    (dispatch/register-handler! :throws
                                (fn [_ _] (throw (ex-info "boom" {}))))
    (is (nil? (dispatch/dispatch! {} :throws)))
    (let [entry (last (dispatch/event-log-entries))]
      (is (= :throws (:event-type entry)))
      (is (= :pure (:pure-result-kind entry))))))

(deftest canonical-dispatch-trace-failure-paths-test
  (testing "dispatch handler exception records handler-result and completes with nil return"
    (dispatch/register-handler! :handler-throws
                                (fn [_ _] (throw (ex-info "boom" {}))))
    (is (nil? (dispatch/dispatch! {} :handler-throws {:x 1})))
    (let [entries (dispatch/dispatch-trace-entries)
          dispatch-id (:dispatch-id (first entries))
          by-id (filter #(= dispatch-id (:dispatch-id %)) entries)]
      (is (some #(and (= :dispatch/received (:trace/kind %))
                      (= :handler-throws (:event-type %)))
                by-id))
      (is (some #(and (= :dispatch/handler-result (:trace/kind %))
                      (= {:kind :pure-result
                          :effect-count 0
                          :has-root-state-update false
                          :has-return true
                          :return-key nil
                          :return-effect-result? false}
                         (:result %)))
                by-id))
      (is (some #(and (= :dispatch/completed (:trace/kind %))
                      (= nil (:result %)))
                by-id))))

  (testing "effect execution exception records effect-start effect-finish error and failed dispatch"
    (dispatch/clear-dispatch-trace!)
    (let [ctx {:execute-dispatch-effect-fn (fn [_ _]
                                             (throw (ex-info "effect boom" {})))}]
      (dispatch/register-handler! :effect-throws
                                  (fn [_ _]
                                    {:effects [{:effect/type :effect/fail
                                                :value 1}]
                                     :return :ok}))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"effect boom"
                            (dispatch/dispatch! ctx :effect-throws {:x 1})))
      (let [entries (dispatch/dispatch-trace-entries)
            dispatch-id (:dispatch-id (first entries))
            by-id (filter #(= dispatch-id (:dispatch-id %)) entries)]
        (is (some #(and (= :dispatch/effect-start (:trace/kind %))
                        (= :effect/fail (:effect-type %)))
                  by-id))
        (is (some #(and (= :dispatch/effect-finish (:trace/kind %))
                        (= :effect/fail (:effect-type %))
                        (= "effect boom" (:error-message %)))
                  by-id))
        (is (some #(and (= :dispatch/failed (:trace/kind %))
                        (= :effect-throws (:event-type %))
                        (= "effect boom" (:error-message %)))
                  by-id)))))

  (testing "service request error payload is traced as service-response is-error"
    (dispatch/clear-dispatch-trace!)
    (let [dispatch-id (dispatch/next-dispatch-id)
          request-fn-var (resolve 'psi.agent-session.service-protocol/send-service-request!)]
      (with-redefs [psi.agent-session.services/service-in
                    (fn [_ctx _service-key]
                      {:request-fn (fn [_req]
                                     {:payload {"error" {"message" "rpc boom"}}
                                      :is-error true})})]
        (@request-fn-var
         {} [:svc :err]
         {:request-id "r-err"
          :payload {"jsonrpc" "2.0" "id" "r-err" "method" "explode"}
          :timeout-ms 100}
         {:dispatch-id dispatch-id})
        (let [entries (dispatch/dispatch-trace-entries)]
          (is (some #(and (= :dispatch/service-response (:trace/kind %))
                          (= dispatch-id (:dispatch-id %))
                          (= "explode" (:method %))
                          (true? (:is-error %)))
                    entries)))))))

(deftest schema-validation-test
  (testing "validate-dispatch-schemas"
    (testing "passes a valid pure-result with effects"
      (let [seen-effects (atom [])
            execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
            ctx {:execute-dispatch-effect-fn execute-fn
                 :validate-dispatch-result-fn dispatch/validate-dispatch-schemas}]
        (dispatch/register-handler!
         :schema-valid
         (fn [_ctx _data]
           {:effects [{:effect/type :runtime/agent-abort}]
            :return :ok}))
        (is (= :ok (dispatch/dispatch! ctx :schema-valid)))
        (is (= [{:effect/type :runtime/agent-abort}] @seen-effects))))

    (testing "passes a valid root-state-update with effects"
      (let [root-state (atom {})
            seen-effects (atom [])
            apply-fn (fn [_ctx f] (swap! root-state f))
            execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
            ctx {:apply-root-state-update-fn apply-fn
                 :execute-dispatch-effect-fn execute-fn
                 :validate-dispatch-result-fn dispatch/validate-dispatch-schemas}]
        (dispatch/register-handler!
         :schema-valid-update
         (fn [_ctx _data]
           {:root-state-update #(assoc % :done true)
            :effects [{:effect/type :statechart/send-event :event :go}]}))
        (dispatch/dispatch! ctx :schema-valid-update)
        (is (true? (:done @root-state)))
        (is (= [{:effect/type :statechart/send-event :event :go}] @seen-effects))))

    (testing "blocks dispatch when effect has unknown type"
      (let [seen-effects (atom [])
            execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
            ctx {:execute-dispatch-effect-fn execute-fn
                 :validate-dispatch-result-fn dispatch/validate-dispatch-schemas}]
        (dispatch/register-handler!
         :schema-bad-effect
         (fn [_ctx _data]
           {:effects [{:effect/type :nonexistent/effect}]
            :return :ok}))
        (is (= :ok (dispatch/dispatch! ctx :schema-bad-effect)))
        (is (= [] @seen-effects)
            "effects suppressed on schema failure")
        (let [entry (last (dispatch/event-log-entries))]
          (is (true? (:blocked? entry)))
          (is (= :schema-validation-failed
                 (get-in entry [:block-reason :type]))))))

    (testing "blocks dispatch when effect missing required key"
      (let [seen-effects (atom [])
            execute-fn (fn [_ctx effect] (swap! seen-effects conj effect))
            ctx {:execute-dispatch-effect-fn execute-fn
                 :validate-dispatch-result-fn dispatch/validate-dispatch-schemas}]
        (dispatch/register-handler!
         :schema-missing-key
         (fn [_ctx _data]
           {:effects [{:effect/type :runtime/agent-queue-steering}]
            :return :ok}))
        (is (= :ok (dispatch/dispatch! ctx :schema-missing-key)))
        (is (= [] @seen-effects))
        (let [entry (last (dispatch/event-log-entries))]
          (is (true? (:blocked? entry)))
          (is (= :schema-validation-failed
                 (get-in entry [:block-reason :type]))))))

    (testing "passes when handler returns return-only pure result"
      (let [ctx {:validate-dispatch-result-fn dispatch/validate-dispatch-schemas}]
        (dispatch/register-handler!
         :schema-return-only
         (fn [_ctx _data] {:return "plain-value"}))
        (is (= "plain-value" (dispatch/dispatch! ctx :schema-return-only)))))))

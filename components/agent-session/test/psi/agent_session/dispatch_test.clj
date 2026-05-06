(ns psi.agent-session.dispatch-test
  (:require
   [psi.agent-session.test-support :as test-support]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.service-protocol]
   [psi.agent-session.services]
   [psi.agent-session.session-state :as ss]
   [psi.state-kernel.dispatch :as kernel]))

;; ── Fixture: clean handler registry and event log between tests ─

(defn- clean-state [f]
  (kernel/clear-handlers!)
  (kernel/clear-event-log!)
  (kernel/clear-dispatch-trace!)
  (dispatch/set-interceptors! nil)
  (try (f)
       (finally
         (kernel/clear-handlers!)
         (kernel/clear-event-log!)
         (kernel/clear-dispatch-trace!)
         (dispatch/set-interceptors! nil))))

(use-fixtures :each clean-state)
(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

;; ── Handler registration ────────────────────────────────────

(deftest register-handler-test
  (testing "register-handler! adds handler to registry"
    (kernel/register-handler! :test-event (fn [_ctx _data] :handled))
    (is (contains? (kernel/registered-event-types) :test-event)))

  (testing "register-handler! replaces existing handler"
    (kernel/register-handler! :test-event (fn [_ctx _data] :first))
    (kernel/register-handler! :test-event (fn [_ctx _data] :second))
    (is (= :second (dispatch/dispatch! {} :test-event))))

  (testing "register-handler! stores handler entries"
    (kernel/register-handler! :classified (fn [_ctx _data] :ok))
    (is (= {:fn :present}
           (some-> (kernel/handler-entry :classified)
                   (update :fn (constantly :present)))))))

(deftest registered-event-types-test
  (testing "returns empty set when no handlers registered"
    (is (= #{} (kernel/registered-event-types))))

  (testing "returns all registered event types"
    (kernel/register-handler! :a (fn [_ _] nil))
    (kernel/register-handler! :b (fn [_ _] nil))
    (is (= #{:a :b} (kernel/registered-event-types)))))

;; ── Dispatch ────────────────────────────────────────────────

(deftest dispatch-test
  (testing "dispatch! calls registered handler with ctx and event-data"
    (let [calls (atom [])]
      (kernel/register-handler! :test-event
                                (fn [ctx data]
                                  (swap! calls conj {:ctx ctx :data data})
                                  :result))
      (let [ctx {:some "context"}
            result (dispatch/dispatch! ctx :test-event {:foo "bar"})]
        (is (= :result result))
        (is (= 1 (count @calls)))
        (is (= {:ctx {:some "context"}
                :data {:foo "bar"
                       :dispatch-id (:dispatch-id (:data (first @calls)))}}
               (first @calls))))))

  (testing "dispatch normalizes a canonical internal event value while preserving compat projections"
    (let [seen (atom nil)
          probe (kernel/->interceptor
                 {:id :probe
                  :before (fn [ictx]
                            (reset! seen {:event (:event ictx)
                                          :event-type (:event-type ictx)
                                          :event-data (:event-data ictx)
                                          :origin (:origin ictx)
                                          :ext-id (:ext-id ictx)
                                          :replaying? (:replaying? ictx)})
                            ictx)})]
      (dispatch/set-interceptors! [probe kernel/handler-interceptor dispatch/apply-interceptor])
      (kernel/register-handler! :normalized (fn [_ _] :ok))
      (is (= :ok (dispatch/dispatch! {} :normalized {:x 1}
                                     {:origin :extension
                                      :ext-id "/ext/test.clj"
                                      :replaying? true})))
      (is (= {:event {:event/type        :normalized
                      :event/data        {:x 1}
                      :event/session-id  nil
                      :event/origin      :extension
                      :event/ext-id      "/ext/test.clj"
                      :event/replaying?  true
                      :event/dispatch-id (:event/dispatch-id (:event @seen))}
              :event-type :normalized
              :event-data {:x 1}
              :origin     :extension
              :ext-id     "/ext/test.clj"
              :replaying? true}
             @seen))))

  (testing "dispatch! returns nil for unregistered event type"
    (is (nil? (dispatch/dispatch! {} :unknown-event {:x 1}))))

  (testing "dispatch! with no event-data passes only injected dispatch metadata"
    (let [received-data (atom :not-called)]
      (kernel/register-handler! :no-data
                                (fn [_ctx data]
                                  (reset! received-data data)))
      (dispatch/dispatch! {} :no-data)
      (is (string? (:dispatch-id @received-data)))
      (is (= [:dispatch-id] (keys @received-data)))))

  (testing "dispatch! with 2-arity call passes only injected dispatch metadata"
    (let [received-data (atom :not-called)]
      (kernel/register-handler! :two-arity
                                (fn [_ctx data]
                                  (reset! received-data data)))
      (dispatch/dispatch! {} :two-arity)
      (is (string? (:dispatch-id @received-data)))
      (is (= [:dispatch-id] (keys @received-data))))))

;; ── Handler receives ctx ────────────────────────────────────

(deftest dispatch-ctx-threading-test
  (testing "handler receives the ctx passed to dispatch!"
    (let [state* (atom {:agent-session {:data {:is-streaming false}}})
          ctx {:state* state*}]
      (kernel/register-handler! :mark-streaming
                                (fn [ctx _data]
                                  (swap! (:state* ctx) assoc-in
                                         [:agent-session :data :is-streaming] true)))
      (dispatch/dispatch! ctx :mark-streaming)
      (is (true? (get-in @state* [:agent-session :data :is-streaming]))))))

;; ── Interceptor chain ───────────────────────────────────────

(deftest interceptor-chain-test
  (testing "before fns run in order, after fns in reverse"
    (let [order (atom [])]
      (dispatch/set-interceptors!
       [(kernel/->interceptor
         {:id :a
          :before (fn [ictx] (swap! order conj :a-before) ictx)
          :after  (fn [ictx] (swap! order conj :a-after) ictx)})
        (kernel/->interceptor
         {:id :b
          :before (fn [ictx] (swap! order conj :b-before) ictx)
          :after  (fn [ictx] (swap! order conj :b-after) ictx)})])
      (kernel/register-handler! :test (fn [_ _] nil))
      (dispatch/dispatch! {} :test)
      (is (= [:a-before :b-before :b-after :a-after] @order))))

  (testing "default pure-result sequencing is apply then validate then trim-effects-on-replay then effects"
    (let [order (atom [])
          session-data (atom {:retry-attempt 0})
          apply-fn (fn [_ctx f]
                     (swap! order conj :apply)
                     (swap! session-data f))
          execute-fn (fn [_ctx effect]
                       (swap! order conj [:effect effect]))
          validate-fn (fn [_ctx ictx]
                        (swap! order conj [:validate (:applied-effects ictx) @session-data])
                        {:valid? true})
          trim-probe (kernel/->interceptor
                      {:id :trim-probe
                       :after (fn [ictx]
                                (swap! order conj [:trim (boolean (:replaying? ictx)) (:applied-effects ictx)])
                                ictx)})
          effects-probe (kernel/->interceptor
                         {:id :effects-probe
                          :after (fn [ictx]
                                   (swap! order conj [:effects-stage (:applied-effects ictx)])
                                   ictx)})
          apply-probe (kernel/->interceptor
                       {:id :apply-probe
                        :after (fn [ictx]
                                 (swap! order conj [:apply-stage (:applied-effects ictx) @session-data])
                                 ictx)})
          ctx {:apply-root-state-update-fn apply-fn
               :execute-dispatch-effect-fn execute-fn
               :validate-dispatch-result-fn validate-fn}]
      (dispatch/set-interceptors!
       [dispatch/permission-interceptor
        kernel/log-interceptor
        kernel/handler-interceptor
        kernel/effect-interceptor
        effects-probe
        kernel/trim-effects-on-replay
        trim-probe
        kernel/validate-interceptor
        apply-probe
        dispatch/apply-interceptor])
      (kernel/register-handler!
       :ordered-pure
       (fn [_ctx _data]
         {:root-state-update #(update % :retry-attempt inc)
          :effects [{:effect/type :schedule}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :ordered-pure)))
      (is (= [:apply
              [:apply-stage [{:effect/type :schedule}] {:retry-attempt 1}]
              [:validate [{:effect/type :schedule}] {:retry-attempt 1}]
              [:trim false [{:effect/type :schedule}]]
              [:effects-stage [{:effect/type :schedule}]]
              [:effect {:effect/type :schedule}]]
             @order))))

  (testing "root-state-update pure-result sequencing applies before validate and effects"
    (let [order (atom [])
          root-state (atom {:agent-session {:data {:session-name "before"}}})
          apply-root-fn (fn [_ctx f]
                          (let [new-state (swap! root-state f)]
                            (swap! order conj [:root-update (get-in new-state [:agent-session :data :session-name])])))
          execute-fn (fn [_ctx effect]
                       (swap! order conj [:effect effect]))
          validate-fn (fn [_ctx ictx]
                        (swap! order conj [:validate (:applied-effects ictx) @root-state])
                        {:valid? true})
          trim-probe (kernel/->interceptor
                      {:id :trim-probe
                       :after (fn [ictx]
                                (swap! order conj [:trim (boolean (:replaying? ictx)) (:applied-effects ictx)])
                                ictx)})
          effects-probe (kernel/->interceptor
                         {:id :effects-probe
                          :after (fn [ictx]
                                   (swap! order conj [:effects-stage (:applied-effects ictx)])
                                   ictx)})
          apply-probe (kernel/->interceptor
                       {:id :apply-probe
                        :after (fn [ictx]
                                 (swap! order conj [:apply-stage (:applied-effects ictx) @root-state])
                                 ictx)})
          ctx {:apply-root-state-update-fn apply-root-fn
               :execute-dispatch-effect-fn execute-fn
               :validate-dispatch-result-fn validate-fn}]
      (dispatch/set-interceptors!
       [dispatch/permission-interceptor
        kernel/log-interceptor
        dispatch/statechart-interceptor
        kernel/handler-interceptor
        kernel/effect-interceptor
        effects-probe
        kernel/trim-effects-on-replay
        trim-probe
        kernel/validate-interceptor
        apply-probe
        dispatch/apply-interceptor])
      (kernel/register-handler!
       :ordered-root-update
       (fn [_ctx _data]
         {:root-state-update #(assoc-in % [:agent-session :data :session-name] "after")
          :effects [{:effect/type :notify}]
          :return :ok}))
      (is (= :ok (dispatch/dispatch! ctx :ordered-root-update)))
      (is (= [[:root-update "after"]
              [:apply-stage [{:effect/type :notify}] {:agent-session {:data {:session-name "after"}}}]
              [:validate [{:effect/type :notify}] {:agent-session {:data {:session-name "after"}}}]
              [:trim false [{:effect/type :notify}]]
              [:effects-stage [{:effect/type :notify}]]
              [:effect {:effect/type :notify}]]
             @order))))

  (testing "statechart interceptor can claim an event and skip the handler stage"
    (dispatch/set-interceptors! nil)
    (let [calls (atom [])
          ctx {:dispatch-statechart-event-fn
               (fn [_ctx event-type event-data _ictx]
                 (swap! calls conj [:statechart event-type event-data])
                 {:claimed? true :result :claimed})}]
      (kernel/register-handler! :claimed-event (fn [_ _] (swap! calls conj [:handler]) :handler-result))
      (is (= :claimed (dispatch/dispatch! ctx :claimed-event {:x 1})))
      (is (= [[:statechart :claimed-event {:x 1}]] @calls))))

  (testing "blocked context skips subsequent before fns"
    (let [order (atom [])]
      (dispatch/set-interceptors!
       [(kernel/->interceptor
         {:id :blocker
          :before (fn [ictx]
                    (swap! order conj :blocker)
                    (assoc ictx :blocked? true :block-reason :test-block))})
        (kernel/->interceptor
         {:id :skipped
          :before (fn [ictx]
                    (swap! order conj :skipped)
                    ictx)})])
      (kernel/register-handler! :test (fn [_ _] :should-not-run))
      (let [result (dispatch/dispatch! {} :test)]
        (is (nil? result))
        (is (= [:blocker] @order)))))

  (testing "after fns still run when blocked"
    (let [after-ran? (atom false)]
      (dispatch/set-interceptors!
       [(kernel/->interceptor
         {:id :with-after
          :after (fn [ictx] (reset! after-ran? true) ictx)})
        (kernel/->interceptor
         {:id :blocker
          :before (fn [ictx] (assoc ictx :blocked? true))})])
      (kernel/register-handler! :test (fn [_ _] nil))
      (dispatch/dispatch! {} :test)
      (is (true? @after-ran?)))))

;; ── Event log ───────────────────────────────────────────────

(deftest canonical-dispatch-trace-test
  (testing "dispatch assigns one stable dispatch-id and records received/completed"
    (kernel/register-handler! :trace-event (fn [_ _] :ok))
    (is (= :ok (dispatch/dispatch! {} :trace-event {:x 1})))
    (let [entries (kernel/dispatch-trace-entries)
          received (first entries)
          completed (last entries)]
      (is (= :dispatch/received (:trace/kind received)))
      (is (= :dispatch/completed (:trace/kind completed)))
      (is (= (:dispatch-id received) (:dispatch-id completed)))
      (is (= :trace-event (:event-type received)))
      (is (= :trace-event (:event-type completed)))))

  (testing "dispatch records interceptor, handler, effect, and completion stages under one dispatch-id"
    (kernel/clear-dispatch-trace!)
    (let [effect-calls (atom [])
          ctx {:execute-dispatch-effect-fn (fn [_ effect]
                                             (swap! effect-calls conj effect)
                                             {:ok true})}]
      (kernel/register-handler! :trace-rich
                                (fn [_ _]
                                  {:effects [{:effect/type :effect/demo
                                              :value 1}]
                                   :return :done}))
      (is (= :done (dispatch/dispatch! ctx :trace-rich {:x 1})))
      (let [entries (kernel/dispatch-trace-entries)
            dispatch-id (:dispatch-id (first entries))
            by-id (filter #(= dispatch-id (:dispatch-id %)) entries)]
        (is (seq @effect-calls))
        (is (some #(and (= :dispatch/interceptor-enter (:trace/kind %))
                        (= :permission (:interceptor-id %)))
                  by-id))
        (is (some #(and (= :dispatch/interceptor-exit (:trace/kind %))
                        (= :apply (:interceptor-id %)))
                  by-id))
        (is (some #(and (= :dispatch/handler-result (:trace/kind %))
                        (= {:kind :pure-result
                            :effect-count 1
                            :has-root-state-update false
                            :has-return true
                            :return-key nil
                            :return-effect-result? false}
                           (:result %)))
                  by-id))
        (is (some #(and (= :dispatch/effects-emitted (:trace/kind %))
                        (= [{:effect/type :effect/demo
                             :value 1}]
                           (:effects %)))
                  by-id))
        (is (some #(and (= :dispatch/effect-start (:trace/kind %))
                        (= :effect/demo (:effect-type %)))
                  by-id))
        (is (some #(and (= :dispatch/effect-finish (:trace/kind %))
                        (= :effect/demo (:effect-type %))
                        (= {:ok true} (:result %)))
                  by-id))
        (is (= :dispatch/completed (:trace/kind (last by-id)))))))

  (testing "service request/response/notify entries inherit an explicit dispatch-id"
    (let [calls (atom [])
          dispatch-id (kernel/next-dispatch-id)
          request-fn-var (resolve 'psi.agent-session.service-protocol/send-service-request!)
          notify-fn-var (resolve 'psi.agent-session.service-protocol/send-service-notification!)]
      (with-redefs [psi.agent-session.services/service-in
                    (fn [_ctx _service-key]
                      {:send-fn (fn [_payload] (swap! calls conj :send))
                       :await-response-fn (fn [_req]
                                            {:payload {"result" {"ok" true}}})
                       :await-response-sends? false})]
        (@request-fn-var
         {} [:svc :one]
         {:request-id "r1"
          :payload {"jsonrpc" "2.0" "id" "r1" "method" "initialize"}
          :timeout-ms 100}
         {:dispatch-id dispatch-id})
        (@notify-fn-var
         {} [:svc :one]
         {"jsonrpc" "2.0" "method" "initialized"}
         {:dispatch-id dispatch-id})
        (let [entries (kernel/dispatch-trace-entries)]
          (is (some #(and (= :dispatch/service-request (:trace/kind %))
                          (= dispatch-id (:dispatch-id %))
                          (= "initialize" (:method %)))
                    entries))
          (is (some #(and (= :dispatch/service-response (:trace/kind %))
                          (= dispatch-id (:dispatch-id %))
                          (= "initialize" (:method %)))
                    entries))
          (is (some #(and (= :dispatch/service-notify (:trace/kind %))
                          (= dispatch-id (:dispatch-id %))
                          (= "initialized" (:method %)))
                    entries)))))))

(deftest event-log-test
  (testing "dispatch writes event log entries"
    (kernel/register-handler! :logged-event (fn [_ _] :ok))
    (dispatch/dispatch! {} :logged-event {:x 1})
    (let [entries (kernel/event-log-entries)]
      (is (= 1 (count entries)))
      (let [entry (first entries)]
        (is (= :logged-event (:event-type entry)))
        (is (= {:x 1} (:event-data entry)))
        (is (= :core (:origin entry)))
        (is (false? (:replaying? entry)))
        (is (false? (:blocked? entry)))
        (is (number? (:timestamp entry)))
        (is (number? (:duration-ms entry))))))

  (testing "unregistered events are still logged"
    (kernel/clear-event-log!)
    (dispatch/dispatch! {} :no-handler {:y 2})
    (let [entries (kernel/event-log-entries)]
      (is (= 1 (count entries)))
      (is (= :no-handler (:event-type (first entries))))))

  (testing "blocked events are logged with blocked? true"
    (kernel/clear-event-log!)
    (dispatch/set-interceptors!
     [(kernel/->interceptor
       {:id :blocker
        :before (fn [ictx] (assoc ictx :blocked? true))})
      kernel/log-interceptor
      kernel/handler-interceptor])
    (kernel/register-handler! :blocked (fn [_ _] nil))
    (dispatch/dispatch! {} :blocked)
    (let [entries (kernel/event-log-entries)]
      (is (= 1 (count entries)))
      (is (true? (:blocked? (first entries))))))

  (testing "event log is bounded"
    ;; Use a chain with only handler (skip log) then log manually
    ;; to avoid testing the bound indirectly. Instead, test directly.
    (kernel/clear-event-log!)
    (kernel/register-handler! :bulk (fn [_ _] nil))
    (dotimes [i 1005]
      (dispatch/dispatch! {} :bulk {:i i}))
    (is (<= (count (kernel/event-log-entries)) 1000))))

;; ── Default interceptors ────────────────────────────────────

(deftest default-interceptors-test
  (testing "default chain includes permission, log, statechart, handler, effects, trim-effects-on-replay, validate, and apply interceptors"
    (let [ids (mapv :id dispatch/default-interceptors)]
      (is (= [:permission :log :statechart :handler :effects :trim-effects-on-replay :validate :apply] ids))))

  (testing "current-interceptors returns defaults when no override"
    (is (= dispatch/default-interceptors (dispatch/current-interceptors))))

  (testing "set-interceptors! overrides the chain"
    (let [custom [(kernel/->interceptor {:id :custom})]]
      (dispatch/set-interceptors! custom)
      (is (= custom (dispatch/current-interceptors)))))

  (testing "set-interceptors! nil restores defaults"
    (dispatch/set-interceptors! [(kernel/->interceptor {:id :temp})])
    (dispatch/set-interceptors! nil)
    (is (= dispatch/default-interceptors (dispatch/current-interceptors)))))

;; ── Permission interceptor ───────────────────────────────────

(defn- make-test-registry
  "Create a minimal extension registry.
   Input may be a seq of paths or a seq of [path ext-record] pairs."
  [exts]
  (let [entries (map (fn [x]
                       (if (vector? x)
                         x
                         [x {:path x}]))
                     exts)
        extensions (into {} entries)]
    {:state (atom {:extensions extensions
                   :registration-order (mapv first entries)})}))

(deftest permission-interceptor-test
  (testing "core origin bypasses permission check"
    (kernel/register-handler! :core-event (fn [_ _] :ok))
    (is (= :ok (dispatch/dispatch! {} :core-event nil {:origin :core}))))

  (testing "statechart origin bypasses permission check"
    (kernel/register-handler! :sc-event (fn [_ _] :ok))
    (is (= :ok (dispatch/dispatch! {} :sc-event nil {:origin :statechart}))))

  (testing "adapter origin bypasses permission check"
    (kernel/register-handler! :adapter-event (fn [_ _] :ok))
    (is (= :ok (dispatch/dispatch! {} :adapter-event nil {:origin :adapter}))))

  (testing "extension origin with registered ext-id and no manifest allowed-events is blocked"
    (let [reg (make-test-registry [["/ext/a.clj" {:path "/ext/a.clj"}]])
          ctx {:extension-registry reg}]
      (kernel/register-handler! :ext-event (fn [_ _] :allowed))
      (kernel/clear-event-log!)
      (is (nil? (dispatch/dispatch! ctx :ext-event nil
                                    {:origin :extension
                                     :ext-id "/ext/a.clj"})))
      (let [entry (last (kernel/event-log-entries))]
        (is (true? (:blocked? entry)))
        (is (= {:reason :permission-denied
                :event-type :ext-event
                :ext-id "/ext/a.clj"}
               (:block-reason entry))))))

  (testing "extension origin with allowed manifest event is allowed"
    (let [reg (make-test-registry [["/ext/a.clj" {:path "/ext/a.clj"
                                                  :allowed-events #{:ext-event}}]])
          ctx {:extension-registry reg}]
      (kernel/register-handler! :ext-event (fn [_ _] :allowed))
      (is (= :allowed (dispatch/dispatch! ctx :ext-event nil
                                          {:origin :extension
                                           :ext-id "/ext/a.clj"})))))

  (testing "extension origin with disallowed manifest event is blocked"
    (let [reg (make-test-registry [["/ext/a.clj" {:path "/ext/a.clj"
                                                  :allowed-events #{:other-event}}]])
          ctx {:extension-registry reg}]
      (kernel/register-handler! :ext-event (fn [_ _] :should-not-run))
      (kernel/clear-event-log!)
      (is (nil? (dispatch/dispatch! ctx :ext-event nil
                                    {:origin :extension
                                     :ext-id "/ext/a.clj"})))
      (let [entry (last (kernel/event-log-entries))]
        (is (true? (:blocked? entry)))
        (is (= {:reason :permission-denied
                :event-type :ext-event
                :ext-id "/ext/a.clj"}
               (:block-reason entry))))))

  (testing "extension origin with unknown ext-id is blocked"
    (let [reg (make-test-registry ["/ext/a.clj"])
          ctx {:extension-registry reg}]
      (kernel/register-handler! :ext-event (fn [_ _] :should-not-run))
      (kernel/clear-event-log!)
      (is (nil? (dispatch/dispatch! ctx :ext-event nil
                                    {:origin :extension
                                     :ext-id "/ext/unknown.clj"})))
      (let [entry (last (kernel/event-log-entries))]
        (is (true? (:blocked? entry)))
        (is (= :unknown-extension (:block-reason entry))))))

  (testing "extension origin with nil ext-id is blocked"
    (kernel/register-handler! :ext-event (fn [_ _] :should-not-run))
    (is (nil? (dispatch/dispatch! {} :ext-event nil
                                  {:origin :extension
                                   :ext-id nil}))))

  (testing "extension origin with no registry on ctx is blocked"
    (kernel/register-handler! :ext-event (fn [_ _] :should-not-run))
    (is (nil? (dispatch/dispatch! {} :ext-event nil
                                  {:origin :extension
                                   :ext-id "/ext/a.clj"})))))

;; ── Origin in log entries ───────────────────────────────────

(deftest startup-bootstrap-summary-dispatch-test
  (let [[ctx _]            (create-session-context {:persist? false})
        _                  (kernel/clear-event-log!)
        sd                 (session/new-session-in! ctx nil {})
        session-id         (:session-id sd)
        completed-at       (java.time.Instant/now)
        summary            {:timestamp completed-at :prompt-count 1}]
    (testing "startup summary writes through dispatch handlers"
      (dispatch/dispatch! ctx :session/set-startup-bootstrap-summary {:session-id session-id :summary summary} {:origin :core})
      (let [sd         (ss/get-session-data-in ctx session-id)
            entries     (kernel/event-log-entries)
            event-types (mapv :event-type entries)]
        (is (= summary (:startup-bootstrap sd)))
        (is (= [:session/new-initialize
                :session/ensure-base-system-prompt
                :session/retarget-runtime-prompt-metadata
                :session/set-startup-bootstrap-summary]
               event-types))))))

(deftest origin-logged-test
  (testing "log entry captures origin"
    (kernel/register-handler! :origin-test (fn [_ _] nil))
    (dispatch/dispatch! {} :origin-test nil {:origin :statechart})
    (let [entry (last (kernel/event-log-entries))]
      (is (= :statechart (:origin entry)))))

  (testing "log entry captures ext-id for extension origin"
    (kernel/clear-event-log!)
    (let [reg (make-test-registry ["/ext/a.clj"])
          ctx {:extension-registry reg}]
      (kernel/register-handler! :ext-log (fn [_ _] nil))
      (dispatch/dispatch! ctx :ext-log nil {:origin :extension :ext-id "/ext/a.clj"})
      (let [entry (last (kernel/event-log-entries))]
        (is (= :extension (:origin entry)))
        (is (= "/ext/a.clj" (:ext-id entry)))))))

;; ── Pure handler results ─────────────────────────────────────

;; Pure-result / validation / failure-path coverage moved to
;; dispatch_pure_result_test.clj to reduce file length.

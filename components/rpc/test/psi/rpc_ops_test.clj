(ns psi.rpc-ops-test
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.rpc.events :as rpc.events]
   [psi.rpc.transport :as rpc.transport]
   [psi.rpc-test-support :as support]))

(deftest session-request-handler-query-eql-and-op-mapping-test
  (testing "query_eql routes to session/query-in and returns canonical result envelope"
    (let [[ctx _] (support/create-session-context)
          state   (atom {:transport {:ready? true :pending {}}})
          handler (support/make-handler ctx state)
          {:keys [out-lines]}
          (support/run-loop "{:id \"q1\" :kind :request :op \"query_eql\" :params {:query \"[:psi.graph/domain-coverage :psi.memory/status]\"}}\n"
                            handler
                            state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :response (:kind frame)))
      (is (= "query_eql" (:op frame)))
      (is (= true (:ok frame)))
      (is (map? (get-in frame [:data :result])))
      (is (contains? (get-in frame [:data :result]) :psi.graph/domain-coverage))
      (is (contains? (get-in frame [:data :result]) :psi.memory/status))))

  (testing "query_eql invalid query EDN returns request/invalid-query"
    (let [[ctx _] (support/create-session-context)
          state   (atom {:transport {:ready? true :pending {}}})
          handler (support/make-handler ctx state)
          {:keys [out-lines]}
          (support/run-loop "{:id \"q2\" :kind :request :op \"query_eql\" :params {:query \"not-edn\"}}\n"
                            handler
                            state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "query_eql" (:op frame)))
      (is (= "request/invalid-query" (:error-code frame)))))

  (testing "query_eql non-vector query returns request/invalid-query"
    (let [[ctx _] (support/create-session-context)
          state   (atom {:transport {:ready? true :pending {}}})
          handler (support/make-handler ctx state)
          {:keys [out-lines]}
          (support/run-loop "{:id \"q3\" :kind :request :op \"query_eql\" :params {:query \"{:a 1}\"}}\n"
                            handler
                            state)
          frame   (-> out-lines first edn/read-string)]
      (is (= :error (:kind frame)))
      (is (= "request/invalid-query" (:error-code frame)))))

  (testing "unknown op returns request/op-not-supported with supported ops"
    (let [[ctx _] (support/create-session-context)
          state   (atom {:transport {:ready? true :pending {}}})
          handler (support/make-handler ctx state)
          {:keys [out-lines]}
          (support/run-loop "{:id \"u1\" :kind :request :op \"nope\"}\n"
                            handler
                            state)
          frame   (-> out-lines first edn/read-string)
          supported (get-in frame [:data :supported-ops])]
      (is (= :error (:kind frame)))
      (is (= "request/op-not-supported" (:error-code frame)))
      (is (vector? supported))
      (is (some #(= "prompt_while_streaming" %) supported))
      (is (some #(= "resolve_dialog" %) supported))
      (is (some #(= "cancel_dialog" %) supported))
      (is (some #(= "list_background_jobs" %) supported))
      (is (some #(= "inspect_background_job" %) supported))
      (is (some #(= "cancel_background_job" %) supported))))

  (testing "subscribe/unsubscribe update shared state and return subscribed topics"
    (let [[ctx _] (support/create-session-context)
          state   (atom {:transport {:ready? true :pending {}}
                         :connection {:subscribed-topics #{}}})
          handler (support/make-handler ctx state)
          {:keys [out-lines]}
          (support/run-loop (str "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/delta\" \"tool/start\"]}}\n"
                                 "{:id \"s2\" :kind :request :op \"unsubscribe\" :params {:topics [\"tool/start\"]}}\n")
                            handler
                            state)
          [f1 f2] (support/parse-frames out-lines)]
      (is (= :response (:kind f1)))
      (is (= ["assistant/delta" "tool/start"] (get-in f1 [:data :subscribed])))
      (is (= :response (:kind f2)))
      (is (= ["assistant/delta"] (get-in f2 [:data :subscribed])))
      (is (= #{"assistant/delta"} (get-in @state [:connection :subscribed-topics])))))

  (testing "projection listener unregisters when projection topics are removed but unrelated topics remain"
    (let [[ctx _] (support/create-session-context)
          state   (atom {:transport {:ready? true :pending {}}
                         :connection {:subscribed-topics #{}}})
          handler (support/make-handler ctx state)
          {:keys [out-lines]}
          (support/run-loop (str "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"assistant/message\" \"context/updated\"]}}\n"
                                 "{:id \"s2\" :kind :request :op \"unsubscribe\" :params {:topics [\"context/updated\"]}}\n")
                            handler
                            state)
          responses (filter #(= :response (:kind %)) (support/parse-frames out-lines))
          f2 (second responses)]
      (is (= :response (:kind f2)))
      (is (= ["assistant/message"] (get-in f2 [:data :subscribed])))
      (is (= #{"assistant/message"} (get-in @state [:connection :subscribed-topics])))
      (is (nil? (get-in @state [:workers :projection-listener-id])))))

  (testing "background job list/inspect/cancel ops route through session job store"
    (let [[ctx thread-id] (support/create-session-context)
          _               (dispatch/dispatch! ctx :session/update-background-jobs-state
                                              {:update-fn (fn [store]
                                                            (:state (bg-jobs/start-background-job
                                                                     store
                                                                     {:tool-call-id "tc-rpc-bg-1"
                                                                      :thread-id thread-id
                                                                      :tool-name "agent-chain"
                                                                      :job-id "job-rpc-1"})))}
                                              {:origin :core})
          state           (atom {:transport {:ready? true :pending {}}})
          handler         (support/make-handler ctx state)
          {:keys [out-lines]}
          (support/run-loop (str "{:id \"jb1\" :kind :request :op \"list_background_jobs\"}\n"
                                 "{:id \"jb2\" :kind :request :op \"inspect_background_job\" :params {:job-id \"job-rpc-1\"}}\n"
                                 "{:id \"jb3\" :kind :request :op \"cancel_background_job\" :params {:job-id \"job-rpc-1\"}}\n")
                            handler
                            state)
          [f1 f2 f3]      (support/parse-frames out-lines)]
      (is (= :response (:kind f1)))
      (is (= "list_background_jobs" (:op f1)))
      (is (= "job-rpc-1" (get-in f1 [:data :jobs 0 :job-id])))
      (is (= "job-rpc-1  [running]  agent-chain"
             (get-in f1 [:data :jobs 0 :summary :list-line])))
      (is (= :response (:kind f2)))
      (is (= "inspect_background_job" (:op f2)))
      (is (= "job-rpc-1" (get-in f2 [:data :job :job-id])))
      (is (= "running" (get-in f2 [:data :job :summary :status-label])))
      (is (= :response (:kind f3)))
      (is (= "cancel_background_job" (:op f3)))
      (is (true? (get-in f3 [:data :accepted])))
      (is (= :pending-cancel (get-in f3 [:data :job :status])))
      (is (= "pending-cancel" (get-in f3 [:data :job :summary :status-label]))))))

(deftest rpc-cancel-background-job-routes-scheduled-prompts-to-scheduler-test
  (testing "cancel_background_job accepts scheduler-backed ids"
    (let [[ctx session-id] (support/create-session-context)
          _ (dispatch/dispatch! ctx :scheduler/create
                                {:session-id session-id
                                 :schedule-id "sch-rpc-1"
                                 :label "rpc-cancel"
                                 :message "cancel over rpc"
                                 :created-at (java.time.Instant/parse "2099-04-21T18:00:00Z")
                                 :fire-at (java.time.Instant/parse "2099-04-21T18:05:00Z")
                                 :delay-ms 1000}
                                {:origin :core})
          state   (atom {:transport {:ready? true :pending {}}})
          handler (support/make-handler ctx state)
          {:keys [out-lines]}
          (support/run-loop "{:id \"sched-cancel\" :kind :request :op \"cancel_background_job\" :params {:job-id \"schedule/sch-rpc-1\"}}\n"
                            handler
                            state)
          [frame] (support/parse-frames out-lines)
          result  (session/query-in ctx session-id
                                    [{:psi.scheduler/schedules
                                      [:psi.scheduler/schedule-id
                                       :psi.scheduler/status]}])
          schedule (first (:psi.scheduler/schedules result))]
      (is (= :response (:kind frame)))
      (is (= "cancel_background_job" (:op frame)))
      (is (true? (get-in frame [:data :accepted])))
      (is (= :cancelled (:psi.scheduler/status schedule))))))

(deftest rpc-subscribe-ui-topics-emits-initial-widget-snapshot-test
  (testing "subscribe ui/widgets-updated emits current widget projection immediately"
    (let [[ctx _] (support/create-session-context)
          _       (dispatch/dispatch! ctx :session/ui-set-widget
                                      {:extension-id "ext.demo" :widget-id "w-1"
                                       :placement :above-editor :content ["hello widget"]}
                                      {:origin :test})
          state   (atom {:transport {:ready? true :pending {}}
                         :connection {:subscribed-topics #{}}})
          handler (support/make-handler ctx state)
          {:keys [out-lines]}
          (support/run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                 "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"ui/widgets-updated\"]}}\n")
                            handler
                            state
                            100)
          frames      (support/parse-frames out-lines)
          widget-evt  (some #(when (= "ui/widgets-updated" (:event %)) %) frames)]
      (is (some? widget-evt))
      (is (= "w-1" (get-in widget-evt [:data :widgets 0 :widget-id])))
      (is (= ["hello widget"] (get-in widget-evt [:data :widgets 0 :content]))))))

(deftest rpc-ui-snapshot-delegates-to-canonical-extension-ui-projection-test
  (let [snapshot (rpc.events/ui-snapshot
                  (first (support/create-session-context)))]
    (is (map? snapshot))))

(deftest rpc-subscribe-ui-topics-emits-canonical-widget-and-status-order-test
  (testing "subscribe emits backend-owned widget/status order from canonical projection"
    (let [[ctx _] (support/create-session-context)
          _       (dispatch/dispatch! ctx :session/ui-set-widget
                                      {:extension-id "ext.b" :widget-id "w-2"
                                       :placement :below-editor :content ["B2"]}
                                      {:origin :test})
          _       (dispatch/dispatch! ctx :session/ui-set-widget
                                      {:extension-id "ext.a" :widget-id "w-3"
                                       :placement :above-editor :content ["A3"]}
                                      {:origin :test})
          _       (dispatch/dispatch! ctx :session/ui-set-widget
                                      {:extension-id "ext.b" :widget-id "w-1"
                                       :placement :above-editor :content ["B1"]}
                                      {:origin :test})
          _       (dispatch/dispatch! ctx :session/ui-set-status
                                      {:extension-id "ext-z" :text "Zeta"}
                                      {:origin :test})
          _       (dispatch/dispatch! ctx :session/ui-set-status
                                      {:extension-id "ext-a" :text "Alpha"}
                                      {:origin :test})
          state   (atom {:transport {:ready? true :pending {}}
                         :connection {:subscribed-topics #{}}})
          handler (support/make-handler ctx state)
          {:keys [out-lines]}
          (support/run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                 "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"ui/widgets-updated\" \"ui/status-updated\"]}}\n")
                            handler
                            state
                            100)
          frames      (support/parse-frames out-lines)
          widget-evt  (some #(when (= "ui/widgets-updated" (:event %)) %) frames)
          status-evt  (some #(when (= "ui/status-updated" (:event %)) %) frames)]
      (is (= [["ext.a" "w-3"]
              ["ext.b" "w-1"]
              ["ext.b" "w-2"]]
             (mapv (juxt :extension-id :widget-id)
                   (get-in widget-evt [:data :widgets]))))
      (is (= ["ext-a" "ext-z"]
             (mapv :extension-id (get-in status-evt [:data :statuses])))))))

(deftest rpc-subscribe-ui-topics-emits-initial-notification-snapshot-test
  (testing "subscribe ui/notification emits current visible notifications immediately"
    (let [[ctx _] (support/create-session-context)
          _       (dispatch/dispatch! ctx :session/ui-notify
                                      {:extension-id "ext.demo"
                                       :message "hello notification"
                                       :level :info}
                                      {:origin :test})
          state   (atom {:transport {:ready? true :pending {}}
                         :connection {:subscribed-topics #{}}})
          handler (support/make-handler ctx state)
          {:keys [out-lines]}
          (support/run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                 "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"ui/notification\"]}}\n")
                            handler
                            state
                            100)
          frames            (support/parse-frames out-lines)
          notification-evt  (some #(when (= "ui/notification" (:event %)) %) frames)]
      (is (some? notification-evt))
      (is (= "ext.demo" (get-in notification-evt [:data :extension-id])))
      (is (= "hello notification" (get-in notification-evt [:data :message])))
      (is (= "info" (get-in notification-evt [:data :level]))))))

(deftest rpc-event-driven-ui-projection-streams-widget-updates-without-prompt-test
  (testing "after subscribe, ui widget updates stream from event-driven projection delivery without a prompt request"
    (let [[ctx _]     (support/create-session-context)
          state       (atom {:transport {:ready? true :pending {}}
                             :connection {:subscribed-topics #{}}})
          handler     (support/make-handler ctx state)
          in-reader   (java.io.PipedReader.)
          in-writer   (java.io.PipedWriter. in-reader)
          out-writer  (java.io.StringWriter.)
          err-writer  (java.io.StringWriter.)
          write-line! (fn [line]
                        (.write in-writer (str line "\n"))
                        (.flush in-writer))
          loop-future (future
                        (rpc.transport/run-stdio-loop! {:in              in-reader
                                                        :out             out-writer
                                                        :err             err-writer
                                                        :state           state
                                                        :request-handler handler}))]
      (try
        (write-line! "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}")
        (write-line! "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"ui/widgets-updated\"]}}")
        (dispatch/dispatch! ctx :session/ui-set-widget
                            {:extension-id "ext.demo" :widget-id "w-2"
                             :placement :above-editor :content ["live update"]}
                            {:origin :test})
        (let [latest (support/await-frames!
                      out-writer
                      (fn [frames]
                        (let [widget-events (filter #(= "ui/widgets-updated" (:event %)) frames)]
                          (when (seq widget-events)
                            (last widget-events))))
                      1000)]
          (.close in-writer)
          (deref loop-future 500 support/timeout-token)
          (is (not= support/timeout-token latest) "timed out waiting for ui/widgets-updated")
          (is (= "w-2" (get-in latest [:data :widgets 0 :widget-id])))
          (is (= ["live update"] (get-in latest [:data :widgets 0 :content]))))
        (finally
          (future-cancel loop-future)
          (try (.close in-writer) (catch Exception _ nil))
          (try (.close in-reader) (catch Exception _ nil)))))))

(deftest rpc-event-driven-ui-projection-streams-notifications-without-prompt-test
  (testing "after subscribe, ui notifications stream from event-driven projection delivery without a prompt request"
    (let [[ctx _]     (support/create-session-context)
          state       (atom {:transport {:ready? true :pending {}}
                             :connection {:subscribed-topics #{}}})
          handler     (support/make-handler ctx state)
          in-reader   (java.io.PipedReader.)
          in-writer   (java.io.PipedWriter. in-reader)
          out-writer  (java.io.StringWriter.)
          err-writer  (java.io.StringWriter.)
          write-line! (fn [line]
                        (.write in-writer (str line "\n"))
                        (.flush in-writer))
          loop-future (future
                        (rpc.transport/run-stdio-loop! {:in              in-reader
                                                        :out             out-writer
                                                        :err             err-writer
                                                        :state           state
                                                        :request-handler handler}))]
      (try
        (write-line! "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}")
        (write-line! "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"ui/notification\"]}}")
        (dispatch/dispatch! ctx :session/ui-notify
                            {:extension-id "ext.demo"
                             :message "live note"
                             :level :warning}
                            {:origin :test})
        (let [latest (support/await-frames!
                      out-writer
                      (fn [frames]
                        (let [notification-events (filter #(= "ui/notification" (:event %)) frames)]
                          (when (seq notification-events)
                            (last notification-events))))
                      1000)]
          (.close in-writer)
          (deref loop-future 500 support/timeout-token)
          (is (not= support/timeout-token latest) "timed out waiting for ui/notification")
          (is (= "ext.demo" (get-in latest [:data :extension-id])))
          (is (= "live note" (get-in latest [:data :message])))
          (is (= "warning" (get-in latest [:data :level]))))
        (finally
          (future-cancel loop-future)
          (try (.close in-writer) (catch Exception _ nil))
          (try (.close in-reader) (catch Exception _ nil)))))))

(deftest rpc-event-driven-ui-projection-streams-status-and-dialog-without-prompt-test
  (testing "after subscribe, ui status and dialog updates stream from event-driven projection delivery without a prompt request"
    (let [[ctx session-id] (support/create-session-context)
          state            (atom {:transport {:ready? true :pending {}}
                                  :connection {:subscribed-topics #{}}})
          handler          (support/make-handler ctx state)
          in-reader        (java.io.PipedReader.)
          in-writer        (java.io.PipedWriter. in-reader)
          out-writer       (java.io.StringWriter.)
          err-writer       (java.io.StringWriter.)
          write-line!      (fn [line]
                             (.write in-writer (str line "\n"))
                             (.flush in-writer))
          loop-future      (future
                             (rpc.transport/run-stdio-loop! {:in              in-reader
                                                             :out             out-writer
                                                             :err             err-writer
                                                             :state           state
                                                             :request-handler handler}))]
      (try
        (write-line! "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}")
        (write-line! "{:id \"s1\" :kind :request :op \"subscribe\" :params {:topics [\"ui/status-updated\" \"ui/dialog-requested\"]}}")
        (dispatch/dispatch! ctx :session/ui-set-status
                            {:session-id session-id
                             :extension-id "ext.demo"
                             :text "Live status"}
                            {:origin :test})
        (dispatch/dispatch! ctx :session/ui-request-dialog
                            {:session-id session-id
                             :kind :confirm
                             :ext-id "ext.demo"
                             :title "Live dialog"
                             :message "Proceed?"}
                            {:origin :test})
        (let [frames* (support/await-frames!
                       out-writer
                       (fn [frames]
                         (let [status-evts (filter #(= "ui/status-updated" (:event %)) frames)
                               dialog-evts (filter #(= "ui/dialog-requested" (:event %)) frames)]
                           (when (and (seq status-evts) (seq dialog-evts))
                             {:latest-status (last status-evts)
                              :latest-dialog (last dialog-evts)})))
                       1000)
              latest-status (:latest-status frames*)
              latest-dialog (:latest-dialog frames*)]
          (.close in-writer)
          (deref loop-future 500 support/timeout-token)
          (is (not= support/timeout-token frames*) "timed out waiting for status/dialog projection events")
          (is (= "ext.demo" (get-in latest-status [:data :statuses 0 :extension-id])))
          (is (= "Live status" (get-in latest-status [:data :statuses 0 :text])))
          (is (= "confirm" (get-in latest-dialog [:data :kind])))
          (is (= "Live dialog" (get-in latest-dialog [:data :title])))
          (is (= "Proceed?" (get-in latest-dialog [:data :message]))))
        (finally
          (future-cancel loop-future)
          (try (.close in-writer) (catch Exception _ nil))
          (try (.close in-reader) (catch Exception _ nil)))))))

(ns psi.agent-session.psi-tool-mutate-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions.runtime-eql :as runtime-eql]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tools :as tools]
   [psi.session-state.state :as ss]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (assoc (test-support/safe-context-opts opts)
                                            :mutations mutations/all-mutations))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest psi-tool-mutate-test
  (testing "psi-tool mutate invokes registered mutation and preserves returned payload"
    (let [[ctx session-id] (create-session-context {:persist? false})
          child-sd         (session/new-session-in! ctx session-id {})
          target-id        (:session-id child-sd)
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result           ((:execute tool) {"action" "mutate"
                                             "mutation" "psi.extension/close-session"
                                             "params" {"session-id" target-id}})
          parsed           (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :mutate (:psi-tool/action parsed)))
      (is (= 'psi.extension/close-session (:psi-tool/mutation parsed)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (= {:psi.agent-session/close-session-closed? true
              :psi.agent-session/close-session-id target-id}
             (:psi-tool/result parsed)))
      (is (not (contains? parsed :psi-tool/error)))
      (is (nil? (ss/get-session-data-in ctx target-id)))
      (is (some? (ss/get-session-data-in ctx session-id)))))

  (testing "psi-tool mutate accepts top-level string-keyed params through the live tool surface"
    (let [[ctx session-id] (create-session-context {:persist? false})
          child-sd         (session/new-session-in! ctx session-id {})
          target-id        (:session-id child-sd)
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result           ((:execute tool) {"action" "mutate"
                                             "mutation" "psi.extension/close-session"
                                             "params" {"session-id" target-id
                                                       "unknown" {:preserved? true}}})
          parsed           (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (= {:psi.agent-session/close-session-closed? true
              :psi.agent-session/close-session-id target-id}
             (:psi-tool/result parsed)))
      (is (nil? (ss/get-session-data-in ctx target-id)))
      (is (some? (ss/get-session-data-in ctx session-id)))))

  (testing "psi-tool mutate rejects unknown mutation names with validate error"
    (let [[ctx session-id] (create-session-context {:persist? false})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result           ((:execute tool) {"action" "mutate"
                                             "mutation" "psi.extension/not-a-real-mutation"
                                             "params" {}})
          parsed           (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :mutate (:psi-tool/action parsed)))
      (is (= :error (:psi-tool/overall-status parsed)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase])))
      (is (= "Unknown psi-tool mutation: psi.extension/not-a-real-mutation"
             (get-in parsed [:psi-tool/error :message])))
      (is (not (contains? parsed :psi-tool/result)))))

  (testing "psi-tool mutate rejects malformed params with validate error"
    (let [[ctx session-id] (create-session-context {:persist? false})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result           ((:execute tool) {"action" "mutate"
                                             "mutation" "psi.extension/close-session"
                                             "params" "not-a-map"})
          parsed           (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :error (:psi-tool/overall-status parsed)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase])))
      (is (= :string (get-in parsed [:psi-tool/error :data :params-type])))
      (is (not (contains? parsed :psi-tool/result)))))

  (testing "psi-tool mutate rejects unsupported entity"
    (let [[ctx session-id] (create-session-context {:persist? false})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result           ((:execute tool) {"action" "mutate"
                                             "mutation" "psi.extension/close-session"
                                             "entity" "{:psi.agent-session/session-id \"x\"}"
                                             "params" {"session-id" "x"}})
          parsed           (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase])))
      (is (re-find #"does not support `entity`" (get-in parsed [:psi-tool/error :message])))))

  (testing "psi-tool mutate preserves canonical successful mutation payloads, including non-error empty-target results"
    (let [[ctx session-id] (create-session-context {:persist? false})
          tool             (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result           ((:execute tool) {"action" "mutate"
                                             "mutation" "psi.extension/close-session"
                                             "params" {}})
          parsed           (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (contains? parsed :psi-tool/result))
      (is (= #:psi.agent-session{:close-session-closed? false
                                 :close-session-id nil}
             (:psi-tool/result parsed)))
      (is (not (contains? parsed :psi-tool/error)))))

  (testing "psi-tool mutate uses canonical runtime mutation path rather than eval or query function"
    (let [[ctx session-id] (create-session-context {:persist? false})
          child-sd         (session/new-session-in! ctx session-id {})
          target-id        (:session-id child-sd)
          query-called?    (atom false)
          called           (atom nil)
          tool             (tools/make-psi-tool (fn [_q] (reset! query-called? true) {}) {:ctx ctx :session-id session-id})]
      (with-redefs [runtime-eql/run-extension-mutation-in! (fn [_ctx sid op-sym params]
                                                             (reset! called {:session-id sid
                                                                             :op-sym op-sym
                                                                             :params params})
                                                             {:ok true})]
        (let [result ((:execute tool) {"action" "mutate"
                                       "mutation" "psi.extension/close-session"
                                       "params" {"session-id" target-id}})
              parsed (read-string (:content result))]
          (is (false? @query-called?))
          (is (= session-id (:session-id @called)))
          (is (= 'psi.extension/close-session (:op-sym @called)))
          (is (= {:session-id target-id} (:params @called)))
          (is (= {:ok true} (:psi-tool/result parsed)))))))

  (testing "query active session then compact summaries then mutate chosen non-active session"
    (let [[ctx active-session-id] (create-session-context {:persist? false})
          child-sd                (session/new-session-in! ctx active-session-id {:session-name "helper session"})
          child-session-id        (:session-id child-sd)
          tool                    (tools/make-psi-tool (fn [q] (session/query-in ctx active-session-id q))
                                                       {:ctx ctx :session-id active-session-id})
          exec                    (:execute tool)
          active-result           (read-string (:content (exec {"action" "query"
                                                                "query" "[:psi.agent-session/active-session-id]"})))
          summary-result          (read-string (:content (exec {"action" "query"
                                                                "query" "[{:psi.agent-session/context-session-summaries [:psi.session-info/id :psi.session-info/display-name :psi.session-info/created :psi.session-info/updated :psi.session-info/parent-session-id :psi.session-info/worktree-path]}]"})))
          chosen-id               (->> (:psi.agent-session/context-session-summaries summary-result)
                                       (map :psi.session-info/id)
                                       (remove #{(:psi.agent-session/active-session-id active-result)})
                                       first)
          mutate-result           (read-string (:content (exec {"action" "mutate"
                                                                "mutation" "psi.extension/close-session"
                                                                "params" {"session-id" chosen-id}})))
          verify-result           (read-string (:content (exec {"action" "query"
                                                                "query" "[{:psi.agent-session/context-session-summaries [:psi.session-info/id :psi.session-info/display-name]} :psi.agent-session/active-session-id]"})))
          remaining-ids           (set (map :psi.session-info/id (:psi.agent-session/context-session-summaries verify-result)))]
      (is (= active-session-id (:psi.agent-session/active-session-id active-result)))
      (is (= child-session-id chosen-id))
      (is (= :ok (:psi-tool/overall-status mutate-result)))
      (is (= child-session-id (get-in mutate-result [:psi-tool/result :psi.agent-session/close-session-id])))
      (is (not (contains? remaining-ids child-session-id)))
      (is (contains? remaining-ids active-session-id))
      (is (= active-session-id (:psi.agent-session/active-session-id verify-result))))))

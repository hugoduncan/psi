(ns psi.agent-session.session-close-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.session-state :as ss]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest close-session-cancels-owned-schedules-and-removes-session-test
  (testing "close-session-in! cancels pending/queued schedules, clears timers, and removes the session"
    (let [[ctx session-id] (create-session-context {:persist? false})
          sibling          (session/new-session-in! ctx session-id {:session-name "sibling"})
          sibling-id       (:session-id sibling)
          _                (dispatch/dispatch! ctx :scheduler/create
                                               {:session-id session-id
                                                :schedule-id "sch-pending"
                                                :label "pending"
                                                :message "pending"
                                                :fire-at (.plusSeconds (java.time.Instant/now) 60)}
                                               {:origin :core})
          _                (dispatch/dispatch! ctx :scheduler/create
                                               {:session-id session-id
                                                :schedule-id "sch-queued"
                                                :label "queued"
                                                :message "queued"
                                                :fire-at (.plusSeconds (java.time.Instant/now) 60)}
                                               {:origin :core})
          _                (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :scheduler :schedules "sch-queued" :status] :queued)
          _                (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :scheduler :queue] ["sch-queued"])
          result           (session/close-session-in! ctx session-id)]
      (is (true? (:closed? result)))
      (is (= session-id (:session-id result)))
      (is (= sibling-id (:active-session-id result)))
      (is (nil? (ss/get-session-data-in ctx session-id)))
      (is (some? (ss/get-session-data-in ctx sibling-id)))
      (is (= {} @(:scheduler-timers* ctx)))))

  (testing "close-session-in! closes the last remaining session and leaves no active session"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _                (dispatch/dispatch! ctx :scheduler/create
                                               {:session-id session-id
                                                :schedule-id "sch-1"
                                                :label "pending"
                                                :message "pending"
                                                :fire-at (.plusSeconds (java.time.Instant/now) 60)}
                                               {:origin :core})
          result           (session/close-session-in! ctx session-id)]
      (is (true? (:closed? result)))
      (is (nil? (:active-session-id result)))
      (is (= [] (ss/list-context-sessions-in ctx)))
      (is (= {} @(:scheduler-timers* ctx))))))

(deftest close-session-idempotent-test
  (testing "close-session-in! returns {:closed? false} for a session-id not in context"
    (let [[ctx _] (create-session-context {:persist? false})
          result  (session/close-session-in! ctx "nonexistent-session-id")]
      (is (false? (:closed? result)))
      (is (= "nonexistent-session-id" (:session-id result)))))

  (testing "close-session-in! is idempotent: second call returns {:closed? false}"
    (let [[ctx session-id] (create-session-context {:persist? false})
          first-result     (session/close-session-in! ctx session-id)
          second-result    (session/close-session-in! ctx session-id)]
      (is (true? (:closed? first-result)))
      (is (false? (:closed? second-result))))))

(deftest close-session-deletes-statechart-working-memory-test
  (testing "close-session-in! deletes the statechart working memory for the closed session"
    (let [[ctx session-id] (create-session-context {:persist? false})
          sc-session-id    (ss/sc-session-id-in ctx session-id)]
      ;; Before close: statechart is running (idle phase)
      (is (= :idle (sc/sc-phase (:sc-env ctx) sc-session-id)))
      (session/close-session-in! ctx session-id)
      ;; After close: working memory is gone, sc-phase returns nil
      (is (nil? (sc/sc-phase (:sc-env ctx) sc-session-id))))))

(deftest close-session-tree-closes-all-descendants-test
  (testing "close-session-tree-in! closes all descendants then the root"
    (let [[ctx root-id] (create-session-context {:persist? false})
          child-id      (str (java.util.UUID/randomUUID))
          grandchild-id (str (java.util.UUID/randomUUID))]
      ;; Inject child and grandchild with parent references
      (swap! (:state* ctx) assoc-in [:agent-session :sessions child-id :data]
             {:session-id child-id :parent-session-id root-id})
      (swap! (:state* ctx) assoc-in [:agent-session :sessions grandchild-id :data]
             {:session-id grandchild-id :parent-session-id child-id})
      (let [result (session/close-session-tree-in! ctx root-id)]
        (is (= 3 (:closed-count result)))
        (is (= #{root-id child-id grandchild-id} (set (:closed-session-ids result))))
        (is (nil? (ss/get-session-data-in ctx root-id)))
        (is (nil? (ss/get-session-data-in ctx child-id)))
        (is (nil? (ss/get-session-data-in ctx grandchild-id))))))

  (testing "close-session-tree-in! on a leaf session behaves like single close"
    (let [[ctx session-id] (create-session-context {:persist? false})
          result           (session/close-session-tree-in! ctx session-id)]
      (is (= 1 (:closed-count result)))
      (is (= [session-id] (:closed-session-ids result)))
      (is (nil? (ss/get-session-data-in ctx session-id)))))

  (testing "close-session-tree-in! handles already-closed descendants idempotently"
    (let [[ctx root-id] (create-session-context {:persist? false})
          child-id      (str (java.util.UUID/randomUUID))]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions child-id :data]
             {:session-id child-id :parent-session-id root-id})
      ;; Close child manually first
      (session/close-session-in! ctx child-id)
      ;; Tree close should still close root and gracefully handle already-closed child
      (let [result (session/close-session-tree-in! ctx root-id)]
        (is (= 1 (:closed-count result)))
        (is (= [root-id] (:closed-session-ids result)))))))

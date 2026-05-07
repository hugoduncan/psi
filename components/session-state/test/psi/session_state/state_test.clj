(ns psi.session-state.state-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.session-state.state :as ss]
   [psi.agent-session.core :as session]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.test-support :as test-support]))

(defn- create-ctx []
  (session/create-context (test-support/safe-context-opts {:persist? false})))

(defn- add-session!
  [ctx parent-id opts]
  (let [sd (session/new-session-in! ctx parent-id opts)]
    (:session-id sd)))

(deftest state-paths-test
  (testing "canonical state paths include extracted workflow and session locations"
    (is (= [:workflows] (ss/state-path :workflow-state)))
    (is (= [:workflows :definitions] (ss/state-path :workflow-definitions)))
    (is (= [:agent-session :sessions "sid" :data] (ss/state-path :session-data "sid")))
    (is (= [:agent-session :sessions "sid" :persistence :journal] (ss/state-path :journal "sid")))))

(deftest worktree-invariant-test
  (testing "session-worktree-path-in returns canonical worktree path"
    (let [ctx (create-ctx)
          sid (add-session! ctx nil {:worktree-path "/tmp/ws-a"})]
      (is (= "/tmp/ws-a" (ss/session-worktree-path-in ctx sid)))))

  (testing "session-worktree-path-in throws when required worktree-path is missing"
    (let [ctx (create-ctx)
          sid "missing-worktree"]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions sid :data] {:session-id sid})
      (let [ex (is (thrown? clojure.lang.ExceptionInfo (ss/session-worktree-path-in ctx sid)))]
        (is ex)))))

(deftest session-read-update-test
  (testing "get-session-data-in and session-update use extracted authority"
    (let [ctx (create-ctx)
          sid (add-session! ctx nil {:session-name "before"})]
      (is (= "before" (:session-name (ss/get-session-data-in ctx sid))))
      (ss/apply-root-state-update-in! ctx (ss/session-update sid #(assoc % :session-name "after")))
      (is (= "after" (:session-name (ss/get-session-data-in ctx sid)))))))

(deftest journal-append-test
  (testing "append-journal-entry-root-update appends entries purely"
    (let [entry  (persist/message-entry {:role "user" :content [{:type :text :text "hi"}]})
          state' ((ss/append-journal-entry-root-update "sid" entry) {})]
      (is (= [entry] (get-in state' [:agent-session :sessions "sid" :persistence :journal])))))

  (testing "append-journal-entry-in! appends entries through extracted component"
    (let [ctx      (create-ctx)
          sid      (add-session! ctx nil {})
          before   (vec (ss/get-state-value-in ctx (ss/state-path :journal sid)))
          entry    (persist/message-entry {:role "user" :content [{:type :text :text "hi"}]})]
      (ss/append-journal-entry-in! ctx sid entry)
      (let [after (vec (ss/get-state-value-in ctx (ss/state-path :journal sid)))]
        (is (= (conj before entry) after))
        (is (= entry (last after)))))))

(deftest children-of-in-empty-test
  (testing "children-of-in returns empty vec when no sessions share parent-id"
    (let [ctx (create-ctx)
          sid (add-session! ctx nil {})]
      (is (= [] (ss/children-of-in ctx sid))))))

(deftest children-of-in-single-child-test
  (testing "children-of-in returns the single direct child"
    (let [ctx      (create-ctx)
          parent   (add-session! ctx nil {})
          child-id (str (java.util.UUID/randomUUID))]
      (swap! (:state* ctx) assoc-in
             [:agent-session :sessions child-id :data]
             {:session-id child-id :parent-session-id parent})
      (is (= [child-id] (ss/children-of-in ctx parent))))))

(deftest descendants-of-in-multi-level-bottom-up-test
  (testing "descendants-of-in returns multi-level tree in leaf-first order"
    (let [ctx        (create-ctx)
          parent     (add-session! ctx nil {})
          child      (str (java.util.UUID/randomUUID))
          grandchild (str (java.util.UUID/randomUUID))]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions child :data]
             {:session-id child :parent-session-id parent})
      (swap! (:state* ctx) assoc-in [:agent-session :sessions grandchild :data]
             {:session-id grandchild :parent-session-id child})
      (is (= [grandchild child] (ss/descendants-of-in ctx parent))))))

(ns psi.agent-session.session-state-enumeration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]))

(defn- create-ctx []
  (session/create-context (test-support/safe-context-opts {:persist? false})))

(defn- add-session!
  "Add a session to ctx with optional :parent-session-id, return session-id."
  [ctx parent-id opts]
  (let [sd (session/new-session-in! ctx parent-id opts)]
    (:session-id sd)))

(deftest children-of-in-empty-test
  (testing "children-of-in returns empty vec when no sessions share parent-id"
    (let [ctx (create-ctx)
          sid (add-session! ctx nil {})]
      (is (= [] (ss/children-of-in ctx sid))))))

(deftest children-of-in-single-child-test
  (testing "children-of-in returns the single direct child"
    ;; new-session-in! creates a fresh root; inject parent-session-id manually.
    (let [ctx      (create-ctx)
          parent   (add-session! ctx nil {})
          child-id (str (java.util.UUID/randomUUID))]
      (swap! (:state* ctx) assoc-in
             [:agent-session :sessions child-id :data]
             {:session-id child-id :parent-session-id parent})
      (is (= [child-id] (ss/children-of-in ctx parent))))))

(deftest children-of-in-no-parent-match-test
  (testing "children-of-in does not return sessions whose parent-session-id is different"
    (let [ctx  (create-ctx)
          sid1 (add-session! ctx nil {})
          sid2 (add-session! ctx nil {})]
      (is (= [] (ss/children-of-in ctx sid1)))
      (is (= [] (ss/children-of-in ctx sid2))))))

(deftest descendants-of-in-empty-test
  (testing "descendants-of-in returns empty vec when session has no children"
    (let [ctx (create-ctx)
          sid (add-session! ctx nil {})]
      (is (= [] (ss/descendants-of-in ctx sid))))))

(deftest descendants-of-in-single-level-test
  (testing "descendants-of-in returns direct children when depth is 1"
    (let [ctx      (create-ctx)
          parent   (add-session! ctx nil {})
          child-a  (str (java.util.UUID/randomUUID))
          child-b  (str (java.util.UUID/randomUUID))]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions child-a :data]
             {:session-id child-a :parent-session-id parent})
      (swap! (:state* ctx) assoc-in [:agent-session :sessions child-b :data]
             {:session-id child-b :parent-session-id parent})
      (let [descs (set (ss/descendants-of-in ctx parent))]
        (is (contains? descs child-a))
        (is (contains? descs child-b))
        (is (= 2 (count descs)))))))

(deftest descendants-of-in-multi-level-bottom-up-test
  (testing "descendants-of-in returns multi-level tree in leaf-first (bottom-up) order"
    (let [ctx        (create-ctx)
          parent     (add-session! ctx nil {})
          child      (str (java.util.UUID/randomUUID))
          grandchild (str (java.util.UUID/randomUUID))]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions child :data]
             {:session-id child :parent-session-id parent})
      (swap! (:state* ctx) assoc-in [:agent-session :sessions grandchild :data]
             {:session-id grandchild :parent-session-id child})
      (let [descs (ss/descendants-of-in ctx parent)]
        ;; grandchild must appear before child (leaf-first)
        (is (= [grandchild child] descs))))))

(deftest descendants-of-in-does-not-include-root-test
  (testing "descendants-of-in does not include root-id itself"
    (let [ctx   (create-ctx)
          root  (add-session! ctx nil {})
          child (str (java.util.UUID/randomUUID))]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions child :data]
             {:session-id child :parent-session-id root})
      (is (not (contains? (set (ss/descendants-of-in ctx root)) root))))))

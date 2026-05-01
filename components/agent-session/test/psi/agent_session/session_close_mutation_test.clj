(ns psi.agent-session.session-close-mutation-test
  "Integration tests for psi.extension/close-session and
   psi.extension/close-session-tree mutations."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.query.core :as query]))

(defn- create-session-context []
  (let [ctx (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(defn- make-mutate [ctx]
  (let [qctx (query/create-query-context)]
    (session/register-resolvers-in! qctx false)
    (session/register-mutations-in! qctx mutations/all-mutations true)
    (fn [op params]
      (get (query/query-in qctx
                           {:psi/agent-session-ctx ctx}
                           [(list op (assoc params :psi/agent-session-ctx ctx))])
           op))))

(deftest close-session-mutation-closes-target-session-test
  (testing "psi.extension/close-session closes the specified target session"
    (let [[ctx caller-id]  (create-session-context)
          target-id        (str (java.util.UUID/randomUUID))
          mutate           (make-mutate ctx)]
      ;; Inject a target session distinct from the caller
      (swap! (:state* ctx) assoc-in [:agent-session :sessions target-id :data]
             {:session-id target-id})
      (let [result (mutate 'psi.extension/close-session {:session-id target-id})]
        (is (true? (:psi.agent-session/close-session-closed? result)))
        (is (= target-id (:psi.agent-session/close-session-id result)))
        ;; Target is gone; caller is untouched
        (is (nil? (ss/get-session-data-in ctx target-id)))
        (is (some? (ss/get-session-data-in ctx caller-id))))))

  (testing "psi.extension/close-session is idempotent for already-closed sessions"
    (let [[ctx _]  (create-session-context)
          ghost-id "nonexistent-session-id"
          mutate   (make-mutate ctx)
          result   (mutate 'psi.extension/close-session {:session-id ghost-id})]
      (is (false? (:psi.agent-session/close-session-closed? result)))
      (is (= ghost-id (:psi.agent-session/close-session-id result))))))

(deftest close-session-tree-mutation-closes-tree-test
  (testing "psi.extension/close-session-tree closes a root and all its descendants"
    (let [[ctx caller-id]  (create-session-context)
          root-id          (str (java.util.UUID/randomUUID))
          child-id         (str (java.util.UUID/randomUUID))
          mutate           (make-mutate ctx)]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions root-id :data]
             {:session-id root-id})
      (swap! (:state* ctx) assoc-in [:agent-session :sessions child-id :data]
             {:session-id child-id :parent-session-id root-id})
      (let [result (mutate 'psi.extension/close-session-tree {:session-id root-id})]
        (is (= 2 (:psi.agent-session/close-session-tree-closed-count result)))
        (is (= #{root-id child-id}
               (set (:psi.agent-session/close-session-tree-closed-ids result))))
        ;; Caller is untouched
        (is (some? (ss/get-session-data-in ctx caller-id)))))))

(deftest close-session-mutation-targets-explicit-session-not-caller-test
  (testing "psi.extension/close-session with no session-id in session-scoped-ops injection still uses explicit session-id"
    ;; Verify that session-scoped-extension-mutation-ops does NOT include these ops
    ;; by calling with explicit session-id that differs from the calling context.
    (let [[ctx caller-id]  (create-session-context)
          other-id         (str (java.util.UUID/randomUUID))
          mutate           (make-mutate ctx)]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions other-id :data]
             {:session-id other-id})
      ;; Explicitly target other-id
      (mutate 'psi.extension/close-session {:session-id other-id})
      ;; caller-id must still exist
      (is (some? (ss/get-session-data-in ctx caller-id)))
      ;; other-id must be gone
      (is (nil? (ss/get-session-data-in ctx other-id))))))

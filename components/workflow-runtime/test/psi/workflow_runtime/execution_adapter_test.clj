(ns psi.workflow-runtime.execution-adapter-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.execution-adapter :as execution-adapter]))

(defn- ex-for
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo ex
      ex)))

(defn- missing-op-ctx
  [op-key]
  {execution-adapter/adapter-key
   (dissoc
    (execution-adapter/create
     {:create-child-session! (fn [_ctx _parent _opts] :ok)
      :prompt-execution-result! (fn
                                  ([_ctx _sid _text] :ok)
                                  ([_ctx _sid _text _images] :ok)
                                  ([_ctx _sid _text _images _opts] :ok))
      :get-session-data (fn [_ctx _sid] :ok)
      :list-context-sessions (fn [_ctx] :ok)
      :find-skill (fn [_ctx _skills _skill-name] :ok)
      :execute-judge! (fn [_ctx _parent _actor _judge-spec _routing-table _routing-context] :ok)
      :abort-session! (fn [_ctx _session-id] :ok)})
    op-key)})

(deftest create-child-session-missing-operation-test
  (testing "missing create-child-session operation fails clearly"
    (let [ex (ex-for #(execution-adapter/create-child-session!
                       (missing-op-ctx :create-child-session!)
                       "parent"
                       {}))]
      (is (some? ex))
      (is (re-find #"operation is required" (ex-message ex)))
      (is (= execution-adapter/adapter-key (:adapter-key (ex-data ex))))
      (is (= :create-child-session! (:operation (ex-data ex)))))))

(deftest create-child-session-forwards-parent-and-opts-unchanged-test
  (testing "create-child-session! forwards ctx, parent-session-id, and opts unchanged"
    (let [calls* (atom [])
          ctx {execution-adapter/adapter-key
               (execution-adapter/create
                {:create-child-session! (fn [ctx' parent opts]
                                          (swap! calls* conj {:ctx ctx' :parent parent :opts opts})
                                          {:psi.agent-session/session-id (:child-session-id opts)})})}
          opts {:child-session-id "child-1"
                :session-name "workflow child"
                :workflow-owned? true}
          result (execution-adapter/create-child-session! ctx "parent-1" opts)]
      (is (= {:psi.agent-session/session-id "child-1"} result))
      (is (= [{:ctx ctx :parent "parent-1" :opts opts}] @calls*)))))

(deftest prompt-execution-result-missing-operation-test
  (testing "missing prompt-execution-result operation fails clearly"
    (let [ex (ex-for #(execution-adapter/prompt-execution-result!
                       (missing-op-ctx :prompt-execution-result!)
                       "sid"
                       "hello"))]
      (is (some? ex))
      (is (re-find #"operation is required" (ex-message ex)))
      (is (= execution-adapter/adapter-key (:adapter-key (ex-data ex))))
      (is (= :prompt-execution-result! (:operation (ex-data ex)))))))

(deftest get-session-data-missing-operation-test
  (testing "missing get-session-data operation fails clearly"
    (let [ex (ex-for #(execution-adapter/get-session-data
                       (missing-op-ctx :get-session-data)
                       "sid"))]
      (is (some? ex))
      (is (re-find #"operation is required" (ex-message ex)))
      (is (= execution-adapter/adapter-key (:adapter-key (ex-data ex))))
      (is (= :get-session-data (:operation (ex-data ex)))))))

(deftest list-context-sessions-missing-operation-test
  (testing "missing list-context-sessions operation fails clearly"
    (let [ex (ex-for #(execution-adapter/list-context-sessions
                       (missing-op-ctx :list-context-sessions)))]
      (is (some? ex))
      (is (re-find #"operation is required" (ex-message ex)))
      (is (= execution-adapter/adapter-key (:adapter-key (ex-data ex))))
      (is (= :list-context-sessions (:operation (ex-data ex)))))))

(deftest find-skill-missing-operation-test
  (testing "missing find-skill operation fails clearly"
    (let [ex (ex-for #(execution-adapter/find-skill
                       (missing-op-ctx :find-skill)
                       []
                       "skill"))]
      (is (some? ex))
      (is (re-find #"operation is required" (ex-message ex)))
      (is (= execution-adapter/adapter-key (:adapter-key (ex-data ex))))
      (is (= :find-skill (:operation (ex-data ex)))))))

(deftest execute-judge-missing-operation-test
  (testing "missing execute-judge operation fails clearly"
    (let [ex (ex-for #(execution-adapter/execute-judge!
                       (missing-op-ctx :execute-judge!)
                       "parent"
                       "actor"
                       {}
                       {}
                       {}))]
      (is (some? ex))
      (is (re-find #"operation is required" (ex-message ex)))
      (is (= execution-adapter/adapter-key (:adapter-key (ex-data ex))))
      (is (= :execute-judge! (:operation (ex-data ex)))))))

(deftest abort-session-missing-operation-test
  (testing "missing abort-session operation fails clearly"
    (let [ex (ex-for #(execution-adapter/abort-session!
                       (missing-op-ctx :abort-session!)
                       "sid"))]
      (is (some? ex))
      (is (re-find #"operation is required" (ex-message ex)))
      (is (= execution-adapter/adapter-key (:adapter-key (ex-data ex))))
      (is (= :abort-session! (:operation (ex-data ex)))))))

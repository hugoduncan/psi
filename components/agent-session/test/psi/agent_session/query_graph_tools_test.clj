(ns psi.agent-session.query-graph-tools-test
  "Tool- and bootstrap-focused portions split from query_graph_test."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.query.core :as query])
  (:import
   (java.io File)))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest runtime-tool-executor-boundary-test
  (testing "execute-tool-runtime-in! uses the ctx-provided runtime tool executor when present"
    (let [calls      (atom [])
          [ctx session-id] (create-session-context {:persist? false})
          ctx        (assoc ctx :runtime-tool-executor-fn
                            (fn [_ctx tool-name args opts]
                              (swap! calls conj {:tool-name tool-name :args args :opts opts})
                              {:content "custom" :is-error false}))]
      (is (= {:content "custom" :is-error false}
             (tool-plan/execute-tool-runtime-in! ctx session-id "custom-tool" {"x" 1} {:tool-call-id "c1"})))
      (is (= [{:tool-name "custom-tool"
               :args {"x" 1}
               :opts {:tool-call-id "c1"}}]
             @calls))))

  (testing "default-execute-runtime-tool-in! remains the fallback runtime implementation"
    (let [[ctx session-id] (create-session-context {:persist? false})]
      (agent-core/set-tools-in!
       (ss/agent-ctx-in ctx session-id)
       [{:name "prefix-a"
         :execute (fn [args _opts]
                    {:content (str "A:" (get args "text" ""))
                     :is-error false})}])
      (is (= {:content "A:hello" :is-error false}
             (tool-plan/default-execute-runtime-tool-in! ctx session-id "prefix-a" {"text" "hello"} {})))
      (is (= {:content "A:hello" :is-error false}
             (tool-plan/execute-tool-runtime-in! ctx session-id "prefix-a" {"text" "hello"} {})))))

  (testing "default-execute-runtime-tool-in! falls back to extension registry when projected agent tools omit execute"
    (let [[ctx session-id] (create-session-context {:persist? false})
          reg              (:extension-registry ctx)
          exec-fn          (fn [args _opts]
                             {:content (str "EXT:" (get args "text" ""))
                              :is-error false})]
      (ext/register-extension-in! reg "/ext/a")
      (ext/register-tool-in! reg "/ext/a" {:name "ext-tool"
                                           :description "extension tool"
                                           :parameters {:type "object"}
                                           :execute exec-fn})
      ;; Simulate the agent-core runtime projection stripping :execute while the
      ;; canonical extension registry still retains the executable fn.
      (agent-core/set-tools-in!
       (ss/agent-ctx-in ctx session-id)
       [{:name "ext-tool"
         :label "ext-tool"
         :description "extension tool"
         :parameters {:type "object"}}])
      (is (= {:content "EXT:hello" :is-error false}
             (tool-plan/default-execute-runtime-tool-in! ctx session-id "ext-tool" {"text" "hello"} {}))))))

(deftest tool-plan-runtime-tool-executor-boundary-test
  (testing "run-tool-plan uses the ctx-provided runtime tool executor boundary"
    (let [calls         (atom [])
          [ctx* session-id] (create-session-context {:persist? false})
          ctx            (assoc ctx*
                                :runtime-tool-executor-fn
                                (fn [_ctx tool-name args opts]
                                  (swap! calls conj {:tool-name tool-name :args args :opts opts})
                                  {:content (str "custom:" (get args "text" ""))
                                   :is-error false}))
          qctx       (query/create-query-context)
          mutate     (fn [op params]
                       (get (query/query-in qctx
                                            {:psi/agent-session-ctx ctx}
                                            [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                        (not (contains? params :session-id))
                                                        (assoc :session-id session-id)))])
                            op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (let [result (mutate 'psi.extension/run-tool-plan
                           {:steps [{:id :s1 :tool "custom-tool" :args {:text "hello"}}]})]
        (is (true? (:psi.extension.tool-plan/succeeded? result)))
        (is (= "custom:hello"
               (get-in result [:psi.extension.tool-plan/result-by-id :s1 :content])))
        (is (= 1 (count @calls)))))))

(deftest tool-plan-mutation-test
  (testing "run-tool-plan chains step outputs into later step args"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (agent-core/set-tools-in!
       (ss/agent-ctx-in ctx session-id)
       [{:name "prefix-a"
         :execute (fn [args _opts]
                    {:content  (str "A:" (get args "text" ""))
                     :is-error false})}
        {:name "prefix-b"
         :execute (fn [args _opts]
                    {:content  (str "B:" (get args "text" ""))
                     :is-error false})}])
      (let [result (mutate 'psi.extension/run-tool-plan
                           {:steps [{:id :s1 :tool "prefix-a" :args {:text "hello"}}
                                    {:id :s2 :tool "prefix-b" :args {:text [:from :s1 :content]}}]})]
        (is (true? (:psi.extension.tool-plan/succeeded? result)))
        (is (= "B:A:hello"
               (get-in result [:psi.extension.tool-plan/result-by-id :s2 :content]))))))

  (testing "run-tool-plan stops on first failing step by default"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          ran-last (atom false)
          mutate   (fn [op params]
                     (get (query/query-in qctx
                                          {:psi/agent-session-ctx ctx}
                                          [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                      (not (contains? params :session-id))
                                                      (assoc :session-id session-id)))])
                          op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (agent-core/set-tools-in!
       (ss/agent-ctx-in ctx session-id)
       [{:name "ok" :execute (fn [_args _opts] {:content "ok" :is-error false})}
        {:name "fail" :execute (fn [_args _opts] {:content "boom" :is-error true})}
        {:name "should-not-run"
         :execute (fn [_args _opts] (reset! ran-last true) {:content "ran" :is-error false})}])
      (let [result (mutate 'psi.extension/run-tool-plan
                           {:steps [{:id :s1 :tool "ok"}
                                    {:id :s2 :tool "fail"}
                                    {:id :s3 :tool "should-not-run"}]})]
        (is (false? (:psi.extension.tool-plan/succeeded? result)))
        (is (= :s2 (:psi.extension.tool-plan/failed-step-id result)))
        (is (false? @ran-last))))))

(deftest tool-mutations-test
  (testing "built-in tool mutations execute read/write/update/bash"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))
          f      (File/createTempFile "psi-tool-mutation" ".txt")
          path   (.getAbsolutePath f)]
      (.deleteOnExit f)
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (let [w  (mutate 'psi.extension.tool/write {:path path :content "alpha"})
            r1 (mutate 'psi.extension.tool/read {:path path})
            u  (mutate 'psi.extension.tool/update {:path path :oldText "alpha" :newText "beta"})
            r2 (mutate 'psi.extension.tool/read {:path path})
            b  (mutate 'psi.extension.tool/bash {:command "printf 'ok'"})]
        (is (= "write" (:psi.extension.tool/name w)))
        (is (= "alpha" (:psi.extension.tool/content r1)))
        (is (= "edit" (:psi.extension.tool/name u)))
        (is (= "beta" (:psi.extension.tool/content r2)))
        (is (= "bash" (:psi.extension.tool/name b))))))

  (testing "chain mutation is an alias to run-tool-plan"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (agent-core/set-tools-in!
       (ss/agent-ctx-in ctx session-id)
       [{:name "prefix-a" :execute (fn [args _opts] {:content  (str "A:" (get args "text" "")) :is-error false})}
        {:name "prefix-b" :execute (fn [args _opts] {:content  (str "B:" (get args "text" "")) :is-error false})}])
      (let [result (mutate 'psi.extension.tool/chain
                           {:steps [{:id :s1 :tool "prefix-a" :args {:text "hello"}}
                                    {:id :s2 :tool "prefix-b" :args {:text [:from :s1 :content]}}]})]
        (is (true? (:psi.extension.tool-plan/succeeded? result)))
        (is (= "B:A:hello"
               (get-in result [:psi.extension.tool-plan/result-by-id :s2 :content]))))))

  (testing "register/update/unregister prompt contribution mutations"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (let [r1 (mutate 'psi.extension/register-prompt-contribution {:ext-path "/ext/a" :id "c1" :contribution {:content "A" :priority 10 :enabled true}})
            r2 (mutate 'psi.extension/update-prompt-contribution {:ext-path "/ext/a" :id "c1" :patch {:content "B"}})
            r3 (mutate 'psi.extension/unregister-prompt-contribution {:ext-path "/ext/a" :id "c1"})]
        (is (true? (:psi.extension.prompt-contribution/registered? r1)))
        (is (true? (:psi.extension.prompt-contribution/updated? r2)))
        (is (true? (:psi.extension.prompt-contribution/removed? r3)))))))

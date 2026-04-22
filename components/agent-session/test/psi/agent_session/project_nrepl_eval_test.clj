(ns psi.agent-session.project-nrepl-eval-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.project-nrepl-eval :as project-nrepl-eval]
   [psi.agent-session.project-nrepl-runtime :as project-nrepl-runtime]
   [psi.agent-session.test-support :as test-support]))

(defn- make-ctx []
  (let [[ctx _] (test-support/create-test-session {:persist? false})]
    ctx))

(defn- install-instance!
  [ctx worktree-path client-session]
  (project-nrepl-runtime/ensure-instance-in!
   ctx
   {:worktree-path worktree-path
    :acquisition-mode :attached
    :endpoint {:host "127.0.0.1" :port 7888 :port-source :explicit}})
  (project-nrepl-runtime/update-instance-in!
   ctx worktree-path
   #(assoc %
           :lifecycle-state :ready
           :readiness true
           :active-session-id "nrepl-session-1"
           :runtime-handle {:client-session client-session
                            :session-id "nrepl-session-1"})))

(deftest eval-instance-in-test
  (testing "successful eval returns structured result and updates projection"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")
          calls*   (atom [])
          client-session (fn [msg]
                           (swap! calls* conj msg)
                           [{:id (:id msg)
                             :session "nrepl-session-1"
                             :value "3"
                             :status #{"done"}}])]
      (install-instance! ctx worktree client-session)
      (let [result (project-nrepl-eval/eval-instance-in! ctx worktree "(+ 1 2)")
            instance (project-nrepl-runtime/instance-in ctx worktree)]
        (is (= :success (:status result)))
        (is (= "(+ 1 2)" (:input result)))
        (is (= "3" (:value result)))
        (is (= :ready (:lifecycle-state instance)))
        (is (= :success (get-in instance [:last-eval :status])))
        (is (= "(+ 1 2)" (get-in instance [:last-eval :input])))
        (is (nil? (get-in instance [:runtime-handle :active-op])))
        (is (= "eval" (:op (first @calls*)))))))

  (testing "single-flight eval rejects concurrent in-flight eval"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")]
      (install-instance! ctx worktree (fn [_] []))
      (project-nrepl-runtime/update-instance-in!
       ctx worktree
       #(assoc-in % [:runtime-handle :active-op] {:op-id "active" :started-at (java.time.Instant/now)}))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"already in flight"
           (project-nrepl-eval/eval-instance-in! ctx worktree "(+ 1 2)")))))

  (testing "eval transport failure returns unavailable and records last-error"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")
          client-session (fn [_] (throw (ex-info "eval-boom" {:phase :eval})))]
      (install-instance! ctx worktree client-session)
      (let [result (project-nrepl-eval/eval-instance-in! ctx worktree "(+ 1 2)")
            instance (project-nrepl-runtime/instance-in ctx worktree)]
        (is (= :unavailable (:status result)))
        (is (= "eval-boom" (get-in result [:error :message])))
        (is (= :ready (:lifecycle-state instance)))
        (is (= :unavailable (get-in instance [:last-eval :status])))
        (is (= "eval-boom" (get-in instance [:last-error :message])))))))

(deftest interrupt-instance-in-test
  (testing "interrupt reports unavailable when no eval is active"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")]
      (install-instance! ctx worktree (fn [_] []))
      (is (= {:status :unavailable
              :reason :no-active-eval
              :worktree-path worktree}
             (project-nrepl-eval/interrupt-instance-in! ctx worktree)))))

  (testing "interrupt targets the active eval op id and records summary"
    (let [ctx      (make-ctx)
          worktree (System/getProperty "user.dir")
          calls*   (atom [])
          client-session (fn [msg]
                           (swap! calls* conj msg)
                           [{:id (:id msg)
                             :session "nrepl-session-1"
                             :status #{"done"}}])]
      (install-instance! ctx worktree client-session)
      (project-nrepl-runtime/update-instance-in!
       ctx worktree
       #(assoc-in % [:runtime-handle :active-op] {:op-id "eval-123" :started-at (java.time.Instant/now)}))
      (let [result (project-nrepl-eval/interrupt-instance-in! ctx worktree)
            instance (project-nrepl-runtime/instance-in ctx worktree)]
        (is (= :success (:status result)))
        (is (= "eval-123" (:interrupted-op-id result)))
        (is (= "interrupt" (:op (first @calls*))))
        (is (= "eval-123" (:interrupt-id (first @calls*))))
        (is (= result (:last-interrupt instance)))))))

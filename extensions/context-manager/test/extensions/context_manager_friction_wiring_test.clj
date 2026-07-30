(ns extensions.context-manager-friction-wiring-test
  "Integration-style test for the fire-and-forget `session_turn_finished`
   wiring (task 239, slice 4): the handler must return promptly even when
   the friction-analysis path is slow, and the analysis must actually run
   (observed via its own async diagnostic log line)."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.context-manager :as context-manager]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(use-fixtures :each (fn [f]
                      (reset! context-manager/initialized? nil)
                      (reset! context-manager/friction-helper-session-ids #{})
                      (reset! context-manager/entity-resolution-helper-session-ids #{})
                      (f)))

(defn- await-log-line
  "Poll (up to ~2s) until a log line matching `pred` appears in `state`."
  [state pred]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (or (some pred (:log-lines @state))
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 5)
            (recur))))))

(deftest turn-finished-handler-does-not-block-on-slow-friction-analysis-test
  (testing "handler returns promptly even though its query-session collaborator is slow"
    (let [slow-query-fn (fn [_q]
                          (Thread/sleep 200)
                          {})
          {:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/context_manager.clj"
                                :query-fn slow-query-fn})]
      (context-manager/init api)
      (let [handler (first (get-in @state [:handlers "session_turn_finished"]))
            started (System/currentTimeMillis)]
        (is (nil? (handler {:session-id "s1" :turn-id "t1"}))
            "handler must return nil promptly, not await the slow query")
        (is (< (- (System/currentTimeMillis) started) 150)
            "handler must return well before the 200ms query-session delay elapses")
        (testing "the friction-analysis future still runs and logs a diagnostic"
          (is (await-log-line
               state
               #(re-find #"context-manager: friction-analysis:" %))
              "friction-analysis eventually logs its no-op diagnostic (no
               worktree from the nullable query-session default)"))))))

(ns psi.app-runtime.session-summary-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.runtime :as runtime]
   [psi.session-state.state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.app-runtime.session-summary :as summary]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest session-summary-builds-shared-header-fragments-test
  (testing "session summary exposes session id, display name, and header model label"
    (let [[ctx sid] (create-session-context)
          _         (session/dispatch-in! ctx :session/set-model
                                          {:session-id sid
                                           :model {:provider "openai"
                                                   :id "gpt-5.3-codex"
                                                   :reasoning true}}
                                          {:origin :core})
          _         (session/dispatch-in! ctx :session/set-thinking-level
                                          {:session-id sid :level :high}
                                          {:origin :core})
          _         (runtime/journal-user-message-in! ctx sid "Investigate failures" nil)
          model     (summary/session-summary ctx sid)]
      (is (= sid (:session-id model)))
      (is (= "Investigate failures" (:session-display-name model)))
      (is (= "(openai) gpt-5.3-codex • thinking high"
             (:header-model-label model))))))

(deftest session-summary-builds-retry-status-fragments-test
  (testing "session summary status line includes visible retry timing and rate-limit text"
    (let [[ctx sid] (create-session-context)
          now-ms    (System/currentTimeMillis)
          _         (ss/apply-root-state-update-in! ctx
                                                    (ss/session-update sid #(assoc %
                                                                                   :retry {:active? true
                                                                                           :attempt 1
                                                                                           :delay-ms 8000
                                                                                           :delay-source :retry-after
                                                                                           :resume-at (+ now-ms 8000)
                                                                                           :rate-limit {:remaining 0
                                                                                                        :limit 5000
                                                                                                        :reset-at (+ now-ms 32000)}})))
          model     (summary/session-summary ctx sid)
          status    (:status-session-line model)]
      (is (re-find (re-pattern (str "^session: " sid " phase:retrying streaming:no compacting:no pending:0 retry:0"))
                   status))
      (is (re-find #"retrying-in:[78]s" status))
      (is (re-find #"source:retry-after" status))
      (is (re-find #"remaining:0/5000" status))
      (is (re-find #"reset-in:3[12]s" status)))))

(deftest session-summary-tolerates-keyword-sentinel-session-values-test
  (testing "keyword sentinel values in session state are treated as absent"
    (let [[ctx sid] (create-session-context)
          _         (ss/apply-root-state-update-in! ctx
                                                    (ss/session-update sid #(assoc %
                                                                                   :model {:provider :pathom/unknown
                                                                                           :id :pathom/unknown
                                                                                           :reasoning true}
                                                                                   :steering-messages :pathom/unknown
                                                                                   :follow-up-messages :pathom/unknown)))
          model     (summary/session-summary ctx sid)]
      (is (= sid (:session-id model)))
      (is (nil? (:header-model-label model)))
      (is (= 0 (:pending-message-count model)))
      (is (= (str "session: " sid " phase:idle streaming:no compacting:no pending:0 retry:0")
             (:status-session-line model))))))

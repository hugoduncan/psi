(ns psi.app-runtime.messages-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.session-persistence.core :as persist]
   [psi.session-state.state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.app-runtime.messages :as app-messages]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest session-messages-rebuilds-canonical-messages-from-journal-test
  (let [[ctx sid] (create-session-context {:persist? false})
        _         (ss/append-journal-entry-in! ctx sid (persist/message-entry {:role "user" :content "hi"}))
        _         (ss/append-journal-entry-in! ctx sid (persist/message-entry {:role "assistant"
                                                                               :content [{:type :text :text "there"}]}))]
    (is (= [{:role "user" :content "hi"}
            {:role "assistant" :content [{:type :text :text "there"}]}]
           (app-messages/session-messages ctx sid)))))

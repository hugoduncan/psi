(ns psi.app-runtime.navigation-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.persistence :as persist]
   [psi.session-state.state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.app-runtime.context :as app-context]
   [psi.app-runtime.messages :as app-messages]
   [psi.app-runtime.navigation :as navigation]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest switch-session-result-builds_canonical_navigation_map_test
  (let [[ctx sid1] (create-session-context {:persist? false})
        sd2        (session/new-session-in! ctx sid1 {})
        sid2       (:session-id sd2)
        _          (ss/journal-append-in! ctx sid2 (persist/message-entry {:role "assistant"
                                                                           :content [{:type :text :text "hello"}]}))
        state      (atom {:focus-session-id sid1})
        nav        (navigation/switch-session-result ctx state sid1 sid2)]
    (is (= :switch-session (:nav/op nav)))
    (is (= sid2 (:nav/session-id nav)))
    (is (= sid2 (get-in nav [:nav/rehydration :session-id])))
    (is (= (app-messages/session-messages ctx sid2)
           (get-in nav [:nav/rehydration :messages])))
    (is (= (app-context/context-snapshot ctx sid2 sid2)
           (:nav/context-snapshot nav)))))

(deftest fork-session-result-builds_rehydration_and_context_test
  (let [[ctx sid] (create-session-context {:persist? false})
        entry     (persist/message-entry {:role "user" :content "branch here"})
        _         (ss/journal-append-in! ctx sid entry)
        _         (ss/journal-append-in! ctx sid (persist/message-entry {:role "assistant"
                                                                         :content [{:type :text :text "reply"}]}))
        state     (atom {:focus-session-id sid})
        nav       (navigation/fork-session-result ctx state sid (:id entry))]
    (is (= :fork-session (:nav/op nav)))
    (is (string? (:nav/session-id nav)))
    (is (= (:nav/session-id nav)
           (get-in nav [:nav/context-snapshot :active-session-id])))
    (is (= [{:role "user" :content "branch here"}
            {:role "assistant" :content [{:type :text :text "reply"}]}]
           (get-in nav [:nav/rehydration :messages])))))

(deftest new-session-result_supports_callback_rehydrate_test
  (let [[ctx sid] (create-session-context {:persist? false})
        state     (atom {:focus-session-id sid})
        nav       (navigation/new-session-result
                   ctx state sid
                   {:on-new-session! (fn [_]
                                       (let [sd (session/new-session-in! ctx sid {})]
                                         {:session-id (:session-id sd)
                                          :agent-messages [{:role "assistant"
                                                            :content [{:type :text :text "boot"}]}]
                                          :tool-calls {"t1" {:name "read"}}
                                          :tool-order ["t1"]}))})]
    (is (= :new-session (:nav/op nav)))
    (is (= [{:role "assistant" :content [{:type :text :text "boot"}]}]
           (get-in nav [:nav/rehydration :messages])))
    (is (= ["t1"] (get-in nav [:nav/rehydration :tool-order])))))

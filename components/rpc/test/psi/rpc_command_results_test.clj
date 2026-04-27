(ns psi.rpc-command-results-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.rpc.session.command-results :as sut]))

(deftest extension-command-output-test
  (testing "string return is preferred when present"
    (let [result (sut/extension-command-output
                  {:handler (fn [_] "returned text")
                   :args ""})]
      (is (= "returned text" result))))

  (testing "map return message is used when present"
    (let [result (sut/extension-command-output
                  {:handler (fn [_] {:message "message text"})
                   :args ""})]
      (is (= "message text" result))))

  (testing "stdout is returned when present"
    (let [result (sut/extension-command-output
                  {:handler (fn [_] (println "hello"))
                   :args ""})]
      (is (= "hello\n" result))))

  (testing "blank stdout returns nil instead of placeholder text"
    (let [result (sut/extension-command-output
                  {:handler (fn [_] nil)
                   :args ""})]
      (is (nil? result))))

  (testing "handler errors are surfaced deterministically"
    (let [result (sut/extension-command-output
                  {:handler (fn [_] (throw (ex-info "boom" {})))
                   :args ""})]
      (is (= "[extension command error: boom]" result)))))

(deftest handle-command-result-extension-command-test
  (testing "extension command with no stdout or return emits no command-result placeholder"
    (let [events (atom [])
          emit!  (fn [event data]
                   (swap! events conj [event data]))]
      (sut/handle-command-result!
       "req-1"
       {:type :extension-cmd
        :name "work-on"
        :args ""
        :handler (fn [_] nil)}
       emit!)
      (is (= [] @events))))

  (testing "extension command with string return emits text command-result"
    (let [events (atom [])
          emit!  (fn [event data]
                   (swap! events conj [event data]))]
      (sut/handle-command-result!
       "req-1"
       {:type :extension-cmd
        :name "delegate"
        :args ""
        :handler (fn [_] "Delegated to lambda-build — run run-1")}
       emit!)
      (is (= [["command-result"
               {:type "text"
                :message "Delegated to lambda-build — run run-1"}]]
             @events))))

  (testing "extension command with stdout emits text command-result"
    (let [events (atom [])
          emit!  (fn [event data]
                   (swap! events conj [event data]))]
      (sut/handle-command-result!
       "req-1"
       {:type :extension-cmd
        :name "hello"
        :args ""
        :handler (fn [_] (println "hello from ext"))}
       emit!)
      (is (= [["command-result"
               {:type "text"
                :message "hello from ext\n"}]]
             @events)))))

(deftest handle-prompt-command-result-extension-command-test
  (testing "legacy prompt path also suppresses blank extension command placeholders"
    (let [events (atom [])
          emit!  (fn [event data]
                   (swap! events conj [event data]))]
      (sut/handle-prompt-command-result!
       {:type :extension-cmd
        :name "work-on"
        :args ""
        :handler (fn [_] nil)}
       emit!)
      (is (= [] @events)))))

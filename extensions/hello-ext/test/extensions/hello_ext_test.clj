(ns extensions.hello-ext-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.hello-ext :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest init-registers-command-handler-and-tools-test
  (testing "hello extension registers commands, session handler, and demo tools"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/hello_ext.clj"})]
      (sut/init api)
      (is (= "hello"
             (get-in @state [:commands "hello" :name])))
      (is (= "hello-plan"
             (get-in @state [:commands "hello-plan" :name])))
      (is (= "hello-upper"
             (get-in @state [:tools "hello-upper" :name])))
      (is (= "hello-wrap"
             (get-in @state [:tools "hello-wrap" :name])))
      (is (= 1 (count (get-in @state [:handlers "session_switch"])))))))

(deftest nullable-api-schedule-event-test
  (testing "nullable extension api records scheduled events"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/schedule_event.clj"})]
      ((:mutate api) 'psi.extension/schedule-event
                     {:delay-ms 250
                      :event-name "rename-checkpoint"
                      :payload {:session-id "s1" :turn-count 2}})
      (is (= [{:ext-path "/test/schedule_event.clj"
               :delay-ms 250
               :event-name "rename-checkpoint"
               :payload {:session-id "s1" :turn-count 2}}]
             (:scheduled-events @state))))))

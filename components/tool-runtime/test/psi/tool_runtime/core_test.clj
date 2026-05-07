(ns psi.tool-runtime.core-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.tool-runtime.core :as core]))

(deftest content-normalization-test
  (is (= "hello" (core/tool-content->text "hello")))
  (is (= "a\nb"
         (core/tool-content->text [{:type :text :text "a"}
                                   {:type :image :data "..."}
                                   {:type :text :text "b"}])))
  (is (= [{:type :text :text "hello"}]
         (core/normalize-tool-content "hello"))))

(deftest lifecycle-event-shape-test
  (let [event (core/tool-lifecycle-event :tool-result "call-1" "read" :result-text "ok")]
    (is (= :tool-result (:event-kind event)))
    (is (= "call-1" (:tool-call-id event)))
    (is (= "call-1" (:tool-id event)))
    (is (= "read" (:tool-name event)))
    (is (= "ok" (:result-text event)))))

(deftest execute-tool-call-prepared-on-event-test
  (let [events (atom [])
        result (core/execute-tool-call-prepared!
                {:execute-tool      (fn [_tool-name _args opts]
                                      ((:on-update opts) {:content "partial" :details {:phase :running}})
                                      {:content [{:type :text :text "done"}]
                                       :is-error false
                                       :details {:truncation {:truncated false}}})
                 :post-process      (fn [_tool-call _args raw] raw)
                 :effective-policy  (fn [_] {:max-lines 10 :max-bytes 20})
                 :telemetry-args-fn (fn [_ args] args)
                 :execute-opts      {}
                 :on-event          #(swap! events conj %)}
                {:id "call-1" :name "read" :arguments "{}"}
                {})]
    (is (= [:tool-start :tool-executing :tool-execution-update]
           (mapv :event-kind @events)))
    (is (= "call-1" (get-in result [:result-message :tool-call-id])))
    (is (= [{:type :text :text "done"}] (get-in result [:result-message :content])))))

(deftest execute-tool-call-prepared-error-emits-tool-error-test
  (let [events (atom [])
        result (core/execute-tool-call-prepared!
                {:execute-tool      (fn [_tool-name _args _opts]
                                      (throw (ex-info "boom" {})))
                 :post-process      (fn [_tool-call _args raw] raw)
                 :effective-policy  (fn [_] {:max-lines 10 :max-bytes 20})
                 :telemetry-args-fn (fn [_ args] args)
                 :execute-opts      {}
                 :on-event          #(swap! events conj %)}
                {:id "call-err" :name "bash" :arguments "{}"}
                {})]
    (is (= [:tool-start :tool-executing :tool-error]
           (mapv :event-kind @events)))
    (is (= true (get-in result [:tool-result :is-error])))
    (is (= true (get-in result [:tool-result :details :exception])))
    (is (= "call-err" (get-in result [:result-message :tool-call-id])))))

(deftest error-lifecycle-execute-then-record-test
  (let [execute-events (atom [])
        record-events  (atom [])
        recorded       (atom nil)
        ended          (atom nil)
        shaped-result  (core/execute-tool-call-prepared!
                        {:execute-tool      (fn [_tool-name _args _opts]
                                              (throw (ex-info "boom" {})))
                         :post-process      (fn [_tool-call _args raw] raw)
                         :effective-policy  (fn [_] {:max-lines 10 :max-bytes 20})
                         :telemetry-args-fn (fn [_ args] args)
                         :execute-opts      {}
                         :on-event          #(swap! execute-events conj %)}
                        {:id "call-err" :name "bash" :arguments "{}"}
                        {})
        record-result  (core/record-tool-call-result!
                        {:on-event #(swap! record-events conj %)
                         :record-output-stat! (fn [_] nil)
                         :on-agent-end! (fn [tool-call tool-result is-error?]
                                          (reset! ended [tool-call tool-result is-error?]))
                         :record-result! #(reset! recorded %)}
                        shaped-result)]
    (is (= [:tool-start :tool-executing :tool-error]
           (mapv :event-kind @execute-events)))
    (is (= [:tool-result]
           (mapv :event-kind @record-events)))
    (is (= "call-err" (:tool-call-id record-result)))
    (is (= true (:is-error record-result)))
    (is (= "call-err" (:tool-call-id @recorded)))
    (is (= true (last @ended)))))

(deftest record-tool-call-result-test
  (let [events   (atom [])
        recorded (atom nil)
        ended    (atom nil)
        stats    (atom nil)
        shaped   {:tool-call {:id "call-2" :name "bash" :arguments "{}"}
                  :tool-result {:content "trimmed"
                                :is-error false
                                :details {:truncation {:truncated true :truncated-by :bytes}}}
                  :result-message {:role "toolResult"
                                   :tool-call-id "call-2"
                                   :tool-name "bash"
                                   :content [{:type :text :text "trimmed"}]
                                   :is-error false
                                   :details {:truncation {:truncated true :truncated-by :bytes}}
                                   :result-text "trimmed"}
                  :effective-policy {:max-lines 10 :max-bytes 20}}
        result   (core/record-tool-call-result!
                  {:on-event #(swap! events conj %)
                   :record-output-stat! #(reset! stats %)
                   :on-agent-end! (fn [tool-call tool-result is-error?]
                                    (reset! ended [tool-call tool-result is-error?]))
                   :record-result! #(reset! recorded %)}
                  shaped)]
    (is (= :tool-result (:event-kind (last @events))))
    (is (= "call-2" (:tool-call-id result)))
    (is (= "call-2" (:tool-call-id @recorded)))
    (is (= "call-2" (get-in @stats [:stat :tool-call-id])))
    (is (= false (last @ended)))))

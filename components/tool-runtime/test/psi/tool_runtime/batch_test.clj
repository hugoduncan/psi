(ns psi.tool-runtime.batch-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.tool-runtime.batch :as batch])
  (:import
   (java.util.concurrent ExecutorService Executors)))

(deftest tool-call-file-key-test
  (is (= "/tmp/a" (batch/tool-call-file-key {:arguments "{\"path\":\"/tmp/a\"}"})))
  (is (= "/tmp/b" (batch/tool-call-file-key {:parsed-args {"file" "/tmp/b"}})))
  (is (nil? (batch/tool-call-file-key {:arguments "{}"}))))

(deftest run-tool-calls-order-and-recording-test
  (let [executor (Executors/newFixedThreadPool 2)
        prepared (atom [])
        recorded (atom [])]
    (try
      (let [results (batch/run-tool-calls!
                     {:executor executor
                      :run-one! (fn [tool-call parsed-args]
                                  {:tool-call-id (:id tool-call)
                                   :parsed-args parsed-args})
                      :execute-prepared! (fn [tool-call _parsed-args]
                                           (swap! prepared conj (:id tool-call))
                                           {:tool-call tool-call
                                            :result-message {:tool-call-id (:id tool-call)}})
                      :record-result! (fn [shaped-result]
                                        (swap! recorded conj (get-in shaped-result [:result-message :tool-call-id]))
                                        (:result-message shaped-result))}
                     [{:id "call-1" :name "read" :arguments "{}"}
                      {:id "call-2" :name "bash" :arguments "{}"}])]
        (is (= ["call-1" "call-2"] (mapv :tool-call-id results)))
        (is (= ["call-1" "call-2"] @recorded))
        (is (= #{"call-1" "call-2"} (set @prepared))))
      (finally
        (.shutdown ^ExecutorService executor)))))

(deftest run-tool-calls-bounded-parallelism-test
  (let [executor   (Executors/newFixedThreadPool 2)
        active     (atom 0)
        max-active (atom 0)
        started    (promise)
        release    (promise)]
    (try
      (let [runner (future
                     (batch/run-tool-calls!
                      {:executor executor
                       :run-one! (fn [tool-call _]
                                   {:tool-call-id (:id tool-call)})
                       :execute-prepared! (fn [tool-call _]
                                            (let [n (swap! active inc)]
                                              (swap! max-active max n)
                                              (when (= 2 n) (deliver started true))
                                              (when (= "call-1" (:id tool-call))
                                                @started
                                                @release)
                                              (Thread/sleep 20)
                                              (swap! active dec)
                                              {:tool-call tool-call
                                               :result-message {:tool-call-id (:id tool-call)}}))
                       :record-result! :result-message}
                      [{:id "call-1" :name "read" :arguments "{}"}
                       {:id "call-2" :name "bash" :arguments "{}"}
                       {:id "call-3" :name "write" :arguments "{}"}]))]
        @started
        (deliver release true)
        (let [results @runner]
          (is (= 2 @max-active))
          (is (= ["call-1" "call-2" "call-3"] (mapv :tool-call-id results)))))
      (finally
        (.shutdown ^ExecutorService executor)))))

(ns psi.agent-session.rpc-test
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.rpc :as rpc]))

(defn- run-loop
  [input handler]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (rpc/run-stdio-loop! {:in              (java.io.StringReader. input)
                          :out             out
                          :err             err
                          :request-handler handler})
    {:out-lines (->> (str/split-lines (str out))
                     (remove str/blank?)
                     vec)
     :err-text  (str err)}))

(defn- parse-frames [lines]
  (mapv edn/read-string lines))

(deftest run-stdio-loop-validates-request-envelopes-test
  (testing "returns canonical protocol/transport errors for invalid input frames"
    (let [{:keys [out-lines]}
          (run-loop (str "\n"
                         "{:kind :request :op \"ping\"}\n"
                         "{:id \"1\" :kind :response :op \"ping\"}\n"
                         "{:id \"2\" :kind :request :op \"ping\" :x 1}\n"
                         "not-edn\n")
                    (fn [_ _ _] nil))
          frames (parse-frames out-lines)]
      (is (= 5 (count frames)))
      (is (= ["transport/invalid-frame"
              "protocol/invalid-envelope"
              "protocol/invalid-envelope"
              "protocol/invalid-envelope"
              "protocol/invalid-envelope"]
             (mapv :error-code frames)))
      (is (every? #(= :error (:kind %)) frames)))))

(deftest run-stdio-loop-emits-canonical-response-frame-test
  (testing "writer strips non-canonical keys from response frames"
    (let [{:keys [out-lines]}
          (run-loop "{:id \"42\" :kind :request :op \"ping\"}\n"
                    (fn [request _emit _state]
                      (assoc (rpc/response-frame (:id request) "ping" true {:pong true})
                             :extra "drop-me")))
          frame (-> out-lines parse-frames first)]
      (is (= {:id "42"
              :kind :response
              :op "ping"
              :ok true
              :data {:pong true}}
             frame))
      (is (not (contains? frame :extra))))))

(deftest run-stdio-loop-routes-handler-stdout-to-stderr-test
  (testing "non-protocol output from request handler is redirected to stderr"
    (let [{:keys [out-lines err-text]}
          (run-loop "{:id \"10\" :kind :request :op \"ping\"}\n"
                    (fn [_ _ _]
                      (println "diagnostic line")
                      (rpc/response-frame "10" "ping" true {:pong true})))
          frame (-> out-lines parse-frames first)]
      (is (= :response (:kind frame)))
      (is (str/includes? err-text "diagnostic line"))
      (is (= 1 (count out-lines))))))

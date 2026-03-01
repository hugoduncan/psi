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
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"42\" :kind :request :op \"ping\"}\n")
                    (fn [request _emit _state]
                      (assoc (rpc/response-frame (:id request) (:op request) true {:pong true})
                             :extra "drop-me")))
          frame (-> out-lines parse-frames second)]
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
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"10\" :kind :request :op \"ping\"}\n")
                    (fn [request _ _]
                      (println "diagnostic line")
                      (rpc/response-frame (:id request) (:op request) true {:pong true})))
          frame (-> out-lines parse-frames second)]
      (is (= :response (:kind frame)))
      (is (str/includes? err-text "diagnostic line"))
      (is (= 2 (count out-lines))))))

(deftest run-stdio-loop-enforces-handshake-gate-test
  (testing "non-handshake requests are rejected before ready"
    (let [{:keys [out-lines]}
          (run-loop "{:id \"1\" :kind :request :op \"ping\"}\n"
                    (fn [_ _ _]
                      (rpc/response-frame "1" "ping" true {:pong true})))
          frame (-> out-lines parse-frames first)]
      (is (= :error (:kind frame)))
      (is (= "transport/not-ready" (:error-code frame)))
      (is (= "1" (:id frame)))
      (is (= "ping" (:op frame))))))

(deftest run-stdio-loop-handshake-compatibility-test
  (testing "unsupported major protocol is rejected and transport remains not-ready"
    (let [{:keys [out-lines]}
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"2.0\"}}}\n"
                         "{:id \"p1\" :kind :request :op \"ping\"}\n")
                    (fn [_ _ _]
                      (rpc/response-frame "p1" "ping" true {:pong true})))
          [h p] (parse-frames out-lines)]
      (is (= :error (:kind h)))
      (is (= "protocol/unsupported-version" (:error-code h)))
      (is (= :error (:kind p)))
      (is (= "transport/not-ready" (:error-code p)))))

  (testing "supported major protocol sets ready and allows non-handshake ops"
    (let [{:keys [out-lines]}
          (run-loop (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                         "{:id \"p1\" :kind :request :op \"ping\"}\n")
                    (fn [request _ _]
                      (rpc/response-frame (:id request) (:op request) true {:pong true})))
          [h p] (parse-frames out-lines)]
      (is (= :response (:kind h)))
      (is (= "handshake" (:op h)))
      (is (= :response (:kind p)))
      (is (= "ping" (:op p))))))

(deftest run-stdio-loop-pending-lifecycle-test
  (testing "accepted request adds pending and terminal response clears it"
    (let [state (atom {:max-pending-requests 2})
          out   (java.io.StringWriter.)
          err   (java.io.StringWriter.)]
      (rpc/run-stdio-loop!
       {:in (java.io.StringReader.
             (str "{:id \"h\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                  "{:id \"r1\" :kind :request :op \"echo\"}\n"))
        :out out
        :err err
        :state state
        :request-handler
        (fn [request _emit _state]
          (if (= "echo" (:op request))
            (rpc/response-frame (:id request) "echo" true {:ok true})
            (rpc/response-frame (:id request) (:op request) true {})))})
      (is (= {} (:pending @state)))
      (is (= true (:ready? @state)))))

  (testing "max pending guard returns canonical error"
    (let [state (atom {:max-pending-requests 1 :ready? true :pending {"existing" "op"}})
          out   (java.io.StringWriter.)
          err   (java.io.StringWriter.)]
      (rpc/run-stdio-loop!
       {:in (java.io.StringReader. "{:id \"r2\" :kind :request :op \"echo\"}\n")
        :out out
        :err err
        :state state
        :request-handler (fn [_ _ _] nil)})
      (let [frame (-> out str str/split-lines first edn/read-string)]
        (is (= :error (:kind frame)))
        (is (= "transport/max-pending-exceeded" (:error-code frame)))
        (is (= "r2" (:id frame))))))

  (testing "duplicate request id is rejected with request/invalid-id"
    (let [state (atom {:ready? true :pending {"dup" "existing-op"}})
          out   (java.io.StringWriter.)
          err   (java.io.StringWriter.)]
      (rpc/run-stdio-loop!
       {:in (java.io.StringReader. "{:id \"dup\" :kind :request :op \"echo\"}\n")
        :out out
        :err err
        :state state
        :request-handler (fn [_ _ _] nil)})
      (let [frame (-> out str str/split-lines first edn/read-string)]
        (is (= :error (:kind frame)))
        (is (= "request/invalid-id" (:error-code frame)))
        (is (= "dup" (:id frame)))))))

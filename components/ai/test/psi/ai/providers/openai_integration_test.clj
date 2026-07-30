(ns ^:integration psi.ai.providers.openai-integration-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.openai :as openai])
  (:import [java.util Base64]))

(defn- integration-model
  []
  (let [env-key (some-> (System/getenv "OPENAI_INTEGRATION_MODEL_KEY") str/trim not-empty)]
    (or (some-> env-key keyword models/get-model)
        (models/get-model :gpt-4o)
        (models/get-model :gpt-5-nano))))

(defn- integration-codex-model
  []
  (let [env-key (some-> (System/getenv "OPENAI_CODEX_INTEGRATION_MODEL_KEY") str/trim not-empty)]
    (or (some-> env-key keyword models/get-model)
        (models/get-model :gpt-5.3-codex)
        (models/get-model :gpt-5-codex))))

(defn- pad-base64url
  [s]
  (let [m (mod (count s) 4)]
    (case m
      0 s
      2 (str s "==")
      3 (str s "=")
      1 (str s "===")
      s)))

(defn- chatgpt-account-id-from-token
  [token]
  (try
    (let [[_ payload _] (str/split (or token "") #"\\." 3)
          decoded       (String. (.decode (Base64/getUrlDecoder)
                                          (pad-base64url payload))
                                 "UTF-8")
          claims        (json/parse-string decoded false)]
      (get-in claims ["https://api.openai.com/auth" "chatgpt_account_id"]))
    (catch Throwable _
      nil)))

(defn- event-index
  [events pred]
  (first
   (keep-indexed (fn [i ev]
                   (when (pred ev) i))
                 events)))

(defn- tool-args-json
  [events content-index]
  (->> events
       (filter #(and (= :toolcall-delta (:type %))
                     (= content-index (:content-index %))))
       (map :delta)
       (apply str)))

(deftest ^:integration live-openai-chat-toolcall-flow-test
  (testing "live OpenAI streaming emits coherent tool-call lifecycle"
    (let [api-key (some-> (System/getenv "OPENAI_API_KEY") str/trim)
          model   (integration-model)]
      (if (or (str/blank? api-key) (nil? model))
        (is true "Skipping integration test: OPENAI_API_KEY or model unavailable")
        (let [conversation (-> (conv/create "You are a deterministic tool caller. Use tools when instructed.")
                               (conv/add-user-message
                                "Call echo_tool exactly once with JSON arguments {\"value\":\"integration-test\"}. Return no normal text.")
                               (conv/add-tool
                                {:name "echo_tool"
                                 :description "Echo a value."
                                 :parameters {:type "object"
                                              :properties {:value {:type "string"}}
                                              :required ["value"]
                                              :additionalProperties false}}))
              events       (atom [])
              run-future   (future
                             ((:stream openai/provider)
                              conversation
                              model
                              {:api-key api-key
                               :temperature 0
                               :max-tokens 256}
                              (fn [event]
                                (swap! events conj event))))
              completed?   (not= ::timeout (deref run-future 120000 ::timeout))]
          (when-not completed?
            (future-cancel run-future))
          (is completed? "OpenAI stream did not complete within timeout")

          (let [evs            @events
                stream-start-i (event-index evs #(= :start (:type %)))
                error-event    (first (filter #(= :error (:type %)) evs))
                tool-start     (first (filter #(= :toolcall-start (:type %)) evs))
                done-event     (last (filter #(= :done (:type %)) evs))]
            (is (number? stream-start-i) "expected :start event")
            (is (nil? error-event)
                (str "unexpected provider error: " (some-> error-event :error-message)))
            (is (some? tool-start) "expected at least one :toolcall-start event")
            (is (some? done-event) "expected terminal :done event")
            (is (= :tool_calls (:reason done-event))
                (str "expected :done reason :tool_calls, got " (:reason done-event)))

            (when tool-start
              (let [idx          (:content-index tool-start)
                    tool-end-i   (event-index evs #(and (= :toolcall-end (:type %))
                                                        (= idx (:content-index %))))
                    done-i       (event-index evs #(= :done (:type %)))
                    args-json    (tool-args-json evs idx)
                    parsed-args  (try
                                   (json/parse-string args-json true)
                                   (catch Throwable _ nil))]
                (is (= "echo_tool" (:name tool-start))
                    (str "expected tool name echo_tool, got " (:name tool-start)))
                (is (number? tool-end-i) "expected :toolcall-end for started tool")
                (is (and (number? done-i)
                         (< (or tool-end-i Integer/MAX_VALUE) done-i))
                    "expected toolcall-end before done")
                (is (seq args-json) "expected non-empty toolcall args JSON")
                (is (map? parsed-args)
                    (str "toolcall args were not valid JSON: " args-json))
                (is (= "integration-test" (:value parsed-args))
                    (str "expected parsed args {:value \"integration-test\"}, got " parsed-args))))))))))

(deftest ^:integration live-openai-codex-toolcall-flow-test
  (testing "live OpenAI Codex streaming emits coherent tool-call lifecycle"
    (let [api-key    (or (some-> (System/getenv "OPENAI_CODEX_API_KEY") str/trim not-empty)
                         (some-> (System/getenv "OPENAI_API_KEY") str/trim not-empty))
          account-id (chatgpt-account-id-from-token api-key)
          model      (integration-codex-model)]
      (if (or (str/blank? api-key) (str/blank? account-id) (nil? model))
        (is true "Skipping codex integration test: OPENAI_CODEX_API_KEY/OPENAI_API_KEY with chatgpt_account_id and codex model required")
        (let [conversation (-> (conv/create "You are a deterministic tool caller. Use tools when instructed.")
                               (conv/add-user-message
                                "Call echo_tool exactly once with JSON arguments {\"value\":\"integration-test-codex\"}. Return no normal text.")
                               (conv/add-tool
                                {:name "echo_tool"
                                 :description "Echo a value."
                                 :parameters {:type "object"
                                              :properties {:value {:type "string"}}
                                              :required ["value"]
                                              :additionalProperties false}}))
              events       (atom [])
              run-future   (future
                             ((:stream openai/provider)
                              conversation
                              model
                              {:api-key api-key
                               :max-tokens 256}
                              (fn [event]
                                (swap! events conj event))))
              completed?   (not= ::timeout (deref run-future 120000 ::timeout))]
          (when-not completed?
            (future-cancel run-future))
          (is completed? "OpenAI Codex stream did not complete within timeout")

          (let [evs            @events
                stream-start-i (event-index evs #(= :start (:type %)))
                error-event    (first (filter #(= :error (:type %)) evs))
                tool-start     (first (filter #(= :toolcall-start (:type %)) evs))
                done-event     (last (filter #(= :done (:type %)) evs))]
            (is (number? stream-start-i) "expected :start event")
            (is (nil? error-event)
                (str "unexpected provider error: " (some-> error-event :error-message)))
            (is (some? tool-start) "expected at least one :toolcall-start event")
            (is (some? done-event) "expected terminal :done event")

            (when tool-start
              (let [idx         (:content-index tool-start)
                    tool-end-i  (event-index evs #(and (= :toolcall-end (:type %))
                                                       (= idx (:content-index %))))
                    done-i      (event-index evs #(= :done (:type %)))
                    args-json   (tool-args-json evs idx)
                    parsed-args (try
                                  (json/parse-string args-json true)
                                  (catch Throwable _ nil))]
                (is (= "echo_tool" (:name tool-start))
                    (str "expected tool name echo_tool, got " (:name tool-start)))
                (is (number? tool-end-i) "expected :toolcall-end for started tool")
                (is (and (number? done-i)
                         (< (or tool-end-i Integer/MAX_VALUE) done-i))
                    "expected toolcall-end before done")
                (is (seq args-json) "expected non-empty toolcall args JSON")
                (is (map? parsed-args)
                    (str "toolcall args were not valid JSON: " args-json))
                (is (= "integration-test-codex" (:value parsed-args))
                    (str "expected parsed args {:value \"integration-test-codex\"}, got " parsed-args))))))))))

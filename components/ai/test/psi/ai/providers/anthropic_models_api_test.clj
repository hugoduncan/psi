(ns psi.ai.providers.anthropic-models-api-test
  (:require
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private models-url "https://api.anthropic.com/v1/models")
(def ^:private target-model-id "claude-opus-4-8")
(def ^:private anthropic-version "2023-06-01")

(defn- enabled?
  []
  (= "1" (System/getenv "PSI_LIVE_ANTHROPIC_MODELS_API")))

(defn- api-key
  []
  (System/getenv "ANTHROPIC_API_KEY"))

(defn- api-key-present?
  []
  (not (str/blank? (api-key))))

(defn- request-options
  []
  {:headers {"x-api-key" (api-key)
             "anthropic-version" anthropic-version}
   :as :json})

(defmacro ^:private with-live-models-api
  [& body]
  `(cond
     (not (enabled?))
     (testing "skipped: missing PSI_LIVE_ANTHROPIC_MODELS_API=1"
       (is true))

     (not (api-key-present?))
     (testing "skipped: missing ANTHROPIC_API_KEY"
       (is true))

     :else
     (do ~@body)))

(deftest ^:integration live-anthropic-models-list-includes-opus-4-8-test
  ;; Opt-in live proof for Anthropic Models API availability of Opus 4.8.
  (with-live-models-api
    (let [response (http/get models-url (request-options))
          ids      (set (map :id (:data (:body response))))]
      (is (= 200 (:status response)))
      (is (contains? ids target-model-id)))))

(deftest ^:integration live-anthropic-models-retrieve-opus-4-8-test
  ;; Opt-in live proof for the canonical Opus 4.8 model id.
  (with-live-models-api
    (let [response (http/get (str models-url "/" target-model-id)
                             (request-options))]
      (is (= 200 (:status response)))
      (is (= target-model-id (get-in response [:body :id]))))))

(ns psi.ai.providers.anthropic.error
  "Anthropic provider error response normalization."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [psi.ai.providers.anthropic.request-support :as request-support])
  (:import [java.io InputStream]))

(defn- body->text
  [body]
  (try
    (cond
      ;; Explicit nil → nil (not "") so callers can use (seq body-text) to
      ;; distinguish "no body" from "empty body string".
      (nil? body) nil
      (string? body) body
      (instance? InputStream body) (slurp (io/reader body))
      :else (str body))
    (catch Exception _ nil)))

(defn- parse-json-text-safe
  [text]
  (when (seq text)
    (try
      (json/parse-string text true)
      (catch Exception _
        nil))))

(defn- parsed-error-message
  [parsed-body]
  (or (get-in parsed-body [:error :message])
      (get-in parsed-body [:message])))

(defn- request-id-from-headers
  [headers]
  (or (get headers "request-id")
      (get headers "Request-Id")
      (get headers "x-request-id")
      (get headers "X-Request-Id")
      (get headers "X-Request-ID")))

(defn- meaningful-error-message?
  [s]
  (and (string? s)
       (not (str/blank? s))
       (not (contains? #{"Error" "error" "Exception"} s))))

(defn- fallback-status-message
  [status]
  (case status
    400 "Anthropic rejected the request"
    401 "Anthropic authentication failed"
    403 "Anthropic authorization failed"
    404 "Anthropic endpoint not found"
    429 "Anthropic rate limit exceeded"
    500 "Anthropic server error"
    502 "Anthropic gateway error"
    503 "Anthropic service unavailable"
    "Anthropic request failed"))

(defn oauth-auth-request?
  "True only for the complete Claude Code OAuth request shape.

   A bare Authorization bearer header can be custom-provider auth; it must
   retain compatibility fallback handling and must not be diagnosed as OAuth."
  [request]
  (let [headers (or (:headers request) {})
        auth    (or (get headers "Authorization")
                    (get headers "authorization"))]
    (and (string? auth)
         (str/starts-with? auth "Bearer ")
         (= "cli" (get headers "x-app"))
         (str/starts-with? (or (get headers "user-agent") "") "claude-cli/"))))

(defn- request-diagnostic-hint
  [request]
  (when (map? request)
    (let [parsed         (request-support/parse-json-body-safe (:body request))
          model-id       (when (map? parsed) (:model parsed))
          beta           (get-in request [:headers "anthropic-beta"])
          message-count  (when (map? parsed) (count (or (:messages parsed) [])))
          tool-count     (when (map? parsed) (count (or (:tools parsed) [])))
          parts          (cond-> []
                           (string? model-id)      (conj (str "model=" model-id))
                           (string? beta)          (conj (str "anthropic-beta=" beta))
                           (number? message-count) (conj (str "messages=" message-count))
                           (number? tool-count)    (conj (str "tools=" tool-count))
                           (oauth-auth-request? request) (conj "auth=oauth"))]
      (when (seq parts)
        (str " request{" (str/join ", " parts) "}")))))

(defn- augment-400-message
  "Append diagnostic context to a generic 400 base-msg when Anthropic returns
   no actionable error detail. Only applied when base-msg is the fallback
   'Anthropic rejected the request' string."
  [base-msg body-text oauth? request]
  (if (= base-msg "Anthropic rejected the request")
    (str base-msg
         " ("
         (if (str/blank? body-text)
           "no error body returned"
           "provider response omitted actionable details")
         "; possible causes: model access, unsupported beta header, or invalid request payload"
         (when oauth?
           "; oauth token in use")
         ")"
         (or (request-diagnostic-hint request) ""))
    base-msg))

(defn- base-error-message
  [{:keys [status fallback-message parsed-body]}]
  (or (when-let [parsed-msg (some-> parsed-body parsed-error-message)]
        (when (meaningful-error-message? parsed-msg)
          parsed-msg))
      (when (meaningful-error-message? fallback-message)
        fallback-message)
      (fallback-status-message status)))

(defn- error-message
  [{:keys [status headers body-text fallback-message request parsed-body]}]
  (let [base-msg (base-error-message {:status status
                                      :body-text body-text
                                      :fallback-message fallback-message
                                      :parsed-body parsed-body})
        base-msg (cond-> base-msg
                   (= 400 status) (augment-400-message body-text
                                                       (oauth-auth-request? request)
                                                       request))]
    (str base-msg
         (when status
           (str " (status " status ")"))
         (when-let [req-id (request-id-from-headers headers)]
           (str " [request-id " req-id "]")))))

(defn error-from-response-data
  [{:keys [status headers body-text fallback-message request]}]
  (let [parsed-body (parse-json-text-safe body-text)]
    (cond-> {:type :error
             :error-message (error-message {:status status
                                            :headers headers
                                            :body-text body-text
                                            :fallback-message fallback-message
                                            :request request
                                            :parsed-body parsed-body})
             :headers headers}
      status          (assoc :http-status status)
      (seq body-text) (assoc :body-text body-text)
      parsed-body     (assoc :body parsed-body))))

(defn error-context
  ([body headers]
   {:headers headers
    :body-text (body->text body)})
  ([body headers request]
   (assoc (error-context body headers)
          :request request)))

(defn exception->error
  [e]
  (let [data (ex-data e)]
    (error-from-response-data
     (merge (error-context (:body data) (:headers data))
            {:status (:status data)
             :fallback-message (or (ex-message e) (str e))}))))

(defn response->error
  [response request]
  (error-from-response-data
   (merge (error-context (:body response) (:headers response) request)
          {:status (:status response)})))

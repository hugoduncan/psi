(ns psi.ai.providers.openai.transport
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [psi.ai.providers.http-boundary :as http-boundary]
            [psi.ai.providers.request-support :as request-support]
            [psi.ai.proxy :as proxy]))

(defn safe-call!
  [f payload]
  (when (fn? f)
    (try
      (f payload)
      (catch Exception _
        nil))))

(defn stream-response
  [options url request]
  (http-boundary/post! (http-boundary/boundary options)
                       url
                       (merge request
                              (proxy/request-proxy-options url)
                              {:as :stream :cookie-policy :none :throw-exceptions false})))

(defn execute-response
  [options url request]
  (http-boundary/post! (http-boundary/boundary options)
                       url
                       (merge request
                              (proxy/request-proxy-options url)
                              {:as :text :cookie-policy :none :throw-exceptions false})))

(defn redact-request-headers
  [headers]
  (request-support/redact-headers
   headers
   [["Authorization" request-support/redact-authorization]
    ["chatgpt-account-id" request-support/mask-chatgpt-account-id]
    ["x-api-key" request-support/redact-secret]]))

(defn parse-json-body-safe
  [body]
  (try
    (json/parse-string (str (or body "")) true)
    (catch Exception _
      (str (or body "")))))

(defn capture-provider-id
  [model]
  (or (:provider model) :openai))

(defn capture-request!
  [model options api url request]
  (safe-call! (:on-provider-request options)
              {:provider (capture-provider-id model)
               :api api
               :url url
               :request {:headers (redact-request-headers (:headers request))
                         :body (parse-json-body-safe (:body request))}}))

(defn capture-response!
  [model options api url event]
  (safe-call! (:on-provider-response options)
              {:provider (capture-provider-id model)
               :api api
               :url url
               :event event}))

(defn parse-sse-line
  "Parse a Server-Sent Events `data:` line; returns nil for non-data lines."
  [line]
  (when (and line (.startsWith ^String line "data: "))
    (let [data (.substring ^String line 6)]
      (when (not= data "[DONE]")
        (try (json/parse-string data true)
             (catch Exception _ nil))))))

(defn body->text
  [body]
  (try
    (cond
      (nil? body) nil
      (string? body) body
      (instance? java.io.InputStream body) (slurp body)
      :else (str body))
    (catch Exception _ nil)))

(defn parse-json-text-safe
  [text]
  (when (seq text)
    (try
      (json/parse-string text true)
      (catch Exception _
        nil))))

(defn parsed-error-message
  [parsed-body]
  (or (get-in parsed-body [:error :message])
      (get-in parsed-body [:message])))

(defn parse-error-message
  [body-text]
  (when (seq body-text)
    (or (some-> body-text
                parse-json-text-safe
                parsed-error-message)
        body-text)))

(defn request-id-from-headers
  [headers]
  (or (get headers "x-request-id")
      (get headers "x-oai-request-id")
      (get headers "x-openai-request-id")
      (get headers "X-Request-ID")
      (get headers "X-OAI-Request-ID")
      (get headers "X-OpenAI-Request-ID")))

(defn meaningful-error-message?
  [s]
  (and (string? s)
       (not (str/blank? s))
       (not (contains? #{"Error" "error" "Exception"} s))))

(defn fallback-status-message
  [status]
  (case status
    400 "OpenAI rejected the request"
    401 "OpenAI authentication failed"
    403 "OpenAI authorization failed"
    404 "OpenAI endpoint not found"
    429 "OpenAI rate limit exceeded"
    500 "OpenAI server error"
    502 "OpenAI gateway error"
    503 "OpenAI service unavailable"
    "OpenAI request failed"))

(defn base-error-message
  [{:keys [status fallback-message parsed-body]}]
  (or (when-let [parsed-msg (some-> parsed-body parsed-error-message)]
        (when (meaningful-error-message? parsed-msg)
          parsed-msg))
      (when (meaningful-error-message? fallback-message)
        fallback-message)
      (fallback-status-message status)))

(defn error-message
  [{:keys [status headers fallback-message parsed-body]}]
  (str (base-error-message {:status status
                            :fallback-message fallback-message
                            :parsed-body parsed-body})
       (when status
         (str " (status " status ")"))
       (when-let [req-id (request-id-from-headers headers)]
         (str " [request-id " req-id "]"))))

(defn error-from-response-data
  [{:keys [status headers body-text fallback-message]}]
  (let [parsed-body (parse-json-text-safe body-text)]
    (cond-> {:type :error
             :error-message (error-message {:status status
                                            :headers headers
                                            :fallback-message fallback-message
                                            :parsed-body parsed-body})}
      status          (assoc :http-status status)
      headers         (assoc :headers headers)
      (seq body-text) (assoc :body-text body-text)
      parsed-body     (assoc :body parsed-body))))

(defn error-status?
  [status]
  (and (number? status)
       (>= status 400)))

(defn response->error
  [response]
  (error-from-response-data {:status (:status response)
                             :headers (:headers response)
                             :body-text (body->text (:body response))}))

(defn emit-error!
  [model options api url consume-fn err]
  (capture-response! model options api url err)
  (consume-fn err))

(defn exception->error
  [e]
  (let [data (ex-data e)]
    (error-from-response-data {:status (:status data)
                               :headers (:headers data)
                               :body-text (body->text (:body data))
                               :fallback-message (or (ex-message e) (str e))})))

(ns psi.ai.providers.anthropic.capture
  "Anthropic provider request/response capture and stream plumbing helpers.

  Request/response capture emits :on-provider-request / :on-provider-response
  observability callbacks with redacted headers; the stream plumbing wraps
  the HTTP post and the terminal error/status handling shared by the
  streaming and non-streaming paths."
  (:require [psi.ai.proxy :as proxy]
            [psi.ai.providers.anthropic.error :as anthropic-error]
            [psi.ai.providers.anthropic.request-support :as anthropic-request-support]
            [psi.ai.providers.http-boundary :as http-boundary]
            [psi.ai.providers.request-support :as request-support]))

(defn safe-call!
  [f payload]
  (when (fn? f)
    (try
      (f payload)
      (catch Exception _
        nil))))

(defn redact-request-headers
  [headers]
  (request-support/redact-headers
   headers
   [["Authorization" request-support/redact-authorization]
    ["x-api-key" request-support/redact-secret]]))

(defn capture-provider-id
  [model]
  (or (:provider model) :anthropic))

(defn capture-request!
  [model options url request]
  (safe-call! (:on-provider-request options)
              {:provider (capture-provider-id model)
               :api :anthropic-messages
               :url url
               :request {:headers (redact-request-headers (:headers request))
                         :body (anthropic-request-support/parse-json-body-safe (:body request))}}))

(defn capture-response!
  [model options url event]
  (safe-call! (:on-provider-response options)
              {:provider (capture-provider-id model)
               :api :anthropic-messages
               :url url
               :event event}))

(defn stream-response
  [options url request]
  (http-boundary/post! (http-boundary/boundary options)
                       url
                       (merge request
                              (proxy/request-proxy-options url)
                              {:as :stream :throw-exceptions false})))

(defn error-status?
  [status]
  (and (number? status)
       (>= status 400)))

(defn emit-error!
  [model options url consume-fn err]
  (capture-response! model options url err)
  (consume-fn err))

(defn emit-start!
  "Emit :start exactly once before the first content or terminal event.
   This keeps malformed, truncated, error-first, and first-read-failure streams
   on the same event contract as well-formed streams."
  [consume-fn started?]
  (request-support/emit-start! consume-fn started?))

(defn consume-retry-response!
  [model options url emit-terminal-error! consume-stream-response! retry-request]
  (capture-request! model options url retry-request)
  (let [retry-response (stream-response options url retry-request)
        retry-status   (:status retry-response)]
    (if (error-status? retry-status)
      (emit-terminal-error!
       (anthropic-error/response->error retry-response retry-request))
      (consume-stream-response! retry-response))))

(defn handle-400-response!
  [{:keys [prompt-caching-beta interleaved-thinking-beta oauth-auth-request?]}
   model options url request response emit-terminal-error! consume-stream-response!]
  (if-let [fallback (anthropic-request-support/fallback-request-for-400
                     request
                     {:prompt-caching-beta prompt-caching-beta
                      :interleaved-thinking-beta interleaved-thinking-beta
                      :oauth-auth-request? oauth-auth-request?})]
    (let [first-error (anthropic-error/response->error response request)]
      (capture-response! model options url (assoc first-error
                                                  :retrying-with-compatibility-fallback true
                                                  :retry-fallback-steps (:steps fallback)))
      (consume-retry-response! model options
                               url
                               emit-terminal-error!
                               consume-stream-response!
                               (:request fallback)))
    (emit-terminal-error!
     (anthropic-error/response->error response request))))

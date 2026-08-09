(ns psi.ai.providers.anthropic.capture
  "Anthropic provider request/response capture and stream plumbing helpers.

  Request/response capture emits :on-provider-request / :on-provider-response
  observability callbacks with redacted headers; the stream plumbing wraps
  the HTTP post and the terminal error/status handling shared by the
  streaming and non-streaming paths."
  (:require [clj-http.client :as http]
            [psi.ai.proxy :as proxy]
            [psi.ai.providers.anthropic.error :as anthropic-error]
            [psi.ai.providers.anthropic.request-support :as anthropic-request-support]
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
  [url request]
  (http/post url (merge request
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
  "Emit :start exactly once, before the terminal, when the stream never
   emitted it (the stream's first event is a terminal/error rather than
   message_start — a malformed/truncated stream, an error-first stream, or a
   stream-read exception before any output). Review 50: stream-anthropic had
   no started? tracking — :start was emitted only inside the message_start
   case branch, so the terminal emitters emitted :done/:error with no
   preceding :start when the stream never received message_start — the only
   three-transport asymmetry left in the review-48 EOF-level flush (both
   sibling transports emit :start first when not started:
   emit-chat-completion-finish!'s stream-started? compare-and-set and the
   codex EOF flush's emit-codex-start!). Review 53: the outer catch block (a
   stream-read exception before any output) also emits :start first — the
   last :start-before-terminal gap on this transport. Review 54: the
   content-block branches (content_block_start/delta/stop) also emit :start
   before the first content event (the non-terminal half of the review-50
   class). Benign for the consumer (:start is a no-op handler; the turn
   statechart is already past :idle via the turn-level :turn/start) but
   removes the cross-transport asymmetries in the event-emission class this
   task has repeatedly treated as actionable. The once-semantics live in the
   shared `request-support/emit-start!` (review 54 extracted the three
   byte-identical per-transport copies); this private wrapper keeps the
   transport-local name at the call sites."
  [consume-fn started?]
  (request-support/emit-start! consume-fn started?))

(defn consume-retry-response!
  [model options url consume-fn consume-stream-response! retry-request]
  (capture-request! model options url retry-request)
  (let [retry-response (stream-response url retry-request)
        retry-status   (:status retry-response)]
    (if (error-status? retry-status)
      (emit-error! model options url consume-fn
                   (anthropic-error/response->error retry-response retry-request))
      (consume-stream-response! retry-response))))

(defn handle-400-response!
  [{:keys [prompt-caching-beta interleaved-thinking-beta oauth-auth-request?]}
   model options url request response consume-fn consume-stream-response!]
  (if-let [fallback (anthropic-request-support/fallback-request-for-400
                     request
                     {:prompt-caching-beta prompt-caching-beta
                      :interleaved-thinking-beta interleaved-thinking-beta
                      ;; Review 22: the :without-all-betas decision uses the
                      ;; transport's COMPUTED oauth? boolean (threaded from
                      ;; build-request via ::oauth?), NOT the header
                      ;; content-sniff — a keyless custom provider whose
                      ;; custom :headers reproduce the Claude Code CLI marker
                      ;; set (Authorization Bearer + user-agent: claude-cli/…
                      ;; + x-app: cli) is still not OAuth and must get
                      ;; :without-all-betas on a beta-related 400. The
                      ;; content-sniffing oauth-auth-request? predicate is
                      ;; kept for error diagnostics only.
                      ;; Review 55: oauth-auth-request? is passed in from the
                      ;; psi.ai.providers.anthropic wrapper (where build-request
                      ;; attaches ::oauth?) — the fn body must close over the
                      ;; namespace where the ::oauth? keyword is defined, not
                      ;; capture's, or the lookup always misses (a namespaced-
                      ;; keyword namespace drift regression found by the full
                      ;; suite: the retried OAuth request stripped ALL betas).
                      :oauth-auth-request? oauth-auth-request?})]
    (let [first-error (anthropic-error/response->error response request)]
      (capture-response! model options url (assoc first-error
                                                  :retrying-with-compatibility-fallback true
                                                  :retry-fallback-steps (:steps fallback)))
      (consume-retry-response! model options
                               url
                               consume-fn
                               consume-stream-response!
                               (:request fallback)))
    (emit-error! model options url consume-fn
                 (anthropic-error/response->error response request))))

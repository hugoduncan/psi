(ns psi.ai.providers.anthropic-retry-test
  "HTTP-400 compatibility-retry tests for the :anthropic-messages transport
   (fallback-request-steps-for-400 / fallback-request-for-400 /
   handle-400-response! in providers/anthropic.clj and
   providers/anthropic/request_support.clj). Extracted from
   anthropic_stream_test.clj (review 22) to keep both files under the repo
   file-length gate."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [psi.ai.conversation :as conv]
   [psi.ai.models :as models]
   [psi.ai.providers.anthropic :as anthropic]
   [psi.ai.providers.anthropic.error :as anthropic-error])
  (:import [java.io ByteArrayInputStream]))

(defn- sse-line [event-type data-map]
  (str "event: " event-type "\ndata: " (json/generate-string data-map) "\n\n"))

(defn- stream-body [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(deftest stream-anthropic-retries-without-prompt-caching-on-400-test
  (testing "400 with prompt-caching enabled retries once without cache directives"
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create {:system-prompt "sys"
                                   :system-prompt-blocks [{:kind :text
                                                           :text "sys"
                                                           :cache-control {:type :ephemeral}}]})
                     (conv/add-user-message "hello"))
          calls  (atom [])
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [http/post (fn [_url req]
                                (swap! calls conj req)
                                (if (= 1 (count @calls))
                                  {:status 400
                                   :headers {"request-id" "req_ant_first"}
                                   :body nil}
                                  {:status 200
                                   :headers {}
                                   :body (stream-body sse)}))]
        (anthropic/stream-anthropic convo model {:api-key "test-key"}
                                    (fn [e] (swap! events conj e))))
      (is (= 2 (count @calls)))
      (is (re-find #"prompt-caching"
                   (or (get-in (first @calls) [:headers "anthropic-beta"]) "")))
      (is (not (re-find #"prompt-caching"
                        (or (get-in (second @calls) [:headers "anthropic-beta"]) ""))))
      (is (not (re-find #"cache_control"
                        (or (:body (second @calls)) ""))))
      (is (= "sys"
             (:system (json/parse-string (:body (second @calls)) true)))
          "after prompt-caching fallback, system is collapsed to plain string")
      (is (some #(= :start (:type %)) @events))
      (is (some #(= :done (:type %)) @events))
      (is (not-any? #(= :error (:type %)) @events)))))

(deftest stream-anthropic-retries-without-thinking-on-400-test
  (testing "oauth + thinking request retries once with compatibility fallbacks on 400"
    (let [model  (models/get-model :sonnet-4.6)
          convo  (-> (conv/create "sys")
                     (conv/add-user-message "hello"))
          calls  (atom [])
          events (atom [])
          sse    (str (sse-line "message_start" {:type "message_start"})
                      (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [http/post (fn [_url req]
                                (swap! calls conj req)
                                (if (= 1 (count @calls))
                                  {:status 400
                                   :headers {"request-id" "req_ant_first"}
                                   :body (stream-body
                                          (json/generate-string
                                           {:error {:message "Anthropic rejected the request"}}))}
                                  {:status 200
                                   :headers {}
                                   :body (stream-body sse)}))]
        (anthropic/stream-anthropic convo model {:api-key "sk-ant-oat-test-token"
                                                 :thinking-level :medium}
                                    (fn [e] (swap! events conj e))))
      (is (= 2 (count @calls)))
      (let [first-betas  (or (get-in (first @calls) [:headers "anthropic-beta"]) "")
            second-betas (or (get-in (second @calls) [:headers "anthropic-beta"]) "")
            second-body  (json/parse-string (:body (second @calls)) true)]
        (is (re-find #"claude-code" first-betas))
        (is (re-find #"interleaved-thinking" first-betas))
        (is (re-find #"context-management" first-betas))
        (is (re-find #"prompt-caching-scope-2026-01-05" first-betas)
            "scope beta should be present for oauth")
        (is (re-find #"oauth-2025-04-20" second-betas)
            "oauth beta must be preserved")
        (is (re-find #"claude-code" second-betas)
            "claude-code beta should remain for oauth compatibility")
        (is (re-find #"context-management" second-betas)
            "context-management beta should remain for oauth compatibility")
        (is (re-find #"prompt-caching-scope-2026-01-05" second-betas)
            "scope beta should remain for oauth compatibility")
        (is (not (re-find #"interleaved-thinking" second-betas)))
        (is (nil? (:thinking second-body))))
      (is (some #(= :start (:type %)) @events))
      (is (some #(= :done (:type %)) @events))
      (is (not-any? #(= :error (:type %)) @events)))))

(deftest stream-anthropic-retries-adaptive-shape-without-thinking-on-400-test
  ;; Review 13: the HTTP-400 compatibility retry's :without-thinking step
  ;; strips BOTH :thinking and :output_config from the retried body (verified
  ;; against request_support.clj request-transform). For an adaptive-shape
  ;; DeepSeek request (thinking.type "adaptive" + output_config.effort) a
  ;; strict endpoint that rejects the unverified "adaptive" type does NOT
  ;; hard-fail on the streaming path — the retry succeeds with thinking
  ;; omitted, which DeepSeek treats as thinking ON (server default) at default
  ;; effort, silently dropping the user's effort setting. The non-streaming
  ;; execute-anthropic path has no 400 fallback and hard-fails on the same
  ;; request (streaming/non-streaming asymmetry).
  (testing "adaptive-shape request retries once on 400 with :thinking and :output_config stripped"
    (let [model    {:id                 "deepseek-v4-flash"
                    :name               "DeepSeek V4 Flash"
                    :provider           :deepseek
                    :custom?            true
                    :api                :anthropic-messages
                    :base-url           "https://api.deepseek.com/anthropic"
                    :supports-reasoning true
                    :adaptive-thinking  true
                    :supports-images    false
                    :supports-text      true
                    :context-window     1000000
                    :max-tokens         384000
                    :input-cost         0.14
                    :output-cost        0.28
                    :cache-read-cost    0.0028
                    :cache-write-cost   0.14
                    :locality           :cloud
                    :latency-tier       :low
                    :cost-tier          :low}
          convo    (-> (conv/create "sys")
                       (conv/add-user-message "hello"))
          calls    (atom [])
          captures (atom [])
          events   (atom [])
          sse      (str (sse-line "message_start" {:type "message_start"})
                        (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [http/post (fn [_url req]
                                (swap! calls conj req)
                                (if (= 1 (count @calls))
                                  {:status 400
                                   :headers {"request-id" "req_ant_first"}
                                   :body (stream-body
                                          (json/generate-string
                                           {:error {:message "Anthropic rejected the request"}}))}
                                  {:status 200
                                   :headers {}
                                   :body (stream-body sse)}))]
        (anthropic/stream-anthropic
         convo model {:api-key "test-key"
                      :thinking-level :high
                      :on-provider-response #(swap! captures conj %)}
         (fn [e] (swap! events conj e))))
      (is (= 2 (count @calls))
          "adaptive-shape 400 must retry once")
      (let [first-body  (json/parse-string (:body (first @calls)) true)
            second-body (json/parse-string (:body (second @calls)) true)]
        (is (= "adaptive" (get-in first-body [:thinking :type]))
            "first request carries the adaptive thinking shape")
        (is (= "high" (get-in first-body [:output_config :effort]))
            "first request carries output_config.effort")
        (is (nil? (:thinking second-body))
            ":without-thinking must strip :thinking from the retried body")
        (is (nil? (:output_config second-body))
            ":without-thinking must strip :output_config from the retried body"))
      (is (some #(and (:retrying-with-compatibility-fallback (:event %))
                      (= [:without-thinking] (:retry-fallback-steps (:event %))))
                @captures)
          "response capture records the :without-thinking fallback step")
      (is (some #(= :start (:type %)) @events))
      (is (some #(= :done (:type %)) @events))
      (is (not-any? #(= :error (:type %)) @events)
          "retry succeeds — the 400 is absorbed, thinking silently ON on DeepSeek"))))

(deftest stream-anthropic-retries-without-all-betas-on-400-for-keyless-bearer-test
  ;; Review 19: fallback-request-steps-for-400 gates :without-all-betas on
  ;; (not (oauth-auth-request? request)), and oauth-auth-request? classified
  ;; ANY request carrying an Authorization: Bearer header as an OAuth request
  ;; — including a keyless custom provider whose auth comes from a custom
  ;; Authorization: Bearer header (the documented "Local servers and custom
  ;; headers" keyless pattern). On a beta-related 400 such a request kept ALL
  ;; beta headers on the retry (e.g. fast-mode-2026-02-01), repeating the same
  ;; 400 and hard-failing — the review-8 fast-mode note's "beta stripped"
  ;; degradation was worse there (not even the beta was stripped).
  ;; oauth-auth-request? now requires the transport's own OAuth signature
  ;; (Authorization Bearer + user-agent: claude-cli/… + x-app: cli), so a
  ;; keyless custom-header-Bearer request gets :without-all-betas like any
  ;; other non-OAuth request.
  (testing "keyless custom-header Bearer request gets :without-all-betas on a beta-related 400"
    (let [model    {:id                 "local-proxy"
                    :name               "Local Proxy"
                    :provider           :local-proxy
                    :custom?            true
                    :api                :anthropic-messages
                    :base-url           "http://localhost:8080"
                    :supports-reasoning false
                    :supports-images    false
                    :supports-text      true
                    :context-window     128000
                    :max-tokens         16384
                    :input-cost         0.0
                    :output-cost        0.0
                    :cache-read-cost    0.0
                    :cache-write-cost   0.0}
          convo    (-> (conv/create "sys")
                       (conv/add-user-message "hello"))
          calls    (atom [])
          captures (atom [])
          events   (atom [])
          sse      (str (sse-line "message_start" {:type "message_start"})
                        (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [http/post (fn [_url req]
                                (swap! calls conj req)
                                (if (= 1 (count @calls))
                                  {:status 400
                                   :headers {"request-id" "req_ant_first"}
                                   :body (stream-body
                                          (json/generate-string
                                           {:error {:message "Anthropic rejected the request"}}))}
                                  {:status 200
                                   :headers {}
                                   :body (stream-body sse)}))]
        (anthropic/stream-anthropic
         convo model {:no-auth-header true
                      :headers {"Authorization" "Bearer local-token"}
                      :speed-mode :fast
                      :on-provider-response #(swap! captures conj %)}
         (fn [e] (swap! events conj e))))
      (is (= 2 (count @calls))
          "keyless custom-header-Bearer 400 must retry once")
      (let [first-betas  (or (get-in (first @calls) [:headers "anthropic-beta"]) "")
            second-betas (or (get-in (second @calls) [:headers "anthropic-beta"]) "")
            second-auth  (get-in (second @calls) [:headers "Authorization"])
            first-body   (json/parse-string (:body (first @calls)) true)
            second-body  (json/parse-string (:body (second @calls)) true)]
        (is (re-find #"fast-mode" first-betas)
            "first request carries the fast-mode beta")
        (is (not (re-find #"fast-mode" second-betas))
            ":without-all-betas must clear ALL beta headers on the retry")
        (is (= "Bearer local-token" second-auth)
            "custom Authorization header must be preserved on the retry")
        ;; Review 35: the :without-all-betas transform strips beta HEADERS but
        ;; leaves the body's :speed "fast" field — the documented
        ;; "fast-mode 400 is not auto-recoverable" degradation (a DeepSeek 400
        ;; on the unverified speed field retries once with the same field and
        ;; hard-fails). Lock the retained body key, not just the stripped
        ;; header.
        (is (= "fast" (:speed first-body))
            "first request body carries \"speed\": \"fast\" in fast mode")
        (is (= "fast" (:speed second-body))
            "the retried body RETAINS \"speed\": \"fast\" — beta stripping does not
             remove the unverified speed field, so a speed-field 400 repeats
             and hard-fails (documented non-recovery)"))
      (is (some #(and (:retrying-with-compatibility-fallback (:event %))
                      (= [:without-all-betas] (:retry-fallback-steps (:event %))))
                @captures)
          "response capture records the :without-all-betas fallback step")
      (is (some #(= :start (:type %)) @events))
      (is (some #(= :done (:type %)) @events))
      (is (not-any? #(= :error (:type %)) @events)
          "retry succeeds — the beta-related 400 is absorbed")))

  (testing "oauth-auth-request? distinguishes genuine OAuth from keyless custom-header Bearer"
    ;; Direct predicate lock for the review-19 narrowing: only the
    ;; transport's own OAuth shape (Authorization Bearer + user-agent
    ;; claude-cli/ + x-app: cli) counts as OAuth; a bare custom Bearer
    ;; header does not.
    (let [oauth-request  {:headers {"Authorization" "Bearer sk-ant-oat-token"
                                    "user-agent"    "claude-cli/2.1.75"
                                    "x-app"         "cli"}}
          keyless-bearer {:headers {"Authorization" "Bearer local-token"
                                    "Content-Type"  "application/json"}}]
      (is (anthropic-error/oauth-auth-request? oauth-request)
          "genuine OAuth request (all three markers) is still classified OAuth")
      (is (not (anthropic-error/oauth-auth-request? keyless-bearer))
          "keyless custom-header Bearer request is NOT classified OAuth"))))

(deftest stream-anthropic-400-fallback-uses-transport-oauth-decision-test
  ;; Review 22: oauth-auth-request? (anthropic/error.clj) content-sniffs the
  ;; three-marker OAuth signature (Authorization: Bearer + user-agent:
  ;; claude-cli/… + x-app: cli) from the MERGED request headers, so the
  ;; documented keyless custom-header pattern can reproduce it: a keyless
  ;; custom :anthropic-messages provider whose custom :headers carry all
  ;; three markers was classified as a genuine OAuth request, which skipped
  ;; :without-all-betas on a beta-related 400 — ALL betas (e.g.
  ;; fast-mode-2026-02-01) were retained on the retry, which repeated the
  ;; same 400 and hard-failed. The fallback decision now uses the
  ;; transport's COMPUTED oauth? boolean (built-in Anthropic model +
  ;; OAuth-shaped key, threaded from build-request via ::oauth?), not the
  ;; header content-sniff — so this keyless request gets :without-all-betas
  ;; (betas stripped on the retry) exactly like the review-19 keyless Bearer
  ;; case. Fails against the old content-sniffing predicate (the three-marker
  ;; request was classified OAuth → no steps → 400 surfaced without retry).
  (testing "keyless custom-provider request with all three OAuth markers still gets :without-all-betas"
    (let [model    {:id                 "claude-gateway"
                    :name               "Claude Code-compatible gateway"
                    :provider           :local-proxy
                    :custom?            true
                    :api                :anthropic-messages
                    :base-url           "http://localhost:8080"
                    :supports-reasoning false
                    :supports-images    false
                    :supports-text      true
                    :context-window     128000
                    :max-tokens         16384
                    :input-cost         0.0
                    :output-cost        0.0
                    :cache-read-cost    0.0
                    :cache-write-cost   0.0}
          convo    (-> (conv/create "sys")
                       (conv/add-user-message "hello"))
          calls    (atom [])
          captures (atom [])
          events   (atom [])
          sse      (str (sse-line "message_start" {:type "message_start"})
                        (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [http/post (fn [_url req]
                                (swap! calls conj req)
                                (if (= 1 (count @calls))
                                  {:status 400
                                   :headers {"request-id" "req_ant_first"}
                                   :body (stream-body
                                          (json/generate-string
                                           {:error {:message "Anthropic rejected the request"}}))}
                                  {:status 200
                                   :headers {}
                                   :body (stream-body sse)}))]
        (anthropic/stream-anthropic
         convo model
         {:no-auth-header true
          ;; The full Claude Code CLI marker set as custom headers — the
          ;; exact reproduction of the OAuth signature review-22 flagged.
          :headers {"Authorization" "Bearer local-token"
                    "user-agent"    "claude-cli/2.1.75"
                    "x-app"         "cli"}
          :speed-mode :fast
          :on-provider-response #(swap! captures conj %)}
         (fn [e] (swap! events conj e))))
      (is (= 2 (count @calls))
          "three-marker keyless custom-provider 400 must retry once")
      (let [first-betas  (or (get-in (first @calls) [:headers "anthropic-beta"]) "")
            second-betas (or (get-in (second @calls) [:headers "anthropic-beta"]) "")
            second-auth  (get-in (second @calls) [:headers "Authorization"])]
        (is (re-find #"fast-mode" first-betas)
            "first request carries the fast-mode beta")
        (is (not (re-find #"fast-mode" second-betas))
            ":without-all-betas must clear ALL beta headers on the retry")
        (is (= "Bearer local-token" second-auth)
            "custom Authorization header must be preserved on the retry"))
      (is (some #(and (:retrying-with-compatibility-fallback (:event %))
                      (= [:without-all-betas] (:retry-fallback-steps (:event %))))
                @captures)
          "response capture records the :without-all-betas fallback step")
      (is (some #(= :start (:type %)) @events))
      (is (some #(= :done (:type %)) @events))
      (is (not-any? #(= :error (:type %)) @events)
          "retry succeeds — the three-marker keyless request is not treated as OAuth"))))

(deftest stream-anthropic-custom-anthropic-beta-header-stripped-by-without-all-betas-test
  ;; Review 22: a custom "anthropic-beta" header REPLACES the transport betas
  ;; on the first request (merge of custom headers over base headers, locked
  ;; by build-request-custom-anthropic-beta-header-replaces-transport-betas-test),
  ;; and on a beta-related 400 the :without-all-betas step wipes the user's
  ;; custom beta too (clear-beta-header drops the anthropic-beta header
  ;; entirely) — the retry may then 400 for a DIFFERENT reason (missing
  ;; provider-required beta) and hard-fail, masking the original error.
  ;; Documented in doc/custom-providers.md "Local servers and custom headers".
  (testing "custom anthropic-beta header is stripped by :without-all-betas on a beta-related 400"
    (let [model    {:id                 "local-proxy"
                    :name               "Local Proxy"
                    :provider           :local-proxy
                    :custom?            true
                    :api                :anthropic-messages
                    :base-url           "http://localhost:8080"
                    :supports-reasoning false
                    :supports-images    false
                    :supports-text      true
                    :context-window     128000
                    :max-tokens         16384
                    :input-cost         0.0
                    :output-cost        0.0
                    :cache-read-cost    0.0
                    :cache-write-cost   0.0}
          convo    (-> (conv/create "sys")
                       (conv/add-user-message "hello"))
          calls    (atom [])
          captures (atom [])
          events   (atom [])
          sse      (str (sse-line "message_start" {:type "message_start"})
                        (sse-line "message_stop" {:type "message_stop"}))]
      (with-redefs [http/post (fn [_url req]
                                (swap! calls conj req)
                                (if (= 1 (count @calls))
                                  {:status 400
                                   :headers {"request-id" "req_ant_first"}
                                   :body (stream-body
                                          (json/generate-string
                                           {:error {:message "Anthropic rejected the request"}}))}
                                  {:status 200
                                   :headers {}
                                   :body (stream-body sse)}))]
        (anthropic/stream-anthropic
         convo model {:api-key "test-key"
                      :headers {"anthropic-beta" "custom-beta-1"}
                      :on-provider-response #(swap! captures conj %)}
         (fn [e] (swap! events conj e))))
      (is (= 2 (count @calls))
          "custom-beta 400 must retry once")
      (let [first-betas  (or (get-in (first @calls) [:headers "anthropic-beta"]) "")
            second-betas (or (get-in (second @calls) [:headers "anthropic-beta"]) "")
            second-key   (get-in (second @calls) [:headers "x-api-key"])]
        (is (= "custom-beta-1" first-betas)
            "first request carries the custom anthropic-beta header")
        (is (not (re-find #"custom-beta-1" second-betas))
            ":without-all-betas must clear the custom anthropic-beta header on the retry")
        (is (= "" (or second-betas ""))
            "retried request has no anthropic-beta header at all")
        (is (= "test-key" second-key)
            "configured x-api-key auth must be preserved on the retry"))
      (is (some #(and (:retrying-with-compatibility-fallback (:event %))
                      (= [:without-all-betas] (:retry-fallback-steps (:event %))))
                @captures)
          "response capture records the :without-all-betas fallback step")
      (is (some #(= :start (:type %)) @events))
      (is (some #(= :done (:type %)) @events))
      (is (not-any? #(= :error (:type %)) @events)
          "retry succeeds — the custom beta is stripped, the 400 is absorbed"))))


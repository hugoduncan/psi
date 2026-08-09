(ns psi.ai.providers.openai.chat-completions
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [psi.ai.models :as models]
            [psi.ai.providers.openai.content :as content]
            [psi.ai.providers.openai.reasoning :as reasoning]
            [psi.ai.providers.openai.transport :as transport]
            [psi.ai.providers.request-support :as request-support]
            [psi.ai.structured-output :as structured-output]))

(defn- extract-reasoning-delta
  [delta]
  (let [reasoning (:reasoning delta)]
    (or
     (content/string-fragment (get-in delta [:reasoning :content]))
     (content/string-fragment (get-in delta [:reasoning :summary]))
     (content/string-fragment (:reasoning_content delta))

     (when (string? reasoning)
       reasoning)

     (when (map? reasoning)
       (or (content/string-fragment (:content reasoning))
           (content/string-fragment (:summary reasoning))
           (content/string-fragment (:text reasoning))
           (content/string-fragment (:delta reasoning))))

     (when (sequential? reasoning)
       (content/join-parts
        (keep (fn [part]
                (when (map? part)
                  (let [ptype (content/normalize-part-type part)]
                    (when (or (contains? reasoning/reasoning-part-types ptype)
                              (contains? part :reasoning)
                              (contains? part :summary))
                      (or (content/string-fragment (:text part))
                          (content/string-fragment (:content part))
                          (content/string-fragment (:delta part))
                          (content/string-fragment (:reasoning part))
                          (content/string-fragment (:summary part)))))))
              reasoning)))

     (when (sequential? (:content delta))
       (content/join-parts
        (keep (fn [part]
                (when (map? part)
                  (let [ptype (content/normalize-part-type part)]
                    (when (contains? reasoning/reasoning-part-types ptype)
                      (or (content/string-fragment (:text part))
                          (content/string-fragment (:content part))
                          (content/string-fragment (:delta part))
                          (content/string-fragment (:summary part)))))))
              (:content delta)))))))

(defn- extract-text-delta
  [delta]
  (cond
    (string? (:content delta))
    (:content delta)

    (sequential? (:content delta))
    (content/join-parts
     (keep (fn [part]
             (when (map? part)
               (let [ptype (content/normalize-part-type part)]
                 (when (contains? #{"text" "output_text"} ptype)
                   (or (content/string-fragment (:text part))
                       (content/string-fragment (:content part)))))))
           (:content delta)))

    :else nil))

(defn- extract-tool-call-fragments
  [choice delta]
  (let [delta-tool-calls   (or (:tool_calls delta) [])
        delta-function     (:function_call delta)
        message-tool-calls (or (get-in choice [:message :tool_calls]) [])
        message-function   (get-in choice [:message :function_call])]
    (vec
     (concat
      delta-tool-calls
      (when (map? delta-function)
        [{:index 0
          :function delta-function}])
      message-tool-calls
      (when (map? message-function)
        [{:index 0
          :function message-function}])))))

(defn transform-messages
  "Transform conversation messages to OpenAI chat completions format."
  ([conversation]
   (transform-messages conversation nil))
  ([conversation fallback-request]
   (let [last-user-index (when fallback-request
                           (last (keep-indexed (fn [idx msg]
                                                 (when (= :user (:role msg)) idx))
                                               (:messages conversation))))]
     (->> (:messages conversation)
          (map-indexed vector)
          (reduce
           (fn [acc [idx msg]]
             (case (:role msg)
               :user
               (let [text (content/user-message-text msg)]
                 (conj acc {:role    "user"
                            :content (if (= idx last-user-index)
                                       (structured-output/append-fallback-instructions-to-text
                                        text fallback-request)
                                       text)}))

               :assistant
               (if (= :structured (get-in msg [:content :kind]))
                 (let [{:keys [text tool-calls]} (content/assistant-structured-content msg)
                       base                     (cond-> {:role "assistant"}
                                                  (seq text) (assoc :content text))]
                   (conj acc
                         (if (seq tool-calls)
                           (assoc base :tool_calls (mapv content/chat-tool-call tool-calls))
                           base)))
                 (conj acc {:role    "assistant"
                            :content (get-in msg [:content :text] "")}))

               :tool-result
               (conj acc {:role         "tool"
                          :tool_call_id (:tool-call-id msg)
                          :content      (content/tool-result-text msg)})

               :system
               (conj acc {:role "system"
                          :content (content/user-message-text msg)})

               acc))
           [])))))

(defn build-request
  "Build OpenAI Chat Completions API request map."
  [conversation model options]
  (let [structured-request (structured-output/structured-output-request options)
        strategy           (structured-output/select-strategy model structured-request)
        ;; No auth key is required when the request is explicitly keyless
        ;; (:no-auth-header, e.g. :auth-header? false local servers) or when
        ;; custom :headers supply a recognized auth header (x-api-key /
        ;; authorization) without a configured :api-key. Incidental custom
        ;; headers (e.g. X-Client) do NOT imply keyless: with a blank
        ;; configured key such a request fast-fails with the clear
        ;; "Missing API key" error instead of silently sending the global
        ;; OPENAI_API_KEY to a custom endpoint — consistent with the
        ;; :anthropic-messages transport.
        no-auth?           (request-support/no-auth? options)
        api-key            (when-not no-auth?
                             (request-support/resolve-api-key
                              model options request-support/openai-api-key-config))
        fallback-request   (when (= :prompted-json (:strategy strategy))
                             structured-request)
        base-messages      (transform-messages conversation fallback-request)
        messages      (if (:system-prompt conversation)
                        (cons {:role    "system"
                               :content (:system-prompt conversation)}
                              base-messages)
                        base-messages)
        tool-defs     (when (seq (:tools conversation))
                        (mapv (fn [t]
                                {:type     "function"
                                 :function {:name        (:name t)
                                            :description (:description t)
                                            :parameters  (:parameters t)}})
                              (:tools conversation)))
        effort        (reasoning/reasoning-effort model options)
        temperature   (or (:temperature options) 0)
        template-kw   (reasoning/chat-template-kwargs model options)
        body          (cond-> {:model          (:id model)
                               :messages       (vec messages)
                               :stream         true
                               :stream_options {:include_usage true}
                               :temperature    temperature}
                        (:max-tokens options)       (assoc :max_tokens  (:max-tokens options))
                        (seq tool-defs)             (assoc :tools tool-defs)
                        (and (seq tool-defs)
                             (contains? model :parallel-tool-calls))
                        (assoc :parallel_tool_calls (:parallel-tool-calls model))
                        effort                      (assoc :reasoning_effort effort)
                        (= :fast (:speed-mode options)) (assoc :service_tier "flex")
                        template-kw                 (assoc :chat_template_kwargs template-kw)
                        (:logprobs-enabled options) (assoc :logprobs true
                                                           :top_logprobs (or (:top-logprobs options) 3))
                        (and (= :provider-native (:strategy strategy))
                             (= :openai/chat-completions-json-schema-response-format
                                (:native-mechanism strategy)))
                        (assoc :response_format
                               {:type "json_schema"
                                :json_schema
                                {:name (structured-output/structured-output-name structured-request)
                                 :strict (not (false? (:strict? structured-request)))
                                 :schema (:json-schema structured-request)}}))]
    {:headers (cond-> {"Content-Type" "application/json"}
                ;; Skip Authorization when no key is resolved: explicit
                ;; :no-auth-header (e.g. local servers that reject auth
                ;; headers) or keyless custom-header auth.
                (not no-auth?)
                (assoc "Authorization" (str "Bearer " api-key))
                (:headers options)
                (merge (:headers options)))
     :body    (json/generate-string body)}))

(defn- completions-usage-map
  [model usage]
  (let [usage-map {:input-tokens       (or (:prompt_tokens usage) 0)
                   :output-tokens      (or (:completion_tokens usage) 0)
                   :cache-read-tokens  0
                   :cache-write-tokens 0
                   :total-tokens       (or (:total_tokens usage)
                                           (+ (or (:prompt_tokens usage) 0)
                                              (or (:completion_tokens usage) 0)))}]
    (assoc usage-map :cost (models/calculate-cost model usage-map))))

(defn- emit-chat-completion-finish!
  [consume-fn stream-started? done? reason usage]
  (when-not @done?
    (reset! done? true)
    (when (compare-and-set! stream-started? false true)
      (consume-fn {:type :start}))
    (consume-fn (cond-> {:type :done
                         :reason reason}
                  usage (assoc :usage usage)))))

(defn- update-tool-index! [tool-index-by-id next-tool-index call-id idx]
  (when (seq call-id)
    (swap! tool-index-by-id assoc call-id idx))
  (swap! next-tool-index #(max % (inc idx)))
  idx)

(defn- make-chat-stream-state
  []
  {:stream-started?            (atom false)
   :done?                      (atom false)
   :pending-finish-reason      (atom nil)
   ;; Review 48: last-seen usage chunk (completions-usage-map shape), so the
   ;; flushed terminal :done (trailing [DONE] or the EOF flush) carries the
   ;; provider's usage when one was seen — the review-47 zero-usage class on
   ;; the :openai-completions sibling. A provider that omits the usage chunk
   ;; entirely leaves this nil and the :done carries no :usage (zero
   ;; usage/cost recorded — documented consequence for usage-omitting
   ;; endpoints).
   :last-usage                  (atom nil)
   :structured-result-emitted? (atom false)
   :text-buffer                (atom "")
   :next-tool-index            (atom 0)
   :tool-index-by-id           (atom {})
   :tool-state                 (atom {})})

(defn- emit-stream-start!
  [consume-fn stream-started?]
  (when (compare-and-set! stream-started? false true)
    (consume-fn {:type :start})))

(defn- emit-started-event!
  [consume-fn stream-started? event]
  (emit-stream-start! consume-fn stream-started?)
  (consume-fn event))

(defn- resolve-chat-tool-index
  [{:keys [tool-index-by-id next-tool-index]} tool-call fallback-idx]
  (let [idx     (:index tool-call)
        call-id (:id tool-call)]
    (cond
      (number? idx)
      (update-tool-index! tool-index-by-id next-tool-index call-id idx)

      (and (seq call-id)
           (contains? @tool-index-by-id call-id))
      (get @tool-index-by-id call-id)

      :else
      (update-tool-index! tool-index-by-id next-tool-index call-id
                          (or fallback-idx @next-tool-index)))))

(defn- ensure-chat-tool-entry!
  [{:keys [tool-state]} idx]
  (swap! tool-state update idx
         (fn [s]
           (merge {:id nil
                   :name nil
                   :started? false
                   :args-buffer ""}
                  s))))

(defn- start-chat-tool-if-ready!
  [{:keys [tool-state stream-started?]} consume-fn idx force?]
  (let [{:keys [id name started? args-buffer]} (get @tool-state idx)
        id* (or id (when force? (content/new-call-id)))]
    (when (and (not started?) (seq name) (seq id*))
      (swap! tool-state assoc idx
             {:id id*
              :name name
              :started? true
              :args-buffer (or args-buffer "")})
      (emit-started-event! consume-fn stream-started?
                           {:type :toolcall-start
                            :content-index idx
                            :id id*
                            :name name})
      (when (seq args-buffer)
        (consume-fn {:type :toolcall-delta
                     :content-index idx
                     :delta args-buffer})))))

(defn- process-chat-tool-call!
  [stream-state consume-fn idx tool-call]
  (let [{:keys [tool-state tool-index-by-id stream-started?]} stream-state
        call-id   (:id tool-call)
        call-name (get-in tool-call [:function :name])
        args      (content/normalize-tool-arguments
                   (get-in tool-call [:function :arguments]))]
    (ensure-chat-tool-entry! stream-state idx)
    (when (seq call-id)
      (swap! tool-state assoc-in [idx :id] call-id)
      (swap! tool-index-by-id assoc call-id idx))
    (when (seq call-name)
      (swap! tool-state assoc-in [idx :name] call-name))
    (start-chat-tool-if-ready! stream-state consume-fn idx false)
    (when (seq args)
      (let [current-buffer (get-in @tool-state [idx :args-buffer] "")
            {:keys [buffer delta]} (content/accumulate-tool-arguments current-buffer args)]
        (swap! tool-state assoc-in [idx :args-buffer] buffer)
        (when (and (get-in @tool-state [idx :started?])
                   (seq delta))
          (emit-started-event! consume-fn stream-started?
                               {:type :toolcall-delta
                                :content-index idx
                                :delta delta}))))
    (start-chat-tool-if-ready! stream-state consume-fn idx false)))

(defn- force-start-pending-chat-tools!
  [stream-state consume-fn]
  (doseq [idx (sort (keys @(-> stream-state :tool-state)))]
    (start-chat-tool-if-ready! stream-state consume-fn idx true)))

(defn- emit-chat-tool-ends!
  [{:keys [tool-state]} consume-fn]
  (doseq [[idx {:keys [started?]}] (sort-by key @tool-state)]
    (when started?
      (consume-fn {:type :toolcall-end
                   :content-index idx})))
  (reset! tool-state {}))

(defn- normalize-openai-logprob-token
  [item]
  {:token   (:token item)
   :logprob (:logprob item)
   :top     (mapv (fn [t] {:token (:token t) :logprob (:logprob t)})
                  (or (:top_logprobs item) []))})

(defn- normalize-llama-logprob-token
  [item]
  {:token   (:content item)
   :logprob (when-let [p (some-> (:probs item) first :prob)]
              (when (pos? p) (Math/log p)))
   :top     (mapv (fn [t] {:token (:tok_str t) :logprob (when (and (:prob t) (pos? (:prob t)))
                                                          (Math/log (:prob t)))})
                  (or (:probs item) []))})

(defn- extract-openai-logprob-delta
  "Extract per-chunk logprob data from OpenAI SSE delta (choices[0].logprobs.content)."
  [choice]
  (when-let [tokens (seq (get-in choice [:logprobs :content]))]
    (mapv normalize-openai-logprob-token tokens)))

(defn- extract-llama-logprob-delta
  "Extract logprob data from llama.cpp final SSE chunk (completion_probabilities)."
  [chunk]
  (when-let [tokens (seq (:completion_probabilities chunk))]
    (mapv normalize-llama-logprob-token tokens)))

(defn- emit-chat-chunk!
  [stream-state consume-fn choice delta]
  (let [{:keys [stream-started? text-buffer]} stream-state
        text-delta      (extract-text-delta delta)
        reasoning-delta (extract-reasoning-delta delta)]
    (when (and choice (= (:role delta) "assistant"))
      (emit-stream-start! consume-fn stream-started?))
    (when (seq text-delta)
      (swap! text-buffer str text-delta)
      (emit-started-event! consume-fn stream-started?
                           {:type :text-delta
                            :content-index 0
                            :delta text-delta}))
    (when (seq reasoning-delta)
      (emit-started-event! consume-fn stream-started?
                           {:type :thinking-delta
                            :content-index 0
                            :delta reasoning-delta}))
    (when-let [logprob-tokens (extract-openai-logprob-delta choice)]
      (consume-fn {:type :logprob-delta :tokens logprob-tokens}))
    (doseq [[fallback-idx tool-call]
            (map-indexed vector (extract-tool-call-fragments choice delta))]
      (process-chat-tool-call! stream-state consume-fn
                               (resolve-chat-tool-index stream-state tool-call fallback-idx)
                               tool-call))))

(defn- structured-output-result
  [strategy source raw-text]
  (when (contains? #{:provider-native :prompted-json} (:strategy strategy))
    (let [parse-result (structured-output/parse-json-value raw-text)]
      (cond-> (assoc strategy
                     :source source
                     :raw-text raw-text
                     :raw-payload raw-text)
        (:parsed? parse-result) (assoc :payload (:payload parse-result))
        (not parse-result) (assoc :parse-error? true)))))

(defn- emit-structured-output-result!
  [stream-state consume-fn strategy source]
  (let [{:keys [structured-result-emitted? text-buffer]} stream-state]
    (when (compare-and-set! structured-result-emitted? false true)
      (when-let [result (structured-output-result strategy source @text-buffer)]
        (consume-fn {:type :structured-output-result
                     :structured-output result})))))

(defn- finish-chat-chunk!
  [stream-state consume-fn model chunk choice strategy]
  (let [{:keys [stream-started? done? pending-finish-reason]} stream-state]
    (cond
      (:usage chunk)
      (do
        (force-start-pending-chat-tools! stream-state consume-fn)
        (emit-chat-tool-ends! stream-state consume-fn)
        (emit-structured-output-result! stream-state
                                        consume-fn
                                        strategy
                                        (if (= :provider-native (:strategy strategy))
                                          :openai/message-json
                                          :prompted-json/text))
        ;; Review 48: record the last-seen usage so the flushed terminal
        ;; :done (trailing [DONE] or EOF flush) can carry it when the done
        ;; emission is deferred (see flush-pending-chat-finish! / the EOF
        ;; flush in stream-openai).
        (reset! (:last-usage stream-state) (completions-usage-map model (:usage chunk)))
        (emit-chat-completion-finish! consume-fn
                                      stream-started?
                                      done?
                                      (or @pending-finish-reason
                                          (keyword (get-in choice [:finish_reason] "stop")))
                                      (completions-usage-map model (:usage chunk)))
        (reset! pending-finish-reason nil))

      (:finish_reason choice)
      (do
        (force-start-pending-chat-tools! stream-state consume-fn)
        (emit-chat-tool-ends! stream-state consume-fn)
        (when-let [logprob-tokens (extract-llama-logprob-delta chunk)]
          (consume-fn {:type :logprob-delta :tokens logprob-tokens}))
        (reset! pending-finish-reason (keyword (:finish_reason choice)))))))

(defn- flush-pending-chat-finish!
  [stream-state consume-fn strategy]
  (let [{:keys [stream-started? done? pending-finish-reason]} stream-state]
    (when-let [reason @pending-finish-reason]
      (emit-structured-output-result! stream-state
                                      consume-fn
                                      strategy
                                      (if (= :provider-native (:strategy strategy))
                                        :openai/message-json
                                        :prompted-json/text))
      ;; Review 48: attach the last-seen usage chunk (when one was seen) to
      ;; the flushed :done instead of hardcoding nil — a usage chunk seen
      ;; before the trailing [DONE]/EOF is preserved on the terminal event.
      (emit-chat-completion-finish! consume-fn stream-started? done? reason
                                    @(:last-usage stream-state))
      (reset! pending-finish-reason nil))))

(defn- emit-chat-error!
  "Surface a mid-stream OpenAI SSE error chunk ({\"error\": {...}}) as an
   :error event and terminate the stream. Review 43: an error chunk with no
   :choices previously no-oped in process-chat-sse-line! (no :error event, no
   terminal :done), so a mid-stream provider error hung the turn until the
   idle timeout with a misleading timeout — the same silent-drop class fixed
   for the anthropic transport's \"error\" SSE event. The chunk is already
   captured via capture-response! before this is called; the done? guard
   prevents a trailing [DONE]/finish chunk from emitting a second terminal
   event."
  [stream-state consume-fn chunk]
  (let [{:keys [done?]} stream-state]
    (when-not @done?
      (reset! done? true)
      (let [status (some (fn [s] (and (number? s) (>= s 400) s))
                         [(:status chunk)
                          (get-in chunk [:error :status])
                          (get-in chunk [:error :http_status])])
            err    (transport/response->error {:status  status
                                               :headers nil
                                               :body    (json/generate-string chunk)})]
        (consume-fn err)))))

(defn- process-chat-sse-line!
  [stream-state consume-fn model options url strategy line]
  ;; Review 46: short-circuit the whole line once the stream has terminated
  ;; (done? — set by emit-chat-error! and emit-chat-completion-finish!). A
  ;; trailing chunk after a mid-stream SSE error chunk previously still
  ;; emitted non-terminal events: a trailing :choices chunk fired
  ;; :text-delta via emit-chat-chunk!, a trailing usage/finish chunk drove
  ;; finish-chat-chunk!'s unguarded force-start-pending-chat-tools! /
  ;; emit-chat-tool-ends! / emit-structured-output-result! (only
  ;; emit-chat-completion-finish! was done?-guarded), and a trailing [DONE]
  ;; with a pending finish reason fired flush-pending-chat-finish!'s
  ;; :structured-output-result. Now every post-done line is a full no-op.
  (when-not @(:done? stream-state)
    (if-let [chunk (transport/parse-sse-line line)]
      (do
        (transport/capture-response! model options :openai-completions url chunk)
        (if (:error chunk)
          (emit-chat-error! stream-state consume-fn chunk)
          (let [choice (first (:choices chunk))
                delta  (:delta choice)]
            (emit-chat-chunk! stream-state consume-fn choice delta)
            (finish-chat-chunk! stream-state consume-fn model chunk choice strategy))))
      (when (and line (.startsWith ^String line "data: ") (= "[DONE]" (.substring ^String line 6)))
        (flush-pending-chat-finish! stream-state consume-fn strategy)))))

(defn- non-streaming-request
  [conversation model options]
  (let [request (build-request conversation model options)
        body    (-> (:body request)
                    (json/parse-string true)
                    (assoc :stream false)
                    (dissoc :stream_options))]
    (assoc request :body (json/generate-string body))))

(defn- tool-call-block
  [tool-call]
  {:type :tool-call
   :id (or (:id tool-call) (content/new-call-id))
   :name (get-in tool-call [:function :name])
   :arguments (content/normalize-tool-arguments
               (get-in tool-call [:function :arguments]))})

(defn- completion-message->content
  [message]
  (let [text (content/string-fragment (:content message))
        tool-calls (mapv tool-call-block (or (:tool_calls message) []))]
    (cond-> []
      (seq text) (conj {:type :text :text text})
      (seq tool-calls) (into tool-calls))))

(defn- completion-response->assistant-message
  ([model body]
   (completion-response->assistant-message model body nil))
  ([model body strategy]
   (let [choice (first (:choices body))
         message (:message choice)
         stop-reason (keyword (or (:finish_reason choice) "stop"))
         usage (when-let [usage (:usage body)]
                 (completions-usage-map model usage))
         logprobs (or (extract-openai-logprob-delta choice)
                      (extract-llama-logprob-delta body))
         text (content/string-fragment (:content message))]
     (cond-> {:assistant-message (cond-> {:role "assistant"
                                          :content (completion-message->content message)
                                          :stop-reason stop-reason
                                          :timestamp (java.time.Instant/now)}
                                   (map? usage) (assoc :usage usage))
              :logprobs logprobs}
       strategy (assoc :structured-output
                       (structured-output-result strategy
                                                 (if (= :provider-native (:strategy strategy))
                                                   :openai/message-json
                                                   :prompted-json/text)
                                                 text))))))

(defn execute-openai
  [conversation model options]
  (let [url                (str (:base-url model) "/chat/completions")
        structured-request (structured-output/structured-output-request options)
        strategy           (structured-output/select-strategy model structured-request)
        request            (non-streaming-request conversation model options)]
    (try
      (transport/capture-request! model options :openai-completions url request)
      (let [response (transport/execute-response url request)]
        (if (transport/error-status? (:status response))
          (transport/response->error response)
          (let [body (json/parse-string (:body response) true)
                _    (transport/capture-response! model options :openai-completions url body)]
            (completion-response->assistant-message model body strategy))))
      (catch Exception e
        (transport/exception->error e)))))

(defn stream-openai
  [conversation model options consume-fn]
  (let [url                (str (:base-url model) "/chat/completions")
        structured-request (structured-output/structured-output-request options)
        strategy           (structured-output/select-strategy model structured-request)
        request            (build-request conversation model options)
        stream-state       (make-chat-stream-state)]
    (try
      (transport/capture-request! model options :openai-completions url request)
      (when strategy
        (consume-fn {:type :structured-output-strategy
                     :structured-output strategy}))
      (let [response (transport/stream-response url request)]
        (if (transport/error-status? (:status response))
          (transport/emit-error! model
                                 options
                                 :openai-completions
                                 url
                                 consume-fn
                                 (transport/response->error response))
          (do
            (with-open [reader (io/reader (:body response))]
              (doseq [line (line-seq reader)]
                (process-chat-sse-line! stream-state consume-fn model options url strategy line)))
            ;; Review 48: EOF-level terminal flush — mirror the codex
            ;; transport's (when-not @(:done? ...) ...) after its SSE doseq.
            ;; A stream that EOFs without an in-band terminal event (a usage
            ;; chunk, a finish_reason chunk + [DONE], or an error chunk)
            ;; previously emitted NO :done/:error and hung the turn until
            ;; llm-stream-idle-timeout-ms: a final chunk carrying finish_reason
            ;; but no trailing [DONE] left pending-finish-reason unflushed
            ;; (flush-pending-chat-finish! runs only on a [DONE] line), and a
            ;; [DONE] with no prior finish_reason chunk no-oped (the flush
            ;; guards on the pending reason). The flush emits the pending
            ;; finish reason when set, else :stop, and attaches the last-seen
            ;; usage chunk when one was provided; when an in-band terminal
            ;; already fired, done? makes it a no-op.
            (when-not @(:done? stream-state)
              (flush-pending-chat-finish! stream-state consume-fn strategy)
              (when-not @(:done? stream-state)
                (emit-structured-output-result! stream-state
                                                consume-fn
                                                strategy
                                                (if (= :provider-native (:strategy strategy))
                                                  :openai/message-json
                                                  :prompted-json/text))
                (emit-chat-completion-finish! consume-fn
                                              (:stream-started? stream-state)
                                              (:done? stream-state)
                                              :stop
                                              @(:last-usage stream-state)))
              ;; Preserve the pre-review-48 nil return of the stream fn (the
              ;; flush's when-not would otherwise return the last consumed
              ;; event via emit-chat-completion-finish!'s consume-fn result).
              nil))))
      (catch Exception e
        ;; Review 44: guard the error emission on done? (mirroring the codex
        ;; transport's emit-codex-error!) — if a mid-stream SSE error chunk
        ;; already terminated the stream (emit-chat-error! sets done?),
        ;; a stream-read exception thrown afterwards must not emit a SECOND
        ;; :error.
        (when-not @(:done? stream-state)
          (transport/emit-error! model
                                 options
                                 :openai-completions
                                 url
                                 consume-fn
                                 (transport/exception->error e)))))))

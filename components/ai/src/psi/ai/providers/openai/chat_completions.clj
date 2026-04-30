(ns psi.ai.providers.openai.chat-completions
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [psi.ai.models :as models]
            [psi.ai.providers.openai.content :as content]
            [psi.ai.providers.openai.reasoning :as reasoning]
            [psi.ai.providers.openai.transport :as transport]))

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
  [conversation]
  (->> (:messages conversation)
       (reduce
        (fn [acc msg]
          (case (:role msg)
            :user
            (conj acc {:role    "user"
                       :content (content/user-message-text msg)})

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

            acc))
        [])))

(defn build-request
  "Build OpenAI Chat Completions API request map."
  [conversation model options]
  (let [base-messages (transform-messages conversation)
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
        body          (cond-> {:model          (:id model)
                               :messages       (vec messages)
                               :stream         true
                               :stream_options {:include_usage true}
                               :temperature    temperature}
                        (:max-tokens options)  (assoc :max_tokens  (:max-tokens options))
                        (seq tool-defs)        (assoc :tools tool-defs)
                        effort                 (assoc :reasoning_effort effort))]
    {:headers (cond-> {"Content-Type" "application/json"}
                ;; Skip Authorization when :no-auth-header is set
                ;; (e.g. local servers that reject auth headers)
                (not (:no-auth-header options))
                (assoc "Authorization"
                       (str "Bearer " (or (:api-key options)
                                          (System/getenv "OPENAI_API_KEY"))))
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
  {:stream-started?  (atom false)
   :done?            (atom false)
   :next-tool-index  (atom 0)
   :tool-index-by-id (atom {})
   :tool-state       (atom {})})

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

(defn- emit-chat-chunk!
  [stream-state consume-fn choice delta]
  (let [{:keys [stream-started?]} stream-state
        text-delta      (extract-text-delta delta)
        reasoning-delta (extract-reasoning-delta delta)]
    (when (and choice (= (:role delta) "assistant"))
      (emit-stream-start! consume-fn stream-started?))
    (when (seq text-delta)
      (emit-started-event! consume-fn stream-started?
                           {:type :text-delta
                            :content-index 0
                            :delta text-delta}))
    (when (seq reasoning-delta)
      (emit-started-event! consume-fn stream-started?
                           {:type :thinking-delta
                            :content-index 0
                            :delta reasoning-delta}))
    (doseq [[fallback-idx tool-call]
            (map-indexed vector (extract-tool-call-fragments choice delta))]
      (process-chat-tool-call! stream-state consume-fn
                               (resolve-chat-tool-index stream-state tool-call fallback-idx)
                               tool-call))))

(defn- finish-chat-chunk!
  [stream-state consume-fn model chunk choice]
  (let [{:keys [stream-started? done?]} stream-state]
    (cond
      (:usage chunk)
      (do
        (force-start-pending-chat-tools! stream-state consume-fn)
        (emit-chat-tool-ends! stream-state consume-fn)
        (emit-chat-completion-finish! consume-fn
                                      stream-started?
                                      done?
                                      (keyword (get-in choice [:finish_reason] "stop"))
                                      (completions-usage-map model (:usage chunk))))

      (:finish_reason choice)
      (do
        (force-start-pending-chat-tools! stream-state consume-fn)
        (emit-chat-tool-ends! stream-state consume-fn)
        (emit-chat-completion-finish! consume-fn
                                      stream-started?
                                      done?
                                      (keyword (:finish_reason choice))
                                      nil)))))

(defn- process-chat-sse-line!
  [stream-state consume-fn model options url line]
  (when-let [chunk (transport/parse-sse-line line)]
    (transport/capture-response! model options :openai-completions url chunk)
    (let [choice (first (:choices chunk))
          delta  (:delta choice)]
      (emit-chat-chunk! stream-state consume-fn choice delta)
      (finish-chat-chunk! stream-state consume-fn model chunk choice))))

(defn stream-openai
  [conversation model options consume-fn]
  (let [url          (str (:base-url model) "/chat/completions")
        request      (build-request conversation model options)
        stream-state (make-chat-stream-state)]
    (try
      (transport/capture-request! model options :openai-completions url request)
      (let [response (transport/stream-response url request)]
        (if (transport/error-status? (:status response))
          (transport/emit-error! model
                                 options
                                 :openai-completions
                                 url
                                 consume-fn
                                 (transport/response->error response))
          (with-open [reader (io/reader (:body response))]
            (doseq [line (line-seq reader)]
              (process-chat-sse-line! stream-state consume-fn model options url line)))))
      (catch Exception e
        (transport/emit-error! model
                               options
                               :openai-completions
                               url
                               consume-fn
                               (transport/exception->error e))))))

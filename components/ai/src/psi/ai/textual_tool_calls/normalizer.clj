(ns psi.ai.textual-tool-calls.normalizer
  (:require
   [cheshire.core :as json]
   [psi.ai.textual-tool-calls.capabilities :as capabilities]
   [psi.ai.textual-tool-calls.index-allocator :as index-allocator]
   [psi.ai.textual-tool-calls.parser :as parser]))

(def ^:private parsed-calls-key
  ::parsed-calls)

(defn- text-block
  [source-block text]
  (when (seq text)
    (cond-> {:type :text :text text}
      (contains? source-block :content-index) (assoc :content-index (:content-index source-block)))))

(defn- with-parsed-calls
  [block]
  (if (and (= :text (:type block))
           (string? (:text block)))
    (assoc block parsed-calls-key (parser/parse-xml-tool-calls (:text block)))
    block))

(defn- next-index
  [turn-id state source-block kind]
  (index-allocator/assign-index turn-id parsed-calls-key state source-block kind))

(defn- recovered-tool-call-block
  [turn-id state source-block parsed-call]
  (let [[state* content-index] (next-index turn-id state source-block :recovered-tool-call)]
    [state*
     {:type :tool-call
      :content-index content-index
      :id (str turn-id "/toolcall/" content-index)
      :name (:name parsed-call)
      :arguments (json/generate-string (:arguments parsed-call))}]))

(defn- append-text-block
  [turn-id state source-block text]
  (if (seq text)
    (let [[state* _] (next-index turn-id state source-block :source-text)]
      [state* (text-block source-block text)])
    [state nil]))

(defn- append-before-text
  [turn-id state source-block blocks text]
  (let [[state* block] (append-text-block turn-id state source-block text)]
    [state* (cond-> blocks block (conj block))]))

(defn- append-recovered-call
  [turn-id state source-block blocks parsed-call]
  (let [[state* block] (recovered-tool-call-block turn-id state source-block parsed-call)]
    [state* (conj blocks block)]))

(defn- textual-tool-call-content
  [turn-id state source-block]
  (let [text         (:text source-block)
        parsed-calls (get source-block parsed-calls-key)]
    (if (empty? parsed-calls)
      (let [[state* block] (append-text-block turn-id state source-block text)]
        [state* [block]])
      (loop [state  state
             cursor 0
             calls  parsed-calls
             blocks []]
        (if-let [{[start end] :span :as parsed-call} (first calls)]
          (let [before          (subs text cursor start)
                [state* blocks*] (append-before-text turn-id state source-block blocks before)
                [state** blocks**] (append-recovered-call turn-id state* source-block blocks* parsed-call)]
            (recur state** end (rest calls) blocks**))
          (let [[state* block] (append-text-block turn-id state source-block (subs text cursor))]
            [state* (cond-> blocks block (conj block))]))))))

(defn- normalize-block
  [turn-id state block]
  (if (and (= :text (:type block)) (string? (:text block)))
    (textual-tool-call-content turn-id state block)
    (let [[state* _] (next-index turn-id state block :existing-content)]
      [state* [block]])))

(defn- strip-content-index
  [block]
  (dissoc block :content-index))

(defn- normalize-content
  [turn-id content]
  (let [initial-state (index-allocator/initial-state turn-id parsed-calls-key content)]
    (loop [state  initial-state
           blocks content
           result []]
      (if-let [block (first blocks)]
        (let [[state* normalized-blocks] (normalize-block turn-id state block)]
          (recur state* (rest blocks) (into result (keep identity normalized-blocks))))
        result))))

(defn normalize-assistant-message
  "Recover textual tool calls in an assistant message when the resolved model opts in.

   The normalizer is intentionally pure and narrow. It only transforms text blocks
   when `model` declares `{:capabilities {:textual-tool-calls #{:xml}}}`; all
   other models return the assistant message unchanged. Well-formed textual
   calls are converted to canonical tool-call blocks and their exact source spans
   are removed from assistant text. Malformed markup remains ordinary text."
  [turn-id model assistant-message]
  (if-not (capabilities/supports-format? model :xml)
    assistant-message
    (let [content    (mapv with-parsed-calls (:content assistant-message))
          normalized (->> (normalize-content turn-id content)
                          (mapv strip-content-index))]
      (assoc assistant-message :content normalized))))

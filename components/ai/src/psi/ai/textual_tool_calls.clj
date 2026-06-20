(ns psi.ai.textual-tool-calls
  "Model-capability-gated parser for local-runner textual tool-call markup."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]))

(def supported-formats
  "Textual tool-call formats understood by Psi."
  #{:xml})

(defn supports-format?
  "Return true when resolved model explicitly opts into textual tool calls for format."
  [model format]
  (contains? (set (get-in model [:capabilities :textual-tool-calls])) format))

(def ^:private identifier-pattern "[A-Za-z0-9_-]+")
(def ^:private tool-call-pattern #"(?s)<tool_call>(.*?)</tool_call>")
(def ^:private function-pattern
  (re-pattern (str "(?s)<function=(" identifier-pattern ")>(.*?)</function>")))
(def ^:private parameter-pattern
  (re-pattern (str "(?s)<parameter=(" identifier-pattern ")>(.*?)</parameter>")))

(defn- matcher-results
  [pattern s]
  (let [matcher (re-matcher pattern (or s ""))]
    (loop [matches []]
      (if (.find matcher)
        (recur (conj matches {:start  (.start matcher)
                              :end    (.end matcher)
                              :match  (.group matcher 0)
                              :groups (mapv #(.group matcher %) (range 1 (inc (.groupCount matcher))))}))
        matches))))

(defn- remove-spans
  [s spans]
  (let [ordered (sort-by :start spans)]
    (loop [cursor 0
           spans*  ordered
           parts   []]
      (if-let [{:keys [start end]} (first spans*)]
        (recur end (rest spans*) (conj parts (subs s cursor start)))
        (apply str (conj parts (subs s cursor)))))))

(defn- parsed-parameters
  [function-body]
  (let [parameter-matches (matcher-results parameter-pattern function-body)
        remainder         (remove-spans function-body parameter-matches)
        params            (mapv (fn [{:keys [groups]}]
                                  (let [[param-name param-value] groups]
                                    [param-name (str/trim param-value)]))
                                parameter-matches)
        param-names       (map first params)]
    (when (and (seq params)
               (str/blank? remainder)
               (= (count param-names) (count (distinct param-names))))
      (into {} params))))

(defn- parsed-tool-call
  [{:keys [start end match groups]}]
  (let [[body]           groups
        function-matches (matcher-results function-pattern body)]
    (when (= 1 (count function-matches))
      (let [{function-start :start function-end :end function-groups :groups} (first function-matches)
            outside-function (str (subs body 0 function-start)
                                  (subs body function-end))
            [tool-name function-body] function-groups]
        (when (and (str/blank? outside-function)
                   (not (str/includes? function-body "<function=")))
          (when-let [arguments (parsed-parameters function-body)]
            {:span      [start end]
             :source    match
             :name      tool-name
             :arguments arguments}))))))

(defn parse-xml-tool-calls
  "Parse well-formed XML-like textual tool calls from text.

   Returns successful calls in response order. Malformed blocks are omitted from
   the result so callers can leave their source text unchanged."
  [text]
  (->> (matcher-results tool-call-pattern text)
       (keep parsed-tool-call)
       vec))

(defn- text-block
  [text]
  (when (seq text)
    {:type :text :text text}))

(defn- recovered-tool-call-block
  [turn-id next-content-index parsed-call]
  {:type :tool-call
   :id (str turn-id "/toolcall/" (next-content-index))
   :name (:name parsed-call)
   :arguments (json/generate-string (:arguments parsed-call))})

(defn- textual-tool-call-content
  [turn-id next-content-index text]
  (let [parsed-calls (parse-xml-tool-calls text)]
    (if (empty? parsed-calls)
      [(text-block text)]
      (loop [cursor 0
             calls  parsed-calls
             blocks []]
        (if-let [{[start end] :span :as parsed-call} (first calls)]
          (let [before (subs text cursor start)]
            (recur end
                   (rest calls)
                   (cond-> blocks
                     (seq before) (conj (text-block before))
                     :always      (conj (recovered-tool-call-block turn-id next-content-index parsed-call)))))
          (cond-> blocks
            (seq (subs text cursor)) (conj (text-block (subs text cursor)))))))))

(defn- generated-tool-call-index
  [turn-id tool-call-id]
  (let [prefix (str turn-id "/toolcall/")]
    (when (str/starts-with? (str tool-call-id) prefix)
      (parse-long (subs (str tool-call-id) (count prefix))))))

(defn- next-content-index-fn
  [turn-id content]
  (let [used-indexes (->> content
                          (keep #(when (= :tool-call (:type %))
                                   (generated-tool-call-index turn-id (:id %))))
                          set)
        state        (atom {:candidate 0 :used used-indexes})]
    (fn []
      (loop []
        (let [{:keys [candidate used]} @state]
          (if (contains? used candidate)
            (do (swap! state update :candidate inc)
                (recur))
            (do (swap! state (fn [{:keys [used]}]
                               {:candidate (inc candidate)
                                :used      (conj used candidate)}))
                candidate)))))))

(defn normalize-assistant-message
  "Recover textual tool calls in an assistant message when the resolved model opts in.

   The normalizer is intentionally pure and narrow. It only transforms text blocks
   when `model` declares `{:capabilities {:textual-tool-calls #{:xml}}}`; all
   other models return the assistant message unchanged. Well-formed textual
   calls are converted to canonical tool-call blocks and their exact source spans
   are removed from assistant text. Malformed markup remains ordinary text."
  [turn-id model assistant-message]
  (if-not (supports-format? model :xml)
    assistant-message
    (let [content            (:content assistant-message)
          next-content-index (next-content-index-fn turn-id content)
          normalized         (mapcat (fn [block]
                                       (if (and (= :text (:type block)) (string? (:text block)))
                                         (textual-tool-call-content turn-id next-content-index (:text block))
                                         [block]))
                                     content)
          content*           (vec (keep identity normalized))]
      (assoc assistant-message :content content*))))

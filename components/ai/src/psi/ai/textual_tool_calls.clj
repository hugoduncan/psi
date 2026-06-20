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
(def ^:private tool-call-open "<tool_call>")
(def ^:private tool-call-close "</tool_call>")
(def ^:private function-pattern
  (re-pattern (str "(?s)^\\s*<function=(" identifier-pattern ")>(.*)</function>\\s*$")))
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
               (not-any? (fn [[_ param-value]]
                           (or (str/includes? param-value "<parameter=")
                               (str/includes? param-value "</parameter>")))
                         params)
               (= (count param-names) (count (distinct param-names))))
      (into {} params))))

(defn- parsed-tool-call
  [{:keys [start end match groups]}]
  (let [[body]         groups
        function-match (re-matches function-pattern body)]
    (when function-match
      (let [[_ tool-name function-body] function-match]
        (when-let [arguments (parsed-parameters function-body)]
          {:span      [start end]
           :source    match
           :name      tool-name
           :arguments arguments})))))

(defn- candidates-for-open
  [s open]
  (loop [close-search-from (+ open (count tool-call-open))
         candidates        []]
    (if-let [close-start (str/index-of s tool-call-close close-search-from)]
      (let [end       (+ close-start (count tool-call-close))
            candidate {:start  open
                       :end    end
                       :match  (subs s open end)
                       :groups [(subs s (+ open (count tool-call-open)) close-start)]}]
        (recur end (conj candidates candidate)))
      candidates)))

(defn- candidate-tool-call-spans
  [text]
  (let [s (or text "")]
    (loop [search-from 0
           spans       []]
      (if-let [open (str/index-of s tool-call-open search-from)]
        (recur (inc open) (into spans (candidates-for-open s open)))
        spans))))

(defn- non-overlapping-successes
  [parsed-calls]
  (loop [cursor 0
         calls  parsed-calls
         kept   []]
    (if-let [{[start end] :span :as call} (first calls)]
      (if (< start cursor)
        (recur cursor (rest calls) kept)
        (recur end (rest calls) (conj kept call)))
      kept)))

(defn parse-xml-tool-calls
  "Parse well-formed XML-like textual tool calls from text.

   Returns successful calls in response order. Malformed blocks are omitted from
   the result so callers can leave their source text unchanged. Later valid
   blocks are still recoverable when an earlier malformed prefix overlaps them."
  [text]
  (->> (candidate-tool-call-spans text)
       (keep parsed-tool-call)
       non-overlapping-successes
       vec))

(defn- text-block
  [source-block text]
  (when (seq text)
    (cond-> {:type :text :text text}
      (contains? source-block :content-index) (assoc :content-index (:content-index source-block)))))

(defn- advance-position!
  [state]
  (swap! state update :position (fnil inc 0)))

(defn- recovered-tool-call-block
  [turn-id next-content-index parsed-call]
  (let [content-index (next-content-index parsed-call)]
    {:type :tool-call
     :content-index content-index
     :id (str turn-id "/toolcall/" content-index)
     :name (:name parsed-call)
     :arguments (json/generate-string (:arguments parsed-call))}))

(defn- append-text-block
  [next-content-index source-block text]
  (when (seq text)
    (next-content-index source-block :source-text)
    (text-block source-block text)))

(defn- textual-tool-call-content
  [turn-id next-content-index source-block]
  (let [text         (:text source-block)
        parsed-calls (parse-xml-tool-calls text)]
    (if (empty? parsed-calls)
      (do
        (next-content-index source-block :source-text)
        [(text-block source-block text)])
      (loop [cursor 0
             calls  parsed-calls
             blocks []]
        (if-let [{[start end] :span :as parsed-call} (first calls)]
          (let [before (subs text cursor start)]
            (recur end
                   (rest calls)
                   (cond-> blocks
                     (seq before) (conj (append-text-block next-content-index source-block before))
                     :always      (conj (recovered-tool-call-block
                                         turn-id
                                         (fn [_]
                                           (next-content-index source-block :recovered-tool-call))
                                         parsed-call)))))
          (cond-> blocks
            (seq (subs text cursor)) (conj (append-text-block next-content-index source-block (subs text cursor)))))))))

(defn- generated-tool-call-index
  [turn-id tool-call-id]
  (let [prefix (str turn-id "/toolcall/")]
    (when (str/starts-with? (str tool-call-id) prefix)
      (parse-long (subs (str tool-call-id) (count prefix))))))

(defn- reserve-content-index
  [state content-index]
  (swap! state (fn [state*]
                 (-> state*
                     (update :used conj content-index)
                     (update :position max content-index))))
  content-index)

(defn- allocate-content-index
  [state preferred]
  (loop [candidate preferred]
    (let [{:keys [used]} @state]
      (if (contains? used candidate)
        (recur (inc candidate))
        (reserve-content-index state candidate)))))

(defn- fully-replaced-text-block?
  [block]
  (and (= :text (:type block))
       (string? (:text block))
       (let [parsed-calls (parse-xml-tool-calls (:text block))]
         (and (= 1 (count parsed-calls))
              (let [{[start end] :span} (first parsed-calls)]
                (and (zero? start) (= end (count (:text block)))))))))

(defn- next-content-index-fn
  [turn-id content]
  (let [indexed-content (->> content
                             (map-indexed vector)
                             vec)
        shadowed-source-indexes (->> indexed-content
                                     (filter (fn [[idx block]]
                                               (let [source-index (:content-index block)]
                                                 (and (fully-replaced-text-block? block)
                                                      source-index
                                                      (not-any? (fn [[other-idx other-block]]
                                                                  (and (not= idx other-idx)
                                                                       (or (= source-index (:content-index other-block))
                                                                           (= source-index
                                                                              (generated-tool-call-index
                                                                               turn-id
                                                                               (:id other-block))))))
                                                                indexed-content)))))
                                     (keep (comp :content-index second))
                                     set)
        source-indexes (->> content
                            (keep :content-index)
                            set)
        generated-id-indexes (->> content
                                  (keep #(generated-tool-call-index turn-id (:id %)))
                                  set)
        used-indexes   (set (concat (remove shadowed-source-indexes source-indexes)
                                    generated-id-indexes))
        state          (atom {:used used-indexes :position -1 :source-text-reserved #{}})]
    (fn
      ([]
       (advance-position! state))
      ([source-block kind]
       (let [{:keys [position]} @state
             source-index (:content-index source-block)
             preferred (cond
                         (and (= :source-text kind) source-index)
                         (max (long source-index) (inc (long position)))

                         (and (= :recovered-tool-call kind)
                              source-index
                              (fully-replaced-text-block? source-block))
                         (long source-index)

                         (and (= :existing-content kind) source-index)
                         (long source-index)

                         (and (= :existing-content kind)
                              (generated-tool-call-index turn-id (:id source-block)))
                         (generated-tool-call-index turn-id (:id source-block))

                         (= :existing-content kind)
                         (inc (long position))

                         source-index
                         (max (inc (long source-index)) (inc (long position)))

                         :else
                         (inc (long position)))]
         (cond
           (and (= :existing-content kind) source-index)
           (reserve-content-index state preferred)

           (and (= :existing-content kind)
                (generated-tool-call-index turn-id (:id source-block)))
           (reserve-content-index state preferred)

           (and (= :source-text kind) source-index)
           (if (contains? (:source-text-reserved @state) source-index)
             (allocate-content-index state (max (inc (long source-index)) (inc (long position))))
             (do
               (swap! state update :source-text-reserved conj source-index)
               (reserve-content-index state source-index)))

           :else
           (let [preferred* (if (contains? (:source-text-reserved @state) source-index)
                              (max (inc (long source-index)) preferred)
                              preferred)]
             (allocate-content-index state preferred*))))))))

(defn- normalize-block
  [turn-id next-content-index block]
  (if (and (= :text (:type block)) (string? (:text block)))
    (textual-tool-call-content turn-id next-content-index block)
    (do
      (next-content-index block :existing-content)
      [block])))

(defn- strip-content-index
  [block]
  (dissoc block :content-index))

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
          normalized         (->> content
                                  (mapcat #(normalize-block turn-id next-content-index %))
                                  (keep identity)
                                  (mapv strip-content-index))]
      (assoc assistant-message :content normalized))))

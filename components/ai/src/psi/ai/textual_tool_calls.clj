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
(def ^:private parameter-open-pattern
  (re-pattern (str "<parameter=(" identifier-pattern ")>")))
(def ^:private textual-tool-call-tag-pattern
  (re-pattern
   "<tool_call>|</tool_call>|<function=[^>]*>|</function>|<parameter=[^>]*>|</parameter>"))

(def ^:dynamic *parse-work-counter*
  "Optional instrumentation hook for parser tests. When bound to an atom,
  parser scan work increments the atom without affecting parse semantics."
  nil)

(defn- count-work!
  []
  (when *parse-work-counter*
    (swap! *parse-work-counter* inc)))

(def ^:private max-candidate-span-chars
  "Hard bound on one textual tool-call candidate block.

  Larger blocks remain ordinary assistant text. This keeps malformed many-marker
  scans linear in the response size with a fixed per-open work cap."
  65536)

(defn- marker-positions
  [s marker]
  (loop [search-from 0
         positions   []]
    (count-work!)
    (if-let [position (str/index-of s marker search-from)]
      (recur (+ position (count marker)) (conj positions position))
      positions)))

(defn- candidate-tool-call-spans
  [text]
  (let [s      (or text "")
        opens  (marker-positions s tool-call-open)
        closes (marker-positions s tool-call-close)]
    (loop [opens  opens
           closes closes
           spans  []]
      (if-let [open (first opens)]
        (do
          (count-work!)
          (let [min-close-start (+ open (count tool-call-open))
                closes*         (drop-while #(< % min-close-start) closes)]
            (recur (rest opens)
                   closes*
                   (if-let [close-start (first closes*)]
                     (let [end       (+ close-start (count tool-call-close))
                           oversized? (> end (+ open max-candidate-span-chars))]
                       (conj spans
                             (cond-> {:start     open
                                      :end       end
                                      :oversized? oversized?}
                               (not oversized?)
                               (assoc :match (subs s open end)
                                      :groups [(subs s min-close-start close-start)]
                                      :full-text s))))
                     spans))))
        spans))))

(defn- inside-earlier-candidate?
  [all-spans start end]
  (boolean
   (some (fn [{candidate-start :start candidate-end :end}]
           (and (< candidate-start start) (>= candidate-end end)))
         all-spans)))

(defn- in-oversized-candidate?
  [all-spans start end]
  (boolean
   (some (fn [{candidate-start :start candidate-end :end oversized? :oversized?}]
           (and oversized?
                (< candidate-start start)
                (> candidate-end end)))
         all-spans)))

(defn- tag-looking-parameter-text?
  [value]
  (boolean (re-find textual-tool-call-tag-pattern value)))

(defn- parse-parameter-at
  [function-body cursor]
  (let [matcher (re-matcher parameter-open-pattern function-body)]
    (.region matcher cursor (count function-body))
    (when (.lookingAt matcher)
      (let [name        (.group matcher 1)
            value-start (.end matcher)]
        (when-let [close-start (str/index-of function-body "</parameter>" value-start)]
          {:name  name
           :value (subs function-body value-start close-start)
           :end   (+ close-start (count "</parameter>"))})))))

(defn- parsed-parameters
  [function-body]
  (loop [cursor 0
         params []]
    (cond
      (str/blank? (subs function-body cursor))
      (let [param-names (map first params)]
        (when (and (seq params)
                   (= (count param-names) (count (distinct param-names))))
          (into {} params)))

      :else
      (when (str/blank? (subs function-body cursor
                              (or (str/index-of function-body "<parameter=" cursor)
                                  (count function-body))))
        (when-let [open-start (str/index-of function-body "<parameter=" cursor)]
          (when-let [{:keys [name value end]} (parse-parameter-at function-body open-start)]
            (when-not (tag-looking-parameter-text? value)
              (recur end (conj params [name (str/trim value)])))))))))

(defn- parsed-tool-call
  [all-spans {:keys [start end match groups]}]
  (let [[body]         groups
        function-match (re-matches function-pattern body)]
    (when (and function-match
               (not (inside-earlier-candidate? all-spans start end))
               (not (in-oversized-candidate? all-spans start end)))
      (let [[_ tool-name function-body] function-match]
        (when-let [arguments (parsed-parameters function-body)]
          {:span      [start end]
           :source    match
           :name      tool-name
           :arguments arguments})))))

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
   blocks are still recoverable when they are outside earlier candidate spans."
  [text]
  (let [spans (candidate-tool-call-spans text)]
    (->> spans
         (remove :oversized?)
         (keep #(parsed-tool-call spans %))
         non-overlapping-successes
         vec)))
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

(def ^:private parsed-calls-key
  ::parsed-calls)

(defn- textual-tool-call-content
  [turn-id next-content-index source-block]
  (let [text         (:text source-block)
        parsed-calls (get source-block parsed-calls-key)]
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

(defn- with-parsed-calls
  [block]
  (if (and (= :text (:type block))
           (string? (:text block)))
    (assoc block parsed-calls-key (parse-xml-tool-calls (:text block)))
    block))

(defn- leading-recovered-text-block?
  [block]
  (when-let [parsed-calls (seq (get block parsed-calls-key))]
    (zero? (-> parsed-calls first :span first))))

(defn- next-content-index-fn
  [turn-id content]
  (let [indexed-content (->> content
                             (map-indexed vector)
                             vec)
        shadowed-source-indexes (->> indexed-content
                                     (filter (fn [[idx block]]
                                               (let [source-index (:content-index block)]
                                                 (and (leading-recovered-text-block? block)
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
                              (leading-recovered-text-block? source-block)
                              (not (contains? (:source-text-reserved @state) source-index)))
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

           (and (= :recovered-tool-call kind)
                source-index
                (leading-recovered-text-block? source-block)
                (not (contains? (:source-text-reserved @state) source-index)))
           (do
             (swap! state update :source-text-reserved conj source-index)
             (allocate-content-index state preferred))

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
    (let [content            (mapv with-parsed-calls (:content assistant-message))
          next-content-index (next-content-index-fn turn-id content)
          normalized         (->> content
                                  (mapcat #(normalize-block turn-id next-content-index %))
                                  (keep identity)
                                  (mapv strip-content-index))]
      (assoc assistant-message :content normalized))))

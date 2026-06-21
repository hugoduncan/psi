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
(declare candidate-tool-call-spans)

(defn- parameter-open-matches
  [function-body]
  (let [matcher (re-matcher (re-pattern (str "<parameter=(" identifier-pattern ")>"))
                            (or function-body ""))]
    (loop [matches []]
      (if (.find matcher)
        (recur (conj matches {:start (.start matcher)
                              :end   (.end matcher)
                              :name  (.group matcher 1)}))
        matches))))

(defn- parameter-candidates
  [function-body open-match next-open]
  (let [first-close-start (str/index-of function-body "</parameter>" (:end open-match))]
    (loop [search-from (:end open-match)
           candidates  []]
      (if-let [close-start (str/index-of function-body "</parameter>" search-from)]
        (let [end (+ close-start (count "</parameter>"))]
          (recur end
                 (conj candidates {:start (:start open-match)
                                   :end end
                                   :name (:name open-match)
                                   :value-start (:end open-match)
                                   :value (subs function-body (:end open-match) close-start)
                                   :crosses-next-open? (boolean (and next-open (< (:start next-open) close-start)))
                                   :crosses-nonnested-next-open? (boolean
                                                                  (and next-open
                                                                       first-close-start
                                                                       (< first-close-start (:start next-open) close-start)))})))
        candidates))))

(defn- choose-parameter-candidates
  [function-body open-matches]
  (letfn [(next-open-index [end]
            (or (first (keep-indexed (fn [idx open-match]
                                       (when (>= (:start open-match) end)
                                         idx))
                                     open-matches))
                (count open-matches)))
          (step [idx cursor]
            (if (= idx (count open-matches))
              (when (str/blank? (subs function-body cursor))
                [])
              (let [open-match (nth open-matches idx)
                    next-open  (get open-matches (inc idx))]
                (when (str/blank? (subs function-body cursor (:start open-match)))
                  (some (fn [{:keys [end crosses-next-open? crosses-nonnested-next-open?] :as candidate}]
                          (let [next-idx (next-open-index end)]
                            (when (and (or (not crosses-next-open?) (> next-idx (inc idx)))
                                       (not crosses-nonnested-next-open?))
                              (when-let [rest-candidates (step next-idx end)]
                                (cons candidate rest-candidates)))))
                        (parameter-candidates function-body open-match next-open))))))]
    (step 0 0)))

(defn- incomplete-nested-tool-call-start?
  [value]
  (let [start-pattern (re-pattern (str "<tool_call><function=" identifier-pattern "><parameter=" identifier-pattern ">"))
        matcher       (re-matcher start-pattern value)]
    (loop []
      (if (.find matcher)
        (let [start (.start matcher)
              end   (.end matcher)
              quoted-literal? (and (pos? start)
                                   (contains? #{\' \"} (.charAt value (dec start))))
              parameter-open-at-boundary? (str/blank? (subs value end))]
          (or (and (not quoted-literal?)
                   (not parameter-open-at-boundary?)
                   (not (str/index-of value tool-call-close start)))
              (recur)))
        false))))

(defn- swallowed-following-function-block?
  [value]
  (boolean
   (re-find (re-pattern (str "(?s)</parameter>\\s*</function>\\s*<function=" identifier-pattern ">"))
            value)))

(defn- parsed-parameter-pairs
  [function-body]
  (some->> (choose-parameter-candidates function-body
                                        (parameter-open-matches function-body))
           (mapv (fn [{:keys [name value]}]
                   [name (str/trim value)]))))

(defn- parsed-parameters
  [function-body]
  (let [params      (parsed-parameter-pairs function-body)
        param-names (map first params)]
    (when (and (seq params)
               (= (count param-names) (count (distinct param-names)))
               (every? (complement incomplete-nested-tool-call-start?) (map second params))
               (every? (complement swallowed-following-function-block?) (map second params)))
      (into {} params))))

(defn- non-parameter-remainder-contains-tag?
  [function-body tag]
  (loop [cursor 0
         params (choose-parameter-candidates function-body
                                             (parameter-open-matches function-body))]
    (if-let [{:keys [start end]} (first params)]
      (or (str/includes? (subs function-body cursor start) tag)
          (recur end (rest params)))
      (str/includes? (subs function-body cursor) tag))))

(defn- duplicate-parameter-name?
  [function-body]
  (let [params (parsed-parameter-pairs function-body)]
    (and (seq params)
         (not= (count (map first params))
               (count (distinct (map first params)))))))

(defn- span-starts-in-parameter-value?
  [function-body body-start start]
  (some (fn [{value-start :value-start candidate-end :end}]
          (let [absolute-value-start (+ body-start value-start)
                absolute-value-end   (- (+ body-start candidate-end) (count "</parameter>"))]
            (and (<= absolute-value-start start) (< start absolute-value-end))))
        (choose-parameter-candidates function-body
                                     (parameter-open-matches function-body))))

(defn- span-starts-in-body-parameter-before-function?
  [body body-start start]
  (let [first-function-start (str/index-of body "<function=")]
    (and first-function-start
         (some (fn [{param-start :start value-start :end}]
                 (when-let [close-start (str/index-of body "</parameter>" value-start)]
                   (let [absolute-value-start (+ body-start value-start)
                         absolute-value-end   (+ body-start close-start)]
                     (and (< param-start first-function-start)
                          (<= absolute-value-start start)
                          (< start absolute-value-end)))))
               (parameter-open-matches body)))))

(defn- span-starts-in-invalid-function-parameter?
  [body body-start start]
  (let [relative-start (- start body-start)]
    (some (fn [function-open]
            (let [function-open-end (str/index-of body ">" function-open)
                  function-close (when function-open-end
                                   (str/index-of body "</function>" function-open-end))
                  valid-function-open? (when function-open-end
                                         (re-matches
                                          (re-pattern (str "<function=" identifier-pattern ">"))
                                          (subs body function-open (inc function-open-end))))]
              (and function-open-end
                   function-close
                   (not valid-function-open?)
                   (< function-open relative-start)
                   (> function-close relative-start)
                   (some (fn [{relative-value-start :end}]
                           (let [value-start (+ function-open relative-value-start)]
                             (when-let [parameter-close (str/index-of body "</parameter>" value-start)]
                               (and (<= value-start relative-start)
                                    (< relative-start parameter-close)
                                    (< function-open value-start)
                                    (< parameter-close function-close)))))
                         (parameter-open-matches
                          (subs body function-open (inc function-close)))))))
          (loop [search-from 0
                 starts []]
            (if-let [idx (str/index-of body "<function=" search-from)]
              (recur (inc idx) (conj starts idx))
              starts)))))

(defn- span-starts-in-later-function-parameter?
  [body body-start start]
  (let [relative-start (- start body-start)
        function-starts (loop [search-from 0
                               starts []]
                          (if-let [idx (str/index-of body "<function=" search-from)]
                            (recur (inc idx) (conj starts idx))
                            starts))]
    (when (> (count function-starts) 1)
      (some (fn [function-open]
              (let [function-open-end (str/index-of body ">" function-open)
                    function-close    (when function-open-end
                                        (str/index-of body "</function>" function-open-end))
                    valid-function-open? (when function-open-end
                                           (re-matches
                                            (re-pattern (str "<function=" identifier-pattern ">"))
                                            (subs body function-open (inc function-open-end))))]
                (and function-open-end
                     function-close
                     valid-function-open?
                     (< function-open relative-start)
                     (< relative-start function-close)
                     (some (fn [{relative-value-start :end}]
                             (let [value-start (+ function-open relative-value-start)]
                               (when-let [parameter-close (str/index-of body "</parameter>" value-start)]
                                 (and (<= value-start relative-start)
                                      (< relative-start parameter-close)
                                      (< parameter-close function-close)))))
                           (parameter-open-matches
                            (subs body function-open (inc function-close)))))))
            (rest function-starts)))))

(defn- span-starts-in-invalid-parameter-value?
  [body body-start start]
  (let [relative-start (- start body-start)]
    (some (fn [parameter-open]
            (let [parameter-open-end (str/index-of body ">" parameter-open)
                  parameter-close    (when parameter-open-end
                                       (str/index-of body "</parameter>" parameter-open-end))
                  valid-parameter-open? (when parameter-open-end
                                          (re-matches
                                           (re-pattern (str "<parameter=" identifier-pattern ">"))
                                           (subs body parameter-open (inc parameter-open-end))))]
              (and parameter-open-end
                   parameter-close
                   (not valid-parameter-open?)
                   (<= (inc parameter-open-end) relative-start)
                   (< relative-start parameter-close))))
          (loop [search-from 0
                 starts []]
            (if-let [idx (str/index-of body "<parameter=" search-from)]
              (recur (inc idx) (conj starts idx))
              starts)))))

(defn- enclosing-malformed-tool-call?
  [text start end]
  (some (fn [{candidate-start :start candidate-end :end :keys [groups]}]
          (when (and (< candidate-start start) (>= candidate-end end))
            (let [[body]     groups
                  body-start (+ candidate-start (count tool-call-open))
                  matcher    (re-matcher function-pattern body)]
              (if (.matches matcher)
                (let [function-body       (.group matcher 2)
                      function-body-start (+ body-start (.start matcher 2))
                      nested-in-parameter? (span-starts-in-parameter-value?
                                            function-body
                                            function-body-start
                                            start)]
                  (or (span-starts-in-invalid-parameter-value?
                       function-body
                       function-body-start
                       start)
                      (span-starts-in-later-function-parameter?
                       body
                       body-start
                       start)
                      (and nested-in-parameter?
                           (duplicate-parameter-name? function-body))
                      (and nested-in-parameter?
                           (or (zero? candidate-start)
                               (= candidate-end (count text)))
                           (seq (parsed-parameter-pairs function-body))
                           (nil? (parsed-parameters function-body)))
                      (and nested-in-parameter?
                           (some swallowed-following-function-block?
                                 (map second (parsed-parameter-pairs function-body))))
                      (and (nil? (parsed-parameter-pairs function-body))
                           (nil? (parsed-parameters function-body))
                           (str/starts-with? (str/trim function-body) "<tool_call>"))))
                (or (span-starts-in-body-parameter-before-function? body body-start start)
                    (span-starts-in-invalid-function-parameter? body body-start start)
                    (span-starts-in-later-function-parameter? body body-start start)
                    (span-starts-in-invalid-parameter-value? body body-start start))))))
        (candidate-tool-call-spans text)))

(defn- parsed-tool-call
  [{:keys [start end match groups full-text]}]
  (let [[body]         groups
        function-match (re-matches function-pattern body)]
    (when function-match
      (let [[_ tool-name function-body] function-match]
        (when-not (or (non-parameter-remainder-contains-tag? function-body "<function=")
                      (non-parameter-remainder-contains-tag? function-body "<tool_call>")
                      (and (< end (count full-text))
                           (contains? #{\' \"} (.charAt full-text end))
                           (re-find #"['\"]<tool_call>" function-body))
                      (enclosing-malformed-tool-call? full-text start end))
          (when-let [arguments (parsed-parameters function-body)]
            {:span      [start end]
             :source    match
             :name      tool-name
             :arguments arguments}))))))

(defn- candidates-for-open
  [s open]
  (loop [close-search-from (+ open (count tool-call-open))
         candidates        []]
    (if-let [close-start (str/index-of s tool-call-close close-search-from)]
      (let [end       (+ close-start (count tool-call-close))
            candidate {:start     open
                       :end       end
                       :match     (subs s open end)
                       :groups    [(subs s (+ open (count tool-call-open)) close-start)]
                       :full-text s}]
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
         (and (seq parsed-calls)
              (zero? (-> parsed-calls first :span first))
              (= (count (:text block)) (-> parsed-calls last :span second))
              (every? (fn [[left right]]
                        (= (-> left :span second) (-> right :span first)))
                      (partition 2 1 parsed-calls))))))

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

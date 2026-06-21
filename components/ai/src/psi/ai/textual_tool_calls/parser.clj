(ns psi.ai.textual-tool-calls.parser
  (:require
   [clojure.string :as str]))

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

(ns psi.ai.textual-tool-calls
  "Model-capability-gated parser for local-runner textual tool-call markup."
  (:require
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

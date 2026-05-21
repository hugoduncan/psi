(ns psi.session-journal.codec
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(defn- instant->date
  "Recursively convert any java.time.Instant values in `x` to java.util.Date
  so that pr-str produces #inst literals readable by clojure.edn/read-string."
  [x]
  (cond
    (instance? Instant x)    (java.util.Date/from x)
    (map? x)                 (reduce-kv (fn [m k v] (assoc m k (instant->date v))) {} x)
    (sequential? x)          (mapv instant->date x)
    :else                    x))

(defn entry->line
  "Serialise a single map to a single EDN line (no newlines inside).
  Converts java.time.Instant to #inst so edn/read-string can round-trip."
  [m]
  (pr-str (instant->date m)))

(defn parse-line
  "Parse a single NDEDN line. Returns nil on blank, parse error, or non-map.
   Canonicalizes #inst values to java.time.Instant."
  [line]
  (let [trimmed (str/trim line)]
    (when-not (str/blank? trimmed)
      (try
        (let [v (edn/read-string
                 {:readers {'inst #(Instant/parse %)}}
                 trimmed)]
          (when (map? v) v))
        (catch Exception _
          nil)))))

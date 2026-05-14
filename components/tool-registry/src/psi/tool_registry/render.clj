(ns psi.tool-registry.render
  "Shared tool-display helpers for canonical tool-definition render hooks."
  (:require
   [clojure.string :as str]))

(defn parse-tool-args
  [args]
  (cond
    (map? args) args
    (nil? args) nil
    :else nil))

(defn tool-arg-get
  [args & keys]
  (when (map? args)
    (some #(get args %) keys)))

(defn tool-int
  [v]
  (cond
    (integer? v) v
    (number? v)  (long v)
    (string? v)  (try (Long/parseLong v) (catch Exception _ nil))
    :else        nil))

(defn tool-line-range-suffix
  "Return optional :line or :start:end suffix string for built-in file tools."
  [tool-name args details]
  (case tool-name
    "read"
    (let [offset* (tool-int (tool-arg-get args "offset" :offset))
          limit*  (tool-int (tool-arg-get args "limit" :limit))
          offset  (or offset* (when limit* 1))]
      (cond
        (and offset limit* (pos? limit*))
        (format ":%d:%d" offset (+ offset (dec limit*)))

        offset
        (format ":%d" offset)

        :else ""))

    "edit"
    (let [first-changed* (or (get details :firstChangedLine)
                             (get details :first-changed-line)
                             (get details "firstChangedLine")
                             (get details "first-changed-line"))
          first-changed  (tool-int first-changed*)
          old-text       (tool-arg-get args "oldText" :oldText "old_text")
          span           (when (string? old-text)
                           (max 1 (count (str/split-lines old-text))))]
      (cond
        (and first-changed span (> span 1))
        (format ":%d:%d" first-changed (+ first-changed (dec span)))

        first-changed
        (format ":%d" first-changed)

        :else ""))

    ""))

(defn builtin-call-header
  "Return the canonical plain-text call header for the in-scope built-in tools.
   Falls back to nil for tools this task does not migrate."
  [tool-name args details]
  (let [args        (parse-tool-args args)
        primary     (case tool-name
                      ("read" "edit" "write") (tool-arg-get args "path" :path)
                      "bash"                    (tool-arg-get args "command" :command)
                      nil)
        line-suffix (tool-line-range-suffix tool-name args details)
        label       (if (seq (str primary))
                      (str primary line-suffix)
                      "…")]
    (case tool-name
      "bash"  (str "$ " label)
      "read"  (str "read " label)
      "edit"  (str "edit " label)
      "write" (str "write " label)
      nil)))

(defn builtin-call-render-fn
  [tool-name]
  (fn [args]
    (builtin-call-header tool-name args nil)))

(defn builtin-result-render-fn
  "Return nil-rendering hook for tools that only customize call headers in this slice."
  []
  (fn [_result _opts]
    nil))

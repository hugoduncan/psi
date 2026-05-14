(ns psi.tool-registry.render
  "Shared tool-display helpers for canonical tool-definition render hooks."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]))

(defn parse-tool-args
  [args]
  (cond
    (map? args) args
    (string? args) (try (json/parse-string args) (catch Exception _ nil))
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

(defn transport-call-summary
  "Return a transport-safe shared tool call summary string from tool-name/args/details.
   Uses the canonical built-in header semantics for migrated built-ins and nil for
   tools that should fall back to frontend-generic rendering."
  [tool-name args details]
  (builtin-call-header tool-name (parse-tool-args args) details))

(defn transport-progress-event
  "Project transport-safe shared display data into a progress event for RPC use.
   Adds `:call-summary` for current tool lifecycle payloads when derivable."
  [progress-event]
  (let [tool-name (:tool-name progress-event)
        args      (or (:parsed-args progress-event) (:arguments progress-event))
        details   (:details progress-event)]
    (cond-> progress-event
      (and (string? tool-name)
           (some? (transport-call-summary tool-name args details)))
      (assoc :call-summary (transport-call-summary tool-name args details)))))

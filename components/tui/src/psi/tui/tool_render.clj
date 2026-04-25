(ns psi.tui.tool-render
  (:require
   [charm.style.core :as charm-style]
   [cheshire.core :as json]
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [psi.tui.ansi :as ansi]))

(def ^:private tool-style     (charm-style/style :fg charm-style/yellow :bold true))
(def ^:private tool-ok-style  (charm-style/style :fg charm-style/green))
(def ^:private tool-err-style (charm-style/style :fg charm-style/red))
(def ^:private tool-dim-style (charm-style/style :fg 245))
(def ^:private dim-style      (charm-style/style :fg 240))

(defn- parse-tool-args
  [parsed-args args-str]
  (or parsed-args
      (try (json/parse-string args-str)
           (catch Exception _ nil))))

(defn- tool-arg-get
  "Return first non-nil value from args map for the given key variants."
  [args & keys]
  (when (map? args)
    (some #(get args %) keys)))

(defn- tool-int
  [v]
  (cond
    (integer? v) v
    (number? v)  (long v)
    (string? v)  (try (Long/parseLong v) (catch Exception _ nil))
    :else        nil))

(defn- tool-line-range-suffix
  "Return optional :line or :start:end suffix string for read/edit tools."
  [tool-name args details]
  (case tool-name
    "read"
    (let [offset* (tool-int (tool-arg-get args "offset" :offset))
          limit*  (tool-int (tool-arg-get args "limit"  :limit))
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

(defn- tool-header
  "Build the single-line header summary for a tool row.

  Format: <display-name> <primary-arg><line-range-suffix>
  Matches the Emacs psi-emacs--tool-summary format."
  [tool-name parsed-args args-str details]
  (let [args         (parse-tool-args parsed-args args-str)
        display-name (case tool-name
                       "bash"  "$"
                       tool-name)
        primary      (case tool-name
                       ("read" "edit" "write") (tool-arg-get args "path" :path)
                       "bash"                  (tool-arg-get args "command" :command)
                       nil)
        line-suffix  (tool-line-range-suffix tool-name args details)
        label        (cond
                       (seq primary) (str primary line-suffix)
                       :else         "…")]
    (str (charm-style/render tool-style display-name) " " label)))

(defn- tool-status-indicator
  [status spinner-char]
  (case status
    :pending (str spinner-char)
    :running (str spinner-char)
    :success (charm-style/render tool-ok-style "✓")
    :error   (charm-style/render tool-err-style "✗")
    ""))

(defn- wrap-tool-result-line
  [line avail]
  (if (or (nil? avail) (<= (ansi/visible-width line) avail))
    [line]
    (ansi/word-wrap-ansi line avail)))

(defn- content-block->text
  [block]
  (let [t (:type block)
        t-label (cond
                  (keyword? t) (name t)
                  (string? t)  t
                  :else        "unknown")]
    (case t
      :text  (:text block)
      :image (str "[image " (or (:mime-type block) "unknown") "]")
      (str "[unsupported content block: " t-label "]"))))

(defn- tool-content->text
  [content]
  (when (seq content)
    (str/join "\n" (map content-block->text content))))

(defn- tool-all-lines
  "Return all lines of tool result text for expanded display."
  [_tool-name text]
  (when (and text (not (str/blank? text)))
    (str/split-lines text)))

(defn- detail-warning-lines
  [details]
  (let [truncation (or (:truncation details)
                       (get details "truncation"))
        full-output-path (or (:full-output-path details)
                             (:fullOutputPath details)
                             (get details "full-output-path")
                             (get details "fullOutputPath"))
        entry-limit (or (:entry-limit-reached details)
                        (:entryLimitReached details)
                        (get details "entry-limit-reached")
                        (get details "entryLimitReached"))
        result-limit (or (:result-limit-reached details)
                         (:resultLimitReached details)
                         (get details "result-limit-reached")
                         (get details "resultLimitReached"))
        match-limit (or (:match-limit-reached details)
                        (:matchLimitReached details)
                        (get details "match-limit-reached")
                        (get details "matchLimitReached"))
        lines-truncated? (boolean (or (:lines-truncated details)
                                      (:linesTruncated details)
                                      (get details "lines-truncated")
                                      (get details "linesTruncated")))]
    (cond-> []
      (and (map? truncation) (:truncated truncation))
      (conj (str "Truncated output"
                 (when-let [by (:truncated-by truncation)]
                   (str " (" by ")"))))

      full-output-path
      (conj (str "Full output: " full-output-path))

      entry-limit
      (conj (str "Entry limit reached: " entry-limit))

      result-limit
      (conj (str "Result limit reached: " result-limit))

      match-limit
      (conj (str "Match limit reached: " match-limit))

      lines-truncated?
      (conj "Long lines truncated"))))

(defn- extension-call-render
  [ui-snapshot tc]
  (when-let [render-fn (some-> (get-in ui-snapshot [:tool-renderers (:name tc)])
                               :render-call-fn)]
    (try
      (some-> (render-fn (parse-tool-args (:parsed-args tc) (:args tc))) str)
      (catch Exception e
        (timbre/warn "Tool call renderer failed for" (:name tc) "- falling back:" (ex-message e))
        nil))))

(defn- extension-result-render
  [ui-snapshot tc opts]
  (when-let [render-fn (some-> (get-in ui-snapshot [:tool-renderers (:name tc)])
                               :render-result-fn)]
    (try
      (some-> (render-fn tc opts) str)
      (catch Exception e
        (timbre/warn "Tool result renderer failed for" (:name tc) "- falling back:" (ex-message e))
        nil))))

(defn- render-prefixed-lines
  [lines avail style]
  (mapcat (fn [line]
            (let [wrapped (wrap-tool-result-line line avail)]
              (map #(str "    " (charm-style/render style %)) wrapped)))
          lines))

(defn render-tool-calls
  [tool-calls tool-order spinner-char width tools-expanded? ui-snapshot]
  (when (seq tool-order)
    (let [header-avail (when (and width (> width 4)) (- width 4))
          result-avail (when (and width (> width 4)) (- width 4))]
      (str/join
       "\n"
       (for [id tool-order
             :let [tc (get tool-calls id)]
             :when tc]
         (let [expanded?     (boolean tools-expanded?)
               status-icon   (tool-status-indicator (:status tc) spinner-char)
               call-render   (extension-call-render ui-snapshot tc)
               header        (if (seq call-render)
                               call-render
                               (tool-header (:name tc) (:parsed-args tc) (:args tc) (:details tc)))
               header        (if header-avail
                               (ansi/truncate-to-width header header-avail)
                               header)
               ;; Collapsed (default): header only — no content preview.
               ;; Expanded (ctrl+o):   full content with warning lines.
               body-lines    (when expanded?
                               (let [raw-result    (or (:result tc) (tool-content->text (:content tc)))
                                     result-render (extension-result-render ui-snapshot tc
                                                                            {:expanded? true
                                                                             :width width
                                                                             :tool-id id
                                                                             :tool-name (:name tc)})]
                                 (if (seq result-render)
                                   (render-prefixed-lines (str/split-lines result-render)
                                                          result-avail
                                                          (if (:is-error tc) tool-err-style tool-dim-style))
                                   (let [lines         (or (tool-all-lines (:name tc) raw-result) [])
                                         warning-lines (detail-warning-lines (:details tc))
                                         result-style  (if (:is-error tc) tool-err-style tool-dim-style)]
                                     (concat (render-prefixed-lines lines result-avail result-style)
                                             (render-prefixed-lines warning-lines result-avail dim-style))))))]
           (str "  " status-icon " " header
                (when (seq body-lines)
                  (str "\n" (str/join "\n" body-lines))))))))))

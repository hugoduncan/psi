(ns psi.agent-session.tool-output
  "Shared output-policy resolution, truncation helpers, and process temp store.

   Output Policy:
   - default-max-lines (1000) and default-max-bytes (25600)
   - Per-tool overrides via {:tool-name {:max-lines N :max-bytes N}}
   - effective-policy merges overrides over defaults

   Truncation:
   - head-truncate: keeps first N lines/bytes (used by read, eql_query, ls, find, grep)
   - tail-truncate: keeps last N lines/bytes (used by bash)

   Temp Store:
   - Process-wide temp directory for truncated full-output artifacts
   - Init once, cleanup on orderly shutdown (warning-only on failure)"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [taoensso.timbre :as timbre])
  (:import
   [java.io File]
   [java.nio.file Files]
   [java.time Instant]))

;;; Policy defaults

(def default-max-lines
  "Default maximum lines for tool output."
  1000)

(def default-max-bytes
  "Default maximum bytes for tool output."
  25600)

(defn effective-policy
  "Resolve effective output policy for a tool.
   Merges tool-specific overrides over defaults; nil override fields
   fall through to defaults.

   Returns {:max-lines N :max-bytes N}."
  [overrides-map tool-name]
  (let [defaults  {:max-lines default-max-lines
                   :max-bytes default-max-bytes}
        overrides (get overrides-map tool-name)]
    (if overrides
      (merge defaults (into {} (filter (comp some? val)) overrides))
      defaults)))

;;; Truncation helpers

(defn head-truncate
  "Truncate text keeping the first N lines and/or bytes.

   Returns a map with:
   - :content        — the (possibly truncated) string
   - :truncated      — boolean
   - :truncated-by   — :lines, :bytes, or :none
   - :total-lines    — total line count of input
   - :total-bytes    — total byte count of input
   - :output-lines   — line count of output
   - :output-bytes   — byte count of output
   - :max-lines      — the limit applied
   - :max-bytes      — the limit applied
   - :first-line-exceeds-limit — true if the first line alone exceeds max-bytes"
  [text {:keys [max-lines max-bytes]}]
  (let [total-bytes (count (.getBytes (str text) "UTF-8"))
        lines       (str/split-lines (str text))
        total-lines (count lines)]
    (if (and (<= total-lines max-lines)
             (<= total-bytes max-bytes))
      ;; Within limits
      {:content                 text
       :truncated               false
       :truncated-by            :none
       :total-lines             total-lines
       :total-bytes             total-bytes
       :output-lines            total-lines
       :output-bytes            total-bytes
       :max-lines               max-lines
       :max-bytes               max-bytes
       :first-line-exceeds-limit false}
      ;; Need truncation — apply line limit first, then byte limit
      (let [line-limited    (take max-lines lines)
            line-joined     (str/join "\n" line-limited)
            line-joined-bytes (count (.getBytes line-joined "UTF-8"))
            truncated-by-lines (> total-lines max-lines)]
        (if (<= line-joined-bytes max-bytes)
          ;; Line-limited but within byte limit
          {:content                 line-joined
           :truncated               truncated-by-lines
           :truncated-by            (if truncated-by-lines :lines :none)
           :total-lines             total-lines
           :total-bytes             total-bytes
           :output-lines            (count line-limited)
           :output-bytes            line-joined-bytes
           :max-lines               max-lines
           :max-bytes               max-bytes
           :first-line-exceeds-limit false}
          ;; Need byte truncation
          (let [first-line       (first lines)
                first-line-bytes (count (.getBytes (str first-line) "UTF-8"))]
            (if (> first-line-bytes max-bytes)
              ;; First line alone exceeds byte limit
              {:content                 ""
               :truncated               true
               :truncated-by            :bytes
               :total-lines             total-lines
               :total-bytes             total-bytes
               :output-lines            0
               :output-bytes            0
               :max-lines               max-lines
               :max-bytes               max-bytes
               :first-line-exceeds-limit true}
              ;; Accumulate lines until byte limit
              (loop [acc-lines  []
                     acc-bytes  0
                     remaining  (seq line-limited)]
                (if-not remaining
                  (let [content (str/join "\n" acc-lines)]
                    {:content                 content
                     :truncated               truncated-by-lines
                     :truncated-by            (if truncated-by-lines :lines :bytes)
                     :total-lines             total-lines
                     :total-bytes             total-bytes
                     :output-lines            (count acc-lines)
                     :output-bytes            (count (.getBytes content "UTF-8"))
                     :max-lines               max-lines
                     :max-bytes               max-bytes
                     :first-line-exceeds-limit false})
                  (let [line       (first remaining)
                        line-bytes (count (.getBytes line "UTF-8"))
                        sep-bytes  (if (seq acc-lines) 1 0) ; newline separator
                        new-bytes  (+ acc-bytes sep-bytes line-bytes)]
                    (if (> new-bytes max-bytes)
                      ;; This line would exceed byte limit
                      (let [content (str/join "\n" acc-lines)]
                        {:content                 content
                         :truncated               true
                         :truncated-by            :bytes
                         :total-lines             total-lines
                         :total-bytes             total-bytes
                         :output-lines            (count acc-lines)
                         :output-bytes            (count (.getBytes content "UTF-8"))
                         :max-lines               max-lines
                         :max-bytes               max-bytes
                         :first-line-exceeds-limit false})
                      (recur (conj acc-lines line)
                             new-bytes
                             (next remaining)))))))))))))

(defn tail-truncate
  "Truncate text keeping the last N lines and/or bytes.

   Returns a map with:
   - :content           — the (possibly truncated) string
   - :truncated         — boolean
   - :truncated-by      — :lines, :bytes, or :none
   - :total-lines       — total line count of input
   - :total-bytes       — total byte count of input
   - :output-lines      — line count of output
   - :output-bytes      — byte count of output
   - :max-lines         — the limit applied
   - :max-bytes         — the limit applied
   - :last-line-partial — true if the last kept line was cut by byte limit"
  [text {:keys [max-lines max-bytes]}]
  (let [total-bytes (count (.getBytes (str text) "UTF-8"))
        lines       (str/split-lines (str text))
        total-lines (count lines)]
    (if (and (<= total-lines max-lines)
             (<= total-bytes max-bytes))
      ;; Within limits
      {:content            text
       :truncated          false
       :truncated-by       :none
       :total-lines        total-lines
       :total-bytes        total-bytes
       :output-lines       total-lines
       :output-bytes       total-bytes
       :max-lines          max-lines
       :max-bytes          max-bytes
       :last-line-partial  false}
      ;; Need truncation — take last N lines first, then byte limit
      (let [line-limited      (vec (take-last max-lines lines))
            line-joined       (str/join "\n" line-limited)
            line-joined-bytes (count (.getBytes line-joined "UTF-8"))
            truncated-by-lines (> total-lines max-lines)]
        (if (<= line-joined-bytes max-bytes)
          ;; Line-limited but within byte limit
          {:content            line-joined
           :truncated          truncated-by-lines
           :truncated-by       (if truncated-by-lines :lines :none)
           :total-lines        total-lines
           :total-bytes        total-bytes
           :output-lines       (count line-limited)
           :output-bytes       line-joined-bytes
           :max-lines          max-lines
           :max-bytes          max-bytes
           :last-line-partial  false}
          ;; Need byte truncation — accumulate from the end
          (loop [acc-lines  '()
                 acc-bytes  0
                 remaining  (reverse line-limited)]
            (if-not (seq remaining)
              ;; Exhausted lines but still over byte limit shouldn't happen,
              ;; but handle gracefully
              (let [content (str/join "\n" acc-lines)]
                {:content            content
                 :truncated          true
                 :truncated-by       :bytes
                 :total-lines        total-lines
                 :total-bytes        total-bytes
                 :output-lines       (count acc-lines)
                 :output-bytes       (count (.getBytes content "UTF-8"))
                 :max-lines          max-lines
                 :max-bytes          max-bytes
                 :last-line-partial  false})
              (let [line       (first remaining)
                    line-bytes (count (.getBytes line "UTF-8"))
                    sep-bytes  (if (seq acc-lines) 1 0)
                    new-bytes  (+ acc-bytes sep-bytes line-bytes)]
                (if (> new-bytes max-bytes)
                  ;; This line would exceed byte limit — stop
                  (let [content (str/join "\n" acc-lines)]
                    {:content            content
                     :truncated          true
                     :truncated-by       :bytes
                     :total-lines        total-lines
                     :total-bytes        total-bytes
                     :output-lines       (count acc-lines)
                     :output-bytes       (count (.getBytes content "UTF-8"))
                     :max-lines          max-lines
                     :max-bytes          max-bytes
                     :last-line-partial  false})
                  (recur (cons line acc-lines)
                         new-bytes
                         (rest remaining)))))))))))

;;; Process temp store

(defonce ^:private temp-store-atom
  (atom nil))

(defn init-temp-store!
  "Create the process-wide temp directory for truncated output artifacts.
   Idempotent — returns the existing root if already initialized.
   Returns the root directory path as a string."
  []
  (if-let [existing @temp-store-atom]
    (.getAbsolutePath ^File (:root-dir existing))
    (let [root-path (Files/createTempDirectory
                     "psi-tool-output-"
                     (into-array java.nio.file.attribute.FileAttribute []))
          root-file (.toFile root-path)]
      (reset! temp-store-atom {:root-dir   root-file
                               :created-at (Instant/now)})
      (.getAbsolutePath root-file))))

(defn persist-truncated-output!
  "Write full (un-truncated) output to a file in the temp store.
   Initializes the temp store if needed.
   Returns the absolute path of the written file as a string."
  [tool-name tool-call-id full-text]
  (let [_         (init-temp-store!)
        root-dir  (:root-dir @temp-store-atom)
        filename  (str tool-name "-" tool-call-id ".log")
        out-file  (io/file root-dir filename)]
    (spit out-file full-text)
    (.getAbsolutePath out-file)))

(defn cleanup-temp-store!
  "Recursively delete the temp store directory.
   Catches exceptions and logs a warning — never blocks shutdown.
   Returns true if cleanup succeeded, false if it failed or no store existed."
  []
  (if-let [{:keys [root-dir]} @temp-store-atom]
    (try
      (doseq [f (reverse (file-seq root-dir))]
        (.delete ^File f))
      (reset! temp-store-atom nil)
      true
      (catch Exception e
        (timbre/warn "Temp store cleanup failed:" (ex-message e))
        (reset! temp-store-atom nil)
        false))
    false))

(defn temp-store-root
  "Returns the current temp store root directory path, or nil if not initialized."
  []
  (when-let [{:keys [root-dir]} @temp-store-atom]
    (.getAbsolutePath ^File root-dir)))

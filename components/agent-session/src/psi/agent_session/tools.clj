(ns psi.agent-session.tools
  "Built-in tool implementations: read, bash, edit, write.

   Each tool returns {:content string :is-error boolean}.
   Errors throw ex-info so the executor can catch and report them."
  (:require
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.tool-output :as tool-output]
   [psi.agent-session.tool-path :as tool-path])
  (:import
   [java.awt.geom AffineTransform]
   [java.awt.image AffineTransformOp BufferedImage]
   [java.io ByteArrayOutputStream File FileInputStream]
   [java.util Base64]
   [javax.imageio ImageIO]))

;; ============================================================
;; Tool schemas (for agent registration)
;; ============================================================

(def read-tool
  {:name        "read"
   :label       "Read"
   :description "Read the contents of a file. Returns the file text."
   :parameters  (pr-str {:type       "object"
                         :properties {:path   {:type "string" :description "File path to read"}
                                      :offset {:type "integer" :description "1-indexed line number to start reading from"}
                                      :limit  {:type "integer" :description "Maximum number of lines to read from offset"}}
                         :required   ["path"]})})

(def bash-tool
  {:name        "bash"
   :label       "Bash"
   :description "Execute a bash command. Returns stdout and stderr combined."
   :parameters  (pr-str {:type       "object"
                         :properties {:command {:type "string" :description "Bash command to run"}
                                      :timeout {:type "integer" :description "Timeout in seconds (default 30)"}}
                         :required   ["command"]})})

(def edit-tool
  {:name        "edit"
   :label       "Edit"
   :description "Replace exact text in a file. oldText must match exactly."
   :parameters  (pr-str {:type       "object"
                         :properties {:path    {:type "string" :description "File path"}
                                      :oldText {:type "string" :description "Exact text to find"}
                                      :newText {:type "string" :description "Replacement text"}}
                         :required   ["path" "oldText" "newText"]})})

(def write-tool
  {:name        "write"
   :label       "Write"
   :description "Write content to a file, creating it if it does not exist."
   :parameters  (pr-str {:type       "object"
                         :properties {:path    {:type "string" :description "File path"}
                                      :content {:type "string" :description "Content to write"}}
                         :required   ["path" "content"]})})

(def eql-query-tool
  {:name        "eql_query"
   :label       "EQL Query"
   :description "Execute an EQL query against the live session graph. Returns session state, tool info, extension status, and more. Input is an EDN vector, e.g. [:psi.agent-session/phase :psi.agent-session/model]"
   :parameters  (pr-str {:type       "object"
                         :properties {:query {:type "string" :description "EQL query vector as EDN string, e.g. \"[:psi.agent-session/phase :psi.agent-session/session-id]\""}}
                         :required   ["query"]})})

(def all-tool-schemas
  [read-tool bash-tool edit-tool write-tool eql-query-tool])

;; ============================================================
;; Tool implementations
;; ============================================================

(defn- resolve-path
  "Resolve a path against an optional cwd. Delegates to tool-path for
   normalization (strip @, unicode spaces, tilde expansion) and cwd resolution."
  ^java.io.File [cwd path]
  (let [expanded (tool-path/expand-path (str path))]
    (tool-path/resolve-to-cwd cwd expanded)))

;;; File type detection

(def ^:private file-type-sniff-bytes
  "Number of bytes to read for file type detection."
  4100)

(def ^:private supported-image-mimes
  "Set of MIME types we handle as image attachments."
  #{"image/jpeg" "image/png" "image/gif" "image/webp"})

(defn- bytes-start-with?
  "Check if byte array starts with the given byte sequence."
  [^bytes buf ^bytes sig]
  (when (>= (alength buf) (alength sig))
    (loop [i 0]
      (if (>= i (alength sig))
        true
        (if (= (aget buf i) (aget sig i))
          (recur (inc i))
          false)))))

(defn detect-mime
  "Detect MIME type from magic bytes. Returns MIME string or nil.
   Checks JPEG (FF D8 FF), PNG (89 50 4E 47), GIF (47 49 46 38),
   WebP (52 49 46 46 ... 57 45 42 50)."
  [^bytes buf]
  (when (and buf (pos? (alength buf)))
    (cond
      ;; JPEG: FF D8 FF
      (bytes-start-with? buf (byte-array [(unchecked-byte 0xFF)
                                          (unchecked-byte 0xD8)
                                          (unchecked-byte 0xFF)]))
      "image/jpeg"

      ;; PNG: 89 50 4E 47
      (bytes-start-with? buf (byte-array [(unchecked-byte 0x89)
                                          (byte 0x50)
                                          (byte 0x4E)
                                          (byte 0x47)]))
      "image/png"

      ;; GIF: 47 49 46 38
      (bytes-start-with? buf (byte-array [(byte 0x47)
                                          (byte 0x49)
                                          (byte 0x46)
                                          (byte 0x38)]))
      "image/gif"

      ;; WebP: RIFF....WEBP (bytes 0-3 = RIFF, bytes 8-11 = WEBP)
      (and (>= (alength buf) 12)
           (bytes-start-with? buf (byte-array [(byte 0x52) (byte 0x49)
                                               (byte 0x46) (byte 0x46)]))
           (= (aget buf 8) (byte 0x57))
           (= (aget buf 9) (byte 0x45))
           (= (aget buf 10) (byte 0x42))
           (= (aget buf 11) (byte 0x50)))
      "image/webp"

      :else nil)))

(defn- binary-file?
  "Check if byte array contains null bytes, indicating a binary file."
  [^bytes buf]
  (let [len (alength buf)]
    (loop [i 0]
      (if (>= i len)
        false
        (if (zero? (aget buf i))
          true
          (recur (inc i)))))))

(defn- read-file-prefix
  "Read up to n bytes from the start of a file. Returns byte array."
  [^File f n]
  (with-open [fis (FileInputStream. f)]
    (let [buf (byte-array n)
          read-count (.read fis buf)]
      (if (< read-count n)
        (java.util.Arrays/copyOf buf (max 0 read-count))
        buf))))

;;; Image handling

(def ^:private auto-resize-max-dim
  "Maximum width or height for auto-resized images."
  2000)

(defn- resize-image
  "Resize an image if wider or taller than max-dim.
   Returns [base64-string mime-type]. Input is raw file bytes and detected mime."
  [^bytes file-bytes ^String mime]
  (let [bais   (java.io.ByteArrayInputStream. file-bytes)
        img    (ImageIO/read bais)
        w      (.getWidth img)
        h      (.getHeight img)]
    (if (and (<= w auto-resize-max-dim) (<= h auto-resize-max-dim))
      ;; No resize needed
      [(.encodeToString (Base64/getEncoder) file-bytes) mime]
      ;; Resize maintaining aspect ratio
      (let [scale   (min (/ (double auto-resize-max-dim) w)
                         (/ (double auto-resize-max-dim) h))
            new-w   (int (* w scale))
            new-h   (int (* h scale))
            tx      (AffineTransform/getScaleInstance scale scale)
            op      (AffineTransformOp. tx AffineTransformOp/TYPE_BILINEAR)
            dest    (BufferedImage. new-w new-h (.getType img))
            _       (.filter op img dest)
            baos    (ByteArrayOutputStream.)
            ;; Write as PNG for lossless resized output
            format  (case mime
                      "image/jpeg" "jpg"
                      "image/png"  "png"
                      "image/gif"  "png"
                      "image/webp" "png"
                      "png")
            out-mime (case format
                       "jpg" "image/jpeg"
                       "png" "image/png")
            _       (ImageIO/write dest format baos)]
        [(.encodeToString (Base64/getEncoder) (.toByteArray baos)) out-mime]))))

(defn- read-image-file
  "Read an image file and return content blocks with base64 data."
  [^File f ^String mime {:keys [auto-resize-images]
                         :or   {auto-resize-images true}}]
  (let [file-bytes (java.nio.file.Files/readAllBytes (.toPath f))]
    (if auto-resize-images
      (let [[b64 out-mime] (resize-image file-bytes mime)]
        {:content  [{:type "text" :text (str "Read image file [" out-mime "]")}
                    {:type "image" :data b64 :mimeType out-mime}]
         :is-error false
         :details  nil})
      (let [b64 (.encodeToString (Base64/getEncoder) file-bytes)]
        {:content  [{:type "text" :text (str "Read image file [" mime "]")}
                    {:type "image" :data b64 :mimeType mime}]
         :is-error false
         :details  nil}))))

(defn- read-binary-file
  "Return a warning-only result for non-image binary files."
  [^File f]
  {:content  (str "Binary file detected: " (.getAbsolutePath f) ". Content omitted.")
   :is-error false
   :details  {:binary-file-detected true
              :truncation           nil}})

;;; Text file reading with offset/limit/truncation

(defn- read-text-file
  "Read a text file with optional offset/limit and head truncation.
   offset is 1-indexed. Returns spec-compliant result map."
  [^File f offset limit {:keys [overrides]}]
  (let [content    (slurp f)
        all-lines  (str/split-lines content)
        total-lines (count all-lines)
        start-idx  (max 0 (dec (or offset 1)))
        start-display (inc start-idx)]
    ;; Validate offset
    (when (and offset (>= start-idx total-lines))
      (throw (ex-info (str "Offset " offset " is beyond end of file ("
                           total-lines " lines total)")
                      {:offset offset :total-lines total-lines})))
    ;; Select lines
    (let [end-idx      (if limit
                         (min (+ start-idx limit) total-lines)
                         total-lines)
          selected     (subvec (vec all-lines) start-idx end-idx)
          selected-text (str/join "\n" selected)
          policy       (tool-output/effective-policy (or overrides {}) "read")
          truncation   (tool-output/head-truncate selected-text policy)]
      (cond
        ;; First line exceeds byte limit
        (:first-line-exceeds-limit truncation)
        {:content  (str "[Line " start-display " exceeds "
                        (:max-bytes truncation) " bytes. Use bash for a bounded slice.]")
         :is-error false
         :details  {:truncation          truncation
                    :binary-file-detected false}}

        ;; Truncated by policy
        (:truncated truncation)
        (let [shown-end (+ start-idx (:output-lines truncation))
              guidance  (str "\n\n--- Showing lines " start-display "-" shown-end
                             " of " total-lines " total. Use offset="
                             (inc shown-end) " to continue.")]
          {:content  (str (:content truncation) guidance)
           :is-error false
           :details  {:truncation          truncation
                      :binary-file-detected false}})

        ;; Not truncated but limit was used and more lines exist
        (and limit (< end-idx total-lines))
        (let [remaining (- total-lines end-idx)
              guidance  (str "\n\n--- " remaining " more lines in file. Use offset="
                             (inc end-idx) " to continue.")]
          {:content  (str (:content truncation) guidance)
           :is-error false
           :details  {:truncation          truncation
                      :binary-file-detected false}})

        ;; Full content, no truncation
        :else
        {:content  (:content truncation)
         :is-error false
         :details  {:truncation          truncation
                    :binary-file-detected false}}))))

(defn- slurp-file
  ([path] (slurp-file nil path))
  ([cwd path]
   (let [f (resolve-path cwd path)]
     (when-not (.exists f)
       (throw (ex-info (str "File not found: " (.getPath f)) {:path (.getPath f)})))
     (slurp f))))

(defn execute-read
  "Read a file and return its contents.
   Supports binary safety (magic-byte detection), image attachments,
   offset/limit line slicing, and head truncation per output policy.

   Accepts optional :cwd in opts to resolve relative paths.
   Accepts optional :overrides in opts for output policy overrides.
   Accepts optional :auto-resize-images in opts (default true)."
  ([args] (execute-read args nil))
  ([{:strs [path offset limit]} {:keys [cwd] :as opts}]
   (let [f (tool-path/resolve-read-path (str path) cwd)]
     ;; Check file exists
     (when-not (.exists f)
       (throw (ex-info (str "File not found: " (.getAbsolutePath f))
                       {:path (.getAbsolutePath f)})))
     ;; Sniff file type
     (let [prefix (read-file-prefix f file-type-sniff-bytes)
           mime   (detect-mime prefix)]
       (cond
         ;; Supported image — return as attachment
         (supported-image-mimes mime)
         (read-image-file f mime opts)

         ;; Non-image binary — warning only
         (binary-file? prefix)
         (read-binary-file f)

         ;; Text file — offset/limit/truncation
         :else
         (read-text-file f offset limit opts))))))

(defn execute-bash
  "Run a shell command via babashka.process, returning combined stdout+stderr.
   Stdin is bound to /dev/null so tools like rg don't misdetect a readable
   pipe and search stdin instead of the working directory.
   Accepts optional :cwd in opts to set the working directory."
  ([args] (execute-bash args nil))
  ([{:strs [command]} {:keys [cwd]}]
   (let [result (proc/shell (cond-> {:out      :string
                                     :err      :string
                                     :continue true
                                     :in       (java.io.File. "/dev/null")}
                              cwd (assoc :dir cwd))
                            "bash" "-c" command)
         out    (str (:out result) (:err result))]
     {:content  (if (str/blank? out) "[no output]" out)
      :is-error (not= 0 (:exit result))})))

(defn execute-edit
  "Replace oldText with newText in a file.
   Accepts optional :cwd in opts to resolve relative paths."
  ([args] (execute-edit args nil))
  ([{:strs [path oldText newText]} {:keys [cwd]}]
   (let [f       (resolve-path cwd path)
         fpath   (.getPath f)
         content (slurp-file cwd path)]
     (when-not (str/includes? content oldText)
       (throw (ex-info "oldText not found in file"
                       {:path fpath :oldText (subs oldText 0 (min 80 (count oldText)))})))
     (let [updated (str/replace-first content oldText newText)]
       (spit f updated)
       {:content  (str "Edited " fpath)
        :is-error false}))))

(defn execute-write
  "Write content to a file (creates parent dirs if needed).
   Accepts optional :cwd in opts to resolve relative paths."
  ([args] (execute-write args nil))
  ([{:strs [path content]} {:keys [cwd]}]
   (let [f     (resolve-path cwd path)
         fpath (.getPath f)]
     (io/make-parents f)
     (spit f content)
     {:content  (str "Wrote " fpath)
      :is-error false})))

(defn make-eql-query-tool
  "Create an eql_query tool with an :execute fn that closes over `query-fn`.
   `query-fn` should be (fn [eql-query-vec] -> result-map), typically
   `(partial resolvers/query-in ctx)` or `(fn [q] (session/query-in ctx q))`."
  [query-fn]
  (assoc eql-query-tool
         :execute
         (fn [{:strs [query]}]
           (try
             (let [q (binding [*read-eval* false]
                       (read-string query))]
               (when-not (vector? q)
                 (throw (ex-info "Query must be an EDN vector" {:input query})))
               (let [result (query-fn q)]
                 {:content  (pr-str result)
                  :is-error false}))
             (catch Exception e
               {:content  (str "EQL query error: " (ex-message e))
                :is-error true})))))

(def all-tools
  "Built-in tool definitions including execution fns.
   Use this when registering tools into agent state.
   Note: eql_query is excluded — it requires a session context.
   Use `make-eql-query-tool` to create it with a query-fn."
  [{:name        (:name read-tool)
    :label       (:label read-tool)
    :description (:description read-tool)
    :parameters  (:parameters read-tool)
    :execute     execute-read}
   {:name        (:name bash-tool)
    :label       (:label bash-tool)
    :description (:description bash-tool)
    :parameters  (:parameters bash-tool)
    :execute     execute-bash}
   {:name        (:name edit-tool)
    :label       (:label edit-tool)
    :description (:description edit-tool)
    :parameters  (:parameters edit-tool)
    :execute     execute-edit}
   {:name        (:name write-tool)
    :label       (:label write-tool)
    :description (:description write-tool)
    :parameters  (:parameters write-tool)
    :execute     execute-write}])

;; ============================================================
;; CWD-scoped tools
;; ============================================================

(defn make-tools-with-cwd
  "Return the four standard tool maps (read, bash, edit, write) with :execute
   fns that resolve relative paths and run commands in `cwd`.

   This is the preferred way for extensions/sub-agents to get tools scoped
   to a specific working directory without redefining tool wrappers."
  [cwd]
  (let [opts {:cwd cwd}]
    [{:name        (:name read-tool)
      :label       (:label read-tool)
      :description (:description read-tool)
      :parameters  (:parameters read-tool)
      :execute     (fn [args] (execute-read args opts))}
     {:name        (:name bash-tool)
      :label       (:label bash-tool)
      :description (:description bash-tool)
      :parameters  (:parameters bash-tool)
      :execute     (fn [args] (execute-bash args opts))}
     {:name        (:name edit-tool)
      :label       (:label edit-tool)
      :description (:description edit-tool)
      :parameters  (:parameters edit-tool)
      :execute     (fn [args] (execute-edit args opts))}
     {:name        (:name write-tool)
      :label       (:label write-tool)
      :description (:description write-tool)
      :parameters  (:parameters write-tool)
      :execute     (fn [args] (execute-write args opts))}]))

;; ============================================================
;; Dispatch
;; ============================================================

(defn execute-tool
  "Dispatch a tool call by name. Returns {:content string :is-error boolean}.
  Throws ex-info for unknown tools.
  Note: eql_query is not dispatched here — it requires a session context
  and is handled via the tool registry's :execute fn."
  [tool-name args-map]
  (case tool-name
    "read"  (execute-read args-map)
    "bash"  (execute-bash args-map)
    "edit"  (execute-edit args-map)
    "write" (execute-write args-map)
    (throw (ex-info (str "Unknown tool: " tool-name) {:tool tool-name}))))

(ns psi.agent-session.tools
  "Built-in tool implementations: read, bash, edit, write.

   Each tool returns {:content string :is-error boolean}.
   Errors throw ex-info so the executor can catch and report them."
  (:require
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.psi-tool :as psi-tool]
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
   :parameters  {:type       "object"
                 :properties {:path   {:type "string" :description "File path to read"}
                              :offset {:type "integer" :description "1-indexed line number to start reading from"}
                              :limit  {:type "integer" :description "Maximum number of lines to read from offset"}}
                 :required   ["path"]}})

(def bash-tool
  {:name        "bash"
   :label       "Bash"
   :description "Execute a bash command. Returns stdout and stderr combined."
   :parameters  {:type       "object"
                 :properties {:command {:type "string" :description "Bash command to run"}
                              :timeout {:type "integer" :description "Timeout in seconds (default 30)"}}
                 :required   ["command"]}})

(def edit-tool
  {:name        "edit"
   :label       "Edit"
   :description "Replace exact text in a file. oldText must match exactly."
   :parameters  {:type       "object"
                 :properties {:path    {:type "string" :description "File path"}
                              :oldText {:type "string" :description "Exact text to find"}
                              :newText {:type "string" :description "Replacement text"}}
                 :required   ["path" "oldText" "newText"]}})

(def write-tool
  {:name        "write"
   :label       "Write"
   :description "Write content to a file, creating it if it does not exist."
   :parameters  {:type       "object"
                 :properties {:path    {:type "string" :description "File path"}
                              :content {:type "string" :description "Content to write"}}
                 :required   ["path" "content"]}})

(def psi-tool psi-tool/psi-tool)

(def all-tool-schemas
  [read-tool bash-tool edit-tool write-tool psi-tool])

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

(defonce ^:private bash-process-atom
  (atom nil))

(defn abort-bash!
  "Abort the currently running bash process, if any.
   Returns true if a process was aborted, false otherwise."
  []
  (when-let [p @bash-process-atom]
    (try
      (.destroyForcibly ^Process (:proc p))
      true
      (catch Exception _
        false))))

(defn execute-bash
  "Run a shell command via babashka.process, returning combined stdout+stderr.

   Spec-compliant behavior:
   - Stdin bound to /dev/null
   - Tail truncation via effective output policy for \"bash\"
   - Full-output spill file when truncated (fullOutputPath in details)
   - Timeout with process destruction (default 30s)
   - Non-zero exit prefixed with exit code
   - Command prefix support
   - Streaming on-update callback

   Accepts optional opts map:
   - :cwd           — working directory
   - :overrides     — output policy overrides map
   - :command-prefix — prepended to command with newline separator
   - :on-update     — (fn [{:content s :details d}]) called during execution
   - :tool-call-id  — identifier for temp artifact naming"
  ([args] (execute-bash args nil))
  ([{:strs [command timeout]} {:keys [cwd overrides command-prefix on-update tool-call-id]}]
   (let [timeout-secs  (or timeout 30)
         resolved-cmd  (if command-prefix
                         (str command-prefix "\n" command)
                         command)
         policy        (tool-output/effective-policy (or overrides {}) "bash")
         proc-opts     (cond-> {:out      :string
                                :err      :string
                                :in       (java.io.File. "/dev/null")}
                         cwd (assoc :dir cwd))]
     (try
       (let [p      (proc/process proc-opts "bash" "-c" resolved-cmd)
             _      (reset! bash-process-atom {:proc (:proc p)})
             ;; Wait with timeout — IBlockingDeref returns timeout-value on timeout
             result (deref p (* timeout-secs 1000) ::timeout)]
         (reset! bash-process-atom nil)
         (when (= result ::timeout)
           (.destroyForcibly ^Process (:proc p)))
         (if (= result ::timeout)
           ;; Timeout result
           {:content  (str "Command timed out after " timeout-secs " seconds")
            :is-error true
            :details  nil}
           ;; Normal completion
           (let [merged    (str (:out result) (:err result))
                 exit-code (:exit result)
                 non-zero? (not= 0 exit-code)
                 trunc     (tool-output/tail-truncate merged policy)
                 truncated? (:truncated trunc)
                 base-text (if (str/blank? (:content trunc))
                             "(no output)"
                             (:content trunc))
                 ;; Persist full output if truncated
                 spill-path (when truncated?
                              (tool-output/persist-truncated-output!
                               "bash"
                               (or tool-call-id (str (java.util.UUID/randomUUID)))
                               merged))
                 ;; Build content
                 content   (cond-> ""
                             non-zero?
                             (str "Command exited with code " exit-code "\n")

                             true
                             (str base-text)

                             truncated?
                             (str "\n\n... [truncated, " (:total-lines trunc)
                                  " total lines] Full output: " spill-path))
                 result    {:content  content
                            :is-error non-zero?
                            :details  (when truncated?
                                        {:truncation       trunc
                                         :full-output-path spill-path})}]
             (when on-update
               (try
                 (on-update result)
                 (catch Exception _ nil)))
             result)))
       (catch Exception e
         (reset! bash-process-atom nil)
         {:content  (str "Command execution error: " (ex-message e))
          :is-error true
          :details  nil})))))

(defn- normalize-line-endings
  [s]
  (str/replace (or s "") #"\r\n" "\n"))

(defn- detect-line-ending
  [s]
  (if (str/includes? (or s "") "\r\n") "\r\n" "\n"))

(defn- trim-trailing-ws
  [s]
  (str/replace s #"[ \t]+$" ""))

(defn- normalize-smart-punctuation
  [s]
  (-> s
      (str/replace "\u2018" "'")
      (str/replace "\u2019" "'")
      (str/replace "\u201C" "\"")
      (str/replace "\u201D" "\"")
      (str/replace "\u2013" "-")
      (str/replace "\u2014" "-")))

(defn- fuzzy-line
  [s]
  (-> (or s "")
      normalize-smart-punctuation
      trim-trailing-ws))

(defn- split-lines-preserve
  [s]
  (vec (str/split (or s "") #"\n" -1)))

(defn- find-fuzzy-window
  [content old-text]
  (let [content-lines (split-lines-preserve content)
        old-lines     (split-lines-preserve old-text)
        clen          (count content-lines)
        olen          (count old-lines)]
    (when (pos? olen)
      (->> (range 0 (inc (- clen olen)))
           (filter (fn [start]
                     (every?
                      true?
                      (for [i (range olen)]
                        (= (fuzzy-line (nth content-lines (+ start i)))
                           (fuzzy-line (nth old-lines i)))))))
           vec))))

(defn- first-changed-line
  [before after]
  (let [before-lines (split-lines-preserve (normalize-line-endings before))
        after-lines  (split-lines-preserve (normalize-line-endings after))
        max-len      (max (count before-lines) (count after-lines))]
    (some (fn [i]
            (when (not= (nth before-lines i nil) (nth after-lines i nil))
              (inc i)))
          (range max-len))))

(defn- simple-diff
  [before after]
  (let [line (or (first-changed-line before after) 1)]
    (str "--- original\n"
         "+++ updated\n"
         "@@ first-changed-line " line " @@\n")))

(defn execute-edit
  "Replace oldText with newText in a file.
   Accepts optional :cwd in opts to resolve relative paths.
   Preserves BOM and dominant line endings.
   If exact match fails, tries fuzzy matching (smart punctuation + trailing ws)."
  ([args] (execute-edit args nil))
  ([{:strs [path oldText newText]} {:keys [cwd]}]
   (let [f             (resolve-path cwd path)
         fpath         (.getPath f)
         raw-content   (slurp-file cwd path)
         has-bom?      (str/starts-with? raw-content "\uFEFF")
         no-bom        (if has-bom? (subs raw-content 1) raw-content)
         line-ending   (detect-line-ending no-bom)
         content-norm  (normalize-line-endings no-bom)
         old-norm      (normalize-line-endings oldText)
         new-norm      (normalize-line-endings newText)
         exact-index   (str/index-of content-norm old-norm)
         updated-norm  (if (some? exact-index)
                         (str/replace-first content-norm old-norm new-norm)
                         (let [matches (find-fuzzy-window content-norm old-norm)]
                           (cond
                             (empty? matches)
                             (throw (ex-info "oldText not found in file"
                                             {:path fpath :oldText (subs oldText 0 (min 80 (count oldText)))}))

                             (> (count matches) 1)
                             (throw (ex-info "Fuzzy match is ambiguous"
                                             {:path fpath :match-count (count matches)}))

                             :else
                             (let [start         (first matches)
                                   content-lines (split-lines-preserve content-norm)
                                   old-lines     (split-lines-preserve old-norm)
                                   new-lines     (split-lines-preserve new-norm)
                                   prefix        (subvec content-lines 0 start)
                                   suffix        (subvec content-lines (+ start (count old-lines)))
                                   replaced      (concat prefix new-lines suffix)]
                               (str/join "\n" replaced)))))
         updated-out   (-> updated-norm
                           (str/replace "\n" line-ending)
                           (#(if has-bom? (str "\uFEFF" %) %)))
         diff          (simple-diff raw-content updated-out)
         changed-line  (or (first-changed-line raw-content updated-out) 1)]
     (spit f updated-out)
     {:content     (str "Successfully replaced text in " fpath ".")
      :is-error    false
      :details     {:diff diff
                    :first-changed-line changed-line}
      :meta        {:tool-name "edit"}
      :effects     [{:type "file/edit"
                     :path fpath
                     :worktree-path cwd
                     :first-changed-line changed-line}]
      :enrichments []})))

(defn execute-write
  "Write content to a file (creates parent dirs if needed).
   Accepts optional :cwd in opts to resolve relative paths."
  ([args] (execute-write args nil))
  ([{:strs [path content]} {:keys [cwd]}]
   (let [f      (resolve-path cwd path)
         fpath  (.getPath f)
         bytes  (count (.getBytes (or content "") "UTF-8"))]
     (io/make-parents f)
     (spit f content)
     {:content     (str "Successfully wrote " bytes " bytes to " fpath)
      :is-error    false
      :details     nil
      :meta        {:tool-name "write"}
      :effects     [{:type "file/write"
                     :path fpath
                     :worktree-path cwd
                     :bytes bytes}]
      :enrichments []})))

(def make-psi-tool psi-tool/make-psi-tool)

(def all-tools
  "Built-in tool definitions including execution fns.
   Use this when registering tools into agent state.
   Note: psi-tool is excluded — it requires a session context.
   Use `make-psi-tool` to create it with a query-fn."
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

(defn make-read-only-tools-with-cwd
  "Return read-only tools scoped to cwd.
   Backward-compatible helper for callers that only need file reads."
  [cwd]
  (let [opts {:cwd cwd}]
    [{:name        (:name read-tool)
      :label       (:label read-tool)
      :description (:description read-tool)
      :parameters  (:parameters read-tool)
      :execute     (fn [args] (execute-read args opts))}]))

;; ============================================================
;; Dispatch
;; ============================================================

(def built-in-dispatch-tools
  #{"read" "bash" "edit" "write"})

(defn execute-tool
  "Dispatch a tool call by name. Returns {:content string|blocks :is-error boolean}.
  Throws ex-info for unknown tools.
  Note: psi-tool is not dispatched here — it requires a session context
  and is handled via the tool registry's :execute fn."
  ([tool-name args-map]
   (execute-tool tool-name args-map nil))
  ([tool-name args-map opts]
   (case tool-name
     "read"  (execute-read args-map opts)
     "bash"  (execute-bash args-map opts)
     "edit"  (execute-edit args-map opts)
     "write" (execute-write args-map opts)
     (throw (ex-info (str "Unknown tool: " tool-name) {:tool tool-name})))))

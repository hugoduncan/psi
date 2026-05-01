;;; psi-tool-rows.el --- Tool row rendering helpers for psi frontend  -*- lexical-binding: t; -*-

;;; Commentary:
;; Extracted tool row summary/render/update helpers used by psi.el.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'ansi-color)
(require 'json)
(require 'psi-globals)

(declare-function psi-emacs--event-data-get "psi-events" (data keys))
(declare-function psi-emacs--region-bounds "psi-regions" (kind id))
(declare-function psi-emacs--region-register "psi-regions" (kind id start end &optional props))
(declare-function psi-emacs--draft-anchor-at-end-p "psi-compose")
(declare-function psi-emacs--assistant-start-marker "psi-assistant-render")
(declare-function psi-emacs--assistant-end-marker "psi-assistant-render")
(declare-function psi-emacs--ensure-newline-before-append "psi-compose")
(declare-function psi-emacs--set-draft-anchor-to-end "psi-compose")
(declare-function psi-emacs--mark-region-read-only "psi-compose" (start end))
(declare-function psi-emacs--refresh-header-line "psi-run-state")

(defun psi-emacs--string-has-face-p (text)
  "Return non-nil if TEXT has any `face' property."
  (let ((i 0)
        (len (length text))
        (hit nil))
    (while (and (< i len) (not hit))
      (setq hit (get-text-property i 'face text))
      (setq i (1+ i)))
    hit))

(defun psi-emacs--ansi-to-face (text)
  "Return TEXT with ANSI escapes converted to Emacs face properties."
  (let* ((raw (or text ""))
         (had-ansi (string-match-p "\x1b\\[[0-9;]*m" raw))
         (converted (ansi-color-apply raw)))
    (if (and had-ansi
             (not (psi-emacs--string-has-face-p converted))
             (not (string-empty-p converted)))
        (propertize converted 'face 'ansi-color-red)
      converted)))

(defconst psi-emacs--tool-header-max-chars 120
  "Maximum characters shown in a tool row header summary.")

(defun psi-emacs--alist-map-p (value)
  "Return non-nil when VALUE has alist-map shape."
  (and (listp value)
       (cl-every #'consp value)))

(defun psi-emacs--hash-table->alist (table)
  "Convert hash TABLE to an alist."
  (let (out)
    (maphash (lambda (k v)
               (push (cons k v) out))
             table)
    (nreverse out)))

(defun psi-emacs--json-parse-string-safe (json-string)
  "Parse JSON-STRING into an alist map, or nil when unreadable."
  (condition-case _
      (if (fboundp 'json-parse-string)
          (json-parse-string json-string
                             :object-type 'alist
                             :array-type 'list
                             :null-object nil
                             :false-object nil)
        (let ((json-object-type 'alist)
              (json-array-type 'list)
              (json-key-type 'string))
          (json-read-from-string json-string)))
    (error nil)))

(defun psi-emacs--tool-args-map (parsed-args arguments)
  "Return plist describing normalized tool args from PARSED-ARGS/ARGUMENTS.

Result shape:
  :args               alist map or nil
  :invalid-args-type  non-nil when args parse to a non-map value."
  (cond
   ((and (not (null parsed-args))
         (psi-emacs--alist-map-p parsed-args))
    (list :args parsed-args :invalid-args-type nil))

   ((hash-table-p parsed-args)
    (list :args (psi-emacs--hash-table->alist parsed-args)
          :invalid-args-type nil))

   ((null parsed-args)
    (if (and (stringp arguments)
             (not (string-empty-p (string-trim arguments))))
        (let ((parsed (psi-emacs--json-parse-string-safe arguments)))
          (cond
           ((psi-emacs--alist-map-p parsed)
            (list :args parsed :invalid-args-type nil))
           ((hash-table-p parsed)
            (list :args (psi-emacs--hash-table->alist parsed)
                  :invalid-args-type nil))
           ((null parsed)
            ;; Parse failures can occur while arguments are still streaming.
            (list :args nil :invalid-args-type nil))
           (t
            (list :args nil :invalid-args-type t))))
      (list :args nil :invalid-args-type nil)))

   (t
    (list :args nil :invalid-args-type t))))

(defun psi-emacs--tool-arg-get (args keys)
  "Return first non-nil arg value from ARGS for KEYS."
  (when (psi-emacs--alist-map-p args)
    (psi-emacs--event-data-get args keys)))

(defun psi-emacs--tool-display-id (tool-id)
  "Return display-friendly TOOL-ID.

For composite ids of shape `call|item`, keep the call id prefix."
  (if (and (stringp tool-id)
           (string-match-p "|" tool-id))
      (car (split-string tool-id "|" t))
    tool-id))

(defun psi-emacs--single-line (text)
  "Normalize TEXT to one trimmed display line."
  (let ((line (or text "")))
    (setq line (replace-regexp-in-string "[\r\n\t]+" " " line))
    (setq line (replace-regexp-in-string " +" " " line))
    (string-trim line)))

(defun psi-emacs--truncate-single-line (text max-chars)
  "Clamp TEXT to MAX-CHARS with ellipsis when needed."
  (let* ((line (psi-emacs--single-line text))
         (limit (max 1 (or max-chars 1))))
    (if (> (length line) limit)
        (concat (substring line 0 (max 0 (1- limit))) "…")
      line)))

(defun psi-emacs--project-root-directory ()
  "Return canonical project root directory for current buffer, or nil."
  (let* ((base (and (stringp default-directory)
                    (not (string-empty-p default-directory))
                    (expand-file-name default-directory)))
         (project-root
          (when (and base (fboundp 'project-current))
            (let ((proj (ignore-errors (project-current nil base))))
              (cond
               ((and proj (fboundp 'project-root))
                (ignore-errors (project-root proj)))
               ((and proj (fboundp 'project-roots))
                (car (ignore-errors (project-roots proj))))
               (t nil)))))
         (root (or project-root base)))
    (when (and (stringp root)
               (not (string-empty-p root)))
      (file-name-as-directory (expand-file-name root)))))

(defun psi-emacs--display-path-relative-to-project (path-text)
  "Return PATH-TEXT relative to current project root when possible."
  (let ((path (string-trim (or path-text ""))))
    (if (string-empty-p path)
        path
      (if (file-name-absolute-p path)
          (let* ((absolute (expand-file-name path))
                 (project-root (psi-emacs--project-root-directory)))
            (if (and project-root
                     (file-in-directory-p absolute project-root))
                (file-relative-name absolute project-root)
              (abbreviate-file-name absolute)))
        path))))

(defun psi-emacs--tool-display-name (name)
  "Return display label for tool NAME."
  (if (equal name "bash") "$" name))

(defun psi-emacs--tool-primary-text (name primary-value)
  "Return display text for tool NAME primary argument PRIMARY-VALUE."
  (when (and primary-value
             (not (string-empty-p (format "%s" primary-value))))
    (let ((text (psi-emacs--single-line (format "%s" primary-value))))
      (if (member name '("read" "edit" "write"))
          (psi-emacs--display-path-relative-to-project text)
        text))))

(defun psi-emacs--tool-int (value)
  "Return VALUE as integer when possible, otherwise nil."
  (cond
   ((integerp value) value)
   ((numberp value) (truncate value))
   ((stringp value)
    (when (string-match-p "\\`[0-9]+\\'" value)
      (string-to-number value)))
   (t nil)))

(defun psi-emacs--tool-line-range-suffix (tool-name args details)
  "Return optional :line or :start:end suffix for TOOL-NAME.

For read, derive from offset/limit args.
For edit, derive from details.firstChangedLine and oldText span when available."
  (pcase tool-name
    ("read"
     (let* ((offset* (psi-emacs--tool-int
                      (psi-emacs--tool-arg-get args '("offset" :offset offset))))
            (limit* (psi-emacs--tool-int
                     (psi-emacs--tool-arg-get args '("limit" :limit limit))))
            (offset (or offset* (and limit* 1))))
       (cond
        ((and offset limit* (> limit* 0))
         (format ":%d:%d" offset (+ offset (1- limit*))))
        (offset
         (format ":%d" offset))
        (t ""))))
    ("edit"
     (let* ((first-changed* (or (alist-get :firstChangedLine details nil nil #'equal)
                                (alist-get :first-changed-line details nil nil #'equal)
                                (alist-get "firstChangedLine" details nil nil #'equal)
                                (alist-get "first-changed-line" details nil nil #'equal)))
            (first-changed (psi-emacs--tool-int first-changed*))
            (old-text (psi-emacs--tool-arg-get args '("oldText" :oldText oldText)))
            (span (and (stringp old-text)
                       (max 1 (length (split-string old-text "\n" nil))))))
       (cond
        ((and first-changed span (> span 1))
         (format ":%d:%d" first-changed (+ first-changed (1- span))))
        (first-changed
         (format ":%d" first-changed))
        (t ""))))
    (_ "")))

(defun psi-emacs--tool-summary (tool-name parsed-args arguments tool-id &optional details)
  "Return display summary for a tool row.

Prefers TOOL-NAME + key call argument (path/command) over internal TOOL-ID.
For read/edit, appends optional line-number suffix when derivable.
TOOL-ID remains fallback-only when tool name is absent."
  (let* ((name-raw (or tool-name
                       (psi-emacs--tool-display-id tool-id)
                       "tool"))
         (name (if (symbolp name-raw)
                   (symbol-name name-raw)
                 (format "%s" name-raw)))
         (display-name (psi-emacs--tool-display-name name))
         (args-info (psi-emacs--tool-args-map parsed-args arguments))
         (args (plist-get args-info :args))
         (invalid-args? (plist-get args-info :invalid-args-type))
         (known-tool? (member name '("read" "edit" "write" "bash")))
         (primary-value (pcase name
                          ((or "read" "edit" "write")
                           (psi-emacs--tool-arg-get args '("path" :path path)))
                          ("bash"
                           (psi-emacs--tool-arg-get args '("command" :command command)))
                          (_ nil)))
         (primary-text (psi-emacs--tool-primary-text name primary-value))
         (line-range-suffix (if (member name '("read" "edit"))
                                (psi-emacs--tool-line-range-suffix name args details)
                              ""))
         (summary (cond
                   (invalid-args?
                    (format "%s [invalid arg]" display-name))
                   ((and known-tool? primary-text)
                    (format "%s %s%s" display-name primary-text line-range-suffix))
                   (known-tool?
                    (format "%s …" display-name))
                   (t
                    display-name))))
    (psi-emacs--truncate-single-line summary psi-emacs--tool-header-max-chars)))

(defun psi-emacs--tool-status-label (stage is-error)
  "Return normalized display status from lifecycle STAGE and IS-ERROR."
  (cond
   ((equal stage "start") "pending")
   ((member stage '("delta" "executing" "update")) "running")
   ((equal stage "result") (if is-error "error" "success"))
   ((and (stringp stage) (not (string-empty-p stage))) stage)
   (t "running")))

(defun psi-emacs--tool-next-accumulated-text (row stage text arguments)
  "Return next accumulated body text from ROW and event payload.

`tool/executing` updates mutate header metadata and may update body output,
when ARGUMENTS is present. `tool/update` and `tool/result` replace body text
with latest snapshot semantics."
  (let* ((previous (or (plist-get row :accumulated-text) ""))
         (next (or text ""))
         (has-next (not (string-empty-p next)))
         (arguments-event? (stringp arguments)))
    (cond
     ((member stage '("update" "result"))
      (if has-next next previous))
     ((and (equal stage "delta") arguments-event?)
      previous)
     (has-next
      (concat previous next))
     (t previous))))

(defun psi-emacs--tool-row-live-markers-p (start end)
  "Return non-nil when START/END tool row markers are live."
  (and (markerp start)
       (markerp end)
       (marker-buffer start)
       (marker-buffer end)))

(defun psi-emacs--tool-row-restore-markers (tool-id row)
  "Return ROW with restored :start/:end markers for TOOL-ID when recoverable.

When ROW markers drifted or were lost, recover bounds from region identity
properties and recreate live markers. Returns updated row plist or ROW."
  (let ((start (plist-get row :start))
        (end (plist-get row :end)))
    (if (psi-emacs--tool-row-live-markers-p start end)
        row
      (if-let ((bounds (psi-emacs--region-bounds 'tool-row tool-id)))
          (let ((start* (copy-marker (car bounds) nil))
                (end* (copy-marker (cdr bounds) nil)))
            (set-marker-insertion-type end* nil)
            (plist-put (plist-put row :start start*) :end end*))
        row))))

(defun psi-emacs--sync-tool-row-region (tool-id start end)
  "Register tool row region identity for TOOL-ID over START..END."
  (when tool-id
    (psi-emacs--region-register 'tool-row tool-id start end)))

(defun psi-emacs--projection-start-position ()
  "Return projection block start position, or nil when absent."
  (when-let ((bounds (and psi-emacs--state
                          (psi-emacs--region-bounds 'projection 'main))))
    (car bounds)))

(defun psi-emacs--tool-row-safe-end-position (end-marker)
  "Return safe end position for END-MARKER that never crosses projection start."
  (let ((end-pos (and (markerp end-marker)
                      (marker-buffer end-marker)
                      (marker-position end-marker)))
        (projection-start (psi-emacs--projection-start-position)))
    (cond
     ((null end-pos) nil)
     ((and (numberp projection-start)
           (> end-pos projection-start))
      projection-start)
     (t end-pos))))

(defun psi-emacs--tool-status-face (status)
  "Return display face symbol for tool STATUS label."
  (pcase status
    ("pending" 'psi-emacs-tool-pending-face)
    ("running" 'psi-emacs-tool-running-face)
    ("success" 'psi-emacs-tool-success-face)
    ("error" 'psi-emacs-tool-error-face)
    (_ 'shadow)))

(defun psi-emacs--tool-row-header-string (tool-summary status)
  "Build collapsed header string from TOOL-SUMMARY and STATUS."
  (concat (propertize (or tool-summary "")
                      'face 'psi-emacs-tool-call-face
                      'font-lock-face 'psi-emacs-tool-call-face)
          (propertize " "
                      'face 'default
                      'font-lock-face 'default)
          (let ((status-face (psi-emacs--tool-status-face status)))
            (propertize (or status "")
                        'face status-face
                        'font-lock-face status-face))
          (propertize "\n"
                      'face 'default
                      'font-lock-face 'default)))

(defun psi-emacs--tool-body-string (text)
  "Return TEXT with ANSI faces applied and explicit de-emphasized baseline.

Unstyled characters get `psi-emacs-tool-output-face' so header/status faces
never stick to the tool body during rerenders or toggles. ANSI-colored spans
keep their original face properties."
  (let* ((body (copy-sequence (psi-emacs--ansi-to-face (or text ""))))
         (len (length body)))
    (dotimes (i len)
      (when (and (null (get-text-property i 'face body))
                 (null (get-text-property i 'font-lock-face body)))
        (add-text-properties i (1+ i)
                             '(face psi-emacs-tool-output-face
                               font-lock-face psi-emacs-tool-output-face)
                             body)))
    body))

(defun psi-emacs--tool-row-string (tool-summary status text)
  "Build expanded tool row string from TOOL-SUMMARY STATUS and TEXT.

The header stays on its own line and any tool output renders below it so
header/status faces do not bleed into the body when toggled open. ANSI
sequences in TEXT are converted to Emacs faces."
  (let* ((header (psi-emacs--tool-row-header-string tool-summary status))
         (body-text (or text "")))
    (if (string-empty-p body-text)
        header
      (let ((body (psi-emacs--tool-body-string body-text)))
        (concat header
                body
                (if (string-suffix-p "\n" body)
                    ""
                  (propertize "\n" 'face 'default 'font-lock-face 'default)))))))

(defun psi-emacs--render-tool-row (tool-summary status accumulated-text mode)
  "Render tool row using TOOL-SUMMARY STATUS ACCUMULATED-TEXT and MODE.

MODE nil is treated as collapsed (default)."
  (if (eq mode 'expanded)
      (psi-emacs--tool-row-string tool-summary status (or accumulated-text ""))
    (psi-emacs--tool-row-header-string tool-summary status)))

(defun psi-emacs--upsert-tool-row (tool-id stage text &optional tool-name arguments parsed-args is-error details)
  "Create or update TOOL-ID row for lifecycle STAGE.

TEXT represents tool execution output snapshots.
TOOL-NAME, ARGUMENTS, PARSED-ARGS, and DETAILS update display metadata.
Rows are rendered according to global tool-output-view-mode."
  (when (and psi-emacs--state tool-id)
    (let* ((follow-anchor (psi-emacs--draft-anchor-at-end-p))
           (rows (psi-emacs-state-tool-rows psi-emacs--state))
           (view-mode (psi-emacs-state-tool-output-view-mode psi-emacs--state))
           (row (psi-emacs--tool-row-restore-markers tool-id (gethash tool-id rows)))
           (start (plist-get row :start))
           (end (plist-get row :end))
           (tool-name* (or tool-name (plist-get row :tool-name)))
           (arguments* (if (stringp arguments)
                           arguments
                         (plist-get row :arguments)))
           (parsed-args* (if (null parsed-args)
                             (plist-get row :parsed-args)
                           parsed-args))
           (is-error* (if (null is-error)
                          (plist-get row :is-error)
                        is-error))
           (details* (if (null details)
                         (plist-get row :details)
                       details))
           (status (psi-emacs--tool-status-label stage is-error*))
           (accumulated (psi-emacs--tool-next-accumulated-text row stage text arguments))
           (tool-summary (psi-emacs--tool-summary tool-name* parsed-args* arguments* tool-id details*))
           (rendered (psi-emacs--render-tool-row tool-summary status accumulated view-mode)))
      (if (and (markerp start)
               (markerp end)
               (marker-buffer start)
               (marker-buffer end))
          (save-excursion
            (let* ((start-pos (marker-position start))
                   (end-pos (psi-emacs--tool-row-safe-end-position end)))
              (when (and (numberp start-pos)
                         (numberp end-pos)
                         (<= start-pos end-pos))
                (let ((inhibit-read-only t))
                  (goto-char start-pos)
                  (delete-region start-pos end-pos)
                  (insert rendered)
                  (set-marker end (point)))
                ;; Keep row boundary stable when projection is inserted at row end.
                (set-marker-insertion-type end nil)
                ;; If the assistant start marker drifted to the tool row start
                ;; (due to the delete above collapsing markers at that position),
                ;; advance it to the tool row end so the assistant range still
                ;; covers only the assistant line and not this tool row.
                (let ((as (psi-emacs--assistant-start-marker)))
                  (when (and as (= (marker-position as) start-pos))
                    (set-marker as (point))))
                ;; Fix any other tool row start markers that collapsed to
                ;; start-pos due to the delete above.  When two tool rows are
                ;; adjacent the next row's :start marker shares the same buffer
                ;; position as this row's :end marker.  delete-region collapses
                ;; both to start-pos, so the subsequent row's start drifts into
                ;; this row's range.  Advance any such markers to point (= end
                ;; of this row's new content) to restore correct boundaries.
                (maphash (lambda (other-id other-row)
                           (unless (equal other-id tool-id)
                             (let ((other-start (plist-get other-row :start)))
                               (when (and (markerp other-start)
                                          (marker-buffer other-start)
                                          (= (marker-position other-start) start-pos))
                                 (set-marker other-start (point))))))
                         rows)))
            (let ((updated (list :id tool-id
                                 :tool-name tool-name*
                                 :arguments arguments*
                                 :parsed-args parsed-args*
                                 :details details*
                                 :is-error is-error*
                                 :stage stage
                                 :status status
                                 :text text
                                 :tool-summary tool-summary
                                 :accumulated-text accumulated
                                 :start start
                                 :end end)))
              (puthash tool-id updated rows)
              (psi-emacs--sync-tool-row-region tool-id start end)))
        (save-excursion
          ;; New tool rows must be inserted before the live assistant line (if
          ;; one exists) so that their markers never overlap the assistant range.
          ;; Overlapping markers cause delete-region on tool-row update to
          ;; accidentally swallow the assistant line, and vice-versa.
          ;; When no assistant range is live, fall back to the normal
          ;; transcript-append position (just above the input separator).
          (let* ((assistant-start-marker (psi-emacs--assistant-start-marker))
                 (insert-pos (and assistant-start-marker
                                  (marker-position assistant-start-marker))))
            (if insert-pos
                (progn
                  (goto-char insert-pos)
                  (unless (or (bobp) (eq (char-before) ?\n))
                    (let ((inhibit-read-only t)) (insert "\n")))
                  (let ((new-start (copy-marker (point) nil))
                        (new-end   (copy-marker (point) nil)))
                    (let ((inhibit-read-only t))
                      (insert rendered))
                    (set-marker new-end (point))
                    (set-marker-insertion-type new-end nil)
                    ;; The assistant start marker is nil-type so it stayed at
                    ;; insert-pos while we inserted the tool row before it.
                    ;; Advance it to point (= end of tool row = start of
                    ;; assistant line) so the assistant range covers only the
                    ;; assistant line and not this new tool row.
                    (set-marker assistant-start-marker (point))
                    ;; The assistant end marker is also nil-type and stayed at
                    ;; insert-pos + A (where A = prior assistant content length).
                    ;; If the tool row is longer than the assistant content
                    ;; (R > A), end is now BEFORE start, making the range
                    ;; inverted.  delete-region on an inverted range swaps the
                    ;; bounds and deletes tool row content instead of assistant
                    ;; content, leaving stale assistant text in the buffer and
                    ;; corrupting the tool row.  Advance end to at least start
                    ;; so the range is always non-inverted.
                    (let ((ae (psi-emacs--assistant-end-marker)))
                      (when (and ae (< (marker-position ae) (point)))
                        (set-marker ae (point))))
                    (let ((new-row (list :id tool-id
                                         :tool-name tool-name*
                                         :arguments arguments*
                                         :parsed-args parsed-args*
                                         :details details*
                                         :is-error is-error*
                                         :stage stage
                                         :status status
                                         :text text
                                         :tool-summary tool-summary
                                         :accumulated-text accumulated
                                         :start new-start
                                         :end new-end)))
                      (puthash tool-id new-row rows)
                      (psi-emacs--sync-tool-row-region tool-id new-start new-end))))
              (psi-emacs--ensure-newline-before-append)
              (let ((new-start (copy-marker (point) nil))
                    ;; Keep insertion-type nil so projection/footer inserted at the
                    ;; row boundary stays outside this tool row range.
                    (new-end (copy-marker (point) nil)))
                (let ((inhibit-read-only t))
                  (insert rendered))
                (set-marker new-end (point))
                (set-marker-insertion-type new-end nil)
                (let ((new-row (list :id tool-id
                                     :tool-name tool-name*
                                     :arguments arguments*
                                     :parsed-args parsed-args*
                                     :details details*
                                     :is-error is-error*
                                     :stage stage
                                     :status status
                                     :text text
                                     :tool-summary tool-summary
                                     :accumulated-text accumulated
                                     :start new-start
                                     :end new-end)))
                  (puthash tool-id new-row rows)
                  (psi-emacs--sync-tool-row-region tool-id new-start new-end)))))))
      (when follow-anchor
        (psi-emacs--set-draft-anchor-to-end)))))

(defun psi-emacs-toggle-tool-output-view ()
  "Toggle global tool-output view mode between collapsed and expanded.

In collapsed mode only the header line of each tool row is shown.
In expanded mode the full body output of each tool row is shown.
The toggle applies to all existing tool rows and future tool rows.
This command is valid even when no tool rows exist."
  (interactive)
  (when psi-emacs--state
    (let* ((current (psi-emacs-state-tool-output-view-mode psi-emacs--state))
           (new-mode (if (eq current 'expanded) 'collapsed 'expanded))
           (rows (psi-emacs-state-tool-rows psi-emacs--state)))
      (setf (psi-emacs-state-tool-output-view-mode psi-emacs--state) new-mode)
      ;; Re-render all existing tool rows with the new mode.
      ;; Bind `inhibit-read-only' across the whole pass so transcript/footer
      ;; protection never interferes with these programmatic replacements.
      (let ((inhibit-read-only t))
        (maphash (lambda (tool-id row)
                   (let* ((row* (psi-emacs--tool-row-restore-markers tool-id row))
                          (accumulated (plist-get row* :accumulated-text))
                          (start (plist-get row* :start))
                          (end (plist-get row* :end))
                          (tool-summary (or (plist-get row* :tool-summary)
                                            (psi-emacs--tool-summary
                                             (plist-get row* :tool-name)
                                             (plist-get row* :parsed-args)
                                             (plist-get row* :arguments)
                                             tool-id
                                             (plist-get row* :details))))
                          (status (or (plist-get row* :status)
                                      (psi-emacs--tool-status-label
                                       (plist-get row* :stage)
                                       (plist-get row* :is-error)))))
                     (when (and (markerp start)
                                (markerp end)
                                (marker-buffer start)
                                (marker-buffer end))
                       (let ((rendered (psi-emacs--render-tool-row
                                        tool-summary status accumulated new-mode)))
                         (save-excursion
                           (let* ((start-pos (marker-position start))
                                  (end-pos (psi-emacs--tool-row-safe-end-position end)))
                             (when (and (numberp start-pos)
                                        (numberp end-pos)
                                        (<= start-pos end-pos))
                               (goto-char start-pos)
                               (delete-region start-pos end-pos)
                               (insert rendered)
                               (set-marker end (point))
                               ;; Keep row boundary stable with projection/footer.
                               (set-marker-insertion-type end nil)
                               ;; Adjacent row boundaries can collapse to the deleted
                               ;; start position during rerender. Advance any other row
                               ;; start markers that landed there to this row's new end
                               ;; so toggle passes cannot make subsequent rows disappear.
                               (maphash (lambda (other-id other-row)
                                          (unless (equal other-id tool-id)
                                            (let ((other-start (plist-get other-row :start)))
                                              (when (and (markerp other-start)
                                                         (marker-buffer other-start)
                                                         (= (marker-position other-start) start-pos))
                                                (set-marker other-start (point))))))
                                        rows)
                               (psi-emacs--mark-region-read-only start-pos (point))
                               (psi-emacs--sync-tool-row-region tool-id start end))))))))
                 rows))
      (psi-emacs--refresh-header-line))))

(provide 'psi-tool-rows)

;;; psi-tool-rows.el ends here
